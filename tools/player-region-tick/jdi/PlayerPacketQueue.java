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

/** Read-only original PacketUtils queue pressure and exact terminal after its accepted prefix. */
public class PlayerPacketQueue {
    static PrintWriter output;static long sequence;
    static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerPacketQueue::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static int number(ObjectReference object,String name){return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference player){var v=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(v,"mostSigBits")).value(),((LongValue)field(v,"leastSigBits")).value()).toString();}
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(fields);output.println(json(row));output.flush();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);var engine=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine").getFirst();var method=engine.methodsByName("tryRunPlayerPacket").getFirst();var entry=vm.eventRequestManager().createBreakpointRequest(method.location());entry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);entry.enable();log("armed",Map.of("actor",args[2]));var tasksType=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionPlayerPacketTasks").getFirst();var terminal=vm.eventRequestManager().createBreakpointRequest(tasksType.methodsByName("disconnectOverflow").getFirst().location());terminal.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);terminal.enable();long deadline=System.nanoTime()+45_000_000_000L;boolean observed=false;boolean bounded=false;ObjectReference capturedTasks=null;
        try{
            while(!observed&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(300);if(events==null)continue;
                try{for(var event:events)if(event instanceof BreakpointEvent hit){var frame=hit.thread().frame(0);
                    if(hit.request()==terminal){if(!bounded||capturedTasks==null)throw new AssertionError("Terminal overtook pressure observation");if(number(capturedTasks,"count")!=0)throw new AssertionError("Terminal overtook accepted prefix");log("drained",Map.of("thread",hit.thread().name(),"pending",0,"terminals",number((ObjectReference)field(capturedTasks,"terminals"),"size"),"stack",stack(hit.thread())));observed=true;continue;}
                    var packet=(ObjectReference)frame.getArgumentValues().getFirst();var listener=(ObjectReference)frame.getArgumentValues().get(1);var player=(ObjectReference)field(listener,"player");if(!uuid(player).equals(args[2])||!packet.referenceType().name().equals("net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket"))continue;
                    if(!hit.thread().name().equals("Server thread"))throw new AssertionError("Probe left original server queue");var router=(ObjectReference)field(frame.thisObject(),"playerPackets");var tasks=(ObjectReference)field(router,"tasks");var gate=(ObjectReference)field(frame.thisObject(),"playerNetworkGate");if(((BooleanValue)field(gate,"frozen")).value())throw new AssertionError("Queue probe must leave original receive path open");
                    log("paused",Map.of("actor",args[2],"thread",hit.thread().name(),"pending",number(tasks,"count"),"stack",stack(hit.thread())));long until=System.nanoTime()+15_000_000_000L;var marker=Path.of(args[3]);
                    while(System.nanoTime()<until&&(!Files.exists(marker)||number(tasks,"count")!=4096||number((ObjectReference)field(tasks,"terminals"),"size")!=1))Thread.sleep(5);
                    if(!Files.exists(marker)||number(tasks,"count")!=4096||number((ObjectReference)field(tasks,"terminals"),"size")!=1)throw new AssertionError("Normal packet flood did not reach bounded original queue terminal");
                    log("bounded",Map.of("actor",args[2],"thread",hit.thread().name(),"pending",4096,"terminals",1,"frozen",false,"stack",stack(hit.thread())));capturedTasks=tasks;bounded=true;vm.eventRequestManager().deleteEventRequest(entry);
                }}finally{events.resume();}
            }
            if(!observed)throw new AssertionError("Missing original packet queue pressure observation");log("observed",Map.of("bounded",true));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
