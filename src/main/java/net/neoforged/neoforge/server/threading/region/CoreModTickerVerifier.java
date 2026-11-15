/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
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
    private static final Set<String> REFUSALS = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        if (!available()) return refuse("optional VM enforcement unavailable");
        if (source == null || function == null) return refuse("missing source or function");
        if (!Modifier.isFinal(source.getClass().getModifiers()) || source.getClass().getSuperclass() != BlockEntity.class)
            return refuse("source class is not a final direct BlockEntity");
        try {
            final var generated = RuntimeProof.definition(function.getClass());
            if (generated == null) return refuse("actual function definition unavailable");
            if (!generated.fields().isEmpty()) return refuse("actual function carries instance fields " + generated.fields());
            final var sam = generated.method("tick", SAM);
            if (!plain(sam, false)) return refuse("actual function SAM is not a plain instance method");
            final byte[] code = sam.code();
            // Noncapturing four-argument forwarding SAM, invoking one actual static implementation.
            if (!at(code, 0, 43, 44, 45, 25, 4, 184) || code.length != 9 || u8(code, 8) != 177)
                return refuse("actual function SAM shape " + hex(code));
            final var target = generated.constants().member(u16(code, 6));
            if (target.kind() != 10 || !SAM.equals(target.descriptor()))
                return refuse("actual function SAM target " + target.kind() + " " + target.owner() + "." + target.name() + target.descriptor());
            final Class<?> host = RuntimeProof.loaded(function.getClass(), target.owner());
            if (host == null) return refuse("actual forwarder host is not loaded");
            final var hostCode = RuntimeProof.definition(host);
            if (hostCode == null) return refuse("actual forwarder host definition unavailable");
            final var forwarder = hostCode.method(target.name(), target.descriptor());
            if (!plain(forwarder, true)) return refuse("actual forwarder is not a plain static method");
            final byte[] forwarding = forwarder.code();
            // Pass only the real source to one final, statically resolved instance method.
            if (!at(forwarding, 0, 45, 192) || forwarding.length != 8 || u8(forwarding, 4) != 182 || u8(forwarding, 7) != 177)
                return refuse("actual forwarder shape " + hex(forwarding) + " host=" + host.getName() + " target=" + target.name() + target.descriptor());
            final String cast = hostCode.constants().className(u16(forwarding, 2));
            final var body = hostCode.constants().member(u16(forwarding, 5));
            if (body.kind() != 10 || !body.descriptor().equals("()V") || !cast.equals(body.owner())
                    || RuntimeProof.loaded(host, cast) != source.getClass())
                return refuse("actual forwarder target " + body.kind() + " " + body.owner() + "." + body.name() + body.descriptor() + " cast=" + cast);
            final var sourceCode = RuntimeProof.definition(source.getClass());
            if (sourceCode == null) return refuse("actual source definition unavailable");
            for (final var method : sourceCode.methods()) {
                if (Set.of("getLevel", "getBlockPos", "getBlockState", "getType", "isRemoved", "hasLevel", "hasAttachments")
                        .contains(method.name()))
                    return refuse("source reaches live world accessor " + method.name());
            }
            final var original = sourceCode.method(body.name(), body.descriptor());
            if (!plain(original, false)) return refuse("original tick method is not a plain instance method");
            final byte[] counter = original.code();
            // ++this.intField; if (newValue % positiveConstant != 0) return. The unknown periodic
            // branch is never truncated/executed speculatively: the whole invocation remains serial.
            if (counter.length < 18 || !at(counter, 0, 42, 89, 180) || !at(counter, 5, 4, 96, 90, 181)
                    || !at(counter, 11, 16) || !at(counter, 13, 112, 154))
                return refuse("original counter shape " + hex(counter));
            final int divisor = (byte) counter[12];
            final int destination = 14 + (short) u16(counter, 15);
            if (divisor < 2 || destination != counter.length - 1 || u8(counter, destination) != 177)
                return refuse("original counter branch " + hex(counter));
            final var read = sourceCode.constants().member(u16(counter, 3));
            final var write = sourceCode.constants().member(u16(counter, 9));
            if (!read.equals(write) || read.kind() != 9 || !read.descriptor().equals("I")
                    || !read.owner().equals(source.getClass().getName().replace('.', '/')))
                return refuse("original counter field is not one private instance int");
            final Field field = source.getClass().getDeclaredField(read.name());
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                    || !field.trySetAccessible())
                return refuse("original counter field is not a writable instance int");
            final var definitions = List.of(generated, hostCode, sourceCode);
            if (definitions.stream().anyMatch(definition -> !definition.initialized() || !definition.current()))
                return refuse("actual definition is uninitialized or stale");
            return new Code(function, source.getClass(), field, divisor, definitions);
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError unavailable) {
            return refuse(unavailable.toString());
        }
    }

    private static Code refuse(String reason) {
        if (REFUSALS.size() < 16 && REFUSALS.add(reason)) System.err.println("FORGIA_MOD_TICKER_REFUSAL " + reason);
        return null;
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

    private static String hex(byte[] code) {
        final var text = new StringBuilder("opcodes[");
        for (int index = 0; index < code.length; ++index) {
            if (index > 0) text.append(' ');
            text.append(u8(code, index));
        }
        return text.append(']').toString();
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
