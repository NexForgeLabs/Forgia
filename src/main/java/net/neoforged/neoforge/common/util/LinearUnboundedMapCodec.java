/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.util;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;
import com.mojang.datafixers.util.Unit;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.codecs.BaseMapCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jetbrains.annotations.ApiStatus;

/**
 * Strict unbounded map decoding with hash-based duplicate detection for large saved maps.
 * <p>
 * Keeps DFU's first-value duplicate policy, partial entries, error accumulation, lifecycle and
 * immutable insertion order. Encoding still uses the original {@link BaseMapCodec} implementation.
 * This is deliberately local to audited large maps, rather than changing every mod's codec.
 */
@ApiStatus.Internal
public record LinearUnboundedMapCodec<K, V>(Codec<K> keyCodec, Codec<V> elementCodec) implements BaseMapCodec<K, V>, Codec<Map<K, V>> {
    public LinearUnboundedMapCodec {
        Objects.requireNonNull(keyCodec);
        Objects.requireNonNull(elementCodec);
    }

    @Override
    public <T> DataResult<Map<K, V>> decode(DynamicOps<T> ops, MapLike<T> input) {
        final Map<K, V> read = new LinkedHashMap<>();
        final Stream.Builder<Pair<T, T>> failed = Stream.builder();
        final DataResult<Unit> result = input.entries().reduce(DataResult.success(Unit.INSTANCE, Lifecycle.stable()), (prior, pair) -> {
            final var key = this.keyCodec.parse(ops, pair.getFirst());
            final var value = this.elementCodec.parse(ops, pair.getSecond());
            final var entry = key.apply2stable(Pair::of, value);
            final var decoded = entry.resultOrPartial();
            if (decoded.isPresent()) {
                final var item = decoded.get();
                if (read.putIfAbsent(item.getFirst(), item.getSecond()) != null) {
                    failed.add(pair);
                    return prior.apply2stable((unit, ignored) -> unit,
                            DataResult.error(() -> "Duplicate entry for key: '" + item.getFirst() + "'"));
                }
            }
            if (entry.isError()) failed.add(pair);
            return prior.apply2stable((unit, ignored) -> unit, entry);
        }, (first, second) -> first.apply2stable((unit, ignored) -> unit, second));
        final Map<K, V> elements = ImmutableMap.copyOf(read);
        final T errors = ops.createMap(failed.build());
        return result.map(ignored -> elements).setPartial(elements).mapError(message -> message + " missed input: " + errors);
    }

    @Override
    public <T> DataResult<Pair<Map<K, V>, T>> decode(DynamicOps<T> ops, T input) {
        return ops.getMap(input).setLifecycle(Lifecycle.stable()).flatMap(map -> this.decode(ops, map)).map(map -> Pair.of(map, input));
    }

    @Override
    public <T> DataResult<T> encode(Map<K, V> input, DynamicOps<T> ops, T prefix) {
        return this.encode(input, ops, ops.mapBuilder()).build(prefix);
    }

    @Override
    public String toString() {
        return "LinearUnboundedMapCodec[" + this.keyCodec + " -> " + this.elementCodec + "]";
    }
}
