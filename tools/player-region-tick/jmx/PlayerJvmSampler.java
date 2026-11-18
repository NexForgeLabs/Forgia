import com.sun.tools.attach.VirtualMachine;
import com.sun.management.ThreadMXBean;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** External standard JVM management only: no game classes, event listeners or game permissions. */
public final class PlayerJvmSampler {
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof long[] array) return java.util.Arrays.toString(array);
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> list) {
            var rows = new ArrayList<String>();
            list.forEach(item -> rows.add(json(item)));
            return String.join(",", rows).transform(text -> "[" + text + "]");
        }
        String text = value.toString();
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    public static void main(String[] args) throws Exception {
        String pid = args[0];
        Path output = Path.of(args[1]), labelFile = Path.of(args[2]), stop = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Retain prior JVM sample evidence");
        var vm = VirtualMachine.attach(pid);
        String address;
        try { address = vm.startLocalManagementAgent(); } finally { vm.detach(); }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
                var writer = new PrintWriter(Files.newBufferedWriter(output, java.nio.file.StandardOpenOption.CREATE_NEW))) {
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            var ticks = new ObjectName("net.minecraft.server:type=Server");
            var threads = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            var memory = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            var runtime = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            var collectors = ManagementFactory.getPlatformMXBeans(connection, GarbageCollectorMXBean.class);
            if (!threads.isThreadAllocatedMemorySupported() || !threads.isThreadAllocatedMemoryEnabled())
                throw new IllegalStateException("Required native thread allocation monitoring is unavailable");
            long sequence = 0;
            while (!Files.exists(stop)) {
                long start = System.nanoTime();
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("seq", ++sequence);
                sample.put("wallMillis", System.currentTimeMillis());
                sample.put("monitorNanos", start);
                sample.put("window", Files.exists(labelFile) ? Files.readString(labelFile).strip() : "setup");
                sample.put("uptimeMillis", runtime.getUptime());
                sample.put("tickTimesNanos", connection.getAttribute(ticks, "tickTimes"));
                sample.put("smoothedTickMillis", connection.getAttribute(ticks, "averageTickTime"));
                long[] ids = threads.getAllThreadIds();
                long[] allocated = threads.getThreadAllocatedBytes(ids);
                long[] cpu = threads.getThreadCpuTime(ids);
                ThreadInfo[] infos = threads.getThreadInfo(ids, sequence % 5 == 0 ? 32 : 0);
                List<Object> threadRows = new ArrayList<>();
                for (int index = 0; index < ids.length; index++) {
                    if (infos[index] == null) continue;
                    var info = infos[index];
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", ids[index]); row.put("name", info.getThreadName());
                    row.put("allocatedBytes", allocated[index]); row.put("cpuNanos", cpu[index]); row.put("state", info.getThreadState().toString());
                    if (sequence % 5 == 0 && (info.getThreadName().equals("Server thread") || info.getThreadName().contains("Region Worker")))
                        row.put("stack", java.util.Arrays.stream(info.getStackTrace()).map(Object::toString).toList());
                    threadRows.add(row);
                }
                sample.put("threads", threadRows);
                var heap = memory.getHeapMemoryUsage();
                sample.put("heapUsedBytes", heap.getUsed()); sample.put("heapCommittedBytes", heap.getCommitted());
                sample.put("gc", collectors.stream().map(gc -> Map.of("name", gc.getName(), "count", gc.getCollectionCount(), "millis", gc.getCollectionTime())).toList());
                sample.put("sampleDurationNanos", System.nanoTime() - start);
                writer.println(json(sample)); writer.flush();
                if (writer.checkError()) throw new IllegalStateException("JVM sample write failed");
                Thread.sleep(Math.max(1, 200 - (System.nanoTime() - start) / 1_000_000));
            }
        }
    }
}
