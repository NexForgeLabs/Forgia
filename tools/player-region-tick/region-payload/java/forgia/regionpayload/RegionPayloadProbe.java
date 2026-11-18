package forgia.regionpayload;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;

/** An ordinary mod using the public declaration. All game actions arrive as real TCP payloads. */
@Mod("forgia_region_payload_probe")
public final class RegionPayloadProbe {
    private static final Gson GSON = new Gson();
    private static final ConcurrentLinkedQueue<Map<String, Object>> EVENTS = new ConcurrentLinkedQueue<>();
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> TRANSFERS = new ConcurrentHashMap<>();

    public record Local(String text) implements CustomPacketPayload {
        static final Type<Local> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("forgia_region", "local"));
        static final StreamCodec<RegistryFriendlyByteBuf, Local> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Local::text, Local::new);
        @Override public Type<Local> type() { return TYPE; }
    }
    public record Main(String text) implements CustomPacketPayload {
        static final Type<Main> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("forgia_region", "main"));
        static final StreamCodec<RegistryFriendlyByteBuf, Main> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Main::text, Main::new);
        @Override public Type<Main> type() { return TYPE; }
    }
    public record Network(String text) implements CustomPacketPayload {
        static final Type<Network> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("forgia_region", "network"));
        static final StreamCodec<RegistryFriendlyByteBuf, Network> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Network::text, Network::new);
        @Override public Type<Network> type() { return TYPE; }
    }
    public record Reply(String text) implements CustomPacketPayload {
        static final Type<Reply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("forgia_region", "reply"));
        static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Reply::text, Reply::new);
        @Override public Type<Reply> type() { return TYPE; }
    }

    private static void event(String kind, String text, ServerPlayer player, boolean worker, boolean main, boolean network) {
        COUNTS.computeIfAbsent(kind, ignored -> new AtomicInteger()).incrementAndGet();
        EVENTS.add(Map.of("kind", kind, "text", text, "player", player.getUUID().toString(), "instance", System.identityHashCode(player),
                "thread", Thread.currentThread().getName(), "worker", worker, "main", main, "network", network, "nano", System.nanoTime()));
        if (EVENTS.size() > 4096) throw new IllegalStateException("Bounded proof event budget exceeded");
    }

    public RegionPayloadProbe(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent registration) -> {
            var registrar = registration.registrar("1").optional();
            registrar.playToServerRegion(Local.TYPE, Local.CODEC, Local.class, (payload, context) -> {
                ServerPlayer player = context.player();
                boolean worker = RegionWorkerScope.isNativeWorkerThread();
                if (worker != context.isRegionWorker() || worker != (RegionPlayerStateScope.current() != null)) throw new AssertionError("Actual owner scopes disagree");
                event("local", payload.text(), player, worker, player.server.isSameThread(), false);
                if (payload.text().startsWith("hold")) {
                    try {
                        Files.writeString(Path.of("region-payload-" + payload.text() + ".json"), GSON.toJson(Map.of("worker", worker, "thread", Thread.currentThread().getName())), StandardOpenOption.CREATE_NEW);
                        Thread.sleep(800);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                } else if (payload.text().startsWith("transfer_") || payload.text().equals("boom")) {
                    int ordinal = TRANSFERS.computeIfAbsent(player.getUUID(), ignored -> new AtomicInteger()).getAndIncrement();
                    int from = ordinal % 2 == 0 ? 9 : 10, to = ordinal % 2 == 0 ? 10 : 9;
                    var inventory = player.getInventory();
                    ItemStack source = inventory.getItem(from), target = inventory.getItem(to);
                    if (!source.is(Items.DIAMOND) || source.getCount() < 1 || !target.isEmpty() && !target.is(Items.DIAMOND)) throw new AssertionError("Bad conservation fixture");
                    source.shrink(1);
                    if (target.isEmpty()) inventory.setItem(to, new ItemStack(Items.DIAMOND));
                    else target.grow(1);
                    inventory.setChanged();
                    player.inventoryMenu.broadcastChanges();
                }
                context.reply(new Reply("reply:" + payload.text()));
                context.enqueueWork(main -> {
                    var sender = (ServerPlayer) main.player();
                    if (!sender.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread() || RegionPlayerStateScope.current() != null) throw new AssertionError("enqueueWork left MAIN");
                    event("completion", payload.text(), sender, false, true, false);
                    sender.sendSystemMessage(Component.literal("region_payload_done:" + payload.text()));
                });
                if (payload.text().equals("boom")) throw new IllegalArgumentException("declared-proof-failure-after-transfer");
                event("return", payload.text(), player, worker, player.server.isSameThread(), false);
            });
            registrar.playToServer(Main.TYPE, Main.CODEC, (payload, context) -> {
                var player = (ServerPlayer) context.player();
                if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new AssertionError("Default MAIN changed");
                event("main", payload.text(), player, false, true, false);
            });
            registrar.executesOn(HandlerThread.NETWORK).playToServer(Network.TYPE, Network.CODEC, (payload, context) -> {
                var player = (ServerPlayer) context.player();
                boolean network = context.connection().channel().eventLoop().inEventLoop();
                if (!network || player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new AssertionError("NETWORK changed thread");
                event("network", payload.text(), player, false, false, network);
                context.enqueueWork(() -> event("network-main", payload.text(), player, false, player.server.isSameThread(), false));
            });
            registrar.playToClient(Reply.TYPE, Reply.CODEC, (payload, context) -> {});
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(Commands.literal("region_payload_dump").requires(source -> source.hasPermission(4)).executes(context -> {
            try {
                Files.writeString(Path.of("region-payload-events.json"), GSON.toJson(Map.of("events", EVENTS, "counts", COUNTS)));
                context.getSource().sendSuccess(() -> Component.literal("region payload proof dumped"), false);
                return 1;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        })));
    }
}
