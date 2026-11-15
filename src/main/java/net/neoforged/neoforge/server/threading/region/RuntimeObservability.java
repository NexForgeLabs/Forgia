/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;

/** Observes native tick boundaries. Holds no strong server/Level reference and creates no executor. */
@ApiStatus.Internal
public final class RuntimeObservability {
    private static State current;

    public static boolean enabled() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.enableRuntimeDiagnostics.get()
                : ForgiaConfig.SERVER.enableRuntimeDiagnostics.getDefault();
    }

    private static boolean jvmSamplingEnabled() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.enableRuntimeJvmSampling.get()
                : ForgiaConfig.SERVER.enableRuntimeJvmSampling.getDefault();
    }

    private RuntimeObservability() {}

    public static void recordServerTick(MinecraftServer server) {
        if (!enabled()) {
            if (current != null) current = null;
            if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)
                engine.setRuntimeTimingEnabled(false);
            return;
        }
        if (current == null || current.server.get() != server) current = new State(server);
        final long now = System.nanoTime();
        current.history.record(now, server.getTickTimesNanos()[Math.floorMod(server.getTickCount(), 100)]);
        if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)
            engine.setRuntimeTimingEnabled(true);
        if (jvmSamplingEnabled()) {
            if (now - current.lastJvmSample >= 1_000_000_000L) current.sampleJvm(server, now);
        } else {
            current.lastJvmSample = 0;
            current.jvm = JvmSample.UNAVAILABLE;
        }
    }

    /** Commands call on the authoritative server thread, exactly like native tick-ring access. */
    public static RuntimeTickHistory.Summary history(MinecraftServer server, int seconds) {
        if (current == null || current.server.get() != server)
            return new RuntimeTickHistory.Summary(seconds, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, 0, false);
        return current.history.snapshot(System.nanoTime(), seconds);
    }

    public static JvmSample jvmSample(MinecraftServer server) {
        return !enabled() || !jvmSamplingEnabled() || current == null || current.server.get() != server ? JvmSample.UNAVAILABLE : current.jvm;
    }

    public static void clear(MinecraftServer server) {
        if (current != null && current.server.get() == server) current = null;
    }

    public record JvmSample(double intervalSeconds, double mainCpuPercent, double workerCpuPercentOfOneCore,
            double allocatedMiBPerSecond, long gcCollections, long gcCollectionMillis, long heapBytes) {
        private static final JvmSample UNAVAILABLE = new JvmSample(Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, -1, -1);
    }

    private static final class State {
        private final WeakReference<MinecraftServer> server;
        private final RuntimeTickHistory history = new RuntimeTickHistory();
        private final java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final java.lang.management.MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final List<java.lang.management.GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        private long lastJvmSample;
        private long lastMainCpu = -1, lastWorkerCpu = -1, lastAllocated = -1, lastGcCount, lastGcMillis;
        private JvmSample jvm = JvmSample.UNAVAILABLE;
        private List<Long> previousWorkerIds = List.of();

        private State(MinecraftServer server) {
            this.server = new WeakReference<>(server);
        }

        private void sampleJvm(MinecraftServer server, long now) {
            final boolean cpuAvailable = this.threads.isThreadCpuTimeSupported() && this.threads.isThreadCpuTimeEnabled();
            final long mainCpu = cpuAvailable ? this.threads.getThreadCpuTime(Thread.currentThread().threadId()) : -1;
            long workerCpu = -1;
            long allocated = -1;
            final var workers = RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine
                    ? engine.workerRuntimeSnapshot().workers() : List.<WorkerRuntimeSnapshot.Worker>of();
            final List<Long> ids = workers.stream().map(WorkerRuntimeSnapshot.Worker::id).toList();
            if (!ids.equals(this.previousWorkerIds)) {
                this.lastWorkerCpu = -1;
                this.lastAllocated = -1;
                this.previousWorkerIds = ids;
            }
            if (cpuAvailable) {
                workerCpu = 0;
                for (final var worker : workers) {
                    if (worker.cpuNanos() < 0) { workerCpu = -1; break; }
                    workerCpu += worker.cpuNanos();
                }
            }
            if (this.threads instanceof com.sun.management.ThreadMXBean allocations && allocations.isThreadAllocatedMemorySupported()
                    && allocations.isThreadAllocatedMemoryEnabled()) {
                allocated = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
                for (final var worker : workers) {
                    final long bytes = allocations.getThreadAllocatedBytes(worker.id());
                    if (bytes < 0) { allocated = -1; break; }
                    allocated += bytes;
                }
            }
            long gcCount = 0, gcMillis = 0;
            for (final var gc : this.collectors) {
                if (gc.getCollectionCount() >= 0) gcCount += gc.getCollectionCount();
                if (gc.getCollectionTime() >= 0) gcMillis += gc.getCollectionTime();
            }
            final long elapsed = now - this.lastJvmSample;
            if (this.lastJvmSample != 0) {
                this.jvm = new JvmSample(elapsed / 1e9, percent(mainCpu, this.lastMainCpu, elapsed),
                        percent(workerCpu, this.lastWorkerCpu, elapsed),
                        allocated >= 0 && this.lastAllocated >= 0 && allocated >= this.lastAllocated
                                ? (allocated - this.lastAllocated) * 1e9 / elapsed / (1024.0 * 1024.0) : Double.NaN,
                        gcCount - this.lastGcCount, gcMillis - this.lastGcMillis, this.memory.getHeapMemoryUsage().getUsed());
            }
            this.lastJvmSample = now;
            this.lastMainCpu = mainCpu;
            this.lastWorkerCpu = workerCpu;
            this.lastAllocated = allocated;
            this.lastGcCount = gcCount;
            this.lastGcMillis = gcMillis;
        }

        private static double percent(long value, long previous, long elapsed) {
            return value < 0 || previous < 0 || value < previous ? Double.NaN : (value - previous) * 100.0 / elapsed;
        }
    }
}
