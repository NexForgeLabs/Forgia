/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Opt-in contract for an adapter's audited, complete original ticker branch. Register through
 * {@link ParallelApi.Registrar#registerBlockEntityTicks}; require AUDITED_BLOCK_ENTITY_TICKS first.
 * <p>
 * The adapter owns the target-mod audit: exact implementations and transformations, complete branch
 * conditions, mutable roots, aliases and every inherited/behaviour/callback effect. Core checks the
 * registered audit's integrity, actual binding/lifecycle, fresh footprint and region exclusion before
 * issuing its existing invocation Permit. It does not infer semantic safety from bytecode hashes.
 * Installing such an adapter trusts that implementation's audit; this is not a JVM sandbox or an
 * automatic proof against arbitrary asynchronous/reflective writers.
 * <p>
 * No replacement body, executor, Runnable, commit callback or permission constructor is exposed.
 * Unknown calls retain their whole original serial ticker. Started bodies are never replayed.
 */
@ApiStatus.Experimental
public interface AuditedBlockEntityTickAdapter extends BlockEntityTickAdapter {
    /** Captured once during server registration. This declaration is not execution authority. */
    Audit audit();

    /**
     * Read-only preparation for the canonical original-ticker path, while the server is quiescent.
     * The legacy explicit driver continues to use prepare. No success may be cached across fences.
     */
    default void prepareCanonical(ServerLevel level) {
        this.prepare(level);
    }

    /**
     * Return null unless the whole original branch is closed under region ownership. Called at the
     * server cursor after batch publication and again under the held region before any prefix body starts. Read only this
     * source's owned fields and immutable metadata: no provider/world lookup, callbacks, mutation, RNG,
     * scheduling or blocking. Do not retain the source. A fresh result must list the same backing roots
     * and access modes; changed identities refuse the unstarted prefix.
     *
     * @param tickerImplementation exact class of the actual function captured by the original Core wrapper
     */
    @Nullable
    Proof inspect(BlockEntity source, Class<?> tickerImplementation);

    /** Runtime implementation integrity; the allowed merged Mixins are part of the adapter's audit. */
    record Implementation(Class<?> type, String sha256, Set<String> allowedMixins) {
        public Implementation {
            Objects.requireNonNull(type);
            Objects.requireNonNull(sha256);
            allowedMixins = Set.copyOf(allowedMixins);
            if (!sha256.matches("[0-9a-f]{64}") || allowedMixins.size() > 64
                    || allowedMixins.stream().anyMatch(name -> name.isBlank() || name.length() > 256))
                throw new IllegalArgumentException("Invalid implementation fingerprint");
        }
    }

    /**
     * The manifest must include the adapter, exact source/function classes, their non-Core ancestors
     * and every implementation reached by a candidate branch. Completeness is the adapter's reviewed
     * responsibility; Core rejects missing exact source/function ancestry and unknown transformations.
     */
    record Audit(String reference, String revision, Set<Class<?>> sourceClasses, Set<Class<?>> tickerClasses,
            List<Implementation> implementations) {
        public Audit {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(revision);
            sourceClasses = Set.copyOf(sourceClasses);
            tickerClasses = Set.copyOf(tickerClasses);
            implementations = List.copyOf(implementations);
            if (reference.isBlank() || reference.length() > 512 || revision.isBlank() || revision.length() > 128
                    || sourceClasses.isEmpty() || sourceClasses.size() > 128 || tickerClasses.isEmpty() || tickerClasses.size() > 128
                    || implementations.isEmpty() || implementations.size() > 256)
                throw new IllegalArgumentException("Invalid audited-ticker manifest");
        }
    }

    /** Frozen registration integrity, separate from actual body starts/completions in the v1 counters. */
    record Status(net.minecraft.resources.ResourceLocation id, String reference, String revision, boolean verified, String reason) {}

    enum Access {
        READ, WRITE
    }

    /** Actual backing-root identity, compared by reference without invoking equals/hashCode. */
    record Storage(Object identity, Access access) {
        public Storage {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(access);
        }
    }

    /**
     * One branch's complete instance-local footprint. Include the exact source as WRITE and every
     * mutable root the original branch reaches. Shared read-only roots use READ. Foreign/world-wide
     * writes, unknown callbacks and incomplete external-writer control require a null inspection.
     * Core separates any READ/WRITE alias into a later prefix before starting either body's wave,
     * including same-region writes that could invalidate a later branch.
     */
    record Proof(List<Storage> storage) {
        public Proof {
            storage = List.copyOf(storage);
            if (storage.isEmpty() || storage.size() > 64) throw new IllegalArgumentException("Invalid audited-ticker footprint");
        }
    }
}
