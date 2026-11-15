
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class RedefinitionAttemptProbe {
    private static native int attempt(Class<?> target, byte[] replacement);

    public static void main(String[] args) throws Exception {
        final var victim = new RedefinitionVictim();
        if (!RuntimeProof.enforcementAvailable() || victim.original() != 1) throw new AssertionError("Initial actual service/body missing");
        final var before = RuntimeProof.definition(victim.getClass());
        if (before == null || !before.current()) throw new AssertionError("Missing original installed method evidence");
        final int result = attempt(victim.getClass(), Files.readAllBytes(Path.of(args[0])));
        // JVMTI_ERROR_INVALID_CLASS_FORMAT=60. Merely detecting an installed replacement is insufficient.
        if (result != 60 || victim.original() != 1) throw new AssertionError("Replacement escaped refusal: " + result);
        if (RuntimeProof.available() || before.current()) throw new AssertionError("Old proof survived a modification attempt");
        System.out.println("ACTUAL_JVMTI_REDEFINITION_REFUSED_BEFORE_INSTALL error=60 originalBody=1 replacementBodyNeverExecuted=true oldProofInvalidated=true noCoreMutationAPI=true");
    }
}
