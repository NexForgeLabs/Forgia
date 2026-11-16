/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Current-invocation proof, NOT a historical prediction. Incomplete/overflowed proofs are fences. */
public record C7Footprint(Map<C7Resource, Access> resources, boolean complete) {
    public enum Access {
        READ, WRITE
    }

    public C7Footprint {
        resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        resources.forEach((key, value) -> {
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
        });
    }

    public static C7Footprint unknown() {
        return new C7Footprint(Map.of(), false);
    }

    public boolean permits(C7Resource key, Access requested) {
        final Access declared = this.resources.get(key);
        return this.complete && (declared == Access.WRITE || declared == requested);
    }

    public static final class Builder {
        private final int limit;
        private final Map<C7Resource, Access> resources = new LinkedHashMap<>();
        private boolean complete = true;

        public Builder(int limit) {
            if (limit < 1) throw new IllegalArgumentException("resource limit");
            this.limit = limit;
        }

        public Builder access(C7Resource key, Access access) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(access);
            if (!this.resources.containsKey(key) && this.resources.size() >= this.limit) this.complete = false;
            else this.resources.merge(key, access, (a, b) -> a == Access.WRITE || b == Access.WRITE ? Access.WRITE : Access.READ);
            return this;
        }

        public C7Footprint build() {
            return new C7Footprint(this.resources, this.complete);
        }
    }
}
