/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Selects how chunks are mapped to region coordinates.
 */
public enum RegionPolicy {
    /**
     * Legacy 16x16-chunk grid with dynamic merge and split recalculation.
     */
    DYNAMIC,
    /**
     * Folia-style dynamic region merging and splitting over fixed {@code (1 << shift) x (1 << shift)}-chunk
     * sections, maintaining a 1-section empty buffer zone between any two independent regions.
     * <p>
     * Physical entity section storage remains partitioned by static section coordinates so merges and splits
     * migrate zero entity sections; ownership queries check the lock-free published section-to-region map.
     */
    DYNAMIC_CLUSTER
}
