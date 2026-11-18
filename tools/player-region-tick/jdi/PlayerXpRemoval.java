/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Read-only original playerTouch entry/return observer. No game Mod, mutation or admission exception. */
public class PlayerXpRemoval {
    static VirtualMachine vm;
    static PrintWriter output;
    static long sequence;
    static ObjectReference removedOrb;
    static ThreadReference removalThread;
    static boolean storageReturned;
    static boolean primerObserved;
    record Active(ObjectReference orb, ObjectReference player, int count, int xp) {}
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();
            map.forEach((k, v) -> parts.add(json(k.toString()) + ":" + json(v)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(PlayerXpRemoval::json).toList()) + "]";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    static void log(String event, Map<String, Object> fields) {
        var row = new LinkedHashMap<String, Object>();
        row.put("seq", ++sequence);row.put("event", event);row.putAll(fields);
        output.println(json(row));output.flush();
    }
    static int number(ObjectReference object, String field) {
        return ((PrimitiveValue) object.getValue(object.referenceType().fieldByName(field))).intValue();
    }
    static String uuid(ObjectReference object) {
        var value = (ObjectReference) object.getValue(object.referenceType().fieldByName("uuid"));
        return new UUID(((LongValue) value.getValue(value.referenceType().fieldByName("mostSigBits"))).value(),
                ((LongValue) value.getValue(value.referenceType().fieldByName("leastSigBits"))).value()).toString();
    }
    static List<String> stack(ThreadReference thread) throws Exception {
        return thread.frames().stream().limit(40).map(f -> f.location().declaringType().name() + "." + f.location().method().name() + ":" + f.location().lineNumber()).toList();
    }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]), expectedXp = Integer.parseInt(args[3]);String actor = args[1], target = args.length > 4 ? args[4] : null;
        output = new PrintWriter(Files.newBufferedWriter(Path.of(args[2]), StandardOpenOption.CREATE_NEW));
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options = connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(Integer.toString(port));
        vm = connector.attach(options);
        var types = vm.classesByName("net.minecraft.world.entity.ExperienceOrb");
        if (types.size() != 1) throw new AssertionError("Original XP class not prepared by fixture");
        var method = types.getFirst().methodsByName("playerTouch", "(Lnet/minecraft/world/entity/player/Player;)V").getFirst();
        var entry = vm.eventRequestManager().createBreakpointRequest(method.location());
        entry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);entry.enable();
        var callbacks = vm.classesByName("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback");
        if (callbacks.size() != 1) throw new AssertionError("Original storage callback not prepared");
        var removal = callbacks.getFirst().methodsByName("onRemove").getFirst();
        var removalEntry = vm.eventRequestManager().createBreakpointRequest(removal.location());
        removalEntry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);removalEntry.enable();
        var exits = new HashMap<Long, MethodExitRequest>();
        var storageExits = new HashMap<Long, MethodExitRequest>();
        var active = new HashMap<Long, Active>();boolean complete = false;long deadline = System.nanoTime() + 90_000_000_000L;
        log("armed", Map.of("actor", actor));
        try {
            while ((!complete || !storageReturned) && System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1000);if (events == null) continue;
                try {
                    for (Event event : events) {
                        if (event instanceof BreakpointEvent hit && hit.location().method().equals(removal)) {
                            var callback = hit.thread().frame(0).thisObject();
                            var entity = (ObjectReference) callback.getValue(callback.referenceType().fieldByName("realEntity"));
                            if (entity == null || removedOrb == null || !entity.equals(removedOrb)) continue;
                            if (removalThread != null) throw new AssertionError("Duplicate storage removal");
                            removalThread = hit.thread();
                            var exit = vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(callbacks.getFirst());
                            exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                            storageExits.put(hit.thread().uniqueID(), exit);
                            log("storage-enter", Map.of("thread", hit.thread().name(), "orb", uuid(entity), "stack", stack(hit.thread())));
                        } else if (event instanceof MethodExitEvent hit && hit.method().equals(removal)) {
                            if (removalThread == null || !removalThread.equals(hit.thread())) continue;
                            var callbackEntity = (ObjectReference) hit.thread().frame(0).thisObject().getValue(hit.thread().frame(0).thisObject().referenceType().fieldByName("realEntity"));
                            if (callbackEntity == null || !callbackEntity.equals(removedOrb)) continue;
                            vm.eventRequestManager().deleteEventRequest(storageExits.remove(hit.thread().uniqueID()));
                            if (storageReturned) throw new AssertionError("Duplicate original removal return");
                            var callback = (ObjectReference) removedOrb.getValue(removedOrb.referenceType().fieldByName("levelCallback"));
                            log("storage-return", Map.of("thread", hit.thread().name(), "orb", uuid(removedOrb), "levelCallbackClass", callback.referenceType().name()));
                            storageReturned = true;
                        } else if (event instanceof BreakpointEvent hit) {
                            var frame = hit.thread().frame(0);var player = (ObjectReference) frame.getArgumentValues().getFirst();
                            if (!uuid(player).equals(actor)) continue;
                            var orb = frame.thisObject();
                            boolean primer = args.length > 6 && !primerObserved && uuid(orb).equals(args[5]);
                            if (!primer && target != null && !uuid(orb).equals(target)) continue;
                            var exit = vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(types.getFirst());
                            exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                            if (exits.put(hit.thread().uniqueID(), exit) != null) throw new AssertionError("Nested original XP observation");
                            if (primer) continue;
                            removedOrb = orb;var value = new Active(orb, player, number(orb, "count"), number(player, "totalExperience"));
                            if (active.put(hit.thread().uniqueID(), value) != null) throw new AssertionError("Nested original XP invocation");
                            log("enter", Map.of("thread", hit.thread().name(), "orb", uuid(orb), "actor", actor,
                                    "count", value.count(), "xp", value.xp(), "value", number(orb, "value"), "stack", stack(hit.thread())));
                        } else if (event instanceof MethodExitEvent hit && hit.method().equals(method)) {
                            var exit = exits.remove(hit.thread().uniqueID());if (exit == null) continue;
                            vm.eventRequestManager().deleteEventRequest(exit);
                            if (args.length > 6 && !primerObserved) {
                                var primer = hit.thread().frame(0).thisObject();
                                if (uuid(primer).equals(args[5]) && number(primer, "count") == 0) {
                                    var collector = (ObjectReference) hit.thread().frame(0).getArgumentValues().getFirst();
                                    log("primer-return", Map.of("orb", uuid(primer), "thread", hit.thread().name(), "xp", number(collector, "totalExperience"), "takeXpDelay", number(collector, "takeXpDelay")));
                                    long resumeDeadline = System.nanoTime() + 15_000_000_000L;
                                    while (!Files.exists(Path.of(args[6])) && System.nanoTime() < resumeDeadline) Thread.sleep(10);
                                    if (!Files.exists(Path.of(args[6]))) throw new AssertionError("Normal client follow-up not delivered during primer pause");
                                    primerObserved = true;
                                }
                            }
                            var value = active.remove(hit.thread().uniqueID());if (value == null) continue;
                            int count = number(value.orb(), "count"), xp = number(value.player(), "totalExperience");
                            boolean removed = value.orb().getValue(value.orb().referenceType().fieldByName("removalReason")) != null;
                            log("return", Map.of("thread", hit.thread().name(), "orb", uuid(value.orb()), "actor", actor,
                                    "countBefore", value.count(), "countAfter", count, "xpBefore", value.xp(), "xpAfter", xp, "removed", removed));
                            if (count == 0 && removed && xp == expectedXp) complete = true;
                        }
                    }
                } finally { events.resume(); }
            }
            if (!complete || !storageReturned || !active.isEmpty()) throw new AssertionError("Missing complete original XP conservation path");
            log("observed", Map.of("xp", expectedXp, "remainingOrbCount", 0));
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException ignored) {}
            output.close();
        }
    }
}
