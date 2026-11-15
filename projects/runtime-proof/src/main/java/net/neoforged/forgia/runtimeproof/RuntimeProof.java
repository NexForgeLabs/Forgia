/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.runtimeproof;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Optional bootstrap VM evidence. The native service reads installed JVM methods, including hidden
 * classes. These immutable descriptions grant no execution or resource permission. A missing service,
 * unsupported runtime, incomplete snapshot or changed definition must remain a complete serial fence.
 */
public final class RuntimeProof {
    private static final int MAGIC = 0x46524731;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MEMBERS = 4096;

    private RuntimeProof() {}

    public static boolean available() {
        try {
            return RuntimeProof.class.getClassLoader() == null && ready() == MAGIC;
        } catch (UnsatisfiedLinkError absent) {
            return false;
        }
    }

    /** Actual installed bytecode, never a resource stream or bytes supplied by an adapter. */
    public static Definition definition(Class<?> actual) {
        Objects.requireNonNull(actual);
        if (!available()) return null;
        final byte[] snapshot = describe(actual);
        if (snapshot == null || snapshot.length > MAX_BYTES) return null;
        try (final var input = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            if (input.readInt() != MAGIC) return null;
            final long epoch = input.readLong();
            final int classStatus = input.readInt();
            final int poolCount = input.readInt();
            final byte[] pool = bytes(input);
            final int methodCount = count(input);
            final List<Method> methods = new ArrayList<>(methodCount);
            for (int index = 0; index < methodCount; ++index) {
                final int access = input.readInt();
                final String name = input.readUTF();
                final String descriptor = input.readUTF();
                final byte[] code = bytes(input);
                final int exceptionHandlers = input.readInt();
                if (exceptionHandlers < 0 || exceptionHandlers > 65535) return null;
                methods.add(new Method(access, name, descriptor, code, exceptionHandlers));
            }
            final int fieldCount = count(input);
            final List<Field> fields = new ArrayList<>(fieldCount);
            for (int index = 0; index < fieldCount; ++index) {
                fields.add(new Field(input.readInt(), input.readUTF(), input.readUTF()));
            }
            if (input.available() != 0 || poolCount < 1 || poolCount > 65535 || epoch != epoch()) return null;
            return new Definition(actual, epoch, classStatus, new ConstantPool(poolCount, pool), List.copyOf(methods), List.copyOf(fields));
        } catch (IOException | IllegalArgumentException incomplete) {
            return null;
        }
    }

    private static byte[] bytes(DataInputStream input) throws IOException {
        final int length = input.readInt();
        if (length < 0 || length > MAX_BYTES || length > input.available()) throw new IOException("Incomplete VM bytes");
        return input.readNBytes(length);
    }

    private static int count(DataInputStream input) throws IOException {
        final int count = input.readInt();
        if (count < 0 || count > MAX_MEMBERS) throw new IOException("VM member bound exceeded");
        return count;
    }

    public static Class<?>[] capturedCoreTypes(Class<?> anchor) {
        return available() && anchor != null ? knownCoreTypes(anchor) : null;
    }

    public static byte[] trustedDefinition(Class<?> actual) {
        return available() && actual != null ? definitionBytes(actual) : null;
    }

