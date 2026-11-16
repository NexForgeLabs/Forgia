/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Dependency ratchets for extracted runtime slices, not a claim that all legacy packages are isolated. */
class RegionThreadingArchitectureTest {
    private static final String REGION = "net/neoforged/neoforge/server/threading/region/";

    @Test
    void chunkOrchestrationDependsOnItsHostRatherThanTheEngineOrConcretePool() throws Exception {
        for (final Class<?> type : classes("RegionChunkTickCoordinator", "RegionChunkTickCoordinator$Host")) {
            assertNoReference(type, REGION + "RegionThreadingEngine", REGION + "RegionWorkerPool",
                    "net/neoforged/neoforge/server/threading/RegionThreadingManager");
        }
    }

    @Test
    void chunkOrchestrationDoesNotReachTheBlockEntityAuthorityOrProofBackend() throws Exception {
        for (final Class<?> type : classes("RegionChunkTickCoordinator", "RegionChunkTickCoordinator$Host")) {
            assertNoReference(type, REGION + "BlockEntityExecutionEngine", REGION + "BlockEntityResourceOwnership",
                    REGION + "CoreModTickerVerifier");
        }
    }

    @Test
    void constructionOptionsRemainValuesRatherThanRuntimeOrConfigurationLookups() throws Exception {
        for (final Class<?> type : classes("RegionThreadingOptions", "RegionThreadingOptions$Workers", "RegionThreadingOptions$TickSlices")) {
            assertNoReference(type, REGION + "RegionThreadingEngine", REGION + "RegionWorkerPool", REGION + "RegionState",
                    "net/neoforged/neoforge/common/ForgiaConfig", "net/minecraft/server/level/ServerLevel",
                    "net/minecraft/server/MinecraftServer", "java/lang/ThreadLocal");
        }
    }

    @Test
    void workerInfrastructureDoesNotDependOnTheNewChunkCoordinator() throws Exception {
        assertNoReference(classes("RegionWorkerPool")[0], REGION + "RegionChunkTickCoordinator");
    }

    @Test
    void diagnosticAggregationDoesNotReachEngineWorldOrExecutionAuthority() throws Exception {
        for (final Class<?> type : diagnosticTypes()) {
            assertNoReference(type, REGION + "RegionThreadingEngine", REGION + "RegionState", REGION + "RegionWorkAdmission",
                    REGION + "RegionChunkTickCoordinator", REGION + "BlockEntityExecutionEngine", REGION + "BlockEntityResourceOwnership",
                    REGION + "CoreModTickerVerifier", "net/neoforged/neoforge/server/threading/RegionThreadingManager",
                    "net/minecraft/server/MinecraftServer", "net/minecraft/server/level/ServerLevel", "java/lang/ThreadLocal");
        }
    }

    @Test
    void diagnosticAggregationOnlyReadsItsBorrowedCountersAndPhaseState() throws Exception {
        final Set<String> forbidden = Set.of("set", "getAndSet", "lazySet", "compareAndSet", "weakCompareAndSet",
                "addAndGet", "getAndAdd", "incrementAndGet", "getAndIncrement", "decrementAndGet", "getAndDecrement",
                "updateAndGet", "getAndUpdate", "accumulateAndGet", "getAndAccumulate", "setPlain", "setOpaque", "setRelease",
                "beginGlobalPhase", "endGlobalPhase", "beginRegionPhase", "endRegionPhase",
                "beginOwnership", "endOwnership", "clearRunning", "execute", "submitAndAwait");
        for (final Class<?> type : diagnosticTypes()) {
            final Set<String> constants = utf8Constants(type);
            for (final String method : forbidden) {
                assertFalse(constants.contains(method), () -> type.getName() + " must not reference writer/dispatch method " + method);
            }
        }
    }

    private static Class<?>[] diagnosticTypes() throws ClassNotFoundException {
        return classes("RegionDiagnosticsCollector", "RegionDiagnosticsCollector$BorderSources",
                "RegionDiagnosticsCollector$BlockEventSources", "RegionDiagnosticsCollector$GameEventSources",
                "RegionDiagnosticsCollector$SaveSources", "RegionDiagnosticsCollector$PhaseCounts");
    }

    private static Class<?>[] classes(String... names) throws ClassNotFoundException {
        final Class<?>[] types = new Class<?>[names.length];
        for (int index = 0; index < names.length; ++index) {
            types[index] = Class.forName(REGION.replace('/', '.') + names[index]);
        }
        return types;
    }

    private static void assertNoReference(Class<?> type, String... forbiddenTypes) throws IOException {
        final Set<String> constants = utf8Constants(type);
        for (final String forbidden : forbiddenTypes) {
            assertFalse(constants.stream().anyMatch(value -> value.contains(forbidden)),
                    () -> type.getName() + " must not depend on " + forbidden.replace('/', '.'));
        }
    }

    /** Includes field/method descriptors as well as class entries; needs no new bytecode library dependency. */
    private static Set<String> utf8Constants(Class<?> type) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            final DataInputStream input = new DataInputStream(stream);
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            input.readUnsignedShort();
            final int count = input.readUnsignedShort();
            final Set<String> strings = new HashSet<>();
            for (int index = 1; index < count; ++index) {
                final int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> strings.add(input.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        ++index;
                    }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unknown constant-pool tag " + tag + " in " + resource);
                }
            }
            return strings;
        }
    }
}
