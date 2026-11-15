
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.nio.file.Path;

public final class RuntimeNativeLibraryProbe {
    private static native int originalNativeBody();

    public static void main(String[] args) {
        final boolean refusalExpected = Boolean.parseBoolean(args[1]);
        boolean refused = false;
        try {
            System.load(Path.of(args[0]).toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError expected) {
            if (!refusalExpected) throw expected;
            refused = true;
        }
        if (refusalExpected != refused) throw new AssertionError("Native library boundary mismatch");
        if (!refused && originalNativeBody() != 7) throw new AssertionError("Native control body changed");
        System.out.println("NATIVE_LIBRARY_CONTROL_PASS refused=" + refused);
    }
}
