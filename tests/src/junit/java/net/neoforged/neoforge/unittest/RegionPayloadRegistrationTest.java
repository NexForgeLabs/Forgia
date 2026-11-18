/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.Test;

class RegionPayloadRegistrationTest {
    private record Payload(Type<Payload> type) implements CustomPacketPayload {}
    private record Impostor(Type<Payload> type) implements CustomPacketPayload {}

    @SuppressWarnings("unchecked")
    private static Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> registrations() throws Exception {
        var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        return (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(HandlerThread.class)
    void declarationIsOneExactServerPlayRegistrationAndDoesNotChangeRegistrarDefaults(HandlerThread thread) throws Exception {
        var registry = registrations();
        var setup = NetworkRegistry.class.getDeclaredField("setup");
        setup.setAccessible(true);
        boolean prior = setup.getBoolean(null);
        var id = ResourceLocation.fromNamespaceAndPath("region_api_test", "p" + UUID.randomUUID().toString().replace("-", ""));
        var other = id.withSuffix("_ordinary");
        var declared = new CustomPacketPayload.Type<Payload>(id);
        var ordinary = new CustomPacketPayload.Type<Payload>(other);
        StreamCodec<RegistryFriendlyByteBuf, Payload> codec = StreamCodec.unit(new Payload(declared));
        setup.setBoolean(null, false);
        try {
            var registrar = new PayloadRegistrar(" 1 ").optional().executesOn(thread);
            assertSame(registrar, registrar.playToServerRegion(declared, codec, Payload.class, (payload, context) -> fail("Registration must not run a handler")));
            registrar.playToServer(ordinary, codec, (payload, context) -> fail("Registration must not run a handler"));
            var registration = registry.get(ConnectionProtocol.PLAY).get(id);
            assertEquals(List.of(ConnectionProtocol.PLAY), registration.protocols());
            assertEquals(java.util.Optional.of(PacketFlow.SERVERBOUND), registration.flow());
            assertTrue(registration.optional());
            assertEquals("1", registration.version());
            assertFalse(registry.get(ConnectionProtocol.CONFIGURATION).containsKey(id));
            var handler = assertInstanceOf(RegionThreadPayloadHandler.class, registration.handler());
            assertTrue(NetworkRegistry.isRegionPayloadHandler(id, handler));
            assertFalse(NetworkRegistry.isRegionPayloadHandler(other, handler));
            assertTrue(handler.matches(new Payload(declared)));
            assertFalse(handler.matches(new Impostor(declared)));
            assertFalse(handler.matches(new Payload(ordinary)));
            assertFalse(NetworkRegistry.isRegionPayloadHandler(id, new RegionThreadPayloadHandler<>(declared, Payload.class, (p, c) -> {})));
            assertEquals(thread == HandlerThread.MAIN, registry.get(ConnectionProtocol.PLAY).get(other).handler() instanceof MainThreadPayloadHandler);
            assertThrows(UnsupportedOperationException.class, () -> registrar.playToServerRegion(declared, codec, Payload.class, (p, c) -> {}));
            setup.setBoolean(null, true);
            assertThrows(UnsupportedOperationException.class, () -> registrar.playToServerRegion(new CustomPacketPayload.Type<>(id.withSuffix("_late")), codec, Payload.class, (p, c) -> {}));
        } finally {
            registry.values().forEach(map -> { map.remove(id); map.remove(other); map.remove(id.withSuffix("_late")); });
            setup.setBoolean(null, prior);
        }
    }
}
