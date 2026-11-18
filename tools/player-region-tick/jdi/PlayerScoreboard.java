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

/** Read-only observer of the original score update and original saved-data dirty callback. */
public class PlayerScoreboard {
    static PrintWriter output;static long sequence;
    static String json(Object value){if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(PlayerScoreboard::json).toList())+"]";return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    static void log(String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("seq",++sequence);row.put("event",event);row.putAll(fields);output.println(json(row));output.flush();}
    static Value field(ObjectReference object,String name){return object.getValue(object.referenceType().fieldByName(name));}
    static String uuid(ObjectReference player){var value=(ObjectReference)field(player,"uuid");return new UUID(((LongValue)field(value,"mostSigBits")).value(),((LongValue)field(value,"leastSigBits")).value()).toString();}
    static List<String> stack(ThreadReference thread)throws Exception{return thread.frames().stream().limit(40).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
    record Active(ObjectReference board,ObjectReference player,ObjectReference objective,ObjectReference score,MethodExitRequest request,int value){}
    public static void main(String[] args)throws Exception{
        output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));var actors=Set.of(args[2],args[3]);Path done=Path.of(args[4]);
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);var vm=connector.attach(options);
        var type=vm.classesByName("net.minecraft.server.ServerScoreboard").getFirst();var changed=type.methodsByName("onScoreChanged").getFirst();var dirty=type.methodsByName("setDirty").getFirst();var entry=vm.eventRequestManager().createBreakpointRequest(changed.location());entry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);entry.enable();
        var active=new HashMap<Long,Active>();long deadline=System.nanoTime()+180_000_000_000L;log("armed",Map.of("actors",List.copyOf(actors)));
        try{
            while((!Files.exists(done)||!active.isEmpty())&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(500);if(events==null)continue;
                try{for(var event:events){
                    if(event instanceof BreakpointEvent hit){
                        var frame=hit.thread().frame(0);var values=frame.getArgumentValues();var player=(ObjectReference)values.get(0);if(!player.referenceType().name().equals("net.minecraft.server.level.ServerPlayer")||!actors.contains(uuid(player)))continue;
                        var objective=(ObjectReference)values.get(1);String name=((StringReference)field(objective,"name")).value();if(!Set.of("p3_time","p3_xp").contains(name))continue;
                        var score=(ObjectReference)values.get(2);var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(type);exit.addThreadFilter(hit.thread());exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();
                        var value=new Active(frame.thisObject(),player,objective,score,exit,((IntegerValue)field(score,"value")).value());if(active.put(hit.thread().uniqueID(),value)!=null)throw new AssertionError("Nested original score callback");
                        log("score-enter",Map.of("actor",uuid(player),"objective",name,"board",value.board().uniqueID(),"score",score.uniqueID(),"value",value.value(),"thread",hit.thread().name(),"stack",stack(hit.thread())));
                    }else if(event instanceof MethodExitEvent hit){
                        var value=active.get(hit.thread().uniqueID());if(value==null)continue;
                        var saved=(ObjectReference)field(value.board(),"forgiaPlayerSaveData");
                        if(hit.method().equals(dirty))log("dirty-return",Map.of("actor",uuid(value.player()),"board",value.board().uniqueID(),"savedData",saved.uniqueID(),"dirty",((BooleanValue)field(saved,"dirty")).value(),"thread",hit.thread().name()));
                        else if(hit.method().equals(changed)){
                            active.remove(hit.thread().uniqueID());vm.eventRequestManager().deleteEventRequest(value.request());
                            log("score-return",Map.of("actor",uuid(value.player()),"objective",((StringReference)field(value.objective(),"name")).value(),"board",value.board().uniqueID(),"score",value.score().uniqueID(),"value",((IntegerValue)field(value.score(),"value")).value(),"savedData",saved.uniqueID(),"dirty",((BooleanValue)field(saved,"dirty")).value(),"thread",hit.thread().name()));
                        }
                    }
                }}finally{events.resume();}
            }
            if(!Files.exists(done)||!active.isEmpty())throw new AssertionError("Original scoreboard observation incomplete");log("observed",Map.of("complete",true));
        }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
    }
}
