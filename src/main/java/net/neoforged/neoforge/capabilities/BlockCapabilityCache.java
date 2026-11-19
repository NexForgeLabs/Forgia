/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.capabilities;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.jetbrains.annotations.Nullable;

/**
 * A cache for block capabilities, to be used to track capabilities at a specific position, with a specific context.
 *
 * <p>The cache is invalidated when the level is notified of a change via {@link Level#invalidateCapabilities(BlockPos)}.
 *
 * <p>Instances are automatically cleared by the garbage collector when they are no longer in use.
 */
public final class BlockCapabilityCache<T, C extends @Nullable Object> {
    /**
     * Creates a new cache instance and registers it to the level.
     *
     * @param capability the capability
     * @param level      the level
     * @param pos        the position
     * @param context    extra context for the query
     */
    public static <T, C extends @Nullable Object> BlockCapabilityCache<T, C> create(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context) {
        return create(capability, level, pos, context, () -> true, () -> {});
    }

    /**
     * Creates a new cache instance with an invalidation listener, and registers it to the level.
     *
     * <p>A few details regarding the system:
     * <ul>
     * <li>Calling {@link #getCapability()} from the invalidation listener is not supported,
     * as the block being invalidated might not be ready to be queried again yet.
     * If you receive an invalidation notification, you should wait for some time
     * (e.g. until your own tick) before checking {@link #getCapability()} again.</li>
     * <li>In general, do not perform any level access for the listener.
     * The listener itself might be in a chunk that is being unloaded, for example.</li>
     * <li>The listener does not receive notifications before {@link #getCapability()} is called.
     * After each invalidation, {@link #getCapability()} must be called again to enable further notifications.</li>
     * </ul>
     *
     * @param capability           the capability
     * @param level                the level
     * @param pos                  the position
     * @param context              extra context for the query
     * @param isValid              a function to check if the listener still wants to receive notifications.
     *                             A typical example would be {@code () -> !this.isRemoved()} for a block entity
     *                             that should not receive invalidation notifications anymore once it is removed.
     * @param invalidationListener the invalidation listener. Will be called whenever the capability of the cache might have changed.
     */
    public static <T, C extends @Nullable Object> BlockCapabilityCache<T, C> create(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context, BooleanSupplier isValid, Runnable invalidationListener) {
        Objects.requireNonNull(capability);
        Objects.requireNonNull(isValid);
        Objects.requireNonNull(invalidationListener);
        pos = pos.immutable();

        var cache = new BlockCapabilityCache<>(capability, level, pos, context, isValid, invalidationListener);
        level.registerCapabilityListener(pos, cache.listener);
        return cache;
    }

    private final BlockCapability<T, C> capability;
    private final ServerLevel level;
    private final BlockPos pos;
    private final C context;

    // Protect cache state only. Providers and mod callbacks execute outside this lock.
    private final Object stateLock = new Object();
    private long invalidationVersion;

    /** A completed query arms the invalidation listener, including when it returned null. */
    private boolean cacheValid;
    @Nullable
    private T cachedCap = null;

    private boolean canQuery = true;
    private final ICapabilityInvalidationListener listener;

    private BlockCapabilityCache(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context, BooleanSupplier isValid, Runnable invalidationListener) {
        this.capability = capability;
        this.level = level;
        this.pos = pos;
        this.context = context;

        this.listener = () -> {
            final boolean notify;
            synchronized (this.stateLock) {
                ++this.invalidationVersion;
                notify = this.cacheValid;
                this.canQuery = false;
                this.cacheValid = false;
                this.cachedCap = null;
            }

            boolean keep = true;
            try {
                keep = isValid.getAsBoolean();
                if (keep && notify) {
                    invalidationListener.run();
                }
                return keep;
            } finally {
                synchronized (this.stateLock) {
                    this.canQuery = keep;
                }
            }
        };
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos pos() {
        return pos;
    }

    public C context() {
        return context;
    }

    /**
     * Gets the capability instance, or {@code null} if the capability is not present.
     *
     * <p>If {@linkplain #pos() the target position} is not loaded, this method will return {@code null}.
     */
    @Nullable
    public T getCapability() {
        if (RegionThreadingHooks.isNativeRegionWorkerThread()) {
            RegionThreadingHooks.assertBlockCapabilityAccess(this.level, this.pos, this.capability.name().toString());
        }
        final long version;
        synchronized (this.stateLock) {
            if (!this.canQuery) {
                throw new IllegalStateException("Do not call getCapability on an invalid cache or from the invalidation listener!");
            }
            if (this.cacheValid) {
                return this.cachedCap;
            }
            version = this.invalidationVersion;
        }

        // Preserve the lookup's result, but never publish it as cached if an invalidation overlapped it.
        final T result = this.level.isLoaded(this.pos) ? this.level.getCapability(this.capability, this.pos, this.context) : null;
        synchronized (this.stateLock) {
            if (version == this.invalidationVersion) {
                this.cachedCap = result;
                this.cacheValid = true;
            }
        }
        return result;
    }
}
