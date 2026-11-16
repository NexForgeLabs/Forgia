/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Diagnostic classification only. Naming a vanilla type NEVER admits its body to a worker. */
final class C7TypePolicy {
    private static final ClassValue<Boolean> PLAIN_TYPE_GETTER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getType").getDeclaringClass() == BlockEntity.class;
            } catch (ReflectiveOperationException refused) {
                return false;
            }
        }
    };

    private C7TypePolicy() {}

    static String type(TickingBlockEntity ticker) {
        if (!C7RuntimeAudit.canReadBindingMetadata()) return "<unverified-metadata>";
        final var subject = LevelChunk.c7TickerSubject(ticker);
        if (subject == null) return "<foreign>";
        if (!PLAIN_TYPE_GETTER.get(subject.getClass())) return "<custom-type-getter>";
        final var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(subject.getType());
        return key == null ? "<unregistered>" : key.toString();
    }

    static C7Refusal unsupported(String type) {
        return switch (type) {
            case "minecraft:hopper", "minecraft:brewing_stand", "minecraft:crafter" -> C7Refusal.INVENTORY_OR_TRANSFER_NOT_SUPPORTED;
            case "minecraft:beacon", "minecraft:conduit", "minecraft:bell", "minecraft:mob_spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:shulker_box", "minecraft:campfire", "minecraft:enchanting_table", "minecraft:end_gateway" -> C7Refusal.ENTITY_ACCESS_NOT_SUPPORTED;
            case "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:sculk_shrieker", "minecraft:sculk_catalyst", "minecraft:daylight_detector", "minecraft:jukebox", "minecraft:piston", "minecraft:brushable_block" -> C7Refusal.WORLD_CALLBACKS_NOT_SUPPORTED;
            case "minecraft:command_block", "minecraft:structure_block", "minecraft:jigsaw" -> C7Refusal.GLOBAL_STATE_NOT_SUPPORTED;
            default -> type.startsWith("minecraft:") ? C7Refusal.NO_DETACHED_PROGRAM : C7Refusal.UNKNOWN_FUNCTION;
        };
    }
}
