
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class ProductionAccessProbe {
    private int value;
    private static int earlierEffects;

    public void complexWriter() {
        earlierEffects++;
        value = 991;
    }

    private static final ThreadLocal<Boolean> OWNED = ThreadLocal.withInitial(() -> false);
    private static volatile boolean reserved;

    private void direct(int v) {
        value = v;
    }

    @FunctionalInterface
    interface Route {
        void write(int v) throws Throwable;
    }

    static final class HiddenWriter {
        static void write(ProductionAccessProbe target, int v) {
            target.value = v;
        }
    }

    public static void main(String[] args) throws Throwable {
        if (!RuntimeProof.enforcementAvailable()) throw new AssertionError("service not ready");
        var target = new ProductionAccessProbe();
        Field field = ProductionAccessProbe.class.getDeclaredField("value");
        field.setAccessible(true);
        var uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) uf.get(null);
        long offset = unsafe.objectFieldOffset(field);
        var vh = MethodHandles.lookup().findVarHandle(ProductionAccessProbe.class, "value", int.class);
        var setter = MethodHandles.lookup().findSetter(ProductionAccessProbe.class, "value", int.class);
        var hidden = MethodHandles.lookup().defineHiddenClass(Files.readAllBytes(Path.of(args[0])), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        var hiddenWriter = hidden.findStatic(hidden.lookupClass(), "write", MethodType.methodType(void.class, ProductionAccessProbe.class, int.class));
        var routes = new LinkedHashMap<String, Route>();
        routes.put("direct", v -> target.direct(v));
        routes.put("reflection", v -> {
            try {
                field.setInt(target, v);
            } catch (InternalError failure) {
                if (failure.getCause() instanceof IllegalStateException refusal) throw refusal;
                throw failure;
            }
        });
        routes.put("methodHandle", v -> {
            setter.invokeExact(target, v);
        });
        routes.put("varHandle", v -> vh.set(target, v));
        routes.put("varHandleVolatile", v -> vh.setVolatile(target, v));
        routes.put("varHandleCAS", v -> vh.compareAndSet(target, 0, v));
        routes.put("unsafe", v -> unsafe.putInt(target, offset, v));
        routes.put("unsafeVolatile", v -> unsafe.putIntVolatile(target, offset, v));
        routes.put("unsafeCAS", v -> unsafe.compareAndSwapInt(target, offset, 0, v));
        routes.put("unobservedHiddenNestmate", v -> {
            hiddenWriter.invokeExact(target, v);
        });
        var coordinator = Thread.currentThread();
        RuntimeProof.AccessCheck check = (actual, write) -> actual == target && (OWNED.get() || (!reserved && Thread.currentThread() == coordinator));
        if (!RuntimeProof.protect(target, check)) throw new AssertionError("no root interception");
        if (RuntimeProof.protect(target, (actual, write) -> true)) throw new AssertionError("different callback inherited interception");
        for (int i = 0; i < 20000; i++) {
            target.direct(i);
            unsafe.putInt(target, offset, i);
            vh.setVolatile(target, i);
        }
        target.direct(0);
        var actualDefinition = RuntimeProof.definition(ProductionAccessProbe.class);
        if (actualDefinition == null || (actualDefinition.method("direct", "(I)V").code()[0] & 255) != 42) throw new AssertionError("debug instrumentation lost actual original bytecodes");
        if (!RuntimeProof.beginCoreReservation()) throw new AssertionError("access lifetime not empty");
        reserved = true;
        RuntimeProof.endCoreReservation();
        boolean entryRefused = false;
        try {
            target.complexWriter();
        } catch (IllegalStateException expected) {
            entryRefused = true;
        }
        if (!entryRefused || earlierEffects != 0) throw new AssertionError("public writer entry executed earlier side effects");
        int refusals = 0;
        for (var e : routes.entrySet()) {
            boolean rejected = false;
            try {
                e.getValue().write(19);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            OWNED.set(true);
            int actual = target.value;
            OWNED.set(false);
            if (!rejected || actual != 0) throw new AssertionError(e.getKey() + " rejected=" + rejected + " value=" + actual);
            refusals++;
            System.out.println("PRODUCTION_REFUSED_BEFORE_STORE " + e.getKey());
        }
        OWNED.set(true);
        for (var e : routes.entrySet()) {
            target.direct(0);
            e.getValue().write(23);
            if (target.value != 23) throw new AssertionError(e.getKey());
        }
        OWNED.set(false);
        if (!RuntimeProof.beginCoreReservation()) throw new AssertionError("release before active access finished");
        reserved = false;
        RuntimeProof.endCoreReservation();
        target.direct(31);
        if (target.value != 31) throw new AssertionError();
        byte[] bounded = new byte[8];
        boolean headerRefused = false;
        try {
            unsafe.putInt(bounded, 12, 1000000);
        } catch (IllegalStateException expected) {
            headerRefused = true;
        }
        if (!headerRefused || bounded.length != 8) throw new AssertionError("array header mutation escaped");
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        document.appendChild(document.createElement("evidence"));
        var xml = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(document), new javax.xml.transform.stream.StreamResult(xml));
        if (!xml.toString().contains("evidence")) throw new AssertionError("XML serialization");
        Thread thread = new Thread(() -> {});
        thread.start();
        thread.join();
        boolean ffiRefused = false;
        try {
            Class<?> ffi = Class.forName("jdk.internal.foreign.abi.NativeEntryPoint", true, null);
            var entry = Arrays.stream(ffi.getDeclaredMethods()).filter(m -> m.getName().equals("makeDowncallStub")).findFirst().orElseThrow();
            entry.setAccessible(true);
            try {
                entry.invoke(null, MethodType.methodType(void.class), null, null, null, false, 0, false);
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof IllegalStateException refusal) ffiRefused = true;
                else throw wrapped;
            }
        } catch (ClassFormatError expected) {
            ffiRefused = true;
        }
        if (!ffiRefused) throw new AssertionError("native FFI downcall entry executed");
        System.out.println("PRODUCTION_INTERCEPTION_COMPONENT_PASS routes=" + refusals + " originalCallback=true privateProbeAuthorityOnly=true xml=true publicEntryBeforeEffects=true newThread=true ffiRefused=true");
    }
}
