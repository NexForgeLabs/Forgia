package dev.forgia.compat.createparallel;

import com.mojang.brigadier.CommandDispatcher;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-game probe command (/gantryprobe) that continuously tracks nearby GantryCarriageBlockEntity
 * and GantryContraptionEntity coordinates around the invoking player.
 */
public final class GantryProbeCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("GantryProbe");
    private static final Map<UUID, String> ACTIVE_PROBES = new ConcurrentHashMap<>();

    private GantryProbeCommand() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(GantryProbeCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(GantryProbeCommand::onServerTick);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("gantryprobe")
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                    final UUID id = player.getUUID();
                    if (ACTIVE_PROBES.remove(id) != null) {
                        player.sendSystemMessage(Component.literal("[GantryProbe] 已停止追踪附近的起重机取物器。"));
                    } else {
                        ACTIVE_PROBES.put(id, "");
                        player.sendSystemMessage(Component.literal("[GantryProbe] 已开启！正在持续检索周围 32 格内的起重机滑轮与动态结构坐标（再输入一次 /gantryprobe 关闭）。"));
                    }
                    return 1;
                })
                .then(Commands.literal("stop").executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ACTIVE_PROBES.remove(player.getUUID());
                    player.sendSystemMessage(Component.literal("[GantryProbe] 已停止。"));
                    return 1;
                })));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_PROBES.isEmpty()) {
            return;
        }
        for (final ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            final UUID id = player.getUUID();
            final String prev = ACTIVE_PROBES.get(id);
            if (prev == null) {
                continue;
            }
            final ServerLevel level = player.serverLevel();
            final BlockPos center = player.blockPosition();
            final AABB box = new AABB(center).inflate(32.0);

            final List<GantryContraptionEntity> entities = level.getEntitiesOfClass(GantryContraptionEntity.class, box);
            final StringBuilder sb = new StringBuilder();

            // Scan loaded chunks around player for GantryCarriageBlockEntity
            final int minCx = (center.getX() - 32) >> 4;
            final int maxCx = (center.getX() + 32) >> 4;
            final int minCz = (center.getZ() - 32) >> 4;
            final int maxCz = (center.getZ() + 32) >> 4;
            for (int cx = minCx; cx <= maxCx; ++cx) {
                for (int cz = minCz; cz <= maxCz; ++cz) {
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    for (final BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof GantryCarriageBlockEntity && be.getBlockPos().distSqr(center) <= 32 * 32) {
                            final BlockPos p = be.getBlockPos();
                            sb.append(String.format("滑轮方块=(%d,%d,%d) ", p.getX(), p.getY(), p.getZ()));
                        }
                    }
                }
            }

            for (final GantryContraptionEntity e : entities) {
                sb.append(String.format("实体#%d=(%.2f,%.2f,%.2f, t=%d) ",
                        e.getId(), e.getX(), e.getY(), e.getZ(), e.tickCount));
            }

            final String current = sb.length() == 0 ? "未检测到起重机" : sb.toString().trim();
            if (!current.equals(prev)) {
                ACTIVE_PROBES.put(id, current);
                final String line = String.format("[gt=%d] %s", level.getGameTime(), current);
                LOGGER.info("[GantryProbe] {}", line);
                player.sendSystemMessage(Component.literal(line));
            }
        }
    }
}
