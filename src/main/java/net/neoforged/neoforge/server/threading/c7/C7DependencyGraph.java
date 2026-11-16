/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Last-writer/readers-since-writer construction: O(accesses + emitted edges), excluding deduplication.
 * Edges retain canonical RAW/WAR/WAW order. Unknown access is an exclusive whole-phase fence.
 * A read/read overlap adds no edge. Extra dependencies are validated before any invocation starts.
 */
public final class C7DependencyGraph {
    public record Edge(int before, int after) {}

    public record Plan(List<List<Integer>> waves, int dependencies, long conflicts) {}

    public static final class DependencyCycleException extends IllegalStateException {
        DependencyCycleException() {
            super("C7 dependency cycle: no work was started and no edge was discarded");
        }
    }

    private static final class State {
        int writer = -1;
        final List<Integer> readers = new ArrayList<>();
    }

    private C7DependencyGraph() {}

    public static Plan build(List<C7Footprint> footprints, int maxWidth, List<Edge> extraEdges) {
        if (maxWidth < 1) throw new IllegalArgumentException("wave width");
        final int count = footprints.size();
        final List<Set<Integer>> outgoing = new ArrayList<>(count);
        for (int i = 0; i < count; ++i) outgoing.add(new HashSet<>());
        final int[] incoming = new int[count];
        final Map<C7Resource, State> states = new HashMap<>();
        int fence = -1;
        long conflicts = 0;
        for (int index = 0; index < count; ++index) {
            final C7Footprint footprint = footprints.get(index);
            if (fence >= 0) edge(outgoing, incoming, fence, index);
            if (!footprint.complete()) {
                // Earlier fences already dominate preceding segments. Bound fence fan-in to this segment.
                for (int previous = fence + 1; previous < index; ++previous) edge(outgoing, incoming, previous, index);
                fence = index;
                states.clear();
                continue;
            }
            for (final var access : footprint.resources().entrySet()) {
                final State state = states.computeIfAbsent(access.getKey(), ignored -> new State());
                if (state.writer >= 0) {
                    edge(outgoing, incoming, state.writer, index);
                    ++conflicts;
                }
                if (access.getValue() == C7Footprint.Access.WRITE) {
                    for (final int reader : state.readers) {
                        edge(outgoing, incoming, reader, index);
                        ++conflicts;
                    }
                    state.readers.clear();
                    state.writer = index;
                } else state.readers.add(index);
            }
        }
        for (final Edge extra : extraEdges) {
            if (extra.before < 0 || extra.after < 0 || extra.before >= count || extra.after >= count)
                throw new IllegalArgumentException("C7 dependency endpoint out of range");
            edge(outgoing, incoming, extra.before, extra.after);
        }
        int dependencies = 0;
        final PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < count; ++i) {
            dependencies += incoming[i];
            if (incoming[i] == 0) ready.add(i);
        }
        final List<List<Integer>> waves = new ArrayList<>();
        int visited = 0;
        while (!ready.isEmpty()) {
            final List<Integer> wave = new ArrayList<>(Math.min(maxWidth, ready.size()));
            while (!ready.isEmpty() && wave.size() < maxWidth) wave.add(ready.remove());
            // Publish successors only AFTER choosing this wave: predecessor and successor never overlap.
            for (final int index : wave) for (final int after : outgoing.get(index)) if (--incoming[after] == 0) ready.add(after);
            visited += wave.size();
            waves.add(List.copyOf(wave));
        }
        if (visited != count) throw new DependencyCycleException();
        return new Plan(List.copyOf(waves), dependencies, conflicts);
    }

    private static void edge(List<Set<Integer>> outgoing, int[] incoming, int before, int after) {
        if (outgoing.get(before).add(after)) ++incoming[after];
    }
}
