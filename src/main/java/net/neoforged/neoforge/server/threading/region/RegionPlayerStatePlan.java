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
    private final Object[] resourceArray;
    private final List<Object> menuResources;
    @Nullable
    private final Template template;
    private final List<Object> sharedResources;
    private final Scoreboard scoreboard;
    private final long scoreboardRevision;
    private final Map<StatType<?>, Map<?, Stat<?>>> stats;
    private final Map<AdvancementHolder, AwardSnapshot> awards;
    private final Map<ExperienceOrb, Integer> orbs;
    private final RegionPlayerInventoryPlan inventory;
    private final RegionPlayerContainerPlan container;
    private final RegionPlayerScoreboardPlan scorePlan;
    private final RegionPlayerMapPlan maps;
    private final RegionPlayerDamagePlan damage;
    private boolean consumed;
    private boolean publisherLocalStateChecked;
    private boolean needsTemplateValidation;
    private boolean invalidTemplate;
    @Nullable
    private RegionPlayerAdmissionPlan admission;

    void bindAdmission(RegionPlayerAdmissionPlan admission) {
        if (this.admission != null || admission.state() != this) throw new IllegalStateException("Admission must bind its original state exactly once");
        this.admission = admission;
    }

    private int remainingExperience = 7;

    private RegionPlayerStatePlan(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, List<Object> resources,
            Map<StatType<?>, Map<?, Stat<?>>> stats, Map<AdvancementHolder, AwardSnapshot> awards,
            Map<ExperienceOrb, Integer> orbs, RegionPlayerInventoryPlan inventory, RegionPlayerContainerPlan container, RegionPlayerScoreboardPlan scorePlan, RegionPlayerMapPlan maps, RegionPlayerDamagePlan damage) {
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
        this.resourceArray = this.resources.toArray();
        this.scoreboard = player.getScoreboard();
        this.scoreboardRevision = scoreboard.forgiaObjectiveRevision();
        this.stats = Collections.unmodifiableMap(new IdentityHashMap<>(stats));
        this.awards = Collections.unmodifiableMap(new IdentityHashMap<>(awards));
        this.orbs = Collections.unmodifiableMap(new IdentityHashMap<>(orbs));
        this.inventory = inventory;
        this.container = container;
        this.scorePlan = scorePlan;
        this.maps = maps;
        this.damage = damage;
        this.menuResources = List.copyOf(container.localResources());
        this.template = null;
        List<Object> shared = new ArrayList<>(container.sharedResources());
        shared.addAll(scorePlan.sharedResources());
        shared.addAll(maps.sharedResources());
        shared.addAll(damage.resources());
        this.sharedResources = List.copyOf(shared);
    }

    /** A reusable recipe for the simple branch. No chunk, lease, ticket, body or foreign entity. */
    @ApiStatus.Internal
    public static final class Template {
        private final PlayerAdvancements advancements;
        private final ServerGamePacketListenerImpl listener;
        private final Connection connection;
        private final PlayerCriterionListeners.Snapshot listeners;
        private final long listenerGeneration;
        private final List<Object> resources;
        private final Object[] resourceArray;
        private final Object[] ownedTable;
        private final int ownedMask;
        private final Scoreboard scoreboard;
        private final long scoreboardRevision;
        private final Map<StatType<?>, Map<?, Stat<?>>> stats;
        private final RegionPlayerInventoryPlan inventory;
        private final List<Object> inventoryResources;
        @Nullable
        private List<Object> admissionResources;
        @Nullable
        private Object adm0, adm1, adm2, adm3;
        @Nullable
        private List<Object> claims;
        private final RegionPlayerContainerPlan container;
        private final RegionPlayerScoreboardPlan scorePlan;
        private final RegionPlayerMapPlan maps;
        private final RegionPlayerDamagePlan damage;

        private Template(RegionPlayerStatePlan source) {
            this.advancements = source.advancements;
            this.listener = source.listener;
            this.connection = source.connection;
            this.listeners = source.listeners;
            this.listenerGeneration = source.listenerGeneration;
            this.resources = source.resources;
            this.resourceArray = source.resourceArray;
            this.scoreboard = source.scoreboard;
            this.scoreboardRevision = source.scoreboardRevision;
            this.stats = source.stats;
            this.inventory = source.inventory;
            this.inventoryResources = List.copyOf(source.inventory.resources());
            this.container = source.container;
            this.scorePlan = source.scorePlan;
            this.maps = source.maps;
            this.damage = source.damage;
            int total = this.resourceArray.length + this.inventoryResources.size();
            int cap = 16;
            while (cap < total * 2) cap <<= 1;
            Object[] table = new Object[cap];
            int mask = cap - 1;
            for (Object obj : this.resourceArray) insertOwned(table, mask, obj);
            for (Object obj : this.inventoryResources) insertOwned(table, mask, obj);
            this.ownedTable = table;
            this.ownedMask = mask;
        }

        private static void insertOwned(Object[] table, int mask, Object obj) {
            if (obj == null) return;
            int slot = System.identityHashCode(obj) & mask;
            while (true) {
                Object existing = table[slot];
                if (existing == null || existing == obj) {
                    table[slot] = obj;
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        boolean containsOwned(Object obj) {
            if (obj == null) return false;
            Object[] table = this.ownedTable;
            int mask = this.ownedMask;
            int slot = System.identityHashCode(obj) & mask;
            while (true) {
                Object existing = table[slot];
                if (existing == obj) return true;
                if (existing == null) return false;
                slot = (slot + 1) & mask;
            }
        }

        @Nullable
        List<Object> reuseAdmissionResources(Object a0, Object a1, Object a2, Object a3) {
            if (this.admissionResources != null && this.adm0 == a0 && this.adm1 == a1 && this.adm2 == a2 && this.adm3 == a3) {
                return this.admissionResources;
            }
            return null;
        }
    }

    private RegionPlayerStatePlan(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, Template template) {
        this.engine = engine;
        this.spatial = spatial;
        this.player = spatial.player();
        this.advancements = template.advancements;
        this.listener = template.listener;
        this.connection = template.connection;
        this.tick = player.server.getTickCount();
        this.listeners = template.listeners;
        this.listenerGeneration = template.listenerGeneration;
        this.resources = template.resources;
        this.resourceArray = template.resourceArray;
        this.menuResources = List.of();
        this.sharedResources = List.of();
        this.scoreboard = template.scoreboard;
        this.scoreboardRevision = template.scoreboardRevision;
        this.stats = template.stats;
        this.awards = Map.of();
        this.orbs = Map.of();
        this.inventory = template.inventory;
        this.template = template;
        this.container = template.container;
        this.scorePlan = template.scorePlan;
        this.maps = template.maps;
        this.damage = template.damage;
    }

    private static boolean simpleTick(RegionPlayerSpatialPlan spatial) {
        ServerPlayer player = spatial.player();
        return spatial.members().size() == 1 && player.containerMenu == player.inventoryMenu && !RegionPlayerDamagePlan.starving(player);
    }

    // Audited movement needs a separate stat handle and scoreboard check for WALK_ONE_CM.
    static final int MOVEMENT_TEMPLATE = 2;

    private static int templateIndex(RegionPlayerSpatialPlan spatial) {
        return RegionPlayerPacketPlan.preparesMovement(spatial.player()) ? MOVEMENT_TEMPLATE : spatial.phase().ordinal();
    }

    @Nullable
    static RegionPlayerStatePlan rebindTick(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial) {
        return rebindTick(engine, spatial, false);
    }

    @Nullable
    static RegionPlayerStatePlan rebindTick(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, boolean deferValidation) {
        if (!simpleTick(spatial)) return null;
        Template template = spatial.player().forgiaPlayerStateTemplate(templateIndex(spatial));
        if (template == null) return null;
        var plan = new RegionPlayerStatePlan(engine, spatial, template);
        // The sealed wave can compare all claim identities in parallel while the bodies are idle.
        plan.needsTemplateValidation = true;
        if (deferValidation) return plan.currentOwner() ? plan : null;
        return plan.validateTemplate() ? plan : null;
    }

    boolean needsTemplateValidation() {
        return this.needsTemplateValidation;
    }

    boolean validateTemplate() {
        if (!this.needsTemplateValidation) return true;
        boolean localOk = ServerPlayer.forgiaMatchesLocalTemplateResources(this.player, this.resourceArray)
                || (this.player.forgiaHasLocalStateBindings() && this.localResourcesCurrent());
        if (!this.currentOwner() || !localOk
                || !this.inventory.current(this.spatial, true) || !this.scorePlan.current()) {
            this.invalidTemplate = true;
            return false;
        }
        this.needsTemplateValidation = false;
        return true;
    }

    boolean validatePrefix(boolean worker) {
        if (!this.validateTemplate()) return false;
        if (!this.spatial.validateTerrain(worker)) {
            this.invalidTemplate = true;
            return false;
        }
        return true;
    }

    /** Invoked on the publisher after the worker lane has physically returned. */
    void discardInvalidTemplate() {
        if (this.invalidTemplate) {
            int index = templateIndex(this.spatial);
            if (this.player.forgiaPlayerStateTemplate(index) == this.template) {
                this.player.forgiaPlayerStateTemplate(index, null);
            }
        }
    }

    static Capture captureTick(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial) {
        var rebound = rebindTick(engine, spatial);
        if (rebound != null) return new Capture(rebound, Refusal.NONE);
        Capture capture = captureFresh(engine, spatial);
        var plan = capture.plan();
        spatial.player().forgiaPlayerStateTemplate(templateIndex(spatial), simpleTick(spatial) && plan != null && plan.sharedResources.isEmpty()
                && plan.menuResources.isEmpty() && plan.awards.isEmpty() && plan.orbs.isEmpty() ? new Template(plan) : null);
        return capture;
    }

    boolean hasOnlyPlainLocalInventory() {
        // Only the already validated isolated template has no pickup, award or map effect.
        return this.publisherLocalStateChecked && !this.maps.hasMap();
    }

    boolean permitsLocalNetworkReturn() {
        return this.template != null && this.spatial.members().size() == 1;
    }

    Connection connection() {
        return this.connection;
    }

    public ServerPlayer player() {
        return this.player;
    }

    public RegionPlayerTickCoordinator.Phase phase() {
        return this.spatial.phase();
    }

    @Nullable
    List<Object> reuseTemplateAdmissionResources(Object a0, Object a1, Object a2, Object a3) {
        return this.template == null ? null : this.template.reuseAdmissionResources(a0, a1, a2, a3);
    }

    List<Object> resources() {
        if (this.template != null && this.admission != null) {
            // Both callers are on the publisher. The rebound plan already compared the live
            // local objects and inventory against the template. Check the other four identities
            // before reusing the immutable resource list for this invocation.
            List<Object> captured = this.admission.resources();
            if (this.template.claims != null && this.template.admissionResources == captured) {
                return this.template.claims;
            }
            if (this.template.claims == null || !sameReferences(this.template.admissionResources, captured)) {
                this.template.claims = this.buildResources();
                this.template.admissionResources = captured;
                if (captured.size() == 4) {
                    this.template.adm0 = captured.get(0);
                    this.template.adm1 = captured.get(1);
                    this.template.adm2 = captured.get(2);
                    this.template.adm3 = captured.get(3);
                }
            }
            return this.template.claims;
        }
        return this.buildResources();
    }

    private List<Object> buildResources() {
        List<Object> combined = new ArrayList<>(this.resources);
        combined.addAll(this.template == null ? this.inventory.resources() : this.template.inventoryResources);
        combined.addAll(this.menuResources);
        combined.addAll(this.sharedResources());
        if (this.admission != null) combined.addAll(this.admission.resources());
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
        return this.currentOwner(RegionPlayerSpatialLease.current());
    }

    boolean currentOwner(@Nullable RegionPlayerSpatialLease lease) {
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
        return !consumed && spatial.revalidate() && stateCurrent(true);
    }

    // The immediately preceding spatial reservation already validated the full footprint.
    boolean revalidateLocal() {
        return !consumed && stateCurrent(false);
    }

    /** The sole indexed entity in this scope has no recipients or shared state. Its mutable
     * local objects are server-owned until submission, then owned exclusively until return. */
    void completeSealedLocalCheck() {
        if (this.template == null || this.spatial.members().size() != 1) return;
        this.publisherLocalStateChecked = true;
    }

    private boolean stateCurrent(boolean beforeBody) {
        return (admission == null || admission.current(beforeBody)) && currentOwner() && player.forgiaHasLocalStateBindings() && player.forgiaHasSimpleSharedState()
                && advancements.forgiaCriterionListeners().matches(listeners) && boundedExperience(player)
                && localResourcesCurrent() && player.getScoreboard() == scoreboard
                && scoreboard.forgiaObjectiveRevision() == scoreboardRevision
                && awards.values().stream().allMatch(AwardSnapshot::current)
                && orbsCurrent() && inventory.current(spatial, this.template != null) && container.current() && scorePlan.current() && maps.currentAfterInventory() && damage.current();
    }

    boolean allowsPickupRemoval(net.minecraft.world.entity.Entity entity) {
        return consumed && (entity instanceof ExperienceOrb orb && orbs.containsKey(orb)
                || entity instanceof ItemEntity item && inventory.captures(item));
    }

    private boolean orbsCurrent() {
        int candidates = 0;
        for (var target : spatial.members().keySet()) {
            if (target instanceof ExperienceOrb orb && target.getBoundingBox().intersects(spatial.footprint().queryBounds())) {
                Integer captured = orbs.get(orb);
                if (captured == null || orb.getValue() != captured) return false;
                candidates++;
            }
        }
        return candidates == orbs.size();
    }

    /** Final unstarted check on the original lease's worker, before any original invocation effects. */
    synchronized boolean consume() {
        return consume(false);
    }

    synchronized boolean consume(boolean laneExtensionsVerified) {
        if (consumed || this.needsTemplateValidation) return false;
        boolean current = this.publisherLocalStateChecked
                ? admission != null && admission.current(true, laneExtensionsVerified) && currentOwner() && player.forgiaHasSimpleSharedState()
                        && advancements.forgiaCriterionListeners().matches(listeners) && boundedExperience(player)
                        && (laneExtensionsVerified ? player.containerMenu == player.inventoryMenu : container.current() && scorePlan.current())
                        && damage.current()
                : stateCurrent(true);
        if (!current) return false;
        consumed = true;
        return true;
    }

    List<Object> sharedResources() {
        return this.sharedResources;
    }

    private static boolean hasReference(Object[] values, Object resource) {
        for (int i = 0; i < values.length; i++) if (values[i] == resource) return true;
        return false;
    }

    private static boolean hasReference(List<Object> values, Object resource) {
        for (int i = 0; i < values.size(); i++) if (values.get(i) == resource) return true;
        return false;
    }

    boolean shares(Object resource) {
        return hasReference(this.sharedResources, resource);
    }

    RegionPlayerConflictPlan.Entry conflictEntry() {
        var entry = spatial.conflictEntry();
        return new RegionPlayerConflictPlan.Entry(entry.player(), entry.home(), entry.footprint(), entry.targets(), sharedResources());
    }

    boolean owns(Object resource) {
        // Fast O(1) identity hash lookup for the template-owned local objects and inventory.
        if (this.template != null && this.template.containsOwned(resource)) return true;
        if (this.admission != null && hasReference(this.admission.resources(), resource)) return true;
        if (this.template != null) return this.inventory.owns(resource);
        // Checking a permission must not rebuild the entire claim list. Inventory can change
        // during a captured pickup or menu click, so its original membership is still read live.
        return hasReference(this.resourceArray, resource) || hasReference(this.menuResources, resource)
                || shares(resource) || this.inventory.owns(resource);
    }

    boolean emptyScoreboard(Scoreboard board, ObjectiveCriteria criterion, Object holder) {
        return scorePlan.allows(board, criterion, holder) && scorePlan.empty(criterion);
    }

    net.minecraft.world.level.saveddata.maps.MapItemSavedData mapData(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.saveddata.maps.MapId id) {
        return maps.require(level, id);
    }

    void checkDamage(net.minecraft.world.entity.Entity target, net.minecraft.world.damagesource.DamageSource source, float amount) {
        damage.take(target, source, amount);
    }

    void checkMapCarrier(net.minecraft.world.level.saveddata.maps.MapItemSavedData data, net.minecraft.world.entity.player.Player player) {
        maps.checkCarrier(data, player);
    }

    boolean allowsScoreboard(Scoreboard board, ObjectiveCriteria criterion, Object holder) {
        return scorePlan.allows(board, criterion, holder);
    }

    boolean allowsObjective(Scoreboard board, Object holder, net.minecraft.world.scores.Objective objective) {
        return scorePlan.allows(board, holder, objective);
    }

    @Nullable
    Stat<?> stat(StatType<?> type, Object key) {
        Map<?, Stat<?>> values = stats.get(type);
        return values == null ? null : values.get(key);
    }

    boolean allowsTrigger(SimpleCriterionTrigger<?> trigger) {
        return trigger == CriteriaTriggers.TICK
                || ((trigger == CriteriaTriggers.LOCATION || trigger == CriteriaTriggers.ENTER_BLOCK || trigger == CriteriaTriggers.INVENTORY_CHANGED || trigger == CriteriaTriggers.ENTITY_HURT_PLAYER)
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

    private static boolean sameReferences(Object[] a, ArrayList<Object> b) {
        int size = a.length;
        if (size != b.size()) return false;
        for (int i = 0; i < size; i++) if (a[i] != b.get(i)) return false;
        return true;
    }

    private static boolean sameReferences(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static final ThreadLocal<ArrayList<Object>> RESOURCE_CHECK = ThreadLocal.withInitial(ArrayList::new);

    private boolean localResourcesCurrent() {
        if (this.template == null) return sameReferences(this.resources, localResources(this.player));
        final Thread thread = Thread.currentThread();
        ArrayList<Object> current = thread instanceof RegionWorkerThread wt ? wt.resourceCheckScratch() : RESOURCE_CHECK.get();
        try {
            // Each reference is still read from its original holder. Scratch storage is cleared
            // before returning and cannot pin players, listeners or stacks between calls.
            var player = this.player;
            current.add(player);
            current.add(player.getAdvancements());
            current.add(player.getAdvancements().forgiaCriterionListeners());
            current.add(player.getStats());
            current.add(player.getAttributes());
            current.add(player.getFoodData());
            current.add(player.getCooldowns());
            current.add(player.getInventory());
            current.add(player.inventoryMenu);
            current.add(player.connection);
            current.add(player.connection.getConnection());
            current.add(player.gameMode);
            player.getWardenSpawnTracker().ifPresent(current::add);
            player.getAttributes().forgiaCollectPlayerLocalAttributes(current);
            player.inventoryMenu.forgiaCollectPlayerLocalContainers(current);
            return sameReferences(this.resourceArray, current);
        } finally {
            current.clear();
        }
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
        return capture(engine, spatial, false);
    }

    /** The caller just captured geometry and has not run any original body or callback. */
    static Capture captureFresh(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial) {
        return capture(engine, spatial, true);
    }

    private static Capture capture(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, boolean fresh) {
        ServerPlayer player = spatial.player();
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Player state capture requires server thread");
        if (!spatial.belongsTo(engine) || !engine.playerCriterionStorageEnabled()) return reject(Refusal.DEPENDENCIES);
        if (!fresh && !engine.isQuiescentForParallelApi()) return reject(Refusal.NOT_QUIESCENT);
        if ((!fresh && !spatial.revalidate()) || player.getAdvancements() == null || !player.getAdvancements().forgiaCriterionListeners().enabled()
                || player.connection == null || player.server.getPlayerList().getPlayer(player.getUUID()) != player)
            return reject(Refusal.STALE_OWNER);
        if (!player.forgiaHasLocalStateBindings()) return reject(Refusal.SHARED_MENU);
        RegionPlayerContainerPlan container = RegionPlayerContainerPlan.capture(player);
        if (container == null) return reject(Refusal.SHARED_MENU);
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
            // Exact item/capacity closure is captured below; unknown callbacks are rejected by admission.
            if (entry instanceof ExperienceOrb orb) {
                int value = orb.getValue();
                if (value < 0 || value > 7 - experience) return reject(Refusal.XP_SHARED_SOUND);
                experience += value;
                orbs.put(orb, value);
            }
        }
        RegionPlayerInventoryPlan inventory = RegionPlayerInventoryPlan.capture(spatial);
        if (inventory == null) return reject(Refusal.ITEM_PICKUP_CALLBACKS);
        RegionPlayerMapPlan maps = RegionPlayerMapPlan.capture(player);
        if (maps == null) return reject(Refusal.ITEM_PICKUP_CALLBACKS);
        RegionPlayerDamagePlan damage = RegionPlayerDamagePlan.capture(spatial);
        if (damage == null) return reject(Refusal.OPTIONAL_PLAYER_STATE);
        if (RegionPlayerDamagePlan.starving(player) && !storage.empty(CriteriaTriggers.ENTITY_HURT_PLAYER)) return reject(Refusal.UNKNOWN_CRITERION);
        if (!storage.empty(CriteriaTriggers.INVENTORY_CHANGED)) return reject(Refusal.UNKNOWN_CRITERION);
        if (!boundedExperience(player)) return reject(Refusal.XP_SHARED_SOUND);
        // Initialize only mandatory intrinsic stat handles on the publisher. Workers use immutable
        // identity snapshots, and cannot populate shared StatType/ObjectiveCriteria caches.
        Map<ResourceLocation, Stat<?>> warmed = new IdentityHashMap<>();
        for (ResourceLocation value : List.of(Stats.PLAY_TIME, Stats.TOTAL_WORLD_TIME, Stats.TIME_SINCE_DEATH, Stats.CROUCH_TIME, Stats.TIME_SINCE_REST)) warmed.put(value, Stats.CUSTOM.get(value));
        if (RegionPlayerPacketPlan.preparesMovement(player)) warmed.put(Stats.WALK_ONE_CM, Stats.CUSTOM.get(Stats.WALK_ONE_CM));
        if (RegionPlayerDamagePlan.starving(player)) warmed.put(Stats.DAMAGE_TAKEN, Stats.CUSTOM.get(Stats.DAMAGE_TAKEN));
        Map<StatType<?>, Map<?, Stat<?>>> stats = new IdentityHashMap<>();
        stats.put(Stats.CUSTOM, Collections.unmodifiableMap(warmed));
        Set<ObjectiveCriteria> criteria = Collections.newSetFromMap(new IdentityHashMap<>());
        criteria.addAll(warmed.values());
        Map<net.minecraft.world.item.Item, Stat<?>> pickedUp = new IdentityHashMap<>();
        for (var target : spatial.members().keySet()) if (target instanceof ItemEntity item && inventory.captures(item))
            pickedUp.put(item.getItem().getItem(), Stats.ITEM_PICKED_UP.get(item.getItem().getItem()));
        stats.put(Stats.ITEM_PICKED_UP, Collections.unmodifiableMap(pickedUp));
        criteria.addAll(pickedUp.values());
        criteria.addAll(List.of(ObjectiveCriteria.HEALTH, ObjectiveCriteria.FOOD, ObjectiveCriteria.AIR, ObjectiveCriteria.ARMOR, ObjectiveCriteria.EXPERIENCE, ObjectiveCriteria.LEVEL));
        Scoreboard board = player.getScoreboard();
        RegionPlayerScoreboardPlan scorePlan = RegionPlayerScoreboardPlan.capture(player, criteria);
        if (scorePlan == null) return reject(Refusal.SCOREBOARD_OBJECTIVES);
        // Materialize all per-player instances once; dirty values subsequently recompute against
        // the same owned instances. The resource claim still observes all current instances.
        engine.warmPlayerAttributes(player.getAttributes());
        return new Capture(new RegionPlayerStatePlan(engine, spatial, localResources(player), stats, awards, orbs, inventory, container, scorePlan, maps, damage), Refusal.NONE);
    }
}
