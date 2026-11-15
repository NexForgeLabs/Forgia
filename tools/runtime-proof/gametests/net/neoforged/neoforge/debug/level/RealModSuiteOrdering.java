/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Keep intentional failure injection after all zero-failure regressions, without filtering any test. */
@EventBusSubscriber(modid = "neotests")
public final class RealModSuiteOrdering {
    private RealModSuiteOrdering() {}

    @SubscribeEvent
    public static void afterGameTestBatchesAreCollected(ServerStartingEvent event) {
        if (!(event.getServer() instanceof GameTestServer server)) return;
        try {
            final Field field = GameTestServer.class.getDeclaredField("testBatches");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final List<GameTestBatch> batches = (List<GameTestBatch>) field.get(server);
            final List<String> before = batches.stream().map(GameTestBatch::name).toList();
            // List.sort is stable: the complete original registration set and every other order remain.
            batches.sort(Comparator.comparingInt(batch -> batch.name().startsWith("regionC6Failure:") ? 1 : 0));
            if (batches.size() != before.size() || !batches.stream().map(GameTestBatch::name).sorted().toList().equals(before.stream().sorted().toList()))
                throw new AssertionError("C6 suite ordering changed its batch membership");
            System.out.println("C6_SUITE_ORDER completeBatchSetPreserved=true batches=" + batches.size()
                    + " injectedFailureBatches=" + batches.stream().filter(batch -> batch.name().startsWith("regionC6Failure:")).count()
                    + " last=" + (batches.isEmpty() ? "none" : batches.getLast().name()));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot preserve full-suite fault-injection ordering", failure);
        }
    }
}
