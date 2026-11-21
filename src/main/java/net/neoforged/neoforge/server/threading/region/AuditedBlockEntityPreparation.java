/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter.Access;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter.Proof;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/**
 * Generic bridge from a registered audited original-tick contract to the existing canonical Native
 * prefix. Target classes, fingerprints, state predicates and footprints all belong to the adapter.
 * The original batch/kernel/region owner remains the only execution authority.
 */
final class AuditedBlockEntityPreparation {
    private static final String CORE_STATUS = inspectCoreMethods();
    private static final boolean CORE_CURRENT = "ok".equals(CORE_STATUS);

    private AuditedBlockEntityPreparation() {}

    static String coreStatus() {
        return CORE_STATUS;
    }

    static boolean isCoreCurrent() {
        return CORE_CURRENT;
    }

    /** Collection only selects frozen metadata; no adapter callback runs before batch publication. */
    static boolean hasCandidate(RegionBlockEntityTickAdmission admission) {
        if (!CORE_CURRENT || admission.decision() != RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                || TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled()
                || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(admission.type()))
            return false;
        final var binding = ParallelAdapterRuntime.auditedBlockEntityTickBinding(admission);
        if (binding == null || !binding.verified()) return false;
        final var frame = LevelChunk.captureCoreTickerFrame(admission.ticker());
        return frame != null && frame.level() == admission.level() && frame.function() != null
                && binding.audit().sourceClasses().contains(frame.source().getClass())
                && binding.audit().tickerClasses().contains(frame.function().getClass());
    }

    @Nullable
    static Candidate prepare(RegionBlockEntityTickAdmission admission) {
        if (!hasCandidate(admission)) return null;
        final var binding = ParallelAdapterRuntime.auditedBlockEntityTickBinding(admission);
        final var frame = LevelChunk.captureCoreTickerFrame(admission.ticker());
        if (binding == null || binding.adapter() instanceof net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter
                || frame == null || !current(admission, frame))
            return null;
        final var proof = binding.adapter().inspect(frame.source(), frame.function().getClass());
        final var storage = footprint(frame.source(), proof);
        if (storage == null || !current(admission, frame)
                || ParallelAdapterRuntime.auditedBlockEntityTickBinding(admission) != binding)
            return null;
        return new Candidate(admission, frame.source(), frame, binding, storage);
    }

    @Nullable
    static Map<Object, Access> footprint(BlockEntity source, @Nullable Proof proof) {
        if (proof == null) return null;
        final Map<Object, Access> storage = new IdentityHashMap<>();
        for (final var entry : proof.storage()) {
            final Object identity = entry.identity();
            // Region ownership does not authorize a whole world, server, or arbitrary chunk resource.
            if (identity instanceof Level || identity instanceof ChunkAccess || identity instanceof MinecraftServer
                    || identity instanceof net.minecraft.world.entity.Entity || identity instanceof BlockEntity blockEntity && blockEntity != source
                    || storage.put(identity, entry.access()) != null)
                return null;
        }
        return storage.get(source) == Access.WRITE ? storage : null;
    }

    static boolean current(RegionBlockEntityTickAdmission admission, LevelChunk.CapturedTickerBinding frame) {
        final var source = frame.source();
        final var position = admission.position();
        return CORE_CURRENT && admission.isCurrent() && frame.current() && !source.isRemoved()
                && source.getLevel() == admission.level() && position.equals(source.getBlockPos())
                && frame.chunk().isLoaded() && frame.chunk().getBlockEntities().get(position) == source
                && frame.chunk().getBlockState(position) == source.getBlockState()
                && source.getType().isValid(source.getBlockState())
                && admission.level().getProfiler() == InactiveProfiler.INSTANCE;
    }

    record Candidate(RegionBlockEntityTickAdmission admission, BlockEntity source, LevelChunk.CapturedTickerBinding frame,
            ParallelAdapterRuntime.AuditedTickBinding binding, Map<Object, Access> storage) implements NativeBlockEntityPreparation.Candidate {
        @Override
        public boolean fresh() {
            if (!hasCandidate(this.admission) || ParallelAdapterRuntime.auditedBlockEntityTickBinding(this.admission) != this.binding
                    || !current(this.admission, this.frame))
                return false;
            final var proof = this.binding.adapter().inspect(this.source, this.frame.function().getClass());
            if (proof == null || proof.storage().size() != this.storage.size()) return false;
            final var seen = new IdentityHashMap<Object, Boolean>();
            for (final var entry : proof.storage()) {
                if (this.storage.get(entry.identity()) != entry.access() || seen.put(entry.identity(), Boolean.TRUE) != null) return false;
            }
            return current(this.admission, this.frame)
                    && ParallelAdapterRuntime.auditedBlockEntityTickBinding(this.admission) == this.binding;
        }

        @Override
        public boolean conflictsWith(NativeBlockEntityPreparation.Candidate other) {
            if (other instanceof Candidate audited) {
                for (final var entry : this.storage.entrySet()) {
                    final var access = audited.storage.get(entry.getKey());
                    if (access != null && (entry.getValue() == Access.WRITE || access == Access.WRITE)) return true;
                }
                return false;
            }
            // The original no-callback native branch mutates its own source only.
            return this.storage.containsKey(other.source());
        }
    }

    private static String inspectCoreMethods() {
        try {
            final Map<Class<?>, Set<String>> reached = Map.of(
                    BlockEntity.class, Set.of("getLevel", "hasLevel", "isRemoved", "getBlockPos", "getType", "getBlockState"),
                    Level.class, Set.of("getProfiler"), ServerLevel.class, Set.of("getProfiler"),
                    LevelChunk.class, Set.of("getBlockState", "isTicking", "captureCoreTickerFrame"),
                    net.minecraft.world.level.block.entity.BlockEntityType.class, Set.of("isValid"),
                    TimeTracker.class, Set.of("trackStart", "trackEnd", "isEnabled"));
            for (final var entry : reached.entrySet()) {
                for (final var method : entry.getKey().getDeclaredMethods()) {
                    if (entry.getValue().contains(method.getName())) {
                        final MixinMerged merged = method.getAnnotation(MixinMerged.class);
                        if (merged != null) {
                            return "blocked(" + entry.getKey().getSimpleName() + "#" + method.getName() + " by " + merged.mixin() + ")";
                        }
                    }
                }
            }
            for (final Class<?> nested : LevelChunk.class.getDeclaredClasses()) {
                if (!Set.of("BoundTickingBlockEntity", "RebindableTickingBlockEntityWrapper").contains(nested.getSimpleName())) continue;
                for (final var method : nested.getDeclaredMethods()) {
                    final MixinMerged merged = method.getAnnotation(MixinMerged.class);
                    if (merged != null && !isAllowedCoreTickerMixin(merged.mixin())) {
                        return "blocked(" + nested.getSimpleName() + "#" + method.getName() + " by " + merged.mixin() + ")";
                    }
                }
            }
            return "ok";
        } catch (RuntimeException | LinkageError unavailable) {
            return "error(" + unavailable.getClass().getSimpleName() + ")";
        }
    }

    private static boolean isAllowedCoreTickerMixin(String mixinName) {
        if ("com.klnon.sablepanel.mixin.BoundTickingBlockEntityMixin".equals(mixinName)) {
            return true;
        }
        if ("com.illusivesoulworks.polymorph.mixin.core.MixinLevelChunk".equals(mixinName)) {
            try {
                Class.forName("com.forgia.polymorphcompat.PolymorphCompatMod", false, AuditedBlockEntityPreparation.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
                return false;
            }
        }
        return false;
    }
}
