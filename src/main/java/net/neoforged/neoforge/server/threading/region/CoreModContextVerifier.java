/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.runtimeproof.RuntimeProof;
import org.jetbrains.annotations.Nullable;

/**
 * Captures Core wrapper prerequisites without running an unknown Supplier, Set, factory or Mod method.
 * The verified Mod function ignores its three world arguments and still executes in full, exactly once.
 * Workers read only their intercepted source/wrapper/position/chunk roots and these captured values.
 */
final class CoreModContextVerifier {
    private static final Class<?> GUAVA_SINGLE = ImmutableSet.of(new Object()).getClass();
    private static final Class<?> GUAVA_MULTI = ImmutableSet.of(new Object(), new Object()).getClass();
    private static final Class<?> JDK_SINGLE = Set.of(new Object()).getClass();
    private static final Class<?> JDK_MULTI = Set.of(new Object(), new Object(), new Object()).getClass();

    private static String refusal;

    private static boolean refuse(String reason) {
        if (!reason.equals(refusal)) System.err.println("FORGIA_MOD_CONTEXT_REFUSAL " + reason);
        refusal = reason;
        return false;
    }

    private CoreModContextVerifier() {}

    @Nullable
    static BlockState capture(ServerLevel level, BlockEntity source, LevelChunk chunk) {
        if (level.getClass() != ServerLevel.class || source.getBlockState() == null || source.getBlockState().getClass() != BlockState.class) return null;
        try {
            if (!inactiveProfiler(level)) {
                refuse("profiler");
                return null;
            }
            if (!tickingStatus(chunk)) {
                refuse("chunk full-status");
                return null;
            }
            if (!insideBorder(level, source)) {
                refuse("world border");
                return null;
            }
            final Object raw = field(BlockEntityType.class, "validBlocks").get(source.getType());
            if (!(raw instanceof Set<?> blocks)) return null;
            final Class<?> implementation = blocks.getClass();
            if (implementation != GUAVA_SINGLE && implementation != GUAVA_MULTI && implementation != JDK_SINGLE && implementation != JDK_MULTI) {
                refuse("mutable/unknown validBlocks " + implementation.getName());
                return null;
            }
            final BlockState state = source.getBlockState();
            // The exact fixed-library immutable implementation has no user iterator callback. Identity
            // membership avoids third-party Block.equals/hashCode; workers never read this collection.
            if (blocks.size() > 4096) return null;
            final Object expected = state.getBlock();
            for (final Object block : blocks) if (block == expected) return state;
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            refuse(unavailable.toString());
            return null;
        }
    }

    private static boolean insideBorder(ServerLevel level, BlockEntity source) throws ReflectiveOperationException {
        final WorldBorder border = level.getWorldBorder();
        if (border == null || border.getClass() != WorldBorder.class) return false;
        final Object extent = field(WorldBorder.class, "extent").get(border);
        if (extent == null) return false;
        final Class<?> staticExtent = RuntimeProof.loaded(WorldBorder.class, "net/minecraft/world/level/border/WorldBorder$StaticBorderExtent");
        if (extent.getClass() != staticExtent) return false;
        // The stationary extent and all its original methods were integrity-checked. Moving or custom
        // extents retain the original serial path; no runtime clock/listener callback is pre-executed.
        return border.isWithinBounds(source.getBlockPos());
    }

    private static boolean tickingStatus(LevelChunk chunk) throws ReflectiveOperationException {
        final Object supplier = field(LevelChunk.class, "fullStatus").get(chunk);
        final Getter getter = forwarding(supplier, "()Lnet/minecraft/server/level/FullChunkStatus;");
        if (getter == null || getter.receiver.getClass() != ChunkHolder.class || !getter.target.name().equals("getFullStatus")) return false;
        final Class<?> owner = RuntimeProof.loaded(supplier.getClass(), getter.target.owner());
        if (owner != ChunkHolder.class && owner != GenerationChunkHolder.class) return false;
        // Integrity-checked GenerationChunkHolder.getFullStatus -> exact ChunkHolder.getTicketLevel
        // -> ChunkLevel.fullStatus. Read the real primitive rather than invoking the captured Supplier.
        final int ticketLevel = field(ChunkHolder.class, "ticketLevel").getInt(getter.receiver);
        return ChunkLevel.fullStatus(ticketLevel).isOrAfter(FullChunkStatus.BLOCK_TICKING) && getter.definition.current();
    }

    private static boolean inactiveProfiler(ServerLevel level) throws ReflectiveOperationException {
        final Object supplier = field(net.minecraft.world.level.Level.class, "profiler").get(level);
        final Getter target = forwarding(supplier, "()Lnet/minecraft/util/profiling/ProfilerFiller;");
        if (target == null || target.receiver != level.getServer()) return false;
        for (Class<?> type = target.receiver.getClass(); type != null; type = type.getSuperclass()) {
            final var owner = RuntimeProof.definition(type);
            if (owner == null) return false;
            final var getter = owner.method(target.target.name(), target.target.descriptor());
            if (getter == null) continue;
            final byte[] body = getter.code();
            if (!plain(getter) || body.length != 5 || (body[0] & 255) != 42 || (body[1] & 255) != 180 || (body[4] & 255) != 176) return false;
            final var value = owner.constants().member(u16(body, 2));
            final Class<?> declaring = RuntimeProof.loaded(type, value.owner());
            return value.kind() == 9 && declaring == MinecraftServer.class && field(declaring, value.name()).get(target.receiver) == InactiveProfiler.INSTANCE
                    && target.definition.current() && owner.current();
        }
        return false;
    }

    private record Getter(Object receiver, RuntimeProof.Member target, RuntimeProof.Definition definition) {}

    @Nullable
    private static Getter forwarding(@Nullable Object supplier, String result) throws ReflectiveOperationException {
        if (supplier == null || supplier.getClass().getSuperclass() != Object.class) return null;
        final var definition = RuntimeProof.definition(supplier.getClass());
        if (definition == null || !definition.initialized() || definition.fields().size() != 1) return null;
        final var method = definition.method("get", "()Ljava/lang/Object;");
        if (!plain(method)) return null;
        final byte[] code = method.code();
        if (code.length != 8 || (code[0] & 255) != 42 || (code[1] & 255) != 180 || (code[4] & 255) != 182 || (code[7] & 255) != 176) return null;
        final var captured = definition.constants().member(u16(code, 2));
        final var target = definition.constants().member(u16(code, 5));
        final var declared = definition.fields().getFirst();
        // The actual VM verifies GETFIELD(this). With Object as the only superclass, this actual
        // declared field is its only possible containing class, regardless of hidden-name spelling.
        if (captured.kind() != 9 || target.kind() != 10 || !target.descriptor().equals(result)
                || !declared.name().equals(captured.name()) || !declared.descriptor().equals(captured.descriptor())
                || !Modifier.isFinal(declared.access()) || Modifier.isStatic(declared.access()))
            return null;
        final Object receiver = field(supplier.getClass(), captured.name()).get(supplier);
        return receiver == null ? null : new Getter(receiver, target, definition);
    }

    private static boolean plain(@Nullable RuntimeProof.Method method) {
        return method != null && method.exceptionHandlers() == 0 && (method.access() & (Modifier.STATIC | Modifier.NATIVE | Modifier.SYNCHRONIZED)) == 0;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException("Core context field unavailable");
        return field;
    }

    private static int u16(byte[] bytes, int index) {
        return ((bytes[index] & 255) << 8) | (bytes[index + 1] & 255);
    }
}
