/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.c7.BlockEntityExecutionSystem;
import net.neoforged.neoforge.server.threading.c7.C7Metrics;
import net.neoforged.neoforge.server.threading.c7.C7Runtime;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/** Enumerates every vanilla BE type/valid block variant; admits no unproved ticker to a worker. */
final class C7VanillaEvaluation {
    private static final int WARMUP = 200;
    private static final int MEASURED = 600;
    private static final C7Metrics.TypeSnapshot ZERO = new C7Metrics.TypeSnapshot(0, 0, 0, 0, 0, Map.of(), Map.of());

    private record Fixture(String type, BlockEntityType<?> factory, Block block, net.minecraft.world.level.block.state.BlockState initialState, boolean serverTicker,
            List<String> variants, List<String> tickingVariants, int stateCount, int tickingStateCount, List<BlockPos> positions) {}

    private C7VanillaEvaluation() {}

    static void run(ExtendedGameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        helper.assertTrue(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine, "Region engine required");
        final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
        final int copies = Integer.getInteger("forgia.c7VanillaCopies", 16);
        helper.assertTrue(copies >= 2 && copies <= 64, "copies must be 2..64");
        // GameTest structures use randomized distant coordinates. Keep the measured world data and
        // Region/chunk placement identical between drivers instead of following that harness position.
        final int baseX = 16;
        final int baseZ = 16;
        final List<Fixture> fixtures = new ArrayList<>();
        int index = 0;
        for (final var entry : BuiltInRegistries.BLOCK_ENTITY_TYPE.entrySet().stream()
                .filter(e -> e.getKey().location().getNamespace().equals("minecraft"))
                .sorted(Comparator.comparing(e -> e.getKey().location().toString())).toList()) {
            final var blocks = entry.getValue().getValidBlocks().stream()
                    .filter(block -> BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft"))
                    .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString())).toList();
            helper.assertTrue(!blocks.isEmpty(), "vanilla type has no vanilla valid blocks: " + entry.getKey());
            final var states = blocks.stream().flatMap(block -> block.getStateDefinition().getPossibleStates().stream()).toList();
            final var tickingStates = states.stream().filter(state -> state.getTicker(level, entry.getValue()) != null).toList();
            final var ticking = tickingStates.stream().map(net.minecraft.world.level.block.state.BlockState::getBlock).distinct().toList();
            final Block representative = ticking.isEmpty() ? blocks.getFirst() : ticking.getFirst();
            final var initialState = representative.defaultBlockState().getTicker(level, entry.getValue()) != null || tickingStates.isEmpty()
                    ? representative.defaultBlockState() : tickingStates.getFirst();
            final List<BlockPos> positions = new ArrayList<>();
            for (int copy = 0; copy < (ticking.isEmpty() ? 1 : copies); ++copy, ++index)
                positions.add(new BlockPos(baseX + (index % 32) * 3, 160, baseZ + (index / 32) * 3));
            fixtures.add(new Fixture(entry.getKey().location().toString(), entry.getValue(), representative, initialState, !ticking.isEmpty(),
                    blocks.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList(),
                    ticking.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList(), states.size(), tickingStates.size(), positions));
        }
        final List<BlockPos> positions = fixtures.stream().flatMap(f -> f.positions.stream()).toList();
        final List<ChunkPos> chunks = positions.stream().map(ChunkPos::new).distinct().toList();
        final var sampler = new ServerLoadBenchmarkGameTests.TickSampler(WARMUP, MEASURED, null);
        final var before = new AtomicReference<C7Metrics.Snapshot>();
        final var c6Before = new AtomicReference<net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics>();
        final var cpuBefore = new AtomicReference<Map<String, Long>>();
        final var started = new AtomicLong();
        final var initialInventory = new AtomicLong();
        final var end = new C7EvaluationGameTests.WindowEnd(sampler, engine, started);
        final var cleaned = new AtomicBoolean();
        final var readinessChecks = new java.util.concurrent.atomic.AtomicInteger();
        final Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            NeoForge.EVENT_BUS.unregister(sampler);
            NeoForge.EVENT_BUS.unregister(end);
            for (final var pos : positions) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 2);
            }
            for (final var chunk : chunks) {
                level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk, true);
                level.setChunkForced(chunk.x, chunk.z, false);
            }
        };
        helper.addEndListener(passed -> cleanup.run());
        sampler.onMeasuredWindowStart(() -> {
            before.set(engine.c7Metrics());
            c6Before.set(engine.diagnostics());
            cpuBefore.set(C7EvaluationGameTests.jvm());
            started.set(System.nanoTime());
        });
        helper.startSequence()
                .thenExecute(() -> {
                    System.out.println("C7_ALL_STAGE forcing " + chunks.size() + " fixed-coordinate chunks");
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
                    level.setDayTime(6000);
                    for (final var chunk : chunks) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                        level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk, true);
                    }
                })
                .thenWaitUntil(() -> {
                    final var missing = chunks.stream().filter(chunk -> !level.areEntitiesLoaded(chunk.toLong())).toList();
                    if (!missing.isEmpty() && readinessChecks.incrementAndGet() % 200 == 0) System.out.println("C7_ALL_STAGE waiting for entity load " + missing);
                    helper.assertTrue(missing.isEmpty(), "fixture chunks not entity-loaded: " + missing);
                })
                .thenExecute(() -> {
                    System.out.println("C7_ALL_STAGE placing " + positions.size() + " block entities");
                    for (final var fixture : fixtures) for (final var pos : fixture.positions) {
                        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 2);
                        var state = fixture.initialState;
                        if (state.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) state = state.setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, true);
                        level.setBlock(pos, state, 2);
                        var source = level.getBlockEntity(pos);
                        if (fixture.type.equals("minecraft:piston")) {
                            // The registry factory's placeholder has no movement direction. Real pistons
                            // install the fully initialized constructor before their first tick.
                            source = new net.minecraft.world.level.block.piston.PistonMovingBlockEntity(pos, state,
                                    Blocks.STONE.defaultBlockState(), net.minecraft.core.Direction.EAST, true, false);
                            level.setBlockEntity(source);
                        } else if (source == null || source.getType() != fixture.factory) {
                            source = fixture.factory.create(pos, state);
                            helper.assertTrue(source != null, "cannot construct " + fixture.type);
                            level.setBlockEntity(source);
                        }
                        helper.assertTrue(source.getType() == fixture.factory, "wrong source type " + fixture.type);
                        if (source instanceof AbstractFurnaceBlockEntity furnace) {
                            final var tag = new CompoundTag();
                            tag.putInt("BurnTime", 1000000);
                            tag.putInt("CookTime", 40);
                            tag.putInt("CookTimeTotal", 200);
                            furnace.loadWithComponents(tag, level.registryAccess());
                        }
                        if (source instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox)
                            jukebox.setTheItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_13));
                    }
                    for (final var position : positions) {
                        final var source = level.getBlockEntity(position);
                        if (source instanceof Container container) for (int slot = 0; slot < container.getContainerSize(); ++slot)
                            initialInventory.addAndGet(container.getItem(slot).getCount());
                    }
                    System.out.println("C7_ALL_STAGE starting warmup and measured window");
                    sampler.startWindow(level.getGameTime());
                    NeoForge.EVENT_BUS.register(sampler);
                    NeoForge.EVENT_BUS.register(end);
                })
                .thenIdle(WARMUP + MEASURED + 1)
                .thenExecute(() -> {
                    helper.assertTrue(sampler.sampleCount() == MEASURED && end.c7 != null, "incomplete measurement window");
                    final long[] samples = sampler.sortedSamples();
                    final var rows = new TreeMap<String, Object>();
                    long totalItems = 0;
                    for (final var fixture : fixtures) {
                        final var row = new LinkedHashMap<String, Object>();
                        row.put("type", fixture.type);
                        row.put("representativeBlock", BuiltInRegistries.BLOCK.getKey(fixture.block).toString());
                        row.put("validVanillaVariants", fixture.variants);
                        row.put("serverTickingVariants", fixture.tickingVariants);
                        row.put("statesProbed", fixture.stateCount); row.put("serverTickingStates", fixture.tickingStateCount);
                        row.put("initialBlockState", fixture.initialState.toString());
                        row.put("hasServerTicker", fixture.serverTicker);
                        row.put("placed", fixture.positions.size());
                        int alive = 0;
                        for (final var pos : fixture.positions) {
                            final var source = level.getChunkAt(pos).getBlockEntities().get(pos);
                            if (source == null || source.isRemoved() || source.getType() != fixture.factory) continue;
                            ++alive;
                            if (source instanceof Container container) for (int slot = 0; slot < container.getContainerSize(); ++slot) totalItems += container.getItem(slot).getCount();
                        }
                        row.put("survivingAtEnd", alive);
                        if (C7Runtime.system() == BlockEntityExecutionSystem.C7) addDelta(row, before.get().types().getOrDefault(fixture.type, ZERO), end.c7.types().getOrDefault(fixture.type, ZERO));
                        rows.put(fixture.type, row);
                    }
                    final var report = new LinkedHashMap<String, Object>();
                    report.put("kind", "all registered vanilla BE types; every state of every valid vanilla block variant probed for a server ticker; one representative state per type");
                    report.put("limitations", "mostly idle states, playing jukeboxes, zero players, empty processing inventories; missing audited programs stay serial; type reason categories are missing proof coverage, not observed accesses on every invocation; counters include harness BEs");
                    report.put("driver", C7Runtime.system());
                    report.put("seed", 4242424242L);
                    report.put("dayTime", 6000);
                    report.put("fixtureOrigin", List.of(baseX, 160, baseZ));
                    report.put("warmupTicks", WARMUP);
                    report.put("measuredTicks", MEASURED);
                    report.put("copiesPerTickingType", copies);
                    report.put("vanillaTypes", fixtures.size());
                    report.put("serverTickingTypes", fixtures.stream().filter(Fixture::serverTicker).count());
                    report.put("placed", positions.size());
                    report.put("chunks", chunks.size());
                    report.put("workers", end.c6.workerThreadCount());
                    report.put("msptAverage", java.util.Arrays.stream(samples).average().orElseThrow() / 1000000.0);
                    report.put("msptP95", samples[(int) Math.ceil(samples.length * .95) - 1] / 1000000.0);
                    report.put("msptP99", samples[(int) Math.ceil(samples.length * .99) - 1] / 1000000.0);
                    final boolean c7 = C7Runtime.system() == BlockEntityExecutionSystem.C7;
                    final long parallel = c7 ? end.c7.parallel() - before.get().parallel() : end.c6.blockEntityTicksOnWorkers() - c6Before.get().blockEntityTicksOnWorkers();
                    final long serial = c7 ? end.c7.serial() - before.get().serial() : end.c6.fallbackBlockEntityTicks() - c6Before.get().fallbackBlockEntityTicks();
                    report.put("parallelInvocations", parallel);
                    if (C7Runtime.system() != BlockEntityExecutionSystem.DISABLED) {
                        report.put("serialInvocations", serial);
                        report.put("fallbackRatio", parallel + serial == 0 ? 0 : (double) serial / (parallel + serial));
                    } else report.put("serialInvocationsNote", "original serial loop has no C6/C7 completion counters; not inferred from placement");
                    report.put("types", rows);
                    report.put("c7Before", before.get());
                    report.put("c7After", end.c7);
                    report.put("jvmBefore", cpuBefore.get());
                    report.put("jvmAfter", end.jvm);
                    report.put("workerCpuUtilization", (end.jvm.get("workerCpuNanos") - cpuBefore.get().get("workerCpuNanos")) / (double) end.wall / end.c6.workerThreadCount());
                    report.put("allocationBytes", end.jvm.get("allocationBytes") - cpuBefore.get().get("allocationBytes"));
                    report.put("ownershipViolations", end.c6.ownershipViolationCount() - c6Before.get().ownershipViolationCount());
                    report.put("containerItemCountAtStart", initialInventory.get());
                    report.put("containerItemCountAtEnd", totalItems);
                    report.put("windowWallNanos", end.wall);
                    report.put("processCpuCores", (end.jvm.get("processCpuNanos") - cpuBefore.get().get("processCpuNanos")) / (double) end.wall);
                    report.put("heapDeltaBytes", end.jvm.get("heapBytes") - cpuBefore.get().get("heapBytes"));
                    report.put("mods", net.neoforged.fml.ModList.get().getMods().stream().map(mod -> mod.getModId() + "@" + mod.getVersion()).sorted().toList());
                    try {
                        Files.writeString(Path.of("c7-vanilla-evaluation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    helper.assertTrue(totalItems == initialInventory.get(), "inventory item conservation failed");
                    helper.assertTrue(end.c6.ownershipViolationCount() == c6Before.get().ownershipViolationCount(), "ownership violation");
                    if (c7) helper.assertTrue(end.c7.failed() == before.get().failed(), "C7 invocation failure");
                    System.out.println("C7_ALL_VANILLA driver=" + C7Runtime.system() + " types=" + fixtures.size() + " tickingTypes=" + fixtures.stream().filter(Fixture::serverTicker).count()
                            + " parallel=" + parallel + " serial=" + serial + " mspt=" + report.get("msptAverage"));
                    cleanup.run();
                })
                .thenSucceed();
    }

    private static void addDelta(Map<String, Object> row, C7Metrics.TypeSnapshot before, C7Metrics.TypeSnapshot after) {
        row.put("collected", after.collected() - before.collected());
        row.put("parallel", after.parallel() - before.parallel());
        row.put("fallback", after.serial() - before.serial());
        row.put("skipped", after.skipped() - before.skipped());
        row.put("failed", after.failed() - before.failed());
        final var reasons = new TreeMap<String, Long>();
        after.serialReasons().forEach((reason, count) -> {
            final long delta = count - before.serialReasons().getOrDefault(reason, 0L);
            if (delta != 0) reasons.put(reason.name(), delta);
        });
        row.put("fallbackReasons", reasons);
    }
}
