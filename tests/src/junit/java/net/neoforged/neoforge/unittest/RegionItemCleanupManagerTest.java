/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.region.RegionItemCleanupManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RegionItemCleanupManagerTest {
    @Test
    void filtersByMinAgePersistentAndRemovedState() {
        final int minAgeTicks = 60 * RegionItemCleanupManager.TICKS_PER_SECOND; // 1200 ticks

        final ItemEntity fresh = mockItem(1199, false, false);
        final ItemEntity exact = mockItem(1200, false, false);
        final ItemEntity old = mockItem(3000, false, false);
        final ItemEntity persistent = mockItem(RegionItemCleanupManager.PERSISTENT_ITEM_AGE, false, false);
        final ItemEntity removed = mockItem(3000, true, false);

        assertFalse(RegionItemCleanupManager.isEligible(fresh, minAgeTicks, true, Set.of()));
        assertTrue(RegionItemCleanupManager.isEligible(exact, minAgeTicks, true, Set.of()));
        assertTrue(RegionItemCleanupManager.isEligible(old, minAgeTicks, true, Set.of()));
        assertFalse(RegionItemCleanupManager.isEligible(persistent, minAgeTicks, true, Set.of()));
        assertFalse(RegionItemCleanupManager.isEligible(removed, minAgeTicks, true, Set.of()));
    }

    @Test
    void respectsIgnoreNamedItemsFlag() {
        final ItemEntity named = mockItem(2000, false, true);
        assertFalse(RegionItemCleanupManager.isEligible(named, 1200, true, Set.of()));
        assertTrue(RegionItemCleanupManager.isEligible(named, 1200, false, Set.of()));
    }

    @Test
    void configDefaultsToDisabled() {
        assertFalse(ForgiaConfig.SERVER.enableItemCleanup.getDefault());
    }

    private static ItemEntity mockItem(int age, boolean removed, boolean hasCustomName) {
        final ItemEntity item = mock(ItemEntity.class);
        final ItemStack stack = mock(ItemStack.class);
        when(stack.isEmpty()).thenReturn(false);
        when(stack.has(DataComponents.CUSTOM_NAME)).thenReturn(hasCustomName);
        when(item.isRemoved()).thenReturn(removed);
        when(item.getAge()).thenReturn(age);
        when(item.getItem()).thenReturn(stack);
        return item;
    }
}
