/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.ext.rayonguard;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Sets and reads process-level native environment variables so Rust/C native libraries loaded later in
 * the same JVM process observe the updated value via libc {@code getenv} / Win32 {@code _putenv_s}.
 * <p>
 * Uses JNA (bundled in the NeoForge/Minecraft launch classpath) to avoid JDK restricted-FFM issues across
 * Java versions and module configurations.
 */
public final class NativeEnv {
    private NativeEnv() {}

    public record ApplyResult(boolean applied, @Nullable String previousValue, String effectiveValue, String detail) {}

    private interface PosixLibC extends Library {
        PosixLibC INSTANCE = Native.load(Platform.C_LIBRARY_NAME, PosixLibC.class);

        int setenv(String name, String value, int overwrite);

        @Nullable
        String getenv(String name);
    }

    private interface WindowsUcrt extends Library {
        WindowsUcrt INSTANCE = Native.load("ucrtbase", WindowsUcrt.class);

        int _putenv_s(String name, String value);

        @Nullable
        String getenv(String name);
    }

    private interface WindowsMsvcrt extends Library {
        WindowsMsvcrt INSTANCE = Native.load("msvcrt", WindowsMsvcrt.class);

        int _putenv_s(String name, String value);

        @Nullable
        String getenv(String name);
    }

    @Nullable
    public static String getNativeEnv(String name) {
        if (isWindows()) {
            try {
                return WindowsUcrt.INSTANCE.getenv(name);
            } catch (Throwable ignored) {
                return WindowsMsvcrt.INSTANCE.getenv(name);
            }
        }
        return PosixLibC.INSTANCE.getenv(name);
    }

    public static ApplyResult setNativeEnv(String name, String value, boolean overwrite) {
        try {
            final String previous = getNativeEnv(name);
            if (previous != null && !previous.isEmpty() && !overwrite) {
                return new ApplyResult(false, previous, previous, "kept existing environment variable");
            }
            final int rc;
            if (isWindows()) {
                int ucrtRc = -1;
                try {
                    ucrtRc = WindowsUcrt.INSTANCE._putenv_s(name, value);
                } catch (Throwable ignored) {
                    // Fallback to msvcrt below.
                }
                int msvcrtRc = -1;
                try {
                    msvcrtRc = WindowsMsvcrt.INSTANCE._putenv_s(name, value);
                } catch (Throwable ignored) {
                    // Ignore if only ucrtbase is present.
                }
                rc = (ucrtRc == 0 || msvcrtRc == 0) ? 0 : -1;
            } else {
                rc = PosixLibC.INSTANCE.setenv(name, value, overwrite ? 1 : 0);
            }
            if (rc != 0) {
                return new ApplyResult(false, previous, previous != null ? previous : "",
                        "native setenv returned rc=" + rc);
            }
            final String observed = getNativeEnv(name);
            return new ApplyResult(true, previous, observed != null ? observed : value, "setenv succeeded");
        } catch (Throwable ex) {
            return new ApplyResult(false, null, "", "native setenv unavailable: " + ex);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
