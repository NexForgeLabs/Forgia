/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.LinearUnboundedMapCodec;
import org.junit.jupiter.api.Test;

class LinearUnboundedMapCodecTest {
    @Test
    void successfulDecodeAndEncodePreserveValuesOrderAndLifecycle() {
        final Codec<Map<String, Integer>> original = Codec.unboundedMap(Codec.STRING, Codec.INT);
        final Codec<Map<String, Integer>> optimized = new LinearUnboundedMapCodec<>(Codec.STRING, Codec.INT);
        final JsonElement json = JsonParser.parseString("{\"third\":3,\"first\":1,\"second\":2}");
        final var expected = original.decode(JsonOps.INSTANCE, json);
        final var actual = optimized.decode(JsonOps.INSTANCE, json);
        assertEquals(expected, actual);
        assertEquals(new ArrayList<>(expected.getOrThrow().getFirst().keySet()), new ArrayList<>(actual.getOrThrow().getFirst().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> actual.getOrThrow().getFirst().put("fourth", 4));
        assertEquals(original.encodeStart(JsonOps.INSTANCE, expected.getOrThrow().getFirst()),
                optimized.encodeStart(JsonOps.INSTANCE, actual.getOrThrow().getFirst()));
        final var nbt = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
        assertEquals(original.decode(NbtOps.INSTANCE, nbt), optimized.decode(NbtOps.INSTANCE, nbt));
    }

    @Test
    void malformedKeysValuesAndNonMapsHaveTheOriginalStrictErrorsAndPartialMap() {
        final Codec<Map<ResourceLocation, Integer>> original = Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT);
        final Codec<Map<ResourceLocation, Integer>> optimized = new LinearUnboundedMapCodec<>(ResourceLocation.CODEC, Codec.INT);
        for (final String json : new String[] {
                "{}", "[]", "42", "null",
                "{\"test:valid\":1,\"INVALID ID\":2,\"test:bad_value\":\"bad\",\"test:later\":4}",
                "{\"same\":1,\"minecraft:same\":2,\"test:later\":3}",
                "{\"same\":1,\"minecraft:same\":\"bad\",\"INVALID ID\":2}"
        }) {
            assertSameResult(original.decode(JsonOps.INSTANCE, JsonParser.parseString(json)),
                    optimized.decode(JsonOps.INSTANCE, JsonParser.parseString(json)));
        }
    }

    @Test
    void partialEntriesRetainTheirFirstValueAndErrors() {
        final Codec<Integer> partial = Codec.INT.flatXmap(value -> value < 0 ? DataResult.error(() -> "negative", -value) : DataResult.success(value), DataResult::success);
        final Codec<Map<String, Integer>> original = Codec.unboundedMap(Codec.STRING, partial);
        final Codec<Map<String, Integer>> optimized = new LinearUnboundedMapCodec<>(Codec.STRING, partial);
        final var input = JsonParser.parseString("{\"first\":-1,\"second\":2,\"third\":-3}");
        assertSameResult(original.decode(JsonOps.INSTANCE, input), optimized.decode(JsonOps.INSTANCE, input));
        assertEquals(Map.of("first", 1, "second", 2, "third", 3), optimized.parse(JsonOps.INSTANCE, input).resultOrPartial().orElseThrow());
    }

    @Test
    void largeMapNoLongerPerformsQuadraticKeyEqualityChecks() {
        final AtomicLong comparisons = new AtomicLong();
        final Codec<CountedKey> key = Codec.STRING.xmap(value -> new CountedKey(value, comparisons), CountedKey::value);
        final int entries = 2048;
        final JsonObject input = new JsonObject();
        for (int index = 0; index < entries; ++index) input.addProperty("entry_" + index, index);
        final var original = Codec.unboundedMap(key, Codec.INT).parse(JsonOps.INSTANCE, input).getOrThrow();
        final long originalComparisons = comparisons.getAndSet(0);
        final var optimized = new LinearUnboundedMapCodec<>(key, Codec.INT).parse(JsonOps.INSTANCE, input).getOrThrow();
        final long optimizedComparisons = comparisons.get();
        assertEquals(entries, optimized.size());
        assertTrue(originalComparisons > (long) entries * entries / 4, "control must exercise DFU's linear duplicate search");
        assertTrue(optimizedComparisons < 16L * entries, "hash duplicate lookup must scale with the map size");
        assertEquals(new ArrayList<>(original.values()), new ArrayList<>(optimized.values()));
    }

    @Test
    void encodingKeepsTheOriginalPrefixAndErrorHandling() {
        final Codec<Map<String, Integer>> original = Codec.unboundedMap(Codec.STRING, Codec.INT);
        final Codec<Map<String, Integer>> optimized = new LinearUnboundedMapCodec<>(Codec.STRING, Codec.INT);
        final Map<String, Integer> values = new LinkedHashMap<>();
        values.put("a", 1);
        values.put("b", 2);
        for (final String prefix : new String[] { "{}", "{\"existing\":3}", "[]", "2" }) {
            final var json = JsonParser.parseString(prefix);
            assertSameResult(original.encode(values, JsonOps.INSTANCE, json), optimized.encode(values, JsonOps.INSTANCE, json));
        }
    }

    private static <T> void assertSameResult(DataResult<T> expected, DataResult<T> actual) {
        assertEquals(expected.result(), actual.result());
        assertEquals(expected.resultOrPartial(), actual.resultOrPartial());
        assertEquals(expected.error().map(DataResult.Error::message), actual.error().map(DataResult.Error::message));
        assertEquals(expected.lifecycle(), actual.lifecycle());
    }

    private record CountedKey(String value, AtomicLong comparisons) {
        @Override
        public boolean equals(Object other) {
            this.comparisons.incrementAndGet();
            return other instanceof CountedKey key && this.value.equals(key.value);
        }

        @Override
        public int hashCode() {
            return this.value.hashCode();
        }
    }
}