    /** Read only build-bundled Core integrity evidence from the bootstrap search path. */
    public static byte[] bundledCore(String internalName) {
        if (!available() || internalName == null || internalName.contains("..") || internalName.startsWith("/")) return null;
        try (final var input = RuntimeProof.class.getResourceAsStream("/META-INF/forgia/trusted-core/" + internalName + ".bin")) {
            if (input == null) return null;
            final byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            return bytes.length <= MAX_BYTES ? bytes : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    /** Resolves only an already loaded identity; does not call an arbitrary ClassLoader or initializer. */
    public static Class<?> loaded(Class<?> loaderAnchor, String binaryName) {
        if (!available() || loaderAnchor == null || binaryName == null) return null;
        return findLoaded(loaderAnchor, binaryName.replace('.', '/'));
    }

    public record Field(int access, String name, String descriptor) {}

    public record Method(int access, String name, String descriptor, byte[] code, int exceptionHandlers) {
        public Method {
            code = code.clone();
        }

        @Override
        public byte[] code() {
            return this.code.clone();
        }
    }

    public record Definition(Class<?> actual, long epoch, int classStatus, ConstantPool constants, List<Method> methods, List<Field> fields) {
        public Definition {
            methods = List.copyOf(methods);
            fields = List.copyOf(fields);
        }

        public boolean initialized() {
            return (this.classStatus & 4) != 0 && (this.classStatus & 8) == 0;
        }

        public boolean current() {
            return RuntimeProof.available() && this.epoch == RuntimeProof.epoch();
        }

        public Method method(String name, String descriptor) {
            Method found = null;
            for (final Method method : this.methods) {
                if (!method.name.equals(name) || !method.descriptor.equals(descriptor)) continue;
                if (found != null) return null;
                found = method;
            }
            return found;
        }
    }

    /** Bounded decoder for the actual VM constant pool; strings are descriptive, never authority. */
    public static final class ConstantPool {
        private final Object[] entries;
        private final int[] tags;

        private ConstantPool(int count, byte[] bytes) throws IOException {
            this.entries = new Object[count];
            this.tags = new int[count];
            try (final var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                for (int index = 1; index < count; ++index) {
                    final int tag = input.readUnsignedByte();
                    this.tags[index] = tag;
                    this.entries[index] = switch (tag) {
                        case 1 -> input.readUTF();
                        case 3, 4 -> input.readInt();
                        case 5, 6 -> input.readLong();
                        case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                        case 9, 10, 11, 12, 17, 18 -> new int[] { input.readUnsignedShort(), input.readUnsignedShort() };
                        case 15 -> new int[] { input.readUnsignedByte(), input.readUnsignedShort() };
                        default -> throw new IOException("Unsupported actual constant-pool entry");
                    };
                    if (tag == 5 || tag == 6) ++index;
                }
                if (input.available() != 0) throw new IOException("Trailing actual constant-pool bytes");
            }
        }

        private Object entry(int index, int tag) {
            if (index <= 0 || index >= this.entries.length || this.tags[index] != tag)
                throw new IllegalArgumentException("Actual bytecode has an invalid constant-pool reference");
            return this.entries[index];
        }

        public String className(int index) {
            return (String) this.entry((Integer) this.entry(index, 7), 1);
        }

        public Member member(int index) {
            if (index <= 0 || index >= this.tags.length || (this.tags[index] != 9 && this.tags[index] != 10 && this.tags[index] != 11))
                throw new IllegalArgumentException("Expected an actual field or method reference");
            final int[] reference = (int[]) this.entries[index];
            final int[] nameAndType = (int[]) this.entry(reference[1], 12);
            return new Member(this.tags[index], this.className(reference[0]),
                    (String) this.entry(nameAndType[0], 1), (String) this.entry(nameAndType[1], 1));
        }
    }

    public record Member(int kind, String owner, String name, String descriptor) {}

    /** Interception is conservative: arbitrary native code cannot join the supported closure runtime. */
    public static boolean supportedVm() {
        if (!enforcementAvailable()) return false;
        return System.getProperty("java.specification.version").equals("21")
                && System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }

    /** Called by the VM before an actual original-object access; Core alone supplies its existing authority check. */
    public interface AccessCheck {
        boolean allowed(Object root, boolean write);
    }

    /**
     * Installs interception, not a reservation. The callback must consult the original Core authority.
     * Callbacks cannot enter any protected mutable object, including through a second callback.
     */
    public static boolean protect(Object root, AccessCheck check) {
        return available() && root != null && check != null && protectRoot(root, check);
    }

    /**
     * Enforces permanent instance-field immutability for this actual object. All writes, including
     * writes by a current owner, are rejected; reads need no mutable-object authority. This does not
     * prove that references stored in the object lead to other immutable objects.
     */
    public static boolean protectImmutableValue(Object root) {
        return available() && root != null && freezeRoot(root);
    }

    /** Descriptive bridge readiness; semantic verification and the original kernel are still required. */
    public static boolean enforcementAvailable() {
        if (!available() || !enforcementReady()) return false;
        try {
            final var diagnostic = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (!Boolean.parseBoolean(diagnostic.getVMOption("DisableAttachMechanism").getValue())
                    || Boolean.parseBoolean(diagnostic.getVMOption("InlineUnsafeOps").getValue())
                    || !Boolean.parseBoolean(diagnostic.getVMOption("UseCompressedOops").getValue())
                    || !Boolean.parseBoolean(diagnostic.getVMOption("UseCompressedClassPointers").getValue())
                    || optionEnabled(diagnostic, "EnableJVMCI")
                    || optionEnabled(diagnostic, "WhiteBoxAPI"))
                return false;
            int agents = 0;
            for (final String input : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (input.startsWith("-javaagent:") || input.startsWith("-agentlib:") || input.startsWith("-Xrun")
                        || input.startsWith("-Xbootclasspath") || input.equals("--enable-preview") || input.startsWith("--enable-native-access"))
                    return false;
                if (input.startsWith("-agentpath:")) ++agents;
            }
            return agents == 1;
        } catch (IllegalArgumentException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean optionEnabled(com.sun.management.HotSpotDiagnosticMXBean diagnostic, String name) {
        try {
            return Boolean.parseBoolean(diagnostic.getVMOption(name).getValue());
        } catch (IllegalArgumentException absentVmFeature) {
            return false;
        }
    }

    /** Synchronizes original Core reservation publication with already-started intercepted accesses. */
    public static boolean beginCoreReservation() {
        return enforcementAvailable() && beginReservation();
    }

    public static void endCoreReservation() {
        endReservation();
    }

    public static native long epoch();

    private static native boolean protectRoot(Object root, AccessCheck check);

    private static native boolean freezeRoot(Object root);

    private static native boolean enforcementReady();

    private static native boolean beginReservation();

    private static native void endReservation();

    private static native int ready();

    private static native byte[] describe(Class<?> actual);

    private static native byte[] definitionBytes(Class<?> actual);

    private static native Class<?>[] knownCoreTypes(Class<?> anchor);

    private static native Class<?> findLoaded(Class<?> loaderAnchor, String internalName);
}
