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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

@ForEachTest(groups = "level.region")
public class DynamicPlayerSoakGameTests {
    @GameTest(batch = "dynamicPlayerSoak", timeoutTicks = 31_000)
    @EmptyTemplate("5x5x5")
    @TestHolder(description = "Twenty-minute wall-clock soak with embedded player connections and dynamic section dispatch")
    static void dynamicPlayerSoak(DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean("forgia.dynamicPlayerSoak")) {
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final RegionThreadingEngine engine = (RegionThreadingEngine) RegionThreadingManager.getOrThrow();
            helper.assertTrue(engine.regionPolicy() == RegionPolicy.DYNAMIC_CLUSTER && engine.regionLayout().regionChunkSize() == 8,
                    "Expected dynamic 8x8 sections");
            helper.assertTrue(engine.playerCriterionStorageEnabled(), "Expected player worker tick prerequisites");
            final Probe probe = new Probe(level, engine);
            helper.addEndListener(passed -> probe.close());
            probe.populate();
            NeoForge.EVENT_BUS.register(probe);
            final long duration = TimeUnit.SECONDS.toNanos(Math.clamp(Long.getLong("forgia.dynamicPlayerSoakSeconds", 1200L), 1L, 1200L));
            helper.startSequence().thenWaitUntil(() -> {
                helper.assertTrue(probe.elapsed() >= duration, "Measurement window is still running");
                helper.assertTrue(probe.bots.stream().allMatch(bot -> bot.connection().isConnected()), "A bot disconnected");
            }).thenExecute(probe::close).thenSucceed();
        });
    }

    private record Bot(ServerPlayer player, Connection connection, EmbeddedChannel channel, int x, int z) {}

    public static final class Probe {
        private static final Gson JSON = new Gson();
        private static final long TICK_NANOS = 50_000_000L;
        private final ServerLevel level;
        private final RegionThreadingEngine engine;
        private final List<Bot> bots = new ArrayList<>(32);
        private final SplittableRandom random = new SplittableRandom(4242424242L);
        private long started;
        private long lastSample;
        private long sampleTick;
        private long tickStart;
        private long actions;
        private boolean closed;

        Probe(ServerLevel level, RegionThreadingEngine engine) {
            this.level = level;
            this.engine = engine;
        }

        void populate() {
            var server = level.getServer();
            server.getPlayerList().setViewDistance(3);
            server.getPlayerList().setSimulationDistance(3);
            for (int i = 0; i < 32; i++) {
                final int x = 4160 + (i / 8 % 2) * 1024 + i % 4 * 3;
                final int z = 4160 + (i / 16) * 1024 + (i / 4 % 2) * 4;
                final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                final var profile = new GameProfile(new UUID(0xF0471AL, i), "soak_bot_" + i);
                final var cookie = CommonListenerCookie.createInitial(profile, false);
                final var player = new ServerPlayer(server, level, profile, cookie.clientInformation());
                final var connection = new Connection(PacketFlow.SERVERBOUND);
                final var channel = new EmbeddedChannel(connection);
                bots.add(new Bot(player, connection, channel, x, z));
                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                server.getConnection().getConnections().add(connection);
                player.teleportTo(x + 0.5, y, z + 0.5);
            }
            started = lastSample = System.nanoTime();
            sampleTick = server.getTickCount();
            System.out.println("DYNAMIC_SOAK_STARTED " + ProcessHandle.current().pid() + " " + bots.size());
        }

        long elapsed() {
            return System.nanoTime() - started;
        }

        @net.neoforged.bus.api.SubscribeEvent
        public void pre(ServerTickEvent.Pre event) {
            tickStart = System.nanoTime();
        }

        @net.neoforged.bus.api.SubscribeEvent
        public void post(ServerTickEvent.Post event) {
            final var server = event.getServer();
            final int tick = server.getTickCount();
            for (int i = 0; i < bots.size(); i++) {
                final Bot bot = bots.get(i);
                drain(bot);
                if ((tick + i) % 8 == 0 && bot.connection().isConnected()) act(bot);
            }
            final long now = System.nanoTime();
            if (now - lastSample >= TimeUnit.SECONDS.toNanos(10)) {
                final var row = new LinkedHashMap<String, Object>();
                row.put("pid", ProcessHandle.current().pid());
                row.put("elapsedSeconds", (now - started) / 1.0e9);
                row.put("tick", tick);
                row.put("tps", (tick - sampleTick) * 1.0e9 / (now - lastSample));
                final long[] times = Arrays.stream(server.getTickTimesNanos()).filter(value -> value > 0).sorted().toArray();
                row.put("meanMspt", Arrays.stream(times).average().orElse(0) / 1.0e6);
                row.put("p50Mspt", percentile(times, 0.50));
                row.put("p95Mspt", percentile(times, 0.95));
                row.put("p99Mspt", percentile(times, 0.99));
                row.put("maxMspt", percentile(times, 1));
                row.put("players", bots.stream().filter(bot -> bot.connection().isConnected()).count());
                row.put("actions", actions);
                row.put("loadedChunks", level.getChunkSource().getLoadedChunksCount());
                row.put("pendingChunkTasks", level.getChunkSource().getPendingTasksCount());
                row.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                row.put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> gc.getCollectionCount()).sum());
                row.put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> gc.getCollectionTime()).sum());
                row.put("islands", engine.loadedIslandDiagnostics(level, 32));
                row.put("regions", engine.diagnostics());
                row.put("playerTick", engine.playerTickDiagnostics(server));
                System.out.println("DYNAMIC_SOAK_SAMPLE " + JSON.toJson(row));
                lastSample = now;
                sampleTick = tick;
            }
            final long deadline = tickStart + TICK_NANOS;
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(remaining);
            }
        }

        private void act(Bot bot) {
            final var player = bot.player();
            final var listener = player.connection;
            switch (random.nextInt(5)) {
                case 0 -> listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                case 1 -> listener.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(random.nextInt(9)));
                case 2 -> {
                    if (player.onGround()) player.jumpFromGround();
                }
                default -> {
                    final double x = Math.clamp(player.getX() + random.nextDouble(-0.35, 0.35), bot.x() - 3, bot.x() + 3);
                    final double z = Math.clamp(player.getZ() + random.nextDouble(-0.35, 0.35), bot.z() - 3, bot.z() + 3);
                    listener.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(x, player.getY(), z,
                            random.nextFloat() * 360, random.nextFloat() * 100 - 50, player.onGround()));
                }
            }
            actions++;
        }

        private static void drain(Bot bot) {
            bot.channel().runPendingTasks();
            Object message;
            while ((message = bot.channel().readOutbound()) != null) {
                try {
                    if (message instanceof ClientboundKeepAlivePacket keepAlive) {
                        bot.player().connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
                    } else if (message instanceof ClientboundPlayerPositionPacket teleport) {
                        bot.player().connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.getId()));
                    }
                } finally {
                    ReferenceCountUtil.release(message);
                }
            }
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
                level.getServer().getPlayerList().remove(bot.player());
                bot.channel().finishAndReleaseAll();
            }
        }
    }
}
