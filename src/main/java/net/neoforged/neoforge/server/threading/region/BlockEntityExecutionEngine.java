/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter.Mode;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Inspection;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Outcome;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Refresh;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Resources;
import org.jetbrains.annotations.Nullable;

/**
 * Shared invocation authority, subordinate to a published BE batch and its real outer region leases.
 * Dispatch, auditing, effects and original tickers remain in the two protected execution bridges.
 * No adapter receives this engine, its tickets, region guards or permits.
 */
final class BlockEntityExecutionEngine implements AutoCloseable {
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<AdmissionRead> ADMISSION_READ = new ThreadLocal<>();
    private final ServerLevel level;
    private final Thread coordinator = Thread.currentThread();
    private final BooleanSupplier live;
    private final Function<BlockPos, RegionState> home;
    private final Map<TickingBlockEntity, InvocationTicket> tickets;
    private final Set<RegionGuard> guards = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean open = true;
    private boolean orderedEffects;

    private boolean hasEffects;

    /** Transport policy only; the original Ticket/Permit remains the sole execution authority. */
    void orderedEffects(boolean enabled) {
        this.requireCoordinator();
        if (!this.guards.isEmpty() || this.active.get() != 0) throw new IllegalStateException("Effect policy changed during execution");
        this.orderedEffects = enabled;
    }

    void enableInvocationEffects(InvocationTicket ticket) {
        synchronized (ticket) {
            if (ticket.phase != this || ticket.state != State.INSPECTED)
                throw new IllegalStateException("Effect ownership requires this unstarted inspection");
            ticket.ownsEffects = this.orderedEffects;
        }
    }

    static boolean isCanonicalSerialInvocation() {
        final var permit = CURRENT.get();
        return permit != null && permit.guard == null && permit.ticket.phase.orderedEffects;
    }

    /** Only an already admitted, current, thread-bound body may produce an invocation continuation. */
    static boolean captureInvocationTask(Runnable action) {
        final var permit = CURRENT.get();
        if (permit == null || permit.guard == null || !permit.ticket.ownsEffects) return false;
        permit.requireCurrent();
        final var ticket = permit.ticket;
        List<OwnedEffect> effects = ticket.effects;
        if (effects == null) {
            effects = new ArrayList<>(2);
            ticket.effects = effects;
        }
        if (effects.size() >= RegionTickSlice.BLOCK_ENTITY_TICK.reservationPerUnit())
            throw new IllegalStateException("Audited invocation exceeded its continuation bound; never replay the body");
        effects.add(new OwnedEffect(ticket, ticket.generation, ticket.nextEffect++, EffectKind.INVOCATION_SERVER_TASK,
                java.util.Objects.requireNonNull(action)));
        ticket.phase.hasEffects = true;
        return true;
    }

    /** Physical join is the caller's obligation; no live Permit/Guard may remain at extraction. */
    List<OwnedEffect> takeInvocationEffects(long beforeOrdinal) {
        this.requireCoordinator();
        if (this.active.get() != 0 || !this.guards.isEmpty()) throw new IllegalStateException("Effects extracted before workers returned");
        if (!this.hasEffects) return List.of();
        final var ordered = new ArrayList<>(this.tickets.values());
        ordered.sort(Comparator.comparingLong(t -> t.admission.sequence()));
        final List<OwnedEffect> ready = new ArrayList<>();
        boolean remaining = false;
        for (final var ticket : ordered) {
            final List<OwnedEffect> effects = ticket.effects;
            if (effects == null || effects.isEmpty()) continue;
            if (ticket.admission.sequence() >= beforeOrdinal) {
                remaining = true;
                continue;
            }
            if (ticket.state != State.SUCCEEDED && ticket.state != State.FAILED)
                throw new IllegalStateException("Unfinished invocation has effects");
            for (final var effect : effects) {
                if (effect.invocation != ticket || effect.generation != ticket.generation)
                    throw new IllegalStateException("Effect inherited a foreign or stale proof");
                ready.add(effect);
            }
            // Retire before calling any payload. A failed commit cannot replay an already applied effect.
            effects.clear();
        }
        this.hasEffects = remaining;
        return ready;
    }

