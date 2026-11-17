/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** Complete bounded player queries. Never invokes the ordinary entity clamp/snapshot union. */
final class RegionPlayerScopedQuery {
    private RegionPlayerScopedQuery() {}

    static void visit(ServerLevel level, AABB box, AbortableIterationConsumer<Entity> consumer) {
        RegionPlayerSpatialLease lease = RegionPlayerSpatialLease.current();
        if (lease == null) throw new IllegalStateException("No complete player spatial lease");
        var regions = lease.queryRegions(level, box); // complete validation BEFORE predicates/storage
        RegionEntityOwnershipScope.recordAllowedQuery();
        level.forEachAccessibleNonEmptySectionInRegions(regions, box, section -> section.getEntities(box, entity -> {
            lease.requireTarget(entity, "complete bounded query");
            if (entity.isMultipartEntity()) throw new IllegalStateException("Unbounded multipart player query");
            if (entity.isRemoved()) return AbortableIterationConsumer.Continuation.CONTINUE;
            return consumer.accept(entity);
        }));
    }

    static List<Entity> query(ServerLevel level, @Nullable Entity excluded, AABB box, Predicate<? super Entity> predicate) {
        List<Entity> result = new ArrayList<>();
        visit(level, box, entity -> {
            if (entity != excluded && predicate.test(entity)) result.add(entity);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        return result;
    }

    static <T extends Entity> void query(ServerLevel level, EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> predicate, List<? super T> result, int max) {
        visit(level, box, entity -> {
            T cast = type.tryCast(entity);
            if (cast != null && predicate.test(cast)) {
                result.add(cast);
                if (result.size() >= max) return AbortableIterationConsumer.Continuation.ABORT;
            }
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
    }

    static void get(ServerLevel level, AABB box, Consumer<Entity> consumer) {
        visit(level, box, entity -> {
            consumer.accept(entity);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
    }

    static <T extends Entity> void get(ServerLevel level, EntityTypeTest<Entity, T> type, AABB box, AbortableIterationConsumer<T> consumer) {
        visit(level, box, entity -> {
            T cast = type.tryCast(entity);
            return cast == null ? AbortableIterationConsumer.Continuation.CONTINUE : consumer.accept(cast);
        });
    }
}
