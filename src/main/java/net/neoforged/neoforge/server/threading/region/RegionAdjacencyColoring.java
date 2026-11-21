/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Greedy 8-neighbor graph coloring over {@link RegionCoordinate}s.
 * <p>
 * Two regions in the same dimension are considered adjacent when {@code |rx1 - rx2| <= 1 && |rz1 - rz2| <= 1}.
 * Regions placed in the same color wave therefore share neither an edge nor a corner, allowing border-fixpoint
 * propagation waves to execute in parallel without adjacent-border write contention.
 */
@ApiStatus.Internal
public final class RegionAdjacencyColoring {
    private RegionAdjacencyColoring() {}

    /**
     * Partitions {@code items} into non-adjacent waves in deterministic input order.
     */
    public static <T> List<List<T>> color(Collection<T> items, java.util.function.Function<T, RegionCoordinate> coordExtractor) {
        final List<T> ordered = new ArrayList<>(items);
        final int n = ordered.size();
        if (n <= 1) {
            return n == 0 ? List.of() : List.of(ordered);
        }
        final RegionCoordinate[] coords = new RegionCoordinate[n];
        for (int i = 0; i < n; ++i) {
            coords[i] = coordExtractor.apply(ordered.get(i));
        }
        final int[] colors = new int[n];
        java.util.Arrays.fill(colors, -1);
        final List<List<T>> waves = new ArrayList<>();
        for (int i = 0; i < n; ++i) {
            final RegionCoordinate ci = coords[i];
            int usedMask = 0;
            boolean[] usedLarge = null;
            for (int j = 0; j < i; ++j) {
                if (areAdjacent(ci, coords[j])) {
                    final int cj = colors[j];
                    if (cj < 32) {
                        usedMask |= (1 << cj);
                    } else {
                        if (usedLarge == null) usedLarge = new boolean[waves.size() + 1];
                        if (cj < usedLarge.length) usedLarge[cj] = true;
                    }
                }
            }
            int chosen = 0;
            while (chosen < 32 && (usedMask & (1 << chosen)) != 0) {
                ++chosen;
            }
            if (chosen == 32 && usedLarge != null) {
                while (chosen < usedLarge.length && usedLarge[chosen]) {
                    ++chosen;
                }
            }
            colors[i] = chosen;
            while (waves.size() <= chosen) {
                waves.add(new ArrayList<>());
            }
            waves.get(chosen).add(ordered.get(i));
        }
        return waves;
    }

    public static boolean areAdjacent(RegionCoordinate a, RegionCoordinate b) {
        if (a == b) return true;
        if (!java.util.Objects.equals(a.level(), b.level())) return false;
        return Math.abs(a.regionX() - b.regionX()) <= 1 && Math.abs(a.regionZ() - b.regionZ()) <= 1;
    }
}
