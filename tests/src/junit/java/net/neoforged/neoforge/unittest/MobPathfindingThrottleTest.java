/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.threading.region.MobPathfindingThrottle;
import org.junit.jupiter.api.Test;

class MobPathfindingThrottleTest {
    @Test
    void parseCooldownsAcceptsValidEntriesAndClampsUpperBound() {
        Map<ResourceLocation, Integer> parsed = MobPathfindingThrottle.parseCooldowns(List.of(
                "primal:shark=40",
                " minecraft:zombie_villager = 20 ",
                "mod:huge=99999",
                "invalid_no_equals",
                "bad:num=abc",
                "bad:zero=0",
                "bad:neg=-5"));

        assertEquals(3, parsed.size());
        assertEquals(40, parsed.get(ResourceLocation.parse("primal:shark")));
        assertEquals(20, parsed.get(ResourceLocation.parse("minecraft:zombie_villager")));
        assertEquals(1200, parsed.get(ResourceLocation.parse("mod:huge")));
    }

    @Test
    void isValidEntryValidatesFormatAndPositiveTicks() {
        assertTrue(MobPathfindingThrottle.isValidEntry("primal:shark=40"));
        assertTrue(MobPathfindingThrottle.isValidEntry("minecraft:zombie=10"));
        assertFalse(MobPathfindingThrottle.isValidEntry("primal:shark=0"));
        assertFalse(MobPathfindingThrottle.isValidEntry("primal:shark=-1"));
        assertFalse(MobPathfindingThrottle.isValidEntry("primal:shark"));
        assertFalse(MobPathfindingThrottle.isValidEntry("=40"));
        assertFalse(MobPathfindingThrottle.isValidEntry(123));
    }

    @Test
    void shouldThrottleRespectsCooldownWindowAndTimeReset() {
        assertFalse(MobPathfindingThrottle.shouldThrottle(Long.MIN_VALUE, 100L, 40));
        assertFalse(MobPathfindingThrottle.shouldThrottle(100L, 100L, 0));
        assertTrue(MobPathfindingThrottle.shouldThrottle(100L, 100L, 40));
        assertTrue(MobPathfindingThrottle.shouldThrottle(100L, 139L, 40));
        assertFalse(MobPathfindingThrottle.shouldThrottle(100L, 140L, 40));
        // If gameTime moved backwards (e.g. world/test reset), do not permanently lock out pathfinding.
        assertFalse(MobPathfindingThrottle.shouldThrottle(100L, 50L, 40));
    }
}
