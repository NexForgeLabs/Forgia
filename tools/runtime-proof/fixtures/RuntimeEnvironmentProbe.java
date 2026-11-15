
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class RuntimeEnvironmentProbe {
    public static void main(String[] args) throws Exception {
        final boolean expected = Boolean.parseBoolean(args[0]);
        if (RuntimeProof.enforcementAvailable() != expected) throw new AssertionError("Environment readiness mismatch");
        if (expected) {
            final var server = ManagementFactory.getPlatformMBeanServer();
            final var diagnostics = new ObjectName("com.sun.management:type=DiagnosticCommand");
            final Object version = server.invoke(diagnostics, "vmVersion", new Object[] { new String[0] }, new String[] { "[Ljava.lang.String;" });
            if (!(version instanceof String text) || !text.contains("VM")) throw new AssertionError("Read-only VM diagnostics unavailable");
            boolean refused = false;
            try {
                server.invoke(diagnostics, "jvmtiAgentLoad", new Object[] { new String[] { "/forgia-probe/never-load-this-agent.so" } }, new String[] { "[Ljava.lang.String;" });
            } catch (Exception error) {
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IllegalStateException && "Unsupported diagnostic command in the verified runtime".equals(cause.getMessage())) refused = true;
                }
                if (!refused) throw error;
            }
            if (!refused) throw new AssertionError("JMX native-agent route did not refuse before loading");
        }
        System.out.println("RUNTIME_ENVIRONMENT_CONTROL_PASS enforcement=" + expected + " jmxNativeAgentRefusal=" + expected);
    }
}
