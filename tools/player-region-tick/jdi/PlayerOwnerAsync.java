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
public class PlayerOwnerAsync {
    static PrintWriter output;static long sequence;
    static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerOwnerAsync::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static int number(ObjectReference object,String name){return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference player){var v=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(v,"mostSigBits")).value(),((LongValue)field(v,"leastSigBits")).value()).toString();}
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.put("nano",System.nanoTime());row.putAll(fields);output.println(json(row));output.flush();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    static Map<String,Object> state(ObjectReference player){var p=(ObjectReference)field(player,"position");return Map.of("x",((DoubleValue)field(p,"x")).value(),"y",((DoubleValue)field(p,"y")).value(),"z",((DoubleValue)field(p,"z")).value());}
    record Active(ObjectReference player,MethodExitRequest exit,String text){}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);
        var listenerType=vm.classesByName("net.minecraft.server.network.ServerGamePacketListenerImpl").getFirst();var method=listenerType.methodsByName("broadcastChatMessage").getFirst();var entry=vm.eventRequestManager().createBreakpointRequest(method.location());entry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);entry.enable();
        var engineType=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine").getFirst();var dispatch=engineType.methodsByName("tryRunPlayerChatCompletion").getFirst();var prep=vm.eventRequestManager().createBreakpointRequest(dispatch.location());prep.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);prep.enable();
        var active=new HashMap<Long,Active>();var done=Path.of(args[3]);long deadline=System.nanoTime()+90_000_000_000L;log("armed",Map.of("actor",args[2]));
        try{
            while((!Files.exists(done)||!active.isEmpty())&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(300);if(events==null)continue;
                try{for(var event:events){
                    if(event instanceof BreakpointEvent hit){
                        var frame=hit.thread().frame(0);
                        if(hit.location().method().equals(dispatch)){
                            var listener=(ObjectReference)frame.getArgumentValues().getFirst();var player=(ObjectReference)field(listener,"player");if(!uuid(player).equals(args[2]))continue;
                            var gate=(ObjectReference)field(frame.thisObject(),"playerNetworkGate");long until=System.nanoTime()+5_000_000_000L;while(number(gate,"readers")!=0&&System.nanoTime()<until)Thread.sleep(2);if(number(gate,"readers")!=0)throw new AssertionError("Original receive callbacks did not return");log("queue-quiescent",Map.of("actor",args[2],"readers",0,"thread",hit.thread().name()));continue;
                        }
                        var player=(ObjectReference)field(frame.thisObject(),"player");if(!uuid(player).equals(args[2]))continue;var msg=(ObjectReference)frame.getArgumentValues().getFirst();String text=((StringReference)field((ObjectReference)field(msg,"signedBody"),"content")).value();if(!text.startsWith("p4_async_"))continue;
                        var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(listenerType);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();if(active.put(hit.thread().uniqueID(),new Active(player,exit,text))!=null)throw new AssertionError("Nested original chat broadcast");
                        log("enter",Map.of("actor",args[2],"text",text,"thread",hit.thread().name(),"state",state(player),"stack",stack(hit.thread())));
                    }else if(event instanceof MethodExitEvent hit&&hit.method().equals(method)){
                        var value=active.remove(hit.thread().uniqueID());if(value==null)continue;vm.eventRequestManager().deleteEventRequest(value.exit());log("return",Map.of("actor",args[2],"text",value.text(),"thread",hit.thread().name(),"state",state(value.player())));
                    }
                }}finally{events.resume();}
            }
            if(!Files.exists(done)||!active.isEmpty())throw new AssertionError("Original async completion incomplete");log("observed",Map.of("complete",true));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
