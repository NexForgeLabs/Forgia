/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.runtimeproof.RuntimeProof;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.jetbrains.annotations.Nullable;

/** Core-only fresh manifest preparation, subordinate to the original phase and ownership resources. */
final class CoreModPreparation {
    private static final ThreadLocal<List<Object>> PROBE_READ = new ThreadLocal<>();
    private final RegionThreadingEngine engine;
    private final List<ProtectedRoot> roots = new ArrayList<>();

    private final java.util.Set<String> refusals = new java.util.HashSet<>();

    private void refuse(String reason) {
        if (this.refusals.size() < 16 && this.refusals.add(reason)) System.err.println("FORGIA_MOD_PREPARATION_REFUSAL " + reason);
    }

    CoreModPreparation(RegionThreadingEngine engine) {
        this.engine = engine;
    }

    @Nullable
    Manifest prepare(RegionBlockEntityTickAdmission admission, @Nullable FurnaceResourcePreparation phaseResources) {
        if (admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                || !CoreModTickerVerifier.available() || !LevelChunk.exactCoreBlockEntityBinding(admission.ticker())
                || !admission.isCurrent() || admission.level().getServer() == null
                || !admission.level().getServer().isSameThread() || TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled())
            return null;
        final BlockEntity source = LevelChunk.actualBlockEntitySource(admission.ticker());
        final var function = LevelChunk.actualBlockEntityFunction(admission.ticker());
        if (source == null) return null;
        final var frame = LevelChunk.captureCoreTickerFrame(admission.ticker());
        final var capture = admission.binding().core();
        if (frame == null || capture == null || frame.level() != admission.level() || frame.source() != source
                || frame.function() != function || frame.delegate() != admission.binding().delegate()
                || capture.level() != admission.level() || capture.wrapper() != admission.ticker()
                || capture.delegate() != frame.delegate() || capture.version() != frame.version()) {
            this.refuse("original Core binding capture");
            return null;
        }
        final var code = CoreModTickerVerifier.verify(source, function);
        if (code == null) {
            this.refuse("actual installed Mod function closure");
            return null;
        }
        final var pos = admission.position();
        final LevelChunk chunk = admission.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || chunk.getClass() != LevelChunk.class || !chunk.isLoaded()
                || chunk.getBlockEntities().getClass() != Object2ObjectOpenHashMap.class || !LevelChunk.furnaceWorkMatchesChunk(admission.ticker(), chunk) || frame.chunk() != chunk
                || chunk.getBlockEntities().get(pos) != source || source.getLevel() != admission.level() || source.isRemoved()
                || source.getBlockPos().getClass() != BlockPos.class || !pos.equals(source.getBlockPos()) || source.getType() == null) {
            this.refuse("source/chunk identity position=" + source.getBlockPos().getClass().getName()
                    + " map=" + (chunk == null ? "null" : chunk.getBlockEntities().getClass().getName()));
            return null;
        }
        final BlockState state = CoreModContextVerifier.capture(admission.level(), source, chunk);
        if (state == null) return null;
        final RegionState home = this.engine.modHome(admission.level(), pos);
        if (home == null || home.isDead()) return null;
        final List<Object> storage = List.of(source, admission.ticker(), admission.binding().delegate(), source.getBlockPos());
        for (final Object root : storage) if (!this.protect(root, admission, home)) return null;
        final Integer value;
        if (PROBE_READ.get() != null) throw new IllegalStateException("Nested Mod storage verification");
        PROBE_READ.set(storage);
        try {
            value = code.value(source);
        } finally {
            PROBE_READ.remove();
        }
        if (value == null) return null;
        final Object resourceHome = phaseResources == null ? home : phaseResources.homeFor(pos);
        return new Manifest(admission, source, chunk, home, resourceHome, code, value, source.getType(), state, storage, frame);
    }

    private boolean protect(Object source, RegionBlockEntityTickAdmission admission, RegionState home) {
        this.roots.removeIf(root -> root.source.get() == null);
        for (final var root : this.roots) {
            if (root.source.get() != source) continue;
            if (root.check == null) return source.getClass() == BlockPos.class;
            if (root.check.level.get() != admission.level() || root.check.engine.get() != this.engine) return false;
            root.check.home = new WeakReference<>(home);
            return true;
        }
        if (this.roots.size() >= 4096) return false;
        if (source.getClass() == BlockPos.class) {
            // Exact Core scalar coordinates are frozen by the VM, including reflection/Unsafe/JNI.
            // This establishes immutability instead of instrumenting every unrelated coordinate read.
            if (!RuntimeProof.protectImmutableValue(source)) return false;
            this.roots.add(new ProtectedRoot(new WeakReference<>(source), null));
            return true;
        }
        final var check = new AccessAuthority(this.engine, admission.level(), home);
        if (!RuntimeProof.protect(source, check)) return false;
        this.roots.add(new ProtectedRoot(new WeakReference<>(source), check));
        return true;
    }

    private record ProtectedRoot(WeakReference<Object> source, @Nullable AccessAuthority check) {}

    /** No mutable ownership state: every access consults the actual existing Scope/Region lifecycle. */
    private static final class AccessAuthority implements RuntimeProof.AccessCheck {
        private final WeakReference<RegionThreadingEngine> engine;
        private final WeakReference<ServerLevel> level;
        private final WeakReference<Thread> coordinator;
        private volatile WeakReference<RegionState> home;

        private AccessAuthority(RegionThreadingEngine engine, ServerLevel level, RegionState home) {
            this.engine = new WeakReference<>(engine);
            this.level = new WeakReference<>(level);
            this.coordinator = new WeakReference<>(Thread.currentThread());
            this.home = new WeakReference<>(home);
        }

        @Override
        public boolean allowed(Object root, boolean write) {
            final var current = this.engine.get();
            final var level = this.level.get();
            if (current == null || level == null) {
                // Interception outlives a weak Engine/old Level. Ordinary serial access on the exact
                // original server thread must survive engine removal; a live authority keeps its own
                // phase/level/home strongly reachable and can never use this idle-only branch.
                final var region = this.home.get();
                return !BlockEntityResourceOwnership.Scope.active() && Thread.currentThread() == this.coordinator.get()
                        && (region == null || !region.isRunning());
            }
            if (!write && BlockEntityExecutionEngine.isAdmissionRead(level, root)) return true;
            if (FurnaceWorkScope.active() && BlockEntityExecutionEngine.currentMode() == net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter.Mode.RESOURCE_SCOPED) {
                if (!write && root instanceof LevelChunk loaded && FurnaceWorkScope.checkChunk(loaded, false)) return true;
                if (!write && root instanceof BlockPos position) {
                    FurnaceWorkScope.checkReference(level, position);
                    return true;
                }
                if (FurnaceWorkScope.ownsWrapperReference(level, root, write)) return true;
            }
            if (BlockEntityResourceOwnership.Scope.active()) {
                BlockEntityResourceOwnership.Scope.require(level, root, write ? BlockEntityResourceOwnership.Access.WRITE : BlockEntityResourceOwnership.Access.READ);
                return true;
            }
            if (!write && level.getServer() != null && level.getServer().isSameThread()) {
                final var reads = PROBE_READ.get();
                if (reads != null) for (final Object readable : reads) if (readable == root) return true;
            }
            final var region = this.home.get();
            // This is the existing coarse ownership authority, not a new native permission. A queued
            // or fine-grained region has no owner and therefore cannot pass through this branch.
            if (region != null && region.isOwnedByCurrentThread()) return true;
            return level.getServer() != null && level.getServer().isSameThread() && !RegionWorkerScope.isNativeWorkerThread()
                    && (region == null || !region.isRunning());
        }
    }

    record Manifest(RegionBlockEntityTickAdmission admission, BlockEntity source, LevelChunk chunk,
            RegionState home, Object resourceHome, CoreModTickerVerifier.Code code, int counter,
            net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockState state, List<Object> storage,
            LevelChunk.CapturedTickerBinding frame) implements BoundBlockEntityInvocation {
        Manifest {
            storage = List.copyOf(storage);
        }

        /** Held-region coordinator proof. No caller-supplied predicate or captured success is accepted. */
        boolean fresh(RegionThreadingEngine engine) {
            if (PROBE_READ.get() != null) throw new IllegalStateException("Nested Mod binding verification");
            PROBE_READ.set(storage);
            try {
                return this.workerCurrent() && chunk.isLoaded() && chunk.getBlockEntities().getClass() == Object2ObjectOpenHashMap.class
                        && chunk.getBlockEntities().get(admission.position()) == source
                        && engine.modHome(admission.level(), admission.position()) == home && !home.isDead();
            } finally {
                PROBE_READ.remove();
            }
        }

        /** Reads only already intercepted exact roots and immutable VM evidence, never world maps/callbacks. */
        public boolean workerCurrent() {
            return frame.current() && frame.typeName().equals(admission.type())
                    && LevelChunk.actualBlockEntitySource(admission.ticker()) == source
                    && LevelChunk.actualBlockEntityFunction(admission.ticker()) == code.actualFunction()
                    && !source.isRemoved() && source.getLevel() == admission.level() && source.getType() == type && source.getBlockState() == state
                    && source.getBlockPos() == storage.get(3)
                    && source.getBlockPos().getClass() == BlockPos.class && admission.position().equals(source.getBlockPos())
                    && java.util.Objects.equals(code.value(source), counter);
        }

        boolean eligible(BlockEntityExecutionBatch batch) {
            if (PROBE_READ.get() != null) throw new IllegalStateException("Nested Mod eligibility verification");
            PROBE_READ.set(storage);
            try {
                return batch.eligibleNow(admission);
            } finally {
                PROBE_READ.remove();
            }
        }

        BlockEntityResourceOwnership.Claims claims(RegionThreadingEngine engine) {
            final var claims = BlockEntityResourceOwnership.Claims.builder(admission.level(), resourceHome);
            claims.claim(new BlockEntityResourceOwnership.Binding(new BlockEntityResourceOwnership.Identity(admission.level(), source), resourceHome,
                    () -> fresh(engine), admission.position()), BlockEntityResourceOwnership.Access.WRITE);
            claims.claim(new BlockEntityResourceOwnership.Binding(new BlockEntityResourceOwnership.Identity(admission.level(), admission.ticker()), resourceHome,
                    () -> fresh(engine), null), admission.ticker() == admission.binding().delegate() ? BlockEntityResourceOwnership.Access.WRITE : BlockEntityResourceOwnership.Access.READ);
            if (admission.ticker() != admission.binding().delegate()) {
                claims.claim(new BlockEntityResourceOwnership.Binding(new BlockEntityResourceOwnership.Identity(admission.level(), admission.binding().delegate()), resourceHome,
                        () -> fresh(engine), null), BlockEntityResourceOwnership.Access.WRITE);
            }
            claims.claim(new BlockEntityResourceOwnership.Binding(new BlockEntityResourceOwnership.Identity(admission.level(), source.getBlockPos()), resourceHome,
                    () -> fresh(engine), null), BlockEntityResourceOwnership.Access.READ);
            return claims.build();
        }
    }
}
