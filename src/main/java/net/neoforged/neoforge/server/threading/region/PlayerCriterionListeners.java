/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
    private volatile boolean fakePlayer;
    private final Map<SimpleCriterionTrigger<?>, Set<?>> listeners = new IdentityHashMap<>();
    private long revision;
    private volatile long generation;

    /** HashSet that bumps the owning storage revision on every direct mutation, including iterator.remove(). */
    private final class TrackedListenerSet<E> extends HashSet<E> {
        @Override
        public boolean add(E element) {
            if (super.add(element)) {
                PlayerCriterionListeners.this.revision++;
                return true;
            }
            return false;
        }

        @Override
        public boolean remove(Object object) {
            if (super.remove(object)) {
                PlayerCriterionListeners.this.revision++;
                return true;
            }
            return false;
        }

        @Override
        public void clear() {
            if (!this.isEmpty()) {
                super.clear();
                PlayerCriterionListeners.this.revision++;
            }
        }

        @Override
        public boolean addAll(Collection<? extends E> collection) {
            boolean modified = false;
            for (E element : collection) {
                if (super.add(element)) {
                    modified = true;
                }
            }
            if (modified) PlayerCriterionListeners.this.revision++;
            return modified;
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            if (super.removeAll(collection)) {
                PlayerCriterionListeners.this.revision++;
                return true;
            }
            return false;
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            if (super.retainAll(collection)) {
                PlayerCriterionListeners.this.revision++;
                return true;
            }
            return false;
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            if (super.removeIf(filter)) {
                PlayerCriterionListeners.this.revision++;
                return true;
            }
            return false;
        }

        @Override
        public Iterator<E> iterator() {
            final Iterator<E> delegate = super.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public E next() {
                    return delegate.next();
                }

                @Override
                public void remove() {
                    delegate.remove();
                    PlayerCriterionListeners.this.revision++;
                }
            };
        }
    }

    /** Frozen registration identities for the unstarted invocation check; never used to execute criteria. */
    static final class Snapshot {
        private final long revision;
        private final long generation;
        private final Map<SimpleCriterionTrigger<?>, Set<?>> sets;

        private Snapshot(PlayerCriterionListeners storage) {
            this.revision = storage.revision;
            this.generation = storage.generation;
            this.sets = storage.listeners.isEmpty() ? Map.of() : new IdentityHashMap<>(storage.listeners);
        }

        boolean empty(SimpleCriterionTrigger<?> trigger) {
            Set<?> set = this.sets.get(trigger);
            return set == null || set.isEmpty();
        }

        @Nullable
        Set<?> get(SimpleCriterionTrigger<?> trigger) {
            return this.sets.get(trigger);
        }
    }

    Snapshot snapshot() {
        check();
        return new Snapshot(this);
    }

    // Only the plan's pre-body check uses this. Calling get()/revision() on a native worker still
    // requires the active owner scope. TrackedListenerSet bumps revision on direct HashSet mutations too.
    boolean matches(Snapshot snapshot) {
        return this.revision == snapshot.revision && this.generation == snapshot.generation;
    }

    public long revision() {
        check();
        return this.revision;
    }

    public long generation() {
        return this.generation;
    }

    public void setFakePlayer(boolean fakePlayer) {
        this.fakePlayer = fakePlayer;
    }

    public boolean isFakePlayer() {
        return this.fakePlayer;
    }

    public void invalidateLifecycle() {
        if (!this.fakePlayer) {
            RegionPlayerStateScope.rejectLifecycle("advancement rebind/reload/stopListening");
        }
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
        if (!this.fakePlayer && RegionWorkerScope.isNativeWorkerThread()) RegionPlayerStateScope.checkListenerStorage(this);
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
            set = new TrackedListenerSet<>();
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