    static boolean isAdmissionRead(ServerLevel level, Object root) {
        final var read = ADMISSION_READ.get();
        if (read == null || read.closed || read.phase.level != level || !read.phase.open
                || read.ticket.state != State.INSPECTED || read.guard.closed || read.guard.sealed
                || !read.guard.region.isRunning() || read.guard.region.hasOwner())
            return false;
        for (final Object allowed : read.roots) if (root == allowed) return true;
        return false;
    }

    AdmissionRead admissionRead(InvocationTicket ticket, RegionGuard guard, List<?> roots) {
        if (ADMISSION_READ.get() != null || ticket.phase != this || guard.phase != this || ticket.state != State.INSPECTED
                || guard.mode != Mode.RESOURCE_SCOPED || guard.closed || guard.sealed
                || guard.region.blockEntityExecution != guard || !guard.region.isRunning() || guard.region.hasOwner()
                || ticket.inspection.resources() != Resources.FURNACE_LOCAL && ticket.inspection.resources() != Resources.FURNACE_CLAIMS)
            throw new IllegalStateException("Admission read requires the original unstarted furnace proof and held region");
        return new AdmissionRead(this, ticket, guard, roots);
    }

    static final class AdmissionRead implements AutoCloseable {
        private final BlockEntityExecutionEngine phase;
        private final InvocationTicket ticket;
        private final RegionGuard guard;
        private final List<?> roots;
        private boolean closed;

        private AdmissionRead(BlockEntityExecutionEngine phase, InvocationTicket ticket, RegionGuard guard, List<?> roots) {
            this.phase = phase;
            this.ticket = ticket;
            this.guard = guard;
            this.roots = List.copyOf(roots);
            ADMISSION_READ.set(this);
        }

        @Override
        public void close() {
            if (this.closed || ADMISSION_READ.get() != this) throw new IllegalStateException("Admission read closed on a foreign thread");
            this.closed = true;
            ADMISSION_READ.remove();
        }
    }

    enum EffectKind {
        INVOCATION_SERVER_TASK
    }

    record OwnedEffect(InvocationTicket invocation, long generation, int index, EffectKind kind, Runnable action) {
        RegionBlockEntityTickAdmission admission() {
            return this.invocation.admission;
        }
    }

    BlockEntityExecutionEngine(ServerLevel level, List<RegionBlockEntityTickAdmission> admissions, BooleanSupplier live,
            Function<BlockPos, RegionState> home) {
        this.level = level;
        this.live = live;
        this.home = home;
        this.tickets = new IdentityHashMap<>(Math.max(16, admissions.size()));
        for (int i = 0, size = admissions.size(); i < size; ++i) {
            final var admission = admissions.get(i);
            if (admission.level() != level || this.tickets.put(admission.ticker(), new InvocationTicket(this, admission)) != null)
                throw new IllegalArgumentException("Duplicate ticker or foreign level in block-entity phase");
        }
    }

    static Mode currentMode() {
        final var permit = CURRENT.get();
        return permit == null || permit.guard == null ? null : permit.guard.mode;
    }

    private void requireOpen() {
        if (!this.open) throw new IllegalStateException("Block-entity execution phase is closed");
    }

    InvocationTicket ticket(RegionBlockEntityTickAdmission admission) {
        this.requireOpen();
        final var ticket = this.tickets.get(admission.ticker());
        if (ticket == null || ticket.admission != admission) throw new IllegalStateException("Foreign block-entity invocation");
        return ticket;
    }

    InvocationTicket inspect(RegionBlockEntityTickAdmission admission, Inspection inspection) {
        final var ticket = this.ticket(admission);
        synchronized (ticket) {
            if (ticket.state != State.CAPTURED) throw new IllegalStateException("Invocation already inspected or started");
            ticket.inspection = inspection;
            ++ticket.generation;
            ticket.state = State.INSPECTED;
            return ticket;
        }
    }

    /** Replace only an unstarted proof, never a Ticket or a terminal state. Old Refresh objects die here. */
    InvocationTicket reinspectUnstarted(RegionBlockEntityTickAdmission admission, Inspection inspection) {
        this.requireCoordinator();
        final var ticket = this.ticket(admission);
        synchronized (ticket) {
            if ((ticket.state != State.CAPTURED && ticket.state != State.INSPECTED)
                    || (ticket.effects != null && !ticket.effects.isEmpty()))
                throw new IllegalStateException("Cannot refresh started, terminal or effect-bearing invocation");
            ticket.inspection = inspection;
            ++ticket.generation;
            ticket.ownsEffects = false;
            ticket.modManifest = null;
            ticket.executionManifest = null;
            ticket.modClaims = null;
            ticket.state = State.INSPECTED;
            return ticket;
        }
    }

