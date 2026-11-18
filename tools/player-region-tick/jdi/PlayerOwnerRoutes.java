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

/** Read-only original handler/return observation after PacketUtils, including intervening serial stats fences. */
public class PlayerOwnerRoutes {
    static PrintWriter output;static long sequence;
    static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerOwnerRoutes::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static int number(ObjectReference object,String name){return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference player){var v=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(v,"mostSigBits")).value(),((LongValue)field(v,"leastSigBits")).value()).toString();}
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.put("nano",System.nanoTime());row.putAll(fields);output.println(json(row));output.flush();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    static Map<String,Object> state(ObjectReference player){
        var pos=(ObjectReference)field(player,"position");var inv=(ObjectReference)field(player,"inventory");var backing=(ObjectReference)field((ObjectReference)field(inv,"items"),"list");var array=(ArrayReference)field(backing,"a");int total=0;
        for(Value v:array.getValues())total+=number((ObjectReference)v,"count");var menu=(ObjectReference)field(player,"containerMenu");var carried=(ObjectReference)field(menu,"carried");
        return Map.of("x",((DoubleValue)field(pos,"x")).value(),"y",((DoubleValue)field(pos,"y")).value(),"z",((DoubleValue)field(pos,"z")).value(),"inventory",total,"carried",number(carried,"count"),"xp",number(player,"totalExperience"),"selected",number(inv,"selected"));
    }
    record Active(Method method,ObjectReference player,MethodExitRequest exit,String kind,Map<String,Object> before){}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);
        var type=vm.classesByName("net.minecraft.server.network.ServerGamePacketListenerImpl").getFirst();var methods=new HashMap<Method,String>();
        for(var pair:Map.of("handleMovePlayer","move","handleAcceptTeleportPacket","confirm","handleContainerClick","click","handleClientCommand","stats").entrySet()){
            var method=type.methodsByName(pair.getKey()).getFirst();methods.put(method,pair.getValue());int first=method.location().lineNumber();var after=method.allLineLocations().stream().filter(l->l.lineNumber()>first).findFirst().orElseThrow();var bp=vm.eventRequestManager().createBreakpointRequest(after);bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();
        }
        var engine=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine").getFirst();var dispatch=engine.methodsByName("tryRunPlayerPacket").getFirst();var prep=vm.eventRequestManager().createBreakpointRequest(dispatch.location());prep.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);prep.enable();
        var mapType=vm.classesByName("net.minecraft.server.level.ChunkMap").getFirst();var tracking=mapType.methodsByName("move").getFirst();var track=vm.eventRequestManager().createBreakpointRequest(tracking.location());track.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);track.enable();
        var prepExits=new HashMap<Long,MethodExitRequest>();var prepPackets=new HashMap<Long,String>();var active=new HashMap<Long,Active>();var done=Path.of(args[3]);long deadline=System.nanoTime()+90_000_000_000L;log("armed",Map.of("actor",args[2]));
        try{
            while((!Files.exists(done)||!active.isEmpty())&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(300);if(events==null)continue;
                try{for(var event:events){
                    if(event instanceof BreakpointEvent hit){
                        var frame=hit.thread().frame(0);var method=hit.location().method();
                        if(method.equals(dispatch)){
                            var listener=(ObjectReference)frame.getArgumentValues().get(1);var player=(ObjectReference)field(listener,"player");if(!uuid(player).equals(args[2]))continue;
                            var gate=(ObjectReference)field(frame.thisObject(),"playerNetworkGate");long until=System.nanoTime()+5_000_000_000L;while(number(gate,"readers")!=0&&System.nanoTime()<until)Thread.sleep(2);if(number(gate,"readers")!=0)throw new AssertionError("Receive callbacks did not return");
                            var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(engine);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();if(prepExits.put(hit.thread().uniqueID(),exit)!=null)throw new AssertionError("Nested owner prep");prepPackets.put(hit.thread().uniqueID(),((ObjectReference)frame.getArgumentValues().getFirst()).referenceType().name());
                            log("queue-quiescent",Map.of("actor",args[2],"thread",hit.thread().name(),"readers",0));continue;
                        }
                        if(method.equals(tracking)){
                            var player=(ObjectReference)frame.getArgumentValues().getFirst();if(uuid(player).equals(args[2]))log("tracking",Map.of("actor",args[2],"thread",hit.thread().name(),"state",state(player),"stack",stack(hit.thread())));continue;
                        }
                        var player=(ObjectReference)field(frame.thisObject(),"player");if(!uuid(player).equals(args[2]))continue;
                        var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(type);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();var before=state(player);
                        if(active.put(hit.thread().uniqueID(),new Active(method,player,exit,methods.get(method),before))!=null)throw new AssertionError("Overlapping original packet calls");
                        log("enter",Map.of("actor",args[2],"kind",methods.get(method),"thread",hit.thread().name(),"state",before,"stack",stack(hit.thread())));
                    }else if(event instanceof MethodExitEvent hit){
                        if(hit.method().declaringType().equals(engine)){
                            if(hit.method().equals(dispatch)){var req=prepExits.remove(hit.thread().uniqueID());if(req!=null)vm.eventRequestManager().deleteEventRequest(req);prepPackets.remove(hit.thread().uniqueID());}
                            else if(hit.method().name().equals("capturePlayerAdmissionPlan")){var result=(ObjectReference)hit.returnValue();var refusal=(ObjectReference)field(result,"refusal");log("capture",Map.of("packet",prepPackets.get(hit.thread().uniqueID()),"refusal",((StringReference)field(refusal,"name")).value(),"detail",((StringReference)field(result,"detail")).value(),"thread",hit.thread().name()));}
                            continue;
                        }
                        var value=active.get(hit.thread().uniqueID());if(value==null||!value.method().equals(hit.method()))continue;active.remove(hit.thread().uniqueID());vm.eventRequestManager().deleteEventRequest(value.exit());
                        log("return",Map.of("actor",args[2],"kind",value.kind(),"thread",hit.thread().name(),"before",value.before(),"state",state(value.player())));
                    }
                }}finally{events.resume();}
            }
            if(!Files.exists(done)||!active.isEmpty())throw new AssertionError("Owner route observation incomplete");log("observed",Map.of("complete",true));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
