
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class InstalledDefinitionProbe {
    private int count;

    private void original() {
        count++;
    }

    private void withHandler() {
        try {
            count++;
        } catch (RuntimeException problem) {
            count = 17;
        }
    }

    private static void require(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        System.out.println("BOOTSTRAP_DIAGNOSTIC loader=" + RuntimeProof.class.getClassLoader() + " epoch=" + RuntimeProof.epoch() + " available=" + RuntimeProof.available());
        require(RuntimeProof.available(), "bootstrap actual VM service absent");
        var source = new InstalledDefinitionProbe();
        Runnable function = source::original;
        var lambda = RuntimeProof.definition(function.getClass());
        var owner = RuntimeProof.definition(InstalledDefinitionProbe.class);
        require(lambda != null && owner != null, "actual definitions unavailable");
        var body = lambda.method("run", "()V");
        require(body != null && body.exceptionHandlers() == 0, "hidden body absent");
        byte[] bytes = body.code();
        require(bytes.length == 8, "unexpected hidden body");
        var call = lambda.constants().member(((bytes[5] & 255) << 8) | (bytes[6] & 255));
        require(call.name().equals("original") && call.descriptor().equals("()V"), "wrong actual hidden call");
        require(owner.method("withHandler", "()V").exceptionHandlers() == 1, "exception table omitted");
        require(owner.method("original", "()V").exceptionHandlers() == 0, "spurious handler");
        require(RuntimeProof.loaded(function.getClass(), "InstalledDefinitionProbe") == InstalledDefinitionProbe.class, "actual initiating loader resolution");
        require(RuntimeProof.loaded(function.getClass(), "never.Loaded") == null, "fabricated loaded class");
        byte[] copy = body.code();
        copy[0] = 0;
        require(body.code()[0] != 0, "mutable method bytes");
        function.run();
        require(source.count == 1, "original function changed");
        require(lambda.current() && owner.current(), "lost epoch");
        System.out.println("ACTUAL_INSTALLED_DEFINITION_PASS hidden=" + function.getClass().isHidden() + " handler=1 originalBodies=1 noProofGranted=true");
    }
}