    /** Attach a Core-created manifest to the same unstarted Ticket, never to an already running Serial Permit. */
    void proveMod(RegionBlockEntityTickAdmission admission, CoreModPreparation.Manifest manifest, BlockEntityResourceOwnership.Claims claims) {
        this.requireCoordinator();
        final var ticket = this.ticket(admission);
        synchronized (ticket) {
            if (ticket.state != State.INSPECTED
                    || ticket.inspection.resources() != Resources.MOD_CLAIMS || manifest.admission() != admission)
                throw new IllegalStateException("Mod proof requires this exact unstarted inspected invocation");
            ticket.modManifest = manifest;
            ticket.modClaims = java.util.Objects.requireNonNull(claims);
        }
    }

    /** Only the coordinator may bind a reviewed execution adapter to this still-unstarted Ticket. */
    void proveExecution(RegionBlockEntityTickAdmission admission, ExecutionBlockEntityPreparation.Manifest manifest,
            BlockEntityResourceOwnership.Claims claims) {
        this.requireCoordinator();
        final var ticket = this.ticket(admission);
        synchronized (ticket) {
            if (ticket.state != State.INSPECTED
                    || manifest.admission() != admission || ticket.inspection.descriptor() != manifest.binding().descriptor()
                    || !manifest.binding().verified() || !claims.complete())
                throw new IllegalStateException("Execution evidence requires its exact unstarted invocation");
            ticket.executionManifest = manifest;
            ticket.modClaims = claims;
        }
    }

    RegionGuard regionOwner(RegionState region) {
        return new RegionGuard(this, region, Mode.REGION_OWNER, region);
    }

    RegionGuard subordinate(RegionState region) {
        return this.subordinate(region, region);
    }

    RegionGuard subordinate(RegionState region, Object resourceHome) {
        if (Thread.currentThread() != this.coordinator) throw new IllegalStateException("Subordinate work requires its coordinator");
        return new RegionGuard(this, region, Mode.RESOURCE_SCOPED, resourceHome);
    }

    @Nullable
    Permit permit(InvocationTicket ticket, RegionGuard guard, Refresh refresh, @Nullable BlockEntityResourceOwnership.Lease resources) {
        this.requireOpen();
        synchronized (ticket) {
            if (ticket.phase != this || guard.phase != this || ticket.state != State.INSPECTED
                    || ticket.inspection != refresh.inspection())
                throw new IllegalStateException("Foreign, stale or already started block-entity authorization");
            final var inspection = ticket.inspection;
            if (refresh.outcome() != Outcome.CANDIDATE || inspection.outcome() != Outcome.CANDIDATE
                    || !this.live.getAsBoolean() || !(ticket.executionManifest != null ? ticket.executionManifest.workerCurrent()
                            : inspection.resources() == Resources.MOD_CLAIMS
                                    ? ticket.modManifest != null && ticket.modManifest.workerCurrent()
                                    : ticket.admission.isCurrent())
                    || this.home.apply(ticket.admission.position()) != guard.region)
                return null;
            final var descriptor = inspection.descriptor();
            if (descriptor == null || descriptor.mode() != guard.mode
                    || !descriptor.types().contains(ResourceLocation.tryParse(ticket.admission.type())))
                throw new IllegalStateException("Execution description does not cover this invocation");
            if (guard.mode == Mode.REGION_OWNER && !ticket.admission.isAdmitted()) return null;
            if (guard.mode == Mode.RESOURCE_SCOPED
                    && ticket.admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                    && ticket.executionManifest == null)
                return null;
            if (inspection.resources() == Resources.MOD_CLAIMS
                    && (ticket.modManifest == null || ticket.modManifest.admission() != ticket.admission
                            || ticket.modManifest.home() != guard.region))
                return null;
            if (ticket.executionManifest != null && (ticket.executionManifest.home() != guard.region
                    || ticket.executionManifest.binding().descriptor() != descriptor))
                return null;
            if (inspection.resources() == Resources.ADAPTER_CLAIMS && ticket.executionManifest == null)
                throw new IllegalStateException("Adapter claims require Core-bound execution evidence");
            if (inspection.resources() == Resources.FURNACE_CLAIMS || inspection.resources() == Resources.MOD_CLAIMS
                    || inspection.resources() == Resources.ADAPTER_CLAIMS) {
                if (resources == null || !(inspection.resources() == Resources.MOD_CLAIMS || inspection.resources() == Resources.ADAPTER_CLAIMS
                        ? resources.isEnteredFor(this.level, guard.resourceHome, ticket.modClaims)
                        : resources.isReservedFor(this.level, guard.resourceHome)))
                    throw new IllegalStateException("Invocation lacks its actual resource lease");
            } else if (resources != null) {
                throw new IllegalStateException("Unexpected resource lease on a different execution path");
            }
            return this.start(ticket, guard, resources);
        }
    }

