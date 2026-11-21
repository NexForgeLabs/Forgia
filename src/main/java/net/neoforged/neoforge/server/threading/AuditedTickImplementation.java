/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Implementation integrity for adapter-supplied audits. Contains no target-mod knowledge or permissions. */
final class AuditedTickImplementation {
    private AuditedTickImplementation() {}

    record Result(boolean valid, String reason) {}

    static Result verify(AuditedBlockEntityTickAdapter adapter, AuditedBlockEntityTickAdapter.Audit audit) {
        final Map<Class<?>, AuditedBlockEntityTickAdapter.Implementation> implementations = new IdentityHashMap<>();
        try {
            for (final var implementation : audit.implementations()) {
                if (implementations.put(implementation.type(), implementation) != null) return refused("DUPLICATE_IMPLEMENTATION");
            }
            if (!hasHierarchy(adapter.getClass(), Object.class, implementations)) return refused("MISSING_ADAPTER_IMPLEMENTATION");
            for (final Class<?> source : audit.sourceClasses()) {
                if (!BlockEntity.class.isAssignableFrom(source) || source == BlockEntity.class
                        || !hasHierarchy(source, BlockEntity.class, implementations))
                    return refused("MISSING_SOURCE_IMPLEMENTATION");
            }
            for (final Class<?> ticker : audit.tickerClasses()) {
                if (!BlockEntityTicker.class.isAssignableFrom(ticker) || !hasHierarchy(ticker, Object.class, implementations))
                    return refused("MISSING_TICKER_IMPLEMENTATION");
                // This first contract exposes source-owned roots, not a live function object's
                // private storage. A stateful function needs a richer binding-inspection API.
                for (Class<?> current = ticker; current != Object.class; current = current.getSuperclass()) {
                    for (final var field : current.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())) return refused("STATEFUL_TICKER_UNSUPPORTED");
                    }
                }
            }
            for (final var implementation : implementations.values()) {
                final Class<?> type = implementation.type();
                try (final var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(implementation.sha256()))
                        return refused("IMPLEMENTATION_CHANGED");
                }
                for (final var method : type.getDeclaredMethods()) {
                    final var merged = method.getAnnotation(MixinMerged.class);
                    if (merged != null && !implementation.allowedMixins().contains(merged.mixin()))
                        return refused("UNVERIFIED_TRANSFORMATION(" + type.getSimpleName() + ":" + merged.mixin() + ")");
                }
            }
            return new Result(true, "VERIFIED_IMPLEMENTATIONS");
        } catch (IOException | NoSuchAlgorithmException | RuntimeException | LinkageError unavailable) {
            return refused("IMPLEMENTATION_UNAVAILABLE");
        }
    }

    private static boolean hasHierarchy(Class<?> type, Class<?> stop, Map<Class<?>, ?> implementations) {
        for (Class<?> current = type; current != stop; current = current.getSuperclass()) {
            if (current == null || !implementations.containsKey(current)) return false;
        }
        return true;
    }

    private static Result refused(String reason) {
        return new Result(false, reason);
    }
}
