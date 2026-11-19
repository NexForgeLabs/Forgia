/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Directed dependency waves in the input's canonical order. This planner grants no ownership and executes
 * nothing. An observed footprint is not a complete footprint; callers must not use this as worker admission.
 */
@ApiStatus.Internal
public final class BlockEntityWorkPlanner {
    private BlockEntityWorkPlanner() {}

    /** Identity-scoped resource. Equal coordinates in another level/server never alias. */
    public static final class Resource {
        private final Object domain;
        private final Kind kind;
        private final long key;
        private final Object instance;

        public Resource(Object domain, Kind kind, long key) {
            this.domain = Objects.requireNonNull(domain);
            this.kind = Objects.requireNonNull(kind);
            if (kind == Kind.INSTANCE) throw new IllegalArgumentException("Instance keys require an actual object");
            this.key = key;
            this.instance = null;
        }

        /** Current-operation object key. Historical position keys remain a separate diagnostic form. */
        public Resource(Object domain, Object instance) {
            this.domain = Objects.requireNonNull(domain);
            this.kind = Kind.INSTANCE;
            this.key = 0L;
            this.instance = Objects.requireNonNull(instance);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource resource && this.domain == resource.domain
                    && this.kind == resource.kind && this.key == resource.key && this.instance == resource.instance;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * System.identityHashCode(this.domain) + this.kind.hashCode()) + Long.hashCode(this.key))
                    + System.identityHashCode(this.instance);
        }
    }

    public enum Kind {
        BLOCK_ENTITY, CHUNK, INSTANCE
    }

    public record Unit(Set<Resource> reads, Set<Resource> writes, boolean exclusive) {
        public Unit {
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
        }
    }

    /** Indices refer to input order. Links count unique predecessor links, not transitive closure. */
    public record Plan(List<List<Integer>> waves, long dependencyLinks, int components, int largestComponent, int maxWidth) {
        public Plan {
            waves = waves.stream().map(List::copyOf).toList();
        }
    }

    private static final class TrustedWaveList extends java.util.AbstractList<List<Integer>> implements java.util.RandomAccess {
        private final List<Integer>[] waves;

        private TrustedWaveList(List<Integer>[] waves) {
            this.waves = waves;
        }

        @Override
        public List<Integer> get(int index) {
            return this.waves[index];
        }

        @Override
        public int size() {
            return this.waves.length;
        }
    }

    /** Execution-only result. Graph links and connected components are computed only by {@link #plan}. */
    public record ExecutionPlan(List<List<Integer>> waves, int maxWidth) {
        public ExecutionPlan {
            if (!(waves instanceof TrustedWaveList)) waves = waves.stream().map(List::copyOf).toList();
        }
    }

    /**
     * The earliest legal depth needs only the latest writer depth and greatest following reader depth.
     * A fence follows the global maximum and replaces all earlier resource state with its barrier depth.
     * No reader/predecessor index sets, union-find or quadratic fence links are needed for execution.
     */
    @SuppressWarnings("unchecked")
    public static ExecutionPlan planExecution(List<Unit> input) {
        final int n = input.size();
        if (n > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Work graph unit limit exceeded");
        if (n == 0) return new ExecutionPlan(new TrustedWaveList(new List[0]), 0);
        int totalResources = 0;
        for (int index = 0; index < n; ++index) {
            final Unit unit = input.get(index);
            if (!unit.exclusive()) totalResources += unit.reads().size() + unit.writes().size();
        }
        final int capacity = tableCapacity(Math.max(4, totalResources));
        final int mask = capacity - 1;
        final Resource[] keys = new Resource[capacity];
        final int[] writers = new int[capacity];
        final int[] readers = new int[capacity];
        final int[] stamps = new int[capacity];
        int generation = 1;

        final int[] unitDepth = new int[n];
        int[] waveSizes = new int[Math.min(n, 16)];
        int barrier = 0;
        int maximum = -1;
        int width = 0;
        for (int index = 0; index < n; ++index) {
            final Unit unit = input.get(index);
            final boolean exclusive = unit.exclusive();
            int depth = exclusive ? maximum + 1 : barrier;
            if (!exclusive) {
                for (final Resource resource : unit.reads()) {
                    final int slot = findSlot(keys, stamps, generation, mask, resource);
                    if (slot >= 0) depth = Math.max(depth, writers[slot] + 1);
                }
                for (final Resource resource : unit.writes()) {
                    final int slot = findSlot(keys, stamps, generation, mask, resource);
                    if (slot >= 0) depth = Math.max(depth, Math.max(writers[slot], readers[slot]) + 1);
                }
            }
            unitDepth[index] = depth;
            if (depth >= waveSizes.length) waveSizes = java.util.Arrays.copyOf(waveSizes, Math.max(depth + 1, waveSizes.length * 2));
            final int waveSize = ++waveSizes[depth];
            width = Math.max(width, waveSize);
            maximum = Math.max(maximum, depth);
            if (exclusive) {
                barrier = depth + 1;
                ++generation;
            } else {
                final Set<Resource> writes = unit.writes();
                for (final Resource resource : unit.reads()) {
                    if (!writes.contains(resource)) {
                        final int slot = ensureSlot(keys, writers, readers, stamps, generation, mask, resource);
                        if (depth > readers[slot]) readers[slot] = depth;
                    }
                }
                for (final Resource resource : writes) {
                    final int slot = ensureSlot(keys, writers, readers, stamps, generation, mask, resource);
                    writers[slot] = depth;
                    readers[slot] = -1;
                }
            }
        }
        final int waveCount = maximum + 1;
        final List<Integer>[] waveLists = new List[waveCount];
        final Integer[][] waveArrays = new Integer[waveCount][];
        final int[] offsets = new int[waveCount];
        for (int d = 0; d < waveCount; ++d) {
            waveArrays[d] = new Integer[waveSizes[d]];
            waveLists[d] = java.util.Collections.unmodifiableList(java.util.Arrays.asList(waveArrays[d]));
        }
        for (int index = 0; index < n; ++index) {
            final int d = unitDepth[index];
            waveArrays[d][offsets[d]++] = index;
        }
        return new ExecutionPlan(new TrustedWaveList(waveLists), width);
    }

    private static int tableCapacity(int entries) {
        return Math.max(8, Integer.highestOneBit(Math.max(1, entries * 2 - 1)) << 1);
    }

    private static int findSlot(Resource[] keys, int[] stamps, int generation, int mask, Resource resource) {
        final int h = resource.hashCode();
        int slot = (h ^ (h >>> 16)) & mask;
        while (stamps[slot] == generation) {
            if (keys[slot].equals(resource)) return slot;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private static int ensureSlot(Resource[] keys, int[] writers, int[] readers, int[] stamps, int generation, int mask, Resource resource) {
        final int h = resource.hashCode();
        int slot = (h ^ (h >>> 16)) & mask;
        while (stamps[slot] == generation) {
            if (keys[slot].equals(resource)) return slot;
            slot = (slot + 1) & mask;
        }
        keys[slot] = resource;
        writers[slot] = -1;
        readers[slot] = -1;
        stamps[slot] = generation;
        return slot;
    }

    public static Plan plan(List<Unit> input) {
        if (input.size() > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Work graph unit limit exceeded");
        final Map<Resource, Access> accesses = new HashMap<>();
        final List<List<Integer>> waves = new ArrayList<>();
        final int[] depths = new int[input.size()];
        final int[] parents = new int[input.size()];
        final int[] sizes = new int[input.size()];
        int latestBarrier = -1;
        int maxDepth = -1;
        int maxWidth = 0;
        long links = 0L;
        for (int index = 0; index < input.size(); ++index) {
            parents[index] = index;
            sizes[index] = 1;
            final Unit unit = input.get(index);
            final Set<Integer> predecessors = new HashSet<>();
            if (unit.exclusive()) {
                // The unknown operation is a fence, including against units whose resources were unobserved.
                for (int prior = 0; prior < index; ++prior) predecessors.add(prior);
            } else {
                if (latestBarrier >= 0) predecessors.add(latestBarrier);
                for (final Resource resource : unit.reads()) {
                    final Access access = accesses.get(resource);
                    if (access != null && access.writer >= 0) predecessors.add(access.writer);
                }
                for (final Resource resource : unit.writes()) {
                    final Access access = accesses.get(resource);
                    if (access != null) {
                        if (access.writer >= 0) predecessors.add(access.writer);
                        predecessors.addAll(access.readers);
                    }
                }
            }
            int depth = unit.exclusive() ? maxDepth + 1 : 0;
            for (final int predecessor : predecessors) {
                depth = Math.max(depth, depths[predecessor] + 1);
                join(parents, sizes, index, predecessor);
            }
            links += predecessors.size();
            depths[index] = depth;
            maxDepth = Math.max(maxDepth, depth);
            while (waves.size() <= depth) waves.add(new ArrayList<>());
            final List<Integer> wave = waves.get(depth);
            wave.add(index);
            maxWidth = Math.max(maxWidth, wave.size());
            // Update after dependency discovery: a read/write of the same resource never depends on itself.
            for (final Resource resource : unit.reads()) {
                if (!unit.writes().contains(resource)) accesses.computeIfAbsent(resource, ignored -> new Access()).readers.add(index);
            }
            for (final Resource resource : unit.writes()) {
                final Access access = accesses.computeIfAbsent(resource, ignored -> new Access());
                access.writer = index;
                access.readers.clear();
            }
            if (unit.exclusive()) latestBarrier = index;
        }
        int components = 0;
        int largest = 0;
        for (int index = 0; index < parents.length; ++index) {
            if (root(parents, index) == index) {
                ++components;
                largest = Math.max(largest, sizes[index]);
            }
        }
        return new Plan(waves, links, components, largest, maxWidth);
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    private static void join(int[] parents, int[] sizes, int first, int second) {
        final int a = root(parents, first);
        final int b = root(parents, second);
        if (a != b) {
            parents[b] = a;
            sizes[a] += sizes[b];
        }
    }

    private static final class Access {
        private int writer = -1;
        private final Set<Integer> readers = new HashSet<>();
    }
}
