/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;

/** Publisher-owned resource claims for a prefix of mutually isolated players. */
final class RegionPlayerPrefixClaims {
    // Each resource list has already been checked against the live objects of its invocation.
    // Retain a weak proof so neither a disconnected player nor obsolete resources stay alive.
    private record Proof(List<WeakReference<List<Object>>> resources, int count) {}

    private final EnumMap<RegionPlayerTickCoordinator.Phase, Proof> previous = new EnumMap<>(RegionPlayerTickCoordinator.Phase.class);
    private int activeCount;

    boolean tryAcquire(List<RegionPlayerTickDispatch.Call> calls) {
        var phase = calls.getFirst().phase;
        var resources = new ArrayList<List<Object>>(calls.size());
        for (var call : calls) resources.add(call.admission.state().resources());
        return this.tryAcquire(phase, resources);
    }

    private boolean tryAcquire(RegionPlayerTickCoordinator.Phase phase, List<List<Object>> resources) {
        if (this.activeCount != 0) throw new IllegalStateException("An isolated player prefix is already active");
        Proof proof = this.previous.get(phase);
        if (proof != null && matches(proof, resources)) {
            this.activeCount = proof.count();
            return true;
        }
        var seen = new IdentityHashMap<Object, Integer>();
        for (int index = 0; index < resources.size(); index++) {
            for (Object resource : resources.get(index)) {
                Integer owner = seen.putIfAbsent(resource, index);
                if (owner != null && owner != index) return false;
            }
        }
        this.activeCount = seen.size();
        this.previous.put(phase, new Proof(resources.stream().map(WeakReference::new).toList(), this.activeCount));
        return true;
    }

    private static boolean matches(Proof proof, List<List<Object>> resources) {
        if (proof.resources().size() != resources.size()) return false;
        for (int index = 0; index < resources.size(); index++) {
            if (proof.resources().get(index).get() != resources.get(index)) return false;
        }
        return true;
    }

    void release() {
        this.activeCount = 0;
    }

    int count() {
        return this.activeCount;
    }
}
