/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.server.threading.c7.C7DependencyGraph;
import net.neoforged.neoforge.server.threading.c7.C7Footprint;
import net.neoforged.neoforge.server.threading.c7.C7Resource;
import org.junit.jupiter.api.Test;

class C7DependencyGraphTest {
    private final Object level = new Object();

    private C7Resource resource() {
        return new C7Resource(this.level, new Object(), C7Resource.Kind.GLOBAL);
    }

    private static C7Footprint read(C7Resource key) {
        return new C7Footprint(Map.of(key, C7Footprint.Access.READ), true);
    }

    private static C7Footprint write(C7Resource key) {
        return new C7Footprint(Map.of(key, C7Footprint.Access.WRITE), true);
    }

    private static C7DependencyGraph.Plan plan(C7Footprint... resources) {
        return C7DependencyGraph.build(List.of(resources), 100, List.of());
    }

    @Test
    void independentResourcesShareOneWave() {
        final var graph = plan(write(this.resource()), write(this.resource()), write(this.resource()));
        assertEquals(List.of(List.of(0, 1, 2)), graph.waves());
        assertEquals(0, graph.dependencies());
    }

    @Test
    void sharedReadersDoNotConflict() {
        final var key = this.resource();
        assertEquals(List.of(List.of(0, 1)), plan(read(key), read(key)).waves());
    }

    @Test
    void rawWarAndWawKeepCanonicalOrder() {
        final var key = this.resource();
        assertEquals(List.of(List.of(0), List.of(1)), plan(write(key), read(key)).waves());
        assertEquals(List.of(List.of(0), List.of(1)), plan(read(key), write(key)).waves());
        assertEquals(List.of(List.of(0), List.of(1)), plan(write(key), write(key)).waves());
    }

    @Test
    void allReadersPrecedeTheNextWriter() {
        final var key = this.resource();
        assertEquals(List.of(List.of(0, 1), List.of(2)), plan(read(key), read(key), write(key)).waves());
    }

    @Test
    void chorusFirstFitCounterexampleRemainsDirected() {
        final var x = this.resource();
        final var y = this.resource();
        final var middle = new C7Footprint(Map.of(x, C7Footprint.Access.WRITE, y, C7Footprint.Access.WRITE), true);
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), plan(write(x), middle, write(y)).waves());
    }

    @Test
    void unknownTaskIsAnExclusiveFenceInBothDirections() {
        assertEquals(List.of(List.of(0, 1), List.of(2), List.of(3, 4)),
                plan(write(this.resource()), read(this.resource()), C7Footprint.unknown(), write(this.resource()), write(this.resource())).waves());
    }

    @Test
    void identityIncludesLevelAndLifetimeNotPositionAlone() {
        final Object instance = new Object();
        final var first = new C7Resource(this.level, instance, C7Resource.Kind.BLOCK_ENTITY);
        final var same = new C7Resource(this.level, instance, C7Resource.Kind.BLOCK_ENTITY);
        final var reloaded = new C7Resource(new Object(), instance, C7Resource.Kind.BLOCK_ENTITY);
        assertEquals(first, same);
        assertNotEquals(first, reloaded);
        assertEquals(2, plan(write(first), write(same), write(reloaded)).waves().size());
    }

    @Test
    void explicitCrossChunkCrossRegionAndGlobalEdgesAreRepresentable() {
        final var graph = C7DependencyGraph.build(List.of(write(this.resource()), write(this.resource()), write(this.resource())),
                10, List.of(new C7DependencyGraph.Edge(0, 2), new C7DependencyGraph.Edge(1, 2)));
        assertEquals(List.of(List.of(0, 1), List.of(2)), graph.waves());
        assertEquals(2, graph.dependencies());
    }

    @Test
    void cyclesAndInvalidEndpointsAreRejectedWithoutDroppingEdges() {
        final var tasks = List.of(write(this.resource()), write(this.resource()));
        assertThrows(C7DependencyGraph.DependencyCycleException.class, () -> C7DependencyGraph.build(tasks, 10,
                List.of(new C7DependencyGraph.Edge(0, 1), new C7DependencyGraph.Edge(1, 0))));
        assertThrows(C7DependencyGraph.DependencyCycleException.class, () -> C7DependencyGraph.build(tasks, 10,
                List.of(new C7DependencyGraph.Edge(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> C7DependencyGraph.build(tasks, 10,
                List.of(new C7DependencyGraph.Edge(0, 7))));
    }

    @Test
    void boundedWavesStayDeterministic() {
        final var tasks = new ArrayList<C7Footprint>();
        for (int i = 0; i < 1000; ++i) tasks.add(write(this.resource()));
        final var first = C7DependencyGraph.build(tasks, 17, List.of());
        assertEquals(first, C7DependencyGraph.build(tasks, 17, List.of()));
        assertTrue(first.waves().stream().allMatch(w -> w.size() <= 17));
        assertEquals(1000, first.waves().stream().mapToInt(List::size).sum());
    }

    @Test
    void overflowNeverBecomesASmallerCompleteProof() {
        final var key = this.resource();
        final var proof = new C7Footprint.Builder(1).access(key, C7Footprint.Access.READ)
                .access(key, C7Footprint.Access.WRITE).access(this.resource(), C7Footprint.Access.READ).build();
        assertFalse(proof.complete());
        assertFalse(proof.permits(key, C7Footprint.Access.READ));
        assertEquals(List.of(List.of(0), List.of(1)), plan(proof, write(this.resource())).waves());
    }
}
