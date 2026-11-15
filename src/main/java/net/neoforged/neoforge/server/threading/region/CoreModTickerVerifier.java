/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.neoforged.forgia.runtimeproof.RuntimeProof;
import org.jetbrains.annotations.Nullable;

/**
 * A deliberately small semantic verifier of actual installed code. Names identify resolved VM members;
 * no Mod id, class name, author declaration, class hash or previously successful invocation authorizes a
 * body. Every opcode on the selected original path and every reached target must fit this closed form.
 * All other methods, shapes, branches and incomplete VM evidence refuse before the original Ticket runs.
 */
final class CoreModTickerVerifier {
    private static final String SAM = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V";

    private static final boolean INSTALLED = bootstrapPresent();

    private static boolean bootstrapPresent() {
        try {
            return Class.forName("net.neoforged.forgia.runtimeproof.RuntimeProof", false, null).getClassLoader() == null;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    static boolean installed() {
        return INSTALLED;
    }

    private CoreModTickerVerifier() {}

    static boolean available() {
        if (!INSTALLED) return false;
        try {
            return RuntimeProof.enforcementAvailable();
        } catch (LinkageError absent) {
            return false;
        }
    }

    @Nullable
    static Code verify(BlockEntity source, BlockEntityTicker<?> function) {
        if (!available() || source == null || function == null || !Modifier.isFinal(source.getClass().getModifiers())
                || source.getClass().getSuperclass() != BlockEntity.class)
            return null;
        try {
            final var generated = RuntimeProof.definition(function.getClass());
            if (generated == null || !generated.fields().isEmpty()) return null;
            final var sam = generated.method("tick", SAM);
            if (!plain(sam, false)) return null;
            final byte[] code = sam.code();
            // Noncapturing four-argument forwarding SAM, invoking one actual static implementation.
            if (code.length != 9 || !at(code, 0, 43, 44, 45, 25, 4, 184) || u8(code, 8) != 177) return null;
            final var target = generated.constants().member(u16(code, 6));
            if (target.kind() != 10 || !SAM.equals(target.descriptor())) return null;
            final Class<?> host = RuntimeProof.loaded(function.getClass(), target.owner());
            if (host == null) return null;
            final var hostCode = RuntimeProof.definition(host);
            if (hostCode == null) return null;
            final var forwarder = hostCode.method(target.name(), target.descriptor());
            if (!plain(forwarder, true)) return null;
            final byte[] forwarding = forwarder.code();
            // Pass only the real source to one final, statically resolved instance method.
            if (forwarding.length != 8 || !at(forwarding, 0, 45, 192) || u8(forwarding, 4) != 182 || u8(forwarding, 7) != 177)
                return null;
            final String cast = hostCode.constants().className(u16(forwarding, 2));
            final var body = hostCode.constants().member(u16(forwarding, 5));
            if (body.kind() != 10 || !body.descriptor().equals("()V") || !cast.equals(body.owner())
                    || RuntimeProof.loaded(host, cast) != source.getClass())
                return null;
            final var sourceCode = RuntimeProof.definition(source.getClass());
            if (sourceCode == null) return null;
            for (final var method : sourceCode.methods()) {
                if (java.util.Set.of("getLevel", "getBlockPos", "getBlockState", "getType", "isRemoved", "hasLevel", "hasAttachments")
                        .contains(method.name()))
                    return null;
            }
            final var original = sourceCode.method(body.name(), body.descriptor());
            if (!plain(original, false)) return null;
            final byte[] counter = original.code();
            // ++this.intField; if (newValue % positiveConstant != 0) return. The unknown periodic
            // branch is never truncated/executed speculatively: the whole invocation remains serial.
            if (counter.length < 18 || !at(counter, 0, 42, 89, 180) || !at(counter, 5, 4, 96, 90, 181)
                    || !at(counter, 11, 16) || !at(counter, 13, 112, 154))
                return null;
            final int divisor = (byte) counter[12];
            final int destination = 14 + (short) u16(counter, 15);
            if (divisor < 2 || destination != counter.length - 1 || u8(counter, destination) != 177) return null;
            final var read = sourceCode.constants().member(u16(counter, 3));
            final var write = sourceCode.constants().member(u16(counter, 9));
            if (!read.equals(write) || read.kind() != 9 || !read.descriptor().equals("I")
                    || !read.owner().equals(source.getClass().getName().replace('.', '/')))
                return null;
            final Field field = source.getClass().getDeclaredField(read.name());
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                    || !field.trySetAccessible())
                return null;
            final var definitions = List.of(generated, hostCode, sourceCode);
            if (definitions.stream().anyMatch(definition -> !definition.initialized() || !definition.current())) return null;
            return new Code(function, source.getClass(), field, divisor, definitions);
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError unavailable) {
            return null;
        }
    }

    private static boolean plain(@Nullable RuntimeProof.Method method, boolean isStatic) {
        return method != null && method.exceptionHandlers() == 0 && (method.access() & (Modifier.NATIVE | Modifier.ABSTRACT | Modifier.SYNCHRONIZED)) == 0
                && Modifier.isStatic(method.access()) == isStatic;
    }

    private static boolean at(byte[] code, int index, int... expected) {
        if (index < 0 || index + expected.length > code.length) return false;
        for (final int opcode : expected) if (u8(code, index++) != opcode) return false;
        return true;
    }

    private static int u8(byte[] code, int offset) {
        return code[offset] & 255;
    }

    private static int u16(byte[] code, int offset) {
        return (u8(code, offset) << 8) | u8(code, offset + 1);
    }

    record Code(BlockEntityTicker<?> actualFunction, Class<?> actualSourceClass, Field counter, int divisor,
            List<RuntimeProof.Definition> definitions) {
        @Nullable
        Integer value(BlockEntity source) {
            if (source.getClass() != this.actualSourceClass || this.definitions.stream().anyMatch(definition -> !definition.current())) return null;
            try {
                final int value = this.counter.getInt(source);
                return (value + 1) % this.divisor == 0 ? null : value;
            } catch (IllegalAccessException unavailable) {
                return null;
            }
        }
    }
}
