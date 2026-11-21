/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Original wrapper context shared by VM proofs and trusted adapters, under the original entered Lease. */
@ApiStatus.Internal
public final class CoreModWorkScope implements AutoCloseable {
    private static final ThreadLocal<CoreModWorkScope> CURRENT = new ThreadLocal<>();
    private final BoundBlockEntityInvocation manifest;
    private boolean taskStarted;
    private boolean closed;

    private CoreModWorkScope(BoundBlockEntityInvocation manifest) {
        if (CURRENT.get() != null || !BlockEntityResourceOwnership.Scope.active()
                || BlockEntityExecutionEngine.currentMode() != net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter.Mode.RESOURCE_SCOPED)
            throw new IllegalStateException("Invalid Mod resource scope");
        this.manifest = manifest;
        CURRENT.set(this);
    }

    static CoreModWorkScope open(CoreModPreparation.Manifest manifest) {
        return new CoreModWorkScope(manifest);
    }

    static CoreModWorkScope open(BoundBlockEntityInvocation manifest) {
        return new CoreModWorkScope(manifest);
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static boolean admitted(ServerLevel level, @Nullable TickingBlockEntity ticker, BlockPos position) {
        final var scope = CURRENT.get();
        if (scope == null) return false;
        final var admission = scope.manifest.admission();
        if (scope.closed || level != admission.level()
                || ticker != admission.ticker() && ticker != admission.binding().delegate()
                || !position.equals(admission.position()) || !scope.manifest.workerCurrent())
            throw new IllegalStateException("Mod wrapper lost its exact pre-start proof");
        return true;
    }

    public static boolean admitted(@Nullable TickingBlockEntity ticker, BlockPos position) {
        final var scope = CURRENT.get();
        return scope != null && admitted(scope.manifest.admission().level(), ticker, position);
    }

    @Nullable
    public static net.minecraft.world.level.Level level(TickingBlockEntity ticker, BlockEntity source) {
        final var scope = requireFrame(ticker, source);
        return scope == null ? null : scope.manifest.admission().level();
    }

    @Nullable
    public static ProfilerFiller profiler(TickingBlockEntity ticker, BlockEntity source) {
        final var scope = requireFrame(ticker, source);
        return scope == null ? null : InactiveProfiler.INSTANCE;
    }

    @Nullable
    public static BlockState state(TickingBlockEntity ticker, BlockEntity source) {
        final var scope = requireFrame(ticker, source);
        return scope == null ? null : scope.manifest.state();
    }

    public static boolean prevalidated(TickingBlockEntity ticker, BlockEntity source, BlockState state) {
        final var scope = requireFrame(ticker, source);
        if (scope == null) return false;
        if (scope.manifest.state() != state) throw new IllegalStateException("Foreign Mod wrapper state");
        return true;
    }

    @Nullable
    private static CoreModWorkScope requireFrame(TickingBlockEntity ticker, BlockEntity source) {
        final var scope = CURRENT.get();
        if (scope == null) return null;
        final var admission = scope.manifest.admission();
        if (scope.closed || source != scope.manifest.source()
                || ticker != admission.ticker() && ticker != admission.binding().delegate()
                || !scope.manifest.workerCurrent())
            throw new IllegalStateException("Original Mod wrapper context changed");
        return scope;
    }

    public static boolean runTask(ServerLevel level, BlockPos position, Runnable task) {
        final var scope = CURRENT.get();
        if (scope == null) return false;
        final var admission = scope.manifest.admission();
        if (scope.closed || level != admission.level() || !position.equals(admission.position()) || scope.taskStarted
                || !scope.manifest.workerCurrent())
            throw new IllegalStateException("Mod work attempted an unrelated, repeated or changed body");
        scope.taskStarted = true;
        // This is the original Core wrapper's Runnable invoking the unchanged original third-party
        // function in full. No part of that function was executed during context preparation.
        task.run();
        return true;
    }

    @Override
    public void close() {
        if (this.closed || CURRENT.get() != this) throw new IllegalStateException("Mod wrapper scope closed out of order");
        this.closed = true;
        CURRENT.remove();
    }
}
