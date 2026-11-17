package forgia.networkcontract;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** Explicit unknown Mod; original NETWORK and MAIN contracts must survive whole-call serial fallback. */
@Mod("forgia_network_contract")
public final class NetworkContract {
    static final AtomicInteger total = new AtomicInteger(), onServer = new AtomicInteger(), onNetwork = new AtomicInteger(), main = new AtomicInteger(), wrongLoop = new AtomicInteger(), wrongOrder = new AtomicInteger();
    static final java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public record Probe(String text) implements CustomPacketPayload {
        static final Type<Probe> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("forgia_network", "probe"));
        static final StreamCodec<RegistryFriendlyByteBuf, Probe> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Probe::text, Probe::new);
        @Override public Type<Probe> type() { return TYPE; }
    }
    public NetworkContract(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").optional().executesOn(HandlerThread.NETWORK).playToServer(Probe.TYPE, Probe.CODEC, (packet, context) -> {
            int ordinal = total.getAndIncrement();
            if (packet.text().startsWith("network_") && !packet.text().equals("network_" + ordinal)) wrongOrder.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            if (!context.connection().channel().eventLoop().inEventLoop()) wrongLoop.incrementAndGet();
            boolean server = context.listener().getMainThreadEventLoop().isSameThread();
            if (server) {
                if (onServer.incrementAndGet() == 1) {
                    try { Files.writeString(Path.of("network-contract-violation.json"), new Gson().toJson(Map.of("thread",Thread.currentThread().getName(),"payload",packet.text(),"serverThread",true,"stack",java.util.Arrays.stream(Thread.currentThread().getStackTrace()).map(Object::toString).toList())),StandardOpenOption.CREATE_NEW); }
                    catch (java.io.IOException error) { throw new IllegalStateException(error); }
                }
            } else onNetwork.incrementAndGet();
            context.enqueueWork(() -> {
                if (!context.listener().getMainThreadEventLoop().isSameThread()) throw new AssertionError("Original enqueueWork main contract changed");
                main.incrementAndGet();
            });
        }));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(Commands.literal("network_contract_counts").requires(s -> s.hasPermission(4)).executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(new Gson().toJson(Map.of("total",total.get(),"onServer",onServer.get(),"onNetwork",onNetwork.get(),"main",main.get(),"wrongNetworkLoop",wrongLoop.get(),"threads",threads,"wrongOrder",wrongOrder.get()))),false);return 1;
        })));
    }
}
