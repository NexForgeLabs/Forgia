/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.runtimeproof.RuntimeProof;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Integrity of the already audited Core/Vanilla execution bridge. Build-bundled Core methods are compared
 * with final named definition events from the sole VM agent, bound to each actual defining loader/Class.
 * The complete invokedynamic bootstrap arguments and exception handlers are part of each comparison.
 * This supplies no Mod closure certificate; the original Mod method still passes the semantic verifier.
 */
final class CoreModRuntimeIntegrity {
    private static String refusal;

    private static boolean refuse(String reason) {
        if (!reason.equals(refusal)) System.err.println("FORGIA_CORE_INTEGRITY_REFUSAL " + reason);
        refusal = reason;
        return false;
    }

    private CoreModRuntimeIntegrity() {}

    static boolean verify() {
        if (!CoreModTickerVerifier.available()) {
            if (!"runtime absent".equals(refusal)) System.err.println("FORGIA_CORE_INTEGRITY_REFUSAL runtime absent");
            refusal = "runtime absent";
            return false;
        }
        final long epoch = RuntimeProof.epoch();
        try {
            for (final Class<?> type : List.of(LevelChunk.class, net.minecraft.world.level.chunk.ChunkAccess.class,
                    net.minecraft.core.BlockPos.class, net.minecraft.core.Vec3i.class, net.minecraft.world.level.block.state.BlockState.class,
                    net.minecraft.server.MinecraftServer.class, net.minecraft.server.level.ChunkHolder.class,
                    net.minecraft.server.level.GenerationChunkHolder.class, net.minecraft.server.level.ChunkLevel.class,
                    Level.class, ServerLevel.class, BlockEntity.class, BlockEntityType.class,
                    net.minecraft.world.level.storage.PrimaryLevelData.class, net.minecraft.world.level.border.WorldBorder.class,
                    net.minecraft.world.level.block.state.StateHolder.class, net.minecraft.world.level.block.state.BlockBehaviour.class,
                    TimeTracker.class, RegionThreadingHooks.class, RegionThreadingEngine.class, CoreModTickerVerifier.class,
                    CoreModPreparation.class, CoreModWorkScope.class, CoreModContextVerifier.class, CoreModRuntimeIntegrity.class,
                    RegionState.class, ThreadedRegionizer.class, RegionWorkerPool.class, RegionWorkerScope.class,
                    RegionTickBatch.class, RegionTaskBatch.class, RegionBlockEntityTickBatch.class, BlockEntityExecutionBatch.class,
                    net.minecraft.util.profiling.InactiveProfiler.class, net.minecraft.util.profiling.ProfilerFiller.class,
                    net.minecraft.resources.ResourceLocation.class, CanonicalBlockEntityCoordinator.class,
                    BlockEntityExecutionEngine.class, BlockEntityResourceOwnership.class)) {
                for (final Class<?> member : type.getNestMembers()) {
                    if (!matches(member)) return false;
                }
            }
            final Class<?>[] loaded = RuntimeProof.capturedCoreTypes(RegionThreadingEngine.class);
            if (loaded == null) return false;
            for (final Class<?> type : loaded) if (!type.isHidden() && !matches(type)) return false;
            return RuntimeProof.available() && RuntimeProof.epoch() == epoch;
        } catch (IllegalArgumentException | LinkageError unavailable) {
            return refuse(unavailable.toString());
        }
    }

    private record Verified(long epoch, boolean matched) {}

    private static final ClassValue<Verified> CODE_INTEGRITY = new ClassValue<>() {
        @Override
        protected Verified computeValue(Class<?> type) {
            final long epoch = RuntimeProof.epoch();
            return new Verified(epoch, matchesDefinition(type) && RuntimeProof.available() && RuntimeProof.epoch() == epoch);
        }
    };

    private static boolean matches(Class<?> type) {
        final var checked = CODE_INTEGRITY.get(type);
        if (checked.epoch != RuntimeProof.epoch()) {
            CODE_INTEGRITY.remove(type);
            return false;
        }
        return checked.matched && RuntimeProof.available();
    }

    private static boolean matchesDefinition(Class<?> type) {
        final String name = type.getName().replace('.', '/');
        final byte[] expected = RuntimeProof.bundledCore(name);
        final byte[] actual = RuntimeProof.trustedDefinition(type);
        if (expected == null || actual == null) return refuse(name + " missing bundled=" + (expected != null) + " VM=" + (actual != null));
        if (Arrays.equals(expected, actual)) return true;
        final var baseline = node(expected);
        final var runtime = node(actual);
        if (!baseline.name.equals(name) || !runtime.name.equals(name) || !java.util.Objects.equals(baseline.superName, runtime.superName)) return false;
        final Map<String, MethodNode> methods = new HashMap<>();
        for (final MethodNode method : runtime.methods) {
            if (methods.put(method.name + method.desc, method) != null) return false;
        }
        final java.util.Set<String> original = new java.util.HashSet<>();
        for (final MethodNode method : baseline.methods) {
            original.add(method.name + method.desc);
            final MethodNode found = methods.get(method.name + method.desc);
            if (found == null || method.access != found.access || !instructions(method).equals(instructions(found)))
                return refuse(name + "." + method.name + method.desc + " differs from audited Core build");
        }
        for (final MethodNode method : runtime.methods) {
            if (!original.contains(method.name + method.desc) && !registrationSetter(type, method))
                return refuse(name + "." + method.name + method.desc + " adds an unproved dispatch target");
        }
        // The one registration setter does not replace a reached method. Every original field
        // still has its original storage descriptor; interception covers actual inherited instance fields.
        for (final var field : baseline.fields) {
            if (runtime.fields.stream().filter(current -> current.name.equals(field.name) && current.desc.equals(field.desc)).count() != 1) return false;
        }
        return true;
    }

    private static boolean registrationSetter(Class<?> owner, MethodNode method) {
        if (owner != BlockEntityType.class || !method.desc.equals("(Ljava/util/Set;)V")
                || (method.access & (org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_NATIVE | org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED)) != 0
                || !method.tryCatchBlocks.isEmpty())
            return false;
        final java.util.List<org.objectweb.asm.tree.AbstractInsnNode> code = new java.util.ArrayList<>();
        for (final var instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
        return code.size() == 4 && code.get(0) instanceof org.objectweb.asm.tree.VarInsnNode receiver && receiver.getOpcode() == 25 && receiver.var == 0
                && code.get(1) instanceof org.objectweb.asm.tree.VarInsnNode value && value.getOpcode() == 25 && value.var == 1
                && code.get(2) instanceof org.objectweb.asm.tree.FieldInsnNode field && field.getOpcode() == 181
                && field.owner.equals("net/minecraft/world/level/block/entity/BlockEntityType") && field.name.equals("validBlocks") && field.desc.equals("Ljava/util/Set;")
                && code.get(3).getOpcode() == 177;
    }

    private static ClassNode node(byte[] bytes) {
        final var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static String instructions(MethodNode method) {
        final var text = new Textifier();
        final var visitor = new TraceMethodVisitor(text);
        for (final var handler : method.tryCatchBlocks) handler.accept(visitor);
        method.instructions.accept(visitor);
        return text.getText().toString();
    }
}
