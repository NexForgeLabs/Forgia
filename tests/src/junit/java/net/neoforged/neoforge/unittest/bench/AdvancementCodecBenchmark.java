/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.LinearUnboundedMapCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Same real advancement value codec, generated private inputs; mechanism measurement, not server MSPT. */
@Tag("benchmark")
class AdvancementCodecBenchmark {
    private static volatile int sink;

    @Test
    void pairedStrictAdvancementMapDecoding() {
        final Codec<Map<ResourceLocation, AdvancementProgress>> original = Codec.unboundedMap(ResourceLocation.CODEC, AdvancementProgress.CODEC);
        final Codec<Map<ResourceLocation, AdvancementProgress>> optimized = new LinearUnboundedMapCodec<>(ResourceLocation.CODEC, AdvancementProgress.CODEC);
        final var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        for (final int entries : new int[] { 1024, 8192 }) {
            final var input = new JsonObject();
            for (int index = 0; index < entries; ++index) {
                final var criteria = new JsonObject();
                criteria.addProperty("complete", "2026-11-16 00:00:00 +0000");
                final var progress = new JsonObject();
                progress.add("criteria", criteria);
                progress.addProperty("done", true);
                input.add("example:progress_" + index, progress);
            }
            final var before = original.parse(JsonOps.INSTANCE, input).getOrThrow();
            final var after = optimized.parse(JsonOps.INSTANCE, input).getOrThrow();
            assertEquals(entries, before.size());
            assertEquals(entries, after.size());
            assertEquals(original.encodeStart(JsonOps.INSTANCE, before).getOrThrow(), optimized.encodeStart(JsonOps.INSTANCE, after).getOrThrow());
            for (int warmup = 0; warmup < 4; ++warmup) {
                sink = original.parse(JsonOps.INSTANCE, input).getOrThrow().size();
                sink = optimized.parse(JsonOps.INSTANCE, input).getOrThrow().size();
            }
            final double[][] timings = new double[2][6];
            final long[][] allocation = new long[2][6];
            for (int repeat = 0; repeat < 6; ++repeat) {
                for (int position = 0; position < 2; ++position) {
                    final int condition = (position + repeat) % 2;
                    final var codec = condition == 0 ? original : optimized;
                    final long bytes = bean.getCurrentThreadAllocatedBytes();
                    final long start = System.nanoTime();
                    final var decoded = codec.parse(JsonOps.INSTANCE, input).getOrThrow();
                    timings[condition][repeat] = (System.nanoTime() - start) / 1_000_000.0;
                    allocation[condition][repeat] = bean.getCurrentThreadAllocatedBytes() - bytes;
                    assertEquals(entries, decoded.size());
                    sink = decoded.size();
                    System.out.printf(Locale.ROOT, "ADVANCEMENT_CODEC entries=%d repeat=%d optimized=%s ms=%.3f allocatedBytes=%d%n",
                            entries, repeat, condition == 1, timings[condition][repeat], allocation[condition][repeat]);
                }
            }
            for (int condition = 0; condition < 2; ++condition) {
                Arrays.sort(timings[condition]);
                Arrays.sort(allocation[condition]);
                System.out.printf(Locale.ROOT, "ADVANCEMENT_CODEC_SUMMARY entries=%d optimized=%s medianMs=%.3f minMs=%.3f maxMs=%.3f medianAllocatedBytes=%d%n",
                        entries, condition == 1, (timings[condition][2] + timings[condition][3]) / 2,
                        timings[condition][0], timings[condition][5], (allocation[condition][2] + allocation[condition][3]) / 2);
            }
        }
    }
}
