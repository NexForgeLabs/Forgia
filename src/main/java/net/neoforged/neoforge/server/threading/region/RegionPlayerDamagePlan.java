/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageEffects;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** One nonfatal original self-starvation hit, with the whole notification and sound recipient footprint. */
final class RegionPlayerDamagePlan {
    private record Registry(LevelChunk chunk, int sectionY, EuclideanGameEventListenerRegistry registry) {}

    private final ServerPlayer player;
    @Nullable
    private final DamageSource source;
    private final List<Registry> registries;
    private final List<Object> resources;
    private int remaining = 1;

    private RegionPlayerDamagePlan(ServerPlayer player, @Nullable DamageSource source, List<Registry> registries, List<Object> resources) {
        this.player = player;
        this.source = source;
        this.registries = List.copyOf(registries);
        this.resources = List.copyOf(resources);
    }

    static boolean starving(ServerPlayer player) {
        return player.getFoodData() != null && player.getFoodData().getFoodLevel() == 0;
    }

    static boolean supportedFood(ServerPlayer player) {
        return player.getFoodData().getFoodLevel() >= 2 || starving(player) && intrinsic(player);
    }

    static AABB extraLeaseBounds(ServerPlayer player, AABB original) {
        if (!starving(player)) return original;
        // GameEventDispatcher enumerates block-position +/- notification radius, inclusive.
        int radius = GameEvent.ENTITY_DAMAGE.value().notificationRadius();
        var pos = player.blockPosition();
        return original.minmax(new AABB(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius));
    }

    private static boolean intrinsic(ServerPlayer player) {
        try {
            var players = player.server.getPlayerList().getPlayers();
            if (players.size() != 1 || players.getFirst() != player || player.getHealth() != 20 || player.getMaxHealth() != 20
                    || player.getAbsorptionAmount() != 0 || player.isInvulnerable() || player.getAbilities().invulnerable
                    || player.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL
                    || player.invulnerableTime > 0 || (int) value(player, ServerPlayer.class, "spawnInvulnerableTime") > 0
                    || !player.getLastClimbablePos().isEmpty() || !((Collection<?>) value(player, LivingEntity.class, "damageContainers")).isEmpty()
                    || player.getFoodData().getSaturationLevel() != 0 || !Float.isFinite(player.getFoodData().getExhaustionLevel())
                    || player.getFoodData().getExhaustionLevel() < 0 || player.getFoodData().getExhaustionLevel() > 4
                    || (int) value(player.getFoodData(), FoodData.class, "tickTimer") < 0
                    || (int) value(player.getFoodData(), FoodData.class, "tickTimer") > 79)
                return false;
            var source = player.damageSources().starve();
            if (source.getClass() != DamageSource.class || !source.is(DamageTypes.STARVE) || source.getEntity() != null || source.getDirectEntity() != null
                    || source.getSourcePosition() != null || source.type().exhaustion() != 0 || !source.type().msgId().equals("starve")
                    || source.type().scaling() != DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER
                    || source.type().scaling().getScalingFunction() != net.neoforged.neoforge.common.damagesource.IScalingFunction.DEFAULT
                    || source.type().effects() != DamageEffects.HURT
                    || !source.is(DamageTypeTags.BYPASSES_ARMOR) || !source.is(DamageTypeTags.BYPASSES_EFFECTS) || !source.is(DamageTypeTags.NO_KNOCKBACK)
                    || source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypeTags.IS_FREEZING)
                    || source.is(DamageTypeTags.DAMAGES_HELMET) || GameEvent.ENTITY_DAMAGE.value().notificationRadius() != 16)
                return false;
            var chunkMap = player.serverLevel().getChunkSource().chunkMap;
            return chunkMap != null && chunkMap.forgiaHasNoEntityWatchers(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Nullable
    static RegionPlayerDamagePlan capture(RegionPlayerSpatialPlan spatial) {
        ServerPlayer player = spatial.player();
        if (!starving(player)) return new RegionPlayerDamagePlan(player, null, List.of(), List.of());
        if (!intrinsic(player)) return null;
        List<Registry> registries = new ArrayList<>();
        List<Object> resources = new ArrayList<>();
        try {
            resources.add(value(player, LivingEntity.class, "damageContainers"));
            var pos = player.blockPosition();
            int radius = GameEvent.ENTITY_DAMAGE.value().notificationRadius();
            for (int x = SectionPos.blockToSectionCoord(pos.getX() - radius); x <= SectionPos.blockToSectionCoord(pos.getX() + radius); x++) {
                for (int z = SectionPos.blockToSectionCoord(pos.getZ() - radius); z <= SectionPos.blockToSectionCoord(pos.getZ() + radius); z++) {
                    LevelChunk chunk = spatial.chunks().get(net.minecraft.world.level.ChunkPos.asLong(x, z));
                    if (chunk == null) return null;
                    for (int y = SectionPos.blockToSectionCoord(pos.getY() - radius); y <= SectionPos.blockToSectionCoord(pos.getY() + radius); y++) {
                        var registry = chunk.getListenerRegistry(y); // Original lazy registry cache is materialized on the publisher.
                        if (registry.getClass() != EuclideanGameEventListenerRegistry.class || !empty((EuclideanGameEventListenerRegistry) registry)) return null;
                        registries.add(new Registry(chunk, y, (EuclideanGameEventListenerRegistry) registry));
                        resources.add(registry);
                        resources.add(value(registry, EuclideanGameEventListenerRegistry.class, "listeners"));
                        resources.add(value(registry, EuclideanGameEventListenerRegistry.class, "listenersToAdd"));
                        resources.add(value(registry, EuclideanGameEventListenerRegistry.class, "listenersToRemove"));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
        return new RegionPlayerDamagePlan(player, player.damageSources().starve(), registries, resources);
    }

    private static boolean empty(EuclideanGameEventListenerRegistry registry) throws ReflectiveOperationException {
        return registry.isEmpty() && !(boolean) value(registry, EuclideanGameEventListenerRegistry.class, "processing")
                && ((Collection<?>) value(registry, EuclideanGameEventListenerRegistry.class, "listenersToAdd")).isEmpty()
                && ((Collection<?>) value(registry, EuclideanGameEventListenerRegistry.class, "listenersToRemove")).isEmpty();
    }

    boolean current() {
        if (source == null) return !starving(player);
        if (!starving(player) || !intrinsic(player) || player.damageSources().starve() != source) return false;
        try {
            for (var entry : registries) if (entry.chunk().getListenerRegistry(entry.sectionY()) != entry.registry() || !empty(entry.registry())) return false;
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    void take(Entity target, DamageSource source, float amount) {
        if (target != player || this.source == null || source != this.source || amount != 1 || remaining-- != 1)
            throw new IllegalStateException("Uncaptured original player damage or exhausted nonfatal damage budget");
    }

    List<Object> resources() {
        return resources;
    }

    private static Object value(Object object, Class<?> type, String name) throws ReflectiveOperationException {
        return RegionPlayerFieldAccess.read(object, type, name);
    }
}
