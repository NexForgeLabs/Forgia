/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Owner-local/shared-read prerequisites, not callback approval or full player phase scheduling. */
@ApiStatus.Internal
public final class RegionPlayerStatePlan {
    public enum Refusal {
        NONE, DEPENDENCIES, NOT_QUIESCENT, STALE_OWNER, SHARED_MENU, OPTIONAL_PLAYER_STATE,
        UNKNOWN_CRITERION, GLOBAL_REWARD, SCOREBOARD_OBJECTIVES, ITEM_PICKUP_CALLBACKS, XP_SHARED_SOUND,
        SHARED_PLAYER_RESOURCE, STALE_STATE
    }

    public record Capture(@Nullable RegionPlayerStatePlan plan, Refusal refusal) {
        public Capture {
            if ((plan == null) == (refusal == Refusal.NONE)) throw new IllegalArgumentException("Explicit player-state result required");
        }
    }

    /** Advancement records can contain caller-owned mutable criteria, requirements and reward lists. */
    private record AwardSnapshot(AdvancementHolder holder, Map<String, Criterion<?>> criteria, List<List<String>> requirements, AdvancementRewards reward) {
        private AwardSnapshot(AdvancementHolder holder) {
            this(holder, Map.copyOf(holder.value().criteria()), holder.value().requirements().requirements().stream().map(List::copyOf).toList(), holder.value().rewards());
        }

        private boolean current() {
            var advancement = holder.value();
            return advancement.rewards() == reward && simpleReward(reward) && advancement.display().isEmpty()
                    && advancement.criteria().size() == criteria.size()
                    && criteria.entrySet().stream().allMatch(entry -> advancement.criteria().get(entry.getKey()) == entry.getValue())
                    && requirements.equals(advancement.requirements().requirements());
        }
    }

    private final RegionThreadingEngine engine;
    private final RegionPlayerSpatialPlan spatial;
    private final ServerPlayer player;
    private final PlayerAdvancements advancements;
    private final ServerGamePacketListenerImpl listener;
    private final Connection connection;
    private final PlayerCriterionListeners.Snapshot listeners;
    private final long listenerGeneration;
    private final int tick;
    private final List<Object> resources;
    private final Set<ObjectiveCriteria> emptyCriteria;
    private final Scoreboard scoreboard;
    private final long scoreboardRevision;
    private final Map<StatType<?>, Map<?, Stat<?>>> stats;
    private final Map<AdvancementHolder, AwardSnapshot> awards;
    private final Map<ExperienceOrb, Integer> orbs;
    private boolean consumed;
    @Nullable
    private RegionPlayerAdmissionPlan admission;

    void bindAdmission(RegionPlayerAdmissionPlan admission) {
        if (this.admission != null || admission.state() != this) throw new IllegalStateException("Admission must bind its original state exactly once");
        this.admission = admission;
    }

    private int remainingExperience = 7;

    private RegionPlayerStatePlan(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, List<Object> resources,
            Set<ObjectiveCriteria> criteria, Map<StatType<?>, Map<?, Stat<?>>> stats, Map<AdvancementHolder, AwardSnapshot> awards,
            Map<ExperienceOrb, Integer> orbs) {
        this.engine = engine;
        this.spatial = spatial;
        this.player = spatial.player();
        this.advancements = player.getAdvancements();
        this.listener = player.connection;
        this.connection = listener.getConnection();
        this.tick = player.server.getTickCount();
        this.listenerGeneration = advancements.forgiaCriterionListeners().generation();
        this.listeners = advancements.forgiaCriterionListeners().snapshot();
        this.resources = List.copyOf(resources);
        this.emptyCriteria = Set.copyOf(criteria);
        this.scoreboard = player.getScoreboard();
        this.scoreboardRevision = scoreboard.forgiaObjectiveRevision();
        this.stats = Collections.unmodifiableMap(new IdentityHashMap<>(stats));
        this.awards = Collections.unmodifiableMap(new IdentityHashMap<>(awards));
        this.orbs = Collections.unmodifiableMap(new IdentityHashMap<>(orbs));
    }

    public ServerPlayer player() {
        return this.player;
    }

    public RegionPlayerTickCoordinator.Phase phase() {
        return this.spatial.phase();
    }

    List<Object> resources() {
        if (this.admission == null) return this.resources;
        List<Object> combined = new ArrayList<>(this.resources);
        combined.addAll(this.admission.resources());
        return List.copyOf(combined);
    }

    RegionPlayerSpatialPlan spatial() {
        return this.spatial;
    }

    boolean belongsTo(RegionThreadingEngine engine) {
        return this.engine == engine;
    }

    public boolean conflictsWith(RegionPlayerStatePlan other) {
        Set<Object> objects = Collections.newSetFromMap(new IdentityHashMap<>());
        objects.addAll(this.resources());
        return other.resources().stream().anyMatch(objects::contains);
    }

