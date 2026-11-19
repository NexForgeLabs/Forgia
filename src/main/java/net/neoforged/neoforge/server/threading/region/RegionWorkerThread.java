/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;

/**
 * Dedicated worker thread subclass for {@link RegionWorkerPool}. Hot per-thread region scopes live
 * directly on this thread instance so both the server thread and native workers avoid
 * {@link ThreadLocal} map probes in tight tick and chunk-access paths.
 */
final class RegionWorkerThread extends Thread {
    boolean inWorkerScope;
    @Nullable
    RegionTickBatch tickBatch;
    @Nullable
    RegionDeferredBlockUpdates blockUpdates;
    @Nullable
    RegionEntityOwnershipScope entityOwnershipScope;
    long entityLeaseId;
    long nextEntityLeaseId;
    @Nullable
    RegionPlayerSpatialLease playerSpatialLease;
    @Nullable
    RegionPlayerStateScope playerStateScope;
    @Nullable
    RegionPlayerTickCoordinator.Plan playerTickInvocation;
    @Nullable
    Object poolRuntime;
    @Nullable
    ArrayList<Object> resourceCheckScratch;
    @Nullable
    BlockEntityResourceOwnership.Scope resourceOwnershipScope;
    @Nullable
    FurnaceWorkScope furnaceWorkScope;

    RegionWorkerThread(Runnable target, String name) {
        super(target, name);
    }

    ArrayList<Object> resourceCheckScratch() {
        ArrayList<Object> scratch = this.resourceCheckScratch;
        if (scratch == null) {
            scratch = new ArrayList<>(64);
            this.resourceCheckScratch = scratch;
        }
        return scratch;
    }
}
