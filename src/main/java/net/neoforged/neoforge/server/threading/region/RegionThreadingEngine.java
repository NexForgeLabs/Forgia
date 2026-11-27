/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.server.threading.BlockEntityOperationRuntime;
import net.neoforged.neoforge.server.threading.EntityInteractionScheduler;
import net.neoforged.neoforge.server.threading.EntityInteractionTask;
import net.neoforged.neoforge.server.threading.EntityRegionScheduler;
import net.neoforged.neoforge.server.threading.GlobalRegionScheduler;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionScheduler;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter.Access;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter.Mode;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.AdapterDescriptor;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Inspection;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Outcome;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Refresh;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Resources;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Native NeoForge region threading engine shell.
 * <p>
 * This class is intentionally small until the Folia regionizer and tick loop are ported.
 * <p>
 * The engine owns the server-thread side of every deferred effect of a region worker. For the entity tick
 * slice that is the section publication and target chunk acquisition of a move, the level callback of a
 * removal and the admission of a new entity, applied in that order at the barrier of one entity batch
 * (section updates, removals, admissions, fallback ticks, server tasks, ownership publication). The
 * admission is the one step that changes a caller-visible contract: on a native worker whose lease covers
 * the entity's position, {@code ServerLevel.addFreshEntity} answers <b>"accepted for this tick"</b> instead
 * of "already in the world". The entity is not visible to the level for the rest of the worker tick and the
 * barrier admits it afterwards through the unmodified vanilla path; an admission the barrier cannot apply is
 * <b>dropped</b>, which loses the entity, and is counted in
 * {@link RegionThreadingDiagnostics#deferredEntityAdditionsDropped()} next to the applied and recorded
 * admissions. Entity construction events ({@code EntityEvent.EntityConstructing}, {@code EntityEvent.Size})
 * therefore fire on the worker thread; that is a documented compatibility exposure, not a defect of the
 * admission.
 * <p>
 * The block event phase is the one part of the level tick that is not a region slice: it drains the level's
 * ordered block event set on the server thread after every region batch of the tick returned, so no worker runs
 * and no region is leased by another thread. The phase therefore runs as a server-thread quiescent scope
 * ({@link #beginBlockEventPhase(ServerLevel)}): it takes the lease of each region its events run in once, keeps it
 * until the phase ends and captures no border update, so a write of an event body to another region is the
 * vanilla inline call and the phase needs no border fixpoint at all. That is both cheaper than leasing and
 * flushing per event and faithful to the inline run: an event body reads back what it wrote, so a piston that
 * pushes across a border resolves its structure against the moved blocks and queues the single block event
 * vanilla queues, not a second one from a stale read.
 * <p>
 * The game event dispatch is the one cross-region write that is not a block write: {@code GameEventDispatcher.post}
 * reaches every listener of the event's notification radius - 16 blocks for the common events, 32 for
 * {@code shriek} - and a listener writes its own block entity, so with a 3-chunk region the dispatch crosses a
 * border in the common case. {@link #captureCrossRegionGameEvent} records a section column whose listeners belong
 * to another region instead of visiting it, and {@link #runCrossRegionGameEvents} replays the recorded columns on
 * the owning region's worker in the same tick, in recording order, after the border fixpoint of the batch. The
 * replay uses the vanilla listener registry of the target column, so the candidate a sculk sensor records carries
 * the same source position, distance and game time as the inline run, and the sensor activates on the same tick.
 * The switch is off by default: with it off the dispatch is exactly vanilla and therefore races as before, which
 * is the documented baseline of the feature.
 */
public final class RegionThreadingEngine implements RegionThreading {
    static final Logger LOGGER = LogManager.getLogger();
    static final int MAX_REGION_LEASE_ATTEMPTS = 4;
    /**
     * Upper bound of the server thread's wait for a native global drain to release the global region. The
     * drain itself is bounded by the per-drain task limits, so this only guards against a worker that never
     * returns instead of letting the tick loop hang forever.
     */
    private static final long GLOBAL_REGION_WAIT_NANOS = 50_000_000L;
    /**
     * Number of entity batch barriers a deferred entity move waits for a busy region before it is published
     * through the legacy path. Every attempt happens at a barrier on the server thread, so the bound is
     * counted in barriers and never in a spin.
     */
    static final int MAX_DEFERRED_ENTITY_MOVE_ATTEMPTS = 3;
    /**
     * Chunk margin around an entity's own chunk that the server thread requires to be loaded before it
     * submits that entity to a worker.
     * <p>
     * One chunk is 16 blocks and covers every block, fluid and collision read a vanilla entity tick makes
     * with a loading request: the collision box and the eye position are read at most 2 blocks from the
     * entity, and the AI block scans that reach further do not use the loading accessor
     * ({@code RemoveBlockGoal} probes its 24-block search range with {@code getChunk(..., false)}, and
     * pathfinding reads a {@code PathNavigationRegion} snapshot). A read that still leaves the margin is
     * refused by the chunk source guard and handed to the server-thread fallback, so the margin is a
     * heuristic that keeps the common case off the chunk source, not a correctness bound.
     */
    static final int ENTITY_TICK_CHUNK_MARGIN = 1;
    /** Temporary switch of the activation diagnostics of the self-driven entity tick. */
    /** Sentinels of the per batch neighborhood cache: chunk not asked yet, computed not loaded, loaded. */
    static final byte NEIGHBORHOOD_UNKNOWN = -1;
    static final byte NEIGHBORHOOD_NOT_LOADED = 0;
    static final byte NEIGHBORHOOD_LOADED = 1;
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST = new java.util.concurrent.atomic.AtomicLong();
    static final BasicRegionThreadingContext NONE_CONTEXT = new BasicRegionThreadingContext(RegionThreadingContext.Kind.NONE, null, null, null, null);
    private static final ThreadLocal<RegionThreadingContext> CURRENT_CONTEXT = ThreadLocal.withInitial(() -> NONE_CONTEXT);

    final RegionLayout layout;
    final RegionWorkAdmission regionWorkAdmission = new RegionWorkAdmission();
    final GlobalRegionState globalRegion = new GlobalRegionState(this.regionWorkAdmission);
    final ThreadedRegionizer regionizer;
    /**
     * Per-level Folia-style dynamic region topology, populated only when {@link #layout} uses
     * {@link RegionPolicy#DYNAMIC_CLUSTER}. Cleared on level unload and engine shutdown.
     */
    private final Map<ServerLevel, DynamicRegionTopology> dynamicTopologies = new ConcurrentHashMap<>();
    private final Map<ServerLevel, AtomicLong> dynamicTopologyTicks = new ConcurrentHashMap<>();
    /**
     * Registered entity scheduled tasks, grouped by the entity they belong to.
     * <p>
     * A move notification only reaches the wrappers of its own entity, so the ownership handoff of a barrier
     * looks the entity up instead of scanning every registered task: an unchanged handoff can then skip the
     * scan for an entity that has no wrapper at all, and a changed one costs the wrappers of that entity.
     * Every task is registered and unregistered through the same entry, so the entry of an entity disappears
     * with its last wrapper and no entity stays reachable through an empty group.
     */
    final Map<Entity, Set<EntityRegionScheduledTask>> entityTasksByEntity = new ConcurrentHashMap<>();
    final RegionWorkerPool workerPool;
    private final GlobalRegionTickRunner globalTickRunner = new GlobalRegionTickRunner();
    private final RegionTickRunner tickRunner = new RegionTickRunner();
    private final GlobalRegionScheduler globalScheduler = new EngineGlobalRegionScheduler(this);
    private final RegionScheduler regionScheduler = new EngineRegionScheduler(this);
    private final EntityRegionScheduler entityScheduler = new EngineEntityRegionScheduler(this);
    private final boolean workerExecutionEnabled;
    private final boolean globalWorkerExecutionEnabled;
    private final boolean entityTickWorkerExecutionEnabled;
    private final boolean blockEntityTickWorkerExecutionEnabled;
    private final boolean scheduledTickWorkerExecutionEnabled;
    private final boolean fluidTickWorkerExecutionEnabled;
    private final Set<net.minecraft.world.level.material.Fluid> demotedFluidTypes = ConcurrentHashMap.newKeySet();
    private final RegionScheduledTickMetrics[] scheduledTickMetrics = { new RegionScheduledTickMetrics(), new RegionScheduledTickMetrics() };
    private final RegionScheduledTickCoordinator scheduledTickCoordinator;
    final RegionIndependentTickScheduler independentTickScheduler;
    private volatile boolean independentRegionTickOverride;
    private final boolean chunkTickWorkerExecutionEnabled;
    final RegionChunkTickCoordinator chunkTickCoordinator;
    private final RegionSpawnCoordinator spawnCoordinator;
    private final RegionBorderPropagationCoordinator borderPropagationCoordinator;
    private final RegionBlockEntityTickCoordinator blockEntityTickCoordinator;
    private final RegionEntityTickCoordinator entityTickCoordinator;
    final RegionUnifiedTickCoordinator unifiedTickCoordinator;
    private final RegionEntityTransferCoordinator entityTransferCoordinator;
    private final RegionPlayerPrefixCoordinator playerPrefixCoordinator;
    private final RegionDiagnosticsCollector diagnosticsCollector;
    private final RegionSliceCriticalPathMetrics criticalPathMetrics = new RegionSliceCriticalPathMetrics();
    final RegionFenceMetrics fenceMetrics = new RegionFenceMetrics();
    final RegionMainThreadPhaseMetrics mainThreadPhaseMetrics = new RegionMainThreadPhaseMetrics();
    private final RegionItemCleanupManager itemCleanupManager = new RegionItemCleanupManager();
    private final RegionOverloadGovernor.Config overloadConfig;
    @Nullable
    final RegionOverloadGovernor overloadGovernor;
    private volatile boolean callerRunsEnabled;
    private volatile boolean parallelBorderFixpointEnabled;
    volatile boolean canonicalFenceTailEnabled;
    final FusedRegionTickSession fusedSession = new FusedRegionTickSession();
    private volatile boolean fusedSlicesEnabled;
    volatile boolean activeFusedBatch;
    @Nullable
    it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<RegionState> activeCanonicalBeRegionCosts;
    @Nullable
    String activeCanonicalBeDimensionId;
    @Nullable
    it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<RegionState> activeCanonicalBeChunkRegions;
    boolean activeCanonicalPureSerial;
    private volatile boolean spawnWorkerExecutionEnabled;
    /**
     * Whether a slice leases several regions into one worker task. Off means one task per region, exactly like
     * before packing existed.
     */
    private final boolean packedRegionLeasesEnabled;
    /** Packed worker tasks this engine submitted. */
    final AtomicLong packedTasks = new AtomicLong();
    /** Regions the packed tasks of this engine hold; divided by {@link #packedTasks} it is the regions per task. */
    final AtomicLong packedRegions = new AtomicLong();
    /**
     * Packed acquisitions that could not take every region of their pack and released what they took. The
     * affected regions were leased one by one instead; a growing count means the pool and the region work of the
     * previous tick overlap, not that a region was skipped.
     */
    final AtomicLong packedLeaseFallbacks = new AtomicLong();
    private final AtomicLong rejectedWorkerTaskCount = new AtomicLong();
    private final AtomicLong failedQueuedTaskCount = new AtomicLong();
    final AtomicLong entityTicksOnWorkers = new AtomicLong();
    final AtomicLong skippedEntityTicks = new AtomicLong();
    final AtomicLong fallbackEntityTicks = new AtomicLong();
    private final AtomicLong deferredEntityTicks = new AtomicLong();
    /**
     * Entity ticks a worker stopped because the tick reached a chunk the lease does not hold.
     * <p>
     * A chunk refusal inside the body is not replayable: the body has already written entity fields, deferred
     * removals, block updates or block drops by the time the read fails, so replaying it would apply them
     * twice. The tick is therefore lost and counted here, and the entity's runtime class is declared
     * server-thread-only on the spot through {@link RegionEntityTickPolicy#serverThreadOnly}. The loss happens
     * once per class, not once per tick, and it is a lost tick instead of a stopped server - the failure this
     * replaces took the 178-mod pack down four times (`crash-2026-11-09_11.45.26`, `11.51.15`,
     * `2026-11-08_03.33.25`, `2026-11-10_05.59.18`).
     * <p>
     * This is not {@link #fallbackEntityTicks}: that one is decided before the body starts, so the entire
     * vanilla tick runs on the server thread. {@code chunkFallbackEntityTicks} has the same legacy note -
     * partial ticks are never replayed.
     */
    private final AtomicLong truncatedEntityTicks = new AtomicLong();
    /** Classes already reported for a truncated tick, so one class cannot flood the log. */
    private final Set<Class<?>> truncatedEntityTickClasses = ConcurrentHashMap.newKeySet();
    /** Entity ticks truncated because a worker crossed a storage or player-state boundary guard. */
    private final AtomicLong boundaryFallbackEntityTicks = new AtomicLong();
    /** Classes already demoted to the server thread due to a worker boundary violation. */
    private final Set<Class<?>> boundaryFallbackEntityTickClasses = ConcurrentHashMap.newKeySet();
    /** Block-entity ticks truncated because a worker crossed a storage, capability or global-state boundary guard. */
    private final AtomicLong boundaryFallbackBlockEntityTicks = new AtomicLong();
    /** Block-entity types already demoted to the server thread due to a worker boundary violation. */
    private final Set<String> boundaryFallbackBlockEntityTypes = ConcurrentHashMap.newKeySet();
    /**
     * Entity ticks a worker handed back because the chunk source refused a chunk the tick needed. Each one
     * is ticked on the server thread after the batch instead of blocking the worker on the chunk source.
     */
    private final AtomicLong chunkFallbackEntityTicks = new AtomicLong(); // Legacy accessor: partial ticks are never replayed.
    /**
     * Entities the pre-load check refused because their own chunk neighborhood was not loaded. They are
     * ticked on the server thread like the entities whose region could not be leased, while the rest of
     * their region still runs on a worker.
     */
    final AtomicLong chunkPreloadRefusedEntityTicks = new AtomicLong();
    /** Regions the self-driven entity tick leased and submitted one worker task for. */
    final AtomicLong entityTickRegionsOnWorkers = new AtomicLong();
    /**
     * Regions whose entity tick could not be enumerated safely (no lease, no chunk rectangle, a retired
     * region) and whose entities were therefore ticked on the server thread instead of being dropped.
     */
    final AtomicLong fallbackEntityTickRegions = new AtomicLong();
    /**
     * Regions with a ticking entity whose own chunks are all outside the vanilla entity ticking range. Such a
     * region is still submitted: its entity body runs the despawn check and skips the tick, exactly like the
     * vanilla pass would.
     */
    final AtomicLong entityTickRegionsWithoutEligibleChunk = new AtomicLong();
    /** Entities of a region whose position left the region's chunk rectangle, ticked on the server thread. */
    final AtomicLong entityTicksOutsideRegion = new AtomicLong();
    /** Always-ticking entities (players) a region handed to the server thread instead of ticking on a worker. */
    final AtomicLong serverThreadOnlyEntityTicks = new AtomicLong();
    /** Entity tick list entries the region index could not take; they keep the server-thread path. */
    private final AtomicLong unindexedEntityTickStarts = new AtomicLong();
    /** Level ticks that fell back to the per-entity entity plan because the level's region index is unavailable. */
    private final AtomicLong entityTickIndexFallbacks = new AtomicLong();
    /**
     * The region transitions of players: the defined publication point of a player's region, the counters of the
     * transitions by kind and the one-region invariant.
     */
    final PlayerRegionTransitions playerRegions = new PlayerRegionTransitions();
    final RegionPlayerTickCoordinator playerTicks = new RegionPlayerTickCoordinator(this);
    final RegionPlayerNetworkGate playerNetworkGate = new RegionPlayerNetworkGate();
    final RegionPlayerPacketRouter playerPackets = new RegionPlayerPacketRouter();
    final Map<String, AtomicLong> playerAdmissionRefusals = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, String> playerAdmissionLastRefusal = new java.util.concurrent.ConcurrentHashMap<>();
    final AtomicLong playerTicksAdmittedWithMods = new AtomicLong();
    // Publisher-only, weak keys: an AttributeMap's supplier is final and its instance table only grows.
    private final Map<net.minecraft.world.entity.ai.attributes.AttributeMap, Boolean> warmedPlayerAttributes = new java.util.WeakHashMap<>();
    // Mutable local-object aliases are resource conflicts, never a second entity identity ledger.
    final java.util.IdentityHashMap<Object, RegionPlayerSpatialLease> playerStateClaims = new java.util.IdentityHashMap<>();
    final RegionPlayerPrefixClaims playerPrefixClaims = new RegionPlayerPrefixClaims();
    volatile boolean playerTickPlanningRequested = RegionThreadingConfig.playerTicksRequested();
    /**
     * Levels whose entity tick index was seeded from the level's authoritative tick list.
     */
    private final Set<Object> seededEntityTickLevels = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /**
     * Chunk-load answers of the current loaded-chunk generation of each level.
     * <p>
     * The pre-load margin of the entity tick asks the same chunk neighborhood every tick, and a chunk changes
     * its loaded state only while the level's loaded chunk set changes. The answers are therefore kept until
     * the level reports a different loaded chunk count; both directions of a stale answer are safe - a stale
     * "loaded" is refused by the worker chunk guard and ticked on the server thread, a stale "not loaded"
     * keeps the entity on the server thread - so the cache only decides where an entity ticks, never whether
     * it ticks.
     */
    final Map<ServerLevel, ChunkLoadAnswers> chunkLoadAnswers = new ConcurrentHashMap<>();
    /**
     * The global/region phase state of the tick of each level.
     * <p>
     * The state exists for every level whose tick was opened by the engine bridge, so the state machine can
     * assert that no region batch is in flight while the level's global statements run. A level without a
     * tick entry - direct engine use from a mechanics test or a benchmark - reads as
     * {@link LevelTickPhase#GLOBAL} and counts its region phases as work outside a level tick; see
     * {@link LevelTickPhase}.
     */
    private final Map<ServerLevel, LevelTickPhaseState> levelTickPhases = new ConcurrentHashMap<>();
    /**
     * The cross-region mailbox of each level that has one. A level without an entry has no mailbox and pays
     * one map lookup per level tick entry and per region phase end; the entry is created by the first post.
     */
    final Map<ServerLevel, RegionMailbox> mailboxes = new ConcurrentHashMap<>();
    /**
     * Server-thread tasks a region worker queued while it was <b>not</b> inside an entity batch.
     * <p>
     * Region scoped spawning, chunk ticks and block entity ticks run on workers without an entity batch, yet a mod
     * can add an entity from any of them: Primal spawns a jockey from {@code Zombie.finalizeSpawn} during natural
     * spawning, and that addition found neither a lease to defer to nor a batch to hand off to, so the storage
     * guard failed the whole world tick. The queue is drained by the server thread in
     * {@link #endLevelTick(ServerLevel)}, after every worker of the tick has finished, which keeps the vanilla
     * "added after this tick's entity pass, therefore first ticked next tick" shape.
     */
    private final Map<ServerLevel, ConcurrentLinkedQueue<Runnable>> workerServerTasks = new ConcurrentHashMap<>();
    private final AtomicLong workerServerTasksQueued = new AtomicLong();
    private final AtomicLong workerServerTasksApplied = new AtomicLong();
    private final AtomicLong workerServerTasksDropped = new AtomicLong();
    /**
     * Neighbor cascades a region worker had to hand to the server thread because they reached a chunk it may not
     * load. A chunk at the edge of the loaded area is the common reason, and vanilla would have loaded it inline.
     */
    private final AtomicLong handedOffNeighborCascades = new AtomicLong();
    private final AtomicLong deferredMeleeAttacks = new AtomicLong();
    private final AtomicLong appliedMeleeAttacks = new AtomicLong();
    private final AtomicLong skippedMeleeAttacks = new AtomicLong();
    private final AtomicLong failedMeleeAttacks = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicLongArray interactionOutcomes = new java.util.concurrent.atomic.AtomicLongArray(EntityInteractionTask.State.values().length);
    private final AtomicLong interactionAdmissions = new AtomicLong();
    private final EntityInteractionScheduler entityInteractionScheduler = new EntityInteractionScheduler() {
        @Override
        public EntityInteractionTask submit(Entity source, Entity target, BiConsumer<Entity, Entity> action) {
            return RegionThreadingEngine.this.submitEntityInteraction(source, target, action);
        }

        @Override
        public EntityInteractionTask submitToOwnerAfterChunkLoad(Entity source, Entity owner, BlockPos position,
                BiConsumer<Entity, LevelChunk> action) {
            java.util.Objects.requireNonNull(action, "action");
            return RegionThreadingEngine.this.submitEntityInteraction(source, owner, null, null, false,
                    java.util.Objects.requireNonNull(position, "position").immutable(), action);
        }

        @Override
        public EntityInteractionTask submitToOwner(Entity source, Entity owner, Consumer<Entity> action) {
            java.util.Objects.requireNonNull(action, "action");
            return RegionThreadingEngine.this.submitEntityInteraction(source, owner, null, action, false);
        }
    };

    public Map<String, Long> entityInteractionCounters() {
        final Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("admitted", this.interactionAdmissions.get());
        for (final EntityInteractionTask.State state : EntityInteractionTask.State.values()) {
            if (state != EntityInteractionTask.State.QUEUED && state != EntityInteractionTask.State.RUNNING) {
                counts.put(state.name(), this.interactionOutcomes.get(state.ordinal()));
            }
        }
        return Map.copyOf(counts);
    }

    public record MeleeAttackCounters(long queued, long applied, long skipped, long failed) {}

    public MeleeAttackCounters meleeAttackCounters() {
        return new MeleeAttackCounters(this.deferredMeleeAttacks.get(), this.appliedMeleeAttacks.get(),
                this.skippedMeleeAttacks.get(), this.failedMeleeAttacks.get());
    }

    /*
     * The mailbox counters belong to the engine, not to one mailbox: a mailbox dies with its level, and the
     * messages it dropped on the close have to stay observable in the diagnostics after that.
     */
    private final AtomicLong mailboxAcceptedMessages = new AtomicLong();
    private final AtomicLong mailboxDeliveredMessages = new AtomicLong();
    private final AtomicLong mailboxRefusedFullMessages = new AtomicLong();
    private final AtomicLong mailboxRefusedClosedMessages = new AtomicLong();
    private final AtomicLong mailboxDroppedRetiredMessages = new AtomicLong();
    private final AtomicLong mailboxDroppedOnCloseMessages = new AtomicLong();
    private final AtomicLong mailboxDeliveryFailures = new AtomicLong();

    /**
     * The captured border updates of each level. A region worker records an update that targets another region
     * here instead of applying it; the server thread drains the queue after the worker phase and runs the
     * same-tick fixpoint. The map is empty for a level without border updates and for a server whose switch is
     * off.
     */
    private final Map<ServerLevel, RegionBorderUpdates> borderUpdates = new ConcurrentHashMap<>();
    /**
     * Whether the installed engine captures cross-region border updates. It is the engine's copy of
     * {@code enableRegionThreadingBorderUpdates}, taken at construction so a batch never re-reads the config.
     */
    volatile boolean borderUpdatesEnabled = RegionThreadingConfig.borderUpdatesEnabled();
    /** Test override of the fixpoint bound; a negative value reads the level's vanilla limit. */
    private volatile int borderUpdateBudgetForTesting = -1;
    /** Entries one capture queue of a level admits; the production bound unless a test lowers it. */
    private volatile int borderUpdateCapacity = RegionBorderUpdates.MAX_ENTRIES;
    /** Border updates a worker recorded instead of applying. */
    private final AtomicLong borderUpdatesRecorded = new AtomicLong();
    /** Border updates a propagation pass applied on the target region's worker. */
    private final AtomicLong borderUpdatesApplied = new AtomicLong();
    /** Border updates the fixpoint bound pushed to the target region's next tick through the mailbox. */
    private final AtomicLong borderUpdatesDelayed = new AtomicLong();
    /** Border updates neither applied nor delayed, i.e. a closed mailbox or a retired target. */
    private final AtomicLong borderUpdatesDropped = new AtomicLong();
    /** Full fixpoint passes the server thread ran after a worker phase. */
    private final AtomicLong borderUpdatePasses = new AtomicLong();
    /** Region propagation groups the fixpoint re-ran. */
    private final AtomicLong borderUpdateRegionsRerun = new AtomicLong();
    /** Worst number of passes one worker phase needed. */
    private final AtomicLong borderUpdateWorstPasses = new AtomicLong();
    /** Border updates the capture bound refused, which were handed to the next-tick fallback. */
    private final AtomicLong borderUpdatesOverflowed = new AtomicLong();
    /** Block writes a worker recorded for the region that owns the written position. */
    private final AtomicLong borderBlockWritesRecorded = new AtomicLong();
    /** Block writes a propagation pass applied on the target region's worker. */
    private final AtomicLong borderBlockWritesApplied = new AtomicLong();
    /** Block writes the capture bound refused at the call site, i.e. the caller's write was not applied. */
    private final AtomicLong borderBlockWritesOverflowed = new AtomicLong();
    /** Block writes whose target chunk was no longer loaded when the fixpoint grouped them. */
    private final AtomicLong borderBlockWritesDropped = new AtomicLong();
    /** Reads of a position the same region wrote outside its lease during the same lease. */
    private final AtomicLong readAfterDeferredBlockWrite = new AtomicLong();
    /**
     * The regions of each level that read a chunk of another region since their last version check.
     * <p>
     * A region adds itself on the first foreign read after a check and the fixpoint removes it when it checks
     * the region, so the fixpoint only walks the regions that actually read across a border. A level that never
     * does has no entry at all.
     */
    private final Map<ServerLevel, Set<RegionState>> regionsWithForeignReads = new ConcurrentHashMap<>();
    /** Foreign chunk reads a region recorded, i.e. one per chunk and region and pass. */
    private final AtomicLong foreignChunkReads = new AtomicLong();
    /** Stable reads of a versioned chunk that no region owns. */
    private final AtomicLong readOfUnownedChunk = new AtomicLong();
    /** Native-worker requests refused because their chunk has no seqlock version. */
    private final AtomicLong readOfVersionlessChunk = new AtomicLong();
    /** Fixpoint checks in which a region observed a changed version of a foreign chunk it read. */
    private final AtomicLong foreignReadInvalidatedPasses = new AtomicLong();
    /** Region propagation groups the fixpoint re-ran because a foreign read was invalidated. */
    private final AtomicLong foreignReadRegionReruns = new AtomicLong();
    /** Written positions a region dropped because the re-run seed bound of the region was reached. */
    private final AtomicLong foreignReadSeedsDropped = new AtomicLong();
    /**
     * Whether a changed foreign chunk version re-runs the propagation of the reader. The mechanics seam of the
     * race tests turns it off to show the stale read the re-run repairs.
     */
    private volatile boolean foreignReadRerunEnabled = true;
    /** Block events a region worker recorded for the server thread's block event phase. */
    private final AtomicLong deferredBlockEvents = new AtomicLong();
    /** Block event phases that ran as a quiescent server-thread scope. */
    private final AtomicLong blockEventPhases = new AtomicLong();
    /** Region leases the block event phases took, i.e. one per region and phase that ran an event. */
    private final AtomicLong blockEventPhaseLeases = new AtomicLong();
    /** Region leases the block event phases released, which equals the leases unless a phase was cut short. */
    private final AtomicLong blockEventPhaseReleases = new AtomicLong();
    /** Events the block event phases ran inside their scope. */
    private final AtomicLong blockEventPhaseEvents = new AtomicLong();
    /** Events of a phase that ran without a region lease because its region could not be leased. */
    private final AtomicLong blockEventPhaseLeaseFallbacks = new AtomicLong();
    /** Border fixpoints a block event phase ran, which is zero for every quiescent phase. */
    private final AtomicLong blockEventPhaseFixpoints = new AtomicLong();
    /** Wall time of the block event phases that ran as a quiescent scope. */
    private final AtomicLong blockEventPhaseNanos = new AtomicLong();
    /** Wall time of the slowest block event phase that ran as a quiescent scope. */
    private final AtomicLong blockEventPhaseMaxNanos = new AtomicLong();
    /**
     * The block event phase of the current thread, or {@code null} outside one.
     * <p>
     * A phase is opened on the server thread while every region batch of the level has returned, so no worker
     * runs and no region is leased by another thread. The scope holds the lease of each region its events run in
     * until the phase ends, and it does not capture border updates: a write to another region is applied inline
     * by the server thread, which is the vanilla call and needs no fixpoint. See
     * {@link #beginBlockEventPhase(ServerLevel)}.
     */
    final ThreadLocal<BlockEventPhase> blockEventPhase = new ThreadLocal<>();
    /** Server-thread fallback after this batch's groups have returned; unrelated active batches forbid it. */
    final ThreadLocal<BlockEntityFallbackPhase> blockEntityFallbackPhase = new ThreadLocal<>();
    final AtomicLong blockEntityFallbackPhases = new AtomicLong();
    final AtomicLong blockEntityFallbackLeases = new AtomicLong();
    final AtomicLong blockEntityFallbackReleases = new AtomicLong();
    final AtomicLong blockEntityFallbackCallbacks = new AtomicLong();
    volatile boolean blockEntityFallbackScopeEnabled = true;
    final AtomicLong blockEntityFallbackScopeRefusals = new AtomicLong();

    @ApiStatus.Internal
    public void setBlockEntityFallbackScopeEnabledForTesting(boolean enabled) {
        this.blockEntityFallbackScopeEnabled = enabled;
    }

    /** Work stays in its collected order; the scope retains only leases, never reorders tickers by region. */
    static final class BlockEntityFallbackPhase {
        final ServerLevel level;
        final Map<RegionCoordinate, RegionState> leases = new LinkedHashMap<>();

        BlockEntityFallbackPhase(ServerLevel level) {
            this.level = level;
        }
    }

    public record BlockEntityFallbackScopeCounters(long phases, long leases, long releases, long callbacks, long refused) {}

    public BlockEntityFallbackScopeCounters blockEntityFallbackScopeCounters() {
        return new BlockEntityFallbackScopeCounters(this.blockEntityFallbackPhases.get(), this.blockEntityFallbackLeases.get(),
                this.blockEntityFallbackReleases.get(), this.blockEntityFallbackCallbacks.get(), this.blockEntityFallbackScopeRefusals.get());
    }

    /** Block entity reads a region group made against a quiescent foreign region. */
    private final AtomicLong foreignBlockEntityReads = new AtomicLong();
    /**
     * The captured game event columns of each level. A region worker records a section column of a game event
     * dispatch whose listeners belong to another region here instead of visiting it; the server thread drains
     * the queue after the worker phase and replays it on the owning region's worker. The map is empty for a
     * level without a captured column and for a server whose switch is off.
     */
    private final Map<ServerLevel, RegionBorderGameEvents> borderGameEvents = new ConcurrentHashMap<>();
    /**
     * Whether the installed engine captures the game events whose listeners belong to another region. It is the
     * engine's copy of {@code enableRegionThreadingGameEvents}, taken at construction so a dispatch never
     * re-reads the config.
     */
    private volatile boolean crossRegionGameEventsEnabled = RegionThreadingConfig.crossRegionGameEventsEnabled();
    /** Entries one game event capture queue of a level admits; the production bound unless a test lowers it. */
    private volatile int gameEventCapacity = RegionBorderGameEvents.MAX_ENTRIES;
    /** Game events posted while a region batch of their level was in flight. */
    private final AtomicLong gameEventsPosted = new AtomicLong();
    /** Section columns of a dispatch the posting worker visited itself, because it owns them. */
    private final AtomicLong gameEventSectionsLocal = new AtomicLong();
    /** Section columns a worker recorded for the region that owns them. */
    private final AtomicLong gameEventSectionsDeferred = new AtomicLong();
    /** Captured columns the owning region replayed on its own worker. */
    private final AtomicLong gameEventSectionsDelivered = new AtomicLong();
    /** Captured columns the capture bound refused at the call site, i.e. the event of that column was dropped. */
    private final AtomicLong gameEventSectionsRefused = new AtomicLong();
    /** Captured columns that were never replayed: the target region retired, its chunk unloaded or the bound cut them. */
    private final AtomicLong gameEventSectionsDropped = new AtomicLong();
    /** The dropped columns whose target region did not exist or was retired when the delivery ran. */
    private final AtomicLong gameEventSectionsDroppedNoRegion = new AtomicLong();
    /** The dropped columns whose target chunk was no longer loaded when the delivery ran. */
    private final AtomicLong gameEventSectionsDroppedNoChunk = new AtomicLong();
    /** Delivery rounds the server thread ran after a worker phase. */
    private final AtomicLong gameEventDeliveryPasses = new AtomicLong();
    /** Level saves the engine observed since it was installed. */
    private final AtomicLong levelSaves = new AtomicLong();
    /**
     * Level saves that started while a region tick batch of the level was still in flight.
     * <p>
     * A save may only observe a level whose last tick is fully applied: a batch that has not reached its
     * barrier still holds a worker, a region lease and the deferred effects its groups recorded. This counter
     * is therefore a correctness violation, not a diagnostic, and it is zero for every save of a server that
     * ticks its levels normally.
     */
    private final AtomicLong levelSavesWithBatchInFlight = new AtomicLong();
    /** Level saves that observed deferred effects of a batch that its barrier had not applied yet. */
    private final AtomicLong levelSavesWithTickDeferredWork = new AtomicLong();
    /** Level saves that observed cross-tick deferred work, which a crash loses by design. */
    private final AtomicLong levelSavesWithCrossTickDeferredWork = new AtomicLong();
    /** Entries of cross-tick deferred work the saves observed. */
    private final AtomicLong levelSaveCrossTickDeferredEntries = new AtomicLong();
    /** POI columns written in memory but not yet on disk when a save started. */
    private final AtomicLong levelSaveDirtyPoiColumns = new AtomicLong();
    /** POI columns a flushing save wrote and waited for before it returned. */
    private final AtomicLong levelSaveFlushedPoiColumns = new AtomicLong();
    /** Rate limiter of the save-point warning: the first few observations are logged, the rest only counted. */
    private static final int MAX_LOGGED_SAVE_VIOLATIONS = 4;
    private final AtomicLong loggedSaveViolations = new AtomicLong();

    /**
     * One block event phase of one level on the server thread.
     * <p>
     * The scope holds the lease of every region an event of the phase ran in, keyed by the region coordinate, so
     * a phase that runs several events of the same region takes that region's lease once. Every lease is released
     * in {@link #endBlockEventPhase(ServerLevel)}, including when an event throws.
     */
    private static final class BlockEventPhase {
        final ServerLevel level;
        final Map<RegionCoordinate, RegionState> leases = new LinkedHashMap<>();
        private final long startNanos;
        private int events;
        /**
         * Openings that share this scope. A second opening of the same level's phase reuses the scope and only
         * the matching close ends it, so a caller that opens and closes symmetrically can never close the scope
         * of an enclosing phase.
         */
        private int openings = 1;

        private BlockEventPhase(ServerLevel level) {
            this.level = level;
            this.startNanos = System.nanoTime();
        }
    }

    /** One level's chunk-load answers and the loaded chunk count they were read at. */
    static final class ChunkLoadAnswers {
        final int loadedChunkCount;
        final Long2ByteMap answers = new Long2ByteOpenHashMap();
        /** The derived one-chunk-margin answer per chunk, so a chunk's neighborhood is decided once. */
        final Long2ByteMap neighborhoodAnswers = new Long2ByteOpenHashMap();

        ChunkLoadAnswers(int loadedChunkCount) {
            this.loadedChunkCount = loadedChunkCount;
            this.answers.defaultReturnValue(NEIGHBORHOOD_UNKNOWN);
            this.neighborhoodAnswers.defaultReturnValue(NEIGHBORHOOD_UNKNOWN);
        }
    }

    final AtomicLong blockEntityTicksOnWorkers = new AtomicLong();
    final AtomicLong skippedBlockEntityTicks = new AtomicLong();
    final AtomicLong fallbackBlockEntityTicks = new AtomicLong();
    final AtomicLong blockEntityFallbackUndeclared = new AtomicLong();
    final AtomicLong blockEntityFallbackBoundary = new AtomicLong();
    final AtomicLong blockEntityFallbackMixedSafe = new AtomicLong();
    final AtomicLong blockEntityFallbackUnloadedNeighborhood = new AtomicLong();
    final AtomicLong blockEntityFallbackUntrackedNeighborhood = new AtomicLong();
    final RegionBlockEntityWorkGraph blockEntityWorkGraph = new RegionBlockEntityWorkGraph();
    final FurnaceWorkMetrics furnaceWorkMetrics = new FurnaceWorkMetrics();
    final BlockEntityResourceMetrics blockEntityResourceMetrics = new BlockEntityResourceMetrics();
    volatile boolean blockEntityResourcesEnabled = false;
    volatile boolean furnaceWorkWavesEnabled = false;
    @Nullable
    java.util.function.Consumer<net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity> furnaceBodyObserverForTesting;
    @Nullable
    final CoreModPreparation coreModPreparation = CoreModTickerVerifier.installed() ? new CoreModPreparation(this) : null;
    @Nullable
    java.util.function.BiConsumer<TickingBlockEntity, Runnable> blockEntityBodyObserverForTesting;
    final AtomicLong boundaryBlockEntityTicksOnWorkers = new AtomicLong();
    /** Chunks the plan did not hand over because they are not block-ticking (or the tick rate is frozen). */
    private final AtomicLong nonTickingBlockEntityChunks = new AtomicLong();
    /** Chunks the plan did not hand over because their entities are still loading. */
    private final AtomicLong entitiesLoadingBlockEntityChunks = new AtomicLong();
    /** Chunks the plan did not hand over because they are no longer loaded, so no snapshot exists. */
    private final AtomicLong missingBlockEntityChunks = new AtomicLong();
    /** Chunks whose snapshot went stale between the plan and the worker, so the server thread ticked them. */
    final AtomicLong staleBlockEntityChunkSnapshots = new AtomicLong();
    private final AtomicLong scheduledTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedScheduledTicks = new AtomicLong();
    private final AtomicLong fallbackScheduledTicks = new AtomicLong();
    final AtomicLong chunkTicksOnWorkers = new AtomicLong();
    final AtomicLong skippedChunkTicks = new AtomicLong();
    private final AtomicLong fallbackChunkTicks = new AtomicLong();
    /** Chunks the plan kept on the server thread because a random tick of them could leave their region. */
    private final AtomicLong boundaryChunkTicks = new AtomicLong();
    /** Chunks the plan kept on the server thread because a neighboring chunk is not loaded. */
    private final AtomicLong unloadedNeighborhoodChunkTicks = new AtomicLong();
    /** Chunk ticks a worker abandoned because the chunk source refused a chunk it needed. */
    final AtomicLong truncatedChunkTicks = new AtomicLong();
    /** Chunks collected after their batch already ran, so the server thread ticked them inline. */
    private final AtomicLong lateChunkTicks = new AtomicLong();
    /** Regions whose lease could not be acquired, so their chunks ticked on the server thread. */
    private final AtomicLong unleasedChunkTicks = new AtomicLong();
    /** Lightning strikes a chunk tick worker rolled and the server thread resolved at the barrier. */
    final AtomicLong chunkTickLightningStrikes = new AtomicLong();
    /**
     * Deferred light rechecks and block update publications the barrier of each slice applied, indexed by
     * {@link RegionTickSlice#ordinal()}.
     */
    private final AtomicLong[] appliedDeferredBlockUpdates = newSliceCounters();
    /** Deferred light rechecks and block updates of each slice that were never applied, indexed by slice. */
    private final AtomicLong[] droppedDeferredBlockUpdates = newSliceCounters();
    /**
     * Units of each slice that were refused before their body because the deferred block-update budget of their
     * batch was exhausted, indexed by slice.
     */
    private final AtomicLong[] refusedDeferredBlockUpdates = newSliceCounters();
    /**
     * Tick failures the worker groups of each slice recorded for the server thread, indexed by slice. A failure is
     * counted once, on the worker that caught it, and reported once by
     * {@link RegionTickBatch#reportTickFailures()}.
     */
    private final AtomicLong[] workerTickFailures = newSliceCounters();
    /**
     * Chunks the plan kept on the server thread because the deferred update budget of the batch was exhausted,
     * so their block writes would have had nowhere to be recorded.
     */
    private final AtomicLong deferredUpdateBudgetChunkTicks = new AtomicLong();
    /**
     * Level ticks whose chunk tick stayed on the server thread because the level keeps the legacy shared entity
     * section storage instead of per-region buckets.
     */
    private final AtomicLong nonPartitionedChunkTicks = new AtomicLong();
    /** Chunks whose natural spawning ran on the worker of their region. */
    private final AtomicLong spawnChunksOnWorkers = new AtomicLong();
    /**
     * Chunks the plan kept on the server thread, by reason. Every one of them spawns exactly once, from the
     * vanilla level-wide spawn state, so none of them is dropped.
     */
    private final AtomicLong gateRefusedSpawnChunks = new AtomicLong();
    private final AtomicLong unloadedNeighborhoodSpawnChunks = new AtomicLong();
    private final AtomicLong deferredUpdateBudgetSpawnChunks = new AtomicLong();
    private final AtomicLong lateSpawnChunks = new AtomicLong();
    /** Regions whose lease could not be acquired, so their chunks spawned on the server thread. */
    private final AtomicLong unleasedSpawnChunks = new AtomicLong();
    /** Chunks whose worker spawn body a chunk source refusal truncated after its first spawn. */
    private final AtomicLong truncatedSpawnChunks = new AtomicLong();
    private final AtomicLong orderedSpawnTransactions = new AtomicLong();
    private final AtomicLong orderedSpawnServiceNanos = new AtomicLong();
    /** Chunks a closed level identity kept out of the batch, so they never spawned. */
    private final AtomicLong skippedSpawnChunks = new AtomicLong();
    /**
     * Mob categories a spawn state refused because the level-wide mob cap was reached. Counted on the worker for
     * the region that owns the chunk, so it measures cap refusals, not missing spawn attempts.
     */
    private final AtomicLong capRefusedSpawns = new AtomicLong();
    /**
     * Spawn attempts that could not be recorded as an admission, so no entity was created. The barrier's own
     * refusals are counted in {@link #droppedDeferredAdditionCount}.
     */
    private final AtomicLong refusedSpawnAdditions = new AtomicLong();
    final AtomicBoolean entityTickWorkersNoticeLogged = new AtomicBoolean();
    final AtomicBoolean blockEntityTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean chunkTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean chunkTickStorageNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean spawnWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean spawnStorageNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean entityTickFallbackNoticeLogged = new AtomicBoolean();
    /**
     * Declares who may touch the vanilla entity section/index storage while this engine's workers run.
     * <p>
     * Entity sections, their class index and the by-id/by-uuid lookup are still the single-threaded
     * vanilla structures, so the server thread keeps them and a worker is rejected from the legacy entity
     * query surface and from every guarded storage mutation. Entity ticks may only leave the server thread
     * with {@link RegionEntityStorageMode#REGION_OWNED}, which is what the production configuration selects
     * when the entity tick slice is enabled on a fixed layout
     * ({@link RegionEntityStorageMode#forConfiguration(boolean, RegionLayout)}): the physical section
     * partitions of that layout are then the region-private storage of a lease, and every storage effect of
     * a worker tick is applied by the batch barrier on the server thread.
     */
    private final RegionEntityStorageMode entityStorageMode;
    /**
     * The per-type declarations of the entity tick, consulted before an entity body starts.
     * <p>
     * The registry ships the vanilla declarations of {@link #declareVanillaServerThreadOnlyEntities()}: the
     * types whose own tick reaches a mutation the entity lease does not own. The vanilla POI declarations are
     * gone because the level's POI storage is partitioned per region, and
     * {@link #serverThreadOnlyEntity(Class, String)} is the hook of the mod compatibility layer.
     */
    final RegionEntityTickPolicy entityTickPolicy = new RegionEntityTickPolicy();
    /**
     * Portal transitions a worker recorded for the batch barrier because the destination search reads the
     * {@code PoiManager} of the destination level. Each one is applied on the server thread after the batch,
     * so the transition of a worker-ticked entity never reaches that storage from a worker.
     */
    final AtomicLong deferredPortalTransitionCount = new AtomicLong();
    /** Portal transitions the barrier applied on the server thread. */
    final AtomicLong appliedPortalTransitionCount = new AtomicLong();
    /**
     * Portal transitions a barrier did not apply: the engine shut down, the level identity closed or the
     * entity was removed or left the level before the barrier. The entity keeps its portal state, so the
     * transition happens on a later tick instead of being lost silently.
     */
    final AtomicLong refusedPortalTransitionCount = new AtomicLong();
    /**
     * Mechanics-test seam for the scheduled/random callback slices. Production leaves it {@code false}:
     * no scheduled or random callback class is admitted as entity-storage safe yet, so a configured worker
     * slice falls back to the vanilla inline path. Only the internal mechanics tests enable it.
     */
    private volatile boolean unclassifiedWorkerCallbacksForTesting;
    /**
     * Mechanics-test seam for the legacy entity query handoff. Production rejects legacy entity queries
     * from native workers; the transport itself stays covered by the tests that enable this seam.
     */
    private volatile boolean legacyEntityQueryHandoffForTesting;
    /**
     * Mechanics-test seam of the per-chunk block-entity admission cache. Production keeps the cache enabled;
     * the equivalence test of the cache compares the cached plan against the uncached evaluation over the
     * same chunk and ticker states.
     */
    private volatile boolean blockEntityAdmissionCacheEnabled = true;
    /**
     * Diagnostics-only observer of a worker entity tick failure.
     * <p>
     * The entity worker diagnostics benchmark installs it so one failing tick is recorded and the group
     * continues with the next entity; the production tick loop has no catch and lets the first failure
     * abort the group, the batch and the level tick. Production leaves this {@code null}, so the worker
     * body keeps the vanilla failure path.
     */
    @Nullable
    volatile BiConsumer<Entity, Throwable> entityTickFailureObserverForTesting;
    /**
     * Random source of the entity tick group that executes on the current thread. It is installed once per
     * group for the whole worker tick loop, so {@link #currentGroupRandom()} answers with it for every
     * entity of the group and returns {@code null} again after the loop; the vanilla level random stays
     * authoritative outside a batch.
     */
    final ThreadLocal<RandomSource> currentEntityTickRandom = new ThreadLocal<>();
    /**
     * Random source of the block-entity tick group that executes on the current thread. It is installed once
     * per group for the whole worker tick loop, so {@link #currentGroupRandom()} answers with it for every
     * ticker of the group and the block-entity access gate opens for the block entities of its region; both
     * are removed again after the loop.
     */
    final ThreadLocal<RandomSource> currentBlockEntityTickRandom = new ThreadLocal<>();
    final ThreadLocal<RegionBlockEntityTickBatch.Group> currentBlockEntityOperationGroup = new ThreadLocal<>();
    /**
     * Random source of the scheduled tick group that executes on the current thread. It is only set for
     * the duration of a worker group, so {@link #scheduledTickRandom()} returns {@code null} everywhere
     * else and the vanilla level random stays authoritative outside a batch.
     */
    private final ThreadLocal<RandomSource> currentScheduledTickRandom = new ThreadLocal<>();
    /** The propagation decision to revisit on a foreign read, even when the decision writes nothing. */
    private final ThreadLocal<BlockPos> currentPropagationOrigin = new ThreadLocal<>();
    /**
     * Server-owned mutation queue of the region tick group executing on the current thread. It is only
     * set for the duration of a worker group; the server thread drains it after the phase, so a worker
     * never mutates either {@code LevelTicks} instance concurrently.
     * <p>
     * Scheduled block/fluid ticks, random ticks and block-entity ticks all mutate {@code LevelTicks} through
     * this queue, so the slices never mutate the scheduling structures concurrently.
     */
    final ThreadLocal<Queue<Runnable>> currentRegionTickMutations = new ThreadLocal<>();
    /**
     * Random source of the chunk tick group that executes on the current thread. It is installed for the tick
     * of one chunk, so {@link #chunkTickRandom()} answers with the random source of that chunk and
     * {@code level.random} draws of the chunk body resolve it instead of the shared vanilla source.
     */
    final ThreadLocal<RandomSource> currentChunkTickRandom = new ThreadLocal<>();
    /**
     * Position selection of the chunk tick of the current thread.
     * <p>
     * {@code Level.getBlockRandomPos} selects a position from the level-wide {@code randValue} sequence, which
     * one worker cannot share with another. A chunk tick therefore installs the chunk's own random source here
     * and the level selects the position from it, so the selection of a chunk is per-chunk deterministic.
     */
    final ThreadLocal<RandomSource> currentChunkTickPositionRandom = new ThreadLocal<>();
    /**
     * The strikes a chunk tick worker rolled and may not resolve itself.
     * <p>
     * Only set inside a worker group, so the level defers a strike there and resolves it inline on the server
     * thread.
     */
    final ThreadLocal<Queue<BlockPos>> currentChunkTickLightning = new ThreadLocal<>();
    /**
     * Random source of the spawn body that executes on the current thread. It is installed for the spawn body of
     * one chunk, so {@link #currentGroupRandom()} answers with the random source of that chunk and every
     * {@code level.random} draw of the body resolves it instead of the shared vanilla source.
     */
    private final ThreadLocal<RandomSource> currentSpawnRandom = new ThreadLocal<>();
    /**
     * Per-chunk answers of the spawn body that executes on the current thread, or {@code null} outside a spawn
     * group.
     * <p>
     * The vanilla spawn body asks the level whether a candidate position's chunk may spawn and which players are
     * close enough for spawning, both of which read server-owned structures. Inside a spawn group the level reads
     * this snapshot instead, so a worker never touches the entity manager's visibility map or the chunk map.
     */
    private final ThreadLocal<RegionSpawnNeighborhood> currentSpawnNeighborhood = new ThreadLocal<>();
    /**
     * Entries one region tick batch may reserve for the units it admits to its deferred block updates.
     * Production keeps {@link RegionDeferredBlockUpdates#MAX_ENTRIES}, which is the bound every slice derives its
     * admission count from ({@link RegionTickSlice#reservationPerUnit()}); the mechanics tests lower it to
     * observe the refusal without admitting thousands of units.
     */
    volatile int deferredBlockUpdateBudget = RegionDeferredBlockUpdates.MAX_ENTRIES;
    final Set<RegionTickBatch> activeTickBatches = ConcurrentHashMap.newKeySet();
    /**
     * Pending and terminal decisions of the recorded cross-region entity transfer transaction. The
     * journal, not the live entity state, decides whether a commit may run.
     */
    final RegionEntityTransferJournal entityTransferJournal = new RegionEntityTransferJournal();
    final AtomicLong nextEntityTransferTokenId = new AtomicLong();
    /**
     * Deferred entity moves whose recorded commit answered {@link RegionEntityTransferResult#REGION_BUSY}.
     * Every entry keeps the destination captured when the move was deferred and is applied again at the next
     * entity batch barrier, so a busy region delays the handoff instead of dropping it or replacing its
     * destination with one re-derived from the live position. Guarded by {@link #admissionLock}, so a level
     * clear and a shutdown drop the entries of their level together with the journal.
     */
    final List<RegionEntityTransferCoordinator.DeferredEntityMove> pendingDeferredEntityMoves = new ArrayList<>();
    final AtomicLong legacyDeferredEntityMoveCount = new AtomicLong();
    final AtomicLong retriedDeferredEntityMoveCount = new AtomicLong();
    /**
     * Deferred ownership updates a barrier resolved through the regionizer batch, i.e. updates that needed a
     * publication (an untracked entity) or a recorded transfer.
     */
    final AtomicLong batchedDeferredEntityMoveCount = new AtomicLong();
    /**
     * Deferred ownership updates that named the region their entity was already mapped to. They publish
     * nothing, and their scheduled-task notification is skipped for an entity without a wrapper.
     */
    final AtomicLong unchangedDeferredEntityMoveCount = new AtomicLong();
    /**
     * Deferred ownership batches the regionizer refused as a whole (a closed regionizer, a closed level
     * identity, a level mismatch or a retired destination). The barrier then publishes every update of that
     * batch through the per-entity path, which is the behavior of a barrier that cannot batch.
     */
    final AtomicLong refusedDeferredEntityMoveBatchCount = new AtomicLong();
    /**
     * Scheduled entity tasks that a move notification had to migrate to another region, i.e. tasks whose
     * entity crossed a region boundary while the task waited.
     */
    final AtomicLong entityTaskMigrationCount = new AtomicLong();
    final AtomicLong appliedDeferredSectionUpdateCount = new AtomicLong();
    final AtomicLong droppedDeferredSectionUpdateCount = new AtomicLong();
    /**
     * The part of {@link #droppedDeferredSectionUpdateCount} whose entity was removed, left the level or was
     * never added to it between the move and the barrier.
     * <p>
     * Such an update has nothing left to publish: the removal retires the section membership of the entity
     * before the barrier runs, so dropping the publication is the correct outcome and not a lost effect. The
     * subset is counted on its own so a soak can tell the expected drops of a dying mob apart from a
     * publication that failed, which is the drop that really loses a move.
     */
    final AtomicLong droppedDeferredSectionUpdateForRemovedEntityCount = new AtomicLong();
    /**
     * Deferred removals a barrier applied on the server thread. One applied removal is one level callback of
     * the removal: the section membership, the tracking and ticking callbacks, the identity ledger and the
     * region index of the entity are written exactly like a server-thread removal.
     */
    final AtomicLong appliedDeferredRemovalCount = new AtomicLong();
    /**
     * Deferred removals that did not complete: the engine shut down (including the removals a shut-down batch
     * releases without applying), the level identity closed, or the level callback threw. A dropped removal
     * never leaves a half-applied removal behind.
     */
    final AtomicLong droppedDeferredRemovalCount = new AtomicLong();
    /**
     * Removal requests that were no-ops: the entity was already recorded in the same batch, its removal had
     * already been applied by the server thread, it was revived, it left the level or its level changed. A
     * repeat is never a second removal.
     */
    final AtomicLong repeatedDeferredRemovalCount = new AtomicLong();
    /**
     * Deferred admissions a worker recorded for a barrier. One record is one riding graph, so a vehicle and
     * its passengers count once.
     */
    final AtomicLong recordedDeferredAdditionCount = new AtomicLong();
    /**
     * Deferred admissions a barrier admitted on the server thread through the unmodified
     * {@code ServerLevel.addEntity}. One applied admission is one whole riding graph in the level.
     */
    final AtomicLong appliedDeferredAdditionCount = new AtomicLong();
    /**
     * Deferred admissions that did not complete: the engine shut down (including the admissions a shut-down
     * batch releases without applying), the level identity closed, the entity was removed or left the level
     * between the record and the barrier, a member of the graph was already in the level, or the vanilla path
     * refused a member. A dropped admission loses the entity - it was never in the level and nothing retries
     * it - so it is always counted.
     */
    final AtomicLong droppedDeferredAdditionCount = new AtomicLong();
    final RegionEntityAdmissionDiagnostics entityAdmissionDiagnostics = new RegionEntityAdmissionDiagnostics();

    public RegionEntityAdmissionDiagnostics entityAdmissionDiagnostics() {
        return this.entityAdmissionDiagnostics;
    }

    /**
     * Serializes region drain activation with lifecycle cleanup. A drain holds the read lock only while
     * it checks the lifecycle state and marks the lease or worker submission; shutdown and level clear
     * hold the write lock for the complete cleanup, so an activation either wins and is awaited or is
     * rejected after cleanup completed.
     */
    final ReentrantReadWriteLock lifecycleGate = new ReentrantReadWriteLock();
    /**
     * Test hook run immediately before a level drain acquires a region lease or marks a worker
     * submission. It lets tests force the interleaving with level cleanup; production leaves it
     * {@code null}.
     */
    @Nullable
    private volatile Runnable beforeRegionDrainActivation;
    /**
     * Test hook run while an admission holds {@link #admissionLock}, immediately before the admission is
     * published. It lets tests force the interleaving between an admission and level cleanup; production
     * leaves it {@code null}.
     */
    @Nullable
    private volatile Runnable beforeRegionAdmission;
    /**
     * Serializes task admission with the shutdown transition. Every scheduler path takes this lock for the
     * lookup and insertion, and shutdown sets its flag while holding it, so a task can never be inserted
     * after the cleanup that closes the queues.
     */
    final Object admissionLock = new Object();
    volatile boolean shutdown;

    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, false, false);
    }

    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, false);
    }

    /**
     * Creates an engine with the legacy {@link RegionPolicy#DYNAMIC} layout.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, false);
    }

    /**
     * Creates an engine with the legacy {@link RegionPolicy#DYNAMIC} layout.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, RegionPolicy.DYNAMIC);
    }

    /**
     * Creates an engine with an explicit region layout policy.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, false, regionPolicy);
    }

    /**
     * Creates an engine with an explicit region layout policy.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, false, regionPolicy);
    }

    /**
     * Creates an engine with an explicit region layout policy and every bounded tick slice.
     * <p>
     * This constructor keeps entity storage on the server thread. A production server passes the mode of
     * {@link RegionEntityStorageMode#forConfiguration(boolean, RegionLayout)}, which selects region-owned
     * storage when the entity tick slice is enabled.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, regionPolicy, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    /**
     * Creates an engine with an explicit region layout policy, every bounded tick slice and an entity
     * storage mode.
     * <p>
     * The policy selects the size-3 default fixed layout; production passes the configured
     * {@link RegionLayout} instead, so the engine, its regionizer and the physical section partitions of
     * the levels it ticks share one fixed region size.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionPolicy regionPolicy, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, RegionLayout.of(regionPolicy), entityStorageMode);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice and an entity storage mode.
     * <p>
     * This is the production constructor: the layout carries both the policy and the fixed region edge
     * length, so nothing downstream has to guess the size. Natural spawning stays on the server thread; a caller
     * that wants the spawning slice uses
     * {@link #RegionThreadingEngine(int, boolean, boolean, boolean, boolean, boolean, boolean, boolean, RegionLayout, RegionEntityStorageMode)}.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, false, layout, entityStorageMode);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice, the natural spawning slice and
     * an entity storage mode.
     * <p>
     * The layout carries both the policy and the fixed region edge length, so nothing downstream has to guess the
     * size. The spawning slice is the only slice that admits entities a region does not store itself, because
     * vanilla picks a spawn position without regard to the region border; see {@link RegionSpawnPlan}.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, boolean spawnWorkerExecutionEnabled, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, spawnWorkerExecutionEnabled, false, false, layout, entityStorageMode);
    }

    /**
     * Creates an engine with the light-load dispatch of the worker pool and of the region leases selected.
     * <p>
     * {@code persistentWorkers} dispatches through persistent deques and stealable task ranges instead of a
     * shared executor queue, and {@code packedRegionLeases} lets one task tick several regions of the same slice. Both are off by
     * default, and off means the unchanged behaviour of every slice.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, boolean spawnWorkerExecutionEnabled, boolean persistentWorkers, boolean packedRegionLeases, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this(new RegionThreadingOptions(
                new RegionThreadingOptions.Workers(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, persistentWorkers, packedRegionLeases),
                new RegionThreadingOptions.TickSlices(entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, spawnWorkerExecutionEnabled),
                layout, entityStorageMode));
    }

    /** Creates the runtime from already resolved startup settings; legacy constructors remain compatible. */
    public RegionThreadingEngine(RegionThreadingOptions options) {
        final RegionThreadingOptions.Workers workers = options.workers();
        final RegionThreadingOptions.TickSlices ticks = options.ticks();
        this.layout = options.layout();
        this.entityTickPolicy.setLayout(this.layout);
        this.regionizer = new ThreadedRegionizer(this.layout, this.regionWorkAdmission);
        this.workerPool = new RegionWorkerPool(workers.threads() > 0 ? workers.threads() : Math.max(1, Runtime.getRuntime().availableProcessors() - 1), workers.persistent());
        this.workerExecutionEnabled = workers.regionTasks();
        this.globalWorkerExecutionEnabled = workers.globalTasks();
        this.entityTickWorkerExecutionEnabled = ticks.entities();
        this.blockEntityTickWorkerExecutionEnabled = ticks.blockEntities();
        this.scheduledTickWorkerExecutionEnabled = ticks.scheduledTicks();
        this.fluidTickWorkerExecutionEnabled = this.workerExecutionEnabled && RegionThreadingConfig.fluidTickWorkerExecutionEnabled();
        this.chunkTickWorkerExecutionEnabled = ticks.chunkTicks();
        this.spawnWorkerExecutionEnabled = ticks.spawning();
        this.packedRegionLeasesEnabled = false;
        if (this.layout.isDynamicCluster() && net.neoforged.neoforge.common.ForgiaConfig.isServerConfigLoaded()) {
            this.blockEntityResourcesEnabled = net.neoforged.neoforge.common.ForgiaConfig.SERVER.enableRegionThreadingBlockEntityResources.get();
            this.furnaceWorkWavesEnabled = net.neoforged.neoforge.common.ForgiaConfig.SERVER.enableRegionThreadingFurnaceWorkWaves.get();
        }
        try {
            org.apache.logging.log4j.core.config.Configurator.setLevel("tacz_aero_compat", org.apache.logging.log4j.Level.WARN);
        } catch (Throwable ignored) {
        }
        this.entityStorageMode = options.entityStorageMode();
        this.overloadConfig = options.overload();
        this.overloadGovernor = this.overloadConfig.enabled() && this.layout.isDynamicCluster()
                ? new RegionOverloadGovernor(this.overloadConfig)
                : null;
        this.scheduledTickCoordinator = new RegionScheduledTickCoordinator(new ScheduledTickHost());
        this.independentTickScheduler = new RegionIndependentTickScheduler(this.workerPool, (region, body) -> {
            final ServerLevel level = region.coordinate().level();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, region.coordinate(), region, null), () -> {
                if (level != null) {
                    this.deliverRegionMessages(level, region, body);
                } else {
                    body.run();
                }
            });
        });
        this.chunkTickCoordinator = new RegionChunkTickCoordinator(new ChunkTickHost());
        this.spawnCoordinator = new RegionSpawnCoordinator(new SpawnHost());
        this.borderPropagationCoordinator = new RegionBorderPropagationCoordinator(new BorderPropagationHost());
        this.blockEntityTickCoordinator = new RegionBlockEntityTickCoordinator(this);
        this.entityTickCoordinator = new RegionEntityTickCoordinator(this);
        this.unifiedTickCoordinator = new RegionUnifiedTickCoordinator(this);
        this.entityTransferCoordinator = new RegionEntityTransferCoordinator(this);
        this.playerPrefixCoordinator = new RegionPlayerPrefixCoordinator(this);
        this.diagnosticsCollector = new RegionDiagnosticsCollector(
                new RegionDiagnosticsCollector.BorderSources(
                        this.borderUpdatesRecorded,
                        this.borderUpdatesApplied,
                        this.borderUpdatesDelayed,
                        this.borderUpdatesDropped,
                        this.borderUpdatePasses,
                        this.borderUpdateRegionsRerun,
                        this.borderUpdateWorstPasses,
                        this.borderUpdatesOverflowed,
                        this.foreignChunkReads,
                        this.readOfUnownedChunk,
                        this.readOfVersionlessChunk,
                        this.foreignReadInvalidatedPasses,
                        this.foreignReadRegionReruns,
                        this.foreignReadSeedsDropped,
                        this.borderBlockWritesRecorded,
                        this.borderBlockWritesApplied,
                        this.borderBlockWritesOverflowed,
                        this.borderBlockWritesDropped,
                        this.readAfterDeferredBlockWrite),
                new RegionDiagnosticsCollector.BlockEventSources(
                        this.blockEventPhases,
                        this.blockEventPhaseLeases,
                        this.blockEventPhaseReleases,
                        this.blockEventPhaseEvents,
                        this.blockEventPhaseFixpoints,
                        this.blockEventPhaseLeaseFallbacks,
                        this.blockEventPhaseNanos,
                        this.blockEventPhaseMaxNanos),
                new RegionDiagnosticsCollector.GameEventSources(
                        this.gameEventsPosted,
                        this.gameEventSectionsLocal,
                        this.gameEventSectionsDeferred,
                        this.gameEventSectionsDelivered,
                        this.gameEventSectionsRefused,
                        this.gameEventSectionsDropped,
                        this.gameEventSectionsDroppedNoRegion,
                        this.gameEventSectionsDroppedNoChunk,
                        this.gameEventDeliveryPasses),
                new RegionDiagnosticsCollector.SaveSources(
                        this.levelSaves,
                        this.levelSavesWithBatchInFlight,
                        this.levelSavesWithTickDeferredWork,
                        this.levelSavesWithCrossTickDeferredWork,
                        this.levelSaveCrossTickDeferredEntries,
                        this.levelSaveDirtyPoiColumns,
                        this.levelSaveFlushedPoiColumns),
                this.appliedDeferredBlockUpdates, this.droppedDeferredBlockUpdates, this.refusedDeferredBlockUpdates, this.workerTickFailures);
        this.declareVanillaServerThreadOnlyEntities();
        RegionOwnershipViolationHandler.reset();
        RegionEntityOwnershipScope.resetCounters();
        RegionEntityQueryCounters.reset();
        RegionPlayerSnapshots.reset();
    }

    /**
     * Declares the vanilla entity types whose tick reaches a mutation the entity lease does not own.
     * <p>
     * A declaration is a property of the type, not of a workload: the guard sites of the vanilla classes are
     * audited against the tick of the class, and a type whose own tick can reach a rejected mutation keeps
     * the server thread instead of failing the group of its region at the first such tick. The declarations
     * are deliberately narrow - a type that only reaches the granted funnel stays on the worker:
     * <ul>
     * <li>{@code Slime} (and therefore {@code MagmaCube}): {@code Slime.remove} creates the child slimes of a
     * dead slime <em>before</em> it reaches the removal funnel, so the split is an entity addition inside a
     * removal callback the lease does not grant. A slime that dies in its own tick - fire, drowning, a fall,
     * another mob's attack that the same worker resolves - would otherwise abort the group and crash the
     * server.</li>
     * <li>{@code FishingHook}: its tick discards the hook in five places (the owner is gone, the hook hits a
     * block, the hook is in lava, the reel-in ends), and {@code discard} funnels into
     * {@code FishingHook.remove}, which returns the caught item and the rod damage to the owner before it
     * reaches the funnel.</li>
     * <li>{@code Projectile}: impact damage, enchantments and mod callbacks can mutate the victim, owner
     * or a third entity. Geometry/target ownership alone cannot prove that footprint. Until a complete
     * impact continuation exists, pin the whole tick before effects; specific declarations retain their
     * more precise diagnostic reasons.</li>
     * <li>{@code Shulker}: opening its shell directly moves nearby entities with {@code MoverType.SHULKER},
     * including players and entities stored across a region border. This bypasses the deferred push funnel;
     * pin the entire tick (and subclasses) before effects start instead of skipping or replaying lid pushes.</li>
     * <li>{@code ThrownEnderpearl}: both impact and end-gateway contact can mutate its owner, including player
     * connection teleports, dismount, damage and dimension changes. The complete tick stays on the server thread:
     * deferring only onHit would let ThrowableProjectile continue moving before the impact callback reads the
     * pearl's position, and would miss onInsideBlock's gateway path. Subclasses inherit this restriction.</li>
     * <li>{@code FireworkRocketEntity}: attached flight writes another entity's velocity and explosion damage
     * directly reaches its attached entity (including players) and nearby targets.</li>
     * <li>{@code WitherSkull}: impact damages a target, may heal its owner and then explodes. Neither the owner
     * nor the victims are necessarily in the projectile's entity lease. Keep the complete tick inline until
     * these whole operations have an ownership protocol; do not replay a partially applied impact.</li>
     * </ul>
     * A vehicle ({@code Boat}, {@code ChestBoat}, {@code AbstractMinecartContainer}) and a
     * {@code BlockAttachedEntity} keep the worker: their own tick never removes or re-anchors them, so their
     * guard sites are only reached from the server thread that destroys or moves them.
     */
    private void declareVanillaServerThreadOnlyEntities() {
        // A vehicle ticks its passengers recursively. Pin the whole riding stack before worker admission
        // so a mounted player's connection, input and dismount updates stay on the server thread too.
        this.entityTickPolicy.declarePlayerRidingStack();
        if (!RegionThreadingConfig.slimeWorkerTicksEnabled()) {
            this.entityTickPolicy.serverThreadOnly(Slime.class, "slime_split_on_removal");
        }
        this.entityTickPolicy.serverThreadOnly(FishingHook.class, "fishing_hook_remove");
        this.entityTickPolicy.serverThreadOnly(net.minecraft.world.entity.monster.Shulker.class, "shulker_peek_push");
        this.entityTickPolicy.serverThreadOnly(net.minecraft.world.entity.projectile.Projectile.class,
                "projectile_impact_callbacks");
        this.entityTickPolicy.serverThreadOnly(net.minecraft.world.entity.projectile.ShulkerBullet.class,
                "shulker_bullet_damage_and_enchantment_effects");
        this.entityTickPolicy.serverThreadOnly(net.minecraft.world.entity.projectile.ThrownEnderpearl.class,
                "ender_pearl_owner_interaction");
        this.entityTickPolicy.serverThreadOnly(net.minecraft.world.entity.projectile.FireworkRocketEntity.class,
                "firework_attached_and_area_interactions");
        this.entityTickPolicy.serverThreadOnly(net.minecraft.world.entity.projectile.WitherSkull.class,
                "wither_skull_impact_interactions");
        this.applyAdapterRegisteredEntityPolicies();
    }

    /**
     * Applies any entity type policies registered by compatibility adapters via {@link net.neoforged.neoforge.server.threading.api.RegionAdapterApi}.
     */
    @ApiStatus.Internal
    public void applyAdapterRegisteredEntityPolicies() {
        net.neoforged.neoforge.server.threading.api.RegionAdapterApi.registeredServerThreadOnlyEntityTypes().forEach((id, reason) -> {
            final net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                return;
            }
            this.entityTickPolicy.serverThreadOnlyType(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(key), reason);
        });
    }

    RegionEntityTickCoordinator entityTickCoordinator() {
        return this.entityTickCoordinator;
    }

    RegionBlockEntityTickCoordinator blockEntityTickCoordinator() {
        return this.blockEntityTickCoordinator;
    }

    @ApiStatus.Internal
    public RegionUnifiedTickCoordinator unifiedTickCoordinator() {
        return this.unifiedTickCoordinator;
    }

    /**
     * Phase 4C: Temporarily pins two adjacent sections together in both the regionizer and the dynamic
     * cluster topology until {@code untilGameTime}, deferring split checks while a cross-border structure
     * is active.
     */
    @ApiStatus.Internal
    public void pinAdjacentSectionsTogether(ServerLevel level, RegionCoordinate a, RegionCoordinate b, long untilGameTime) {
        if (level == null || a == null || b == null) {
            return;
        }
        this.regionizer.pinAdjacentSectionsTogether(level, a, b, untilGameTime);
        final DynamicRegionTopology topology = this.dynamicTopologies.get(level);
        if (topology != null) {
            topology.pinAdjacentSectionsTogether(a.regionX(), a.regionZ(), b.regionX(), b.regionZ(), untilGameTime);
        }
    }

    /**
     * Creates an engine with an explicit region layout and every bounded tick slice, keeping entity storage
     * on the server thread.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionLayout layout) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, layout, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice and the natural spawning slice,
     * keeping entity storage on the server thread.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, boolean spawnWorkerExecutionEnabled, RegionLayout layout) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, spawnWorkerExecutionEnabled, layout, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    /** Only used once during server startup, after adapter verification and before worlds tick. */
    @ApiStatus.Internal
    public void configureStartupSpawnWorkers(boolean enabled) {
        if (this.shutdown || !this.isQuiescentForParallelApi() || !this.activeTickBatches.isEmpty()) {
            throw new IllegalStateException("Cannot change spawn worker admission while work is active");
        }
        this.spawnWorkerExecutionEnabled = enabled;
    }

    public RegionPolicy regionPolicy() {
        return this.layout.policy();
    }

    /**
     * {@return the layout this engine maps chunks, entities and tasks with}
     * <p>
     * The physical entity section partitions of a level capture this layout when the level is created, so
     * the manager partitions sections with exactly the size this engine ticks with.
     */
    public RegionLayout regionLayout() {
        return this.layout;
    }

    /**
     * {@return the chunk margin around an entity's own chunk that has to be loaded before the entity is
     * submitted to a worker}
     * <p>
     * The margin is the heuristic that keeps the common entity tick off the server chunk source; a tick that
     * still needs a chunk outside it is handed to the server-thread fallback by the chunk source guard.
     */
    public static int entityTickChunkMargin() {
        return ENTITY_TICK_CHUNK_MARGIN;
    }

    /**
     * Declares that the tick of {@code type} and of every subclass of it may only run on the server thread.
     * <p>
     * The entity slices consult the declaration before an entity body starts and tick a declared entity on
     * the server thread in the same tick instead: in the self-driven slice through the server-thread fallback
     * of its batch, in the per-entity slice by not accepting the entity, which keeps the vanilla inline tick.
     * The declaration is counted per reason and reported by the region threading status, and it covers the
     * riding stack, because a passenger is ticked through the vehicle that carries it.
     * <p>
     * This is the hook of the mod compatibility layer: a mod declares the types whose tick reaches a shared
     * server-owned structure instead of being refused a region worker as a whole. A type that is never
     * declared keeps the worker, so this call only ever moves work back to the server thread.
     *
     * @param type   the declared entity class
     * @param reason the short reason token of the declaration, e.g. the shared structure the tick reaches;
     *               it is the key of the per reason counter and the token the status output reports
     */
    public void serverThreadOnlyEntity(Class<? extends Entity> type, String reason) {
        this.entityTickPolicy.serverThreadOnly(type, reason);
    }

    /**
     * Declares that the tick of the registry type {@code type} may only run on the server thread.
     * <p>
     * This is the declaration-file hook of the mod compatibility layer: a declaration file names a registry
     * type id, which resolves to exactly this object, so the file needs no class. The declaration covers
     * exactly the named registry type - a modded subtype is a registry type of its own and keeps the worker
     * unless it is declared too - while {@link #serverThreadOnlyEntity(Class, String)} covers a class and its
     * subclasses. Like the class declaration it only ever moves work back to the server thread, and its reason
     * is the key of the per reason counter of the status output.
     *
     * @param type   the declared entity registry type
     * @param reason the short reason token of the declaration
     */
    public void serverThreadOnlyEntityType(EntityType<?> type, String reason) {
        this.entityTickPolicy.serverThreadOnlyType(type, reason);
    }

    /**
     * {@return the per-type entity tick policy of this engine}
     */
    RegionEntityTickPolicy entityTickPolicy() {
        return this.entityTickPolicy;
    }

    @Override
    public GlobalRegionScheduler globalScheduler() {
        return this.globalScheduler;
    }

    @Override
    public RegionScheduler regionScheduler() {
        return this.regionScheduler;
    }

    @Override
    public EntityInteractionScheduler entityInteractions() {
        return this.entityInteractionScheduler;
    }

    @Override
    public EntityRegionScheduler entityScheduler() {
        return this.entityScheduler;
    }

    /**
     * {@return the region coordinate the layout assigns to {@code pos}, for the mutation refusal diagnosis}
     */
    @ApiStatus.Internal
    public RegionCoordinate regionCoordinateForDiagnostics(ServerLevel level, BlockPos pos) {
        return this.layout.regionAt(level, pos);
    }

    @ApiStatus.Internal
    @Nullable
    public RegionCoordinate currentRegionCoordinateOrNull() {
        final RegionThreadingContext context = this.currentContext();
        if (context instanceof BasicRegionThreadingContext basicContext && basicContext.kind() == RegionThreadingContext.Kind.REGION) {
            return basicContext.regionCoordinate;
        }
        return null;
    }

    public boolean isOwnedByCurrentRegion(ServerLevel level, BlockPos pos) {
        final RegionPlayerSpatialLease playerSpace = RegionPlayerSpatialLease.current();
        if (playerSpace != null) return playerSpace.ownsRegion(level, this.layout.regionX(SectionPos.blockToSectionCoord(pos.getX())), this.layout.regionZ(SectionPos.blockToSectionCoord(pos.getZ())));
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionCoordinate == null) {
            return false;
        }

        final RegionState region = basicContext.regionState;
        if (region == null || !region.isOwnedByCurrentThread()) {
            return false;
        }
        // A region group holds the lease of its region for its whole body, and the regionizer refuses to
        // merge, split or transfer a running region. The mapping of the group's own coordinate therefore
        // cannot change while the group runs, so the coordinate comparison proves ownership without the
        // regionizer read and the RegionCoordinate allocation that every block-entity ticker of a group
        // would otherwise pay for. Positions outside the group's region keep the authoritative lookup.
        if (region.coordinate().equals(basicContext.regionCoordinate)
                && this.layout.isInRegion(level, pos, basicContext.regionCoordinate)) {
            return true;
        }

        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        return this.regionizer.get(coordinate) == region;
    }

    /**
     * {@return true when the current thread holds the lease of the region that owns the chunk column
     * {@code (chunkX, chunkZ)}}
     * <p>
     * The chunk-column counterpart of {@link #isOwnedByCurrentRegion(ServerLevel, BlockPos)}, for a caller that
     * has a chunk coordinate rather than a position. The game event dispatcher decides per chunk column whether a
     * worker may visit its listener registries, and it must not allocate a position per column to ask.
     */
    boolean isOwnedByCurrentRegion(ServerLevel level, int chunkX, int chunkZ) {
        final RegionPlayerSpatialLease playerSpace = RegionPlayerSpatialLease.current();
        if (playerSpace != null) return playerSpace.ownsRegion(level, this.layout.regionX(chunkX), this.layout.regionZ(chunkZ));
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionCoordinate == null) {
            return false;
        }

        final RegionState region = basicContext.regionState;
        if (region == null || !region.isOwnedByCurrentThread()) {
            return false;
        }
        if (region.coordinate().equals(basicContext.regionCoordinate)
                && this.layout.isInRegion(level, chunkX, chunkZ, basicContext.regionCoordinate)) {
            return true;
        }
        return this.regionizer.get(this.layout.regionAt(level, chunkX, chunkZ)) == region;
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        final RegionPlayerSpatialLease playerSpace = RegionPlayerSpatialLease.current();
        if (playerSpace != null) return playerSpace.owns(entity);
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null && group.owns(entity)) {
            return true;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        return this.isOwnedByCurrentRegion(level, entity.blockPosition());
    }

    /**
     * {@return true when the current region context may read a block entity of another region}
     * <p>
     * A comparator reads the analog output of the block behind it and a piston reads the moving block entity
     * in front of it, and that block can belong to the next region. A region that holds no execution lease
     * cannot start a group before the current phase ends, because the server thread leases every region of a
     * phase before it submits the phase's groups. Its block entities are therefore quiescent for the duration
     * of the current group, and the read is safe. A region that is running, or has a worker submission
     * pending, keeps the vanilla {@code null} answer: only its own worker may read it.
     */
    @ApiStatus.Internal
    public boolean isForeignRegionBlockEntityReadable(ServerLevel level, BlockPos pos) {
        if (RegionPlayerSpatialLease.current() != null) return false;
        if (this.shutdown) {
            return false;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionState == null
                || !basicContext.regionState.isOwnedByCurrentThread()) {
            return false;
        }
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        if (coordinate.equals(basicContext.regionCoordinate)) {
            return false;
        }
        final RegionState target = this.regionizer.get(coordinate);
        final boolean readable = target == null || (!target.isDead() && !target.isRunning() && !target.isWorkerSubmitted());
        if (readable) {
            this.foreignBlockEntityReads.incrementAndGet();
        }
        return readable;
    }

    /**
     * Records the version of one chunk a region worker is about to read through the chunk source.
     * <p>
     * A chunk of the caller's own region is not recorded: it is mutated by the caller only, so its reads cannot
     * race another region's worker. Every other chunk is remembered with the version it has now, and the border
     * fixpoint re-runs the caller's propagation if that version changed before the check. The read itself stays
     * lock-free; this method only adds a map entry.
     * <p>
     * A chunk no region owns cannot change under the read, and a chunk without a version - a proto chunk of
     * another status - cannot be verified and is refused without replay. These cases have separate counters.
     *
     * @param chunkX  the chunk x of the chunk that is handed to the worker
     * @param chunkZ  the chunk z of the chunk that is handed to the worker
     * @param version the version of the chunk, or a negative value when the chunk has none
     */
    @ApiStatus.Internal
    public void recordForeignChunkRead(ServerLevel level, int chunkX, int chunkZ, long version) {
        final RegionPlayerSpatialLease playerSpace = RegionPlayerSpatialLease.current();
        if (playerSpace != null) {
            playerSpace.chunk(level, chunkX, chunkZ);
            return;
        }
        if (!this.borderUpdatesEnabled) {
            return;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionState == null) {
            return;
        }
        final RegionState region = basicContext.regionState;
        if (version < 0L) {
            this.recordVersionlessChunkRead();
            throw RegionWorkerChunkUnavailableException.forVersionlessRead(chunkX, chunkZ);
        }
        if (this.ownsChunk(level, chunkX, chunkZ, basicContext, region)) {
            return;
        }
        if (this.regionizer.get(this.layout.regionAt(level, chunkX, chunkZ)) == null) {
            // No region owns the chunk, so no region mutates it and no version can change under the read. The read
            // keeps the behaviour it had before this check and is counted.
            this.readOfUnownedChunk.incrementAndGet();
            return;
        }
        final BlockPos origin = this.currentPropagationOrigin.get();
        if (origin != null) {
            region.recordRerunSeed(origin.asLong());
            this.currentPropagationOrigin.remove();
        }
        if (region.recordForeignChunkRead(ChunkPos.asLong(chunkX, chunkZ), version)) {
            this.foreignChunkReads.incrementAndGet();
            this.regionsWithForeignReads.computeIfAbsent(level, key -> ConcurrentHashMap.newKeySet()).add(region);
        }
    }

    /**
     * {@return true when the chunk of {@code chunkX}/{@code chunkZ} belongs to the region the current thread
     * holds the lease of}
     * <p>
     * The fixed layout answers from the chunk coordinate alone, so the check of a read of the caller's own
     * region costs two divisions and two comparisons. A dynamic region may be merged and then owns more than one
     * layout cell, so only the regionizer can answer for it.
     */
    private boolean ownsChunk(ServerLevel level, int chunkX, int chunkZ, BasicRegionThreadingContext context, RegionState region) {
        if (!region.isOwnedByCurrentThread()) {
            return false;
        }
        return this.regionizer.get(this.layout.regionAt(level, chunkX, chunkZ)) == region;
    }

    @Override
    public void assertOwnedByCurrentRegion(ServerLevel level, BlockPos pos) {
        if (!this.isOwnedByCurrentRegion(level, pos)) {
            RegionOwnershipViolationHandler.handle("Current thread does not own region at " + pos);
        }
    }

    @Override
    public void assertOwnedByCurrentRegion(Entity entity) {
        if (!this.isOwnedByCurrentRegion(entity)) {
            RegionOwnershipViolationHandler.handle("Current thread does not own entity " + entity);
        }
    }

    @Override
    public RegionThreadingContext currentContext() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * {@return true when {@code thread} belongs to this engine's region worker pool}
     */
    @ApiStatus.Internal
    public boolean isWorkerThread(Thread thread) {
        return this.workerPool.isWorkerThread(thread);
    }

    void enqueueGlobal(Runnable task) {
        this.runAdmitted(() -> this.globalRegion.taskQueue().enqueue(task));
    }

    RegionScheduledTask scheduleGlobal(Runnable task, long delayTicks) {
        return this.callAdmitted(() -> this.globalRegion.taskQueue().schedule(task, delayTicks));
    }

    RegionScheduledTask scheduleGlobalAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        return this.callAdmitted(() -> this.globalRegion.taskQueue().scheduleAtFixedRate(task, initialDelayTicks, periodTicks));
    }

    void enqueueRegion(ServerLevel level, BlockPos pos, Runnable task) {
        this.runAdmitted(() -> {
            if (!this.enqueueRegionTask(level, pos, task)) {
                // The level identity was closed by an unload after the shutdown check; dropping the task
                // is the only safe outcome for work that belongs to an unloaded level.
                LOGGER.debug("Dropped region task for a closed level");
            }
        });
    }

    RegionScheduledTask scheduleRegion(ServerLevel level, BlockPos pos, Runnable task, long delayTicks) {
        return this.callAdmitted(() -> {
            if (delayTicks < 0L) {
                throw new IllegalArgumentException("delayTicks must be >= 0");
            }
            return this.regionizer.schedule(this.layout.regionAt(level, pos), task, delayTicks);
        });
    }

    RegionScheduledTask scheduleRegionAtFixedRate(ServerLevel level, BlockPos pos, Runnable task, long initialDelayTicks, long periodTicks) {
        return this.callAdmitted(() -> {
            if (initialDelayTicks < 0L || periodTicks <= 0L) {
                throw new IllegalArgumentException("initialDelayTicks must be >= 0 and periodTicks must be > 0");
            }
            return this.regionizer.scheduleAtFixedRate(this.layout.regionAt(level, pos), task, initialDelayTicks, periodTicks);
        });
    }

    EntityRegionScheduledTask scheduleEntity(Entity entity, Runnable task, long delayTicks) {
        return this.scheduleEntityTask(entity, task, delayTicks, 0L);
    }

    EntityRegionScheduledTask scheduleEntityAtFixedRate(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        return this.scheduleEntityTask(entity, task, initialDelayTicks, periodTicks);
    }

    private EntityRegionScheduledTask scheduleEntityTask(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return this.callAdmitted(() -> {
            final EntityRegionScheduledTask scheduledTask = new EntityRegionScheduledTask(this, entity, task, delayTicks, periodTicks);
            this.registerEntityTask(scheduledTask);
            try {
                scheduledTask.start();
            } catch (RuntimeException | Error exception) {
                this.unregisterEntityTask(scheduledTask);
                throw exception;
            }
            return scheduledTask;
        });
    }

    /**
     * Registers a wrapper, so a move notification of its entity reaches it.
     * <p>
     * The group of the entity is created and filled in one compute on the entity key, so a notification that
     * reads the index observes either no group or a complete one, never a group that is still being filled.
     */
    private void registerEntityTask(EntityRegionScheduledTask task) {
        this.entityTasksByEntity.compute(task.entity(), (entity, tasks) -> {
            if (tasks == null) {
                tasks = ConcurrentHashMap.newKeySet();
            }
            tasks.add(task);
            return tasks;
        });
    }

    /**
     * Drains tasks queued to the global region.
     */
    @ApiStatus.Internal
    public void drainGlobalTasks() {
        if (this.globalRegion.isRunning() || this.globalRegion.isWorkerSubmitted()) {
            return;
        }
        final long now = System.nanoTime();
        if (this.globalWorkerExecutionEnabled) {
            this.submitGlobalTick(now);
            return;
        }
        this.globalTickRunner.runIfDue(this.globalRegion, now, this::drainGlobalRegionState);
    }

    private void submitGlobalTick(long nowNanos) {
        if (!this.globalRegion.tryMarkWorkerSubmitted()) {
            return;
        }
        if (!this.workerPool.execute("global region drain", () -> {
            try {
                this.globalTickRunner.runIfDue(this.globalRegion, nowNanos, this::drainGlobalRegionState);
            } finally {
                this.globalRegion.clearWorkerSubmitted();
            }
        })) {
            this.globalRegion.clearWorkerSubmitted();
            this.rejectedWorkerTaskCount.incrementAndGet();
        }
    }

    private void drainGlobalRegionState() {
        if (!this.globalRegion.tryMarkRunning()) {
            return;
        }
        try {
            this.runInGlobalRegionOwned(() -> this.failedQueuedTaskCount.addAndGet(this.globalRegion.taskQueue().drain()));
        } finally {
            this.globalRegion.clearRunning();
        }
    }

    /**
     * Runs {@code task} while the current thread owns the global region context.
     */
    @ApiStatus.Internal
    public void runInGlobalRegion(Runnable task) {
        if (this.globalRegion.isRunning() && this.globalRegion.isOwnedByCurrentThread()) {
            task.run();
            return;
        }
        if (!this.globalRegion.tryMarkRunning()) {
            // The global region is owned by another thread for a bounded drain: the native global worker
            // holds it while it runs the due global tasks. The server thread's global work - the server tick
            // events, which must be posted every tick - is never dropped, so the server thread waits for that
            // drain to finish instead of reporting a violation. A worker never waits: the server thread may
            // be waiting for that worker's own batch, so waiting there would deadlock the tick, and the work
            // is refused and counted instead.
            if (RegionWorkerScope.isNativeWorkerThread() || !this.awaitGlobalRegionIdle()
                    || !this.globalRegion.tryMarkRunning()) {
                RegionOwnershipViolationHandler.handle("Global region is already running on another thread");
                return;
            }
        }
        try {
            this.runInGlobalRegionOwned(task);
        } finally {
            this.globalRegion.clearRunning();
        }
    }

    /**
     * Waits for the bounded drain of the native global worker to release the global region.
     * <p>
     * The drain is bounded by {@code regionThreadingMaxImmediateTasksPerDrain} and
     * {@code regionThreadingMaxScheduledTasksPerDrain}, and the worker that drains never waits for the server
     * thread, so this wait is bounded by the drain itself. The bound exists only so a broken worker cannot
     * hang the tick loop: a drain that does not finish inside it is reported as an ownership violation and the
     * caller keeps its failure path.
     *
     * @return {@code true} when the global region became free
     */
    private boolean awaitGlobalRegionIdle() {
        final long deadline = System.nanoTime() + GLOBAL_REGION_WAIT_NANOS;
        while (this.globalRegion.isRunning()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
    }

    private void runInGlobalRegionOwned(Runnable task) {
        this.globalRegion.beginOwnership();
        try {
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.GLOBAL_REGION, null, null, null, null), task);
        } finally {
            this.globalRegion.endOwnership();
        }
    }

    /**
     * Runs {@code task} while the current thread is executing a server level tick bridge.
     */
    @ApiStatus.Internal
    public void runInLevelTick(ServerLevel level, Runnable task) {
        this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.LEVEL, level, null, null, null), task);
    }

    /**
     * {@return the phase of the tick of {@code level}}
     * <p>
     * A level whose tick was not opened through {@link #beginLevelTick(ServerLevel)} reads as
     * {@link LevelTickPhase#GLOBAL}: no region batch of it is in flight, so its global statements are the only
     * thing that could run.
     */
    @ApiStatus.Internal
    public LevelTickPhase levelTickPhase(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhases.get(level);
        return state == null ? LevelTickPhase.GLOBAL : state.phase();
    }

    /**
     * Opens the global phase of the tick of {@code level} and drains the level's global mailbox.
     * <p>
     * Called on the server thread at the head of the level tick, before the queued region work of the tick and
     * before every vanilla statement. The global messages a worker posted since the last global phase are
     * delivered here, in posting order, so no region batch can be in flight while they run. A level tick that
     * throws skips its close; the next entry counts the leftover segment as abandoned and continues from it.
     */
    @ApiStatus.Internal
    public void beginLevelTick(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhaseState(level);
        state.beginGlobalPhase();
        // Reconcile Folia-style dynamic region topology at the GLOBAL safe point (no workers in flight).
        if (this.layout.isDynamicCluster()) {
            this.reconcileDynamicTopologyAtSafePoint(level, state, true);
        }
        // The player list of the level is published once per tick, at the head of the tick and before any region
        // batch of it runs, so a worker answers a player lookup from an immutable view instead of walking the
        // level's live list. See RegionPlayerSnapshots for the one-tick staleness this buys.
        RegionPlayerSnapshots.publish(level);
        this.drainGlobalMessages(level);
        this.independentTickScheduler.drainCompletedCommits(level, RegionThreadingConfig.independentRegionCommitDrainBudgetNanos());
    }

    /**
     * {@return the dynamic region topology of {@code level}, or {@code null} when this engine is not using
     * {@link RegionPolicy#DYNAMIC_CLUSTER}}
     */
    @ApiStatus.Internal
    @Nullable
    public DynamicRegionTopology dynamicTopologyOrNull(ServerLevel level) {
        return this.layout.isDynamicCluster() ? this.dynamicTopologies.get(level) : null;
    }

    @ApiStatus.Internal
    public List<String> loadedIslandDiagnostics(ServerLevel level, int limit) {
        synchronized (this.admissionLock) {
            final DynamicRegionTopology topology = this.dynamicTopologies.get(level);
            if (topology == null) return List.of();
            final List<String> lines = new ArrayList<>();
            lines.add("Loaded islands in " + level.dimension().location() + ": " + topology.regionCount()
                    + ", merges=" + topology.mergeCount() + ", splits=" + topology.splitCount()
                    + ", submissionsSaved=" + topology.coalescedSubmissions());
            final var islands = topology.snapshotRegions();
            for (int i = 0; i < Math.min(limit, islands.size()); i++) {
                final var island = islands.get(i);
                lines.add("  Island " + island.regionId() + " at section " + island.anchorSectionX() + "," + island.anchorSectionZ()
                        + ": sections=" + island.sectionCount() + ", loadedChunks=" + island.loadedChunkCount());
            }
            return List.copyOf(lines);
        }
    }

    private DynamicRegionTopology dynamicTopology(ServerLevel level) {
        return this.dynamicTopologies.computeIfAbsent(level, ignored -> new DynamicRegionTopology(
                this.layout.dynamicSectionShift(),
                DynamicRegionTopology.DEFAULT_MERGE_RADIUS,
                this.layout.dynamicSplitDelayTicks()));
    }

    /**
     * Reconciles the {@link DynamicRegionTopology} of {@code level} only at a quiescent global boundary.
     * <p>
     * Taking {@link #admissionLock} and {@link #regionWorkAdmission} prevents concurrent chunk-topology
     * updates and prevents any concurrent batch publication or region-worker admission from observing a
     * mid-transition topology. If a region phase, an already-submitted batch, or any running/owned/submitted
     * worker for {@code level} is still in flight, reconciliation is deferred until the next quiescent
     * safe point so no live worker can ever observe a mutated topology snapshot.
     */
    private void reconcileDynamicTopologyAtSafePoint(ServerLevel level, LevelTickPhaseState state, boolean advanceTick) {
        synchronized (this.admissionLock) {
            if (this.shutdown || this.regionizer.isLevelClosed(level)) {
                return;
            }
            final DynamicRegionTopology topology = advanceTick
                    ? this.dynamicTopology(level)
                    : this.dynamicTopologies.get(level);
            if (topology == null) {
                return;
            }
            if (!advanceTick && !topology.hasPendingChanges()) {
                return;
            }
            final AtomicLong tickCounter = advanceTick
                    ? this.dynamicTopologyTicks.computeIfAbsent(level, ignored -> new AtomicLong())
                    : this.dynamicTopologyTicks.get(level);
            if (tickCounter == null) {
                return;
            }
            synchronized (this.regionWorkAdmission) {
                if (state.isRegionPhase() || this.hasInFlightDynamicTopologyObservers(level, !advanceTick)) {
                    return;
                }
                final long tick = advanceTick ? tickCounter.incrementAndGet() : tickCounter.get();
                topology.reconcile(tick);
            }
        }
    }

    /**
     * {@return true when any batch or region worker of {@code level} can still observe the published dynamic
     * topology snapshot}
     * <p>
     * Callers must hold {@link #regionWorkAdmission}.
     */
    private boolean hasInFlightDynamicTopologyObservers(ServerLevel level, boolean allowCurrentThreadUnstartedBatch) {
        if (this.regionWorkAdmission.hasNoActiveClaims() && this.activeTickBatches.isEmpty()) {
            return false;
        }
        boolean currentThreadHasUnstartedBatch = false;
        for (final RegionTickBatch batch : this.activeTickBatches) {
            if (batch.level() != level) {
                continue;
            }
            if (!allowCurrentThreadUnstartedBatch || !batch.isExecutingOnCurrentThread() || batch.isSubmitted()) {
                return true;
            }
            currentThreadHasUnstartedBatch = true;
        }
        for (final RegionState region : this.regionizer.regions()) {
            if (region.coordinate().level() != level) {
                continue;
            }
            if (region.hasOwner() || region.isWorkerSubmitted()) {
                return true;
            }
            if (!currentThreadHasUnstartedBatch && region.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the global phase of the tick of {@code level}.
     */
    @ApiStatus.Internal
    public void endLevelTick(ServerLevel level) {
        this.flushStagedFusedChunks(level);
        this.unifiedTickCoordinator.flushStagedWork(level);
        final LevelTickPhaseState state = this.levelTickPhases.get(level);
        if (state != null) {
            state.endGlobalPhase();
        }
        this.drainWorkerServerTasks(level);
        this.independentTickScheduler.drainCompletedCommits(level, RegionThreadingConfig.independentRegionCommitDrainBudgetNanos());
        this.itemCleanupManager.onEndLevelTick(level, this.regionizer.regions());
    }

    @ApiStatus.Internal
    public boolean isIndependentRegionTickEnabled() {
        return this.independentRegionTickOverride || RegionThreadingConfig.independentRegionTickClockEnabled();
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void setIndependentRegionTickEnabledForTesting(boolean enabled) {
        this.independentRegionTickOverride = enabled;
    }

    @ApiStatus.Internal
    public RegionIndependentTickScheduler independentTickScheduler() {
        return this.independentTickScheduler;
    }

    @ApiStatus.Internal
    public RegionIndependentTickScheduler.Snapshot independentTickSnapshot(int topLimit) {
        return this.independentTickScheduler.snapshot(this.isIndependentRegionTickEnabled(), topLimit);
    }

    @ApiStatus.Internal
    public RegionIndependentTickScheduler.DispatchOutcome dispatchIndependentRegionTick(
            ServerLevel level, BlockPos pos, Runnable regionBody, @Nullable Runnable serverThreadCommit) {
        final RegionCoordinate coord = this.layout.regionAt(level, pos);
        final RegionState region = this.regionizer.getOrCreate(coord);
        if (region == null || region.isDead()) {
            return RegionIndependentTickScheduler.DispatchOutcome.REJECTED;
        }
        return this.independentTickScheduler.submitIfDue(region, regionBody, serverThreadCommit, System.nanoTime());
    }

    /**
     * Runs the server-thread tasks the region workers of {@code level} parked outside an entity batch.
     * <p>
     * The server thread owns the level here: every worker of the tick has finished and no region phase is in
     * flight, so a task may write entity storage exactly like a server-thread spawn. Failed tasks are counted
     * and their failures are propagated after the queue is drained, like deferred work of an entity batch.
     * Continuing after a failed neighbor cascade would otherwise hide partially applied world changes.
     */
    private void drainWorkerServerTasks(ServerLevel level) {
        final ConcurrentLinkedQueue<Runnable> tasks = this.workerServerTasks.get(level);
        if (tasks == null) {
            return;
        }
        Throwable failure = null;
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
                this.workerServerTasksApplied.incrementAndGet();
            } catch (Throwable throwable) {
                this.workerServerTasksDropped.incrementAndGet();
                if (failure == null) {
                    failure = throwable;
                } else if (failure != throwable) {
                    failure.addSuppressed(throwable);
                }
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Queues the remaining cascade of a worker neighbor update for the server thread.
     * <p>
     * The cascade reads the target block, and vanilla loads the target chunk when the read misses. A worker may
     * not load a chunk, so it parks the rest of the cascade like any other server-thread task of its region
     * context instead of failing the tick.
     *
     * @return {@code true} when the cascade was parked, {@code false} on the server thread and on a worker with
     *         no queue, where the caller keeps the vanilla refusal
     */
    @ApiStatus.Internal
    public boolean handOffNeighborUpdateCascade(Runnable cascade) {
        if (!RegionWorkerScope.isNativeWorkerThread() || !this.queueLevelEndServerTaskInCurrentBatch(cascade)) {
            return false;
        }
        this.handedOffNeighborCascades.incrementAndGet();
        return true;
    }

    /** {@return the number of worker neighbor cascades handed to the server thread} */
    public long handedOffNeighborCascades() {
        return this.handedOffNeighborCascades.get();
    }

    /** {@return the number of server-thread tasks region workers parked outside an entity batch} */
    public long workerServerTasksQueued() {
        return this.workerServerTasksQueued.get();
    }

    /** {@return the number of parked server-thread tasks the server thread ran} */
    public long workerServerTasksApplied() {
        return this.workerServerTasksApplied.get();
    }

    /** {@return the number of parked server-thread tasks that failed and were dropped} */
    public long workerServerTasksDropped() {
        return this.workerServerTasksDropped.get();
    }

    /**
     * Enters the region phase of one slice batch of {@code level}.
     * <p>
     * The batch that submits region work owns the phase from here until {@link #endRegionPhase(ServerLevel)}:
     * exactly one batch of a level is in flight, and none is in flight while the level's global statements run.
     */
    @ApiStatus.Internal
    public void beginRegionPhase(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhaseState(level);
        if (this.layout.isDynamicCluster() && !state.isRegionPhase()) {
            this.reconcileDynamicTopologyAtSafePoint(level, state, false);
        }
        state.beginRegionPhase();
    }

    /**
     * Leaves the region phase, which returns the level to its global phase and delivers the global messages the
     * workers of the batch posted.
     */
    @ApiStatus.Internal
    public void endRegionPhase(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhaseState(level);
        state.endRegionPhase();
        if (!state.isRegionPhase()) {
            this.drainGlobalMessages(level);
            // The outermost region phase of the level is over and no worker is running, so the POI storage can
            // publish what its regions wrote during the phase without racing a reader of the live sections.
            RegionPoiStorages.publishLevel(level);
        }
    }

    /**
     * {@return the mailbox of {@code level}}, creating it on first use.
     * <p>
     * The mailbox of a shut-down engine is closed: a post through it is refused and counted instead of being
     * queued for a tick that never comes.
     */
    @ApiStatus.Internal
    public RegionMailbox mailbox(ServerLevel level) {
        final RegionMailbox mailbox = this.mailboxes.computeIfAbsent(level,
                key -> new RegionMailbox(this, key, RegionMailbox.DEFAULT_QUEUE_CAPACITY, RegionMailbox.DEFAULT_QUEUE_CAPACITY));
        if (this.shutdown) {
            mailbox.close();
        }
        return mailbox;
    }

    /**
     * Rejects a write of the state the global phase owns from a region phase.
     * <p>
     * The rule and the state it covers are the ownership table of {@link LevelTickPhase}. The check is two
     * volatile reads, so it is cheap enough for the single accessors it guards. It is an ownership assertion,
     * not a storage guard: the configured {@code regionThreadingOwnershipViolationMode} decides whether the
     * caller is interrupted, and the violation is always counted.
     *
     * @param level     the level whose state is written
     * @param operation the operation that writes it, named in the report
     */
    @ApiStatus.Internal
    public void assertGlobalStateWrite(ServerLevel level, String operation) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            RegionOwnershipViolationHandler.handle("Region phase wrote global state: " + operation);
            return;
        }
        final LevelTickPhaseState state = this.levelTickPhases.get(level);
        if (state != null && state.phase() == LevelTickPhase.REGION) {
            RegionOwnershipViolationHandler.handle("Region phase wrote global state: " + operation);
        }
    }

    /** {@return the region state of {@code coordinate}, or {@code null} when the coordinate has no live region} */
    @Nullable
    RegionState regionStateFor(RegionCoordinate coordinate) {
        return this.regionizer.get(coordinate);
    }

    /**
     * {@return true when the region entity tick index of {@code level} is a complete view of its entities}
     * <p>
     * The index is fed by the level's entity tick list hooks and seeded once on the server thread, so it is
     * complete from the first entity slice of the level on. Before that - and whenever entity tick workers
     * are off, the engine shut down or the level's identity closed - the index is not an answer, and a worker
     * query that would read it keeps the legacy guard instead of returning an empty result.
     */
    @ApiStatus.Internal
    public boolean isEntityIndexPublished(ServerLevel level) {
        return this.entityTickWorkerExecutionEnabled && !this.shutdown && this.seededEntityTickLevels.contains(level);
    }

    /**
     * {@return the number of entities the region entity tick index of {@code level} holds}
     * <p>
     * A worker compares it with the size of its own region's index to report that its region is only a part of
     * the level-wide answer. The count is engine bookkeeping maintained with the index, so reading it needs no
     * lock and touches no vanilla storage.
     */
    @ApiStatus.Internal
    public int indexedEntityCount(ServerLevel level) {
        return this.regionizer.entityCount(level);
    }

    /**
     * Runs one region slice batch of {@code level}: the level leaves its global phase for the lifetime of the
     * batch and returns to it when the batch and its server-thread wait are over.
     * <p>
     * Every submission of a region slice batch goes through here, so the phase covers exactly the submitted
     * worker work plus the barrier - the interval in which workers may run - and closes before the batch's
     * server-thread handoff, which is not region work.
     */
    void runRegionBatch(ServerLevel level, Runnable batch) {
        this.beginRegionPhase(level);
        Throwable failure = null;
        try {
            batch.run();
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            try {
                final long fixpointStart = System.nanoTime();
                // The worker phase of the batch is over: no worker holds a lease anymore, so the server thread
                // can hand the captured cross-region updates to their target regions and repeat until the
                // circuit stops changing or the vanilla chained-update bound is reached. A failure of the batch
                // is not masked by a failure of the fixpoint.
                try {
                    this.runBorderUpdateFixpoint(level);
                } catch (Throwable throwable) {
                    failure = accumulateFailure(failure, throwable);
                }
                // The game events the workers of the batch posted for another region follow the border
                // updates: the deferred writes of the source are applied first, exactly where the inline run
                // would have applied them before the listener body ran. The delivery repeats while its own
                // listener bodies post further events or write further borders.
                try {
                    this.runCrossRegionGameEvents(level);
                } catch (Throwable throwable) {
                    failure = accumulateFailure(failure, throwable);
                } finally {
                    this.mainThreadPhaseMetrics.record(RegionMainThreadPhaseMetrics.Phase.BORDER_FIXPOINT,
                            System.nanoTime() - fixpointStart);
                }
            } finally {
                this.endRegionPhase(level);
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Applies the border updates of the worker phase that just ended and re-runs the affected regions until no
     * border update remains or the bound is reached.
     * <p>
     * The fixpoint is a sequence of passes. A pass groups the pending updates by target region and runs one
     * propagation-only group per region, one region after another, so the second region of a pass sees the
     * writes of the first one. A propagation group only applies the captured vanilla updates and runs their
     * neighbor cascades; it ticks no entity, block entity, chunk or spawn body. The cascades record further
     * cross-region updates into the same queue, which become the next pass.
     * <p>
     * <strong>Bound.</strong> The bound is the level's {@code max-chained-neighbor-updates} limit, the number
     * vanilla uses for one cascade (default {@code 1000000}). A negative configured value means unlimited in
     * vanilla; the fixpoint then uses {@link SharedConstants#MAX_CHAINED_NEIGHBOR_UPDATES} so it can never spin.
     * The bound applies to the border updates applied by the passes and to the number of passes, because every
     * pass applies at least one update. The updates that do not fit are posted to the target region's mailbox
     * and applied at the start of its next group, so the circuit converges one tick later instead of losing
     * an update.
     * <p>
     * The capture queue's own bound ({@link RegionBorderUpdates#MAX_ENTRIES}) is an overflow of the memory of
     * one pass, not of the vanilla limit: the records past it are posted to the same next-tick fallback.
     * <p>
     * <strong>Foreign reads.</strong> A propagation group reads block states, fluids and block entities of the
     * neighbouring regions, and a chunk of another region can change between two of those reads. Every region
     * records the version of each foreign chunk it read ({@link RegionState#foreignChunkReads()}), and the
     * version of every position it wrote ({@link RegionState#rerunSeeds()}). The check runs once for the worker
     * phase before the first pass and once per region after its propagation group. A region whose observed
     * version changed re-runs its propagation - the updates it applied, plus a re-evaluation of the positions it
     * wrote - in the next pass. The re-run is bounded by the same pass bound and falls back to the same mailbox,
     * so a region that keeps invalidating converges one tick later instead of spinning.
     */
    private void runBorderUpdateFixpoint(ServerLevel level) {
        this.borderPropagationCoordinator.runBorderUpdateFixpoint(level);
    }

    private void runCrossRegionGameEvents(ServerLevel level) {
        this.borderPropagationCoordinator.runCrossRegionGameEvents(level);
    }

    private void discardForeignReads(RegionState region) {
        this.borderPropagationCoordinator.discardForeignReads(region);
    }

    /** Narrow bridge for {@link RegionBorderPropagationCoordinator}. */
    private final class BorderPropagationHost implements RegionBorderPropagationCoordinator.Host {
        @Override
        public boolean isShutdown() {
            return RegionThreadingEngine.this.shutdown;
        }

        @Override
        public boolean isLevelClosed(ServerLevel level) {
            return RegionThreadingEngine.this.regionizer.isLevelClosed(level);
        }

        @Override
        public boolean borderUpdatesEnabled() {
            return RegionThreadingEngine.this.borderUpdatesEnabled;
        }

        @Override
        public boolean crossRegionGameEventsEnabled() {
            return RegionThreadingEngine.this.crossRegionGameEventsEnabled;
        }

        @Override
        public boolean foreignReadRerunEnabled() {
            return RegionThreadingEngine.this.foreignReadRerunEnabled;
        }

        @Override
        public boolean parallelBorderFixpointEnabled() {
            return RegionThreadingEngine.this.parallelBorderFixpointEnabled;
        }

        @Override
        public boolean activeFusedBatch() {
            return RegionThreadingEngine.this.activeFusedBatch;
        }

        @Override
        public boolean hasBlockEntityFallbackPhase() {
            return RegionThreadingEngine.this.blockEntityFallbackPhase.get() != null;
        }

        @Override
        public boolean hasBlockEventPhase() {
            return RegionThreadingEngine.this.blockEventPhase.get() != null;
        }

        @Override
        public void countBlockEventPhaseFixpoint() {
            RegionThreadingEngine.this.blockEventPhaseFixpoints.incrementAndGet();
        }

        @Override
        public int borderUpdateBudgetForTesting() {
            return RegionThreadingEngine.this.borderUpdateBudgetForTesting;
        }

        @Override
        public int deferredBlockUpdateBudget() {
            return RegionThreadingEngine.this.deferredBlockUpdateBudget;
        }

        @Override
        public RegionLayout layout() {
            return RegionThreadingEngine.this.layout;
        }

        @Override
        public RegionWorkerPool workerPool() {
            return RegionThreadingEngine.this.workerPool;
        }

        @Override
        @Nullable
        public RegionIndependentTickScheduler independentTickScheduler() {
            return RegionThreadingEngine.this.independentTickScheduler;
        }

        @Override
        public FusedRegionTickSession fusedSession() {
            return RegionThreadingEngine.this.fusedSession;
        }

        @Override
        @Nullable
        public RegionBorderUpdates borderUpdates(ServerLevel level) {
            return RegionThreadingEngine.this.borderUpdates.get(level);
        }

        @Override
        public RegionBorderUpdates borderQueue(ServerLevel level) {
            return RegionThreadingEngine.this.borderQueue(level);
        }

        @Override
        @Nullable
        public RegionBorderGameEvents borderGameEvents(ServerLevel level) {
            return RegionThreadingEngine.this.borderGameEvents.get(level);
        }

        @Override
        @Nullable
        public Set<RegionState> regionsWithForeignReads(ServerLevel level) {
            return RegionThreadingEngine.this.regionsWithForeignReads.get(level);
        }

        @Override
        @Nullable
        public RegionState getRegion(RegionCoordinate coordinate) {
            return RegionThreadingEngine.this.regionizer.get(coordinate);
        }

        @Override
        public RegionMailbox mailbox(ServerLevel level) {
            return RegionThreadingEngine.this.mailbox(level);
        }

        @Override
        @Nullable
        public RegionTickBarrier levelBarrier(ServerLevel level) {
            return RegionThreadingEngine.this.levelBarrier(level);
        }

        @Override
        public TickBatchPublication publishTickBatch(RegionTickBatch batch, ServerLevel level) {
            return RegionThreadingEngine.this.publishTickBatch(batch, level);
        }

        @Override
        public void retireTickBatch(RegionTickBatch batch) {
            RegionThreadingEngine.this.activeTickBatches.remove(batch);
        }

        @Override
        public boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier) {
            if (RegionThreadingEngine.this.isIndependentRegionTickEnabled()) {
                return RegionThreadingEngine.this.acquireRegionLeaseBounded(region, barrier, 10_000_000L);
            }
            return RegionThreadingEngine.this.acquireRegionLease(region, barrier);
        }

        @Override
        public void addLeasedGroup(RegionState region, Runnable registration) {
            RegionThreadingEngine.this.addLeasedGroup(region, registration);
        }

        @Override
        public void beginRegionPhase(ServerLevel level) {
            RegionThreadingEngine.this.beginRegionPhase(level);
        }

        @Override
        public void endRegionPhase(ServerLevel level) {
            RegionThreadingEngine.this.endRegionPhase(level);
        }

        @Override
        public void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable action) {
            RegionThreadingEngine.this.runWithContext(
                    new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), action);
        }

        @Override
        public void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations) {
            if (mutations == null) {
                RegionThreadingEngine.this.currentRegionTickMutations.remove();
            } else {
                RegionThreadingEngine.this.currentRegionTickMutations.set(mutations);
            }
        }

        @Override
        public void setCurrentPropagationOrigin(@Nullable BlockPos pos) {
            if (pos == null) {
                RegionThreadingEngine.this.currentPropagationOrigin.remove();
            } else {
                RegionThreadingEngine.this.currentPropagationOrigin.set(pos);
            }
        }

        @Override
        public void applyDeferredBlockUpdates(RegionTickBatch batch) {
            RegionThreadingEngine.this.applyDeferredBlockUpdates(batch);
        }

        @Override
        public void applyRegionTickMutations(Queue<Runnable> mutations) {
            RegionThreadingEngine.this.applyRegionTickMutations(mutations);
        }

        @Override
        public void removeIfEmpty(RegionState region) {
            RegionThreadingEngine.this.regionizer.removeIfEmpty(region);
        }

        @Override
        public void countBorderUpdatesDropped(int count) {
            RegionThreadingEngine.this.borderUpdatesDropped.addAndGet(count);
        }

        @Override
        public void countBorderBlockWriteDropped() {
            RegionThreadingEngine.this.borderBlockWritesDropped.incrementAndGet();
        }

        @Override
        public void countBorderUpdateApplied(boolean isBlockWrite) {
            RegionThreadingEngine.this.borderUpdatesApplied.incrementAndGet();
            if (isBlockWrite) {
                RegionThreadingEngine.this.borderBlockWritesApplied.incrementAndGet();
            }
        }

        @Override
        public void countBorderUpdateRegionRerun() {
            RegionThreadingEngine.this.borderUpdateRegionsRerun.incrementAndGet();
        }

        @Override
        public void countBorderUpdatePasses(int passes) {
            RegionThreadingEngine.this.borderUpdatePasses.addAndGet(passes);
            RegionThreadingEngine.this.borderUpdateWorstPasses.accumulateAndGet(passes, Math::max);
        }

        @Override
        public void countBorderUpdatesDelayed(int count) {
            RegionThreadingEngine.this.borderUpdatesDelayed.addAndGet(count);
        }

        @Override
        public void countBorderUpdatesOverflowed() {
            RegionThreadingEngine.this.borderUpdatesOverflowed.incrementAndGet();
        }

        @Override
        public void countForeignReadInvalidatedPass() {
            RegionThreadingEngine.this.foreignReadInvalidatedPasses.incrementAndGet();
        }

        @Override
        public void countForeignReadRegionRerun() {
            RegionThreadingEngine.this.foreignReadRegionReruns.incrementAndGet();
        }

        @Override
        public void countForeignReadSeedsDropped(int count) {
            RegionThreadingEngine.this.foreignReadSeedsDropped.addAndGet(count);
        }

        @Override
        public void countGameEventDeliveryPass() {
            RegionThreadingEngine.this.gameEventDeliveryPasses.incrementAndGet();
        }

        @Override
        public void countGameEventSectionDelivered() {
            RegionThreadingEngine.this.gameEventSectionsDelivered.incrementAndGet();
        }

        @Override
        public void countGameEventSectionsDropped(int count) {
            RegionThreadingEngine.this.gameEventSectionsDropped.addAndGet(count);
        }

        @Override
        public void countGameEventSectionDroppedNoChunk() {
            RegionThreadingEngine.this.gameEventSectionsDroppedNoChunk.incrementAndGet();
        }

        @Override
        public void countGameEventSectionDroppedNoRegion() {
            RegionThreadingEngine.this.gameEventSectionsDroppedNoRegion.incrementAndGet();
        }
    }

    /**
     * Wraps one region group body so the messages the mailbox queued to {@code region} are delivered before it,
     * on the thread that runs the group.
     */
    Runnable withRegionMailboxDelivery(ServerLevel level, RegionState region, Runnable body) {
        return () -> this.deliverRegionMessages(level, region, body);
    }

    /**
     * Delivers the messages queued to {@code region} and then runs {@code body}, at the start of the group the
     * worker executes for that region.
     */
    void deliverRegionMessages(ServerLevel level, RegionState region, Runnable body) {
        final RegionMailbox mailbox = this.mailboxes.get(level);
        if (mailbox != null) {
            mailbox.deliverRegion(region.coordinate(), region);
        }
        body.run();
    }

    /** Delivers the global messages of {@code level} on the current thread, in posting order. */
    void drainGlobalMessages(ServerLevel level) {
        final RegionMailbox mailbox = this.mailboxes.get(level);
        if (mailbox != null) {
            mailbox.drainGlobal();
        }
    }

    /**
     * Broadcasts an adapter action to all active regions of {@code level} via {@link RegionMailbox}.
     *
     * @return the number of regions that accepted the broadcast message
     */
    @ApiStatus.Internal
    public int broadcastToLevelRegions(ServerLevel level, java.util.function.Consumer<RegionThreadingContext> action) {
        final RegionMailbox mailbox = this.mailbox(level);
        final Set<RegionCoordinate> targets = new java.util.LinkedHashSet<>();
        for (final RegionState region : this.regionizer.regions()) {
            if (!region.isDead() && region.coordinate().level() == level) {
                targets.add(region.coordinate());
            }
        }
        final List<LevelChunk> tickerChunks = new ArrayList<>();
        level.collectBlockEntityTickerChunks(tickerChunks);
        for (int i = 0, size = tickerChunks.size(); i < size; ++i) {
            final LevelChunk chunk = tickerChunks.get(i);
            if (chunk != null) {
                final RegionCoordinate coord = this.layout.regionAt(level, chunk.getPos().getWorldPosition());
                final RegionState state = this.regionizer.getOrCreate(coord);
                if (state != null && !state.isDead()) {
                    targets.add(coord);
                }
            }
        }
        int accepted = 0;
        for (final RegionCoordinate coord : targets) {
            if (mailbox.post(coord, () -> action.accept(this.currentContext())) == RegionMailbox.PostResult.ACCEPTED) {
                ++accepted;
            }
        }
        return accepted;
    }

    /**
     * Posts a task to the region owning {@code targetPos} in {@code level} via {@link RegionMailbox}.
     */
    @ApiStatus.Internal
    public boolean postToRegion(ServerLevel level, BlockPos targetPos, Runnable task) {
        final RegionCoordinate coord = this.layout.regionAt(level, targetPos);
        final RegionState state = this.regionizer.getOrCreate(coord);
        if (state == null || state.isDead()) {
            return this.mailbox(level).postGlobal(task) == RegionMailbox.PostResult.ACCEPTED;
        }
        return this.mailbox(level).post(coord, task) == RegionMailbox.PostResult.ACCEPTED;
    }

    LevelTickPhaseState levelTickPhaseState(ServerLevel level) {
        return this.levelTickPhases.computeIfAbsent(level, key -> new LevelTickPhaseState());
    }

    void recordMailboxAccepted() {
        this.mailboxAcceptedMessages.incrementAndGet();
    }

    void recordMailboxDelivered() {
        this.mailboxDeliveredMessages.incrementAndGet();
    }

    void recordMailboxRefusedFull() {
        this.mailboxRefusedFullMessages.incrementAndGet();
    }

    void recordMailboxRefusedClosed() {
        this.mailboxRefusedClosedMessages.incrementAndGet();
    }

    void recordMailboxDroppedRetired(long messages) {
        this.mailboxDroppedRetiredMessages.addAndGet(messages);
    }

    void recordMailboxDroppedOnClose(long messages) {
        this.mailboxDroppedOnCloseMessages.addAndGet(messages);
    }

    /** {@return the delivery failures counted so far, after counting one more} */
    long recordMailboxDeliveryFailure() {
        return this.mailboxDeliveryFailures.incrementAndGet();
    }

    /** Closes and forgets the mailbox of {@code level}, dropping and counting every queued message. */
    private void closeLevelMailbox(ServerLevel level) {
        final RegionMailbox mailbox = this.mailboxes.remove(level);
        if (mailbox != null) {
            mailbox.close();
        }
    }

    /**
     * Runs {@code task} while the current thread owns the entity's current region context.
     * <p>
     * Inside an entity tick batch the task runs inline only when the entity belongs to the batch group
     * that owns the current region lease. The group's snapshot membership stays authoritative for the
     * duration of the tick, so an entity that moved to another region is still ticked under the region
     * that snapshotted it. Work for an entity outside the group is queued and executed on the server
     * thread after the batch, never inline under a lease that does not own it.
     */
    @ApiStatus.Internal
    public void runInEntityRegion(Entity entity, Runnable task) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            if (group.owns(entity)) {
                task.run();
                return;
            }
            if (!entity.isRemoved()) {
                group.batch().queueServerTask(() -> {
                    if (!entity.isRemoved()) {
                        this.runInEntityRegionOutsideBatch(entity, task);
                    }
                });
                this.deferredEntityTicks.incrementAndGet();
            }
            return;
        }
        if (this.isOwnedByCurrentRegion(entity)) {
            task.run();
            return;
        }
        this.runInEntityRegionOutsideBatch(entity, task);
    }

    private void runInEntityRegionOutsideBatch(Entity entity, Runnable task) {
        if (this.shutdown) {
            // The regionizer is closed; run the tick like vanilla instead of creating a new region.
            task.run();
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            task.run();
            return;
        }

        final RegionCoordinate coordinate = this.layout.regionAt(level, entity.blockPosition());
        final boolean tryLifecycleGateOnly = this.workerPool.isWorkerThread(Thread.currentThread())
                || this.isExecutingTickBatchOnCurrentThread()
                || this.isExecutingRegionTickOnCurrentThread();
        if (!this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
            // A level cleanup is in progress and this thread may be part of the work it waits for. Running
            // the tick inline like vanilla is the only safe outcome; leasing a region now could race the
            // retirement of the level identity.
            task.run();
            return;
        }
        final RegionState region;
        final boolean inline;
        final boolean retry;
        try {
            if (this.shutdown) {
                inline = true;
                region = null;
                retry = false;
            } else {
                region = this.resolveEntityTickRegion(entity, coordinate);
                // The level identity may have been unloaded for region threading; run the tick inline
                // like vanilla instead of creating a region that the unload already excluded.
                inline = region == null;
                retry = !inline && region.isDead();
                if (!inline && !retry && !region.tryMarkRunning()) {
                    final boolean canYieldForAsyncWorker = level.getServer() != null
                            && level.getServer().isSameThread()
                            && this.independentTickScheduler != null
                            && (this.isIndependentRegionTickEnabled()
                                    || RegionThreadingConfig.asyncRegionEntityTicksEnabled()
                                    || RegionThreadingConfig.asyncRegionSpawningEnabled());
                    boolean acquiredAfterYield = false;
                    if (canYieldForAsyncWorker) {
                        final long waitBudgetNanos = (entity instanceof net.minecraft.world.entity.player.Player) ? 50_000_000L : 10_000_000L;
                        final long deadline = System.nanoTime() + waitBudgetNanos;
                        while (!region.isDead() && System.nanoTime() < deadline) {
                            this.serviceMainThreadQueuesForAsyncWait(level);
                            if (region.tryMarkRunning()) {
                                acquiredAfterYield = true;
                                break;
                            }
                            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                        }
                    }
                    if (!acquiredAfterYield) {
                        RegionOwnershipViolationHandler.handle("Entity region is already running for " + entity);
                        return;
                    }
                }
            }
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
        if (inline) {
            task.run();
            return;
        }
        if (retry) {
            // The region may have been retired after the task was queued. Re-resolve against the
            // regionizer's authoritative mappings instead of dropping entity work.
            this.regionizer.removeIfEmpty(region);
            this.runInEntityRegionOutsideBatch(entity, task);
            return;
        }

        try {
            region.beginOwnership();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), task);
        } finally {
            region.endOwnership();
            region.clearRunning();
            this.regionizer.removeIfEmpty(region);
        }
    }

    /**
     * {@return the region whose lease runs the tick of {@code entity}, or {@code null} when the tick has to run
     * inline}
     * <p>
     * Every other entity publishes the region of its live position here, which is how a server-thread entity tick
     * keeps its mapping current. A player's region is published at the start of its level's entity slice and
     * nowhere else, so its tick leases the region the mapping names: a player that teleported during the tick is
     * ticked under the region it is mapped to, and the transition is published at the next defined point. A
     * player without a mapping runs its tick inline, exactly like an entity whose level identity closed.
     */
    @Nullable
    private RegionState resolveEntityTickRegion(Entity entity, RegionCoordinate coordinate) {
        if (entity instanceof ServerPlayer) {
            return this.regionizer.regionFor(entity);
        }
        final RegionState existing = this.regionizer.regionFor(entity);
        if (existing != null && !existing.isDead() && existing.coordinate().equals(coordinate)) {
            return existing;
        }
        return this.regionizer.moveEntity(entity, coordinate);
    }

    /**
     * Queues {@code task} on the server thread after the batch of the current worker task finished.
     * <p>
     * A worker that cannot perform a server-owned effect itself - an entity admission of a spawn outside its
     * lease, a query the lease cannot answer - hands the effect to the server thread instead of failing the
     * tick. The task runs once the batch's workers are done and before the next slice, which is the same point
     * the batch's own deferred work is applied.
     *
     * @return {@code true} when the task was queued, {@code false} when the current thread is not a worker task
     *         of a live batch, so the caller has to keep its own behaviour
     */
    /**
     * Queues the attacker's entire virtual attack call when the target is not a member of this worker's lease.
     * <p>
     * AI can retain a target after it walks across a border. Queries being region-scoped does not invalidate that
     * reference. Hurt may trigger pose/dimension changes, death, drops and knockback, so moving only setPosRaw
     * or retrying the attacker tick after a failure would already be too late. No target side effect starts here.
     * The target's live state is resolved when the barrier invokes the attack, later in this same entity slice;
     * weapons/target health can therefore differ from the worker's selection-time state. Caller cooldowns remain
     * in the worker tick. No synchronous boolean damage result is promised by this admission API.
     */
    public boolean deferMeleeAttack(Mob attacker, Entity target) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return false;
        }
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group == null || attacker.level() != group.level() || !group.owns(attacker)) {
            RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess("melee attack outside the attacker's entity lease");
            return false;
        }
        if (target.level() == group.level() && group.owns(target)
                && !(target instanceof net.minecraft.world.entity.player.Player)
                && this.entityTickPolicy.reasonFor(target) == null) {
            return false;
        }
        final EntityInteractionTask request = this.submitEntityInteraction(attacker, target,
                (source, victim) -> ((Mob) source).doHurtTarget(victim), true);
        if (request.state() == EntityInteractionTask.State.REJECTED) {
            RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess("melee interaction rejected: " + request.reason());
            return false;
        }
        this.deferredMeleeAttacks.incrementAndGet();
        return true;
    }

    /** Queues direct damage from an entity tick, using a participant owned by the issuing group. */
    @ApiStatus.Internal
    public void queuePlayerDamage(ServerPlayer player, net.minecraft.world.damagesource.DamageSource damage, float amount) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (!RegionWorkerScope.isNativeWorkerThread() || group == null) {
            return;
        }
        Entity source = damage.getDirectEntity();
        if (source == null || source.level() != group.level() || !group.owns(source)) {
            source = damage.getEntity();
        }
        if (source != null && source.level() == group.level() && group.owns(source)) {
            this.submitEntityInteraction(source, player, (attacker, target) -> target.hurt(damage, amount));
        }
    }

    /**
     * Routes a worker-initiated player-state mutation (such as applying/removing a mob effect or firing a
     * criterion trigger) onto the server thread without failing the worker tick or demoting the caller's class.
     * <p>
     * Inside an entity tick batch this queues an owner-validated interaction on the current batch so the action
     * runs deterministically on the server thread right after the batch in the same tick. Outside an entity tick
     * group (for example from a block-entity, scheduled-tick or chunk worker) it falls back to
     * {@link MinecraftServer#execute(Runnable)} with live-level revalidation.
     */
    @ApiStatus.Internal
    public boolean queuePlayerAction(ServerPlayer player, Consumer<ServerPlayer> action) {
        if (!RegionWorkerScope.isNativeWorkerThread() || this.shutdown) {
            return false;
        }
        final ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        if (level == null || this.regionizer.isLevelClosed(level)) {
            return false;
        }
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group == null || group.level() != level) {
            if (!RegionThreadingConfig.regionSandboxAllBlockEntitiesEnabled()) {
                return false;
            }
            net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(level, () -> {
                if (!player.isRemoved() && player.level() == level) {
                    action.accept(player);
                }
            });
            return true;
        }
        final RegionEntityInteractionTask request = new RegionEntityInteractionTask(
                player, level,
                target -> {
                    if (target instanceof ServerPlayer livePlayer) {
                        action.accept(livePlayer);
                    }
                },
                state -> this.interactionOutcomes.incrementAndGet(state.ordinal()));
        if (group.batch().queueInteraction(request, this.interactionContinuation(request, group, level, player))) {
            this.interactionAdmissions.incrementAndGet();
            return true;
        }
        return false;
    }

    private EntityInteractionTask submitEntityInteraction(Entity source, Entity target, BiConsumer<Entity, Entity> action) {
        return this.submitEntityInteraction(source, target, action, false);
    }

    private EntityInteractionTask submitEntityInteraction(Entity source, Entity target, BiConsumer<Entity, Entity> action, boolean melee) {
        java.util.Objects.requireNonNull(action, "action");
        return this.submitEntityInteraction(source, target, action, null, melee);
    }

    private EntityInteractionTask submitEntityInteraction(Entity source, Entity target,
            @Nullable BiConsumer<Entity, Entity> action, @Nullable Consumer<Entity> ownerAction, boolean melee) {
        return this.submitEntityInteraction(source, target, action, ownerAction, melee, null, null);
    }

    private EntityInteractionTask submitEntityInteraction(Entity source, Entity target,
            @Nullable BiConsumer<Entity, Entity> action, @Nullable Consumer<Entity> ownerAction, boolean melee,
            @Nullable BlockPos loadPosition, @Nullable BiConsumer<Entity, LevelChunk> loadedAction) {
        final boolean ownerOnly = ownerAction != null || loadedAction != null;
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(target, "target");
        final ServerLevel level = source.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        final var outcomes = this.interactionOutcomes;
        final AtomicLong meleeApplied = this.appliedMeleeAttacks;
        final AtomicLong meleeSkipped = this.skippedMeleeAttacks;
        final AtomicLong meleeFailed = this.failedMeleeAttacks;
        final Consumer<EntityInteractionTask.State> observer = state -> {
            outcomes.incrementAndGet(state.ordinal());
            if (melee) {
                switch (state) {
                    case COMPLETED -> meleeApplied.incrementAndGet();
                    case SKIPPED, CANCELLED -> meleeSkipped.incrementAndGet();
                    case FAILED -> meleeFailed.incrementAndGet();
                    default -> {}
                }
            }
        };
        final RegionEntityInteractionTask request = loadedAction != null
                ? new RegionEntityInteractionTask(target, level, loadPosition, loadedAction, observer)
                : ownerAction == null
                        ? new RegionEntityInteractionTask(source, target, level, action, observer)
                        : new RegionEntityInteractionTask(target, level, ownerAction, observer);
        if (this.shutdown) {
            request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.SHUTDOWN);
            return request;
        }
        if (level == null) {
            request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.INVALID_CONTEXT);
            return request;
        }
        if (this.regionizer.isLevelClosed(level)) {
            request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.LEVEL_CLOSED);
            return request;
        }
        if (RegionWorkerScope.isNativeWorkerThread()) {
            final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
            if (group == null || group.level() != level) {
                request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.INVALID_CONTEXT);
            } else if (!group.owns(source)) {
                request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.UNOWNED_SOURCE);
            } else if (ownerOnly && source.isRemoved()) {
                request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.ENTITY_REMOVED);
            } else if (group.batch().queueInteraction(request, this.interactionContinuation(request, group, level,
                    ownerOnly ? target : source))) {
                        this.interactionAdmissions.incrementAndGet();
                    }
            return request;
        }
        if (!this.canRunEntityInteractionImmediately(level)) {
            request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.INVALID_CONTEXT);
            return request;
        }
        if (ownerOnly && source.isRemoved()) {
            request.retire(EntityInteractionTask.State.REJECTED, EntityInteractionTask.Reason.ENTITY_REMOVED);
            return request;
        }
        this.interactionAdmissions.incrementAndGet();
        final Throwable failure = request.execute(() -> this.interactionLifecycleReason(level));
        if (failure != null) {
            throwUnchecked(failure);
        }
        return request;
    }

    private EntityInteractionTask.Reason interactionLifecycleReason(ServerLevel level) {
        return this.shutdown ? EntityInteractionTask.Reason.SHUTDOWN
                : this.regionizer.isLevelClosed(level) ? EntityInteractionTask.Reason.LEVEL_CLOSED
                        : this.canRunEntityInteractionImmediately(level) ? EntityInteractionTask.Reason.NONE
                                : EntityInteractionTask.Reason.INVALID_CONTEXT;
    }

    /** Internal admission proof, also used by vanilla continuations which no longer have a live source. */
    @ApiStatus.Internal
    public boolean canRunEntityInteractionImmediately(ServerLevel level) {
        final MinecraftServer server = level.getServer();
        if (this.shutdown || this.regionizer.isLevelClosed(level) || RegionWorkerScope.isNativeWorkerThread()
                || server == null || !server.isSameThread() || !this.isInteractionCompletionQuiescent()) {
            return false;
        }
        final AtomicBoolean foreignOwner = new AtomicBoolean();
        this.regionizer.forEach(region -> {
            if ((region.isRunning() && !region.isOwnedByCurrentThread()) || region.isWorkerSubmitted()) {
                foreignOwner.set(true);
            }
        });
        return !foreignOwner.get();
    }

    /** Conservative server-wide check for the public preparation API; this does not grant ownership. */
    @ApiStatus.Internal
    public boolean isQuiescentForParallelApi() {
        if (!this.isInteractionCompletionQuiescent() || this.globalRegion.isWorkerSubmitted()
                || (this.globalRegion.isRunning() && !this.globalRegion.isOwnedByCurrentThread())) {
            return false;
        }
        final AtomicBoolean busy = new AtomicBoolean();
        this.regionizer.forEach(region -> {
            if (region.isWorkerSubmitted() || (region.isRunning() && !region.isOwnedByCurrentThread())) {
                busy.set(true);
            }
        });
        return !busy.get();
    }

    boolean playerDispatchQuiescent() {
        return this.regionWorkAdmission.hasNoActiveClaims() ? this.isInteractionCompletionQuiescent() : this.isQuiescentForParallelApi();
    }

    /** tryEnter already checked every region; while we own admission, no other thread can start work. */
    boolean playerCaptureQuiescent() {
        return this.regionWorkAdmission.isOwner() ? this.isInteractionCompletionQuiescent() : this.isQuiescentForParallelApi();
    }

    /** A main-thread continuation may run inside a published batch only after all of its workers returned. */
    boolean isInteractionCompletionQuiescent() {
        for (final RegionTickBatch batch : this.activeTickBatches) {
            if (!batch.isExecutingOnCurrentThread() || !batch.isGroupsComplete()) {
                return false;
            }
        }
        return true;
    }

    /** Owner-only requests capture the owner for failure reporting, never the consumed source projectile. */
    private Runnable interactionContinuation(RegionEntityInteractionTask request, RegionEntityTickBatch.Group group,
            ServerLevel level, Entity failureEntity) {
        return () -> {
            if (this.shutdown) {
                request.retire(EntityInteractionTask.State.SKIPPED, EntityInteractionTask.Reason.SHUTDOWN);
            } else if (this.regionizer.isLevelClosed(level)) {
                request.retire(EntityInteractionTask.State.SKIPPED, EntityInteractionTask.Reason.LEVEL_CLOSED);
            } else {
                final Throwable failure = request.execute(() -> this.interactionLifecycleReason(level));
                if (failure != null) {
                    this.recordWorkerTickFailure(group.batch(), group.region().coordinate(), failureEntity, failure);
                }
            }
        };
    }

    /** Validates the exact current invocation; a sibling block entity does not inherit source authority. */
    @ApiStatus.Internal
    public boolean isCurrentBlockEntityOperationSource(BlockEntity source) {
        final RegionBlockEntityTickBatch.Group group = this.currentBlockEntityOperationGroup.get();
        if (!RegionWorkerScope.isNativeWorkerThread() || group == null || source.getLevel() != group.batch().level()
                || !RegionBlockEntityTickScope.isCurrentSource(source))
            return false;
        final LevelChunk chunk = group.batch().level().getChunkSource().getChunkNow(source.getBlockPos().getX() >> 4, source.getBlockPos().getZ() >> 4);
        return chunk != null && chunk.isLoaded() && chunk.getBlockEntities().get(source.getBlockPos()) == source;
    }

    @ApiStatus.Internal
    public BlockEntityOperations.Reason blockEntityOperationLifecycle(ServerLevel level) {
        return this.shutdown ? BlockEntityOperations.Reason.SHUTDOWN
                : this.regionizer.isLevelClosed(level) ? BlockEntityOperations.Reason.LEVEL_CLOSED
                        : this.canRunEntityInteractionImmediately(level) && this.isQuiescentForParallelApi() ? BlockEntityOperations.Reason.NONE : BlockEntityOperations.Reason.INVALID_CONTEXT;
    }

    @ApiStatus.Internal
    public void submitBlockEntityOperation(BlockEntityOperationRuntime.Request<?> request, Supplier<BlockEntityOperations.Reason> lifecycle) {
        final ServerLevel level = request.sourceLevel();
        if (this.shutdown) {
            request.retire(BlockEntityOperations.State.REJECTED, BlockEntityOperations.Reason.SHUTDOWN);
        } else if (level == null) {
            request.retire(BlockEntityOperations.State.REJECTED, BlockEntityOperations.Reason.LEVEL_CHANGED);
        } else if (this.regionizer.isLevelClosed(level)) {
            request.retire(BlockEntityOperations.State.REJECTED, BlockEntityOperations.Reason.LEVEL_CLOSED);
        } else if (RegionWorkerScope.isNativeWorkerThread()) {
            final RegionBlockEntityTickBatch.Group group = this.currentBlockEntityOperationGroup.get();
            final BlockEntity source = request.sourceEntity();
            if (group == null || group.batch().level() != level) {
                request.retire(BlockEntityOperations.State.REJECTED, BlockEntityOperations.Reason.INVALID_CONTEXT);
            } else if (source == null || !this.isCurrentBlockEntityOperationSource(source)) {
                request.retire(BlockEntityOperations.State.REJECTED, BlockEntityOperations.Reason.UNOWNED_SOURCE);
            } else {
                group.batch().queueOperation(request, () -> {
                    final BlockEntityOperations.Reason engineReason = this.blockEntityOperationLifecycle(level);
                    return engineReason == BlockEntityOperations.Reason.NONE ? lifecycle.get() : engineReason;
                });
            }
        } else if (!this.canRunEntityInteractionImmediately(level) || !this.isQuiescentForParallelApi()) {
            request.retire(BlockEntityOperations.State.REJECTED, BlockEntityOperations.Reason.INVALID_CONTEXT);
        } else {
            request.accepted();
            BlockEntityOperationRuntime.rethrow(request.execute(lifecycle));
        }
    }

    void applyBlockEntityOperations(RegionBlockEntityTickBatch batch) {
        final List<RegionBlockEntityTickBatch.OperationEntry> operations = batch.drainOperations();
        if (operations.isEmpty()) return;
        // A whole operation must observe scheduled mutations requested before the workers returned. A later
        // comparator/callback must not miss a pending worker schedule and schedule the same delay twice.
        Throwable failure = runBatchCompletion(null, () -> this.applyRegionTickMutations(batch.regionTickMutations()));
        for (final RegionBlockEntityTickBatch.OperationEntry entry : operations) {
            // A failed operation never retries its effects or strands later accepted requests.
            failure = accumulateFailure(failure, entry.request().execute(entry.lifecycle()));
        }
        if (failure != null) throwUnchecked(failure);
    }

    /**
     * Queues a task for the server thread when the current native worker is inside an entity batch, or when it is
     * inside another region phase such as spawning, chunk ticking or block-entity ticking.
     * <p>
     * The latter uses the level queue drained by {@link #endLevelTick(ServerLevel)} because those phases have no
     * entity batch barrier of their own.
     */
    @ApiStatus.Internal
    public boolean queueServerTaskInCurrentBatch(Runnable task) {
        if (this.activeCanonicalPureSerial || BlockEntityExecutionEngine.isCanonicalSerialInvocation()) return false;
        if (RegionWorkerScope.isNativeWorkerThread() && BlockEntityExecutionEngine.captureInvocationTask(task)) return true;
        return this.queueLevelEndServerTaskInCurrentBatch(task);
    }

    /** Explicit original timing for entity additions and unfinished neighbor cascades, never a Fence commit. */
    @ApiStatus.Internal
    public boolean queueLevelEndServerTaskInCurrentBatch(Runnable task) {
        BlockEntityResourceOwnership.Scope.rejectEffect("server task publication");
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            group.batch().queueServerTask(task);
            return true;
        }
        // No entity batch: a spawning, chunk tick or block entity worker still cannot write entity storage, so the
        // task is parked for the level and the server thread runs it once every worker of this tick is done.
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext) || context.kind() != RegionThreadingContext.Kind.REGION) {
            return false;
        }
        final ServerLevel level = basicContext.level().orElse(null);
        if (level == null) {
            return false;
        }
        this.workerServerTasks.computeIfAbsent(level, unused -> new ConcurrentLinkedQueue<>()).add(task);
        this.workerServerTasksQueued.incrementAndGet();
        return true;
    }

    /**
     * {@return the batch group that owns the current worker task, or {@code null} outside an entity batch}
     */
    @Nullable
    private RegionEntityTickBatch.Group currentEntityTickGroup() {
        return this.currentContext() instanceof BasicRegionThreadingContext basicContext
                ? basicContext.entityTickGroup
                : null;
    }

    /**
     * Starts collecting the entities of the current {@code level} tick that should tick on region workers.
     * <p>
     * This is also the publication point of the region of every player of the level: the call runs on the server
     * thread at the start of the entity slice, before the batch of the slice is published and before any region
     * of the level is leased, so walking, a teleport, a portal and a dimension change are all published here and
     * the target region reads the migrated index entry in the batch of this same slice. See
     * {@link PlayerRegionTransitions}.
     *
     * @return the plan to fill, or {@code null} when entity ticks have to stay on the server thread
     */
    @Nullable
    public RegionEntityTickPlan beginEntityTickPlan(ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        if (!this.entityTickWorkerExecutionEnabled || this.shutdown || this.regionWorkAdmission.isOwner()) {
            return null;
        }
        if (this.entityStorageMode != RegionEntityStorageMode.REGION_OWNED) {
            // A worker entity tick moves, removes and spawns entities, which mutates the shared vanilla
            // section/index storage. Entity storage is not region-owned, so the whole pass stays on the
            // server thread instead of shipping a query or a consumer on a worker.
            if (this.entityTickFallbackNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region entity ticks stay on the server thread until entity storage is region-owned (mode {})", this.entityStorageMode);
            }
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the entities inline like vanilla instead of publishing a
            // batch for region state that no longer exists. The publication re-checks this atomically.
            return null;
        }
        final boolean seeded = this.seedEntityTickIndex(level);
        // The defined point of every player region transition of this level: the region index of the level is
        // seeded, the batch of this slice is not published yet and no region of the level is leased.
        this.playerRegions.publish(this, level);
        if (!seeded) {
            // The level's authoritative tick list could not be seeded into the region index (a level without
            // a tick list, or a retiring identity). The per-region index is then not a complete view of the
            // level's entities, so this tick keeps the per-entity collection instead of dropping entities the
            // index does not know.
            this.entityTickIndexFallbacks.incrementAndGet();
            return new RegionEntityTickPlan(this, level, inEntityTickingRange, barrier, false);
        }
        return RegionEntityTickPlan.selfDriven(this, level, inEntityTickingRange, barrier);
    }

    /**
     * Starts the per-entity collection of the current {@code level} tick.
     * <p>
     * This is the fallback mode of the entity slice: the server thread walks the level's entity tick list and
     * the plan collects the entities whose region can own them. The production entry point
     * {@link #beginEntityTickPlan} selects it only for a level whose region entity tick index cannot be
     * seeded; the region entity tick mechanics are tested through it directly.
     */
    @org.jetbrains.annotations.VisibleForTesting
    public RegionEntityTickPlan beginPerEntityTickPlan(ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        return new RegionEntityTickPlan(this, level, inEntityTickingRange, barrier, false);
    }

    /**
     * Seeds {@code level}'s region entity tick index from the level's authoritative entity tick list, once.
     * <p>
     * The index is fed by the entity tick list hooks, so a level that starts ticking entities after the engine
     * was installed - including a level that existed before the bridge was installed - needs one pass over the
     * list to publish its members. The seed runs on the server thread outside the entity pass, publishes the
     * region mapping of every entity and marks its index entry as ticking, exactly like the hook would have.
     *
     * @return {@code false} when the level has no entity tick list to seed from
     */
    private boolean seedEntityTickIndex(ServerLevel level) {
        if (this.seededEntityTickLevels.contains(level)) {
            return true;
        }
        if (!level.forEachEntityInTickListForRegionIndex(entity -> this.entityTickStarted(level, entity))) {
            return false;
        }
        this.seededEntityTickLevels.add(level);
        return true;
    }

    /**
     * Publishes that the level's entity tick list now contains {@code entity}, so the entity is indexed in the
     * region its mapping names and the region's worker ticks it.
     * <p>
     * Called by the entity tick list hook on the server thread. A server that does not execute entity ticks on
     * region workers keeps the vanilla tick list only.
     * <p>
     * A player that has no region mapping of this level yet - a login, or the arrival of a dimension change -
     * does not publish one here: its mapping is published at the start of the level's entity slice, like every
     * other player region transition. The membership is not lost, because every player of a level is in its tick
     * list by the time that slice starts.
     */
    @ApiStatus.Internal
    public void entityTickStarted(ServerLevel level, Entity entity) {
        if (!this.entityTickWorkerExecutionEnabled || this.shutdown) {
            return;
        }
        if (entity instanceof ServerPlayer player && this.playerRegions.deferArrival(this, level, player)) {
            return;
        }
        if (!this.regionizer.onEntityTickStarted(level, entity)) {
            this.unindexedEntityTickStarts.incrementAndGet();
        }
    }

    /**
     * Publishes that the level's entity tick list no longer contains {@code entity}.
     * <p>
     * Called by the entity tick list hook on the server thread. The entity keeps its region mapping and its
     * index entry; only the tick membership is cleared.
     */
    @ApiStatus.Internal
    public void entityTickEnded(ServerLevel level, Entity entity) {
        if (!this.entityTickWorkerExecutionEnabled || this.shutdown) {
            return;
        }
        if (entity instanceof ServerPlayer player) {
            this.playerRegions.untrackPlayer(level, player);
        }
        this.regionizer.onEntityTickEnded(level, entity);
    }

    /**
     * Ticks the entities of every active region on its owning region worker and waits for the batch.
     * <p>
     * This is the self-driven form of {@link #tickEntities(ServerLevel, List, RegionTickBarrier)}: the server
     * thread does not collect entities. It activates the level's regions (one chunk eligibility snapshot per
     * region), publishes the batch, leases every region, submits one worker task per region, waits at the
     * barrier and runs the handoff. A worker enumerates the entity tick index of the region it leased, so the
     * server thread never walks the level's entity tick list.
     * <p>
     * A region whose entity tick cannot be enumerated - no chunk rectangle, a retired region, a lease that
     * cannot be acquired - is counted, and its entities are ticked on the server thread after the batch
     * instead of being dropped. An entity of a leased region whose position left the region rectangle, whose
     * chunk guard would need an unloaded chunk, or that only the server thread may tick (a player) is handed
     * back to the server thread the same way.
     */
    void tickSelfDrivenEntities(ServerLevel level, RegionTickBarrier barrier) {
        this.entityTickCoordinator.tickSelfDrivenEntities(level, barrier);
    }

    void tickEntityServerSide(ServerLevel level, Entity entity) {
        this.entityTickCoordinator.tickEntityServerSide(level, entity);
    }

    /**
     * Starts collecting the block-entity tickers of the current {@code level} tick that should tick on
     * region workers.
     *
     * @return the plan to fill, or {@code null} when block-entity ticks have to stay on the server thread
     */
    @Nullable
    public RegionBlockEntityTickPlan beginBlockEntityTickPlan(ServerLevel level, RegionTickBarrier barrier) {
        if (!this.blockEntityTickWorkerExecutionEnabled || this.shutdown || this.regionWorkAdmission.isOwner()) {
            return null;
        }
        // The actual Level path proves each invocation at the canonical cursor. Historical explicit
        // collectors retain their original declaration bridge for compatibility regression callers.
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the block entities inline like vanilla instead of
            // publishing a batch for region state that no longer exists.
            return null;
        }
        return new RegionBlockEntityTickPlan(this, level, barrier);
    }

    /**
     * {@return the dispatcher that executes the scheduled ticks of one phase on region workers, or
     * {@code null} when scheduled ticks have to stay on the server thread}
     * <p>
     * The dispatcher is created before {@link LevelTicks#tick(long, int, java.util.function.BiConsumer,
     * LevelTicks.TickDispatcher)} collects the ticks, so a tick phase that started with the slice enabled
     * stays enabled even if the profiler is activated while the workers run.
     */
    @Nullable
    public <T> LevelTicks.TickDispatcher<T> beginScheduledTickDispatcher(ServerLevel level, ScheduledTickPhase phase, RegionTickBarrier barrier) {
        final boolean phaseEnabled = phase == ScheduledTickPhase.FLUID
                ? (this.scheduledTickWorkerExecutionEnabled || this.fluidTickWorkerExecutionEnabled)
                : this.scheduledTickWorkerExecutionEnabled;
        if (!phaseEnabled || this.shutdown || this.regionWorkAdmission.isOwner()) {
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) return null;
        final RegionScheduledTickMetrics metrics = this.scheduledTickMetrics[phase.ordinal()];
        return new LevelTicks.TickDispatcher<>() {
            private long start;
            private int count;
            private int serialStarted;

            private boolean begun;

            private void begin(List<ScheduledTick<T>> ticks) {
                this.begun = true;
                this.count = ticks.size();
                this.start = this.count > 0 ? System.nanoTime() : 0L;
                this.serialStarted = 0;
                metrics.phases.increment();
                if (this.count > 0) metrics.collected.add(this.count);
            }

            @Override
            public boolean canDispatch(List<ScheduledTick<T>> ticks) {
                this.begin(ticks);
                if (ticks.isEmpty()) return true;
                final long preparation = System.nanoTime();
                final ScheduledTickStatus.SerialReason reason;
                if (level.getProfiler() != InactiveProfiler.INSTANCE) reason = ScheduledTickStatus.SerialReason.PROFILER_ACTIVE;
                else if (RegionThreadingEngine.this.unclassifiedWorkerCallbacksForTesting) reason = null;
                else reason = RegionScheduledTickAdmission.refusal(level, RegionThreadingEngine.this.layout, phase, ticks,
                        RegionThreadingEngine.this.deferredBlockUpdateBudget / RegionTickSlice.SCHEDULED_TICK.reservationPerUnit(),
                        RegionThreadingEngine.this.fluidTickWorkerExecutionEnabled);
                metrics.preparationNanos.add(System.nanoTime() - preparation);
                if (reason != null) metrics.refused(reason);
                else metrics.admitted.add(this.count);
                return reason == null;
            }

            @Override
            public void runInline(Consumer<BiConsumer<BlockPos, T>> runner, BiConsumer<BlockPos, T> callback) {
                final int[] completed = { 0 };
                try {
                    runner.accept((position, type) -> {
                        ++this.serialStarted;
                        try {
                            callback.accept(position, type);
                            ++completed[0];
                        } catch (Throwable failure) {
                            metrics.failures.increment();
                            throw failure;
                        }
                    });
                } finally {
                    if (this.serialStarted > 0) RegionThreadingEngine.this.fallbackScheduledTicks.addAndGet(this.serialStarted);
                    if (completed[0] > 0) metrics.serial.add(completed[0]);
                    final int skipped = Math.max(0, this.count - this.serialStarted);
                    if (skipped > 0) metrics.skipped.add(skipped);
                    if (this.count > 0) metrics.recordPhase(System.nanoTime() - this.start);
                }
            }

            @Override
            public void runCollected(List<ScheduledTick<T>> ticks, BiConsumer<BlockPos, T> callback,
                    BiConsumer<BlockPos, T> fallbackCallback, Consumer<ScheduledTick<T>> beginTick) {
                if (!this.begun) {
                    this.begin(ticks);
                    if (this.count > 0) metrics.admitted.add(this.count);
                }
                if (ticks.isEmpty()) return;
                try {
                    RegionThreadingEngine.this.tickScheduledTicks(level, phase, ticks, callback, fallbackCallback, beginTick, barrier);
                } finally {
                    if (this.count > 0) metrics.recordPhase(System.nanoTime() - this.start);
                }
            }
        };
    }

    /** Bounded diagnostics for the production block/fluid callback protocols. */
    public List<ScheduledTickStatus> scheduledTickStatus() {
        final List<ScheduledTickStatus> result = new ArrayList<>(ScheduledTickPhase.values().length);
        for (final ScheduledTickPhase phase : ScheduledTickPhase.values()) result.add(this.scheduledTickMetrics[phase.ordinal()].snapshot(phase));
        return List.copyOf(result);
    }

    /**
     * Starts the region-scoped chunk tick of the current {@code level} tick.
     * <p>
     * The level's own chunk loop decides which chunks are ticked (natural spawning gate, forced-tick gate,
     * {@code shouldTickBlocksAt}) and hands every such chunk to {@link RegionChunkTickPlan#accept}. This call
     * only decides whether the slice runs at all.
     *
     * @param randomTickSpeed the level's random tick speed, read once by the level for the whole tick
     * @param barrier         waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when the chunk tick has to stay on the server thread
     */
    @Nullable
    public RegionChunkTickPlan beginChunkTickPlan(ServerLevel level, int randomTickSpeed, RegionTickBarrier barrier) {
        if (!this.chunkTickWorkerExecutionEnabled || this.shutdown || this.regionWorkAdmission.isOwner()) {
            return null;
        }
        if (level.entitySectionRegionChunkSize() <= 0) {
            // A random tick reaches entity storage through the region-scoped query of its lease - a raised snow
            // layer pushes the entities standing in it up, a hatched egg spawns one - and that query only exists
            // for a level whose sections are partitioned per region. A level that keeps the legacy shared section
            // storage would have to refuse those ticks, so its chunk tick stays on the server thread.
            if (this.chunkTickStorageNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region chunk ticks stay on the server thread for a level without region-owned entity section storage");
            }
            this.nonPartitionedChunkTicks.incrementAndGet();
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the chunks inline like vanilla instead of publishing a
            // batch for region state that no longer exists.
            return null;
        }
        final RegionSpawnPlan activeSpawn = this.activeTickSpawnPlan;
        this.activeTickSpawnPlan = null;
        if (activeSpawn != null) {
            return new RegionChunkTickPlan(this, level, randomTickSpeed, barrier,
                    activeSpawn.loadedChunkCache(), activeSpawn.verifiedNeighborhoods());
        }
        return new RegionChunkTickPlan(this, level, randomTickSpeed, barrier);
    }

    private @Nullable RegionSpawnPlan activeTickSpawnPlan;

    /**
     * {@return true when every chunk around {@code chunkPos} belongs to the same region}
     * <p>
     * Hoppers and pack-admitted block entities retain this rule because their bodies can reach live neighboring
     * containers. Audited furnace-family bodies may instead use border propagation with a loaded neighborhood.
     * The chunk tick slice also uses border capture rather than requiring interior chunks.
     */
    boolean isInteriorRegionChunk(ChunkPos chunkPos) {
        final int regionX = this.layout.regionX(chunkPos.x);
        final int regionZ = this.layout.regionZ(chunkPos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (this.layout.regionX(chunkPos.x + dx) != regionX || this.layout.regionZ(chunkPos.z + dz) != regionZ) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@return the random source of the scheduled tick group executing on the current thread, or
     * {@code null} outside a worker group}
     */
    @Nullable
    public RandomSource scheduledTickRandom() {
        return this.currentScheduledTickRandom.get();
    }

    /**
     * {@return the server-owned mutation queue of the region tick group executing on the current thread,
     * or {@code null} outside a worker group}
     * <p>
     * Both {@code LevelTicks} instances of a level share this queue, so a block worker that schedules a
     * fluid tick and a fluid worker that schedules a block tick never mutate the other scheduler
     * concurrently. Scheduled tick and random tick groups use the same queue, so the two slices never
     * mutate the scheduling structures concurrently either. The publishing server thread drains it after
     * all worker groups of the batch finished.
     */
    @Nullable
    public Queue<Runnable> regionTickMutationQueue() {
        return this.currentRegionTickMutations.get();
    }

    /**
     * {@return true when the level tick of this engine ticks entities on region workers}
     * <p>
     * This is the configured request, not the effective dispatch: without region-owned entity storage
     * {@link #beginEntityTickPlan} returns {@code null} and the vanilla inline entity tick runs instead.
     */
    @ApiStatus.Internal
    public boolean isEntityTickWorkerExecutionEnabled() {
        return this.entityTickWorkerExecutionEnabled;
    }

    /**
     * {@return true when scheduled block and fluid ticks execute on region workers}
     * <p>
     * This is the configured request, not the effective dispatch. Without an audited callback admission
     * {@link #beginScheduledTickDispatcher} returns {@code null} and the vanilla inline loop runs instead.
     */
    @ApiStatus.Internal
    public boolean isScheduledTickWorkerExecutionEnabled() {
        return this.scheduledTickWorkerExecutionEnabled;
    }

    /**
     * Starts the region-scoped natural spawning of the current {@code level} tick.
     * <p>
     * The level's own chunk loop evaluates the spawn gate of every chunk and hands every chunk that may spawn to
     * {@link RegionSpawnPlan#accept(LevelChunk, boolean)}. This call only decides whether the slice runs at all.
     *
     * @param source  the server-owned reads the plan needs from the level's chunk source
     * @param barrier waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when natural spawning has to stay on the server thread
     */
    @Nullable
    public RegionSpawnPlan beginSpawnPlan(ServerLevel level, RegionSpawnSource source, boolean spawnFriendlies,
            boolean spawnEnemies, boolean spawnPersistent, RegionTickBarrier barrier) {
        if (!this.spawnWorkerExecutionEnabled || this.shutdown || this.regionWorkAdmission.isOwner()) {
            return null;
        }
        if (level.entitySectionRegionChunkSize() <= 0) {
            // A spawn admits its entity through the region ownership lease, and that lease only exists for a level
            // whose sections are partitioned per region. A level that keeps the legacy shared section storage
            // would have to refuse every spawn, so its natural spawning stays on the server thread.
            if (this.spawnStorageNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region natural spawning stays on the server thread for a level without region-owned entity section storage");
            }
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker spawns would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; spawn inline like vanilla instead of publishing a batch for region
            // state that no longer exists.
            return null;
        }
        this.flushStagedFusedChunks(level);
        if (!this.isQuiescentForParallelApi()) {
            return null;
        }
        final var preparedPlan = net.neoforged.neoforge.server.threading.ParallelAdapterRuntime.preparePlan(level);
        if (!preparedPlan.workerReady()) return null;
        final RegionSpawnPlan plan = new RegionSpawnPlan(this, level, source, spawnFriendlies, spawnEnemies, spawnPersistent, barrier, preparedPlan);
        this.activeTickSpawnPlan = plan;
        return plan;
    }

    /**
     * {@return true when the chunk tick of a level executes on region workers}
     * <p>
     * This is the configured request, not the effective dispatch: the profiler, a closing level, the
     * loaded-neighborhood rule, the deferred update budget and a late collection still keep work on the server
     * thread, and every refusal is counted.
     */
    @ApiStatus.Internal
    public boolean isChunkTickWorkerExecutionEnabled() {
        return this.chunkTickWorkerExecutionEnabled;
    }

    /**
     * {@return true when the configured scheduled tick worker slice actually dispatches callbacks}
     */
    @ApiStatus.Internal
    public boolean isScheduledTickWorkerDispatchEffective() {
        return this.scheduledTickWorkerExecutionEnabled && (this.layout.supportsSectionPartitions() || this.unclassifiedWorkerCallbacksForTesting);
    }

    /**
     * {@return true when the configured chunk tick worker slice actually dispatches chunks}
     */
    @ApiStatus.Internal
    public boolean isChunkTickWorkerDispatchEffective() {
        return this.chunkTickWorkerExecutionEnabled;
    }

    /**
     * Enables the unclassified scheduled/random callback mechanics for internal tests only.
     * <p>
     * Production never calls this: an arbitrary scheduled or random callback can spawn, remove or move
     * entities, so it is not admitted to a worker before it starts. The seam exists so the existing
     * batch, random and mutation-queue mechanics keep their coverage.
     */
    @ApiStatus.Internal
    public void enableUnclassifiedWorkerCallbacksForTesting() {
        this.unclassifiedWorkerCallbacksForTesting = true;
    }

    /**
     * Enables the legacy entity query handoff transport for internal tests only.
     * <p>
     * Production rejects legacy entity queries from native workers because a completed handoff still
     * returns live entity references and runs caller predicates/consumers. The seam keeps the handoff
     * transport tests separate from that production policy.
     */
    @ApiStatus.Internal
    public void enableLegacyEntityQueryHandoffForTesting() {
        this.legacyEntityQueryHandoffForTesting = true;
    }

    /**
     * {@return true when the legacy entity query handoff is enabled for the current engine}
     */
    @ApiStatus.Internal
    public boolean isLegacyEntityQueryHandoffEnabled() {
        return this.legacyEntityQueryHandoffForTesting;
    }

    /**
     * {@return true when the block-entity plan reuses the cached admission of a chunk}
     */
    @ApiStatus.Internal
    public boolean isBlockEntityAdmissionCacheEnabled() {
        return this.blockEntityAdmissionCacheEnabled;
    }

    /**
     * Turns the per-chunk block-entity admission cache off for internal tests only.
     * <p>
     * Production never calls this: the cache is the production plan path. The seam exists so the equivalence
     * test can run the uncached evaluation, which is the reference behavior of the cached plan, over the same
     * chunk and ticker states.
     */
    @ApiStatus.Internal
    public void setBlockEntityAdmissionCacheEnabledForTesting(boolean enabled) {
        this.blockEntityAdmissionCacheEnabled = enabled;
    }

    /**
     * Installs the diagnostics-only observer of worker entity tick failures, or removes it with
     * {@code null}.
     * <p>
     * Only the entity worker diagnostics benchmark calls this. Every failing tick is reported to the
     * observer and the group continues with its next entity, so one run collects every blocked operation
     * instead of only the first one. The worker entity body builds no crash report at all: without an
     * observer the failure is recorded for the server thread and the group stops, with an observer it reaches
     * the observer and the group continues, see {@link RegionTickFailure}.
     */
    @ApiStatus.Internal
    public void setEntityTickFailureObserverForTesting(@Nullable BiConsumer<Entity, Throwable> observer) {
        this.entityTickFailureObserverForTesting = observer;
    }

    /**
     * {@return the random source of the random tick group executing on the current thread, or
     * {@code null} outside a random tick worker group}
     */
    @Nullable
    public RandomSource chunkTickRandom() {
        return this.currentChunkTickRandom.get();
    }

    /**
     * {@return the position selection of the chunk tick executing on the current thread, or {@code null}
     * outside a chunk tick worker group}
     */
    @Nullable
    public RandomSource chunkTickPositionRandom() {
        return this.currentChunkTickPositionRandom.get();
    }

    /**
     * Records the lightning strike of a chunk tick worker for the batch barrier.
     *
     * @return {@code true} when the strike was recorded, {@code false} on a thread that has to resolve it
     *         inline
     */
    public boolean deferChunkTickLightning(BlockPos rolledPos) {
        final Queue<BlockPos> strikes = this.currentChunkTickLightning.get();
        if (strikes == null) {
            return false;
        }
        strikes.add(rolledPos);
        return true;
    }

    /**
     * Records the light section status change of one block write of a region worker group for the batch barrier.
     *
     * @param pos   the written position, already immutable
     * @param empty whether the section of the position holds only air now
     * @return {@code true} when the change was recorded, {@code false} on a thread that has to call the light
     *         engine itself
     */
    public boolean deferLightSectionStatus(net.minecraft.world.level.Level level, BlockPos pos, boolean empty) {
        if (this.deferLightSectionStatus(pos, empty)) {
            return true;
        }
        if (RegionWorkerScope.isNativeWorkerThread() && level instanceof ServerLevel serverLevel) {
            final BlockPos immutablePos = pos.immutable();
            final Queue<Runnable> mutations = this.currentRegionTickMutations.get();
            if (mutations != null) {
                mutations.add(() -> serverLevel.getChunkSource().getLightEngine().updateSectionStatus(immutablePos, empty));
                return true;
            }
            if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(
                    serverLevel, () -> serverLevel.getChunkSource().getLightEngine().updateSectionStatus(immutablePos, empty))) {
                return true;
            }
            if (serverLevel.getServer() != null) {
                serverLevel.getServer().execute(() -> serverLevel.getChunkSource().getLightEngine().updateSectionStatus(immutablePos, empty));
                return true;
            }
        }
        return false;
    }

    public boolean deferLightSectionStatus(BlockPos pos, boolean empty) {
        final RegionDeferredBlockUpdates updates = RegionTickBatch.currentBlockUpdates();
        if (updates == null) {
            return false;
        }
        if (updates.attributesRegistrations()) {
            final long start = System.nanoTime();
            updates.deferSectionStatus(pos, empty);
            RegionBlockEntityTickAttribution.recordDeferredRegistration(System.nanoTime() - start);
            return true;
        }
        updates.deferSectionStatus(pos, empty);
        return true;
    }

    /**
     * Records the light recheck of one block write of a region worker group for the batch barrier.
     *
     * @param pos the written position, already immutable
     * @return {@code true} when the recheck was recorded, {@code false} on a thread that has to call the light
     *         engine itself
     */
    public boolean deferLightCheck(net.minecraft.world.level.Level level, BlockPos pos) {
        if (this.deferLightCheck(pos)) {
            return true;
        }
        if (RegionWorkerScope.isNativeWorkerThread() && level instanceof ServerLevel serverLevel) {
            final BlockPos immutablePos = pos.immutable();
            final Queue<Runnable> mutations = this.currentRegionTickMutations.get();
            if (mutations != null) {
                mutations.add(() -> serverLevel.getChunkSource().getLightEngine().checkBlock(immutablePos));
                return true;
            }
            if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(
                    serverLevel, () -> serverLevel.getChunkSource().getLightEngine().checkBlock(immutablePos))) {
                return true;
            }
            if (serverLevel.getServer() != null) {
                serverLevel.getServer().execute(() -> serverLevel.getChunkSource().getLightEngine().checkBlock(immutablePos));
                return true;
            }
        }
        return false;
    }

    public boolean deferLightCheck(BlockPos pos) {
        final RegionDeferredBlockUpdates updates = RegionTickBatch.currentBlockUpdates();
        if (updates == null) {
            return false;
        }
        if (updates.attributesRegistrations()) {
            final long start = System.nanoTime();
            updates.deferLightCheck(pos);
            RegionBlockEntityTickAttribution.recordDeferredRegistration(System.nanoTime() - start);
            return true;
        }
        updates.deferLightCheck(pos);
        return true;
    }

    /**
     * Records the block update publication of one block write of a region worker group for the batch barrier.
     *
     * @return {@code true} when the publication was recorded, {@code false} on a thread that has to publish it
     *         itself
     */
    public boolean deferBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        final RegionDeferredBlockUpdates updates = RegionTickBatch.currentBlockUpdates();
        if (updates == null) {
            return false;
        }
        if (updates.attributesRegistrations()) {
            // The registration and the re-run seed of the written position are the deferred half of the write:
            // both are work the same write would not do on the server thread, so they are timed together.
            final long start = System.nanoTime();
            updates.deferBlockUpdate(pos, oldState, newState, flags);
            this.recordRerunSeed(pos);
            RegionBlockEntityTickAttribution.recordDeferredRegistration(System.nanoTime() - start);
            return true;
        }
        updates.deferBlockUpdate(pos, oldState, newState, flags);
        // The position is the propagation a re-run of this region has to repeat if a chunk it read changed
        // under the write. Recording it here costs one context read and one list add per written block.
        this.recordRerunSeed(pos);
        return true;
    }

    /**
     * Remembers one position the region of the current group wrote, so a re-run of the region can re-evaluate it.
     * <p>
     * Only a thread that holds the lease of the region records: a write the group sends to another region is
     * already a border update, and the region that applies it records it as its own seed there. A region that has
     * not read a chunk of another region since its last check records nothing: no re-run can need the position,
     * so the seed list of a region that never reads across a border stays empty.
     */
    private void recordRerunSeed(BlockPos pos) {
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext) || basicContext.regionState == null) {
            return;
        }
        final RegionState region = basicContext.regionState;
        if (!region.hasForeignChunkReads() || !region.isOwnedByCurrentThread()) {
            return;
        }
        // A position past the bound is counted by the region and drained with its next check.
        region.recordRerunSeed(pos.asLong());
    }

    /** {@return true when this engine captures cross-region border updates} */
    public boolean borderUpdatesEnabled() {
        return this.borderUpdatesEnabled;
    }

    /**
     * Enables or disables the border capture of this engine.
     * <p>
     * Production reads the switch from the server config at construction. The setter is the mechanics seam the
     * cross-region tests use, so a test does not have to load the config.
     */
    @ApiStatus.Internal
    public void setBorderUpdatesEnabledForTesting(boolean enabled) {
        this.borderUpdatesEnabled = enabled;
    }

    /**
     * Overrides the fixpoint bound, so a mechanics test can observe the next-tick fallback without producing
     * the level's default million chained updates.
     */
    @ApiStatus.Internal
    public void setBorderUpdateBudgetForTesting(int budget) {
        this.borderUpdateBudgetForTesting = budget;
    }

    /**
     * Lowers the number of border updates one capture pass admits, so a mechanics test can observe the capture
     * bound without producing {@link RegionBorderUpdates#MAX_ENTRIES} updates.
     * <p>
     * The capacity is read when the queue of a level is created, so a test has to call this before the first
     * update of the level is recorded.
     *
     * @param capacity the entries one capture queue admits; must be positive
     */
    @ApiStatus.Internal
    public void setBorderUpdateCapacityForTesting(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("The border update capacity has to admit at least one entry: " + capacity);
        }
        this.borderUpdateCapacity = capacity;
    }

    /** {@return the capture queue of {@code level}, created with the configured capacity} */
    private RegionBorderUpdates borderQueue(ServerLevel level) {
        return this.borderUpdates.computeIfAbsent(level, key -> new RegionBorderUpdates(this.borderUpdateCapacity));
    }

    /**
     * Enables or disables the cross-region game event capture of this engine.
     * <p>
     * Production reads the switch from the server config at construction. The setter is the mechanics seam the
     * cross-region tests use, so a test does not have to load the config.
     */
    @ApiStatus.Internal
    public void setCrossRegionGameEventsEnabledForTesting(boolean enabled) {
        this.crossRegionGameEventsEnabled = enabled;
    }

    /**
     * Lowers the number of game event columns one capture pass admits, so a mechanics test can observe the
     * capture bound without producing {@link RegionBorderGameEvents#MAX_ENTRIES} columns.
     * <p>
     * The capacity is read when the queue of a level is created, so a test has to call this before the first
     * column of the level is recorded.
     *
     * @param capacity the columns one capture queue admits; must be positive
     */
    @ApiStatus.Internal
    public void setGameEventCapacityForTesting(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("The game event capture capacity has to admit at least one column: " + capacity);
        }
        this.gameEventCapacity = capacity;
    }

    /** {@return the game event capture queue of {@code level}, created with the configured capacity} */
    private RegionBorderGameEvents gameEventQueue(ServerLevel level) {
        return this.borderGameEvents.computeIfAbsent(level, key -> new RegionBorderGameEvents(this.gameEventCapacity));
    }

    /**
     * Counts one game event posted on {@code level} while a region batch of it was in flight.
     * <p>
     * Runs at the head of {@code GameEventDispatcher.post}, so it is one volatile read and one phase lookup for a
     * post the engine does not capture. With the switch off it counts nothing: the dispatch is then the vanilla
     * one and the counters of the feature stay at zero.
     *
     * @param level the level the event was posted on
     */
    @ApiStatus.Internal
    public void recordGameEventPost(ServerLevel level) {
        if (this.crossRegionGameEventsEnabled && this.levelTickPhase(level) == LevelTickPhase.REGION) {
            this.gameEventsPosted.incrementAndGet();
        }
    }

    /**
     * Captures one section column of a game event dispatch whose listeners belong to another region.
     * <p>
     * Only a region lease captures: the server thread outside a region batch, the global region and the quiescent
     * block event phase deliver inline, because no region is ticked by another thread while they run. A worker
     * that owns the column delivers it inline too. A column of another region is recorded for the region that
     * owns it and never visited by this thread, so neither the listener registry nor the block entity of the
     * foreign section is read or written here.
     *
     * @return {@code true} when the caller has to skip the column, whether it was recorded or refused
     */
    @ApiStatus.Internal
    public boolean captureCrossRegionGameEvent(ServerLevel level, net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
            net.minecraft.world.phys.Vec3 source, net.minecraft.world.level.gameevent.GameEvent.Context context, int chunkX, int chunkZ,
            int minSectionY, int maxSectionY) {
        if (!this.crossRegionGameEventsEnabled || this.levelTickPhase(level) != LevelTickPhase.REGION) {
            return false;
        }
        if (this.isOwnedByCurrentRegion(level, chunkX, chunkZ)) {
            this.gameEventSectionsLocal.incrementAndGet();
            return false;
        }
        final RegionBorderGameEvents.SectionColumn column = new RegionBorderGameEvents.SectionColumn(event, source, context, chunkX, chunkZ,
                minSectionY, maxSectionY);
        if (this.gameEventQueue(level).record(column)) {
            this.gameEventSectionsDeferred.incrementAndGet();
        } else {
            // The capture bound refused the column. The worker still must not visit it, so the event of this
            // column is dropped and counted; a next-tick replay would record a candidate in the wrong game time.
            this.gameEventSectionsRefused.incrementAndGet();
        }
        return true;
    }

    /**
     * Records one neighbor update whose target belongs to another region.
     *
     * @return {@code true} when the caller has to skip the vanilla update
     */
    public boolean deferBorderNeighborUpdate(ServerLevel level, BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level, new RegionBorderUpdates.NeighborUpdate(pos.immutable(), block, neighborPos.immutable(), movedByPiston));
    }

    /**
     * Records one shape update whose target belongs to another region.
     *
     * @return {@code true} when the caller has to skip the vanilla update
     */
    public boolean deferBorderShapeUpdate(ServerLevel level, Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos,
            int updateFlags, int updateLimit) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level,
                new RegionBorderUpdates.ShapeUpdate(direction, state, pos.immutable(), neighborPos.immutable(), updateFlags, updateLimit));
    }

    /**
     * Records one direction of {@code Level.updateNeighbourForOutputSignal} whose target belongs to another
     * region.
     *
     * @return {@code true} when the caller has to skip the vanilla body of that direction
     */
    public boolean deferBorderComparatorUpdate(ServerLevel level, BlockPos pos, BlockPos sourcePos, Block sourceBlock, Direction direction) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level,
                new RegionBorderUpdates.ComparatorUpdate(pos.immutable(), sourcePos.immutable(), sourceBlock, direction));
    }

    /**
     * Records the unsaved-chunk mark of one block-entity change whose block entity belongs to another region.
     *
     * @return {@code true} when the caller has to skip the vanilla mark
     */
    public boolean deferBorderBlockEntityChange(ServerLevel level, BlockPos pos) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level, new RegionBorderUpdates.BlockEntityChange(pos.immutable()));
    }

    /**
     * Records one block write whose position belongs to another region, so the target region runs the vanilla
     * {@code Level.setBlock} on its own worker at the barrier.
     * <p>
     * The record is the whole call - position, state, update flags and recursion budget - so the target region's
     * block-entity lifecycle, heightmaps, light recheck and neighbour and shape propagation are the vanilla ones
     * of the write, not a partial replay of it. The recording worker never mutates the target chunk.
     * <p>
     * <strong>Read after write.</strong> The write is applied by another region at the barrier, so a callback
     * that reads the same position again in the same lease observes the state before the write. The position is
     * remembered in the recording region, so
     * {@link net.neoforged.neoforge.server.threading.RegionThreadingHooks#observeReadAfterDeferredBlockWrite(BlockPos)}
     * can count the approximation instead of leaving it silent.
     * <p>
     * <strong>Bound.</strong> The capture queue of the fixpoint bounds the records of one pass. A write past the
     * bound is not queued and this method answers {@code false}: the caller falls back to the worker mutation
     * guard, which refuses the write with the existing no-replay reason and counts it. Unlike a neighbour update,
     * an overflowed block write is not handed to the next-tick fallback, because the caller of the write was
     * told it was accepted and a write that appears a tick later would be a silent difference from inline.
     *
     * @return {@code true} when the caller has to skip the inline write and report the call as accepted
     */
    @ApiStatus.Internal
    public boolean deferBorderBlockWrite(ServerLevel level, BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        final RegionBorderUpdates updates = this.borderQueue(level);
        final BlockPos writePos = pos.immutable();
        final RegionBorderUpdates.RecordResult result = updates.record(
                new RegionBorderUpdates.BlockWrite(writePos, state, flags, recursionLeft), false);
        if (result == RegionBorderUpdates.RecordResult.OVERFLOW) {
            this.borderUpdatesOverflowed.incrementAndGet();
            this.borderBlockWritesOverflowed.incrementAndGet();
            return false;
        }
        this.borderUpdatesRecorded.incrementAndGet();
        this.borderBlockWritesRecorded.incrementAndGet();
        this.recordDeferredWrite(writePos);
        return true;
    }

    /**
     * Counts one read of a position the current region wrote outside its lease during the current lease.
     * <p>
     * Runs on the read path of a foreign chunk of a native region worker, so it costs one context read and one
     * hash lookup per such read. It never changes the value the read returns: the caller observes the state
     * before the deferred write, which is the documented approximation.
     *
     * @param packedPos the read position, as {@link BlockPos#asLong(int, int, int)}
     */
    @ApiStatus.Internal
    public void observeReadAfterDeferredBlockWrite(long packedPos) {
        final RegionThreadingContext context = this.currentContext();
        if (context instanceof BasicRegionThreadingContext basicContext
                && basicContext.regionState != null
                && basicContext.regionState.wroteDeferred(packedPos)) {
            this.readAfterDeferredBlockWrite.incrementAndGet();
        }
    }

    /** Remembers one position the current region wrote outside its lease, for the read-after-write count. */
    private void recordDeferredWrite(BlockPos pos) {
        final RegionThreadingContext context = this.currentContext();
        if (context instanceof BasicRegionThreadingContext basicContext && basicContext.regionState != null) {
            basicContext.regionState.recordDeferredWrite(pos.asLong());
        }
    }

    /**
     * Records the block event of a region worker for the server thread's block event phase.
     * <p>
     * {@code ServerLevel.blockEvent} adds the event to one level-wide ordered set, and the body of a piston
     * move or a note block plays through the level and can write the next region. The event is recorded in the
     * worker batch's server-owned mutation queue, so the set is mutated only on the server thread, in the order
     * the worker produced the event. The queue is drained at the batch barrier, before vanilla's block event
     * phase, so the event still runs in the same tick.
     *
     * @return {@code true} when the caller has to skip the inline add
     */
    @ApiStatus.Internal
    public boolean deferBlockEvent(ServerLevel level, BlockPos pos, Block block, int eventId, int eventParam) {
        final Queue<Runnable> mutations = this.currentRegionTickMutations.get();
        if (mutations == null || this.shutdown) {
            return false;
        }
        final BlockPos immutablePos = pos.immutable();
        mutations.add(() -> level.blockEvent(immutablePos, block, eventId, eventParam));
        this.deferredBlockEvents.incrementAndGet();
        return true;
    }

    /** {@return the block events region workers deferred to the server thread's block event phase} */
    @ApiStatus.Internal
    public long deferredBlockEvents() {
        return this.deferredBlockEvents.get();
    }

    /** {@return the block entity reads a region group made against a quiescent foreign region} */
    @ApiStatus.Internal
    public long foreignBlockEntityReads() {
        return this.foreignBlockEntityReads.get();
    }

    /** {@return the foreign chunk reads a region recorded for the border fixpoint} */
    @ApiStatus.Internal
    public long foreignChunkReads() {
        return this.foreignChunkReads.get();
    }

    /** {@return reads of versioned chunks that no region owns} */
    @ApiStatus.Internal
    public long readOfUnownedChunk() {
        return this.readOfUnownedChunk.get();
    }

    /** Counts a versionless native-worker read refused before returning chunk data. */
    @ApiStatus.Internal
    public void recordVersionlessChunkRead() {
        this.readOfVersionlessChunk.incrementAndGet();
    }

    /** {@return the fixpoint checks in which a region observed a changed version of a foreign chunk} */
    @ApiStatus.Internal
    public long foreignReadInvalidatedPasses() {
        return this.foreignReadInvalidatedPasses.get();
    }

    /** {@return the region propagation groups the fixpoint re-ran because a foreign read was invalidated} */
    @ApiStatus.Internal
    public long foreignReadRegionReruns() {
        return this.foreignReadRegionReruns.get();
    }

    /** {@return the written positions a region dropped because its re-run seed bound was reached} */
    @ApiStatus.Internal
    public long foreignReadSeedsDropped() {
        return this.foreignReadSeedsDropped.get();
    }

    /**
     * Enables or disables the re-run of a region whose foreign read was invalidated.
     * <p>
     * The race tests turn it off to show the stale state a cross-region read produces without the version
     * check; production always has it on. The switch does not disable the recording, so a disabled check still
     * counts the foreign reads it did not act on.
     */
    @ApiStatus.Internal
    public void setForeignReadRerunEnabledForTesting(boolean enabled) {
        this.foreignReadRerunEnabled = enabled;
    }

    /**
     * {@return true when the current thread holds a region lease that does not contain {@code pos}}
     * <p>
     * Only a region lease captures. The server thread, the global region, a plain region queue task without a
     * lease and a worker whose target is inside its own region all keep the vanilla inline path. The check runs
     * before the record is allocated, so a server with the switch on but no cross-region update pays no
     * allocation per neighbor update.
     * <p>
     * A block event phase never captures: it runs on the server thread while every region batch of the level has
     * returned, so no worker can observe a write to another region and the vanilla inline call is both safe and
     * the exact behaviour the event body would have inline. Capturing there would only queue the write for a
     * fixpoint and hide it from the body's own reads, which is how a piston that pushes across a border used to
     * queue a second block event vanilla never queues.
     */
    private boolean capturesBorderUpdate(ServerLevel level, BlockPos pos) {
        if (!this.borderUpdatesEnabled || this.blockEventPhase.get() != null || this.blockEntityFallbackPhase.get() != null) {
            return false;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionCoordinate == null) {
            return false;
        }
        return !this.isOwnedByCurrentRegion(level, pos);
    }

    private boolean recordBorderUpdate(ServerLevel level, RegionBorderUpdates.BorderUpdate update) {
        final RegionBorderUpdates updates = this.borderQueue(level);
        final RegionBorderUpdates.RecordResult result = updates.record(update);
        if (result == RegionBorderUpdates.RecordResult.ACCEPTED) {
            this.borderUpdatesRecorded.incrementAndGet();
        } else if (result == RegionBorderUpdates.RecordResult.OVERFLOW) {
            this.borderUpdatesOverflowed.incrementAndGet();
        }
        // A duplicate is already queued and an overflow is kept for the next-tick fallback: the caller must not
        // apply either inline, because the worker does not hold the target lease.
        return true;
    }

    /**
     * {@return true when the current thread holds a region lease that does not contain {@code update.pos()}}
     * <p>
     * Only a region lease captures. The server thread, the global region, a plain region queue task without a
     * lease and a worker whose target is inside its own region all keep the vanilla inline path.
     */
    /**
     * {@return the entries one region tick batch may reserve for its deferred block updates}
     */
    int deferredBlockUpdateBudget() {
        return this.deferredBlockUpdateBudget;
    }

    /**
     * {@return the number of chunks one chunk tick batch may admit to its deferred update budget}
     */
    int chunkTickDeferredUpdateBudget() {
        return this.deferredBlockUpdateBudget / RegionTickSlice.CHUNK_TICK.reservationPerUnit();
    }

    /**
     * Lowers the deferred block-update budget, so a mechanics test can observe the refusal without admitting
     * {@link RegionDeferredBlockUpdates#MAX_ENTRIES} entries.
     *
     * @param maxEntries the entries one batch may reserve; must be positive
     */
    @org.jetbrains.annotations.VisibleForTesting
    public void setDeferredBlockUpdateBudget(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("The deferred block-update budget has to admit at least one entry: " + maxEntries);
        }
        this.deferredBlockUpdateBudget = maxEntries;
    }

    /**
     * Lowers the deferred update budget of the chunk tick batch, so a mechanics test can observe the refusal
     * without admitting thousands of chunks.
     *
     * @param maxChunks the number of chunks one batch may admit; must be positive
     */
    @org.jetbrains.annotations.VisibleForTesting
    public void setChunkTickDeferredUpdateBudget(int maxChunks) {
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("The deferred update budget has to admit at least one chunk: " + maxChunks);
        }
        this.deferredBlockUpdateBudget = maxChunks * RegionTickSlice.CHUNK_TICK.reservationPerUnit();
    }

    /**
     * {@return the random source of the region worker group executing on the current thread, or
     * {@code null} outside a worker group}
     * <p>
     * Entity, scheduled, random and block-entity tick groups each own a random source while they run. The
     * level random wrapper uses this to redirect direct {@code level.random} reads of a worker to the random
     * source of its group instead of the shared vanilla source, which no two workers ever share because every
     * group owns its own source.
     */
    @Nullable
    public RandomSource currentGroupRandom() {
        final RandomSource player = RegionPlayerStateScope.random();
        if (player != null) {
            return player;
        }
        final RandomSource scheduled = this.currentScheduledTickRandom.get();
        if (scheduled != null) {
            return scheduled;
        }
        final RandomSource random = this.currentChunkTickRandom.get();
        if (random != null) {
            return random;
        }
        final RandomSource spawn = this.currentSpawnRandom.get();
        if (spawn != null) {
            return spawn;
        }
        final RandomSource blockEntity = this.currentBlockEntityTickRandom.get();
        if (blockEntity != null) {
            return blockEntity;
        }
        return this.currentEntityTickRandom.get();
    }

    /**
     * {@return the random source of the block tick group executing on the current thread, or {@code null}
     * outside a scheduled, random or block-entity tick worker group}
     * <p>
     * This is the narrower question the block-entity access gate asks, because only a group that ticks the
     * blocks or the block entities of its region opens that gate. A scheduled or random tick group opens it
     * because its callback runs block code that reads its own block entity; a block-entity tick group opens it
     * because its ticker reads the block entities of its region - a hopper reads its attached container, which
     * is one block away. An entity tick group owns a random source as well, but that one is resolved through
     * {@link #currentGroupRandom()} and does not grant block-entity access.
     */
    @Nullable
    public RandomSource currentRegionTickRandom() {
        final RandomSource scheduled = this.currentScheduledTickRandom.get();
        if (scheduled != null) {
            return scheduled;
        }
        final RandomSource random = this.currentChunkTickRandom.get();
        return random != null ? random : this.currentBlockEntityTickRandom.get();
    }

    /**
     * {@return true when the current thread is a region worker that executes a region tick batch whose
     * server-thread wait can service a handoff}
     */
    @ApiStatus.Internal
    public boolean isRegionTickBatchWorkerThread() {
        return RegionTickBatch.current() != null && this.workerPool.isWorkerThread(Thread.currentThread());
    }

    /** Only ordinary entity batches service this result-bearing visual call; sandboxed BE workers defer visually. */
    public <T> T sendParticlesFromWorker(ServerLevel level, Supplier<T> send) {
        return this.sendParticlesFromWorker(level, send, null);
    }

    public <T> T sendParticlesFromWorker(ServerLevel level, Supplier<T> send, @Nullable T sandboxFallbackResult) {
        final var group = this.currentEntityTickGroup();
        if (RegionWorkerScope.isNativeWorkerThread() && group != null && group.level() == level
                && RegionTickBatch.current() == group.batch() && level.getServer() != null
                && !RegionPlayerStateScope.hasThreadBinding()
                && !group.batch().isAsyncDetached()) {
            return this.runOnServerThread(level, send);
        }
        if (sandboxFallbackResult != null
                && RegionWorkerScope.isNativeWorkerThread()
                && !RegionPlayerStateScope.hasThreadBinding()
                && level.getServer() != null) {
            final Queue<Runnable> mutations = this.currentRegionTickMutations.get();
            if (mutations != null) {
                mutations.add(() -> send.get());
            } else if (this.independentTickScheduler != null) {
                this.independentTickScheduler.enqueueCommit(level, () -> send.get());
            } else {
                level.getServer().execute(() -> send.get());
            }
            return sandboxFallbackResult;
        }
        throw RegionWorkerBoundaryException.networkBroadcast("ServerLevel.sendParticles",
                "Particle send requires a serviced entity tick batch");
    }

    public long orderedSpawnTransactions() {
        return this.orderedSpawnTransactions.get();
    }

    public long orderedSpawnServiceNanos() {
        return this.orderedSpawnServiceNanos.get();
    }

    @ApiStatus.Internal
    public boolean isOrderedSpawnServerPhase(ServerLevel level) {
        final var batch = RegionTickBatch.current();
        return batch instanceof RegionSpawnBatch && batch.level() == level && batch.isOrderedSpawnServerPhase();
    }

    @ApiStatus.Internal
    public boolean isOrderedSpawnWorker() {
        final var batch = RegionTickBatch.current();
        return batch instanceof RegionSpawnBatch && batch.hasOrderedSpawnWorker();
    }

    @ApiStatus.Internal
    public boolean isAsyncOrderedSpawnWorker(ServerLevel level) {
        final var batch = RegionTickBatch.current();
        return batch instanceof RegionSpawnBatch spawnBatch
                && spawnBatch.level() == level
                && spawnBatch.isAsyncOrderedFinalization();
    }

    @ApiStatus.Internal
    public net.neoforged.neoforge.server.threading.RegionThreadingHooks.SpawnFinalization deferAsyncOrderedSpawnFinalization(
            ServerLevel level, net.minecraft.world.entity.Mob mob, @Nullable net.minecraft.world.entity.SpawnGroupData groupData,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            net.minecraft.world.level.NaturalSpawner.AfterSpawnCallback afterSpawn, int total, int groupCount) {
        return this.spawnCoordinator.deferAsyncOrderedSpawnFinalization(level, mob, groupData, chunk, afterSpawn, total, groupCount);
    }

    @ApiStatus.Internal
    public void spawnAdmissionCheckpoint() {
        final var batch = RegionTickBatch.current();
        if (batch instanceof RegionSpawnBatch) batch.spawnAdmissionCheckpoint();
    }

    @ApiStatus.Internal
    public <T> T orderedSpawnTransaction(ServerLevel level, Supplier<T> operation) {
        final var batch = RegionTickBatch.current();
        if (!(batch instanceof RegionSpawnBatch) || batch.level() != level || !batch.hasOrderedSpawnWorker()) {
            throw new IllegalStateException("No matching ordered spawn group");
        }
        final Supplier<T> scoped = net.neoforged.neoforge.server.threading.ParallelAdapterRuntime.captureOrderedSpawnContext(level, operation);
        final RandomSource random = this.currentSpawnRandom.get();
        return batch.orderedSpawnTransaction(() -> {
            final RandomSource previous = this.currentSpawnRandom.get();
            if (random == null) this.currentSpawnRandom.remove();
            else this.currentSpawnRandom.set(random);
            final long start = System.nanoTime();
            this.orderedSpawnTransactions.incrementAndGet();
            try {
                return scoped.get();
            } finally {
                this.orderedSpawnServiceNanos.addAndGet(System.nanoTime() - start);
                if (previous == null) this.currentSpawnRandom.remove();
                else this.currentSpawnRandom.set(previous);
            }
        });
    }

    /**
     * Runs a complete computation on the server thread while the current region tick worker waits for it.
     * <p>
     * The computation must encapsulate everything that touches unregionized shared state, including the
     * consumer of any entity query. The server thread is inside the batch barrier while it waits, so the
     * handoff is serviced during the wait; the worker receives the exact result or exception of the
     * computation and never continues against the shared state itself. Outside a region tick batch (the
     * server thread, a region drain or the global worker) the computation runs inline unchanged.
     * <p>
     * A level that closed before the handoff ran fails the computation instead of running a callback after
     * the level was unloaded.
     */
    @ApiStatus.Internal
    public <T> T runOnServerThread(ServerLevel level, Supplier<T> computation) {
        RegionPlayerStateScope.rejectServicePump();
        final RegionTickBatch batch = RegionTickBatch.current();
        if (batch == null) {
            return computation.get();
        }
        return batch.runOnServerThread(level, () -> {
            if (this.regionizer.isLevelClosed(level)) {
                throw new IllegalStateException("Level closed before region main-thread work ran");
            }
            return computation.get();
        });
    }

    /**
     * {@return the region that owns {@code pos} under this engine's layout}
     */
    RegionCoordinate regionAt(ServerLevel level, BlockPos pos) {
        return this.layout.regionAt(level, pos);
    }

    /**
     * {@return the region currently owning {@code entity}, recording the ownership in the regionizer}
     * <p>
     * This publishes the mapping of an untracked entity and therefore takes the regionizer write lock. The
     * per-tick grouping of the entity slice uses {@link #regionFor(Entity)} instead: the authoritative
     * mapping is maintained by the entity's own tick (and by the recorded transfer), so grouping does not
     * need to write it again.
     */
    @Nullable
    RegionState regionOf(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return this.regionizer.moveEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
    }

    /**
     * {@return the region currently owning {@code entity}, or {@code null} when it is not tracked}
     * <p>
     * The read-only counterpart of {@link #regionOf(Entity)}: it never publishes a mapping and takes only the
     * regionizer read lock. The entity tick plan groups with it, so grouping a tick does not need the
     * regionizer write lock per entity.
     */
    @Nullable
    RegionState regionFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        final RegionState region = this.regionizer.regionFor(entity);
        if (region == null || region.coordinate().level() != level) {
            return null;
        }
        return region;
    }

    /**
     * Publishes the region mapping and the tick membership of one player of {@code level}.
     * <p>
     * The mapping write, the index migration and the membership mark are one regionizer write, so a reader
     * observes the player in exactly one region of the level at every point. The caller is
     * {@link PlayerRegionTransitions}, which publishes every player of a level at the start of the level's entity
     * slice.
     *
     * @return the region the player is mapped into, or {@code null} when the level identity closed
     */
    @Nullable
    RegionState publishPlayerRegion(ServerLevel level, ServerPlayer player, RegionCoordinate coordinate) {
        final RegionState region = this.regionizer.publishPlayerRegion(player, coordinate);
        if (region != null) {
            this.notifyEntityMoved(player);
        }
        return region;
    }

    /**
     * {@return the number of regions whose entity tick index holds {@code entity}}
     * <p>
     * One region is the invariant: a player indexed by two regions would be handed to the server thread twice, and
     * a player indexed by none is never handed over. The scan is the structural half of the player region
     * invariant and only runs in an audit, never in the tick.
     */
    @ApiStatus.Internal
    public int indexedRegionCount(Entity entity) {
        return this.regionizer.indexedRegionCount(entity);
    }

    /** {@return true when a region tick batch of {@code level} is published and has not finished yet} */
    boolean hasActiveBatch(ServerLevel level) {
        for (final RegionTickBatch batch : this.activeTickBatches) {
            if (batch.level() == level) {
                return true;
            }
        }
        return false;
    }

    @ApiStatus.Internal
    public RegionPlayerTickCoordinator playerTicks() {
        return this.playerTicks;
    }

    /** Full production pre-body contract. Scheduling still requires the original phase ticket and network barrier. */
    @ApiStatus.Internal
    public RegionPlayerAdmissionPlan.Capture capturePlayerAdmissionPlan(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
        return this.capturePlayerAdmissionPlan(player, phase, null, null);
    }

    RegionPlayerAdmissionPlan.Capture capturePlayerAdmissionPlan(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            @Nullable RegionPlayerExtensionSnapshot.Check prefixEnvironment, @Nullable RegionPlayerSpatialPlan.Capture geometry) {
        var capture = RegionPlayerAdmissionPlan.capture(this, player, phase, null, prefixEnvironment, geometry);
        return this.recordPlayerAdmissionCapture(phase, capture);
    }

    RegionPlayerAdmissionPlan.Capture recordPlayerAdmissionCapture(RegionPlayerTickCoordinator.Phase phase, RegionPlayerAdmissionPlan.Capture capture) {
        if (capture.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) {
            this.playerAdmissionRefusals.computeIfAbsent(phase.name() + ":" + capture.refusal().name(), key -> new AtomicLong()).incrementAndGet();
            this.playerAdmissionLastRefusal.put(phase.name(), capture.refusal() + ":" + capture.detail());
        } else if (!"NONE:".equals(this.playerAdmissionLastRefusal.get(phase.name()))) {
            this.playerAdmissionLastRefusal.put(phase.name(), "NONE:");
        }
        return capture;
    }

    /** Bounded enum keys only; details belong to the returned Capture, never retained player objects. */
    public Map<String, Long> playerAdmissionRefusals() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        this.playerAdmissionRefusals.forEach((reason, count) -> counts.put(reason, count.get()));
        return Map.copyOf(counts);
    }

    @Nullable
    String playerServerOnlyReason(ServerPlayer player) {
        return this.entityTickPolicy.playerReasonFor(player);
    }

    void warmPlayerAttributes(net.minecraft.world.entity.ai.attributes.AttributeMap attributes) {
        if (this.warmedPlayerAttributes.containsKey(attributes)) return;
        net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.holders().forEach(attribute -> {
            var instance = attributes.getInstance(attribute);
            if (instance != null) instance.getValue();
        });
        this.warmedPlayerAttributes.put(attributes, Boolean.TRUE);
    }

    /** Geometry only. Callbacks/shared state and actual phase admission remain separate, mandatory gates. */
    @ApiStatus.Internal
    public RegionPlayerSpatialPlan.Capture capturePlayerSpatialPlan(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
        return RegionPlayerSpatialPlan.capture(this, player, phase);
    }

    @Nullable
    RegionPlayerSpatialLease reservePlayerSpatial(RegionTickBatch parent, RegionOwnershipLeaseGroup effects, List<RegionPlayerSpatialPlan> plans) {
        if (plans.isEmpty()) return null;
        return this.canReservePlayerSpatial(parent, plans.getFirst().player()) ? RegionPlayerSpatialLease.acquire(this, parent, effects, plans) : null;
    }

    @Nullable
    RegionPlayerSpatialLease reserveBoundPlayerState(RegionTickBatch parent, RegionOwnershipLeaseGroup effects, List<RegionPlayerStatePlan> plans, boolean sameSealedPrefix) {
        if (plans.isEmpty() || !this.canReservePlayerSpatial(parent, plans.getFirst().player())) return null;
        return RegionPlayerSpatialLease.acquireBound(this, parent, effects, plans, sameSealedPrefix, false);
    }

    boolean canReservePlayerSpatial(RegionTickBatch parent, ServerPlayer player) {
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread() || !this.regionWorkAdmission.isOwner())
            throw new IllegalStateException("Spatial reservation requires original engine server admission");
        return this.activeTickBatches.contains(parent) && parent.isExecutingOnCurrentThread() && parent.isGroupsComplete()
                && !this.shutdown && !this.isLevelClosed(parent.level());
    }

    public Map<String, Object> playerTickDiagnostics(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Player diagnostics require a quiescent server command");
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("requested", this.playerTickPlanningRequested);
        values.put("persistent", this.workerPool.isPersistent());
        values.put("phases", this.playerTicks.snapshot());
        values.put("network", this.playerNetworkGate.snapshot());
        values.put("packets", this.playerPackets.snapshot());
        values.put("refusals", this.playerAdmissionRefusals());
        values.put("lastRefusal", Map.copyOf(this.playerAdmissionLastRefusal));
        values.put("admittedWithMods", this.playerTicksAdmittedWithMods.get());
        values.put("activeBatches", this.activeTickBatches.size());
        values.put("stateClaims", this.playerStateClaimCount());
        if (this.playerPrefixTiming != null) {
            Map<String, Object> timing = new java.util.LinkedHashMap<>();
            for (var phase : RegionPlayerTickCoordinator.Phase.values()) timing.put(phase.name(), this.playerPrefixTiming[phase.ordinal()].snapshot());
            values.put("prefixTiming", timing);
            values.put("saveTiming", Map.of("chunkSaveNanos", this.playerObservedChunkSaveNanos, "chunkFlushNanos", this.playerObservedChunkFlushNanos));
        }
        values.put("players", server.getPlayerList().getPlayers().stream().map(player -> {
            RegionState owner = this.regionFor(player);
            var region = this.regionAt(player.serverLevel(), player.blockPosition());
            return Map.of("uuid", player.getUUID().toString(), "name", player.getGameProfile().getName(),
                    "x", player.getX(), "y", player.getY(), "z", player.getZ(), "regionX", region.regionX(), "regionZ", region.regionZ(),
                    "ownerCurrent", owner != null && owner.coordinate().equals(region), "health", player.getHealth());
        }).toList());
        return values;
    }

    public RegionPlayerNetworkGate playerNetworkGate() {
        return this.playerNetworkGate;
    }

    void retirePlayerPackets(net.minecraft.network.Connection connection) {
        this.playerPackets.retire(connection);
    }

    RegionPlayerPacketRouter playerPackets() {
        return this.playerPackets;
    }

    /** One original PacketUtils queue position; later packets/continuations cannot overtake its join. */
    boolean tryRunPlayerPacket(net.minecraft.network.protocol.Packet<?> packet,
            net.minecraft.server.network.ServerGamePacketListenerImpl listener, Runnable originalHandle) {
        return this.playerPrefixCoordinator.tryRunPlayerPacket(packet, listener, originalHandle);
    }

    boolean tryRunPlayerChatCompletion(net.minecraft.server.network.ServerGamePacketListenerImpl listener, Runnable originalHandle) {
        return this.playerPrefixCoordinator.tryRunPlayerChatCompletion(listener, originalHandle);
    }

    boolean tryRunPlayerPayload(net.minecraft.network.protocol.Packet<?> packet,
            net.minecraft.server.network.ServerGamePacketListenerImpl listener,
            net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<?> declaration, Runnable original) {
        return this.playerPrefixCoordinator.tryRunPlayerPayload(packet, listener, declaration, original);
    }

    final @Nullable PlayerPrefixTiming[] playerPrefixTiming = createPlayerPrefixTiming();

    private long playerObservedChunkSaveNanos, playerObservedChunkFlushNanos;

    public boolean playerTimingEnabled() {
        return this.playerPrefixTiming != null;
    }

    public void recordPlayerConnectionTail(long elapsed, boolean listenerTail) {
        if (this.playerPrefixTiming == null) return;
        var phase = listenerTail ? RegionPlayerTickCoordinator.Phase.CONNECTION_BODY : RegionPlayerTickCoordinator.Phase.CONNECTION;
        this.playerPrefixTiming[phase.ordinal()].serverTailNanos += elapsed;
    }

    public void recordPlayerChunkSave(long elapsed, boolean flush) {
        if (flush) this.playerObservedChunkFlushNanos += elapsed;
        else this.playerObservedChunkSaveNanos += elapsed;
        LOGGER.info("Player timing chunk save flush={} elapsedNanos={}", flush, elapsed);
    }

    @Nullable
    private static PlayerPrefixTiming[] createPlayerPrefixTiming() {
        if (!Boolean.getBoolean("forgia.playerPrefixTiming")) return null;
        var phases = RegionPlayerTickCoordinator.Phase.values();
        var array = new PlayerPrefixTiming[phases.length];
        for (int i = 0; i < phases.length; i++) array[i] = new PlayerPrefixTiming();
        return array;
    }

    // Publisher-only totals. A measurement window differences the status snapshots; none of
    // these timers run in production unless explicitly enabled at JVM startup.
    static final class PlayerPrefixTiming {
        long waves, calls, groups, admissionNanos, leaseNanos, submitAndJoinNanos, workerBodyNanosSum, cleanupNanos;
        long workerWakeDelayNanos, validateLaneNanos, startGateWaitNanos, groupLoopNanos, barrierWakeNanos;
        long networkFreezeNanos, refusedBeforeCaptureNanos, serverTailNanos;
        long preflightCalls, preflightRefusals, preflightNanos;

        Map<String, Long> snapshot() {
            var map = new java.util.LinkedHashMap<String, Long>();
            map.put("waves", waves);
            map.put("calls", calls);
            map.put("groups", groups);
            map.put("admissionNanos", admissionNanos);
            map.put("leaseNanos", leaseNanos);
            map.put("submitAndJoinNanos", submitAndJoinNanos);
            map.put("workerBodyNanosSum", workerBodyNanosSum);
            map.put("cleanupNanos", cleanupNanos);
            map.put("workerWakeDelayNanos", workerWakeDelayNanos);
            map.put("validateLaneNanos", validateLaneNanos);
            map.put("startGateWaitNanos", startGateWaitNanos);
            map.put("groupLoopNanos", groupLoopNanos);
            map.put("barrierWakeNanos", barrierWakeNanos);
            map.put("networkFreezeNanos", networkFreezeNanos);
            map.put("refusedBeforeCaptureNanos", refusedBeforeCaptureNanos);
            map.put("preflightCalls", preflightCalls);
            map.put("preflightRefusals", preflightRefusals);
            map.put("preflightNanos", preflightNanos);
            map.put("serverTailNanos", serverTailNanos);
            return Map.copyOf(map);
        }
    }

    // Bound publisher work and the lifetime of the frozen network boundary. A lane can
    // complete several disjoint groups but never grants their permissions simultaneously.
    static final int PLAYER_PREFIX_LIMIT = 32;
    final java.util.HashSet<RegionCoordinate> isolatedRegionScratch = new java.util.HashSet<>(64);

    int dispatchPlayerPrefix(List<RegionPlayerTickDispatch.Call> ordered) {
        return this.playerPrefixCoordinator.dispatchPlayerPrefix(ordered);
    }

    int dispatchPlayerPrefix(List<RegionPlayerTickDispatch.Call> ordered, boolean skipIneligible) {
        return this.playerPrefixCoordinator.dispatchPlayerPrefix(ordered, skipIneligible);
    }

    public boolean tryRunPlayerConnectionBodyTick(ServerPlayer player, Runnable originalDoTick) {
        return this.playerPrefixCoordinator.tryRunPlayerConnectionBodyTick(player, originalDoTick);
    }

    int dispatchPlayerConnectionBodyPrefix(List<RegionPlayerTickDispatch.Call> calls) {
        return this.playerPrefixCoordinator.dispatchPlayerConnectionBodyPrefix(calls);
    }

    void tickPlayerWorldSequence(RegionEntityTickBatch parent, List<Entity> entities) {
        this.playerPrefixCoordinator.tickPlayerWorldSequence(parent, entities);
    }

    /** Same original connection traversal; results are consumed at each original try/catch position. */
    public final class PlayerConnectionPass implements AutoCloseable {
        private final List<net.minecraft.network.Connection> ordered;
        private final Map<net.minecraft.network.Connection, RegionPlayerTickDispatch.Call> completed = new java.util.IdentityHashMap<>();
        private int cursor;

        private PlayerConnectionPass(List<net.minecraft.network.Connection> ordered) {
            this.ordered = List.copyOf(ordered);
        }

        public boolean hasCompleted(net.minecraft.network.Connection connection) {
            var call = this.completed.get(connection);
            if (call == null) return false;
            // Only an already-started original invocation must be consumed after socket loss. An
            // unstarted call keeps the original traversal's current isConnected() eligibility and
            // disconnection/removal branch; a refused batch is not permission to tick a closed listener.
            if (!call.started) {
                this.completed.remove(connection);
                return false;
            }
            return true;
        }

        public boolean tick(net.minecraft.network.Connection connection) {
            var done = this.completed.remove(connection);
            if (done != null) {
                if (done.failure != null) throwUnchecked(done.failure);
                if (!done.started) tickPlayerSerial(done.player, connection, RegionPlayerTickCoordinator.Phase.CONNECTION, connection::tick);
                return true;
            }
            // Keep complete Connection.tick on the original server thread; ServerPlayer.doTick()
            // enters Region Worker via the controlled CONNECTION_BODY hook inside
            // ServerGamePacketListenerImpl.tick().
            return false;
        }

        @Override
        public void close() {
            // All cached calls physically joined before publication. An original memory-connection
            // crash may abort traversal; clearing inert results must not mask that original failure.
            this.completed.clear();
        }
    }

    public PlayerConnectionPass beginPlayerConnectionPass(List<net.minecraft.network.Connection> connections) {
        return new PlayerConnectionPass(connections);
    }

    public RegionPlayerStatePlan.Capture capturePlayerStatePlan(RegionPlayerSpatialPlan spatial) {
        return RegionPlayerStatePlan.capture(this, spatial);
    }

    @Nullable
    List<Object> claimPlayerState(RegionPlayerSpatialLease lease, List<RegionPlayerStatePlan> plans) {
        if (this.playerPrefixClaims.count() != 0) return null;
        if (plans.size() == 1) {
            // Duplicate references within one plan name the same owner. A table is only needed
            // when distinct players in a conflict group are allowed to share some resources.
            List<Object> claimed = plans.getFirst().resources();
            for (Object resource : claimed) {
                var held = this.playerStateClaims.get(resource);
                if (held != null && held != lease) return null;
            }
            for (Object resource : claimed) this.playerStateClaims.put(resource, lease);
            return claimed;
        }
        var staged = new java.util.IdentityHashMap<Object, RegionPlayerStatePlan>();
        for (var plan : plans) {
            for (Object object : plan.resources()) {
                RegionPlayerStatePlan previous = staged.putIfAbsent(object, plan);
                if (previous != null && previous.player() != plan.player() && (!previous.shares(object) || !plan.shares(object))) return null;
                RegionPlayerSpatialLease held = this.playerStateClaims.get(object);
                if (held != null && held != lease) return null;
            }
        }
        List<Object> claimed = List.copyOf(staged.keySet());
        for (Object object : claimed) this.playerStateClaims.put(object, lease);
        return claimed;
    }

    void releasePlayerState(RegionPlayerSpatialLease lease, List<Object> claimed) {
        for (Object object : claimed) this.playerStateClaims.remove(object, lease);
    }

    int playerStateClaimCount() {
        return this.playerStateClaims.size() + this.playerPrefixClaims.count();
    }

    void releasePlayerSpatialRegion(RegionState region) {
        this.releaseRegionLease(region);
    }

    /** Invalidate prepared work before same-level teleport/dismount/level-transition side effects. */
    @ApiStatus.Internal
    public void invalidatePlayerTickPlans(ServerPlayer player) {
        if (!this.playerTickPlanningRequested) return;
        if (!player.server.isSameThread()) throw new IllegalStateException("Player lifecycle handoff requires its server thread");
        this.playerTicks.invalidate(player);
        for (var phase : RegionPlayerTickCoordinator.Phase.values()) player.forgiaPlayerStateTemplate(phase.ordinal(), null);
        player.forgiaPlayerStateTemplate(RegionPlayerStatePlan.MOVEMENT_TEMPLATE, null);
    }

    private boolean playerTickLayoutSupported() {
        return this.layout.isDynamicCluster();
    }

    /** Storage prerequisites match the actual player phase dependencies; no slice is enabled here. */
    @ApiStatus.Internal
    public boolean playerCriterionStorageEnabled() {
        return this.playerTickPlanningRequested && !this.shutdown && this.playerTickLayoutSupported()
                && this.entityStorageMode == RegionEntityStorageMode.REGION_OWNED && this.entityTickWorkerExecutionEnabled;
    }

    @ApiStatus.Internal
    public boolean playerTickPlanningRequested() {
        return this.playerTickPlanningRequested;
    }

    /** Test construction seam; production selection is captured at startup. */
    @ApiStatus.Internal
    public void configurePlayerTickPlanningForTesting(boolean requested) {
        if (this.playerTicks.snapshot().pending() != 0) throw new IllegalStateException("Player invocation still pending");
        this.playerTickPlanningRequested = requested;
    }

    /** Complete original-thread connection calls; the world entrance keeps its body in ServerPlayer.tick. */
    @ApiStatus.Internal
    public void tickPlayerSerial(ServerPlayer player, net.minecraft.network.Connection connection,
            RegionPlayerTickCoordinator.Phase phase, Runnable original) {
        PlayerTickInvocation invocation = this.beginPlayerSerial(player, connection, phase);
        try {
            original.run();
        } catch (Throwable failure) {
            if (invocation != null) invocation.failed(failure);
            throw failure;
        } finally {
            if (invocation != null) invocation.close();
        }
    }

    @Nullable
    @ApiStatus.Internal
    public PlayerTickInvocation beginPlayerSerial(ServerPlayer player, net.minecraft.network.Connection connection,
            RegionPlayerTickCoordinator.Phase phase) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Player phase must enter on its original server thread");
        if (!this.playerTickPlanningRequested || this.shutdown) return null;
        if (!this.playerTickLayoutSupported() || this.entityStorageMode != RegionEntityStorageMode.REGION_OWNED || !this.entityTickWorkerExecutionEnabled) {
            this.playerTicks.recordUntrackedSerial(phase, RegionPlayerTickCoordinator.Reason.DEPENDENCIES);
            return null;
        }
        boolean entered = false;
        if (!this.regionWorkAdmission.isOwner()) {
            entered = this.regionWorkAdmission.tryEnter(this::isQuiescentForParallelApi);
            if (!entered) {
                this.playerTicks.recordUntrackedSerial(phase, RegionPlayerTickCoordinator.Reason.STALE);
                return null;
            }
        }
        RegionTickBatch parent = null;
        for (RegionTickBatch batch : this.activeTickBatches) {
            if (batch.level() == player.serverLevel() && batch.isExecutingOnCurrentThread() && batch.isGroupsComplete()) {
                parent = batch;
                break;
            }
        }
        PlayerTickInvocation invocation = new PlayerTickInvocation(this, entered);
        RegionPlayerTickCoordinator.Plan prepared = null;
        try {
            if (parent == null && !this.activeTickBatches.isEmpty()) {
                this.playerTicks.recordUntrackedSerial(phase, RegionPlayerTickCoordinator.Reason.STALE);
                invocation.close();
                return null;
            }
            if (parent == null) {
                PlayerSerialLifecycle lifecycle = new PlayerSerialLifecycle(player.serverLevel());
                if (this.publishTickBatch(lifecycle, player.serverLevel()) != TickBatchPublication.PUBLISHED) {
                    lifecycle.finish();
                    invocation.close();
                    return null;
                }
                invocation.lifecycle = lifecycle;
                lifecycle.markExecutingOnCurrentThread();
            }
            this.playerRegions.publishAtPlayerStage(this, player);
            prepared = this.playerTicks.prepare(player, connection, phase);
            if (prepared != null && this.playerTicks.admit(prepared) && this.playerTicks.start(prepared)) {
                invocation.plan = prepared;
            } else {
                this.playerTicks.recordUntrackedSerial(phase, RegionPlayerTickCoordinator.Reason.STALE);
            }
            return invocation;
        } catch (Throwable failure) {
            if (prepared != null && !prepared.started()) this.playerTicks.cancel(prepared);
            if (invocation.engine != null) {
                invocation.failed(failure);
                invocation.close();
            }
            throw failure;
        }
    }

    /** Tracks a full original method while leaving every original instruction and Mixin target in place. */
    @ApiStatus.Internal
    public static final class PlayerTickInvocation implements AutoCloseable {
        @Nullable
        private RegionThreadingEngine engine;
        @Nullable
        private Thread caller;
        @Nullable
        private PlayerSerialLifecycle lifecycle;
        @Nullable
        private RegionPlayerTickCoordinator.Plan plan;
        @Nullable
        private Throwable failure;
        private final boolean admissionEntered;

        private PlayerTickInvocation(RegionThreadingEngine engine, boolean admissionEntered) {
            this.engine = engine;
            this.caller = Thread.currentThread();
            this.admissionEntered = admissionEntered;
        }

        public void failed(Throwable failure) {
            if (this.engine == null || this.caller != Thread.currentThread()) throw new IllegalStateException("Foreign or closed player invocation");
            this.failure = failure;
        }

        @Override
        public void close() {
            RegionThreadingEngine current = this.engine;
            if (current == null) return;
            if (this.caller != Thread.currentThread()) throw new IllegalStateException("Player body has not returned on its caller");
            if (this.lifecycle != null && this.lifecycle.playerSpatialReservationCount() != 0)
                throw new IllegalStateException("Player child spatial lease has not physically returned and released");
            Throwable cleanup = null;
            try {
                if (this.plan != null) current.playerTicks.complete(this.plan, this.failure);
            } catch (Throwable failure) {
                cleanup = failure;
            }
            try {
                if (this.lifecycle != null) this.lifecycle.finish();
            } catch (Throwable failure) {
                cleanup = accumulateFailure(cleanup, failure);
            } finally {
                if (this.lifecycle != null) {
                    current.activeTickBatches.remove(this.lifecycle);
                    this.lifecycle.clearExecutingOnCurrentThread();
                }
            }
            try {
                if (this.admissionEntered) current.regionWorkAdmission.exit();
            } catch (Throwable failure) {
                cleanup = accumulateFailure(cleanup, failure);
            }
            Throwable original = this.failure;
            this.engine = null;
            this.caller = null;
            this.lifecycle = null;
            this.plan = null;
            this.failure = null;
            if (cleanup != null) {
                if (original != null) accumulateFailure(original, cleanup);
                else throwUnchecked(cleanup);
            }
        }
    }

    /** Uses the existing batch publication/join lifecycle, with no tasks, pool or world permission. */
    private static final class PlayerSerialLifecycle extends RegionTickBatch {
        PlayerSerialLifecycle(ServerLevel level) {
            super(level, RegionTickSlice.ENTITY_TICK, 0);
            this.completeExternalWaves();
        }
    }

    /** {@return true once the engine shut down and no region state may be published any more} */
    boolean isShutDown() {
        return this.shutdown;
    }

    /** {@return true when the level identity of {@code level} finished a level clear} */
    boolean isLevelClosed(ServerLevel level) {
        return this.regionizer.isLevelClosed(level);
    }

    /**
     * Records that {@code player} is about to change level, before the removal from its old level runs.
     * <p>
     * The region of a player is published at the start of the entity slice of the level the player is in, so this
     * records the cause of the transition the publication will see and counts a transition that happens while a
     * batch of the old level is in flight. The identity of the player is handed to the ledger of the destination
     * level here, so the destination publishes the same identity as a continuation instead of reserving it
     * again; see {@link EntityIdentityLedger#liveIdentity(Entity)}.
     * <p>
     * Runs on the thread that changes the dimension, which is the server thread of the level.
     */
    @ApiStatus.Internal
    public void recordPlayerDimensionTransition(ServerPlayer player, ResourceKey<Level> destination) {
        if (!(player.level() instanceof ServerLevel source)) {
            return;
        }
        this.invalidatePlayerTickPlans(player);
        this.playerRegions.noteDimensionChange(this, player);
        final MinecraftServer server = source.getServer();
        final ServerLevel destinationLevel = server == null ? null : server.getLevel(destination);
        if (destinationLevel == null || destinationLevel == source) {
            return;
        }
        final EntityIdentity identity = source.entityIdentityLedger().liveIdentity(player);
        if (identity != null) {
            destinationLevel.entityIdentityLedger().expectTransfer(identity);
            this.playerRegions.recordIdentityHandover();
        }
    }

    /**
     * {@return the audit of the player region invariant of {@code level}}
     * <p>
     * Every player of the level must be mapped into exactly one region of it. The audit counts its checks and its
     * failures in the diagnostics and reports the first problem, so a caller can assert on the result without
     * having to describe it again.
     */
    @ApiStatus.Internal
    public PlayerRegionAudit auditPlayerRegions(ServerLevel level) {
        return this.playerRegions.audit(this, level);
    }

    /**
     * Asserts the player region invariant of {@code level}.
     *
     * @throws IllegalStateException when a player of the level is in no region of it or in more than one
     */
    @ApiStatus.Internal
    public void assertPlayerRegions(ServerLevel level) {
        final PlayerRegionAudit audit = this.auditPlayerRegions(level);
        if (!audit.ok()) {
            throw new IllegalStateException("A player is not in exactly one region of its level: " + audit.firstProblem()
                    + " (players=" + audit.players() + ", unplaced=" + audit.unplaced() + ", duplicated=" + audit.duplicated() + ")");
        }
    }

    /**
     * Publishes {@code batch} as active, or rejects it when its level identity is already closed or the
     * engine was shut down.
     * <p>
     * The closed-level check, the shutdown check and the publication happen while the lifecycle read gate
     * is held, so they are atomic with {@link #clearLevelRegionTasks(ServerLevel)} and {@link #shutdown()}:
     * a cleanup either observes the published batch and waits for it, or the publication observes the
     * closed identity and rejects the batch. The read gate is released before any region lease is acquired,
     * because cleanup holds the write gate while it awaits published batches. A published batch therefore
     * has to finish leasing, tick its workers and run {@code removeIfEmpty} without the read gate; taking it
     * again would deadlock against the cleanup that waits for that same batch.
     *
     * @return the publication result; only {@link TickBatchPublication#PUBLISHED} means the caller owns the
     *         batch lifecycle from this point on
     */
    TickBatchPublication publishTickBatch(RegionTickBatch batch, ServerLevel level) {
        for (;;) {
            // Never wait for the admission boundary while holding the lifecycle read gate: a cleanup
            // may already own its write gate while it waits for the canonical phase to finish.
            this.regionWorkAdmission.awaitPublication();
            final Lock readLock = this.lifecycleGate.readLock();
            readLock.lock();
            try {
                synchronized (this.regionWorkAdmission) {
                    if (!this.regionWorkAdmission.allowsCurrentThread()) continue;
                    if (this.regionizer.isLevelClosed(level)) return TickBatchPublication.LEVEL_CLOSED;
                    if (this.shutdown) return TickBatchPublication.SHUTDOWN;
                    if (this.regionWorkAdmission.isOwner()) {
                        for (final var active : this.activeTickBatches) {
                            if (active.holdsServerAdmissionFence()) throw new IllegalStateException("Nested parallel batch inside a canonical server boundary");
                        }
                    }
                    if (this.layout.isDynamicCluster()) {
                        batch.useLoadedIslandDispatch(this.dynamicTopologies.get(level));
                    }
                    batch.enableCallerRuns(this.callerRunsEnabled || RegionThreadingConfig.callerRunsEnabled());
                    batch.attachCriticalPathMetrics(this.criticalPathMetrics);
                    this.activeTickBatches.add(batch);
                    return TickBatchPublication.PUBLISHED;
                }
            } finally {
                readLock.unlock();
            }
        }
    }

    /**
     * Returns {@code true} when {@link #overloadGovernor} is active ({@code DYNAMIC_CLUSTER} + enabled) and
     * decides to skip {@code region} on {@code level.getGameTime()}. All slices of {@code region} share a
     * single cached decision per tick.
     */
    boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region) {
        final RegionOverloadGovernor governor = this.overloadGovernor;
        if (governor == null) {
            return false;
        }
        boolean hasPlayers = false;
        if (!governor.config().throttlePlayerRegions()) {
            final RegionCoordinate target = region.coordinate();
            for (final ServerPlayer player : level.players()) {
                if (this.layout.regionAt(level, player.blockPosition()).equals(target)) {
                    hasPlayers = true;
                    break;
                }
            }
        }
        return governor.shouldSkip(level, region, hasPlayers);
    }

    @SuppressWarnings("unchecked")
    private <T> boolean rescheduleThrottledScheduledTick(ServerLevel level, ScheduledTickPhase phase, ScheduledTick<T> tick, long targetGameTime) {
        final long triggerTick = Math.max(level.getGameTime() + 1L, targetGameTime);
        final ScheduledTick<T> rescheduled = new ScheduledTick<>(tick.type(), tick.pos(), triggerTick, tick.priority(), tick.subTickOrder());
        if (phase == ScheduledTickPhase.BLOCK) {
            final var blockTicks = level.getBlockTicks();
            if (blockTicks == null) return false;
            ((net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.block.Block>) blockTicks).schedule((ScheduledTick<net.minecraft.world.level.block.Block>) rescheduled);
            return true;
        }
        final var fluidTicks = level.getFluidTicks();
        if (fluidTicks == null) return false;
        ((net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.material.Fluid>) fluidTicks).schedule((ScheduledTick<net.minecraft.world.level.material.Fluid>) rescheduled);
        return true;
    }

    @ApiStatus.Internal
    public List<RegionSliceCriticalPathMetrics.SliceSnapshot> criticalPathSnapshots() {
        return this.criticalPathMetrics.snapshots();
    }

    @ApiStatus.Internal
    public RegionFenceMetrics.Snapshot fenceMetricsSnapshot(int limit) {
        return this.fenceMetrics.snapshot(limit);
    }

    @ApiStatus.Internal
    public boolean isOverloadGovernorActive() {
        return this.overloadGovernor != null;
    }

    @ApiStatus.Internal
    public RegionOverloadGovernor.Config overloadGovernorConfig() {
        return this.overloadConfig;
    }

    @ApiStatus.Internal
    public List<RegionOverloadState.Snapshot> overloadRegionSnapshots() {
        final List<RegionOverloadState.Snapshot> snapshots = new ArrayList<>();
        for (final RegionState region : this.regionizer.regions()) {
            final RegionOverloadState state = region.overloadStateOrNull();
            if (state != null && (state.status() == RegionOverloadState.Status.THROTTLED || state.skippedTicks() > 0L)) {
                snapshots.add(state.snapshot(region.coordinate().toString()));
            }
        }
        snapshots.sort(Comparator.comparing((RegionOverloadState.Snapshot s) -> s.status() == RegionOverloadState.Status.THROTTLED ? 0 : 1)
                .thenComparing(Comparator.comparingLong(RegionOverloadState.Snapshot::lastTotalCostNanos).reversed())
                .thenComparing(RegionOverloadState.Snapshot::regionId));
        return snapshots;
    }

    @ApiStatus.Internal
    public boolean isFusedSlicesEnabled() {
        return this.fusedSlicesEnabled || RegionThreadingConfig.fusedSlicesEnabled();
    }

    @ApiStatus.Internal
    public FusedRegionTickSession.Snapshot fusedSliceSnapshot() {
        return this.fusedSession.snapshot();
    }

    @ApiStatus.Internal
    public List<RegionMainThreadPhaseMetrics.PhaseSnapshot> mainThreadPhaseSnapshots() {
        return this.mainThreadPhaseMetrics.snapshots();
    }

    public void recordMainThreadPhase(RegionMainThreadPhaseMetrics.Phase phase, long nanos) {
        this.mainThreadPhaseMetrics.record(phase, nanos);
    }

    @ApiStatus.Internal
    public String auditedBlockEntityCoreStatus() {
        return AuditedBlockEntityPreparation.coreStatus();
    }

    @ApiStatus.Internal
    public RegionItemCleanupManager.Snapshot itemCleanupSnapshot(net.minecraft.server.MinecraftServer server) {
        return this.itemCleanupManager.snapshot(server);
    }

    @ApiStatus.Internal
    public RegionItemCleanupManager.SweepResult sweepItemsNow(net.minecraft.server.MinecraftServer server, int minAgeSeconds) {
        return this.itemCleanupManager.sweepNow(server, this.regionizer.regions(), minAgeSeconds);
    }

    /**
     * Ticks {@code accepted} entities on their owning region workers and waits for the batch.
     * <p>
     * Runs on the server thread: while the workers tick, the server thread only services chunk source
     * tasks, so off thread chunk requests issued by entity code cannot deadlock against the barrier.
     * <p>
     * The batch is published under the lifecycle read gate before any region lease is acquired, so a
     * concurrent shutdown or level unload observes it and waits instead of retiring regions that the batch
     * still holds or is about to lease. The gate is released before leasing: the cleanup waits for the
     * published batch, so the batch must be able to acquire leases and retire its regions without taking
     * the gate again. Region ownership is re-resolved from the regionizer after the snapshot, so a region
     * that merged or split between collection and lease acquisition is replaced by its authoritative
     * successor instead of dropping the entity. An entity whose region cannot be leased safely is ticked on
     * the server thread after the batch rather than being dropped.
     */
    void tickEntities(ServerLevel level, List<Entity> accepted, RegionTickBarrier barrier) {
        this.entityTickCoordinator.tickEntities(level, accepted, barrier);
    }

    /**
     * {@return true when the {@link #ENTITY_TICK_CHUNK_MARGIN} neighborhood of the chunk at
     * {@code chunkX, chunkZ} is loaded}
     * <p>
     * {@code neighborhoodLoaded} memoizes the answer per chunk of the batch (absent, not loaded, loaded), so
     * the entities of one chunk ask once instead of once per entity, and {@code chunkLoaded} memoizes the
     * chunk source answer per chunk, so a chunk shared by the neighborhoods of two adjacent chunks is looked
     * up once. Nothing is force-loaded: a chunk that the chunk source does not already hold makes its
     * neighborhood unloaded.
     */
    static boolean isEntityTickNeighborhoodLoaded(ServerLevel level, int chunkX, int chunkZ, Long2ByteMap chunkLoaded, Long2ByteMap neighborhoodLoaded) {
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final byte cached = neighborhoodLoaded.get(key);
        if (cached != NEIGHBORHOOD_UNKNOWN) {
            return cached == NEIGHBORHOOD_LOADED;
        }
        boolean loaded = true;
        for (int offsetX = -ENTITY_TICK_CHUNK_MARGIN; offsetX <= ENTITY_TICK_CHUNK_MARGIN && loaded; offsetX++) {
            for (int offsetZ = -ENTITY_TICK_CHUNK_MARGIN; offsetZ <= ENTITY_TICK_CHUNK_MARGIN; offsetZ++) {
                if (!isChunkLoaded(level, chunkX + offsetX, chunkZ + offsetZ, chunkLoaded)) {
                    loaded = false;
                    break;
                }
            }
        }
        neighborhoodLoaded.put(key, loaded ? NEIGHBORHOOD_LOADED : NEIGHBORHOOD_NOT_LOADED);
        return loaded;
    }

    /**
     * {@return true when the chunk source can hand out the {@code FULL} chunk at {@code chunkX, chunkZ}
     * without loading one}
     * <p>
     * {@code chunkLoaded} memoizes the answer per chunk of the batch.
     */
    static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ, Long2ByteMap chunkLoaded) {
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final byte cached = chunkLoaded.get(key);
        if (cached != NEIGHBORHOOD_UNKNOWN) {
            return cached == NEIGHBORHOOD_LOADED;
        }
        // Asked at most once per chunk and loaded-chunk generation, so the chunk source round trip of
        // getChunkNow is not on the per-tick path of the neighborhood.
        final boolean loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        chunkLoaded.put(key, loaded ? NEIGHBORHOOD_LOADED : NEIGHBORHOOD_NOT_LOADED);
        return loaded;
    }

    /**
     * Takes the region lease for a whole entity batch.
     * <p>
     * When a worker still leases the region for queued region work, the server thread waits for that work
     * while servicing chunk source tasks, so queued region tasks can never interleave with an entity batch
     * of the same region. The wait has no timeout: letting the server thread continue would let it mutate
     * state that the leasing worker is still touching. The wait always ends in a lease or in the region
     * becoming dead; a dead region is re-resolved by the caller, never waited on again.
     */
    boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier) {
        if (region.isDead()) {
            return false;
        }
        if (region.tryMarkRunning()) {
            return true;
        }
        final AtomicBoolean acquired = new AtomicBoolean();
        final ServerLevel level = region.coordinate().level();
        final long waitStart = System.nanoTime();
        final long[] nextWarnAt = { waitStart + 5_000_000_000L };
        barrier.await(() -> {
            this.serviceMainThreadQueuesForAsyncWait(level);
            if (region.isDead()) {
                return true;
            }
            acquired.set(region.tryMarkRunning());
            if (acquired.get()) {
                return true;
            }
            final long now = System.nanoTime();
            if (now >= nextWarnAt[0]) {
                nextWarnAt[0] = now + 5_000_000_000L;
                LOGGER.warn("Waiting {} ms to acquire region lease for {} (running={}, submitted={}, dead={})",
                        (now - waitStart) / 1_000_000L,
                        region.coordinate(),
                        region.isRunning(),
                        region.isWorkerSubmitted(),
                        region.isDead());
            }
            return false;
        });
        return acquired.get();
    }

    boolean acquireRegionLeaseBounded(RegionState region, RegionTickBarrier barrier, long timeoutNanos) {
        if (region.isDead()) {
            return false;
        }
        if (region.tryMarkRunning()) {
            return true;
        }
        final AtomicBoolean acquired = new AtomicBoolean();
        final ServerLevel level = region.coordinate().level();
        final long deadline = System.nanoTime() + Math.max(0L, timeoutNanos);
        barrier.await(() -> {
            this.serviceMainThreadQueuesForAsyncWait(level);
            if (region.isDead()) {
                return true;
            }
            if (region.tryMarkRunning()) {
                acquired.set(true);
                return true;
            }
            return System.nanoTime() >= deadline;
        });
        return acquired.get();
    }

    /**
     * Registers the worker group of a freshly leased region and releases the lease again when the group
     * cannot be built.
     * <p>
     * Group creation runs batch code (for example the creation of the per-group random source), so a failure
     * between {@link #acquireRegionLease} and {@link RegionTickBatch#addTask} must not leave the region
     * {@code TICKING}: shutdown and level unload would wait for a lease no worker owns anymore.
     */
    void addLeasedGroup(RegionState region, Runnable registration) {
        try {
            registration.run();
        } catch (Throwable throwable) {
            this.releaseRegionLease(region);
            throw throwable;
        }
    }

    /**
     * Releases a lease this thread took and never handed to a worker.
     * <p>
     * Retiring an empty region is part of the release: the lease is what kept the region alive, and a region that
     * is retired after its last group is the same cleanup a finished group runs.
     */
    void releaseRegionLease(RegionState region) {
        region.clearRunning();
        final ServerLevel level = region.coordinate().level();
        final RegionMailbox mailbox = level != null ? this.mailboxes.get(level) : null;
        if (mailbox != null && mailbox.hasPendingMessages(region.coordinate())) {
            return;
        }
        this.regionizer.removeIfEmpty(region);
    }

    /**
     * {@return the packs of one slice's regions, each in the canonical lease order}
     * <p>
     * With packing enabled the regions are split into balanced packs of about {@code regionCount / workerCount}
     * regions; with it disabled every pack holds one region, which is the unpacked submission of one task per
     * region.
     *
     * @see RegionLeasePack#split(List, int)
     */
    List<List<RegionState>> regionPacks(Collection<RegionState> regions) {
        if (!this.packedRegionLeasesEnabled || this.layout.isDynamicCluster()) {
            // The unpacked path keeps the order in which the regions were first seen, exactly like before
            // packing existed: a pack is the only thing that needs a canonical order.
            final List<List<RegionState>> single = new ArrayList<>(regions.size());
            for (final RegionState region : regions) {
                single.add(List.of(region));
            }
            return single;
        }
        return RegionLeasePack.split(RegionLeasePack.canonicalOrder(regions), this.workerPool.threadCount());
    }

    /** Cumulative fallback dispatches, not cache-build decisions or measured ticker-body time. */
    public Map<String, Long> blockEntityFallbackAdmissionCounts() {
        return Map.of("typeNotDeclared", this.blockEntityFallbackUndeclared.get(),
                "boundaryChunk", this.blockEntityFallbackBoundary.get(),
                "safeInMixedChunk", this.blockEntityFallbackMixedSafe.get(),
                "unloadedNeighborhood", this.blockEntityFallbackUnloadedNeighborhood.get(),
                "untrackedNeighborhood", this.blockEntityFallbackUntrackedNeighborhood.get());
    }

    /** Opt-in shadow observation. The resulting plan never grants additional worker ownership. */
    public RegionBlockEntityWorkGraph blockEntityWorkGraph() {
        return this.blockEntityWorkGraph;
    }

    public boolean furnaceWorkWavesEnabled() {
        return this.furnaceWorkWavesEnabled;
    }

    public boolean blockEntityResourcesEnabled() {
        return this.blockEntityResourcesEnabled;
    }

    public BlockEntityResourceMetrics.Snapshot blockEntityResourceMetrics() {
        return this.blockEntityResourceMetrics.snapshot();
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void setBlockEntityResourcesEnabledForTesting(boolean enabled) {
        this.blockEntityResourcesEnabled = enabled;
    }

    public FurnaceWorkMetrics.Snapshot furnaceWorkMetrics() {
        return this.furnaceWorkMetrics.snapshot();
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void setFurnaceWorkWavesEnabledForTesting(boolean enabled) {
        this.furnaceWorkWavesEnabled = enabled;
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void setFurnaceBodyObserverForTesting(@Nullable java.util.function.Consumer<net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity> observer) {
        this.furnaceBodyObserverForTesting = observer;
        FurnaceWorkScope.mirrorThreadLocalForTesting = observer != null;
    }

    /** Successfully returned boundary ticker invocations on native workers; executor rejection is excluded. */
    public long boundaryBlockEntityTicksOnWorkers() {
        return this.boundaryBlockEntityTicksOnWorkers.get();
    }

    /** Records a chunk the plan did not hand over because it is not block-ticking this tick. */
    void countNonTickingBlockEntityChunk() {
        this.nonTickingBlockEntityChunks.incrementAndGet();
    }

    /** Records a chunk the plan did not hand over because its entities are still loading. */
    void countEntitiesLoadingBlockEntityChunk() {
        this.entitiesLoadingBlockEntityChunks.incrementAndGet();
    }

    /** Records a chunk the plan did not hand over because it is no longer loaded. */
    void countMissingBlockEntityChunk() {
        this.missingBlockEntityChunks.incrementAndGet();
    }

    /**
     * Ticks {@code chunks} on their owning region workers and waits for the batch.
     * <p>
     * Runs on the server thread: while the workers tick, the server thread only services chunk source
     * tasks, so off thread chunk requests issued by block-entity code cannot deadlock against the barrier.
     * <p>
     * The batch is published under the lifecycle read gate before any region lease is acquired, so a
     * concurrent shutdown or level unload observes it and waits instead of retiring regions that the batch
     * still holds or is about to lease. The gate is released before leasing: the cleanup waits for the
     * published batch, so the batch must be able to acquire leases and retire its regions without taking
     * the gate again. Region ownership is re-resolved from the regionizer after the snapshot, so a region
     * that merged or split between collection and lease acquisition is replaced by its authoritative
     * successor instead of dropping the chunk. Chunks whose region cannot be leased safely, and chunks whose
     * admission snapshot went stale before the worker ticked them, are ticked on the server thread after the
     * batch rather than being dropped.
     * <p>
     * The two server-owned effects a worker group defers are applied here, after the workers finished and
     * before the batch is released: the removals of the entities an owned ticker removed, and the scheduled
     * ticks its neighbor updates started. The fallback ticks run between them, so a ticker the server thread
     * ticks after the batch observes the removals of the batch it belongs to.
     */
    void tickBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier) {
        this.blockEntityTickCoordinator.tickBlockEntities(level, chunks, barrier);
    }

    void tickBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier, boolean canonicalSequence) {
        this.blockEntityTickCoordinator.tickBlockEntities(level, chunks, barrier, canonicalSequence);
    }

    public void setBlockEntityBodyObserverForTesting(@Nullable java.util.function.BiConsumer<TickingBlockEntity, Runnable> observer) {
        this.blockEntityTickCoordinator.setBlockEntityBodyObserverForTesting(observer);
    }

    RegionBlockEntityTickAdmission narrowCanonicalBlockEntity(RegionBlockEntityTickAdmission admission) {
        return this.blockEntityTickCoordinator.narrowCanonicalBlockEntity(admission);
    }

    void runInBlockEntityFallbackPhase(BlockEntityFallbackPhase phase, BlockPos pos, Runnable task) {
        this.blockEntityTickCoordinator.runInBlockEntityFallbackPhase(phase, pos, task);
    }

    RegionState modHome(ServerLevel level, BlockPos position) {
        return this.blockEntityTickCoordinator.modHome(level, position);
    }

    boolean modServerAccessAllowed(ServerLevel level, BlockPos position) {
        return this.blockEntityTickCoordinator.modServerAccessAllowed(level, position);
    }

    /**
     * Executes the scheduled ticks of one phase that {@code LevelTicks} selected for this tick.
     * <p>
     * The collection, budget, de-duplication and per-tick bookkeeping already ran on the server thread;
     * this method only distributes the callbacks. The batch is published under the lifecycle read gate
     * before any region lease is acquired, so a concurrent shutdown or level unload observes it and waits
     * instead of retiring regions that the batch still holds or is about to lease. Region ownership is
     * re-resolved after the snapshot, so a region that merged or split between collection and lease
     * acquisition is replaced by its authoritative successor instead of dropping the tick. Ticks whose
     * region cannot be leased safely run on the server thread after the batch rather than being dropped.
     * <p>
     * The server thread waits for the whole phase before it applies the mutations the callbacks
     * requested, so a worker never mutates the scheduling structures and a tick scheduled by a callback
     * is never executed early by this batch. The one exception is the per-tick hook: every tick is reported
     * to {@code beginTick} immediately before its callback runs, on the worker that runs it, so
     * {@code LevelTicks.willTickThisTick} answers for a tick of this phase that has not run yet exactly as the
     * inline loop does. Ticks whose region could not be leased run through
     * {@code fallbackCallback} directly on the server thread after the workers finished; the callback
     * must not hand them to a region queue because {@code LevelTicks} already removed them.
     */
    <T> void tickScheduledTicks(ServerLevel level, ScheduledTickPhase phase, List<ScheduledTick<T>> ticks, BiConsumer<BlockPos, T> callback,
            BiConsumer<BlockPos, T> fallbackCallback, Consumer<ScheduledTick<T>> beginTick, RegionTickBarrier barrier) {
        this.scheduledTickCoordinator.executeAndAwait(level, phase, ticks, callback, fallbackCallback, beginTick, barrier);
    }

    /** Narrow bridge to the original runtime; the coordinator owns no independent execution authority. */
    private final class ScheduledTickHost implements RegionScheduledTickCoordinator.Host {
        @Override
        public int blockUpdateEntryBudget() {
            return RegionThreadingEngine.this.deferredBlockUpdateBudget;
        }

        @Override
        public boolean unclassifiedWorkerCallbacksForTesting() {
            return RegionThreadingEngine.this.unclassifiedWorkerCallbacksForTesting;
        }

        @Override
        public RegionScheduledTickMetrics metrics(ScheduledTickPhase phase) {
            return RegionThreadingEngine.this.scheduledTickMetrics[phase.ordinal()];
        }

        @Override
        public TickBatchPublication publish(RegionScheduledTickBatch<?> batch) {
            return RegionThreadingEngine.this.publishTickBatch(batch, batch.level());
        }

        @Override
        public boolean isLevelClosed(ServerLevel level) {
            return RegionThreadingEngine.this.regionizer.isLevelClosed(level);
        }

        @Override
        @Nullable
        public RegionState regionAt(ServerLevel level, BlockPos pos) {
            return RegionThreadingEngine.this.regionizer.getOrCreate(RegionThreadingEngine.this.layout.regionAt(level, pos));
        }

        @Override
        public int maxCatchUpScheduledTicks() {
            return RegionThreadingEngine.this.overloadGovernor != null
                    ? RegionThreadingEngine.this.overloadGovernor.config().maxCatchUpScheduledTicks()
                    : 0;
        }

        @Override
        public boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region) {
            return RegionThreadingEngine.this.shouldSkipOverloadedRegion(level, region);
        }

        @Override
        public <T> boolean rescheduleThrottledScheduledTick(ServerLevel level, ScheduledTickPhase phase, ScheduledTick<T> tick, long targetGameTime) {
            return RegionThreadingEngine.this.rescheduleThrottledScheduledTick(level, phase, tick, targetGameTime);
        }

        @Override
        public boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier) {
            return RegionThreadingEngine.this.acquireRegionLease(region, barrier);
        }

        @Override
        public void addLeasedGroup(RegionState region, Runnable registration) {
            RegionThreadingEngine.this.addLeasedGroup(region, registration);
        }

        @Override
        public Runnable withRegionMailboxDelivery(ServerLevel level, RegionState region, Runnable body) {
            return RegionThreadingEngine.this.withRegionMailboxDelivery(level, region, body);
        }

        @Override
        public void submitAndAwait(RegionScheduledTickBatch<?> batch, RegionTickBarrier barrier) {
            RegionThreadingEngine.this.runRegionBatch(batch.level(), () -> batch.submitAndAwait(RegionThreadingEngine.this.workerPool, barrier));
        }

        @Override
        public void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable body) {
            RegionThreadingEngine.this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), body);
        }

        @Override
        public void setCurrentScheduledTickRandom(@Nullable RandomSource random) {
            if (random == null) {
                RegionThreadingEngine.this.currentScheduledTickRandom.remove();
            } else {
                RegionThreadingEngine.this.currentScheduledTickRandom.set(random);
            }
        }

        @Override
        public void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations) {
            if (mutations == null) {
                RegionThreadingEngine.this.currentRegionTickMutations.remove();
            } else {
                RegionThreadingEngine.this.currentRegionTickMutations.set(mutations);
            }
        }

        @Override
        public void setCurrentPropagationOrigin(@Nullable BlockPos pos) {
            if (pos == null) {
                RegionThreadingEngine.this.currentPropagationOrigin.remove();
            } else {
                RegionThreadingEngine.this.currentPropagationOrigin.set(pos);
            }
        }

        @Override
        public void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, Throwable throwable) {
            RegionThreadingEngine.this.recordWorkerTickFailure(batch, coordinate, null, throwable);
        }

        @Override
        public void removeIfEmpty(RegionState region) {
            RegionThreadingEngine.this.regionizer.removeIfEmpty(region);
        }

        @Override
        public void countSkippedTicks(int count) {
            RegionThreadingEngine.this.skippedScheduledTicks.addAndGet(count);
        }

        @Override
        public void countFallbackTick() {
            RegionThreadingEngine.this.fallbackScheduledTicks.incrementAndGet();
        }

        @Override
        public void countTicksOnWorkers(int count) {
            RegionThreadingEngine.this.scheduledTicksOnWorkers.addAndGet(count);
        }

        @Override
        public void countRefusedDeferredBlockUpdates(int count) {
            RegionThreadingEngine.this.refusedDeferredBlockUpdates[RegionTickSlice.SCHEDULED_TICK.ordinal()].addAndGet(count);
        }

        @Override
        public void applyDeferredBlockUpdates(RegionTickBatch batch) {
            RegionThreadingEngine.this.applyDeferredBlockUpdates(batch);
        }

        @Override
        public void applyRegionTickMutations(Queue<Runnable> mutations) {
            RegionThreadingEngine.this.applyRegionTickMutations(mutations);
        }

        @Override
        public void retireBatch(RegionTickBatch batch) {
            RegionThreadingEngine.this.activeTickBatches.remove(batch);
        }

        @Override
        public RegionLayout layout() {
            return RegionThreadingEngine.this.layout;
        }

        @Override
        public Set<net.minecraft.world.level.material.Fluid> demotedFluids() {
            return RegionThreadingEngine.this.demotedFluidTypes;
        }

        @Override
        public void demoteFluid(net.minecraft.world.level.material.Fluid fluid, Throwable failure) {
            if (RegionThreadingEngine.this.demotedFluidTypes.add(fluid)) {
                final var key = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
                final String fluidId = key != null ? key.toString() : fluid.getClass().getName();
                final String reason = "fluid_worker_error:" + failure.getClass().getSimpleName();
                net.neoforged.neoforge.server.threading.api.RegionAdapterApi.denyRegionSandboxedFluid(fluidId);
                net.neoforged.neoforge.server.threading.RegionThreadingDeclarations.persistLearnedFluidDemotion(fluidId, reason);
                LOGGER.warn("[RegionFluidSandbox] Auto-demoted fluid {} to server thread after worker exception: {}",
                        fluidId, failure.toString());
            }
        }

        @Override
        public boolean asyncFluidTicksEnabled() {
            return RegionThreadingEngine.this.isIndependentRegionTickEnabled()
                    && RegionThreadingConfig.asyncRegionFluidTicksEnabled();
        }

        @Override
        public boolean isIndependentRegionTickEnabled() {
            return RegionThreadingEngine.this.isIndependentRegionTickEnabled();
        }

        @Override
        public boolean isRegionInFlight(RegionCoordinate coordinate) {
            return RegionThreadingEngine.this.independentTickScheduler != null
                    && RegionThreadingEngine.this.independentTickScheduler.isRegionInFlight(coordinate);
        }

        @Override
        public boolean submitAsyncFluidGroup(RegionState region, RegionScheduledTickBatch<?> regionBatch, Runnable wrappedWork, Runnable serverThreadCommit) {
            final ServerLevel level = region.coordinate().level();
            if (level != null) {
                regionBatch.setAsyncMainThreadWorkSink(task -> RegionThreadingEngine.this.independentTickScheduler.enqueueCommit(level, task));
            }
            return RegionThreadingEngine.this.independentTickScheduler.submitAsyncSlice(
                    region, "async fluid", wrappedWork, serverThreadCommit) == RegionIndependentTickScheduler.DispatchOutcome.SUBMITTED;
        }
    }

    /**
     * Applies every mutation a worker requested on either {@code LevelTicks} instance of the batch.
     * <p>
     * Runs on the server thread after all workers finished. Each mutation is applied in request order and
     * one failing mutation does not strand the ones behind it.
     */
    void applyRegionTickMutations(Queue<Runnable> mutations) {
        Throwable failure = null;
        Runnable mutation;
        while ((mutation = mutations.poll()) != null) {
            try {
                mutation.run();
            } catch (Throwable throwable) {
                failure = accumulateFailure(failure, throwable);
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Ticks the chunks of one level tick on the region workers and waits for the batch.
     * <p>
     * Runs on the server thread after the level's own chunk loop decided which chunks tick: natural spawning,
     * the inhabited time update, the custom spawners and the per-chunk broadcast already ran there. The batch is
     * published under the lifecycle read gate before any region lease is acquired, so a concurrent shutdown or
     * level unload observes it and waits instead of retiring regions that the batch still holds or is about to
     * lease. Region ownership is re-resolved after the collection, so a region that merged or split between the
     * decision and the lease acquisition is replaced by its authoritative successor instead of dropping the
     * chunk. Chunks whose region cannot be leased safely run on the server thread after the batch rather than
     * being dropped.
     * <p>
     * The server thread waits for the whole batch before the level broadcast and the rest of the level tick
     * continue. The deferred effects of the workers are applied in this order: the section publications of the
     * moves, the removals, the admissions, the light rechecks and block updates of the block writes, the lightning
     * strikes the workers rolled, the chunks that could not be leased, and finally the {@code LevelTicks}
     * mutations, so a random tick never mutates a server-owned structure from a worker.
     */
    void tickChunks(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed, RegionTickBarrier barrier) {
        if ((this.fusedSlicesEnabled
                || RegionThreadingConfig.fusedSlicesEnabled()
                || this.unifiedTickCoordinator.isUnifiedLoopActive(level))
                && this.entityTickWorkerExecutionEnabled
                && this.entityStorageMode == RegionEntityStorageMode.REGION_OWNED
                && !chunks.isEmpty()
                && !this.shutdown
                && !this.regionizer.isLevelClosed(level)) {
            this.fusedSession.stageChunks(level, chunks, randomTickSpeed, barrier);
            return;
        }
        this.chunkTickCoordinator.executeAndAwait(level, chunks, randomTickSpeed, barrier);
    }

    void flushStagedFusedChunks(ServerLevel level) {
        final FusedRegionTickSession.PendingChunkWork pending = this.fusedSession.takeStagedChunks(level);
        if (pending != null) {
            this.fusedSession.recordEarlyFlush();
            this.chunkTickCoordinator.executeAndAwait(level, pending.chunks(), pending.randomTickSpeed(), pending.barrier());
        }
        this.unifiedTickCoordinator.flushStagedWork(level);
    }

    /** Narrow bridge to the original runtime; the coordinator owns no independent execution authority. */
    private final class ChunkTickHost implements RegionChunkTickCoordinator.Host {
        @Override
        public boolean isShutdown() {
            return RegionThreadingEngine.this.shutdown;
        }

        @Override
        public boolean isLevelClosed(ServerLevel level) {
            return RegionThreadingEngine.this.regionizer.isLevelClosed(level);
        }

        @Override
        public boolean isIndependentRegionTickEnabled() {
            return RegionThreadingEngine.this.isIndependentRegionTickEnabled();
        }

        @Override
        @Nullable
        public RegionIndependentTickScheduler independentTickScheduler() {
            return RegionThreadingEngine.this.independentTickScheduler;
        }

        @Override
        public RegionLayout layout() {
            return RegionThreadingEngine.this.layout;
        }

        @Override
        public int blockUpdateEntryBudget() {
            return RegionThreadingEngine.this.deferredBlockUpdateBudget;
        }

        @Override
        @Nullable
        public RegionState getOrCreateRegion(RegionCoordinate coordinate) {
            return RegionThreadingEngine.this.regionizer.getOrCreate(coordinate);
        }

        @Override
        public boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region) {
            return RegionThreadingEngine.this.shouldSkipOverloadedRegion(level, region);
        }

        @Override
        public TickBatchPublication publish(RegionChunkTickBatch batch) {
            return RegionThreadingEngine.this.publishTickBatch(batch, batch.level());
        }

        @Override
        public void countSkippedChunks(int count) {
            RegionThreadingEngine.this.skippedChunkTicks.addAndGet(count);
        }

        @Override
        public void countUnleasedChunkTick() {
            RegionThreadingEngine.this.unleasedChunkTicks.incrementAndGet();
        }

        @Override
        public void countChunkTicksOnWorkers(int count) {
            RegionThreadingEngine.this.chunkTicksOnWorkers.addAndGet(count);
        }

        @Override
        public void countTruncatedChunkTick() {
            RegionThreadingEngine.this.truncatedChunkTicks.incrementAndGet();
        }

        @Override
        public boolean markChunkTickWorkersNoticeLogged() {
            return RegionThreadingEngine.this.chunkTickWorkersNoticeLogged.compareAndSet(false, true);
        }

        @Override
        public void tickOnServerThread(ServerLevel level, RegionChunkTickEntry chunk, int randomTickSpeed) {
            RegionThreadingEngine.this.tickChunkOnServerThread(level, chunk, randomTickSpeed);
        }

        @Override
        public boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier) {
            return RegionThreadingEngine.this.acquireRegionLease(region, barrier);
        }

        @Override
        public void addLeasedGroup(RegionState region, Runnable registration) {
            RegionThreadingEngine.this.addLeasedGroup(region, registration);
        }

        @Override
        public Runnable withRegionMailboxDelivery(ServerLevel level, RegionState region, Runnable body) {
            return RegionThreadingEngine.this.withRegionMailboxDelivery(level, region, body);
        }

        @Override
        public void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable action) {
            RegionThreadingEngine.this.runWithContext(
                    new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), action);
        }

        @Override
        public void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations) {
            if (mutations == null) {
                RegionThreadingEngine.this.currentRegionTickMutations.remove();
            } else {
                RegionThreadingEngine.this.currentRegionTickMutations.set(mutations);
            }
        }

        @Override
        public void setCurrentChunkTickRandom(@Nullable RandomSource random) {
            if (random == null) {
                RegionThreadingEngine.this.currentChunkTickRandom.remove();
            } else {
                RegionThreadingEngine.this.currentChunkTickRandom.set(random);
            }
        }

        @Override
        public void setCurrentChunkTickPositionRandom(@Nullable RandomSource random) {
            if (random == null) {
                RegionThreadingEngine.this.currentChunkTickPositionRandom.remove();
            } else {
                RegionThreadingEngine.this.currentChunkTickPositionRandom.set(random);
            }
        }

        @Override
        public void setCurrentChunkTickLightning(@Nullable Queue<BlockPos> strikes) {
            if (strikes == null) {
                RegionThreadingEngine.this.currentChunkTickLightning.remove();
            } else {
                RegionThreadingEngine.this.currentChunkTickLightning.set(strikes);
            }
        }

        @Override
        public void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, Throwable throwable) {
            RegionThreadingEngine.this.recordWorkerTickFailure(batch, coordinate, null, throwable);
        }

        @Override
        public void removeIfEmpty(RegionState region) {
            RegionThreadingEngine.this.regionizer.removeIfEmpty(region);
        }

        @Override
        public void applyAsyncCommitEffects(RegionChunkTickBatch batch) {
            RegionThreadingEngine.this.applyDeferredChunkTickSectionUpdates(batch);
            RegionThreadingEngine.this.applyDeferredChunkTickRemovals(batch);
            RegionThreadingEngine.this.applyDeferredChunkTickAdditions(batch);
            RegionThreadingEngine.this.applyDeferredBlockUpdates(batch);
            RegionThreadingEngine.this.applyChunkTickLightning(batch);
            RegionThreadingEngine.this.tickFallbackChunkTicks(batch);
            RegionThreadingEngine.this.applyRegionTickMutations(batch.regionTickMutations());
        }

        @Override
        public void submitAndAwait(RegionChunkTickBatch batch, RegionTickBarrier barrier) {
            RegionThreadingEngine.this.runRegionBatch(batch.level(), () -> batch.submitAndAwait(RegionThreadingEngine.this.workerPool, barrier));
        }

        @Override
        @Nullable
        public Throwable complete(RegionChunkTickBatch batch, @Nullable Throwable failure) {
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.applyDeferredChunkTickSectionUpdates(batch));
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.applyDeferredChunkTickRemovals(batch));
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.applyDeferredChunkTickAdditions(batch));
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.applyDeferredBlockUpdates(batch));
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.applyChunkTickLightning(batch));
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.tickFallbackChunkTicks(batch));
            failure = runBatchCompletion(failure, () -> RegionThreadingEngine.this.applyRegionTickMutations(batch.regionTickMutations()));
            batch.finish();
            RegionThreadingEngine.this.activeTickBatches.remove(batch);
            batch.clearExecutingOnCurrentThread();
            return failure;
        }
    }

    /** Narrow bridge for {@link RegionSpawnCoordinator}. */
    private final class SpawnHost implements RegionSpawnCoordinator.Host {
        @Override
        public boolean isShutdown() {
            return RegionThreadingEngine.this.shutdown;
        }

        @Override
        public boolean isLevelClosed(ServerLevel level) {
            return RegionThreadingEngine.this.regionizer.isLevelClosed(level);
        }

        @Override
        public boolean isIndependentRegionTickEnabled() {
            return RegionThreadingEngine.this.isIndependentRegionTickEnabled();
        }

        @Override
        @Nullable
        public RegionIndependentTickScheduler independentTickScheduler() {
            return RegionThreadingEngine.this.independentTickScheduler;
        }

        @Override
        public RegionLayout layout() {
            return RegionThreadingEngine.this.layout;
        }

        @Override
        public RegionWorkerPool workerPool() {
            return RegionThreadingEngine.this.workerPool;
        }

        @Override
        public int deferredBlockUpdateBudget() {
            return RegionThreadingEngine.this.deferredBlockUpdateBudget;
        }

        @Override
        @Nullable
        public RegionState getOrCreateRegion(RegionCoordinate coordinate) {
            return RegionThreadingEngine.this.regionizer.getOrCreate(coordinate);
        }

        @Override
        public boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region) {
            return RegionThreadingEngine.this.shouldSkipOverloadedRegion(level, region);
        }

        @Override
        public TickBatchPublication publishTickBatch(RegionTickBatch batch, ServerLevel level) {
            return RegionThreadingEngine.this.publishTickBatch(batch, level);
        }

        @Override
        public void retireTickBatch(RegionTickBatch batch) {
            RegionThreadingEngine.this.activeTickBatches.remove(batch);
        }

        @Override
        public boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier) {
            return RegionThreadingEngine.this.acquireRegionLease(region, barrier);
        }

        @Override
        public void addLeasedGroup(RegionState region, Runnable registration) {
            RegionThreadingEngine.this.addLeasedGroup(region, registration);
        }

        @Override
        public void runRegionBatch(ServerLevel level, Runnable action) {
            RegionThreadingEngine.this.runRegionBatch(level, action);
        }

        @Override
        public void deliverRegionMessages(ServerLevel level, RegionState region, Runnable after) {
            RegionThreadingEngine.this.deliverRegionMessages(level, region, after);
        }

        @Override
        public void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable action) {
            RegionThreadingEngine.this.runWithContext(
                    new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), action);
        }

        @Override
        public void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations) {
            if (mutations == null) {
                RegionThreadingEngine.this.currentRegionTickMutations.remove();
            } else {
                RegionThreadingEngine.this.currentRegionTickMutations.set(mutations);
            }
        }

        @Override
        public void setCurrentSpawnRandom(@Nullable RandomSource random) {
            if (random == null) {
                RegionThreadingEngine.this.currentSpawnRandom.remove();
            } else {
                RegionThreadingEngine.this.currentSpawnRandom.set(random);
            }
        }

        @Override
        public void setCurrentSpawnNeighborhood(@Nullable RegionSpawnNeighborhood neighborhood) {
            if (neighborhood == null) {
                RegionThreadingEngine.this.currentSpawnNeighborhood.remove();
            } else {
                RegionThreadingEngine.this.currentSpawnNeighborhood.set(neighborhood);
            }
        }

        @Override
        public void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, Throwable throwable) {
            RegionThreadingEngine.this.recordWorkerTickFailure(batch, coordinate, null, throwable);
        }

        @Override
        public void removeIfEmpty(RegionState region) {
            RegionThreadingEngine.this.regionizer.removeIfEmpty(region);
        }

        @Override
        public void countSkippedSpawnChunks(int count) {
            RegionThreadingEngine.this.skippedSpawnChunks.addAndGet(count);
        }

        @Override
        public void countUnleasedSpawnChunk() {
            RegionThreadingEngine.this.unleasedSpawnChunks.incrementAndGet();
        }

        @Override
        public void countRefusedSpawnBlockUpdate() {
            RegionThreadingEngine.this.countRefusedDeferredBlockUpdate(RegionTickSlice.SPAWN);
        }

        @Override
        public void countSpawnChunksOnWorkers(int count) {
            RegionThreadingEngine.this.spawnChunksOnWorkers.addAndGet(count);
        }

        @Override
        public void countTruncatedSpawnChunks(int count) {
            RegionThreadingEngine.this.truncatedSpawnChunks.addAndGet(count);
        }

        @Override
        public boolean markSpawnWorkersNoticeLogged() {
            return RegionThreadingEngine.this.spawnWorkersNoticeLogged.compareAndSet(false, true);
        }

        @Override
        public void applyDeferredSpawnRemovals(RegionSpawnBatch batch) {
            RegionThreadingEngine.this.applyDeferredSpawnRemovals(batch);
        }

        @Override
        public void applyDeferredSpawnAdditions(RegionSpawnBatch batch) {
            RegionThreadingEngine.this.applyDeferredSpawnAdditions(batch);
        }

        @Override
        public void applyDeferredBlockUpdates(RegionTickBatch batch) {
            RegionThreadingEngine.this.applyDeferredBlockUpdates(batch);
        }

        @Override
        public void applyRegionTickMutations(Queue<Runnable> mutations) {
            RegionThreadingEngine.this.applyRegionTickMutations(mutations);
        }

        @Override
        public void recordOrderedSpawnTransaction(long serviceNanos) {
            RegionThreadingEngine.this.orderedSpawnTransactions.incrementAndGet();
            RegionThreadingEngine.this.orderedSpawnServiceNanos.addAndGet(serviceNanos);
        }
    }

    /**
     * Spawns for {@code chunks} on the workers of their regions and waits for the batch.
     * Delegated to {@link RegionSpawnCoordinator}.
     */
    void spawnChunks(ServerLevel level, List<RegionSpawnChunk> chunks, RegionSpawnPlan plan, RegionTickBarrier barrier) {
        this.spawnCoordinator.spawnChunks(level, chunks, plan, barrier);
    }

    private void applyDeferredSpawnAdditions(RegionSpawnBatch batch) {
        this.applyDeferredAdditions(batch.level(), batch.drainDeferredAdditions(), batch.isAdditionSeamClosed());
    }

    private void applyDeferredSpawnRemovals(RegionSpawnBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * Ticks the chunks whose region could not be leased safely on the server thread after the batch.
     * <p>
     * The chunks were decided and collected on the server thread, so they must not go through the generic region
     * handoff again: that could defer them to a later region tick or drop them on a closed level. A chunk whose
     * level identity closed while the batch ran is counted as skipped, exactly like the collected chunks of a
     * closed level.
     */
    private void tickFallbackChunkTicks(RegionChunkTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final RegionChunkTickEntry chunk : batch.fallbackChunks()) {
            this.tickChunkOnServerThread(level, chunk, batch.randomTickSpeed());
        }
    }

    /**
     * Ticks one collected chunk on the server thread.
     * <p>
     * The chunk draws from the same per-chunk random source it would have drawn from on a worker, so a chunk
     * that falls back produces the same selection as a chunk that is dispatched, and a retried chunk never
     * depends on which thread ticked it.
     */
    /**
     * Ticks one chunk inline on the server thread with the chunk's own random source.
     * <p>
     * A chunk that a plan refuses before its body has to select exactly the positions it would select on a
     * worker, so the selection never depends on the dispatch decision or on the thread. The caller owns the
     * refusal counter, because the reason differs per call site.
     */
    void tickChunkInline(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        final RandomSource random = RegionChunkTickEntry.of(chunk).random(level);
        this.currentChunkTickRandom.set(random);
        this.currentChunkTickPositionRandom.set(random);
        try {
            level.tickChunk(chunk, randomTickSpeed);
        } finally {
            this.currentChunkTickPositionRandom.remove();
            this.currentChunkTickRandom.remove();
        }
    }

    void tickChunkOnServerThread(ServerLevel level, RegionChunkTickEntry chunk, int randomTickSpeed) {
        if (this.regionizer.isLevelClosed(level)) {
            this.skippedChunkTicks.incrementAndGet();
            return;
        }
        this.fallbackChunkTicks.incrementAndGet();
        final RandomSource random = chunk.random(level);
        this.currentChunkTickRandom.set(random);
        this.currentChunkTickPositionRandom.set(random);
        try {
            level.tickChunk(chunk.chunk(), randomTickSpeed);
        } finally {
            this.currentChunkTickPositionRandom.remove();
            this.currentChunkTickRandom.remove();
        }
    }

    /**
     * Applies the light rechecks and block update publications the worker groups of {@code batch} deferred, in the
     * order the batch bodies recorded them, and attributes them to the slice of the batch.
     * <p>
     * Both effects are level-wide, not partitioned per region: the light engine of a level owns one set of queues
     * and one light thread, and the block update publication invalidates the level-wide path type cache and
     * recomputes the paths of every navigating mob of the level. A worker of any slice therefore only recorded
     * them, and this method runs the vanilla calls on the server thread, after the barrier of the batch that
     * recorded them.
     * <p>
     * A level that closed while the batch ran drops the records instead of publishing block updates for a level
     * that no longer exists; every dropped record is counted. One failing record does not strand the ones behind
     * it, so the queue is always empty when this method returns.
     */
    void applyDeferredBlockUpdates(RegionTickBatch batch) {
        final RegionDeferredBlockUpdates updates = batch.blockUpdates();
        if (updates.isEmpty()) {
            return;
        }
        final int slice = batch.slice().ordinal();
        final ServerLevel level = batch.level();
        if (this.regionizer.isLevelClosed(level)) {
            this.droppedDeferredBlockUpdates[slice].addAndGet(updates.discard());
            return;
        }
        final ServerChunkCache chunkSource = level.getChunkSource();
        final Throwable[] failure = new Throwable[1];
        final int applied = updates.drain(update -> {
            try {
                switch (update) {
                    case RegionDeferredBlockUpdates.LightSectionStatus status -> chunkSource.getLightEngine()
                            .updateSectionStatus(status.pos(), status.empty());
                    case RegionDeferredBlockUpdates.LightCheck check -> chunkSource.getLightEngine().checkBlock(check.pos());
                    case RegionDeferredBlockUpdates.BlockUpdate blockUpdate -> level.sendBlockUpdated(blockUpdate.pos(),
                            blockUpdate.oldState(), blockUpdate.newState(), blockUpdate.flags());
                }
            } catch (Throwable throwable) {
                failure[0] = accumulateFailure(failure[0], throwable);
            }
        });
        this.appliedDeferredBlockUpdates[slice].addAndGet(applied);
        if (failure[0] != null) {
            throwUnchecked(failure[0]);
        }
    }

    /**
     * Resolves the lightning strikes the chunk tick workers rolled and applies them on the server thread.
     * <p>
     * A worker may not resolve a strike itself: the target comes from the POI manager (a lightning rod within
     * 128 blocks) and from entity storage, and it can be in another region, so the strike is applied here after
     * the barrier with the level random, exactly like the vanilla thunder body. A strike whose level closed
     * while the batch ran is dropped instead of applying after the unload.
     */
    private void applyChunkTickLightning(RegionChunkTickBatch batch) {
        final Queue<BlockPos> strikes = batch.lightningStrikes();
        if (strikes.isEmpty()) {
            return;
        }
        final ServerLevel level = batch.level();
        if (this.shutdown || this.regionizer.isLevelClosed(level)) {
            strikes.clear();
            return;
        }
        BlockPos rolled;
        while ((rolled = strikes.poll()) != null) {
            level.tickLightning(rolled);
            this.chunkTickLightningStrikes.incrementAndGet();
        }
    }

    /**
     * {@return true when every chunk around {@code chunkPos} is loaded}
     * <p>
     * A random tick, an entity tick or a spawn body of the chunk can read or write the eight neighboring chunks,
     * and a worker may not load a chunk: the chunk source refuses the request instead of waiting for the server
     * thread that waits for the worker. A chunk with an unloaded neighbor therefore keeps the server-thread body.
     */
    boolean isLoadedChunkNeighborhood(ServerLevel level, ChunkPos chunkPos) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                if (!chunkSource.hasChunk(chunkPos.x + offsetX, chunkPos.z + offsetZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Counts one chunk of a boundary chunk tick that the plan handed to the region batch.
     * <p>
     * A chunk is a boundary chunk when a chunk of its eight-neighbour neighbourhood belongs to another region.
     * Such a chunk is dispatched since a write of its body that leaves the region is recorded as a border block
     * write and applied by the target region in the same tick, so the count is the share of the level's chunk
     * ticks the relaxed rule moved off the server thread. It is not a fallback and does not add to
     * {@link #fallbackChunkTicks}.
     */
    void countBoundaryChunkTick() {
        this.boundaryChunkTicks.incrementAndGet();
    }

    /** Counts one chunk the plan kept on the server thread because a neighboring chunk is not loaded. */
    void countUnloadedNeighborhoodChunkTick() {
        this.unloadedNeighborhoodChunkTicks.incrementAndGet();
        this.fallbackChunkTicks.incrementAndGet();
    }

    /** Counts one chunk collected after its batch already ran, so the server thread ticked it inline. */
    void countLateChunkTick() {
        this.lateChunkTicks.incrementAndGet();
        this.fallbackChunkTicks.incrementAndGet();
    }

    /**
     * Counts one chunk the plan kept on the server thread because the batch had already admitted its whole
     * deferred update budget.
     */
    void countDeferredUpdateBudgetChunkTick() {
        this.deferredUpdateBudgetChunkTicks.incrementAndGet();
        this.fallbackChunkTicks.incrementAndGet();
        this.countRefusedDeferredBlockUpdate(RegionTickSlice.CHUNK_TICK);
    }

    /**
     * Counts one unit of {@code slice} that a batch refused before its body because it could not reserve the
     * block-write effects of the unit; the unit ran on the server thread instead.
     */
    void countRefusedDeferredBlockUpdate(RegionTickSlice slice) {
        this.refusedDeferredBlockUpdates[slice.ordinal()].incrementAndGet();
    }

    /** {@return one counter per {@link RegionTickSlice}, indexed by the ordinal of the slice} */
    private static AtomicLong[] newSliceCounters() {
        final AtomicLong[] counters = new AtomicLong[RegionTickSlice.values().length];
        for (int index = 0; index < counters.length; ++index) {
            counters[index] = new AtomicLong();
        }
        return counters;
    }

    /** The reason token of the declaration a chunk-truncated entity tick produces. */
    public static final String CHUNK_UNAVAILABLE_ENTITY_REASON = "chunk_unavailable";
    public static final String BOUNDARY_VIOLATION_ENTITY_REASON = "worker_boundary_violation";
    public static final String BOUNDARY_VIOLATION_BLOCK_ENTITY_REASON = "worker_boundary_violation";

    public long boundaryFallbackEntityTickCount() {
        return this.boundaryFallbackEntityTicks.get();
    }

    public long boundaryFallbackBlockEntityTickCount() {
        return this.boundaryFallbackBlockEntityTicks.get();
    }

    /**
     * Counts one tick failure of a worker group of {@code batch} and records it for the server thread.
     * <p>
     * Called on the worker thread that caught the failure, right before the group stops. The count is per slice,
     * so the diagnostics separate a failing entity tick from a failing block-entity, chunk, scheduled or spawn
     * body, and it counts every failure including the ones past the bound of the record. The record itself
     * carries the context and is reported by {@link RegionTickBatch#reportTickFailures()} on the server thread.
     */
    void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, @Nullable Entity entity, Throwable throwable) {
        this.workerTickFailures[batch.slice().ordinal()].incrementAndGet();
        batch.recordTickFailure(coordinate, entity, throwable);
    }

    /**
     * Truncates one entity tick that a worker stopped because the chunk source refused a chunk it needed.
     * <p>
     * The read is legal vanilla - {@code Level#getChunk} loads - and impossible on a worker, because a load
     * publishes chunk storage the server thread owns. Vanilla answers it by throwing at the tick boundary, which
     * for an entity means {@code ReportedException: Ticking entity} and a stopped server. Truncating keeps the
     * worker's guarantee (nothing is replayed) and makes the loss explicit: the tick is counted, the class is
     * declared server-thread-only so the next tick of that class runs where the read is legal, and the worker
     * keeps ticking the rest of its group instead of stopping at this entity.
     * <p>
     * Only a chunk refusal is truncated. Every other failure keeps the fail-closed behaviour: the group stops
     * and the server thread reports it exactly like the vanilla entity pass does.
     *
     * @return {@code true} when the failure was a chunk refusal and was truncated, {@code false} when the caller
     *         must treat it as an ordinary failure
     */
    boolean truncateIfChunkUnavailable(Entity entity, Throwable throwable) {
        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
            return false;
        }
        this.truncatedEntityTicks.incrementAndGet();
        // The declaration covers the runtime class and every subclass of it, and it invalidates the memoized
        // policy resolutions, so the next tick of this class is admitted to the server thread.
        this.entityTickPolicy.serverThreadOnly(entity.getClass(), CHUNK_UNAVAILABLE_ENTITY_REASON);
        if (this.truncatedEntityTickClasses.add(entity.getClass())) {
            LOGGER.warn("Region worker truncated the tick of {}: the chunk it needs is not loaded ({}). "
                    + "Ticks of this class now run on the server thread.",
                    entity.getClass().getName(), throwable.getMessage());
        }
        return true;
    }

    /**
     * Generic safety-net fallback when a worker entity tick crosses a server-owned storage or player-local
     * state guard. Truncates the current tick and dynamically demotes the entity's runtime class to the
     * server thread so subsequent ticks run legally without crashing the server.
     */
    boolean truncateIfBoundaryViolation(Entity entity, Throwable throwable) {
        final RegionWorkerBoundaryException violation = RegionWorkerBoundaryException.findBoundaryViolation(throwable);
        if (violation == null) {
            return false;
        }
        this.boundaryFallbackEntityTicks.incrementAndGet();
        this.entityTickPolicy.serverThreadOnly(entity.getClass(), BOUNDARY_VIOLATION_ENTITY_REASON);
        if (this.boundaryFallbackEntityTickClasses.add(entity.getClass())) {
            LOGGER.warn("Region worker truncated the tick of {} due to worker boundary guard [{}]: {}. "
                    + "Ticks of this class now fall back to the server thread.",
                    entity.getClass().getName(), violation.category(), violation.getMessage());
        }
        return true;
    }

    /**
     * Generic safety-net fallback when a worker block-entity tick crosses a server-owned storage, capability,
     * or global-state guard. Truncates the current tick and dynamically demotes the block-entity type to the
     * server thread so subsequent ticks run legally without crashing the server.
     */
    void demoteSandboxedBlockEntityOnError(RegionBlockEntityTickAdmission admission, Throwable throwable) {
        final Throwable root = (throwable instanceof RegionBlockEntityTickFailure && throwable.getCause() != null)
                ? throwable.getCause()
                : throwable;
        if (RegionWorkerChunkUnavailableException.isChunkUnavailable(root)
                || this.truncateIfBlockEntityBoundaryViolation(admission, root)) {
            return;
        }
        this.boundaryFallbackBlockEntityTicks.incrementAndGet();
        final String type = admission.type();
        final String reason = "sandbox_runtime_error:" + root.getClass().getSimpleName();
        if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isExplicitlySandboxedBlockEntity(type)) {
            final boolean transientCooldown = net.neoforged.neoforge.server.threading.api.RegionAdapterApi.recordTransientBoundaryFallback(
                    admission.level(), admission.position(), type, RegionThreadingConfig.sandboxTransientCooldownTicks());
            final var ticker = admission.ticker();
            net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(admission.level(), () -> {
                if (!ticker.isRemoved()) {
                    ticker.tick();
                }
            });
            SandboxDemotionProbe.record(admission.level(), admission.position(), type, reason, !transientCooldown, root);
            return;
        }
        SandboxDemotionProbe.record(admission.level(), admission.position(), type, reason, true, root);
        RegionTickCompatibility.declareServerThreadOnlyType(type, reason);
        if (this.boundaryFallbackBlockEntityTypes.add(type)) {
            net.neoforged.neoforge.server.threading.RegionThreadingDeclarations.persistLearnedBlockEntityDemotion(type, reason);
            LOGGER.warn("Region sandbox caught runtime error in block entity {} at {} ({}); demoted this type to server thread without crashing.",
                    type, admission.position(), root.toString());
        }
    }

    boolean truncateIfBlockEntityBoundaryViolation(RegionBlockEntityTickAdmission admission, Throwable throwable) {
        final RegionWorkerBoundaryException violation = RegionWorkerBoundaryException.findBoundaryViolation(throwable);
        if (violation == null) {
            return false;
        }
        this.boundaryFallbackBlockEntityTicks.incrementAndGet();
        final String type = admission.type();
        if (RegionThreadingConfig.regionSandboxAllBlockEntitiesEnabled()) {
            final boolean transientCooldown = net.neoforged.neoforge.server.threading.api.RegionAdapterApi.recordTransientBoundaryFallback(
                    admission.level(), admission.position(), type, RegionThreadingConfig.sandboxTransientCooldownTicks());
            final var ticker = admission.ticker();
            net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(admission.level(), () -> {
                if (!ticker.isRemoved()) {
                    ticker.tick();
                }
            });
            if (transientCooldown) {
                return true;
            }
            // The specific coordinate admission.position() has now reached the permanent threshold and is
            // pinned to the server thread (Long.MAX_VALUE cooldown). Only escalate to a server-wide type
            // demotion if the boundary violation is a global/player state violation rather than a spatial
            // neighbor/chunk/capability boundary at this block position.
            final String category = violation.category();
            final boolean spatialOrInstanceSpecific = "storage_access".equals(category)
                    || "capability_access".equals(category)
                    || "network_broadcast".equals(category)
                    || "minecraft:hopper".equals(type)
                    || net.neoforged.neoforge.server.threading.api.RegionAdapterApi.hasSandboxTypePredicate(type);
            if (spatialOrInstanceSpecific) {
                SandboxDemotionProbe.record(admission.level(), admission.position(), type, category, true, throwable);
                return true;
            }
        }
        SandboxDemotionProbe.record(admission.level(), admission.position(), type, violation.category(), true, throwable);
        RegionTickCompatibility.declareServerThreadOnlyType(type, BOUNDARY_VIOLATION_BLOCK_ENTITY_REASON);
        if (this.boundaryFallbackBlockEntityTypes.add(type)) {
            net.neoforged.neoforge.server.threading.RegionThreadingDeclarations.persistLearnedBlockEntityDemotion(type, BOUNDARY_VIOLATION_BLOCK_ENTITY_REASON);
            LOGGER.warn("Region worker truncated the tick of block entity {} at {} due to repeated worker boundary guard [{}]: {}. "
                    + "Ticks of this type now fall back to the server thread.",
                    type, admission.position(), violation.category(), violation.getMessage());
        }
        return true;
    }

    /** Counts one chunk whose vanilla spawn gate refused it, so vanilla spawns nothing for it either. */
    void countGateRefusedSpawnChunk() {
        this.gateRefusedSpawnChunks.incrementAndGet();
    }

    /** Counts one chunk kept on the server thread because a neighboring chunk is not loaded. */
    void countUnloadedNeighborhoodSpawnChunk() {
        this.unloadedNeighborhoodSpawnChunks.incrementAndGet();
    }

    /**
     * Counts one chunk kept on the server thread because the batch had already admitted its whole deferred
     * update budget, so the block writes of its spawn body would have had nowhere to be recorded.
     */
    void countDeferredUpdateBudgetSpawnChunk() {
        this.deferredUpdateBudgetSpawnChunks.incrementAndGet();
        this.countRefusedDeferredBlockUpdate(RegionTickSlice.SPAWN);
    }

    /** Counts one chunk collected after its batch already ran, so the server thread spawned for it inline. */
    void countLateSpawnChunk() {
        this.lateSpawnChunks.incrementAndGet();
    }

    /**
     * Counts one mob category a spawn state refused because the level-wide mob cap was reached.
     * <p>
     * Called from the vanilla spawn loop of a spawn worker through the region threading hook. On the server
     * thread - the inline path of a refused chunk - nothing is counted: that path is the vanilla body, which the
     * vanilla diagnostics already report.
     */
    public void countSpawnCapRefusal() {
        if (this.currentSpawnNeighborhood.get() != null) {
            this.capRefusedSpawns.incrementAndGet();
        }
    }

    /**
     * Counts one spawn attempt that could not be recorded as a deferred admission, so no entity was created.
     * <p>
     * A spawn worker records every admission it makes for the barrier; an attempt that the batch refuses (its
     * seam is closed, the entity is already in the level or the lease is gone) reaches the storage guard, which
     * refuses it. The counter makes the lost spawn visible next to the admission counters of the barrier.
     */
    public void countRefusedSpawnAddition() {
        this.refusedSpawnAdditions.incrementAndGet();
    }

    /**
     * {@return the number of chunks one spawn batch may admit to its deferred update budget}
     * <p>
     * The plan asks before it collects a chunk, so a chunk whose spawn body would have no room for its block
     * writes keeps the server-thread body instead.
     */
    int spawnDeferredUpdateBudget() {
        return this.deferredBlockUpdateBudget / RegionTickSlice.SPAWN.reservationPerUnit();
    }

    /**
     * {@return the vanilla spawn-gate answer for {@code pos} of {@code level} in the spawn body executing on the
     * current thread, or {@code null} when the caller has to ask the level itself}
     * <p>
     * The vanilla body asks {@code ServerLevel.isNaturalSpawningAllowed} for a candidate position outside the
     * chunk that spawns. That read goes through the entity manager's visibility map, which only the server thread
     * may touch, so a spawn worker answers from the snapshot the plan recorded for its region instead.
     */
    @Nullable
    public Boolean spawnPositionAllowed(ServerLevel level, BlockPos pos) {
        final RegionSpawnNeighborhood neighborhood = this.currentSpawnNeighborhood.get();
        if (neighborhood == null) {
            return null;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext) || basicContext.level().orElse(null) != level) {
            return null;
        }
        return neighborhood.isSpawnAllowed(ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
    }

    /** {@return true when the configured natural spawning slice actually dispatches chunks} */
    @ApiStatus.Internal
    public boolean isSpawnWorkerDispatchEffective() {
        return this.spawnWorkerExecutionEnabled;
    }

    /** Observes the original pool and lease-packing counters without retaining pool authority in the collector. */
    private RegionThreadingDiagnostics.WorkerDispatchCounters workerDispatchCounters() {
        return RegionDiagnosticsCollector.workerDispatch(this.workerPool.dispatchSnapshot(),
                this.packedTasks.get(), this.packedRegions.get(), this.packedLeaseFallbacks.get(),
                this.workerPool.isPersistent(), this.packedRegionLeasesEnabled);
    }

    static void throwUnchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Region tick batch failed", throwable);
    }

    /**
     * Runs {@code task} while the current thread owns the region containing {@code pos}.
     * <p>
     * Work for the position's own region runs inline. When the current thread holds no region lease (the
     * server tick thread and the level tick bridge), the tick takes the lease of the owning region and runs
     * under it when the region is free; a region that a worker currently owns is deferred to its queue so
     * the tick runs in the next tick of that region. When the current thread already holds a region lease,
     * the tick is always deferred: taking a second region's lease from inside a region tick would nest
     * leases and let a cross-region tick run while another region is still being ticked.
     * <p>
     * Inside a block event phase the task runs under the lease the phase already holds for the region, and the
     * phase takes that lease once however many events it runs in the region; see
     * {@link #beginBlockEventPhase(ServerLevel)}.
     */
    @ApiStatus.Internal
    public void runInBlockRegion(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.regionWorkAdmission.isOwner()
                && (this.activeCanonicalPureSerial || BlockEntityExecutionEngine.isCanonicalSerialInvocation())) {
            // This is the complete original server ticker inside a server-wide participating boundary.
            // A cleanup may hold the lifecycle write gate awaiting this very phase: never reacquire it,
            // queue half a ticker for later, or borrow a coarse/fine Worker grant for the serial body.
            task.run();
            return;
        }
        final BlockEntityFallbackPhase fallback = this.blockEntityFallbackPhase.get();
        if (fallback != null && fallback.level == level) {
            this.runInBlockEntityFallbackPhase(fallback, pos, task);
            return;
        }
        final BlockEventPhase phase = this.blockEventPhase.get();
        if (phase != null && phase.level == level) {
            if (this.fusedSession.hasStagedChunks(level) || this.unifiedTickCoordinator.hasStagedWork(level)) {
                this.blockEventPhase.remove();
                try {
                    this.flushStagedFusedChunks(level);
                } finally {
                    this.blockEventPhase.set(phase);
                }
            }
            this.runInBlockEventPhase(phase, level, pos, task);
            return;
        }
        if (this.isOwnedByCurrentRegion(level, pos)) {
            task.run();
            return;
        }
        if (this.shutdown) {
            // The regionizer is closed; tick like vanilla instead of dropping the work into a queue that
            // no longer exists. The server thread owns the tick at this point.
            task.run();
            return;
        }
        final RegionThreadingContext.Kind kind = this.currentContext().kind();
        if (kind == RegionThreadingContext.Kind.REGION || kind == RegionThreadingContext.Kind.GLOBAL_REGION) {
            this.enqueueRegionTask(level, pos, task);
            return;
        }
        if (!this.runBlockTaskUnderLease(level, pos, task)) {
            this.enqueueRegionTask(level, pos, task);
        }
    }

    /**
     * Opens the block event phase of {@code level} as a quiescent server-thread scope.
     * <p>
     * The vanilla block event phase drains the level's ordered event set on the server thread after every region
     * batch of the level tick has returned: the chunk tick batch, the block-entity batch and every fallback after
     * them. No worker runs and no region is leased by another thread, so the phase is the one part of the tick in
     * which the whole level is quiescent. Inside that scope a region lease exists only to establish the region
     * context an event body runs in - the ownership checks, the block-entity read gate and the neighbour updater
     * scope - and the phase keeps it until the phase ends instead of taking and releasing one per event.
     * <p>
     * Because no worker can observe the level, the scope also does not capture border updates
     * ({@link #capturesBorderUpdate}): a write to another region is the vanilla inline call, exactly what the
     * event body would do inline. Nothing is queued for a fixpoint, so a phase needs no fixpoint at all, and the
     * body reads back what it wrote instead of the state before the write.
     * <p>
     * The scope is only opened when the phase really is quiescent: the engine must be running, the calling thread
     * must not be a region worker, and no batch of the engine may be in flight. A batch in flight is what the
     * inline write has to rule out - it is the only state in which a worker holds or can take a region lease -
     * and it also covers a thread that is executing a region tick, because a region tick runs inside a batch. The
     * global region is not part of the check: it is the server-global task queue, it holds no region lease, and a
     * drain of it can be in flight at any point of the server tick. When the check fails the phase runs the
     * per-event lease and the border capture of every other server-thread region task, which is the correct but
     * slower path.
     *
     * @return {@code true} when the phase runs as a scope, {@code false} when it keeps the per-event path
     */
    @ApiStatus.Internal
    public boolean beginBlockEventPhase(ServerLevel level) {
        if (this.shutdown) {
            return false;
        }
        final BlockEventPhase open = this.blockEventPhase.get();
        if (open != null) {
            if (open.level == level) {
                open.openings++;
            }
            return false;
        }
        if (RegionWorkerScope.isNativeWorkerThread()
                || this.workerPool.isWorkerThread(Thread.currentThread())
                || !this.activeTickBatches.isEmpty()) {
            return false;
        }
        this.blockEventPhase.set(new BlockEventPhase(level));
        return true;
    }

    /**
     * Closes the block event phase opened by {@link #beginBlockEventPhase(ServerLevel)} and releases every region
     * lease the phase took, in the reverse order it took them.
     * <p>
     * The release runs whether the phase completed or threw, so no lease survives an event that failed. A phase
     * that was not opened as a scope is a no-op.
     */
    @ApiStatus.Internal
    public void endBlockEventPhase(ServerLevel level) {
        final BlockEventPhase phase = this.blockEventPhase.get();
        if (phase == null || phase.level != level || --phase.openings > 0) {
            return;
        }
        this.blockEventPhase.remove();
        final long duration = System.nanoTime() - phase.startNanos;
        this.blockEventPhases.incrementAndGet();
        this.blockEventPhaseEvents.addAndGet(phase.events);
        this.blockEventPhaseNanos.addAndGet(duration);
        this.blockEventPhaseMaxNanos.accumulateAndGet(duration, Math::max);
        this.releaseBlockEventPhase(phase);
    }

    /**
     * Releases every region lease of {@code phase}, newest first.
     * <p>
     * Every lease the phase took is released exactly once, so the lease and release counters always add up: the
     * phase released everything it held whether it succeeded, threw, or the engine shut down while it ran.
     */
    private void releaseBlockEventPhase(BlockEventPhase phase) {
        final List<RegionState> leases = new ArrayList<>(phase.leases.values());
        phase.leases.clear();
        for (int index = leases.size() - 1; index >= 0; --index) {
            final RegionState region = leases.get(index);
            region.endOwnership();
            region.clearRunning();
            this.regionizer.removeIfEmpty(region);
            this.blockEventPhaseReleases.incrementAndGet();
        }
    }

    /**
     * Runs one event of {@code phase} under the phase's lease of the event's region.
     * <p>
     * The lease is taken on demand the first time an event of the region runs and kept until the phase ends. A
     * region that cannot be leased - it is dead, a worker owns it, or the engine shut down - runs the event without
     * a region context instead of dropping it: no worker runs during a phase, so the vanilla body is correct without
     * one, and the fallback is counted.
     */
    private void runInBlockEventPhase(BlockEventPhase phase, ServerLevel level, BlockPos pos, Runnable task) {
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        RegionState region = phase.leases.get(coordinate);
        if (region == null) {
            region = this.leaseBlockEventRegion(coordinate);
            if (region == null) {
                this.blockEventPhaseLeaseFallbacks.incrementAndGet();
                task.run();
                return;
            }
            phase.leases.put(coordinate, region);
            this.blockEventPhaseLeases.incrementAndGet();
        }
        phase.events++;
        this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), task);
    }

    /**
     * Takes the lease of {@code coordinate} for a block event phase.
     *
     * @return the leased region, or {@code null} when it cannot be leased and the event has to run without one
     */
    @Nullable
    private RegionState leaseBlockEventRegion(RegionCoordinate coordinate) {
        if (this.shutdown || !this.acquireLifecycleReadLock(false)) {
            return null;
        }
        try {
            if (this.shutdown) {
                return null;
            }
            final RegionState region = this.regionizer.getOrCreate(coordinate);
            if (region == null || region.isDead() || !region.tryMarkRunning()) {
                return null;
            }
            region.beginOwnership();
            return region;
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
    }

    /**
     * Records that {@code level} is about to save and checks that the save observes a quiescent level.
     * <p>
     * The save is the moment the world on disk is defined, so it has to observe a level whose last tick is
     * fully applied: no region batch of the level in flight and no deferred effect of one waiting for a
     * barrier that has not run. This method asserts exactly that and counts what it saw:
     * <ul>
     * <li>A batch of the level in flight is a <b>violation</b>: the batch still holds a worker, the lease of
     * its region and the effects its groups recorded, so the chunk, entity and POI data a save reads would be
     * a half-applied tick. {@link #levelSavesWithBatchInFlight} counts it and the first few are logged at
     * error level. It is zero for a save of the server thread, because a batch is published and awaited by the
     * same thread and the server thread can only save between two level ticks.</li>
     * <li>Deferred effects of a batch that has not reached its barrier are counted separately
     * ({@link #levelSavesWithTickDeferredWork}): they are the same violation one level down, and they are the
     * work a crash in the middle of that tick loses.</li>
     * <li>Cross-tick deferred work - border updates the fixpoint bound delayed to the target region's next
     * tick through the mailbox, pending entity transfer decisions and the POI columns the level tick has not
     * written yet - is <b>not</b> a violation: it is work the design defers across a tick boundary, and a
     * crash loses it exactly like the vanilla block update of the tick that never ran. It is counted so the
     * loss is observable instead of silent ({@link #levelSavesWithCrossTickDeferredWork},
     * {@link #levelSaveCrossTickDeferredEntries}, {@link #levelSaveDirtyPoiColumns}).</li>
     * </ul>
     * Runs on the thread that saves, which is the server thread of a production server.
     */
    private final Map<ServerLevel, RegionIndependentTickScheduler.BoundedLevelPin> activeSavePins = new ConcurrentHashMap<>();

    @ApiStatus.Internal
    public void beginLevelSave(ServerLevel level) {
        this.beginLevelSave(level, true);
    }

    @ApiStatus.Internal
    public void beginLevelSave(ServerLevel level, boolean flush) {
        this.flushStagedFusedChunks(level);
        final int maxWaitMs = flush ? 0 : RegionThreadingConfig.autosaveMaxQuiesceWaitMillis();
        if (!flush && maxWaitMs > 0 && this.isIndependentRegionTickEnabled()) {
            final var previous = this.activeSavePins.remove(level);
            if (previous != null) {
                previous.close();
            }
            this.activeSavePins.put(level,
                    this.independentTickScheduler.tryPinLevelQuiescent(level, maxWaitMs * 1_000_000L));
        } else {
            this.independentTickScheduler.awaitLevelQuiescent(level, false);
        }
        this.levelSaves.incrementAndGet();
        long tickDeferred = 0L;
        boolean batchInFlight = false;
        for (final RegionTickBatch batch : this.activeTickBatches) {
            if (batch.level() != level) {
                continue;
            }
            batchInFlight = true;
            tickDeferred += batch.pendingDeferredWork();
        }
        final RegionBorderUpdates borders = this.borderUpdates.get(level);
        final long borderEntries = borders == null ? 0L : borders.pendingCount();
        final RegionMailbox mailbox = this.mailboxes.get(level);
        final long mailboxEntries = mailbox == null ? 0L : mailbox.pendingMessageCount();
        final long pendingMoves;
        synchronized (this.admissionLock) {
            pendingMoves = this.pendingDeferredEntityMoves.size();
        }
        final RegionPoiStorage poiStorage = RegionPoiStorages.of(level);
        final long dirtyPoiColumns = poiStorage == null ? 0L : poiStorage.dirtyColumnCount();
        final long crossTickDeferred = borderEntries + mailboxEntries + pendingMoves + dirtyPoiColumns;
        if (batchInFlight) {
            this.levelSavesWithBatchInFlight.incrementAndGet();
        }
        if (tickDeferred > 0L) {
            this.levelSavesWithTickDeferredWork.incrementAndGet();
        }
        if (crossTickDeferred > 0L) {
            this.levelSavesWithCrossTickDeferredWork.incrementAndGet();
            this.levelSaveCrossTickDeferredEntries.addAndGet(crossTickDeferred);
        }
        this.levelSaveDirtyPoiColumns.addAndGet(dirtyPoiColumns);
        if ((batchInFlight || tickDeferred > 0L) && this.loggedSaveViolations.getAndIncrement() < MAX_LOGGED_SAVE_VIOLATIONS) {
            LOGGER.error("A level save observed a region tick batch that had not finished: {} in flight, {} deferred effects of it"
                    + " (level {}); the saved data is not a completed tick", batchInFlight, tickDeferred, level.dimension().location());
        }
    }

    /**
     * Writes the remaining POI columns of {@code level} and waits for them, at the end of a save that flushes.
     * <p>
     * The vanilla save waits for the chunk and entity IO of a flushing save, but the POI data of a level is a
     * separate IO worker that nobody waits for: the vanilla section storage schedules a column write and
     * forgets it, so a POI write of the last tick can still be missing after a save that reported success.
     * The regionized storage writes its own columns, so the engine can close that window and does: a saved
     * level holds every POI record and ticket count the level held at the save point.
     * <p>
     * Runs on the server thread, after the level's chunk and entity data was written.
     */
    @ApiStatus.Internal
    public void endLevelSave(ServerLevel level) {
        final RegionPoiStorage poiStorage = RegionPoiStorages.of(level);
        if (poiStorage == null) {
            return;
        }
        final int pending = poiStorage.dirtyColumnCount();
        poiStorage.flushAll();
        this.levelSaveFlushedPoiColumns.addAndGet(pending);
    }

    @ApiStatus.Internal
    public void endLevelSaveQuiescence(ServerLevel level) {
        final var pin = this.activeSavePins.remove(level);
        if (pin != null) {
            pin.close();
        }
    }

    @ApiStatus.Internal
    public boolean isChunkInBusyAsyncRegion(ServerLevel level, ChunkPos chunkPos) {
        final var pin = this.activeSavePins.get(level);
        if (pin == null) {
            return false;
        }
        final RegionCoordinate coord = this.layout.regionAt(level, chunkPos.x, chunkPos.z);
        return pin.isBusy(coord);
    }

    /**
     * Runs {@code task} under a freshly acquired lease of the region containing {@code pos}.
     *
     * @return {@code false} when the region is currently leased by another thread or the engine is shut
     *         down, in which case the caller has to defer the tick to the region queue instead of running
     *         it here
     */
    private boolean runBlockTaskUnderLease(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.shutdown) {
            return false;
        }
        final boolean tryLifecycleGateOnly = this.isExecutingTickBatchOnCurrentThread()
                || this.isExecutingRegionTickOnCurrentThread()
                || this.workerPool.isWorkerThread(Thread.currentThread());
        if (!this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
            return false;
        }
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        final RegionState region;
        try {
            if (this.shutdown) {
                return false;
            }
            region = this.regionizer.getOrCreate(coordinate);
            if (region == null || region.isDead() || !region.tryMarkRunning()) {
                return false;
            }
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
        try {
            region.beginOwnership();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), task);
        } finally {
            region.endOwnership();
            region.clearRunning();
            this.regionizer.removeIfEmpty(region);
        }
        // The task ran under a region lease outside a batch: a neighbour, shape, comparator or block-entity
        // update it sent to another region was captured for the fixpoint, and no batch barrier follows this
        // call. Flush the fixpoint here so a server-thread fallback tick still converges in the tick it runs in.
        // A block event phase does not come through here when its scope is open: the phase applies its writes
        // inline and has nothing to flush. The fixpoint is not entered from a native worker either: a worker's
        // target region is queued, not leased inline.
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            this.runBorderUpdateFixpoint(level);
        }
        return true;
    }

    boolean enqueueRegionTask(ServerLevel level, BlockPos pos, Runnable task) {
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        // The regionizer resolves a live owner and inserts the task while holding the same lock that
        // retirement and merge/split publication use, so the handoff can never land in a retired queue and
        // never needs a retry or an inline fallback that would drop it.
        return this.regionizer.enqueue(coordinate, () -> this.runQueuedBlockTask(level, pos, task));
    }

    private void runQueuedBlockTask(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.isOwnedByCurrentRegion(level, pos)) {
            if (RegionWorkerScope.isNativeWorkerThread() && this.currentRegionTickMutations.get() == null) {
                final Queue<Runnable> mutations = new ConcurrentLinkedQueue<>();
                this.currentRegionTickMutations.set(mutations);
                final boolean boundRandom = this.currentBlockEntityTickRandom.get() == null;
                if (boundRandom) {
                    this.currentBlockEntityTickRandom.set(RandomSource.create(
                            level.getSeed() ^ level.getGameTime() ^ pos.asLong()));
                }
                try {
                    task.run();
                } catch (Throwable throwable) {
                    if (RegionWorkerBoundaryException.findBoundaryViolation(throwable) != null
                            || RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                        // A cross-region capability or storage access inside a queued block task (e.g. a
                        // border-adjacent energy cable querying neoforge:energy across a region seam) cannot
                        // complete on a worker; forward cleanly to the server thread instead of spamming errors.
                        this.boundaryFallbackBlockEntityTicks.incrementAndGet();
                        if (!this.shutdown && !this.regionizer.isLevelClosed(level)) {
                            net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(level, task);
                        }
                        return;
                    }
                    throw throwable;
                } finally {
                    if (boundRandom) {
                        this.currentBlockEntityTickRandom.remove();
                    }
                    this.currentRegionTickMutations.remove();
                    if (!mutations.isEmpty() && !this.shutdown && !this.regionizer.isLevelClosed(level)) {
                        net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(
                                level, () -> this.applyRegionTickMutations(mutations));
                    }
                }
                return;
            }
            task.run();
            return;
        }
        // A merge/split can change the owner after insertion. Hand off again under the current queue's
        // lease instead of executing against the wrong region. This remains deferred and non-blocking.
        this.enqueueRegionTask(level, pos, task);
    }

    /**
     * Drains tasks queued to the region that owns {@code pos}.
     * <p>
     * A worker tick that is still running for the same region is awaited first, unless the current thread
     * is the one running it. Otherwise the drain could observe the lease as taken and return while the
     * queued work is only picked up by the worker's next tick.
     * <p>
     * When the current thread already owns this region, it is running inside the region's tick. Work that
     * was queued during that tick belongs to the next tick, so the drain returns without nesting a second
     * queue snapshot or advancing the logical queue tick.
     */
    @ApiStatus.Internal
    public void makeRegionDueForTesting(ServerLevel level, BlockPos pos) {
        final RegionState region = this.regionizer.get(this.layout.regionAt(level, pos));
        if (region != null) {
            region.makeDueForTesting();
        }
    }

    /**
     * Installs a hook that runs immediately before a level drain activates a region.
     * <p>
     * Only used by tests to force the interleaving between drain activation and lifecycle cleanup.
     */
    @ApiStatus.Internal
    public void setBeforeRegionDrainActivationForTesting(@Nullable Runnable hook) {
        this.beforeRegionDrainActivation = hook;
    }

    /**
     * Installs a hook that runs while an admission holds the admission lock, immediately before the
     * admission is published.
     * <p>
     * Only used by tests to force the interleaving between an admission and lifecycle cleanup.
     */
    @ApiStatus.Internal
    public void setBeforeRegionAdmissionForTesting(@Nullable Runnable hook) {
        this.beforeRegionAdmission = hook;
    }

    @ApiStatus.Internal
    public void drainRegionTasks(ServerLevel level, BlockPos pos) {
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        final RegionState region = this.regionizer.get(coordinate);
        if (region == null) {
            return;
        }
        if (region.isOwnedByCurrentThread()) {
            // Reentrant same-region drain: the queue drain that is running on this thread owns the region
            // and will pick up newly inserted work on its next tick. Nested draining would snapshot the
            // queue again and advance its logical tick (and scheduled deadlines) inside the outer tick.
            return;
        } else if (this.workerPool.isWorkerThread(Thread.currentThread())) {
            // A pool worker cannot wait for a submission that may be queued behind its own task, and it
            // must never drain a queue whose worker was already accepted.
            if (!region.isWorkerSubmitted() && !region.isRunning()) {
                this.drainRegionStateWithLifecycleGate(region, true);
            }
        } else {
            this.awaitRegionQuiescent(region, levelBarrier(level));
            final boolean tryLifecycleGateOnly = this.isExecutingTickBatchOnCurrentThread()
                    || this.isExecutingRegionTickOnCurrentThread();
            this.drainRegionStateWithLifecycleGate(region, tryLifecycleGateOnly);
        }
        this.regionizer.removeIfEmpty(region);
    }

    /**
     * Drains all tasks queued to regions in {@code level} and waits for the region ticks submitted by this
     * drain before returning.
     * <p>
     * A worker drain mutates the region queue and can call back into the level, so the vanilla continuation
     * after this method must not observe the region as idle while its worker is still running. The wait
     * uses the level chunk source barrier, exactly like an entity batch, so off-thread chunk requests can
     * still be serviced. A pool worker never waits here: work it submitted may only be runnable after the
     * current worker returns.
     * <p>
     * The drain is a region phase of the level tick: it is opened before the first region is activated and
     * closed after the last worker was awaited, so no queued region task of the level runs next to a global
     * statement of the same level.
     */
    @ApiStatus.Internal
    public void drainLevelRegionTasks(ServerLevel level) {
        this.beginRegionPhase(level);
        try {
            this.drainLevelRegionTasks(level, levelBarrier(level));
        } finally {
            this.endRegionPhase(level);
        }
    }

    @ApiStatus.Internal
    public void drainLevelRegionTasksNonBlocking(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        this.runAdmitted(() -> {
            final long now = System.nanoTime();
            final long tickIndex = level.getGameTime();
            final boolean workerThread = this.workerPool.isWorkerThread(Thread.currentThread());
            final boolean tryLifecycleGateOnly = workerThread || this.isExecutingTickBatchOnCurrentThread()
                    || this.isExecutingRegionTickOnCurrentThread();
            if (this.isIndependentRegionTickEnabled()
                    && (this.workerExecutionEnabled || this.independentRegionTickOverride)
                    && !workerThread
                    && this.independentTickScheduler != null) {
                this.drainLevelRegionTasksAsync(level, now, tickIndex, tryLifecycleGateOnly);
                return;
            }
            this.drainLevelRegionTasks(level, levelBarrier(level));
        });
    }

    private void drainLevelRegionTasks(ServerLevel level, @Nullable RegionTickBarrier barrier) {
        final long now = System.nanoTime();
        // Every live region of this level opens the metric slot of this server tick before any of its work is
        // recorded, so a region that is never leased closes the slot as 0 ns instead of a missed tick.
        final long tickIndex = level.getGameTime();
        final boolean workerThread = this.workerPool.isWorkerThread(Thread.currentThread());
        // A lifecycle cleanup may already wait for the entity batch or region tick running on this thread.
        // Blocking on the lifecycle gate would wait for itself, so such a drain only tries the read lock
        // and rejects its activation when a cleanup is in progress.
        final boolean tryLifecycleGateOnly = workerThread || this.isExecutingTickBatchOnCurrentThread()
                || this.isExecutingRegionTickOnCurrentThread();
        final RegionTaskBatch submissions = new RegionTaskBatch();
        final RegionTaskBatch.Barrier completion = RegionTaskBatch.Barrier.open();
        final List<RegionState> alreadyActive = new ArrayList<>();
        this.regionizer.forEach(region -> {
            if (region.coordinate().level() != level) {
                return;
            }
            region.tickMetrics().beginTick(tickIndex, now);
            if (region.isOwnedByCurrentThread()) {
                // The current thread is already ticking this region (a nested level drain from a region
                // task). Skip it entirely: activating it would consume the region tick timer without a
                // drain and push its next legitimate tick into the future.
                return;
            }
            if (this.workerExecutionEnabled) {
                this.trySubmitRegionDrain(region, now, submissions, alreadyActive, tryLifecycleGateOnly);
            } else {
                this.tickRunner.runIfDue(region, now, () -> this.drainRegionStateWithLifecycleGate(region, tryLifecycleGateOnly));
            }
            this.regionizer.removeIfEmpty(region);
        });

        submissions.submitOpen(this.workerPool, completion);
        completion.setupComplete();
        this.rejectedWorkerTaskCount.addAndGet(completion.rejectedCount());
        if (workerThread) {
            // A region worker cannot wait for a submission that may be queued behind its own task.
            return;
        }
        if (barrier != null) {
            barrier.await(completion::isComplete);
        }
        completion.awaitCompletion();
        for (final RegionState region : alreadyActive) {
            this.awaitRegionQuiescent(region, barrier);
        }
        completion.throwIfFailed();
    }

    private void drainLevelRegionTasksAsync(ServerLevel level, long nowNanos, long tickIndex, boolean tryLifecycleGateOnly) {
        this.regionizer.forEach(region -> {
            if (region.coordinate().level() != level) {
                return;
            }
            region.tickMetrics().beginTick(tickIndex, nowNanos);
            if (region.isOwnedByCurrentThread() || region.isDead() || region.isRunning() || region.isWorkerSubmitted()) {
                return;
            }
            if (this.independentTickScheduler.isRegionInFlight(region.coordinate())) {
                return;
            }
            if (region.taskQueue().pendingCount() == 0) {
                if (this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
                    try {
                        if (!this.shutdown && !region.isDead()) {
                            synchronized (region) {
                                if (!region.tickTimer().isDue(nowNanos)) {
                                    return;
                                }
                            }
                            this.tryCompleteEmptyRegionDrain(region, nowNanos);
                        }
                    } finally {
                        this.lifecycleGate.readLock().unlock();
                    }
                }
                this.regionizer.removeIfEmpty(region);
                return;
            }
            synchronized (region) {
                if (!region.tickTimer().isDue(nowNanos)) {
                    return;
                }
            }
            this.independentTickScheduler.submitAsyncSlice(
                    region,
                    "region queue drain",
                    () -> this.tickRunner.runIfDue(region, nowNanos, () -> {
                        final RegionCoordinate coordinate = region.coordinate();
                        this.runWithContext(
                                new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, coordinate.level(), coordinate, region, null),
                                () -> this.failedQueuedTaskCount.addAndGet(region.taskQueue().drain()));
                    }),
                    null);
        });
    }

    private void serviceMainThreadQueuesForAsyncWait(@Nullable ServerLevel level) {
        if (this.independentTickScheduler != null) {
            if (level != null) {
                this.independentTickScheduler.drainCompletedCommits(level, 500_000L);
            }
            this.independentTickScheduler.drainAllCompletedCommits(250_000L);
        }
        if (level != null) {
            final MinecraftServer server = level.getServer();
            if (server != null && server.isSameThread()) {
                final ServerChunkCache chunkSource = level.getChunkSource();
                if (chunkSource != null) {
                    chunkSource.pollTask();
                }
                server.pollTask();
            }
        }
    }

    private RegionTickBarrier levelBarrier(ServerLevel level) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource == null) {
            return null;
        }
        return condition -> chunkSource.managedBlockOnMainThread(() -> {
            this.serviceMainThreadQueuesForAsyncWait(level);
            return condition.getAsBoolean();
        });
    }

    private void awaitRegionQuiescent(RegionState region, @Nullable RegionTickBarrier barrier) {
        if (region.isOwnedByCurrentThread()) {
            return;
        }
        final ServerLevel level = region.coordinate().level();
        if (barrier == null) {
            while (region.isWorkerSubmitted() || region.isRunning()) {
                this.serviceMainThreadQueuesForAsyncWait(level);
                if (!region.isWorkerSubmitted() && !region.isRunning()) {
                    return;
                }
                LockSupport.parkNanos("waiting for region quiescence", 100_000L);
            }
            return;
        }
        barrier.await(() -> {
            if (this.independentTickScheduler != null && level != null) {
                this.independentTickScheduler.drainCompletedCommits(level, 500_000L);
            }
            return !region.isWorkerSubmitted() && !region.isRunning();
        });
    }

    /**
     * Drops queued region work and scheduled entity tasks for {@code level} during level unload.
     * <p>
     * The clear is serialized with every admission path: running work is awaited under the lifecycle
     * gate first, then the level identity is closed and its regionizer state is dropped while holding the
     * admission lock and the regionizer write lock. No admission can therefore publish a region or task
     * that this clear would miss, and no region, section, entity or scheduled task for the level remains
     * when this method returns.
     * <p>
     * Lock order is lifecycle write gate, then the regionizer. The write gate is held while active tick
     * batches are awaited, but the regionizer lock is not: a published batch keeps leasing regions and its
     * workers retire empty regions ({@code removeIfEmpty}) without the lifecycle gate, so holding the
     * regionizer here would block the very batch the clear waits for. Publication takes the read gate for
     * its closed-level check, so a batch either publishes before this clear (and is awaited) or observes the
     * closed identity and rejects itself.
     * <p>
     * Closure is per {@link ServerLevel} identity and lasts for the lifetime of that identity: later
     * admissions for an unloaded level are rejected (schedules return a cancelled task, enqueues and
     * section/entity updates are dropped) instead of recreating live region state. An unloaded level is
     * never reloaded under the same instance; a legitimate reload constructs a new {@code ServerLevel},
     * which receives a fresh lifecycle. Closed identities are tracked weakly, so this does not keep an
     * unloaded level alive.
     */
    @ApiStatus.Internal
    public void clearLevelRegionTasks(ServerLevel level) {
        this.rejectLifecycleSelfWait("clear region tasks for a level", level);
        synchronized (this) {
            final Lock lifecycleWriteLock = this.lifecycleGate.writeLock();
            lifecycleWriteLock.lock();
            try {
                this.awaitActiveTickBatches(level);
                this.independentTickScheduler.awaitLevelQuiescent(level, true);
                this.awaitSubmittedRegionTasks(level);
                // The admission lock closes the lookup-to-insertion window: an admission that already
                // passed its shutdown check either publishes before this block (and is cleared below) or
                // observes the closed level identity and rejects its work.
                synchronized (this.admissionLock) {
                    // A pending decision that touches the closing level becomes terminal here. A later
                    // commit therefore cannot write a region mapping after the clear below.
                    this.entityTransferJournal.clearPendingFor(level);
                    this.pendingDeferredEntityMoves.removeIf(move -> move.destination().level() == level);
                    this.chunkLoadAnswers.remove(level);
                    this.seededEntityTickLevels.remove(level);
                    this.playerTicks.clearLevel(level);
                    this.playerRegions.clearLevel(level);
                    this.fusedSession.clearLevel(level);
                    this.unifiedTickCoordinator.clearLevel(level);
                    RegionPlayerSnapshots.detach(level);
                    this.regionizer.clearLevel(level);
                    final DynamicRegionTopology topology = this.dynamicTopologies.remove(level);
                    if (topology != null) {
                        topology.clear();
                    }
                    this.dynamicTopologyTicks.remove(level);
                    // The mailbox and the phase state of the level die with its region state: the queued
                    // messages are dropped and counted, and a later post is refused instead of waiting for a
                    // tick of a level that no longer exists. No batch of the level is in flight here, so no
                    // worker can be inside a delivery or a phase transition either.
                    this.closeLevelMailbox(level);
                    final ConcurrentLinkedQueue<Runnable> workerTasks = this.workerServerTasks.remove(level);
                    if (workerTasks != null) {
                        this.workerServerTasksDropped.addAndGet(workerTasks.size());
                        workerTasks.clear();
                    }
                    this.levelTickPhases.remove(level);
                    final Set<RegionState> foreignReaders = this.regionsWithForeignReads.remove(level);
                    if (foreignReaders != null) {
                        foreignReaders.forEach(this::discardForeignReads);
                    }
                    final RegionBorderUpdates borderUpdates = this.borderUpdates.remove(level);
                    if (borderUpdates != null) {
                        this.borderUpdatesDropped.addAndGet(borderUpdates.drain().size() + borderUpdates.drainOverflow().size());
                        borderUpdates.discard();
                    }
                    // The captured game events of the level die with its regions: no region is left to replay
                    // them, so they are dropped and counted instead of waiting for a tick that never comes.
                    final RegionBorderGameEvents gameEvents = this.borderGameEvents.remove(level);
                    if (gameEvents != null) {
                        this.gameEventSectionsDropped.addAndGet(gameEvents.drain().size());
                        gameEvents.discard();
                    }
                    for (final EntityRegionScheduledTask task : this.entityTaskSnapshot()) {
                        if (task.isOwnedBy(level)) {
                            task.cancel();
                        }
                    }
                }
            } finally {
                lifecycleWriteLock.unlock();
            }
        }
    }

    /**
     * Tracks a loaded chunk section in the native regionizer facade.
     */
    @ApiStatus.Internal
    public void addRegionSection(ServerLevel level, int chunkX, int chunkZ) {
        this.callAdmitted(() -> {
            final RegionState region = this.regionizer.addChunk(level, chunkX, chunkZ);
            if (region != null && this.layout.isDynamicCluster()) {
                this.dynamicTopology(level).addChunk(chunkX, chunkZ);
            }
            return region;
        });
    }

    /**
     * Removes a loaded chunk section from the native regionizer facade.
     */
    @ApiStatus.Internal
    public void removeRegionSection(ServerLevel level, int chunkX, int chunkZ) {
        this.runAdmitted(() -> {
            if (this.layout.isDynamicCluster()) {
                final DynamicRegionTopology topology = this.dynamicTopologies.get(level);
                if (topology != null) {
                    topology.removeChunk(chunkX, chunkZ);
                }
            }
            this.regionizer.removeChunk(level, chunkX, chunkZ);
        });
    }

    /**
     * Tracks an entity in the native regionizer facade.
     * <p>
     * A player is the exception: its region mapping is published at the start of its level's entity slice and not
     * when the level announces it, so a dimension change - which announces the player in the destination level
     * from inside the source level's tick - has one writer like every other transition. The tick membership hook
     * records the player instead, and the publication point publishes it; see {@link PlayerRegionTransitions}.
     */
    @ApiStatus.Internal
    public void addRegionEntity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return;
        }
        synchronized (this.admissionLock) {
            if (this.shutdown) {
                return;
            }
            if (entity.level() instanceof ServerLevel level) {
                this.regionizer.addEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
            }
        }
    }

    /**
     * Removes an entity from the native regionizer facade.
     * <p>
     * Inside an entity tick batch the removal is deferred until the batch finished, so the snapshot region
     * stays authoritative while the entity is ticked.
     */
    @ApiStatus.Internal
    public void removeRegionEntity(Entity entity) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            group.deferEntityUpdate(entity, this.deferredEntityDestination(entity));
            return;
        }
        synchronized (this.admissionLock) {
            if (this.shutdown) {
                return;
            }
            this.publishEntityRemoval(entity);
        }
    }

    /**
     * Updates an entity's current region in the native regionizer facade.
     * <p>
     * Inside an entity tick batch the move is deferred until the batch finished. Publishing the new owner
     * while the old snapshot region is still ticking would make ownership checks fail and let scheduled
     * work move to the new region before the old tick completed.
     * <p>
     * A player is the exception: its region is published at the start of its level's entity slice and not from its
     * own tick, because the tick of a player is the one entity tick the server thread runs while a batch is still
     * published, and a mapping written there would be a mapping a worker of that batch could observe. See
     * {@link PlayerRegionTransitions}.
     */
    @ApiStatus.Internal
    public void moveRegionEntity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return;
        }
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            group.deferEntityUpdate(entity, this.deferredEntityDestination(entity));
            return;
        }
        if (!this.shutdown && this.entityTasksByEntity.isEmpty() && entity.level() instanceof ServerLevel level) {
            final RegionState existing = this.regionizer.regionFor(entity);
            if (existing != null && !existing.isDead()
                    && existing.coordinate().equals(this.layout.regionAt(level, entity.blockPosition()))) {
                return;
            }
        }
        synchronized (this.admissionLock) {
            if (this.shutdown) {
                return;
            }
            this.publishEntityMove(entity);
        }
    }

    /**
     * {@return the destination region of a deferred ownership change, captured on the deferring thread}
     * <p>
     * Inside an entity tick batch the position read here is the one the deferring worker already observed
     * while it ticked the entity. The destination is stored with the deferred update and committed at the
     * barrier, so a position the entity reaches in between cannot redirect the handoff.
     */
    @Nullable
    private RegionCoordinate deferredEntityDestination(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return this.layout.regionAt(level, entity.blockPosition());
    }

    void publishEntityMove(Entity entity) {
        if (this.shutdown || !(entity.level() instanceof ServerLevel level)) {
            // The engine is being torn down or the entity left the server side; its scheduled wrappers
            // have no live region to migrate to and must be retired like a removal.
            this.cancelEntityTasks(entity);
            return;
        }
        final RegionState region = this.regionizer.moveEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
        if (region == null) {
            // The level identity was unloaded for region threading. The unload only cancelled the tasks
            // it could still attribute to the level; a wrapper whose entity already reported the new
            // level would stay registered with a dead child, so retire it here as well.
            this.cancelEntityTasks(entity);
            return;
        }
        this.notifyEntityMoved(entity);
    }

    /**
     * Applies the deferred ownership handoffs of a finished entity batch on the server thread.
     * <p>
     * Every handoff carries the destination region its deferring worker captured, so nothing here reads the
     * entity's live position to decide where the mapping goes.
     * <p>
     * The batch's own captures are applied before the moves that a busy region left pending at an earlier
     * barrier, because a capture of this batch is the newer observation of the entity. Committing the newer
     * capture prunes the pending entry of the same entity, so the stale queued destination is not applied and
     * the newest observation wins instead of the mapping lagging a tick behind. The pending entries are claimed
     * one by one after the batch ran, so a move that this barrier queues for a still busy region waits for the
     * next barrier instead of being attempted twice in a row.
     */
    void applyDeferredEntityUpdates(RegionEntityTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredEntityUpdates(batch);
    }

    void applyDeferredEntitySectionUpdates(RegionEntityTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredEntitySectionUpdates(batch);
    }

    void applyDeferredPortalTransitions(RegionEntityTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredPortalTransitions(batch);
    }

    void applyDeferredChunkTickSectionUpdates(RegionChunkTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredChunkTickSectionUpdates(batch);
    }

    void applyDeferredSectionUpdates(ServerLevel level, List<RegionDeferredEntitySectionUpdates.DeferredSectionUpdate> updates, boolean seamClosed) {
        this.entityTransferCoordinator.applyDeferredSectionUpdates(level, updates, seamClosed);
    }

    void applyDeferredEntityRemovals(RegionEntityTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredEntityRemovals(batch);
    }

    void applyDeferredBlockEntityRemovals(RegionBlockEntityTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredBlockEntityRemovals(batch);
    }

    void applyDeferredChunkTickRemovals(RegionChunkTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredChunkTickRemovals(batch);
    }

    long deferredRemovalCount() {
        return this.entityTransferCoordinator.deferredRemovalCount();
    }

    void applyDeferredRemovals(ServerLevel level, RegionDeferredEntityRemovals.Drain drain, boolean seamClosed) {
        this.entityTransferCoordinator.applyDeferredRemovals(level, drain, seamClosed);
    }

    void applyDeferredRemovals(ServerLevel level, RegionDeferredEntityRemovals.Drain drain, boolean seamClosed, boolean committedPlayerEffects) {
        this.entityTransferCoordinator.applyDeferredRemovals(level, drain, seamClosed, committedPlayerEffects);
    }

    void applyDeferredEntityAdditions(RegionEntityTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredEntityAdditions(batch);
    }

    void applyDeferredChunkTickAdditions(RegionChunkTickBatch batch) {
        this.entityTransferCoordinator.applyDeferredChunkTickAdditions(batch);
    }

    void applyDeferredAdditions(ServerLevel level, RegionDeferredEntityAdditions.Drain drain, boolean seamClosed) {
        this.entityTransferCoordinator.applyDeferredAdditions(level, drain, seamClosed);
    }

    void closeDeferredSeams() {
        this.entityTransferCoordinator.closeDeferredSeams();
    }

    public RegionEntityTransferToken recordEntityRegionTransfer(Entity entity, RegionCoordinate destination) {
        return this.entityTransferCoordinator.recordEntityRegionTransfer(entity, destination);
    }

    public static int maxPendingEntityTransfers() {
        return RegionEntityTransferCoordinator.maxPendingEntityTransfers();
    }

    public RegionEntityTransferResult commitEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        return this.entityTransferCoordinator.commitEntityRegionTransfer(token);
    }

    public RegionEntityTransferResult abortEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        return this.entityTransferCoordinator.abortEntityRegionTransfer(token);
    }

    public int pendingEntityTransferCount() {
        return this.entityTransferCoordinator.pendingEntityTransferCount();
    }

    public int rememberedEntityTransferResultCount() {
        return this.entityTransferCoordinator.rememberedEntityTransferResultCount();
    }

    void notifyEntityMoved(Entity entity) {
        this.entityTransferCoordinator.notifyEntityMoved(entity);
    }

    void cancelEntityTasks(Entity entity) {
        this.entityTransferCoordinator.cancelEntityTasks(entity);
    }

    List<EntityRegionScheduledTask> entityTaskSnapshot() {
        return this.entityTransferCoordinator.entityTaskSnapshot();
    }

    int entityTaskCount() {
        return this.entityTransferCoordinator.entityTaskCount();
    }

    void recordEntityTaskMigration() {
        this.entityTransferCoordinator.recordEntityTaskMigration();
    }

    void publishEntityRemoval(Entity entity) {
        this.entityTransferCoordinator.publishEntityRemoval(entity);
    }

    void unregisterEntityTask(EntityRegionScheduledTask task) {
        this.entityTransferCoordinator.unregisterEntityTask(task);
    }

    /** Value-only command observation of the existing regionizer; never grants execution authority. */
    @ApiStatus.Internal
    public List<RuntimeRegionSnapshot> runtimeRegionSnapshots() {
        return this.regionizer.runtimeRegionSnapshots();
    }

    @ApiStatus.Internal
    public WorkerRuntimeSnapshot workerRuntimeSnapshot() {
        return this.workerPool.runtimeSnapshot();
    }

    @ApiStatus.Internal
    public void setRuntimeTimingEnabled(boolean enabled) {
        this.workerPool.setRuntimeTimingEnabled(enabled);
    }

    /**
     * {@return a snapshot of internal runtime diagnostics}
     * <p>
     * The snapshot includes the per-region tick metrics of every live region and their aggregate. Both are
     * immutable, so a caller reads them without holding a lock of the engine, and a diagnostics call that
     * finds no region reports an empty region list and a summary of zero regions.
     */
    @ApiStatus.Internal
    public RegionThreadingDiagnostics diagnostics() {
        final List<RegionTickSnapshot> regionTicks = this.regionizer.regionTickSnapshots();
        final RegionDiagnosticsCollector.PhaseCounts phases = RegionDiagnosticsCollector.phases(this.levelTickPhases.values());
        return new RegionThreadingDiagnostics(
                this.regionizer.size(),
                this.regionizer.runningRegionCount(),
                this.regionizer.submittedRegionCount(),
                this.regionizer.sectionCount(),
                this.regionizer.loadedChunkCount(),
                this.regionizer.pendingRecalculationCount(),
                this.regionizer.structuralChangeCount(),
                this.regionizer.splitCheckRequestCount(),
                this.regionizer.mergeCheckRequestCount(),
                this.regionizer.entityCount(),
                this.entityTaskCount(),
                this.workerPool.threadCount(),
                this.workerExecutionEnabled,
                this.globalWorkerExecutionEnabled,
                this.entityTickWorkerExecutionEnabled,
                this.blockEntityTickWorkerExecutionEnabled,
                this.scheduledTickWorkerExecutionEnabled,
                this.chunkTickWorkerExecutionEnabled,
                this.spawnWorkerExecutionEnabled,
                this.entityTicksOnWorkers.get(),
                this.skippedEntityTicks.get(),
                this.fallbackEntityTicks.get(),
                this.deferredEntityTicks.get(),
                this.truncatedEntityTicks.get(),
                RegionEntityOwnershipScope.allowedMutationCount(),
                RegionEntityOwnershipScope.rejectedMutationCount(),
                RegionEntityOwnershipScope.allowedQueryCount(),
                RegionEntityOwnershipScope.truncatedQueryCount(),
                RegionEntityOwnershipScope.rejectedQueryCount(),
                RegionEntityQueryCounters.snapshot(),
                RegionPlayerSnapshots.publicationCount(),
                this.blockEntityTicksOnWorkers.get(),
                this.skippedBlockEntityTicks.get(),
                this.fallbackBlockEntityTicks.get(),
                this.nonTickingBlockEntityChunks.get(),
                this.entitiesLoadingBlockEntityChunks.get(),
                this.missingBlockEntityChunks.get(),
                this.staleBlockEntityChunkSnapshots.get(),
                this.scheduledTicksOnWorkers.get(),
                this.skippedScheduledTicks.get(),
                this.fallbackScheduledTicks.get(),
                this.chunkTicksOnWorkers.get(),
                this.skippedChunkTicks.get(),
                this.fallbackChunkTicks.get(),
                this.boundaryChunkTicks.get(),
                this.unloadedNeighborhoodChunkTicks.get(),
                this.truncatedChunkTicks.get(),
                this.lateChunkTicks.get(),
                this.unleasedChunkTicks.get(),
                this.chunkTickLightningStrikes.get(),
                this.diagnosticsCollector.blockUpdates(),
                this.diagnosticsCollector.tickFailures(),
                this.deferredUpdateBudgetChunkTicks.get(),
                this.nonPartitionedChunkTicks.get(),
                this.spawnChunksOnWorkers.get(),
                this.gateRefusedSpawnChunks.get(),
                this.unloadedNeighborhoodSpawnChunks.get(),
                this.deferredUpdateBudgetSpawnChunks.get(),
                this.lateSpawnChunks.get(),
                this.unleasedSpawnChunks.get(),
                this.truncatedSpawnChunks.get(),
                this.skippedSpawnChunks.get(),
                this.capRefusedSpawns.get(),
                this.refusedSpawnAdditions.get(),
                this.globalRegion.isRunning(),
                this.globalRegion.isWorkerSubmitted(),
                this.rejectedWorkerTaskCount.get(),
                this.failedQueuedTaskCount.get(),
                RegionOwnershipViolationHandler.violationCount(),
                this.globalRegion.tickMetrics().tickCount(),
                this.globalRegion.tickMetrics().lastTickDurationNanos(),
                this.regionizer.totalRegionTickCount(),
                this.regionizer.maxRegionLastTickDurationNanos(),
                this.globalRegion.cappedDrainCount() + this.regionizer.cappedDrainCount(),
                this.layout.policy(),
                this.layout.regionChunkSize(),
                this.entityStorageMode,
                this.isScheduledTickWorkerDispatchEffective(),
                this.isChunkTickWorkerDispatchEffective(),
                this.isSpawnWorkerDispatchEffective(),
                this.entityTransferJournal.pendingCount(),
                this.entityTransferJournal.rememberedCount(),
                this.entityTransferJournal.rejectedRecordCount(),
                this.legacyDeferredEntityMoveCount.get(),
                this.retriedDeferredEntityMoveCount.get(),
                this.appliedDeferredSectionUpdateCount.get(),
                this.droppedDeferredSectionUpdateCount.get(),
                this.droppedDeferredSectionUpdateForRemovedEntityCount.get(),
                this.appliedDeferredRemovalCount.get(),
                this.droppedDeferredRemovalCount.get(),
                this.repeatedDeferredRemovalCount.get(),
                this.recordedDeferredAdditionCount.get(),
                this.appliedDeferredAdditionCount.get(),
                this.droppedDeferredAdditionCount.get(),
                RegionOwnershipViolationHandler.entityAdditionRejectionCount(),
                RegionWorkerChunkUnavailableException.refusalCount(),
                this.chunkFallbackEntityTicks.get(),
                this.chunkPreloadRefusedEntityTicks.get(),
                this.regionizer.writeLockAcquisitionCount(),
                this.batchedDeferredEntityMoveCount.get(),
                this.unchangedDeferredEntityMoveCount.get(),
                this.refusedDeferredEntityMoveBatchCount.get(),
                this.entityTaskMigrationCount.get(),
                this.entityTickRegionsOnWorkers.get(),
                this.fallbackEntityTickRegions.get(),
                this.entityTickRegionsWithoutEligibleChunk.get(),
                this.entityTicksOutsideRegion.get(),
                this.serverThreadOnlyEntityTicks.get(),
                this.entityTickPolicy.serverThreadOnlyTicks(),
                this.unindexedEntityTickStarts.get(),
                this.entityTickIndexFallbacks.get(),
                this.deferredPortalTransitionCount.get(),
                this.appliedPortalTransitionCount.get(),
                this.refusedPortalTransitionCount.get(),
                phases.globalPhases(),
                phases.regionPhases(),
                phases.regionPhasesWithoutGlobalPhase(),
                phases.abandonedGlobalSegments(),
                this.mailboxAcceptedMessages.get(),
                this.mailboxDeliveredMessages.get(),
                this.mailboxRefusedFullMessages.get(),
                this.mailboxRefusedClosedMessages.get(),
                this.mailboxDroppedRetiredMessages.get(),
                this.mailboxDroppedOnCloseMessages.get(),
                this.mailboxDeliveryFailures.get(),
                this.diagnosticsCollector.borderUpdates(),
                this.diagnosticsCollector.blockEvents(),
                this.diagnosticsCollector.gameEvents(),
                new RegionThreadingDiagnostics.PlayerRegionCounters(
                        this.playerRegions.entries(),
                        this.playerRegions.departures(),
                        this.playerRegions.walkTransitions(),
                        this.playerRegions.teleportTransitions(),
                        this.playerRegions.portalTransitions(),
                        this.playerRegions.dimensionTransitions(),
                        this.playerRegions.transitionsDuringBatch(),
                        this.playerRegions.refusedTransitions(),
                        this.playerRegions.deferredArrivals(),
                        this.playerRegions.identityHandovers(),
                        this.playerRegions.invariantChecks(),
                        this.playerRegions.invariantFailures()),
                RegionPoiCounters.snapshot(),
                this.diagnosticsCollector.saves(),
                this.workerDispatchCounters(),
                RegionMetricsSummary.summarize(regionTicks),
                regionTicks);
    }

    /**
     * Shuts down region threading runtime resources.
     * <p>
     * Serialized and idempotent: the first caller stops new work, waits for the in-progress entity batch
     * (including its lease acquisition and server-thread handoff), then clears the regions and waits for
     * the worker pool to terminate. Lifecycle waits are rejected before any state changes when they are
     * requested from a pool worker, because the worker would be waiting for work only it can complete.
     */
    @ApiStatus.Internal
    public void shutdown() {
        // Reject a Worker or an active server callback before taking the engine monitor: another
        // cleanup may already hold it while waiting for this invocation/phase to finish.
        this.rejectLifecycleSelfWait("shut down region threading", null);
        synchronized (this) {
            if (this.shutdown) {
                return;
            }
            final Lock lifecycleWriteLock = this.lifecycleGate.writeLock();
            lifecycleWriteLock.lock();
            try {
                if (this.shutdown) {
                    return;
                }
                for (final RegionTickBatch active : this.activeTickBatches) {
                    if (active.isExecutingOnCurrentThread()) {
                        throw new IllegalStateException("Cannot shut down region threading from its region tick batch");
                    }
                }
                synchronized (this.admissionLock) {
                    if (this.shutdown) {
                        return;
                    }
                    this.blockEntityWorkGraph.stop();
                    this.shutdown = true;
                    this.entityTransferJournal.clear();
                    this.pendingDeferredEntityMoves.clear();
                }
                // Close the deferred seams before waiting for the batches: a worker of a batch that still runs
                // must be refused from now on, and the updates and removals that already wait are released.
                this.closeDeferredSeams();
                this.awaitActiveTickBatches(null);
                this.playerNetworkGate.closeAfterCallbacks();
                this.playerPackets.close();
                this.playerTicks.close();
                this.playerRegions.close();
                this.awaitSubmittedRegionTasks(null);
                // Drain work whose worker submission was rejected while the pool was closing. No new worker
                // submissions are allowed now, and all queued handoffs finish on this server thread.
                this.regionizer.forEach(region -> this.drainRegionState(region));
                this.globalRegion.clearTasks();
                for (final ConcurrentLinkedQueue<Runnable> workerTasks : this.workerServerTasks.values()) {
                    this.workerServerTasksDropped.addAndGet(workerTasks.size());
                    workerTasks.clear();
                }
                this.workerServerTasks.clear();
                for (final EntityRegionScheduledTask task : this.entityTaskSnapshot()) {
                    task.cancel();
                }
                this.entityTasksByEntity.clear();
                this.chunkLoadAnswers.clear();
                this.seededEntityTickLevels.clear();
                // A shutdown from inside a block event phase releases the leases the phase still holds, so no
                // lease outlives the regions it was taken on. The phase itself closes through its own finally.
                final BlockEventPhase openPhase = this.blockEventPhase.get();
                if (openPhase != null) {
                    this.blockEventPhase.remove();
                    this.releaseBlockEventPhase(openPhase);
                }
                this.regionizer.clear();
                for (final DynamicRegionTopology topology : this.dynamicTopologies.values()) {
                    topology.clear();
                }
                this.dynamicTopologies.clear();
                this.dynamicTopologyTicks.clear();
                // Every mailbox is closed before the pool goes away: its queued messages are dropped and
                // counted, and a post from a worker that is still unwinding is refused instead of queued for a
                // tick that never comes. The phase state of the levels is meaningless after this point.
                for (final RegionMailbox mailbox : this.mailboxes.values()) {
                    mailbox.close();
                }
                this.mailboxes.clear();
                this.levelTickPhases.clear();
                for (final RegionBorderUpdates borderUpdates : this.borderUpdates.values()) {
                    this.borderUpdatesDropped.addAndGet(borderUpdates.drain().size() + borderUpdates.drainOverflow().size());
                    borderUpdates.discard();
                }
                this.borderUpdates.clear();
                for (final RegionBorderGameEvents gameEvents : this.borderGameEvents.values()) {
                    this.gameEventSectionsDropped.addAndGet(gameEvents.drain().size());
                    gameEvents.discard();
                }
                this.borderGameEvents.clear();
                // The global region has no coordinate and is not part of the region metrics, so its window is
                // released here: a shut-down engine holds no metric buffer at all.
                this.globalRegion.tickMetrics().release();
                this.independentTickScheduler.shutdown();
                this.workerPool.close();
            } finally {
                lifecycleWriteLock.unlock();
            }
        }
    }

    /**
     * Waits for the region tick batch that is currently in progress.
     * <p>
     * The batch is published before any lease acquisition, so this also covers a batch that is still
     * waiting for a region lease. A shutdown triggered from inside the batch cannot wait for itself and
     * is rejected, because retiring a region while its worker still ticks it is never safe.
     *
     * @param level the level whose batch to wait for, or {@code null} for any level
     */
    private void awaitActiveTickBatches(@Nullable ServerLevel level) {
        while (true) {
            RegionTickBatch pending = null;
            for (final RegionTickBatch active : this.activeTickBatches) {
                if (active.isComplete() || level != null && active.level() != level && !active.holdsServerAdmissionFence()) {
                    continue;
                }
                if (active.isExecutingOnCurrentThread()) {
                    throw new IllegalStateException("Cannot retire region state while its region tick batch is running on the current thread");
                }
                pending = active;
                break;
            }
            if (pending == null) {
                return;
            }
            pending.awaitCompletion();
        }
    }

    private void awaitSubmittedRegionTasks(@Nullable ServerLevel level) {
        final RegionTickBarrier barrier = level == null ? null : levelBarrier(level);
        this.regionizer.forEach(region -> {
            if (level == null || region.coordinate().level() == level) {
                // A submitted worker owns the region until its finally block clears the marker and
                // releases the lease. Waiting for both prevents queue retirement under that worker.
                this.awaitRegionQuiescent(region, barrier);
            }
        });
    }

    static Throwable runBatchCompletion(@Nullable Throwable previousFailure, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            return accumulateFailure(previousFailure, throwable);
        }
        return previousFailure;
    }

    static Throwable accumulateFailure(@Nullable Throwable previousFailure, @Nullable Throwable nextFailure) {
        if (nextFailure == null) return previousFailure;
        if (previousFailure == null) {
            return nextFailure;
        }
        // A mod may reuse a throwable instance. Self-suppression throws IllegalArgumentException and would
        // abort the drain/finally chain, stranding unrelated effects and masking the original failure.
        if (previousFailure != nextFailure) {
            previousFailure.addSuppressed(nextFailure);
        }
        return previousFailure;
    }

    private void ensureRunning() {
        if (this.shutdown) {
            throw new IllegalStateException("Region threading engine is shut down");
        }
    }

    private void rejectLifecycleSelfWait(String action, @Nullable ServerLevel level) {
        if (this.playerNetworkGate.isCallbackOnCurrentThread()) {
            throw new IllegalStateException("Cannot " + action + " from its current network callback");
        }
        if (this.workerPool.isWorkerThread(Thread.currentThread())) {
            throw new IllegalStateException("Cannot " + action + " from a region worker thread");
        }
        if (this.regionWorkAdmission.isOwner()) {
            throw new IllegalStateException("Cannot " + action + " from its canonical phase coordinator");
        }
        for (final var active : this.activeTickBatches) {
            if ((level == null || active.level() == level || active.holdsServerAdmissionFence())
                    && active.isExecutingOnCurrentThread()) {
                throw new IllegalStateException(level == null
                        ? "Cannot shut down region threading from its region tick batch"
                        : "Cannot retire region state while its region tick batch is running on the current thread");
            }
        }
    }

    /**
     * Runs a void admission while holding the lock that shutdown uses to publish its flag.
     */
    private void runAdmitted(Runnable admission) {
        synchronized (this.admissionLock) {
            this.ensureRunning();
            this.runBeforeRegionAdmissionHook();
            admission.run();
        }
    }

    /**
     * Resolves a value admission while holding the lock that shutdown uses to publish its flag.
     */
    private <T> T callAdmitted(Supplier<T> admission) {
        synchronized (this.admissionLock) {
            this.ensureRunning();
            this.runBeforeRegionAdmissionHook();
            return admission.get();
        }
    }

    private void runBeforeRegionAdmissionHook() {
        final Runnable hook = this.beforeRegionAdmission;
        if (hook != null) {
            hook.run();
        }
    }

    /**
     * Marks a region drain worker under the lifecycle gate, or records that an accepted drain is already
     * active and has to be awaited.
     * <p>
     * The idle check, the shutdown flag and {@link RegionState#tryMarkWorkerSubmitted()} are evaluated
     * while the lifecycle read lock is held. Cleanup holds the write lock until it finished clearing, so a
     * drain either publishes its submission before cleanup starts (and cleanup waits for it) or observes
     * the cleared/dead region and rejects the activation.
     */
    private void trySubmitRegionDrain(RegionState region, long nowNanos, RegionTaskBatch submissions, List<RegionState> alreadyActive, boolean tryLifecycleGateOnly) {
        final Runnable hook = this.beforeRegionDrainActivation;
        if (hook != null) {
            hook.run();
        }
        if (!this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
            return;
        }
        try {
            if (this.shutdown || region.isDead()) {
                return;
            }
            if (region.isWorkerSubmitted() || region.isRunning()) {
                if (!region.isWorkerSubmitted()
                        && this.isIndependentRegionTickEnabled()
                        && this.independentTickScheduler != null
                        && this.independentTickScheduler.isRegionInFlight(region.coordinate())) {
                    return;
                }
                // An accepted worker that has not started yet is not a rejection. Await it instead of
                // draining its queue here, which would run the snapshot twice and advance the queue
                // tick twice.
                alreadyActive.add(region);
                return;
            }
            // Admission precedes the asynchronous runner, but the runner checks this same timer again.
            // Avoid submitting a task that is already known to have no due drain. Keep the active-worker
            // checks above this test: an accepted task must still be physically awaited, even if another
            // drain advanced the deadline in the meantime. The timer is guarded by the same monitor as
            // RegionTickRunner, and no deadline is consumed until the original runner actually starts.
            synchronized (region) {
                if (!region.tickTimer().isDue(nowNanos)) {
                    return;
                }
            }
            if (region.tryMarkWorkerSubmitted()) {
                if (this.tryCompleteEmptyRegionDrain(region, nowNanos)) return;
                submissions.add("region drain " + region.coordinate(), () -> this.runSubmittedRegionTick(region, nowNanos));
            } else if (region.isWorkerSubmitted()
                    || (region.isRunning()
                            && !(this.isIndependentRegionTickEnabled()
                                    && this.independentTickScheduler != null
                                    && this.independentTickScheduler.isRegionInFlight(region.coordinate())))) {
                // Another drain won the submission between the idle check and the CAS; await its worker
                // instead of returning while that accepted drain is still running.
                alreadyActive.add(region);
            }
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
    }

    /** A due empty queue needs a logical tick, but has no callback to deliver on a worker. */
    private boolean tryCompleteEmptyRegionDrain(RegionState region, long nowNanos) {
        if (region.taskQueue().pendingCount() != 0 || !region.tryMarkRunning()) return false;
        boolean completed = false;
        final long start = System.nanoTime();
        try {
            synchronized (region) {
                if (!region.tickTimer().isDue(nowNanos) || !region.taskQueue().advanceIfEmpty()) return false;
                region.tickTimer().markTickStarted(nowNanos);
                completed = true;
            }
            region.tickMetrics().recordTick(System.nanoTime() - start);
            return true;
        } finally {
            region.clearRunning();
            if (completed) region.clearWorkerSubmitted();
        }
    }

    /**
     * Acquires the lifecycle read lock for a drain activation.
     * <p>
     * A pool worker or an entity batch thread must never block here: cleanup may hold the write lock
     * while waiting for work only that thread can complete. Its activation is rejected instead and the
     * caller returns to finish its current task.
     */
    boolean acquireLifecycleReadLock(boolean tryOnly) {
        final Lock readLock = this.lifecycleGate.readLock();
        if (tryOnly) {
            return readLock.tryLock();
        }
        readLock.lock();
        return true;
    }

    /**
     * {@return true when the current thread executes a region tick batch that a cleanup may be waiting
     * for}
     */
    private boolean isExecutingTickBatchOnCurrentThread() {
        for (final RegionTickBatch active : this.activeTickBatches) {
            if (active.isExecutingOnCurrentThread()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return true when the current thread already holds a region lease for the tick it is executing}
     * <p>
     * A cleanup may wait for that lease, so the thread must not block on the lifecycle write lock. Its
     * drain or entity tick is rejected (or falls back to inline execution) instead.
     */
    private boolean isExecutingRegionTickOnCurrentThread() {
        return this.currentContext() instanceof BasicRegionThreadingContext basicContext
                && basicContext.kind() == RegionThreadingContext.Kind.REGION
                && basicContext.regionState != null
                && basicContext.regionState.isOwnedByCurrentThread();
    }

    /**
     * Acquires a region lease for a drain while the lifecycle cleanup is excluded. The read lock is
     * released before the queued tasks run, so a cleanup requested from a task cannot wait for a read
     * lock held by the caller; it observes the running lease and waits for it instead.
     */
    private boolean drainRegionStateWithLifecycleGate(RegionState region, boolean tryOnly) {
        if (!this.acquireLifecycleReadLock(tryOnly)) {
            return false;
        }
        final boolean acquired;
        try {
            if (this.shutdown || region.isDead()) {
                return false;
            }
            acquired = region.tryMarkRunning();
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
        if (!acquired) {
            return false;
        }
        this.runRegionState(region);
        return true;
    }

    private boolean drainRegionState(RegionState region) {
        if (region.isDead() || !region.tryMarkRunning()) {
            return false;
        }
        this.runRegionState(region);
        return true;
    }

    private void runRegionState(RegionState region) {
        final RegionCoordinate coordinate = region.coordinate();
        try {
            region.beginOwnership();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, coordinate.level(), coordinate, region, null), () -> this.failedQueuedTaskCount.addAndGet(region.taskQueue().drain()));
        } finally {
            region.endOwnership();
            region.clearRunning();
        }
    }

    /**
     * Runs one worker submission created by the level drain and clears its submitted marker in every case.
     */
    private void runSubmittedRegionTick(RegionState region, long nowNanos) {
        try {
            this.tickRunner.runIfDue(region, nowNanos, () -> this.drainRegionState(region));
        } finally {
            region.clearWorkerSubmitted();
            this.regionizer.removeIfEmpty(region);
        }
    }

    void runWithContext(BasicRegionThreadingContext context, Runnable task) {
        final RegionThreadingContext previousContext = CURRENT_CONTEXT.get();
        // A region context owns its neighbor update collection state, so a worker can neither clear nor
        // extend the update stack of another region or of the server thread. The server thread keeps the
        // shared state outside a region context, including for main-thread handoff work.
        final boolean regionScoped = context.kind() == RegionThreadingContext.Kind.REGION
                || (context.kind() == RegionThreadingContext.Kind.GLOBAL_REGION && this.workerPool.isWorkerThread(Thread.currentThread()));
        final RegionNeighborUpdateScope previousNeighborScope = regionScoped
                ? RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope())
                : null;
        CURRENT_CONTEXT.set(context);
        try {
            task.run();
        } finally {
            CURRENT_CONTEXT.set(previousContext);
            if (regionScoped) {
                RegionNeighborUpdateScope.exit(previousNeighborScope);
            }
        }
    }

    record BasicRegionThreadingContext(
            RegionThreadingContext.Kind kind,
            @Nullable ServerLevel nullableLevel,
            @Nullable RegionCoordinate regionCoordinate,
            @Nullable RegionState regionState,
            @Nullable RegionEntityTickBatch.Group entityTickGroup) implements RegionThreadingContext {
        @Override
        public Optional<ServerLevel> level() {
            return Optional.ofNullable(this.nullableLevel);
        }
    }
}
