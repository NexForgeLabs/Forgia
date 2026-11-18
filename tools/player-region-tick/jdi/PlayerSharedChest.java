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

/** Original menu/event observers. Reads fields and method returns only; gameplay comes from normal clients. */
public class PlayerSharedChest {
    static PrintWriter output;static long sequence;
    static String json(Object value) {
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerSharedChest::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(fields);output.println(json(row));output.flush();}
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static int number(ObjectReference object,String name){return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference player){var value=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(value,"mostSigBits")).value(),((LongValue)field(value,"leastSigBits")).value()).toString();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(35).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    static List<Value> list(ObjectReference value){
        if(value.referenceType().name().equals("net.minecraft.core.NonNullList"))return list((ObjectReference)field(value,"list"));
        if(value.referenceType().name().equals("java.util.ArrayList")){var array=(ArrayReference)field(value,"elementData");return array.getValues(0,number(value,"size"));}
        if(value.referenceType().name().equals("java.util.Arrays$ArrayList"))return ((ArrayReference)field(value,"a")).getValues();
        throw new AssertionError("Unknown original backing list "+value.referenceType().name());
    }
    static int chestCount(ObjectReference chest){int sum=0;for(var value:list((ObjectReference)field(chest,"items")))sum+=number((ObjectReference)value,"count");return sum;}
    static ObjectReference owner(ObjectReference menu){var slot=(ObjectReference)list((ObjectReference)field(menu,"slots")).get(27);return (ObjectReference)field((ObjectReference)field(slot,"container"),"player");}
    static String phase(List<String> stack){if(stack.stream().anyMatch(s->s.contains("ServerPlayer.doTick:")))return "CONNECTION";if(stack.stream().anyMatch(s->s.contains("ServerPlayer.tick:")))return "ENTITY";return "PACKET";}
    record Active(ObjectReference menu,ObjectReference chest,ObjectReference player,Method method,MethodExitRequest request,int count,List<String> stack,String phase){}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);
        if(!vm.canGetMethodReturnValues())throw new AssertionError("Original return observation unavailable");
        var actors=Set.of(args[2],args[3]);var done=Path.of(args[4]);
        var chestType=vm.classesByName("net.minecraft.world.inventory.ChestMenu").getFirst();var baseType=vm.classesByName("net.minecraft.world.inventory.AbstractContainerMenu").getFirst();
        var valid=chestType.methodsByName("stillValid").getFirst();var broadcast=baseType.methodsByName("broadcastChanges").getFirst();
        for(var method:List.of(valid,broadcast)){var bp=vm.eventRequestManager().createBreakpointRequest(method.location());bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();}
        // Observe construction of original open/close events; constructors have no listeners or game writes.
        for(String type:List.of("net.neoforged.neoforge.event.entity.player.PlayerContainerEvent$Open","net.neoforged.neoforge.event.entity.player.PlayerContainerEvent$Close")){
            var classes=vm.classesByName(type);
            if(classes.isEmpty()){
                var prepare=vm.eventRequestManager().createClassPrepareRequest();prepare.addClassFilter(type);prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);prepare.enable();
            }else for(var constructor:classes.getFirst().methodsByName("<init>","(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/inventory/AbstractContainerMenu;)V")){var bp=vm.eventRequestManager().createBreakpointRequest(constructor.location());bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();}
        }
        var engine=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine").getFirst();var preparation=vm.eventRequestManager().createBreakpointRequest(engine.methodsByName("tryRunPlayerPacket").getFirst().location());preparation.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);preparation.enable();
        var active=new HashMap<Long,Active>();long deadline=System.nanoTime()+150_000_000_000L;log("armed",Map.of("actors",List.copyOf(actors),"protocol",2));
        try{
            while((!Files.exists(done)||!active.isEmpty())&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(500);if(events==null)continue;
                try{for(var event:events){
                    if(event instanceof ClassPrepareEvent prepared){for(var constructor:prepared.referenceType().methodsByName("<init>","(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/inventory/AbstractContainerMenu;)V")){var bp=vm.eventRequestManager().createBreakpointRequest(constructor.location());bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();}vm.eventRequestManager().deleteEventRequest(prepared.request());}
                    else if(event instanceof BreakpointEvent hit){
                        var method=hit.location().method();var frame=hit.thread().frame(0);
                        if(method.name().equals("tryRunPlayerPacket")){
                            var packet=(ObjectReference)frame.getArgumentValues().getFirst();if(!packet.referenceType().name().equals("net.minecraft.network.protocol.game.ServerboundContainerClickPacket"))continue;
                            var listener=(ObjectReference)frame.getArgumentValues().get(1);var player=(ObjectReference)field(listener,"player");if(!actors.contains(uuid(player)))continue;
                            var gate=(ObjectReference)field(frame.thisObject(),"playerNetworkGate");long until=System.nanoTime()+2_000_000_000L;while(number(gate,"readers")!=0&&System.nanoTime()<until)Thread.sleep(1);
                            if(number(gate,"readers")!=0)throw new AssertionError("Original receive reader did not physically return");log("queue-quiescent",Map.of("actor",uuid(player),"thread",hit.thread().name(),"readers",0));continue;
                        }
                        if(method.name().equals("<init>")){
                            var player=(ObjectReference)frame.getArgumentValues().getFirst();var menu=(ObjectReference)frame.getArgumentValues().get(1);
                            if(actors.contains(uuid(player))&&menu.referenceType().name().equals(chestType.name()))log(method.declaringType().name().endsWith("$Open")?"open-event":"close-event",Map.of("actor",uuid(player),"menu",menu.uniqueID(),"chest",((ObjectReference)field(menu,"container")).uniqueID(),"thread",hit.thread().name(),"stack",stack(hit.thread())));
                            continue;
                        }
                        var menu=frame.thisObject();if(!menu.referenceType().name().equals(chestType.name()))continue;
                        var player=owner(menu);if(!actors.contains(uuid(player)))continue;
                        var chest=(ObjectReference)field(menu,"container");var frames=stack(hit.thread());var stage=phase(frames);
                        var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(method.declaringType());exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                        var value=new Active(menu,chest,player,method,exit,chestCount(chest),frames,stage);
                        if(active.put(hit.thread().uniqueID(),value)!=null)throw new AssertionError("Nested original menu method");
                        log("enter",Map.of("actor",uuid(player),"menu",menu.uniqueID(),"chest",chest.uniqueID(),"method",method.name(),"phase",stage,"thread",hit.thread().name(),"count",value.count(),"stack",frames,"keepAlivePending",((BooleanValue)field((ObjectReference)field(player,"connection"),"keepAlivePending")).value(),"awaitingTeleport",field((ObjectReference)field(player,"connection"),"awaitingPositionFromClient")!=null));
                    }else if(event instanceof MethodExitEvent hit){
                        var value=active.get(hit.thread().uniqueID());if(value==null||!value.method().equals(hit.method()))continue;active.remove(hit.thread().uniqueID());vm.eventRequestManager().deleteEventRequest(value.request());
                        log("return",Map.of("actor",uuid(value.player()),"menu",value.menu().uniqueID(),"chest",value.chest().uniqueID(),"method",hit.method().name(),"phase",value.phase(),"thread",hit.thread().name(),"count",chestCount(value.chest()),"valid",hit.method().equals(valid)?((BooleanValue)hit.returnValue()).value():true));
                    }
                }}finally{events.resume();}
            }
            if(!Files.exists(done)||!active.isEmpty())throw new AssertionError("Missing original shared-chest completion");log("observed",Map.of("complete",true));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
