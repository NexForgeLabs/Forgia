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
public class PlayerOwnerPacket {
    static PrintWriter output;static long sequence;
    static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerOwnerPacket::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static int number(ObjectReference object,String name){return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference player){var v=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(v,"mostSigBits")).value(),((LongValue)field(v,"leastSigBits")).value()).toString();}
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(fields);output.println(json(row));output.flush();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    record Active(Method method,ObjectReference player,MethodExitRequest exit,String kind,int slot,int selected){}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);
        var type=vm.classesByName("net.minecraft.server.network.ServerGamePacketListenerImpl").getFirst();
        var selection=type.methodsByName("handleSetCarriedItem").getFirst();var stats=type.methodsByName("handleClientCommand").getFirst();
        var engineType=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine").getFirst();
        var dispatch=engineType.methodsByName("tryRunPlayerPacket").getFirst();
        var dispatchEntry=vm.eventRequestManager().createBreakpointRequest(dispatch.location());dispatchEntry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);dispatchEntry.enable();
        for(var method:List.of(selection,stats)){
            int first=method.location().lineNumber();var afterEnsure=method.allLineLocations().stream().filter(l->l.lineNumber()>first).findFirst().orElseThrow();
            var bp=vm.eventRequestManager().createBreakpointRequest(afterEnsure);bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();
        }
        var active=new HashMap<Long,Active>();int returns=0;long deadline=System.nanoTime()+45_000_000_000L;log("armed",Map.of("actor",args[2]));
        try{
            while(returns<12&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(500);if(events==null)continue;
                try{for(var event:events){
                    if(event instanceof BreakpointEvent hit){
                        if(hit.location().method().equals(dispatch)){
                            var frame=hit.thread().frame(0);var listener=(ObjectReference)frame.getArgumentValues().get(1);var player=(ObjectReference)field(listener,"player");if(!uuid(player).equals(args[2]))continue;
                            if(!hit.thread().name().equals("Server thread"))throw new AssertionError("Owner preparation left original server queue");
                            var gate=(ObjectReference)field(frame.thisObject(),"playerNetworkGate");int before=number(gate,"readers");long until=System.nanoTime()+5_000_000_000L;
                            // Read-only causal scheduling probe: let already-received Netty callbacks physically
                            // return before testing optional admission. No field write, invocation or permission bypass.
                            while(number(gate,"readers")!=0&&System.nanoTime()<until)Thread.sleep(2);
                            if(number(gate,"readers")!=0)throw new AssertionError("Original receive callbacks did not return");
                            log("queue-quiescent",Map.of("thread",hit.thread().name(),"readersBefore",before,"readers",0,"actor",args[2],"stack",stack(hit.thread())));continue;
                        }
                        var frame=hit.thread().frame(0);var player=(ObjectReference)field(frame.thisObject(),"player");if(!uuid(player).equals(args[2]))continue;
                        var method=hit.location().method();String kind=method.equals(selection)?"select":"stats";var packet=(ObjectReference)frame.getArgumentValues().getFirst();int slot=kind.equals("select")?number(packet,"slot"):-1;
                        if(kind.equals("stats")){var action=(ObjectReference)field(packet,"action");if(!((StringReference)field(action,"name")).value().equals("REQUEST_STATS"))throw new AssertionError("Unexpected original client command");}
                        int selected=number((ObjectReference)field(player,"inventory"),"selected");var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(type);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                        if(active.put(hit.thread().uniqueID(),new Active(method,player,exit,kind,slot,selected))!=null)throw new AssertionError("Overlapping original packet on same connection");
                        log("enter",Map.of("kind",kind,"slot",slot,"selected",selected,"actor",args[2],"thread",hit.thread().name(),"stack",stack(hit.thread())));
                    }else if(event instanceof MethodExitEvent hit){
                        var value=active.get(hit.thread().uniqueID());if(value==null||!value.method().equals(hit.method()))continue;
                        active.remove(hit.thread().uniqueID());vm.eventRequestManager().deleteEventRequest(value.exit());returns++;
                        log("return",Map.of("kind",value.kind(),"slot",value.slot(),"selectedBefore",value.selected(),"selectedAfter",number((ObjectReference)field(value.player(),"inventory"),"selected"),"actor",args[2],"thread",hit.thread().name()));
                    }
                }}finally{events.resume();}
            }
            if(returns!=12||!active.isEmpty())throw new AssertionError("Missing complete original packet/fence sequence");log("observed",Map.of("calls",returns));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
