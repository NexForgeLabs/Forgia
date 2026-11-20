/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.neotests.mixin;

/** Standalone fixture classes transformed by the Mixin GameTests. */
public final class MixinTargets {
    private MixinTargets() {}

    public static class Target {
        public static boolean applied() {
            return false;
        }

        public static Throwable crash() {
            return new RuntimeException("abc");
        }
    }

    public interface InterfaceTarget {
        String MAXINT = "maxint";

        default int getNumber(String argument) {
            return 12;
        }
    }
}