    boolean currentOwner() {
        RegionPlayerSpatialLease lease = RegionPlayerSpatialLease.current();
        boolean activeScope = consumed && lease != null && lease.hasStatePlan(this);
        // Stop-new-admission and the physical channel's open bit are not revocation of an already
        // consumed scope. The original tick observes socket loss; its lifecycle callback remains gated
        // until physical return/join. Exact captured identities and the original lease still apply.
        return (activeScope || !engine.isShutDown()) && player.server.getTickCount() == tick && player.serverLevel() == spatial.footprint().level()
                && player.connection == listener && listener.player == player && listener.getConnection() == connection
                && connection.getPacketListener() == listener && (activeScope || (connection.isConnected() && listener.isAcceptingMessages()))
                && player.getAdvancements() == advancements && advancements.forgiaCriterionListeners().generation() == listenerGeneration;
    }

    /** Recheck on the publishing thread immediately before dispatch; rejection is still pre-body. */
    public boolean revalidate() {
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("State revalidation requires publishing thread");
        return !consumed && spatial.revalidate() && stateCurrent();
    }

    private boolean stateCurrent() {
        return (admission == null || admission.current()) && currentOwner() && player.forgiaHasLocalStateBindings() && player.forgiaHasSimpleSharedState()
                && advancements.forgiaCriterionListeners().matches(listeners) && boundedExperience(player)
                && sameReferences(resources, localResources(player)) && player.getScoreboard() == scoreboard
                && scoreboard.forgiaObjectiveRevision() == scoreboardRevision
                && awards.values().stream().allMatch(AwardSnapshot::current)
                && orbs.entrySet().stream().allMatch(entry -> entry.getKey().getValue() == entry.getValue());
    }

    /** Final unstarted check on the original lease's worker, before any original invocation effects. */
    synchronized boolean consume() {
        if (consumed || !stateCurrent()) return false;
        consumed = true;
        return true;
    }

    boolean owns(Object resource) {
        return resources().stream().anyMatch(value -> value == resource);
    }

    boolean emptyScoreboard(Scoreboard board, ObjectiveCriteria criterion, Object holder) {
        return board == scoreboard && holder == player && emptyCriteria.contains(criterion) && board.forgiaObjectiveRevision() == scoreboardRevision;
    }

    @Nullable
    Stat<?> stat(StatType<?> type, Object key) {
        Map<?, Stat<?>> values = stats.get(type);
        return values == null ? null : values.get(key);
    }

    boolean allowsTrigger(SimpleCriterionTrigger<?> trigger) {
        return trigger == CriteriaTriggers.TICK
                || ((trigger == CriteriaTriggers.LOCATION || trigger == CriteriaTriggers.ENTER_BLOCK)
                        && advancements.forgiaCriterionListeners().empty(trigger));
    }

    boolean allowsAward(AdvancementHolder holder) {
        AwardSnapshot snapshot = awards.get(holder);
        return snapshot != null && snapshot.current();
    }

    boolean allowsReward(AdvancementRewards reward) {
        return simpleReward(reward) && awards.values().stream().anyMatch(snapshot -> snapshot.reward() == reward && snapshot.current());
    }

    boolean takeExperience(int amount) {
        if (amount < 0 || amount > remainingExperience || player.experienceLevel >= 5) return false;
        remainingExperience -= amount;
        return true;
    }

