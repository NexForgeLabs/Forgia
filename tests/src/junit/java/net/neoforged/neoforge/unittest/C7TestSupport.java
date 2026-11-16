/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.threading.c7.BlockEntityExecutionSystem;
import net.neoforged.neoforge.server.threading.c7.C7FurnaceProgram;
import net.neoforged.neoforge.server.threading.c7.C7Options;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;

/** Real Level loop, original wrappers, independent C7 ticker and existing real pool. No test scheduler. */
final class C7TestSupport implements AutoCloseable {
    final IConfigSpec.ILoadedConfig previous;
    final CanonicalBlockEntityTestSupport f;

    C7TestSupport(BlockEntityExecutionSystem system, C7Options.Mode mode, boolean persistent) {
        this.previous = (IConfigSpec.ILoadedConfig) CanonicalBlockEntityTestSupport.get(ModConfigSpec.class, ForgiaConfig.SERVER_SPEC, "loadedConfig");
        final var config = CommentedConfig.inMemory();
        ForgiaConfig.SERVER_SPEC.correct(config);
        config.set("blockEntityExecutionSystem", system.name());
        config.set("enableC7", true);
        config.set("c7ExecutionMode", mode.name());
        config.set("c7Conservative", false);
        config.set("c7StragglerNanos", Long.MAX_VALUE);
        final IConfigSpec.ILoadedConfig loaded;
        try {
            final var type = Class.forName("net.neoforged.fml.config.LoadedConfig");
            final var constructor = type.getDeclaredConstructor(CommentedConfig.class, java.nio.file.Path.class, net.neoforged.fml.config.ModConfig.class);
            constructor.setAccessible(true);
            loaded = (IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        ForgiaConfig.SERVER_SPEC.acceptConfig(loaded);
        clearCaches();
        this.f = new CanonicalBlockEntityTestSupport();
        this.f.enable(true, persistent);
        CanonicalBlockEntityTestSupport.set(RegionThreadingEngine.class, this.f.engine, "workerExecutionEnabled", true);
    }

    void clock(String id, BlockPos pos, int lit, int progress, int total) {
        this.f.furnace(id, pos, 0);
        final var source = this.f.furnaces.get(id);
        source.setItem(0, ItemStack.EMPTY);
        CanonicalBlockEntityTestSupport.set(AbstractFurnaceBlockEntity.class, source, "litTime", lit);
        CanonicalBlockEntityTestSupport.set(AbstractFurnaceBlockEntity.class, source, "cookingProgress", progress);
        CanonicalBlockEntityTestSupport.set(AbstractFurnaceBlockEntity.class, source, "cookingTotalTime", total);
    }

    void rebind(String id, AbstractFurnaceBlockEntity replacement) {
        final var wrapper = this.f.tickers.get(id);
        final var pos = wrapper.getPos();
        replacement.setLevel(this.f.level);
        this.f.furnaces.put(id, replacement);
        this.f.world.blockEntities.put(pos, replacement);
        this.f.world.states.put(pos, replacement.getBlockState());
        try {
            final var factory = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
            factory.setAccessible(true);
            final var delegate = (TickingBlockEntity) factory.invoke(this.f.chunk(pos), replacement, C7FurnaceProgram.ticker());
            final var rebind = wrapper.getClass().getDeclaredMethod("rebind", TickingBlockEntity.class);
            rebind.setAccessible(true);
            rebind.invoke(wrapper, delegate);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    int lit(String id) {
        return (Integer) CanonicalBlockEntityTestSupport.get(AbstractFurnaceBlockEntity.class, this.f.furnaces.get(id), "litTime");
    }

    static void clearCaches() {
        for (final var restart : ModConfigSpec.RestartType.values()) ForgiaConfig.SERVER_SPEC.resetCaches(restart);
    }

    @Override
    public void close() {
        try {
            this.f.close();
        } finally {
            ForgiaConfig.SERVER_SPEC.acceptConfig(this.previous);
            clearCaches();
        }
    }
}
