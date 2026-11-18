/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reuses only field metadata; each admission still reads the original receiver. */
final class RegionPlayerFieldAccess {
    private static final ClassValue<ConcurrentMap<String, Field>> FIELDS = new ClassValue<>() {
        @Override
        protected ConcurrentMap<String, Field> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private RegionPlayerFieldAccess() {}

    static Object read(Object receiver, Class<?> type, String name) throws ReflectiveOperationException {
        var fields = FIELDS.get(type);
        Field field = fields.get(name);
        if (field == null) {
            field = type.getDeclaredField(name);
            if (!field.trySetAccessible()) throw new IllegalAccessException(type.getName() + "." + name);
            Field existing = fields.putIfAbsent(name, field);
            if (existing != null) field = existing;
        }
        return field.get(receiver);
    }
}