    /** A true serial fence starts only after all worker authorities have returned. */
    Permit beginSerial(RegionBlockEntityTickAdmission admission) {
        this.requireCoordinator();
        if (this.active.get() != 0 || !this.guards.isEmpty()) throw new IllegalStateException("Serial fence overlaps worker authority");
        return this.start(this.ticket(admission), null, null);
    }

    /** Preserves the old executor-rejection path; it is not a quiescent global serial fence. */
    Permit beginInline(RegionBlockEntityTickAdmission admission, RegionGuard guard) {
        this.requireCoordinator();
        if (guard.phase != this || guard.mode != Mode.REGION_OWNER || this.home.apply(admission.position()) != guard.region)
            throw new IllegalStateException("Foreign inline region authority");
        return this.start(this.ticket(admission), guard, null);
    }

    private void requireCoordinator() {
        this.requireOpen();
        if (Thread.currentThread() != this.coordinator) throw new IllegalStateException("Server invocation requires the phase coordinator");
    }

    private Permit start(InvocationTicket ticket, @Nullable RegionGuard guard, @Nullable BlockEntityResourceOwnership.Lease resources) {
        synchronized (ticket) {
            this.requireOpen();
            if (ticket.phase != this || (ticket.state != State.CAPTURED && ticket.state != State.INSPECTED)
                    || CURRENT.get() != null)
                throw new IllegalStateException("Invocation already started or nested");
            if (guard != null) {
                synchronized (guard.region) {
                    if (guard.closed || guard.region.blockEntityExecution != guard || !guard.region.isRunning()
                            || (guard.mode == Mode.REGION_OWNER && !guard.region.isOwnedByCurrentThread())
                            || (guard.mode == Mode.RESOURCE_SCOPED && (guard.sealed || guard.region.hasOwner())))
                        throw new IllegalStateException("Invocation lost its outer region authority");
                    if (guard.mode == Mode.REGION_OWNER && guard.active != 0)
                        throw new IllegalStateException("A region owner cannot execute two native invocations at once");
                    ++guard.active;
                }
            }
            ticket.state = State.RUNNING;
            this.active.incrementAndGet();
            final var permit = new Permit(ticket, guard, resources);
            CURRENT.set(permit);
            return permit;
        }
    }

    @Override
    public synchronized void close() {
        this.requireCoordinator();
        if (this.active.get() != 0 || !this.guards.isEmpty()) throw new IllegalStateException("Cannot retire active execution authority");
        this.open = false;
        this.hasEffects = false;
        for (final var ticket : this.tickets.values()) {
            synchronized (ticket) {
                if (ticket.state == State.CAPTURED || ticket.state == State.INSPECTED) ticket.state = State.CANCELLED;
                ticket.modManifest = null;
                ticket.executionManifest = null;
                ticket.modClaims = null;
                if (ticket.effects != null) ticket.effects.clear();
            }
        }
        this.tickets.clear();
    }

