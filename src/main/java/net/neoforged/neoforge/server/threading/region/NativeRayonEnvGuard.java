/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies optional native environment bounds before mods initialize native thread pools such as Rust's Rayon
 * (used by Sable's Rapier physics library).
 * <p>
 * In container environments where the host exposes many logical CPUs (e.g. 40+ cores) while the container's
 * CPU quota and JVM {@code -XX:ActiveProcessorCount} are much smaller, Rayon defaults to spawning one OS
 * thread per host logical CPU unless {@code RAYON_NUM_THREADS} is set in the process environment.
 * Operators on managed panels (such as Pterodactyl) often cannot set shell environment variables directly,
 * so this helper reads {@code -Dforgia.rayonNumThreads=<N>} (or defaults to a bounded thread count on Linux
 * when {@code RAYON_NUM_THREADS} is unset) and invokes libc {@code setenv("RAYON_NUM_THREADS", value, 0)}
 * via Minecraft's bundled JNA runtime without overwriting an operator-supplied environment variable.
 */
@ApiStatus.Internal
public final class NativeRayonEnvGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("NativeRayonEnvGuard");
    static final String PROPERTY_NAME = "forgia.rayonNumThreads";
    static final String ENV_NAME = "RAYON_NUM_THREADS";
    private static volatile boolean applied;

    private NativeRayonEnvGuard() {}

    public static void applyIfConfigured() {
        if (applied) {
            return;
        }
        applied = true;
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            return;
        }
        final String existing = System.getenv(ENV_NAME);
        if (existing != null && !existing.isBlank()) {
            return;
        }
        final int targetThreads = resolveTargetThreads(System.getProperty(PROPERTY_NAME));
        if (targetThreads <= 0) {
            return;
        }
        try {
            final int rc = setEnvViaJna(ENV_NAME, Integer.toString(targetThreads), 0);
            if (rc == 0) {
                LOGGER.info("Applied process environment {}={} before native physics initialization", ENV_NAME, targetThreads);
            } else {
                LOGGER.warn("libc setenv({}, {}, 0) returned non-zero status {}", ENV_NAME, targetThreads, rc);
            }
        } catch (Throwable failure) {
            LOGGER.warn("Could not apply {}={}: {}", ENV_NAME, targetThreads, failure.toString());
        }
    }

    static int resolveTargetThreads(String rawProperty) {
        if (rawProperty != null && !rawProperty.isBlank()) {
            try {
                return Integer.parseInt(rawProperty.trim());
            } catch (NumberFormatException ignored) {
                LOGGER.warn("Ignoring invalid -D{}='{}'", PROPERTY_NAME, rawProperty);
                return 0;
            }
        }
        // Only apply a conservative default when explicitly opted in via system property.
        return 0;
    }

    static int setEnvViaJna(String name, String value, int overwrite) throws ReflectiveOperationException {
        final ClassLoader cl = NativeRayonEnvGuard.class.getClassLoader();
        final Class<?> libraryClass = Class.forName("com.sun.jna.Library", false, cl);
        final Class<?> nativeClass = Class.forName("com.sun.jna.Native", false, cl);
        final Class<?> functionClass = Class.forName("com.sun.jna.Function", false, cl);
        final Class<?> nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary", false, cl);

        // Verify JNA is loadable, then call libc setenv(const char*, const char*, int) directly via Function.
        final Method getInstance = nativeLibraryClass.getMethod("getInstance", String.class);
        final Object libc = getInstance.invoke(null, "c");
        final Method getFunction = nativeLibraryClass.getMethod("getFunction", String.class);
        final Object setenvFn = getFunction.invoke(libc, "setenv");
        final Method invokeInt = functionClass.getMethod("invokeInt", Object[].class);
        final Object result = invokeInt.invoke(setenvFn, new Object[] { new Object[] { name, value, overwrite } });
        // Keep references to libraryClass/nativeClass unused warnings away while validating JNA presence
        if (libraryClass == null || nativeClass == null) {
            throw new IllegalStateException("JNA classes unavailable");
        }
        return (Integer) result;
    }

    static String getEnvViaJna(String name) throws ReflectiveOperationException {
        final ClassLoader cl = NativeRayonEnvGuard.class.getClassLoader();
        final Class<?> functionClass = Class.forName("com.sun.jna.Function", false, cl);
        final Class<?> nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary", false, cl);
        final Method getInstance = nativeLibraryClass.getMethod("getInstance", String.class);
        final Object libc = getInstance.invoke(null, "c");
        final Method getFunction = nativeLibraryClass.getMethod("getFunction", String.class);
        final Object getenvFn = getFunction.invoke(libc, "getenv");
        final Method invokeString = functionClass.getMethod("invokeString", Object[].class, boolean.class);
        return (String) invokeString.invoke(getenvFn, new Object[] { new Object[] { name }, Boolean.FALSE });
    }
}
