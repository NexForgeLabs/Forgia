/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One wave's nonblocking, subordinate resource ownership. Only the coordinator holding the outer region
 * leases may construct it. Claims describe a complete audited operation, never an observed access history.
 * The package-private minting surface is an internal protocol, not an adapter permission registration API.
 */
@ApiStatus.Internal
public final class BlockEntityResourceOwnership implements AutoCloseable {
    public static final int MAX_RESOURCES_PER_UNIT = 512;
    public static final int MAX_BINDINGS_PER_PHASE = 131072;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Object domain;
    private final IdentityHashMap<Object, BooleanSupplier> boundaries;
    private final BooleanSupplier live;
    private final Scratch scratch;
    private final Thread coordinator = Thread.currentThread();
    private final AtomicInteger activeScopes = new AtomicInteger();
    private volatile boolean open = true;
    private volatile boolean reserved;
    private List<Lease> leases = List.of();

    BlockEntityResourceOwnership(Object domain, Map<Object, BooleanSupplier> heldRegions, BooleanSupplier live) {
        this(domain, heldRegions, live, new Scratch());
    }

    BlockEntityResourceOwnership(Object domain, Map<Object, BooleanSupplier> heldRegions, BooleanSupplier live, Scratch scratch) {
        this.domain = Objects.requireNonNull(domain);
        this.boundaries = new IdentityHashMap<>(heldRegions.size());
        heldRegions.forEach((region, current) -> this.boundaries.put(Objects.requireNonNull(region), Objects.requireNonNull(current)));
        this.live = Objects.requireNonNull(live);
        this.scratch = Objects.requireNonNull(scratch);
    }

    private BlockEntityResourceOwnership(Object domain, int boundaryCapacity, BooleanSupplier live, Scratch scratch) {
        this.domain = Objects.requireNonNull(domain);
        this.boundaries = new IdentityHashMap<>(boundaryCapacity);
        this.live = Objects.requireNonNull(live);
        this.scratch = Objects.requireNonNull(scratch);
    }

