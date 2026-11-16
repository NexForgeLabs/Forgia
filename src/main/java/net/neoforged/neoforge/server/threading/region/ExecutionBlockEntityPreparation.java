/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter.Access;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import org.jetbrains.annotations.Nullable;

/** One canonical phase's bounded preparation and alias accounting for trusted execution adapters. */
final class ExecutionBlockEntityPreparation {
    private final RegionThreadingEngine engine;
    private final Map<Object, RegionCoordinate> writers = new IdentityHashMap<>();
    private final java.util.function.IntPredicate budget;

    ExecutionBlockEntityPreparation(RegionThreadingEngine engine, java.util.function.IntPredicate budget) {
        this.engine = engine;
        this.budget = budget;
    }

    @Nullable
    Manifest prepare(RegionBlockEntityTickAdmission admission) {
        if (!AuditedBlockEntityPreparation.hasCandidate(admission)) return null;
        final var binding = ParallelAdapterRuntime.auditedBlockEntityTickBinding(admission);
        final var frame = LevelChunk.captureCoreTickerFrame(admission.ticker());
        if (binding == null || !(binding.adapter() instanceof BlockEntityExecutionAdapter)
                || frame == null || !AuditedBlockEntityPreparation.current(admission, frame))
            return null;
        final var home = this.engine.modHome(admission.level(), admission.position());
        if (home == null || home.isDead()) return null;
        final var result = ParallelAdapterRuntime.inspectBlockEntityExecution(admission, binding, frame.source(), frame.function().getClass());
        final var roots = AuditedBlockEntityPreparation.footprint(frame.source(), result.proof());
        if (roots == null || !this.charge(result) || !dependencies(result, roots)
                || !AuditedBlockEntityPreparation.current(admission, frame)
                || ParallelAdapterRuntime.auditedBlockEntityTickBinding(admission) != binding)
            return null;
        // A writable root cannot acquire a different home in a later prefix/window. Shared READ roots
        // have no writable home; READ/WRITE conflicts are still handled independently for each wave.
        for (final var entry : roots.entrySet()) {
            final var writer = this.writers.get(entry.getKey());
            if (writer != null && !writer.equals(home.coordinate())) return null;
            if (entry.getValue() == Access.WRITE) this.writers.put(entry.getKey(), home.coordinate());
        }
        return new Manifest(this, admission, frame.source(), frame, binding, home, frame.source().getBlockState(), roots, result);
    }

    private boolean charge(BlockEntityExecutionAdapter.Inspection inspection) {
        if (inspection.proof() == null) return false;
        return this.budget.test(inspection.proof().storage().size() + inspection.dependencies().size());
    }

    private static boolean dependencies(BlockEntityExecutionAdapter.Inspection inspection, Map<Object, Access> roots) {
        final var seen = new IdentityHashMap<Object, Boolean>();
        for (final var dependency : inspection.dependencies()) {
            if (!roots.containsKey(dependency.identity()) || seen.put(dependency.identity(), Boolean.TRUE) != null) return false;
        }
        return true;
    }

    record Manifest(ExecutionBlockEntityPreparation owner, RegionBlockEntityTickAdmission admission, BlockEntity source,
            LevelChunk.CapturedTickerBinding frame, ParallelAdapterRuntime.AuditedTickBinding binding, RegionState home,
            BlockState state, Map<Object, Access> roots, BlockEntityExecutionAdapter.Inspection inspection) implements BoundBlockEntityInvocation {
        boolean fresh() {
            if (!this.workerCurrent() || !AuditedBlockEntityPreparation.current(this.admission, this.frame)) return false;
            final var current = ParallelAdapterRuntime.inspectBlockEntityExecution(this.admission, this.binding, this.source, this.frame.function().getClass());
            if (!this.owner.charge(current)) return false;
            final var storage = AuditedBlockEntityPreparation.footprint(this.source, current.proof());
            if (storage == null || storage.size() != this.roots.size() || !dependencies(current, storage)
                    || current.dependencies().size() != this.inspection.dependencies().size())
                return false;
            for (final var entry : storage.entrySet()) if (this.roots.get(entry.getKey()) != entry.getValue()) return false;
            final var revisions = new IdentityHashMap<Object, Long>();
            for (final var dependency : this.inspection.dependencies()) revisions.put(dependency.identity(), dependency.revision());
            for (final var dependency : current.dependencies()) {
                final var revision = revisions.get(dependency.identity());
                if (revision == null || revision.longValue() != dependency.revision()) return false;
            }
            return this.workerCurrent() && AuditedBlockEntityPreparation.current(this.admission, this.frame);
        }

        @Override
        public boolean workerCurrent() {
            // Exact wrapper fields and source-owned metadata only: no live chunk/world maps or mod
            // validators on workers. Resource exclusivity was established before any body starts.
            return this.frame.current() && this.source.getLevel() == this.admission.level() && !this.source.isRemoved()
                    && this.source.getBlockState() == this.state && this.source.getBlockPos().equals(this.admission.position())
                    && ParallelAdapterRuntime.auditedBlockEntityTickBinding(this.admission) == this.binding;
        }

        BlockEntityResourceOwnership.Claims claims() {
            final var builder = BlockEntityResourceOwnership.Claims.builder(this.admission.level(), this.home);
            for (final var entry : this.roots.entrySet()) {
                builder.claim(new BlockEntityResourceOwnership.Binding(
                        new BlockEntityResourceOwnership.Identity(this.admission.level(), entry.getKey()),
                        entry.getValue() == Access.WRITE ? this.home : null, this::workerCurrent,
                        entry.getKey() == this.source ? this.admission.position() : null),
                        entry.getValue() == Access.WRITE ? BlockEntityResourceOwnership.Access.WRITE : BlockEntityResourceOwnership.Access.READ);
            }
            return builder.build();
        }
    }
}