    enum State {
        CAPTURED, INSPECTED, RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    static final class InvocationTicket {
        @Nullable
        private CoreModPreparation.Manifest modManifest;
        @Nullable
        private ExecutionBlockEntityPreparation.Manifest executionManifest;
        @Nullable
        private BlockEntityResourceOwnership.Claims modClaims;
        private final BlockEntityExecutionEngine phase;
        private final RegionBlockEntityTickAdmission admission;
        private volatile State state = State.CAPTURED;
        private long generation;
        private boolean ownsEffects;
        private int nextEffect;
        @Nullable
        private List<OwnedEffect> effects;
        @Nullable
        private Inspection inspection;

        private InvocationTicket(BlockEntityExecutionEngine phase, RegionBlockEntityTickAdmission admission) {
            this.phase = phase;
            this.admission = admission;
        }

        Inspection inspection() {
            if (this.inspection == null) throw new IllegalStateException("Invocation has no inspection");
            return this.inspection;
        }

        State state() {
            return this.state;
        }
    }

    static final class RegionGuard implements AutoCloseable {
        private final BlockEntityExecutionEngine phase;
        private final RegionState region;
        private final Mode mode;
        private final Object resourceHome;
        private final Thread coordinator = Thread.currentThread();
        private int active;
        private boolean closed;
        private boolean sealed;

        private RegionGuard(BlockEntityExecutionEngine phase, RegionState region, Mode mode, Object resourceHome) {
            this.phase = phase;
            this.region = region;
            this.mode = mode;
            this.resourceHome = resourceHome;
            synchronized (region) {
                phase.requireOpen();
                if (region.coordinate().level() != phase.level || !region.isRunning() || region.blockEntityExecution != null
                        || (mode == Mode.REGION_OWNER ? !region.isOwnedByCurrentThread() : region.hasOwner()))
                    throw new IllegalStateException("Conflicting block-entity region authorities");
                region.blockEntityExecution = this;
                phase.guards.add(this);
            }
        }

        /** Fine workers cannot acquire full ownership; only the joined coordinator can commit. */
        boolean mayBeginOwnership() {
            return !this.closed && this.coordinator == Thread.currentThread() && this.active == 0
                    && (this.mode == Mode.REGION_OWNER || this.sealed);
        }

        /** After join (or cancellation before submission), revoke all future starts before commit. */
        void sealForCommit() {
            synchronized (this.region) {
                if (this.coordinator != Thread.currentThread() || this.closed || this.active != 0 || this.mode != Mode.RESOURCE_SCOPED)
                    throw new IllegalStateException("Cannot commit before subordinate work returns");
                this.sealed = true;
            }
        }

        @Override
        public void close() {
            synchronized (this.region) {
                if (this.coordinator != Thread.currentThread() || this.closed || this.active != 0
                        || this.region.blockEntityExecution != this)
                    throw new IllegalStateException("Cannot release running, foreign or closed region authority");
                this.closed = true;
                this.region.blockEntityExecution = null;
                this.phase.guards.remove(this);
            }
        }
    }

    static final class Permit implements AutoCloseable {
        private final InvocationTicket ticket;
        @Nullable
        private final RegionGuard guard;
        @Nullable
        private final BlockEntityResourceOwnership.Lease resources;
        private final Thread thread = Thread.currentThread();
        private boolean succeeded;
        private boolean closed;

        private Permit(InvocationTicket ticket, @Nullable RegionGuard guard, @Nullable BlockEntityResourceOwnership.Lease resources) {
            this.ticket = ticket;
            this.guard = guard;
            this.resources = resources;
        }

        private void requireCurrent() {
            if (Thread.currentThread() != this.thread || CURRENT.get() != this || this.closed)
                throw new IllegalStateException("Foreign, nested or closed invocation permit");
        }

        void succeeded() {
            this.requireCurrent();
            if (this.succeeded || (this.resources != null && !this.resources.consumed()))
                throw new IllegalStateException("Invocation already completed or resource scope has not returned");
            this.succeeded = true;
        }

        @Override
        public void close() {
            this.requireCurrent();
            if (this.resources != null && this.resources.entered())
                throw new IllegalStateException("Cannot release an invocation with an active resource scope");
            this.closed = true;
            this.ticket.state = this.succeeded ? State.SUCCEEDED : State.FAILED;
            CURRENT.remove();
            if (this.guard != null) {
                synchronized (this.guard.region) {
                    --this.guard.active;
                }
            }
            this.ticket.phase.active.decrementAndGet();
        }
    }
}
