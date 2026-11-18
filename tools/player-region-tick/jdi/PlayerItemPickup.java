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

/** External read-only original pickup/event/storage observer. Never invokes a game method or writes state. */
public class PlayerItemPickup {
    static PrintWriter output;
    static long sequence;
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();map.forEach((k,v) -> parts.add(json(k.toString())+":"+json(v)));
            return "{"+String.join(",",parts)+"}";
        }
        if (value instanceof List<?> list) return "["+String.join(",",list.stream().map(PlayerItemPickup::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static void log(String event, Map<String,Object> fields) {
        var row = new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(fields);
        output.println(json(row));output.flush();
    }
    static Value field(ObjectReference object,String name) {
        var f=object.referenceType().fieldByName(name);if(f==null)throw new AssertionError("Missing original field "+object.referenceType().name()+"."+name);
        return object.getValue(f);
    }
    static int number(ObjectReference object,String name) {return ((PrimitiveValue)field(object,name)).intValue();}
    static String uuid(ObjectReference object) {
        var value=(ObjectReference)field(object,"uuid");
        return new UUID(((LongValue)field(value,"mostSigBits")).value(),((LongValue)field(value,"leastSigBits")).value()).toString();
    }
    static List<String> stack(ThreadReference thread)throws Exception {
        return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();
    }
    static ObjectReference itemStack(ObjectReference entity) {
        var accessor=(ObjectReference)entity.referenceType().getValue(entity.referenceType().fieldByName("DATA_ITEM"));
        var data=(ObjectReference)field(entity,"entityData");var items=(ArrayReference)field(data,"itemsById");
        return (ObjectReference)field((ObjectReference)items.getValue(number(accessor,"id")),"value");
    }
    static int inventory(ObjectReference player,ObjectReference material) {
        var inventory=(ObjectReference)field(player,"inventory");var list=(ObjectReference)field((ObjectReference)field(inventory,"items"),"list");
        String name=list.referenceType().name();ArrayReference array;
        if(name.equals("java.util.Arrays$ArrayList"))array=(ArrayReference)field(list,"a");
        else if(name.equals("java.util.ArrayList"))array=(ArrayReference)field(list,"elementData");
        else throw new AssertionError("Unknown original inventory backing "+name);
        int total=0;
        for(Value value:array.getValues())if(value instanceof ObjectReference slot && material.equals(field(slot,"item")))total+=number(slot,"count");
        return total;
    }
    record Active(ObjectReference entity,ObjectReference player,ObjectReference material,int count,int inventory,int xp,String thread) {}
    public static void main(String[] args)throws Exception {
        String actor=args[1],target=args[3];int expected=Integer.parseInt(args[4]);
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[2]),StandardOpenOption.CREATE_NEW));
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);
        var vm=connector.attach(options);
        if(!vm.canGetMethodReturnValues())throw new AssertionError("Read-only event return observation unavailable");
        var itemType=vm.classesByName("net.minecraft.world.entity.item.ItemEntity").getFirst();
        var pickup=itemType.methodsByName("playerTouch","(Lnet/minecraft/world/entity/player/Player;)V").getFirst();
        var callbackType=vm.classesByName("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback").getFirst();
        var removal=callbackType.methodsByName("onRemove").getFirst();
        for(var method:List.of(pickup,removal)) {
            var entry=vm.eventRequestManager().createBreakpointRequest(method.location());entry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);entry.enable();
        }
        var exits=new HashMap<Long,List<MethodExitRequest>>();
        var storageExits=new HashMap<Long,MethodExitRequest>();
        var active=new HashMap<Long,Active>();boolean complete=false,storage=false;ObjectReference removed=null;
        long deadline=System.nanoTime()+120_000_000_000L;
        log("armed",Map.of("actor",actor,"target",target));
        try {
            while((!complete||!storage)&&System.nanoTime()<deadline) {
                var events=vm.eventQueue().remove(1000);if(events==null)continue;
                try {
                    for(var event:events) {
                        if(event instanceof BreakpointEvent hit && hit.location().method().equals(pickup)) {
                            var frame=hit.thread().frame(0);var player=(ObjectReference)frame.getArgumentValues().getFirst();var entity=frame.thisObject();
                            if(!uuid(player).equals(actor)||!uuid(entity).equals(target))continue;
                            var item=itemStack(entity);var material=(ObjectReference)field(item,"item");
                            var value=new Active(entity,player,material,number(item,"count"),inventory(player,material),number(player,"totalExperience"),hit.thread().name());
                            if(active.put(hit.thread().uniqueID(),value)!=null)throw new AssertionError("Nested original pickup");
                            var requests=new ArrayList<MethodExitRequest>();
                            for(String type:List.of(itemType.name(),"net.neoforged.neoforge.event.EventHooks")) {
                                var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(type);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();requests.add(exit);
                            }
                            exits.put(hit.thread().uniqueID(),requests);
                            removed=entity;
                            log("enter",Map.of("actor",actor,"entity",target,"thread",value.thread(),"count",value.count(),"inventory",value.inventory(),"xp",value.xp(),"pickupDelay",number(entity,"pickupDelay"),"stack",stack(hit.thread())));
                        } else if(event instanceof BreakpointEvent hit && hit.location().method().equals(removal)) {
                            var entity=(ObjectReference)field(hit.thread().frame(0).thisObject(),"realEntity");
                            if(entity!=null&&removed!=null&&entity.equals(removed)) {
                                var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(callbackType);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                                if(storageExits.put(hit.thread().uniqueID(),exit)!=null)throw new AssertionError("Nested original storage callback");
                                log("storage-enter",Map.of("entity",target,"thread",hit.thread().name(),"stack",stack(hit.thread())));
                            }
                        } else if(event instanceof MethodExitEvent hit && hit.method().equals(removal)) {
                            var entity=(ObjectReference)field(hit.thread().frame(0).thisObject(),"realEntity");
                            if(entity==null||removed==null||!entity.equals(removed))continue;
                            vm.eventRequestManager().deleteEventRequest(storageExits.remove(hit.thread().uniqueID()));
                            if(storage)throw new AssertionError("Duplicate original storage removal");storage=true;
                            log("storage-return",Map.of("entity",target,"thread",hit.thread().name(),"levelCallbackClass",((ObjectReference)field(entity,"levelCallback")).referenceType().name()));
                        } else if(event instanceof MethodExitEvent hit && hit.method().equals(pickup)) {
                            var value=active.remove(hit.thread().uniqueID());if(value==null)continue;
                            vm.eventRequestManager().deleteEventRequests(exits.remove(hit.thread().uniqueID()));
                            boolean discarded=field(value.entity(),"removalReason")!=null;int count=number(itemStack(value.entity()),"count");int inv=inventory(value.player(),value.material());
                            log("return",Map.of("actor",actor,"entity",target,"thread",hit.thread().name(),"countBefore",value.count(),"stackCountAfter",count,"remaining",discarded?0:count,"inventoryBefore",value.inventory(),"inventoryAfter",inv,"xp",number(value.player(),"totalExperience"),"removed",discarded));
                            if(discarded&&inv==expected)complete=true;
                        } else if(event instanceof MethodExitEvent hit && hit.method().declaringType().name().equals("net.neoforged.neoforge.event.EventHooks")) {
                            var value=active.get(hit.thread().uniqueID());if(value==null)continue;
                            if(hit.method().name().equals("fireItemPickupPre")) {
                                var pre=(ObjectReference)hit.returnValue();var state=(ObjectReference)field(pre,"canPickup");
                                log("pickup-pre",Map.of("entity",target,"thread",hit.thread().name(),"result",((StringReference)field(state,"name")).value(),"inventory",inventory(value.player(),value.material())));
                            } else if(hit.method().name().equals("fireItemPickupPost")) {
                                var original=(ObjectReference)hit.thread().frame(0).getArgumentValues().get(2);
                                log("pickup-post",Map.of("entity",target,"thread",hit.thread().name(),"originalCount",number(original,"count"),"remaining",number(itemStack(value.entity()),"count"),"inventory",inventory(value.player(),value.material())));
                            }
                        }
                    }
                } finally {events.resume();}
            }
            if(!complete||!storage||!active.isEmpty())throw new AssertionError("Missing complete original item/event/storage path");
            log("observed",Map.of("inventory",expected,"remaining",0));
        } finally {try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
