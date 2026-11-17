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
public class PlayerPhaseOrder {
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
        if (value instanceof List<?> list) return "["+String.join(",",list.stream().map(PlayerPhaseOrder::json).toList())+"]";
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
        int port=Integer.parseInt(args[0]);var ids=Set.of(args[1].split(","));
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[2]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(Integer.toString(port));options.get("timeout").setValue("15000");vm=connector.attach(options);
        var requests=new ArrayList<BreakpointRequest>();
        for(String name:List.of("fireServerTickPre","fireServerTickPost","fireLevelTickPre","fireLevelTickPost","fireEntityTickPre","fireEntityTickPost","firePlayerTickPre","firePlayerTickPost")) {
            var request=breakpoint("net.neoforged.neoforge.event.EventHooks",name,-1);request.putProperty("event",name);requests.add(request);request.enable();
        }
        for(var target:List.of(List.of("net.minecraft.world.level.Level","tickBlockEntities","BE_ENTER"),List.of("net.minecraft.server.network.ServerConnectionListener","tick","CONNECTIONS_ENTER"),List.of("net.minecraft.server.players.PlayerList","tick","PLAYERLIST_ENTER"))) {
            var request=breakpoint(target.get(0),target.get(1),-1);request.putProperty("event",target.get(2));requests.add(request);request.enable();
        }
        log("armed",Map.of("players",new ArrayList<>(ids)));
        long deadline=System.nanoTime()+90_000_000_000L;int complete=0;boolean begun=false;
        try {
            while(complete<30 && System.nanoTime()<deadline) {
                EventSet events=vm.eventQueue().remove(1000);if(events==null)continue;
                try {
                    for(Event event:events) if(event instanceof BreakpointEvent hit) {
                        String kind=(String)hit.request().getProperty("event");var thread=hit.thread();var frame=thread.frame(0);String actor="";ObjectReference actorLevel=null;
                        if(kind.equals("fireServerTickPre")) {server=(ObjectReference)frame.getArgumentValues().get(1);begun=true;}
                        if(!begun)continue;
                        if(kind.startsWith("firePlayer")||kind.startsWith("fireEntity")) {
                            var candidate=(ObjectReference)frame.getArgumentValues().getFirst();
                            if(!candidate.referenceType().name().equals("net.minecraft.server.level.ServerPlayer"))continue;
                            actor=playerUuid(candidate);if(!ids.contains(actor))continue;
                            actorLevel=(ObjectReference)candidate.getValue(type("net.minecraft.world.entity.Entity").fieldByName("level"));
                        }
                        String levelId="";
                        if(kind.startsWith("fireLevel")) levelId=Long.toString(((ObjectReference)frame.getArgumentValues().getFirst()).uniqueID());
                        if(kind.equals("BE_ENTER")) levelId=Long.toString(frame.thisObject().uniqueID());
                        if(actorLevel!=null)levelId=Long.toString(actorLevel.uniqueID());
                        var row=new LinkedHashMap<String,Object>();row.put("tick",number(server,"tickCount"));row.put("thread",thread.name());row.put("uuid",actor);row.put("levelId",levelId);
                        if(!actor.isEmpty())row.put("stack",stack(thread));
                        if(kind.equals("fireLevelTickPre"))row.put("emptyTimeBefore",number((ObjectReference)frame.getArgumentValues().getFirst(),"emptyTime"));
                        log(kind,row);
                        if(kind.equals("fireServerTickPost"))complete++;
                    }
                } finally {events.resume();}
            }
            if(complete!=30)throw new AssertionError("Incomplete real original phase-order frames: "+complete);
            log("observed",Map.of("completeTicks",complete));
        } finally {
            try{for(var request:requests)request.disable();vm.dispose();}catch(VMDisconnectedException ignored){}
            output.close();
        }
    }
}
