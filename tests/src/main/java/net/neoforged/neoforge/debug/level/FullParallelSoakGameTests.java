/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * 10-minute 32-player soak comparing all Forgia parallel features against vanilla serial execution.
 * <p>
 * Exercises movement, world exploration (chunk generation/loading), dynamic region/island merging and
 * splitting, random entity attacking, block breaking, block placing, and block-entity/natural-spawning
 * activity under identical seeds and action schedules.
 */
@ForEachTest(groups = "level.region")
public class FullParallelSoakGameTests {
    @GameTest(batch = "fullParallelSoak", timeoutTicks = 25_000)
    @EmptyTemplate("5x5x5")
    @TestHolder(description = "10-minute 32-player soak comparing Forgia parallel execution vs vanilla serial")
    static void fullParallelSoak(DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean("forgia.fullParallelSoak")) {
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final @Nullable RegionThreadingEngine engine = RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine e ? e : null;
            final Probe probe = new Probe(level, engine);
            helper.addEndListener(passed -> probe.close());
            probe.populate();
            NeoForge.EVENT_BUS.register(probe);
            final long duration = TimeUnit.SECONDS.toNanos(Math.clamp(Long.getLong("forgia.fullParallelSoakSeconds", 600L), 1L, 3600L));
            helper.startSequence().thenWaitUntil(() -> {
                helper.assertTrue(probe.elapsed() >= duration, "Measurement window is still running");
                helper.assertTrue(probe.bots.stream().allMatch(bot -> bot.connection().isConnected()), "A bot disconnected");
            }).thenExecute(() -> {
                probe.printSummary();
                probe.close();
            }).thenSucceed();
        });
    }

    private static final class Bot {
        final ServerPlayer player;
        final Connection connection;
        final EmbeddedChannel channel;
        final int index;
        final int anchorX;
        final int anchorZ;
        int sequence;

        Bot(ServerPlayer player, Connection connection, EmbeddedChannel channel, int index, int anchorX, int anchorZ) {
            this.player = player;
            this.connection = connection;
            this.channel = channel;
            this.index = index;
            this.anchorX = anchorX;
            this.anchorZ = anchorZ;
        }

        Connection connection() {
            return connection;
        }
    }

    public static final class Probe {
        private static final Gson JSON = new Gson();
        private static final long TICK_NANOS = 50_000_000L;
        private static final int BOT_COUNT = 32;
        private final ServerLevel level;
        private final @Nullable RegionThreadingEngine engine;
        private final List<Bot> bots = new ArrayList<>(BOT_COUNT);
        private final SplittableRandom random = new SplittableRandom(4242424242L);
        private final long[] allTickNanos = new long[32_000];
        private int totalTicks;
        private long started;
        private long lastSample;
        private long sampleTick;
        private long tickStart;
        private long startGcCount;
        private long startGcMillis;
        private long actions;
        private long attacks;
        private long breaks;
        private long places;
        private long moves;
        private boolean closed;

        Probe(ServerLevel level, @Nullable RegionThreadingEngine engine) {
            this.level = level;
            this.engine = engine;
        }

        void populate() {
            var server = level.getServer();
            server.getPlayerList().setViewDistance(3);
            server.getPlayerList().setSimulationDistance(3);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
            server.setDifficulty(Difficulty.NORMAL, true);
            for (int i = 0; i < BOT_COUNT; i++) {
                final int pair = i / 2;
                final int side = i % 2;
                final int pairCenterX = 4200 + (pair % 4) * 512;
                final int pairCenterZ = 4200 + (pair / 4) * 512;
                // Separate pair members by >256 blocks initially so they start in distinct 8x8-chunk sections
                // with an empty buffer section between them, then converge to trigger island merges and diverge to split.
                final int x = pairCenterX + (side == 0 ? -144 : 144);
                final int z = pairCenterZ;
                final int y = Math.max(level.getMinBuildHeight() + 4, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                final var profile = new GameProfile(new UUID(0xF0471BL, i), "soak_bot_" + i);
                final var cookie = CommonListenerCookie.createInitial(profile, false);
                final var player = new ServerPlayer(server, level, profile, cookie.clientInformation());
                final var connection = new Connection(PacketFlow.SERVERBOUND);
                final var channel = new EmbeddedChannel(connection);
                final var bot = new Bot(player, connection, channel, i, pairCenterX, pairCenterZ);
                bots.add(bot);
                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                server.getConnection().getConnections().add(connection);
                player.setGameMode(GameType.CREATIVE);
                equipBot(player);
                player.teleportTo(x + 0.5, y, z + 0.5);
            }
            started = lastSample = System.nanoTime();
            sampleTick = server.getTickCount();
            startGcCount = gcCount();
            startGcMillis = gcMillis();
            System.out.println("FULL_SOAK_STARTED mode=" + (engine != null ? "parallel" : "serial")
                    + " pid=" + ProcessHandle.current().pid()
                    + " bots=" + bots.size());
        }

        private static void equipBot(ServerPlayer player) {
            final var inv = player.getInventory();
            inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            inv.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
            inv.setItem(2, new ItemStack(Items.COBBLESTONE, 64));
            inv.setItem(3, new ItemStack(Items.OAK_PLANKS, 64));
            inv.setItem(4, new ItemStack(Items.FURNACE, 64));
            inv.setItem(5, new ItemStack(Items.HOPPER, 64));
            inv.setItem(6, new ItemStack(Items.REDSTONE_LAMP, 64));
        }

        long elapsed() {
            return System.nanoTime() - started;
        }

        @SubscribeEvent
        public void pre(ServerTickEvent.Pre event) {
            tickStart = System.nanoTime();
        }

        @SubscribeEvent
        public void post(ServerTickEvent.Post event) {
            if (tickStart > 0L) {
                final long workNanos = System.nanoTime() - tickStart;
                if (totalTicks < allTickNanos.length) {
                    allTickNanos[totalTicks] = workNanos;
                }
                totalTicks++;
            }

            final var server = event.getServer();
            final int tick = server.getTickCount();
            for (int i = 0; i < bots.size(); i++) {
                final Bot bot = bots.get(i);
                drain(bot);
                if ((tick + i) % 2 == 0 && bot.connection().isConnected()) {
                    act(bot, tick);
                }
            }
            if (tick % 20 == 0) {
                replenishTargets();
            }

            final long now = System.nanoTime();
            if (now - lastSample >= TimeUnit.SECONDS.toNanos(10)) {
                final var row = buildMetricsRow(server, tick, now, false);
                System.out.println("FULL_SOAK_SAMPLE " + JSON.toJson(row));
                lastSample = now;
                sampleTick = tick;
            }

            if (tickStart > 0L) {
                final long deadline = tickStart + TICK_NANOS;
                long remaining;
                while ((remaining = deadline - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                    LockSupport.parkNanos(remaining);
                }
            }
        }

        void printSummary() {
            final long now = System.nanoTime();
            final var server = level.getServer();
            final var row = buildMetricsRow(server, server.getTickCount(), now, true);
            System.out.println("FULL_SOAK_SUMMARY " + JSON.toJson(row));
        }

        private LinkedHashMap<String, Object> buildMetricsRow(net.minecraft.server.MinecraftServer server, int tick, long now, boolean summary) {
            final long[] times;
            if (summary) {
                final int len = Math.min(totalTicks, allTickNanos.length);
                times = Arrays.copyOf(allTickNanos, len);
                Arrays.sort(times);
            } else {
                times = Arrays.stream(server.getTickTimesNanos()).filter(value -> value > 0).sorted().toArray();
            }
            final var row = new LinkedHashMap<String, Object>();
            row.put("mode", engine != null ? "parallel" : "serial");
            row.put("pid", ProcessHandle.current().pid());
            row.put("elapsedSeconds", (now - started) / 1.0e9);
            row.put("tick", tick);
            row.put("totalTicks", totalTicks);
            row.put("tps", summary
                    ? totalTicks * 1.0e9 / Math.max(1L, now - started)
                    : (tick - sampleTick) * 1.0e9 / Math.max(1L, now - lastSample));
            row.put("meanMspt", Arrays.stream(times).average().orElse(0) / 1.0e6);
            row.put("p50Mspt", percentile(times, 0.50));
            row.put("p95Mspt", percentile(times, 0.95));
            row.put("p99Mspt", percentile(times, 0.99));
            row.put("maxMspt", percentile(times, 1.0));
            row.put("players", bots.stream().filter(bot -> bot.connection().isConnected()).count());
            row.put("actions", actions);
            row.put("attacks", attacks);
            row.put("breaks", breaks);
            row.put("places", places);
            row.put("moves", moves);
            row.put("loadedChunks", level.getChunkSource().getLoadedChunksCount());
            row.put("pendingChunkTasks", level.getChunkSource().getPendingTasksCount());
            row.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            row.put("gcCountDelta", gcCount() - startGcCount);
            row.put("gcMillisDelta", gcMillis() - startGcMillis);
            if (engine != null) {
                row.put("islands", engine.loadedIslandDiagnostics(level, 32));
                row.put("regions", engine.diagnostics());
                row.put("playerTick", engine.playerTickDiagnostics(server));
            }
            return row;
        }

        private void replenishTargets() {
            // Keep a bounded number of mobs near bots so random attacks have real targets.
            for (int i = 0; i < bots.size(); i++) {
                final Bot bot = bots.get(i);
                final var player = bot.player;
                final AABB box = player.getBoundingBox().inflate(5.0);
                final List<Mob> nearby = level.getEntitiesOfClass(Mob.class, box);
                if (nearby.size() < 2) {
                    final double sx = player.getX() + random.nextDouble(-2.0, 2.0);
                    final double sz = player.getZ() + random.nextDouble(-2.0, 2.0);
                    final int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(sx), (int) Math.floor(sz));
                    final Mob mob = random.nextBoolean()
                            ? EntityType.ZOMBIE.create(level)
                            : EntityType.COW.create(level);
                    if (mob != null) {
                        mob.moveTo(sx, Math.max(level.getMinBuildHeight() + 4, sy), sz, random.nextFloat() * 360.0f, 0.0f);
                        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
                        level.addFreshEntity(mob);
                    }
                }
            }
        }

        private void act(Bot bot, int tick) {
            final var player = bot.player;
            final var listener = player.connection;
            // 1) Continuous movement & world exploration + periodic convergence/divergence for island merge/split.
            moveAndExplore(bot, tick);

            // 2) Random gameplay action: attack, break block, place block, or swing/hotbar.
            switch (random.nextInt(4)) {
                case 0 -> {
                    // Random attack on nearby mob
                    final List<Mob> targets = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(4.5));
                    if (!targets.isEmpty()) {
                        final Mob target = targets.get(random.nextInt(targets.size()));
                        listener.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(0));
                        listener.handleInteract(ServerboundInteractPacket.createAttackPacket(target, false));
                        listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                        attacks++;
                    }
                }
                case 1 -> {
                    // Random block break around player
                    final int bx = (int) Math.floor(player.getX()) + random.nextInt(-2, 3);
                    final int bz = (int) Math.floor(player.getZ()) + random.nextInt(-2, 3);
                    final int by = Math.max(level.getMinBuildHeight() + 4, (int) Math.floor(player.getY()) + random.nextInt(-1, 2));
                    final BlockPos pos = new BlockPos(bx, by, bz);
                    if (level.isLoaded(pos)) {
                        final BlockState state = level.getBlockState(pos);
                        if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                            listener.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(1));
                            listener.handlePlayerAction(new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, ++bot.sequence));
                            listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                            breaks++;
                        }
                    }
                }
                case 2 -> {
                    // Random block placement
                    final int bx = (int) Math.floor(player.getX()) + random.nextInt(-2, 3);
                    final int bz = (int) Math.floor(player.getZ()) + random.nextInt(-2, 3);
                    final int by = Math.max(level.getMinBuildHeight() + 4, (int) Math.floor(player.getY()) - 1);
                    final BlockPos support = new BlockPos(bx, by, bz);
                    final BlockPos above = support.above();
                    if (level.isLoaded(support) && !level.getBlockState(support).isAir() && level.getBlockState(above).isAir()) {
                        final int slot = 2 + random.nextInt(5);
                        listener.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
                        listener.handleUseItemOn(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false),
                                ++bot.sequence));
                        listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                        places++;
                    }
                }
                default -> {
                    listener.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(random.nextInt(7)));
                    listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                    if (player.onGround() && random.nextInt(3) == 0) {
                        player.jumpFromGround();
                    }
                }
            }
            actions++;
        }

        private void moveAndExplore(Bot bot, int tick) {
            final var player = bot.player;
            final var listener = player.connection;
            final int phase = (tick / 600) % 4;
            final double step = 1.25;
            double dx = 0.0;
            double dz = 0.0;
            if (bot.index % 4 < 2) {
                // Explorers (跑图): continuously walk outward in a rotating sweep to generate & load new chunks
                final double angle = (bot.index * 0.39) + (tick / 1200.0) * Math.PI * 0.5;
                dx = Math.cos(angle) * step;
                dz = Math.sin(angle) * step;
            } else {
                // Mergers/Splitters: oscillate toward/away from pair center to trigger DYNAMIC_CLUSTER merges and splits
                final int side = bot.index % 2;
                final double targetX = switch (phase) {
                    case 0, 1 -> bot.anchorX + (side == 0 ? -4 : 4);   // Converge -> merge islands
                    default -> bot.anchorX + (side == 0 ? -160 : 160); // Diverge -> split islands
                };
                final double diffX = targetX - player.getX();
                if (Math.abs(diffX) > 1.0) {
                    dx = Math.copySign(Math.min(step, Math.abs(diffX)), diffX);
                } else {
                    dz = random.nextDouble(-0.4, 0.4);
                }
            }
            final double nx = player.getX() + dx;
            final double nz = player.getZ() + dz;
            final int ny = Math.max(level.getMinBuildHeight() + 4,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(nx), (int) Math.floor(nz)));
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(
                    nx, ny, nz,
                    random.nextFloat() * 360.0f, random.nextFloat() * 80.0f - 40.0f, true));
            moves++;
        }

        private static void drain(Bot bot) {
            bot.channel.runPendingTasks();
            Object message;
            while ((message = bot.channel.readOutbound()) != null) {
                try {
                    if (message instanceof ClientboundKeepAlivePacket keepAlive) {
                        bot.player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
                    } else if (message instanceof ClientboundPlayerPositionPacket teleport) {
                        bot.player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.getId()));
                    }
                } finally {
                    ReferenceCountUtil.release(message);
                }
            }
        }

        private static long gcCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> gc.getCollectionCount()).sum();
        }

        private static long gcMillis() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> gc.getCollectionTime()).sum();
        }

        private static double percentile(long[] values, double percentile) {
            return values.length == 0 ? 0 : values[Math.max(0, (int) Math.ceil(values.length * percentile) - 1)] / 1.0e6;
        }

        void close() {
            if (closed) return;
            closed = true;
            NeoForge.EVENT_BUS.unregister(this);
            for (final Bot bot : bots) {
                level.getServer().getConnection().getConnections().remove(bot.connection());
                level.getServer().getPlayerList().remove(bot.player);
                bot.channel.finishAndReleaseAll();
            }
        }
    }
}