    /**
     * Core-only zero-copy construction path for coordinator-held {@link RegionState} leases.
     * <p>
     * Ownership and lifetime invariant:
     * <ul>
     *     <li>Only Core code inside {@code net.neoforged.neoforge.server.threading.region} holding
     *     already-leased {@link RegionState} instances for {@code level} may invoke this factory.</li>
     *     <li>The backing {@link IdentityHashMap} is allocated inside {@link BlockEntityResourceOwnership}
     *     and never escapes to any caller, adapter, or Mod; no external map reference can mutate it
     *     after construction.</li>
     *     <li>Boundary entries remain immutable throughout {@link #tryAcquireAll(List)} and all active
     *     worker scopes; {@link #close()} refuses to clear {@code boundaries} while any {@link Scope}
     *     remains active.</li>
     * </ul>
     */
    static BlockEntityResourceOwnership forHeldRegions(
            ServerLevel level,
            List<RegionState> leased,
            Function<RegionCoordinate, Object> homeForCoordinate,
            BooleanSupplier live,
            Scratch scratch) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(leased);
        Objects.requireNonNull(homeForCoordinate);
        final BlockEntityResourceOwnership ownership = new BlockEntityResourceOwnership(level, leased.size(), live, scratch);
        for (final RegionState region : leased) {
            Objects.requireNonNull(region);
            if (region.coordinate().level() != level) {
                throw new IllegalStateException("Resource proof received a foreign region lease");
            }
            final Object home = Objects.requireNonNull(homeForCoordinate.apply(region.coordinate()));
            // Only the engine's coordinator calls this after acquiring its own exact region leases.
            // No regionizer/lifecycle lock or world callback runs when a worker checks this predicate.
            if (ownership.boundaries.put(home, () -> region.isRunning() && !region.isDead()) != null) {
                throw new IllegalStateException("Duplicate region boundary home");
            }
        }
        return ownership;
    }

    public enum Access {
        READ, WRITE
    }

    /** Actual object identity in an actual level/domain. Views, coordinates and user equals() do not split it. */
    public static final class Identity {
        private final Object domain;
        private final Object instance;

        public Identity(Object domain, Object instance) {
            this.domain = Objects.requireNonNull(domain);
            this.instance = Objects.requireNonNull(instance);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && this.domain == identity.domain && this.instance == identity.instance;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(this.domain) + System.identityHashCode(this.instance);
        }
    }

    /**
     * Attachment is separate from identity. Null home denotes an audited shared read publication, never a
     * writable global resource. All validators are bounded, pure, current-operation core proofs; they run
     * only on the coordinator before publication, not as arbitrary callbacks during worker execution.
     */
    public record Binding(Identity identity, @Nullable Object home, BooleanSupplier current, @Nullable BlockPos position) {
        public Binding {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(current);
            if (position != null) position = position.immutable();
        }
    }

    /**
     * An immutable, explicitly shared READ publication captured by one fresh preparation pass. Its one
     * pure predicate proves the whole captured publication, not arbitrary per-object attachments. It is
     * rechecked on every reservation; sharing storage never subtracts from logical binding budgets.
     */
    public static final class SharedReads {
        private final Object domain;
        private final Object[] instances;
        private final int[] index;
        private final BooleanSupplier current;
        private final int count;
        private final int size;

        private SharedReads(Object domain, List<?> instances, BooleanSupplier current) {
            this.domain = Objects.requireNonNull(domain);
            this.current = Objects.requireNonNull(current);
            this.count = Math.min(instances.size(), MAX_RESOURCES_PER_UNIT + 1);
            final int bounded = this.count <= MAX_RESOURCES_PER_UNIT ? this.count : 0;
            this.instances = new Object[bounded];
            this.index = bounded <= 8 ? null : new int[tableCapacity(bounded)];
            int size = 0;
            for (int input = 0; input < bounded; ++input) {
                final Object instance = Objects.requireNonNull(instances.get(input));
                if (identityIndex(this.instances, size, this.index, instance) < 0) {
                    this.instances[size] = instance;
                    indexIdentity(this.index, instance, size++);
                }
            }
            this.size = size;
        }

        private SharedReads(Object domain, Object primary, Set<?> uniqueInstances, BooleanSupplier current) {
            this.domain = Objects.requireNonNull(domain);
            this.current = Objects.requireNonNull(current);
            Objects.requireNonNull(primary);
            final int total = uniqueInstances.size() + (uniqueInstances.contains(primary) ? 0 : 1);
            this.count = Math.min(total, MAX_RESOURCES_PER_UNIT + 1);
            final int bounded = this.count <= MAX_RESOURCES_PER_UNIT ? this.count : 0;
            this.instances = new Object[bounded];
            this.index = bounded <= 8 ? null : new int[tableCapacity(bounded)];
            if (bounded > 0) {
                int size = 0;
                this.instances[size] = primary;
                indexIdentity(this.index, primary, size++);
                for (final Object instance : uniqueInstances) {
                    if (instance != primary) {
                        this.instances[size] = Objects.requireNonNull(instance);
                        indexIdentity(this.index, instance, size++);
                    }
                }
                this.size = size;
            } else {
                this.size = 0;
            }
        }

        private boolean contains(Object instance) {
            return identityIndex(this.instances, this.size, this.index, instance) >= 0;
        }

        private boolean sameInstances(SharedReads other) {
            if (this.domain != other.domain || this.size != other.size) return false;
            for (int index = 0; index < this.size; ++index) if (!other.contains(this.instances[index])) return false;
            return true;
        }

        public static SharedReads capture(Object domain, List<?> instances, BooleanSupplier current) {
            return new SharedReads(domain, instances, current);
        }

        static SharedReads captureUnique(Object domain, Object primary, Set<?> uniqueInstances, BooleanSupplier current) {
            return new SharedReads(domain, primary, uniqueInstances, current);
        }
    }

    /** Immutable bounded proof input. Every attachment validator survives duplicate-resource normalization. */
    public static final class Claims {
        private static final Binding[] NO_BINDINGS = new Binding[0];
        private static final Object[] NO_INSTANCES = new Object[0];
        private static final Access[] NO_ACCESS = new Access[0];
        private final Object domain;
        private final Object home;
        private final Object[] instances;
        private final Access[] access;
        private final int[] index;
        private final int size;
        private final Binding[] bindings;
        private final SharedReads shared;
        private final boolean hasSharedHome;
        private final boolean complete;

        private Claims(Object domain, Object home, Binding[] bindings, Access[] modes, @Nullable SharedReads shared, boolean complete) {
            this.domain = domain;
            this.home = home;
            this.bindings = bindings;
            this.shared = shared;
            this.instances = bindings.length == 0 ? NO_INSTANCES : new Object[bindings.length];
            this.access = bindings.length == 0 ? NO_ACCESS : new Access[bindings.length];
            this.index = bindings.length <= 8 ? null : new int[tableCapacity(bindings.length)];
            int size = 0;
            for (int binding = 0; binding < bindings.length; ++binding) {
                final Object instance = bindings[binding].identity.instance;
                int slot = identityIndex(this.instances, size, this.index, instance);
                if (slot < 0) {
                    slot = size++;
                    this.instances[slot] = instance;
                    indexIdentity(this.index, instance, slot);
                }
                if (this.access[slot] != Access.WRITE) this.access[slot] = modes[binding];
            }
            this.size = size;
            if (shared != null) {
                for (int slot = 0; slot < size; ++slot) if (this.access[slot] == Access.WRITE && shared.contains(this.instances[slot])) complete = false;
            }
            boolean sharedHome = false;
            BlockPos firstPosition = null;
            Object firstInstance = null;
            Map<BlockPos, Object> positions = null;
            for (final Binding binding : bindings) {
                if (binding.home == null) {
                    sharedHome = true;
                    if (this.localPermission(binding.identity.instance) == Access.WRITE) complete = false;
                }
                if (binding.position == null) continue;
                if (firstPosition == null) {
                    firstPosition = binding.position;
                    firstInstance = binding.identity.instance;
                } else if (firstPosition.equals(binding.position)) {
                    if (firstInstance != binding.identity.instance) complete = false;
                } else {
                    if (positions == null) positions = new HashMap<>();
                    final Object previous = positions.putIfAbsent(binding.position, binding.identity.instance);
                    if (previous != null && previous != binding.identity.instance) complete = false;
                }
            }
            this.hasSharedHome = sharedHome;
            this.complete = complete && (size != 0 || (shared != null && shared.size != 0));
        }

        /** Share only immutable local descriptions; this does not validate any current attachment or publication. */
        private Claims(Claims local, SharedReads fresh) {
            this.domain = local.domain;
            this.home = local.home;
            this.instances = local.instances;
            this.access = local.access;
            this.index = local.index;
            this.size = local.size;
            this.bindings = local.bindings;
            this.shared = fresh;
            this.hasSharedHome = local.hasSharedHome;
            boolean complete = local.complete && fresh.domain == this.domain
                    && this.bindings.length + fresh.count <= MAX_RESOURCES_PER_UNIT;
            for (int slot = 0; complete && slot < this.size; ++slot) {
                if (this.access[slot] == Access.WRITE && fresh.contains(this.instances[slot])) complete = false;
            }
            this.complete = complete;
        }

        Claims withFreshSharedReads(SharedReads fresh) {
            return new Claims(this, Objects.requireNonNull(fresh));
        }

        public static Builder builder(Object domain, Object home) {
            return new Builder(domain, home);
        }

        public static Claims fence(Object domain, Object home) {
            return new Claims(domain, home, NO_BINDINGS, NO_ACCESS, null, false);
        }

        public boolean complete() {
            return this.complete;
        }

        int resourceCount() {
            return this.bindings.length + (this.shared == null ? 0 : this.shared.count);
        }

        private Access localPermission(Object instance) {
            final int slot = identityIndex(this.instances, this.size, this.index, instance);
            return slot < 0 ? null : this.access[slot];
        }

        private Access permission(Object instance) {
            final Access local = this.localPermission(instance);
            return local != null ? local : this.shared != null && this.shared.contains(instance) ? Access.READ : null;
        }

        public boolean sameResources(Claims other) {
            return this.sameResources(other, null);
        }

        /** Memoizes only equality of immutable captured sets, never validation of mutable world state. */
        boolean sameResources(Claims other, @Nullable Map<SharedReads, SharedReads> compared) {
            if (!this.complete || !other.complete || this.domain != other.domain || this.home != other.home) return false;
            boolean localEqual = this.size == other.size;
            for (int slot = 0; localEqual && slot < this.size; ++slot) localEqual = this.access[slot] == other.localPermission(this.instances[slot]);
            if (localEqual) {
                if (this.shared == other.shared) return true;
                if (this.shared != null && other.shared != null) {
                    if (compared != null && compared.get(this.shared) == other.shared) return true;
                    if (this.shared.sameInstances(other.shared)) {
                        if (compared != null) compared.put(this.shared, other.shared);
                        return true;
                    }
                }
            }
            // Expanded/grouped representations can describe the same normalized permissions. Compare
            // their captured unions directly, without constructing diagnostic planner sets or proofs.
            return this.permissionsIncludedIn(other) && other.permissionsIncludedIn(this);
        }

        private boolean permissionsIncludedIn(Claims other) {
            for (int slot = 0; slot < this.size; ++slot) if (this.access[slot] != other.permission(this.instances[slot])) return false;
            if (this.shared != null) {
                for (int slot = 0; slot < this.shared.size; ++slot) {
                    final Object instance = this.shared.instances[slot];
                    if (this.permission(instance) != other.permission(instance)) return false;
                }
            }
            return true;
        }

        /** Diagnostic expansion only. The execution planner consumes the immutable local arrays directly. */
        public BlockEntityWorkPlanner.Unit unit() {
            if (!this.complete) return new BlockEntityWorkPlanner.Unit(Set.of(), Set.of(), true);
            final Set<BlockEntityWorkPlanner.Resource> reads = new HashSet<>();
            final Set<BlockEntityWorkPlanner.Resource> writes = new HashSet<>();
            for (int slot = 0; slot < this.size; ++slot) {
                (this.access[slot] == Access.WRITE ? writes : reads).add(new BlockEntityWorkPlanner.Resource(this.domain, this.instances[slot]));
            }
            if (this.shared != null) {
                for (int slot = 0; slot < this.shared.size; ++slot) {
                    final Object instance = this.shared.instances[slot];
                    if (this.localPermission(instance) == null) reads.add(new BlockEntityWorkPlanner.Resource(this.domain, instance));
                }
            }
            return new BlockEntityWorkPlanner.Unit(reads, writes, false);
        }

        public static final class Builder {
            private final Object domain;
            private final Object home;
            private Binding[] bindings = new Binding[4];
            private Access[] access = new Access[4];
            private int size;
            private SharedReads shared;
            private boolean complete = true;

            private Builder(Object domain, Object home) {
                this.domain = Objects.requireNonNull(domain);
                this.home = Objects.requireNonNull(home);
            }

            public Builder claim(Binding binding, Access mode) {
                Objects.requireNonNull(binding);
                Objects.requireNonNull(mode);
                if (this.size + (this.shared == null ? 0 : this.shared.count) >= MAX_RESOURCES_PER_UNIT || binding.identity.domain != this.domain
                        || (binding.home != null && binding.home != this.home) || (binding.home == null && mode == Access.WRITE)) {
                    this.complete = false;
                    return this;
                }
                if (this.size == this.bindings.length) {
                    this.bindings = Arrays.copyOf(this.bindings, this.size * 2);
                    this.access = Arrays.copyOf(this.access, this.size * 2);
                }
                this.bindings[this.size] = binding;
                this.access[this.size++] = mode;
                return this;
            }

            /** One shared publication per unit; merge its resources during fresh preparation, not here. */
            public Builder sharedReads(SharedReads shared) {
                Objects.requireNonNull(shared);
                if (this.shared != null || shared.domain != this.domain || shared.count + this.size > MAX_RESOURCES_PER_UNIT) this.complete = false;
                if (this.shared == null) this.shared = shared;
                return this;
            }

            public Claims build() {
                // Builders may be reused. Neither a later claim nor a later build can mutate a publication.
                return new Claims(this.domain, this.home, Arrays.copyOf(this.bindings, this.size), this.access, this.shared, this.complete);
            }
        }
    }

    public record Plan(List<Claims> claims, BlockEntityWorkPlanner.ExecutionPlan dependencies) {
        public Plan {
            claims = List.copyOf(claims);
        }
    }

    /** Refuse inconsistent homes/global write aliases across the entire ordered phase, then compute depths. */
    public static Plan plan(List<Claims> input) {
        return plan(input, new Scratch());
    }

    static Plan plan(List<Claims> input, Scratch scratch) {
        final int n = input.size();
        if (n > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Resource proof unit budget exceeded");
        final Map<SharedReads, Boolean> groups = new IdentityHashMap<>();
        final int entries = entryBudget(input, groups);
        if (entries < 0) throw new IllegalArgumentException("Resource proof phase budget exceeded");
        final ResourceTable table = scratch.acquire(entries);
        try {
            for (int index = 0; index < n; ++index) {
                final Claims claims = input.get(index);
                if (!claims.complete) continue;
                final Object domain = claims.domain;
                final Object home = claims.home;
                final boolean singleHome = !claims.hasSharedHome;
                for (int local = 0; local < claims.size; ++local) {
                    final Object instance = claims.instances[local];
                    final int slot = table.slot(domain, instance);
                    table.flags[slot] |= claims.access[local] == Access.WRITE ? ResourceTable.WRITE : ResourceTable.READ;
                    if (singleHome) table.home(slot, home);
                }
                if (!singleHome) {
                    for (final Binding binding : claims.bindings) table.home(table.slot(domain, binding.identity.instance), binding.home);
                }
            }
            for (int i = 0; i < table.usedCount; ++i) {
                final int slot = table.usedSlots[i];
                if ((table.flags[slot] & (ResourceTable.SHARED | ResourceTable.WRITE)) == (ResourceTable.SHARED | ResourceTable.WRITE)) table.flags[slot] |= ResourceTable.INVALID;
            }
            if (!groups.isEmpty()) {
                for (int index = 0; index < n; ++index) {
                    final Claims claims = input.get(index);
                    if (!claims.complete) continue;
                    for (int local = 0; local < claims.size; ++local) {
                        if (claims.access[local] != Access.WRITE) continue;
                        final Object instance = claims.instances[local];
                        for (final SharedReads group : groups.keySet()) {
                            if (group != claims.shared && group.domain == claims.domain && group.contains(instance)) {
                                groups.put(group, Boolean.TRUE);
                                table.flags[table.slot(claims.domain, instance)] |= ResourceTable.INVALID;
                            }
                        }
                    }
                }
                for (int i = 0; i < table.usedCount; ++i) {
                    final int slot = table.usedSlots[i];
                    if (table.invalid(slot)) {
                        final Object domain = table.keys[slot * 2];
                        final Object instance = table.keys[slot * 2 + 1];
                        for (final SharedReads group : groups.keySet()) {
                            if (group.domain == domain && group.contains(instance)) {
                                groups.put(group, Boolean.TRUE);
                            }
                        }
                    }
                }
            }
            final List<Claims> effective = new ArrayList<>(n);
            final List<List<Integer>> waves = new ArrayList<>();
            final int[] writers = new int[table.flags.length];
            final int[] readers = new int[table.flags.length];
            int barrier = 0;
            int maximum = -1;
            int width = 0;
            for (int index = 0; index < n; ++index) {
                Claims claims = input.get(index);
                boolean invalid = claims.shared != null && groups.get(claims.shared) == Boolean.TRUE;
                for (int local = 0; claims.complete && !invalid && local < claims.size; ++local) invalid = table.invalid(table.slot(claims.domain, claims.instances[local]));
                if (claims.complete && invalid) claims = Claims.fence(claims.domain, claims.home);
                effective.add(claims);
                int depth = claims.complete ? barrier : maximum + 1;
                if (claims.complete) {
                    // Shared publications cannot be written anywhere in this accepted phase. Local arrays
                    // suffice for dependency discovery; no per-unit Resource/Set/Depths expansion is needed.
                    for (int local = 0; local < claims.size; ++local) {
                        final int slot = table.slot(claims.domain, claims.instances[local]);
                        depth = Math.max(depth, writers[slot]);
                        if (claims.access[local] == Access.WRITE) depth = Math.max(depth, readers[slot]);
                    }
                }
                while (waves.size() <= depth) waves.add(new ArrayList<>());
                final List<Integer> wave = waves.get(depth);
                wave.add(index);
                width = Math.max(width, wave.size());
                maximum = Math.max(maximum, depth);
                if (!claims.complete) {
                    // Depth arrays store predecessor depth + 1. A fence's new barrier dominates every
                    // previous entry, so no clearing, epoch cache or resource-history authority is needed.
                    barrier = depth + 1;
                } else {
                    for (int local = 0; local < claims.size; ++local) {
                        final int slot = table.slot(claims.domain, claims.instances[local]);
                        if (claims.access[local] == Access.WRITE) {
                            writers[slot] = depth + 1;
                            readers[slot] = 0;
                        } else readers[slot] = Math.max(readers[slot], depth + 1);
                    }
                }
            }
            return new Plan(effective, new BlockEntityWorkPlanner.ExecutionPlan(waves, width));
        } finally {
            scratch.release();
        }
    }

    /** Charge logical uses before allocating a table; SharedReads are checked via their own index without table insertion. */
    private static int entryBudget(List<Claims> input, Map<SharedReads, Boolean> groups) {
        int bindings = 0;
        int entries = 0;
        for (int i = 0; i < input.size(); ++i) {
            final Claims claims = input.get(i);
            bindings += claims.resourceCount();
            if (bindings > MAX_BINDINGS_PER_PHASE) return -1;
            if (!claims.complete) continue;
            entries += claims.size;
            if (claims.shared != null) groups.putIfAbsent(claims.shared, Boolean.FALSE);
        }
        return entries;
    }

    private static int tableCapacity(int entries) {
        return Math.max(4, Integer.highestOneBit(Math.max(1, entries * 2 - 1)) << 1);
    }

    private static int identityHash(Object instance) {
        final int hash = System.identityHashCode(instance);
        return hash ^ (hash >>> 16);
    }

    private static int identityIndex(Object[] instances, int size, @Nullable int[] index, Object instance) {
        if (index == null) {
            for (int slot = 0; slot < size; ++slot) if (instances[slot] == instance) return slot;
        } else {
            final int mask = index.length - 1;
            for (int slot = identityHash(instance) & mask; index[slot] != 0; slot = (slot + 1) & mask) {
                final int found = index[slot] - 1;
                if (instances[found] == instance) return found;
            }
        }
        return -1;
    }

    private static void indexIdentity(@Nullable int[] index, Object instance, int value) {
        if (index == null) return;
        final int mask = index.length - 1;
        int slot = identityHash(instance) & mask;
        while (index[slot] != 0) slot = (slot + 1) & mask;
        index[slot] = value + 1;
    }

    /**
     * Capacity only, owned by one coordinator for one phase. It retains no identity, home, occupancy or
     * successful proof between calls. Planning and reservation still recompute all their separate checks.
     */
    static final class Scratch {
        private final Thread coordinator = Thread.currentThread();
        private ResourceTable table;
        private boolean inUse;

        private ResourceTable acquire(int entries) {
            if (Thread.currentThread() != this.coordinator || this.inUse) throw new IllegalStateException("Resource scratch requires its idle phase coordinator");
            if (this.table == null || this.table.flags.length < tableCapacity(entries)) this.table = new ResourceTable(entries);
            this.inUse = true;
            return this.table;
        }

        private void release() {
            if (Thread.currentThread() != this.coordinator || !this.inUse) throw new IllegalStateException("Resource scratch released out of order");
            this.table.clear();
            this.inUse = false;
        }
    }

    /** Call-local, pre-sized pair-identity table. No user hash/equals, per-entry nodes or retained proof. */
    private static final class ResourceTable {
        private static final int READ = 1;
        private static final int WRITE = 2;
        private static final int SHARED = 4;
        private static final int INVALID = 8;
        private final Object[] keys;
        private final Object[] homes;
        private final byte[] flags;
        private final int[] usedSlots;
        private int usedCount;

        private ResourceTable(int entries) {
            final int capacity = tableCapacity(entries);
            this.keys = new Object[capacity * 2];
            this.homes = new Object[capacity];
            this.flags = new byte[capacity];
            this.usedSlots = new int[capacity];
        }

        private int slot(Object domain, Object instance) {
            final int hash = 31 * System.identityHashCode(domain) + System.identityHashCode(instance);
            final int mask = this.flags.length - 1;
            int slot = (hash ^ (hash >>> 16)) & mask;
            while (this.keys[slot * 2 + 1] != null) {
                if (this.keys[slot * 2] == domain && this.keys[slot * 2 + 1] == instance) return slot;
                slot = (slot + 1) & mask;
            }
            this.keys[slot * 2] = domain;
            this.keys[slot * 2 + 1] = instance;
            this.usedSlots[this.usedCount++] = slot;
            return slot;
        }

        private void home(int slot, @Nullable Object home) {
            if (home == null) this.flags[slot] |= SHARED;
            else if (this.homes[slot] == null) this.homes[slot] = home;
            else if (this.homes[slot] != home) this.flags[slot] |= INVALID;
        }

        private boolean invalid(int slot) {
            return (this.flags[slot] & INVALID) != 0;
        }

        private void clear() {
            if (this.usedCount <= (this.flags.length >> 2)) {
                for (int i = 0; i < this.usedCount; ++i) {
                    final int slot = this.usedSlots[i];
                    this.keys[slot * 2] = null;
                    this.keys[slot * 2 + 1] = null;
                    this.homes[slot] = null;
                    this.flags[slot] = 0;
                }
            } else {
                Arrays.fill(this.keys, null);
                Arrays.fill(this.homes, null);
                Arrays.fill(this.flags, (byte) 0);
            }
            this.usedCount = 0;
        }
    }

    /**
     * Incremental O(1)-per-unit independence checker for contiguous canonical prefixes.
     * Enforces the exact same conflict, home-consistency, shared-read and budget rules as {@link #plan}.
     */
    static final class PrefixIndependence {
        private ResourceTable table = new ResourceTable(64);
        private final Set<SharedReads> sharedGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        private int unitCount;
        private int logicalBindings;

        void reset() {
            this.table.clear();
            this.sharedGroups.clear();
            this.unitCount = 0;
            this.logicalBindings = 0;
        }

        boolean tryAppend(Claims claims) {
            if (!claims.complete || this.unitCount >= RegionBlockEntityWorkGraph.MAX_UNITS) return false;
            final int count = claims.resourceCount();
            if (count > MAX_BINDINGS_PER_PHASE - this.logicalBindings) return false;
            final SharedReads shared = claims.shared;
            final boolean newShared = shared != null && !this.sharedGroups.contains(shared);
            if (newShared) {
                for (int i = 0; i < this.table.usedCount; ++i) {
                    final int slot = this.table.usedSlots[i];
                    if ((this.table.flags[slot] & ResourceTable.WRITE) != 0
                            && this.table.keys[slot * 2] == shared.domain
                            && shared.contains(this.table.keys[slot * 2 + 1])) {
                        return false;
                    }
                }
            }
            if (this.table.usedCount + claims.size > (this.table.flags.length >> 1)) {
                this.grow(this.table.usedCount + claims.size);
            }
            final Object domain = claims.domain;
            final Object home = claims.home;
            final boolean singleHome = !claims.hasSharedHome;
            for (int local = 0; local < claims.size; ++local) {
                final Object instance = claims.instances[local];
                final int slot = this.table.slot(domain, instance);
                final int occupied = this.table.flags[slot];
                final int mode = claims.access[local] == Access.WRITE ? ResourceTable.WRITE : ResourceTable.READ;
                if ((occupied & ResourceTable.WRITE) != 0 || (mode == ResourceTable.WRITE && (occupied & (ResourceTable.READ | ResourceTable.SHARED)) != 0)) {
                    return false;
                }
                if (mode == ResourceTable.WRITE && !this.sharedGroups.isEmpty()) {
                    for (final SharedReads group : this.sharedGroups) {
                        if (group != shared && group.domain == domain && group.contains(instance)) return false;
                    }
                }
                this.table.flags[slot] |= mode;
                if (singleHome) {
                    this.table.home(slot, home);
                    if (this.table.invalid(slot)) return false;
                }
            }
            if (!singleHome) {
                for (final Binding binding : claims.bindings) {
                    final int slot = this.table.slot(domain, binding.identity.instance);
                    this.table.home(slot, binding.home);
                    if (this.table.invalid(slot)
                            || (this.table.flags[slot] & (ResourceTable.SHARED | ResourceTable.WRITE)) == (ResourceTable.SHARED | ResourceTable.WRITE)) {
                        return false;
                    }
                }
            }
            if (newShared) this.sharedGroups.add(shared);
            this.logicalBindings += count;
            ++this.unitCount;
            return true;
        }

        private void grow(int needed) {
            final ResourceTable old = this.table;
            final ResourceTable next = new ResourceTable(Math.max(needed, old.flags.length));
            for (int i = 0; i < old.usedCount; ++i) {
                final int oldSlot = old.usedSlots[i];
                final int newSlot = next.slot(old.keys[oldSlot * 2], old.keys[oldSlot * 2 + 1]);
                next.homes[newSlot] = old.homes[oldSlot];
                next.flags[newSlot] = old.flags[oldSlot];
            }
            this.table = next;
        }
    }

    /**
     * Reserve a complete wave, all-or-nothing, without waiting. It can succeed only once. No live leases
     * escape on a refusal, and no worker is published before all conflicts, bindings and boundaries pass.
     */
    @Nullable
    List<Lease> tryAcquireAll(List<Claims> requests) {
        this.checkCoordinator();
        final int n = requests.size();
        if (!this.open || this.reserved || n == 0 || n > RegionBlockEntityWorkGraph.MAX_UNITS || !this.live.getAsBoolean()) return null;
        final Map<SharedReads, Boolean> groups = new IdentityHashMap<>();
        final int entries = entryBudget(requests, groups);
        if (entries < 0) return null;
        final ResourceTable table = this.scratch.acquire(entries);
        try {
            final boolean hasGroups = !groups.isEmpty();
            final BooleanSupplier[] requestBoundaries = new BooleanSupplier[n];
            final Set<Object> checkedHomes = this.boundaries.size() <= 1 ? null : Collections.newSetFromMap(new IdentityHashMap<>(this.boundaries.size()));
            Object singleHome = null;
            BooleanSupplier singleBoundary = null;
            for (int index = 0; index < n; ++index) {
                final Claims claims = requests.get(index);
                if (!claims.complete || claims.domain != this.domain) return null;
                final BooleanSupplier boundary;
                if (checkedHomes == null) {
                    if (singleHome == claims.home) {
                        boundary = singleBoundary;
                    } else if (singleHome == null) {
                        boundary = this.boundaries.get(claims.home);
                        if (boundary == null || !boundary.getAsBoolean()) return null;
                        singleHome = claims.home;
                        singleBoundary = boundary;
                    } else {
                        return null;
                    }
                } else {
                    boundary = this.boundaries.get(claims.home);
                    if (boundary == null || (checkedHomes.add(claims.home) && !boundary.getAsBoolean())) return null;
                }
                requestBoundaries[index] = boundary;
                final Object home = claims.home;
                final boolean singleClaimHome = !claims.hasSharedHome;
                for (int local = 0; local < claims.size; ++local) {
                    final Object instance = claims.instances[local];
                    final int slot = table.slot(this.domain, instance);
                    final int occupied = table.flags[slot];
                    final int mode = claims.access[local] == Access.WRITE ? ResourceTable.WRITE : ResourceTable.READ;
                    if ((occupied & ResourceTable.WRITE) != 0 || (mode == ResourceTable.WRITE && (occupied & (ResourceTable.READ | ResourceTable.SHARED)) != 0)) return null;
                    if (mode == ResourceTable.WRITE && hasGroups) {
                        for (final SharedReads group : groups.keySet()) {
                            if (group != claims.shared && group.domain == this.domain && group.contains(instance)) return null;
                        }
                    }
                    table.flags[slot] |= mode;
                    if (singleClaimHome) {
                        table.home(slot, home);
                        if (table.invalid(slot)) return null;
                    }
                }
                if (!singleClaimHome) {
                    for (final Binding binding : claims.bindings) {
                        final int slot = table.slot(this.domain, binding.identity.instance);
                        table.home(slot, binding.home);
                        if (table.invalid(slot)
                                || (table.flags[slot] & (ResourceTable.SHARED | ResourceTable.WRITE)) == (ResourceTable.SHARED | ResourceTable.WRITE)) return null;
                    }
                }
            }
            for (int index = 0; index < n; ++index) {
                final Claims claims = requests.get(index);
                for (final Binding binding : claims.bindings) if (!binding.current.getAsBoolean()) return null;
                if (claims.shared != null && groups.put(claims.shared, Boolean.TRUE) != Boolean.TRUE && !claims.shared.current.getAsBoolean()) return null;
            }
            // A pure validator may have observed a lifecycle transition; never publish a partial proof.
            if (!this.live.getAsBoolean()) return null;
            if (checkedHomes == null) {
                if (singleBoundary != null && !singleBoundary.getAsBoolean()) return null;
            } else {
                for (final Object home : checkedHomes) if (!this.boundaries.get(home).getAsBoolean()) return null;
            }
            final Lease[] granted = new Lease[n];
            for (int index = 0; index < n; ++index) granted[index] = new Lease(this, requests.get(index), requestBoundaries[index]);
            this.leases = Collections.unmodifiableList(Arrays.asList(granted));
            this.reserved = true;
            return this.leases;
        } finally {
            this.scratch.release();
        }
    }

    private void checkCoordinator() {
        if (Thread.currentThread() != this.coordinator) throw new IllegalStateException("Resource reservation/release requires its region-lease coordinator");
    }

    @Override
    public void close() {
        this.checkCoordinator();
        if (!this.open) throw new IllegalStateException("Resource ownership already closed");
        if (this.activeScopes.get() != 0) throw new IllegalStateException("Cannot release resources while an ownership scope is active");
        this.open = false;
        if (this.activeScopes.get() != 0) {
            this.open = true;
            throw new IllegalStateException("Cannot release resources while an ownership scope is active");
        }
        for (final Lease lease : this.leases) lease.release();
        this.boundaries.clear();
    }

    private static @Nullable Scope currentScope() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            return worker.resourceOwnershipScope;
        }
        return CURRENT.get();
    }

    private static void setCurrentScope(Scope scope) {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            worker.resourceOwnershipScope = scope;
            return;
        }
        CURRENT.set(scope);
    }

    private static void clearCurrentScope() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            worker.resourceOwnershipScope = null;
            return;
        }
        CURRENT.remove();
    }

    /** One-shot invocation authority. Holding a handle alone grants no access outside its entered scope. */
    public static final class Lease {
        private static final int RESERVED = 0;
        private static final int ENTERED = 1;
        private static final int CONSUMED = 2;
        private static final int RELEASED = 3;
        private final BlockEntityResourceOwnership owner;
        private final Object domain;
        private final BooleanSupplier boundary;
        private final Claims claims;
        private final AtomicInteger state = new AtomicInteger(RESERVED);

        private Lease(BlockEntityResourceOwnership owner, Claims claims, BooleanSupplier boundary) {
            this.owner = owner;
            this.domain = claims.domain;
            this.boundary = boundary;
            this.claims = claims;
        }

        /** Internal invocation bridge checks the real unconsumed grant, never an adapter description. */
        boolean isReservedFor(Object domain, Object home) {
            return this.domain == domain && this.claims.home == home && this.owner.open && this.owner.reserved
                    && this.state.get() == RESERVED && this.boundary.getAsBoolean();
        }

        boolean isEnteredFor(Object domain, Object home, Claims expected) {
            final Scope scope = currentScope();
            return this.claims == expected && this.domain == domain && this.claims.home == home && this.owner.open && this.owner.reserved
                    && this.state.get() == ENTERED && scope != null && scope.lease == this && !scope.closed
                    && this.boundary.getAsBoolean();
        }

        boolean entered() {
            return this.state.get() == ENTERED;
        }

        boolean consumed() {
            return this.state.get() == CONSUMED;
        }

        Scope enter() {
            if (currentScope() != null) throw new IllegalStateException("Nested resource ownership scopes are forbidden");
            this.owner.activeScopes.incrementAndGet();
            if (!this.owner.open || !this.owner.reserved || !this.state.compareAndSet(RESERVED, ENTERED)) {
                this.owner.activeScopes.decrementAndGet();
                throw new IllegalStateException("Resource lease is closed, foreign or already consumed");
            }
            try {
                if (!this.owner.open || !this.boundary.getAsBoolean()) throw new IllegalStateException("Resource lease lost its outer region exclusion");
                final Scope scope = new Scope(this);
                setCurrentScope(scope);
                return scope;
            } catch (Throwable failure) {
                this.state.set(CONSUMED);
                this.owner.activeScopes.decrementAndGet();
                throw failure;
            }
        }

        private void release() {
            final int before = this.state.get();
            if (before == ENTERED || !this.state.compareAndSet(before, RELEASED)) throw new IllegalStateException("Cannot release a running resource lease");
        }
    }

    /** Fine-grained permission, always subordinate to the coordinator's still-held region exclusion. */
    public static final class Scope implements AutoCloseable {
        private final Lease lease;
        private boolean closed;

        private Scope(Lease lease) {
            this.lease = lease;
        }

        public static boolean active() {
            return currentScope() != null;
        }

        public static void require(Object domain, Object instance, Access requested) {
            Objects.requireNonNull(requested);
            final Scope scope = currentScope();
            if (scope == null) throw new IllegalStateException("No block-entity resource ownership scope");
            final Lease lease = scope.lease;
            if (scope.closed || domain != lease.domain || !lease.boundary.getAsBoolean())
                throw new IllegalStateException("Resource access exceeded its level/region/lifetime boundary");
            final Access granted = lease.claims.permission(instance);
            if (granted == null || (requested == Access.WRITE && granted != Access.WRITE)) throw new IllegalStateException("Undeclared block-entity resource access");
        }

        /** Live BE lookups expose mutable objects and therefore require WRITE, not a read-only promise. */
        public static void checkReference(Level level, BlockPos position) {
            final Scope scope = currentScope();
            if (scope == null) return;
            Object instance = null;
            for (final Binding binding : scope.lease.claims.bindings) {
                if (position.equals(binding.position)) {
                    instance = binding.identity.instance;
                    break;
                }
            }
            if (instance == null) throw new IllegalStateException("Undeclared block-entity reference");
            require(level, instance, Access.WRITE);
        }

        public static boolean checkChunk(ChunkAccess chunk, boolean write) {
            final Scope scope = currentScope();
            if (scope == null) return false;
            boolean local = false;
            for (final Binding binding : scope.lease.claims.bindings) if (binding.home != null && binding.identity.instance == chunk) local = true;
            if (!local) throw new IllegalStateException("Shared publication claims grant no foreign chunk access");
            if (!(chunk instanceof LevelChunk loaded)) throw new IllegalStateException("Uncaptured chunk implementation");
            require(loaded.getLevel(), loaded, write ? Access.WRITE : Access.READ);
            return true;
        }

        public static void rejectEffect(String operation) {
            if (currentScope() != null) throw new IllegalStateException("Resource-owned work has no effect protocol for " + operation);
        }

        @Override
        public void close() {
            if (this.closed || currentScope() != this) throw new IllegalStateException("Resource ownership scope closed on the wrong thread or out of order");
            this.closed = true;
            clearCurrentScope();
            this.lease.state.set(Lease.CONSUMED);
            this.lease.owner.activeScopes.decrementAndGet();
        }
    }
}
