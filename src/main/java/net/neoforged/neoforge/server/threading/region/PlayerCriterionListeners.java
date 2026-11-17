/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Simple-trigger listener state of one original PlayerAdvancements instance. Global trigger objects retain
 * no players. The original HashSet is preserved: predicate iteration, selected-list construction and
 * synchronous rewards remain in SimpleCriterionTrigger, including reentrant mutations and exceptions.
 * <p>
 * This is owner-local storage, not an ownership permission or a concurrent collection for the same player.
 * Its owner follows the original PlayerAdvancements lifecycle. Native workers are still rejected until a
 * complete player invocation explicitly covers advancement predicates, rewards and progress mutations.
 */
@ApiStatus.Internal
public final class PlayerCriterionListeners {
    private final boolean enabled;
    private final Map<SimpleCriterionTrigger<?>, Set<?>> listeners = new IdentityHashMap<>();
    private long revision;
    private volatile long generation;

    /** Frozen registration identities for the unstarted invocation check; never used to execute criteria. */
    static final class Snapshot {
        private final long revision;
        private final long generation;
        private final Map<SimpleCriterionTrigger<?>, Set<?>> sets = new IdentityHashMap<>();
        private final Map<SimpleCriterionTrigger<?>, Set<Object>> members = new IdentityHashMap<>();

        private Snapshot(PlayerCriterionListeners storage) {
            this.revision = storage.revision;
            this.generation = storage.generation;
            storage.listeners.forEach((trigger, set) -> {
                this.sets.put(trigger, set);
                Set<Object> identities = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                identities.addAll(set);
                this.members.put(trigger, identities);
            });
        }
    }

    Snapshot snapshot() {
        check();
        return new Snapshot(this);
    }

    // Only the plan's pre-body check uses this. Calling get()/revision() on a native worker still
    // requires the active owner scope. Raw original HashSet mutations must also invalidate capture.
    boolean matches(Snapshot snapshot) {
        if (this.revision != snapshot.revision || this.generation != snapshot.generation || this.listeners.size() != snapshot.sets.size()) return false;
        for (var entry : snapshot.sets.entrySet()) {
            Set<?> current = this.listeners.get(entry.getKey());
            Set<Object> members = snapshot.members.get(entry.getKey());
            if (current != entry.getValue() || current.size() != members.size() || !current.stream().allMatch(members::contains)) return false;
        }
        return true;
    }

    public long revision() {
        check();
        return this.revision;
    }

    public long generation() {
        return this.generation;
    }

    public void invalidateLifecycle() {
        RegionPlayerStateScope.rejectLifecycle("advancement rebind/reload/stopListening");
        this.generation++;
    }

    public boolean empty(SimpleCriterionTrigger<?> trigger) {
        var set = this.get(trigger);
        return set == null || set.isEmpty();
    }

    public PlayerCriterionListeners(boolean enabled) {
        this.enabled = enabled;
    }

    /** Storage mode belongs to this original owner and does not change with later engine retirement. */
    public boolean enabled() {
        return this.enabled;
    }

    private void check() {
        if (RegionWorkerScope.isNativeWorkerThread()) RegionPlayerStateScope.checkListenerStorage(this);
    }

    /** The original set or null; querying never creates a registration. */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T extends SimpleCriterionTrigger.SimpleInstance> Set<CriterionTrigger.Listener<T>> get(SimpleCriterionTrigger<T> trigger) {
        check();
        return (Set<CriterionTrigger.Listener<T>>) this.listeners.get(trigger);
    }

    /** Registers in the exact original set without copying or reordering listeners. */
    public <T extends SimpleCriterionTrigger.SimpleInstance> void add(SimpleCriterionTrigger<T> trigger, CriterionTrigger.Listener<T> listener) {
        check();
        Set<CriterionTrigger.Listener<T>> set = this.get(trigger);
        if (set == null) {
            set = new HashSet<>();
            this.listeners.put(trigger, set);
        }
        set.add(listener);
        this.revision++;
    }

    public <T extends SimpleCriterionTrigger.SimpleInstance> void remove(SimpleCriterionTrigger<T> trigger, CriterionTrigger.Listener<T> listener) {
        check();
        Set<CriterionTrigger.Listener<T>> set = this.get(trigger);
        if (set != null) {
            this.revision++;
            set.remove(listener);
            if (set.isEmpty()) this.listeners.remove(trigger);
        }
    }

    public void removeAll(SimpleCriterionTrigger<?> trigger) {
        check();
        this.listeners.remove(trigger);
        this.revision++;
    }

    public int triggerCount() {
        check();
        return this.listeners.size();
    }
}