    private static boolean sameReferences(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    static List<Object> localResources(ServerPlayer player) {
        List<Object> values = new ArrayList<>(List.of(player, player.getAdvancements(), player.getAdvancements().forgiaCriterionListeners(),
                player.getStats(), player.getAttributes(), player.getFoodData(), player.getCooldowns(), player.getInventory(), player.inventoryMenu,
                player.connection, player.connection.getConnection(), player.gameMode));
        player.getWardenSpawnTracker().ifPresent(values::add);
        values.addAll(player.getAttributes().forgiaPlayerLocalObjects());
        values.addAll(player.inventoryMenu.forgiaPlayerLocalContainers());
        return values;
    }

    private static boolean simpleReward(AdvancementRewards reward) {
        return reward.loot().isEmpty() && reward.recipes().isEmpty() && reward.function().isEmpty() && reward.experience() >= 0 && reward.experience() <= 7;
    }

    private static boolean boundedExperience(ServerPlayer player) {
        return player.experienceLevel >= 0 && player.experienceLevel <= 3 && player.totalExperience >= 0 && player.totalExperience <= 27
                && Float.isFinite(player.experienceProgress) && player.experienceProgress >= 0 && player.experienceProgress < 1;
    }

    private static Capture reject(Refusal refusal) {
        return new Capture(null, refusal);
    }

    static Capture capture(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial) {
        ServerPlayer player = spatial.player();
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Player state capture requires server thread");
        if (!spatial.belongsTo(engine) || !engine.playerCriterionStorageEnabled()) return reject(Refusal.DEPENDENCIES);
        if (!engine.isQuiescentForParallelApi()) return reject(Refusal.NOT_QUIESCENT);
        if (!spatial.revalidate() || player.getAdvancements() == null || !player.getAdvancements().forgiaCriterionListeners().enabled()
                || player.connection == null || player.server.getPlayerList().getPlayer(player.getUUID()) != player)
            return reject(Refusal.STALE_OWNER);
        if (!player.forgiaHasLocalStateBindings()) return reject(Refusal.SHARED_MENU);
        if (!player.forgiaHasSimpleSharedState()) return reject(Refusal.OPTIONAL_PLAYER_STATE);
        PlayerCriterionListeners storage = player.getAdvancements().forgiaCriterionListeners();
        // doTick increments tickCount before the LOCATION test. Capture the whole empty branch,
        // independent of that counter; optional fall/levitation/vehicle state remains absent.
        if (spatial.phase() == RegionPlayerTickCoordinator.Phase.CONNECTION
                && (!storage.empty(CriteriaTriggers.LOCATION) || !storage.empty(CriteriaTriggers.ENTER_BLOCK)))
            return reject(Refusal.UNKNOWN_CRITERION);
        Map<AdvancementHolder, AwardSnapshot> awards = new IdentityHashMap<>();
        int experience = 0;
        var tick = storage.get(CriteriaTriggers.TICK);
        if (tick != null) for (var listener : tick) {
            if (listener.trigger().getClass() != PlayerTrigger.TriggerInstance.class || listener.trigger().player().isPresent()) return reject(Refusal.UNKNOWN_CRITERION);
            AdvancementHolder holder = listener.advancement();
            if (holder.value().display().isPresent() || holder.value().criteria().values().stream().anyMatch(c -> c.trigger() != CriteriaTriggers.TICK
                    || c.triggerInstance().getClass() != PlayerTrigger.TriggerInstance.class || ((PlayerTrigger.TriggerInstance) c.triggerInstance()).player().isPresent()))
                return reject(Refusal.UNKNOWN_CRITERION);
            var reward = holder.value().rewards();
            if (!simpleReward(reward)) return reject(Refusal.GLOBAL_REWARD);
            if (awards.putIfAbsent(holder, new AwardSnapshot(holder)) == null) {
                experience += reward.experience();
                if (experience > 7) return reject(Refusal.XP_SHARED_SOUND);
            }
        }
        Map<ExperienceOrb, Integer> orbs = new IdentityHashMap<>();
        for (var entry : spatial.members().keySet()) {
            if (!entry.getBoundingBox().intersects(spatial.footprint().queryBounds())) continue;
            if (entry instanceof ItemEntity) return reject(Refusal.ITEM_PICKUP_CALLBACKS);
            if (entry instanceof ExperienceOrb orb) {
                int value = orb.getValue();
                if (value < 0 || value > 7 - experience) return reject(Refusal.XP_SHARED_SOUND);
                experience += value;
                orbs.put(orb, value);
            }
        }
        if (!boundedExperience(player)) return reject(Refusal.XP_SHARED_SOUND);
        // Initialize only mandatory intrinsic stat handles on the publisher. Workers use immutable
        // identity snapshots, and cannot populate shared StatType/ObjectiveCriteria caches.
        Map<ResourceLocation, Stat<?>> warmed = new IdentityHashMap<>();
        for (ResourceLocation value : List.of(Stats.PLAY_TIME, Stats.TOTAL_WORLD_TIME, Stats.TIME_SINCE_DEATH, Stats.CROUCH_TIME, Stats.TIME_SINCE_REST)) warmed.put(value, Stats.CUSTOM.get(value));
        Map<StatType<?>, Map<?, Stat<?>>> stats = new IdentityHashMap<>();
        stats.put(Stats.CUSTOM, Collections.unmodifiableMap(warmed));
        Set<ObjectiveCriteria> criteria = Collections.newSetFromMap(new IdentityHashMap<>());
        criteria.addAll(warmed.values());
        criteria.addAll(List.of(ObjectiveCriteria.HEALTH, ObjectiveCriteria.FOOD, ObjectiveCriteria.AIR, ObjectiveCriteria.ARMOR, ObjectiveCriteria.EXPERIENCE, ObjectiveCriteria.LEVEL));
        Scoreboard board = player.getScoreboard();
        for (ObjectiveCriteria criterion : criteria) if (board.forgiaHasObjectives(criterion)) return reject(Refusal.SCOREBOARD_OBJECTIVES);
        // Materialize all per-player instances so shared attribute templates never initialize lazily.
        for (var attribute : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.holders().toList()) {
            var instance = player.getAttributes().getInstance(attribute);
            if (instance != null) instance.getValue();
        }
        return new Capture(new RegionPlayerStatePlan(engine, spatial, localResources(player), criteria, stats, awards, orbs), Refusal.NONE);
    }
}
