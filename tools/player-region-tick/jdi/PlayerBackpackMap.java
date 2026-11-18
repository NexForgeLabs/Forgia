/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only original map carrier/update observer; no game method invocation, state write or transformation. */
public class PlayerBackpackMap {
    static PrintWriter output;static long sequence;
    static String json(Object value){if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerBackpackMap::json).toList())+"]";return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(fields);output.println(json(row));output.flush();}
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static int number(ObjectReference object,String name){return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference player){var value=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(value,"mostSigBits")).value(),((LongValue)field(value,"leastSigBits")).value()).toString();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    static String colors(ObjectReference data)throws Exception{var array=(ArrayReference)field(data,"colors");var values=array.getValues();var bytes=new byte[values.size()];for(int i=0;i<bytes.length;i++)bytes[i]=((ByteValue)values.get(i)).value();return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static Map<String,Object> decoration(ObjectReference data){
        var map=(ObjectReference)field(data,"decorations");var head=(ObjectReference)field(map,"head");
        if(head==null)return Map.of("size",0);
        var value=(ObjectReference)field(head,"value");
        return Map.of("size",number(map,"size"),"name",((StringReference)field(head,"key")).value(),"x",number(value,"x"),"y",number(value,"y"),"rot",number(value,"rot"));
    }
    record Active(ObjectReference data,ObjectReference player,Method method,MethodExitRequest request,String colors){}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));String actor=args[2];Path done=Path.of(args[3]);
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);
        if(!vm.canGetMethodReturnValues())throw new AssertionError("Original map return observation unavailable");
        var type=vm.classesByName("net.minecraft.world.level.saveddata.maps.MapItemSavedData").getFirst();var tick=type.methodsByName("tickCarriedBy").getFirst();var update=type.methodsByName("getUpdatePacket").getFirst();
        var terrain=vm.classesByName("net.minecraft.world.item.MapItem").getFirst().methodsByName("update").getFirst();
        for(var method:List.of(tick,update,terrain)){var bp=vm.eventRequestManager().createBreakpointRequest(method.location());bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();}
        var active=new HashMap<Long,Active>();long deadline=System.nanoTime()+150_000_000_000L;log("armed",Map.of("actor",actor));
        try{
            while((!Files.exists(done)||!active.isEmpty())&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(500);if(events==null)continue;
                try{for(var event:events){
                    if(event instanceof BreakpointEvent hit){
                        var frame=hit.thread().frame(0);
                        if(hit.location().method().equals(terrain)) {
                            var actorValue=(ObjectReference)frame.getArgumentValues().get(1);
                            if(uuid(actorValue).equals(actor))log("terrain-update",Map.of("actor",actor,"thread",hit.thread().name(),"stack",stack(hit.thread())));
                            continue;
                        }
                        var player=(ObjectReference)frame.getArgumentValues().get(hit.location().method().equals(tick)?0:1);if(!uuid(player).equals(actor))continue;
                        var data=frame.thisObject();var method=hit.location().method();var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(type);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                        var value=new Active(data,player,method,exit,colors(data));if(active.put(hit.thread().uniqueID(),value)!=null)throw new AssertionError("Nested original map method");
                        log("enter",Map.of("actor",actor,"map",data.uniqueID(),"method",method.name(),"thread",hit.thread().name(),"colorsSha256",value.colors(),"decoration",decoration(data),"stack",stack(hit.thread())));
                    }else if(event instanceof MethodExitEvent hit){
                        var value=active.get(hit.thread().uniqueID());if(value==null||!value.method().equals(hit.method()))continue;active.remove(hit.thread().uniqueID());vm.eventRequestManager().deleteEventRequest(value.request());
                        var fields=new LinkedHashMap<String,Object>();fields.put("actor",actor);fields.put("map",value.data().uniqueID());fields.put("method",hit.method().name());fields.put("thread",hit.thread().name());fields.put("colorsSha256",colors(value.data()));fields.put("decoration",decoration(value.data()));fields.put("dirty",((BooleanValue)field(value.data(),"dirty")).value());fields.put("carriers",number((ObjectReference)field(value.data(),"carriedByPlayers"),"size"));
                        if(hit.method().equals(update)){
                            var packet=(ObjectReference)hit.returnValue();fields.put("packet",packet==null?null:packet.referenceType().name());
                            if(packet!=null){fields.put("mapId",number((ObjectReference)field(packet,"mapId"),"id"));}
                        }
                        log("return",fields);
                    }
                }}finally{events.resume();}
            }
            if(!Files.exists(done)||!active.isEmpty())throw new AssertionError("Original map observation incomplete");log("observed",Map.of("complete",true));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
