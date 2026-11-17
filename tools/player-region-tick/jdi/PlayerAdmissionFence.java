/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** External breakpoint observer: real TCP close during original body; no game Mod, class redefinition or admission bypass. */
public class PlayerAdmissionFence {
    static PrintWriter output;
    static VirtualMachine vm;
    static String phase, uuid;
    static long actorId, originalTick;
    static int before;
    static ThreadReference selected;
    static ObjectReference player, effectOwner, engine, server;
    static boolean entered, injected, joined, closed;
    static BreakpointRequest post;
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            var parts=new ArrayList<String>();
            map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));
            return "{"+String.join(",",parts)+"}";
        }
        if (value instanceof List<?> list) return "["+String.join(",",list.stream().map(PlayerAdmissionFence::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static long sequence;
    static void log(String event, Map<String,Object> data) {
        var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(data);output.println(json(row));output.flush();
    }
    static ObjectReference object(ObjectReference owner,String field) { return (ObjectReference)owner.getValue(owner.referenceType().fieldByName(field)); }
    static long number(ObjectReference owner,String field) { return ((PrimitiveValue)owner.getValue(owner.referenceType().fieldByName(field))).longValue(); }
    static String playerUuid(ObjectReference value) {
        var id=object(value,"uuid");
        return new UUID(((LongValue)id.getValue(id.referenceType().fieldByName("mostSigBits"))).value(),((LongValue)id.getValue(id.referenceType().fieldByName("leastSigBits"))).value()).toString();
    }
    static ClassType type(String name) {
        var found=vm.classesByName(name);
        if (found.size()!=1) throw new IllegalStateException("Expected one prepared class "+name+", found "+found.size());
        return (ClassType)found.getFirst();
    }
    static BreakpointRequest breakpoint(String typeName,String methodName,int line) throws Exception {
        var klass=type(typeName);
        var methods=klass.methodsByName(methodName);
        if (methods.size()!=1) throw new IllegalStateException("Expected exact original method "+typeName+"."+methodName);
        var method=methods.getFirst();
        Location location=line<0?method.location():method.allLineLocations().stream().filter(l->l.lineNumber()==line).findFirst().orElseThrow(()->new IllegalStateException("No original line "+line+" in "+method));
        var request=vm.eventRequestManager().createBreakpointRequest(location);request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        return request;
    }
    static List<String> stack(ThreadReference thread) throws Exception {
        return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();
    }
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]),line=Integer.parseInt(args[1]);
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[2]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(Integer.toString(port));vm=connector.attach(options);
        var point=breakpoint("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine","dispatchPlayerPrefix",line);point.enable();
        log("armed",Map.of("line",line));boolean seen=false;long deadline=System.nanoTime()+60_000_000_000L;
        try {
            while(!seen && System.nanoTime()<deadline) {
                EventSet events=vm.eventQueue().remove(1000);if(events==null)continue;
                try {
                    for(Event event:events) if(event instanceof BreakpointEvent hit) {
                        var engine=hit.thread().frame(0).thisObject();var gate=object(engine,"playerNetworkGate");
                        boolean frozen=((BooleanValue)gate.getValue(gate.referenceType().fieldByName("frozen"))).value();
                        if(!frozen)throw new AssertionError("Actual original gate not frozen");
                        log("unknown_preflight_frozen",Map.of("thread",hit.thread().name(),"frozen",frozen,"stack",stack(hit.thread())));
                        long until=System.nanoTime()+20_000_000_000L;while(!Files.exists(Path.of(args[3])) && System.nanoTime()<until)Thread.sleep(10);
                        if(!Files.exists(Path.of(args[3])))throw new AssertionError("No ordinary client payload signal");
                        long queued=number(gate,"queued");if(queued<1)throw new AssertionError("Normal NETWORK payload never queued");
                        log("normal_network_payload_queued",Map.of("queued",queued));point.disable();seen=true;
                    }
                }finally{events.resume();}
            }
            if(!seen)throw new AssertionError("Original unknown-extension preflight freeze not observed");
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
