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

/** External debugger only: original installer, no game Mod, no transformed method or admission bypass. */
public class PlayerTickFault {
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
        if (value instanceof List<?> list) return "["+String.join(",",list.stream().map(PlayerTickFault::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static void log(String event, Map<String,Object> data) {
        var row=new LinkedHashMap<String,Object>();row.put("event",event);row.putAll(data);output.println(json(row));output.flush();
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
    static String diagnostics(ThreadReference thread) throws Exception {
        var method=engine.referenceType().methodsByName("playerTickDiagnostics").getFirst();
        var state=engine.invokeMethod(thread,method,List.of(server),ObjectReference.INVOKE_SINGLE_THREADED);
        var gsonClass=type("com.google.gson.Gson");
        var gson=gsonClass.newInstance(thread,gsonClass.methodsByName("<init>","()V").getFirst(),List.of(),ObjectReference.INVOKE_SINGLE_THREADED);
        return ((StringReference)gson.invokeMethod(thread,gsonClass.methodsByName("toJson","(Ljava/lang/Object;)Ljava/lang/String;").getFirst(),List.of(state),ObjectReference.INVOKE_SINGLE_THREADED)).value();
    }
    static List<String> stack(ThreadReference thread) throws Exception {
        return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();
    }
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]);phase=args[1];int bodyLine=Integer.parseInt(args[2]);int joinedLine=Integer.parseInt(args[3]);int closeLine=Integer.parseInt(args[4]);uuid=args[5];
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[6]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(Integer.toString(port));options.get("timeout").setValue("15000");
        vm=connector.attach(options);
        String target=phase.equals("ENTITY")?"net.minecraft.server.level.ServerPlayer":"net.minecraft.network.Connection";
        var entry=breakpoint(target,"tick",-1);entry.putProperty("kind","entry");entry.enable();
        post=breakpoint(target,"tick",bodyLine);post.putProperty("kind","body");
        var join=breakpoint("net.neoforged.neoforge.server.threading.region.RegionPlayerTickCoordinator","terminal",joinedLine);join.putProperty("kind","join");join.enable();
        var close=breakpoint("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine","shutdown",closeLine);close.putProperty("kind","close");close.enable();
        log("armed",Map.of("phase",phase,"bodyLine",bodyLine,"joinedLine",joinedLine,"closeLine",closeLine));
        long deadline=System.nanoTime()+180_000_000_000L;
        try {
            while (!closed && System.nanoTime()<deadline) {
                EventSet events=vm.eventQueue().remove(1000);if(events==null)continue;
                try {
                    for (Event event:events) if(event instanceof BreakpointEvent hit) {
                        String kind=(String)hit.request().getProperty("kind");var thread=hit.thread();var frame=thread.frame(0);
                        if(kind.equals("entry")) {
                            ObjectReference candidate=frame.thisObject();
                            if(!phase.equals("ENTITY")) {
                                var listener=object(candidate,"packetListener");
                                if(listener==null || !listener.referenceType().name().equals("net.minecraft.server.network.ServerGamePacketListenerImpl"))continue;
                                candidate=object(listener,"player");
                            }
                            if(!playerUuid(candidate).equals(uuid))continue;
                            var frames=stack(thread);
                            if(!entered && frames.stream().anyMatch(s->s.startsWith("net.neoforged.neoforge.server.threading.region.RegionPlayerTickDispatch.runOriginal:"))) {
                                player=candidate;actorId=player.uniqueID();server=object(player,"server");selected=thread;originalTick=number(server,"tickCount");
                                effectOwner=phase.equals("ENTITY")?player:object(frame.thisObject(),"packetListener");
                                before=(int)number(effectOwner,phase.equals("ENTITY")?"spawnInvulnerableTime":"tickCount");entered=true;
                                post.addThreadFilter(thread);post.enable();
                                var groupFrame=thread.frames().stream().filter(f->f.location().declaringType().name().equals("net.neoforged.neoforge.server.threading.region.RegionPlayerTickDispatch") && f.location().method().name().equals("runGroup")).findFirst().orElseThrow();
                                var calls=(ObjectReference)groupFrame.getValue(groupFrame.visibleVariableByName("calls"));
                                int size=((IntegerValue)calls.invokeMethod(thread,calls.referenceType().methodsByName("size","()I").getFirst(),List.of(),ObjectReference.INVOKE_SINGLE_THREADED)).value();
                                var groupPlayers=new ArrayList<String>();
                                for(int i=0;i<size;i++) {
                                    var call=(ObjectReference)calls.invokeMethod(thread,calls.referenceType().methodsByName("get","(I)Ljava/lang/Object;").getFirst(),List.of(vm.mirrorOf(i)),ObjectReference.INVOKE_SINGLE_THREADED);
                                    groupPlayers.add(playerUuid(object(call,"player")));
                                }
                                log("original_entry",Map.of("phase",phase,"uuid",uuid,"thread",thread.name(),"tick",originalTick,"playerObjectId",actorId,"counterBefore",before,"stack",frames,"groupPlayers",groupPlayers));
                            } else if(injected && number(object(candidate,"server"),"tickCount")==originalTick)throw new AssertionError("Original failed invocation replayed");
                        } else if(kind.equals("body") && entered && !injected && thread.equals(selected)) {
                            int after=(int)number(effectOwner,phase.equals("ENTITY")?"spawnInvulnerableTime":"tickCount");
                            if(after!=before+(phase.equals("ENTITY")?-1:1))throw new AssertionError("Original body counter did not advance exactly once: "+before+" -> "+after);
                            post.disable();
                            var exceptionType=type("java.lang.RuntimeException");
                            var exception=exceptionType.newInstance(thread,exceptionType.methodsByName("<init>","(Ljava/lang/String;)V").getFirst(),List.of(vm.mirrorOf("player_worker_fault_after_original_body_"+phase)),ObjectReference.INVOKE_SINGLE_THREADED);
                            injected=true;
                            log("original_body_effect",Map.of("phase",phase,"uuid",uuid,"thread",thread.name(),"tick",originalTick,"counterBefore",before,"counterAfter",after,"stack",stack(thread)));
                            thread.stop(exception);
                            log("injected",Map.of("exceptionObjectId",exception.uniqueID(),"phase",phase,"uuid",uuid,"tick",originalTick));
                        } else if(kind.equals("join") && injected && !joined) {
                            var variable=frame.visibleVariableByName("plan");
                            if(variable==null)throw new IllegalStateException("Original terminal plan debug local absent");
                            var plan=(ObjectReference)frame.getValue(variable);
                            var planUuid=object(plan,"uuid");
                            String planId=new UUID(((LongValue)planUuid.getValue(planUuid.referenceType().fieldByName("mostSigBits"))).value(),((LongValue)planUuid.getValue(planUuid.referenceType().fieldByName("leastSigBits"))).value()).toString();
                            Value failure=plan.getValue(plan.referenceType().fieldByName("failureClass"));
                            if(!planId.equals(uuid) || failure==null || number(plan,"tick")!=originalTick)continue;
                            engine=object(frame.thisObject(),"engine");
                            if(plan.getValue(plan.referenceType().fieldByName("binding"))!=null)throw new AssertionError("Terminal failed plan retains live binding");
                            joined=true;log("joined",Map.of("thread",thread.name(),"uuid",uuid,"phase",phase,"tick",originalTick,"bindingReleased",true,"diagnostics",diagnostics(thread),"stack",stack(thread)));
                        } else if(kind.equals("close") && joined && frame.thisObject().uniqueID()==engine.uniqueID()) {
                            closed=true;log("shutdown_after_gate_close",Map.of("diagnostics",diagnostics(thread),"thread",thread.name()));
                        }
                    }
                } finally {events.resume();}
            }
            if(!entered||!injected||!joined||!closed)throw new AssertionError("Incomplete original fault proof "+entered+"/"+injected+"/"+joined+"/"+closed);
        } finally {
            try{vm.dispose();}catch(VMDisconnectedException ignored){}
            output.close();
        }
    }
}
