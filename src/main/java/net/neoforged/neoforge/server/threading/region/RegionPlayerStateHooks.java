/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/** Shared-state guards are scoped to one original player; no arbitrary server-thread service pump. */
@ApiStatus.Internal
public final class RegionPlayerStateHooks {
    private RegionPlayerStateHooks() {}

    public static void reward(AdvancementRewards reward, ServerPlayer player) {
        if (RegionWorkerScope.isNativeWorkerThread() || RegionPlayerStateScope.hasThreadBinding()) {
            if (reward.function().isPresent() || !reward.loot().isEmpty() || !reward.recipes().isEmpty() || reward.experience() < 0 || reward.experience() > 7)
                throw new IllegalStateException("Unclosed global advancement reward; refuse before XP or progress side effects");
            RegionPlayerStateScope.checkReward(reward, player);
        }
    }
}
