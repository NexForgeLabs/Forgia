/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.Objects;

/**
 * A physical resource, scoped to its actual server/level lifetime. Keys compare by object identity,
 * never by a packed position alone. Aliased handler views MUST use the same underlying identity.
 */
public final class C7Resource {
    public enum Kind {
        BLOCK_ENTITY, BLOCK_STATE, CHUNK, INVENTORY, ITEM_HANDLER, FLUID_HANDLER,
        ENERGY_HANDLER, CAPABILITY, WORLD_MUTATION, SCHEDULED_TICKS, ENTITY, GLOBAL
    }

    private final Object lifetime;
    private final Object identity;
    private final Kind kind;

    public C7Resource(Object lifetime, Object identity, Kind kind) {
        this.lifetime = Objects.requireNonNull(lifetime);
        this.identity = Objects.requireNonNull(identity);
        this.kind = Objects.requireNonNull(kind);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof C7Resource key && this.lifetime == key.lifetime
                && this.identity == key.identity && this.kind == key.kind;
    }

    @Override
    public int hashCode() {
        return (System.identityHashCode(this.lifetime) * 31 + System.identityHashCode(this.identity)) * 31 + this.kind.ordinal();
    }

    public Kind kind() {
        return this.kind;
    }
}
