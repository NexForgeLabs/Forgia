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

/** Reads original damage, event, game-event and sound state. No game invocations, writes or class changes. */
public class PlayerStarvationDamage {
 static PrintWriter output;static long sequence;
 static String json(Object v){if(v==null)return "null";if(v instanceof Number||v instanceof Boolean)return v.toString();if(v instanceof Map<?,?>m){var p=new ArrayList<String>();m.forEach((k,x)->p.add(json(k.toString())+":"+json(x)));return "{"+String.join(",",p)+"}";}if(v instanceof List<?>l)return "["+String.join(",",l.stream().map(PlayerStarvationDamage::json).toList())+"]";return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
 static void log(String e,Map<String,Object> f){var r=new LinkedHashMap<String,Object>();r.put("seq",++sequence);r.put("event",e);r.putAll(f);output.println(json(r));output.flush();}
 static Value field(ObjectReference o,String n){var f=o.referenceType().fieldByName(n);if(f==null)throw new AssertionError("Missing original field "+o.referenceType().name()+"."+n);return o.getValue(f);}
 static float number(ObjectReference o,String n){return ((PrimitiveValue)field(o,n)).floatValue();}
 static float health(ObjectReference o){var data=(ObjectReference)field(o,"entityData");var accessor=(ObjectReference)o.referenceType().getValue(o.referenceType().fieldByName("DATA_HEALTH_ID"));var items=(ArrayReference)field(data,"itemsById");var item=(ObjectReference)items.getValue((int)number(accessor,"id"));return number((ObjectReference)field(item,"value"),"value");}
 static String uuid(ObjectReference o){var v=(ObjectReference)field(o,"uuid");return new UUID(((LongValue)field(v,"mostSigBits")).value(),((LongValue)field(v,"leastSigBits")).value()).toString();}
 static List<String> stack(ThreadReference t)throws Exception{return t.frames().stream().limit(50).map(f->f.location().declaringType().name()+"."+f.location().method().name()+":"+f.location().lineNumber()).toList();}
 static Method method(VirtualMachine vm,String type,String name){var classes=vm.classesByName(type);if(classes.size()!=1)throw new AssertionError("Original class not prepared: "+type);return classes.getFirst().methodsByName(name).getFirst();}
 static ObjectReference player;static ThreadReference owner;static boolean completed;
 public static void main(String[]args)throws Exception{
  String actor=args[2];output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1]),StandardOpenOption.CREATE_NEW));
  var c=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(x->x.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();var op=c.defaultArguments();op.get("hostname").setValue("127.0.0.1");op.get("port").setValue(args[0]);var vm=c.attach(op);
  if(!vm.canGetMethodReturnValues())throw new AssertionError("Original hurt return unavailable");
  var hurt=method(vm,"net.minecraft.server.level.ServerPlayer","hurt");
  var hookNames=List.of("onEntityIncomingDamage","onDamageBlock","onLivingDamagePre","onLivingDamagePost","onVanillaGameEvent");
  var hooks=new HashSet<Method>();for(String name:hookNames)hooks.add(method(vm,"net.neoforged.neoforge.common.CommonHooks",name));
  hooks.add(method(vm,"net.neoforged.neoforge.event.EventHooks","onPlaySoundAtPosition"));
  var post=method(vm,"net.minecraft.world.level.gameevent.GameEventDispatcher","post");
  var damage=method(vm,"net.minecraft.server.level.ServerLevel","broadcastDamageEvent");
  var sound=method(vm,"net.minecraft.server.players.PlayerList","broadcast");
  var entry=vm.eventRequestManager().createBreakpointRequest(hurt.location());entry.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);entry.enable();
  var exits=new HashMap<Method,MethodExitRequest>();var temporary=new ArrayList<EventRequest>();
  long deadline=System.nanoTime()+90_000_000_000L;log("armed",Map.of("actor",actor));
  try{
   while(!completed&&System.nanoTime()<deadline){var events=vm.eventQueue().remove(500);if(events==null)continue;
    try{for(var event:events){
     if(event instanceof BreakpointEvent hit){
      var f=hit.thread().frame(0);var m=hit.location().method();
      if(m.equals(hurt)){
       var entity=f.thisObject();if(!uuid(entity).equals(actor))continue;if(owner!=null)throw new AssertionError("Repeated/nested original hurt");player=entity;owner=hit.thread();
       var source=(ObjectReference)f.getArgumentValues().getFirst();var type=(ObjectReference)field((ObjectReference)field(source,"type"),"value");
       log("enter",Map.of("actor",actor,"thread",owner.name(),"health",health(player),"amount",((PrimitiveValue)f.getArgumentValues().get(1)).floatValue(),"source",((StringReference)field(type,"msgId")).value(),"stack",stack(owner)));
       for(var hook:hooks){var bp=vm.eventRequestManager().createBreakpointRequest(hook.location());bp.addThreadFilter(owner);bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();temporary.add(bp);}
       for(var hook:List.of(post,damage,sound)){var bp=vm.eventRequestManager().createBreakpointRequest(hook.location());bp.addThreadFilter(owner);bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);bp.enable();temporary.add(bp);}
       var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(hurt.declaringType());exit.addThreadFilter(owner);exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();temporary.add(exit);
      }else if(owner!=null&&owner.equals(hit.thread())){
       var row=new LinkedHashMap<String,Object>();row.put("actor",actor);row.put("thread",owner.name());row.put("method",m.name());row.put("health",health(player));row.put("stack",stack(owner));
       if(hooks.contains(m)){
        log("hook-enter",row);var exit=vm.eventRequestManager().createMethodExitRequest();exit.addClassFilter(m.declaringType());exit.addThreadFilter(owner);exit.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);exit.enable();exits.put(m,exit);
       }else log(m.equals(post)?"game-event":m.equals(damage)?"damage-packet":"sound-broadcast",row);
      }
     }else if(event instanceof MethodExitEvent hit&&owner!=null&&owner.equals(hit.thread())){
      var m=hit.method();
      if(m.equals(hurt)){
       log("return",Map.of("actor",actor,"thread",owner.name(),"health",health(player),"result",((BooleanValue)hit.returnValue()).value()));completed=true;
      }else if(exits.containsKey(m)){
       vm.eventRequestManager().deleteEventRequest(exits.remove(m));var row=new LinkedHashMap<String,Object>();row.put("actor",actor);row.put("thread",owner.name());row.put("method",m.name());row.put("health",health(player));
       if(hit.returnValue() instanceof BooleanValue b)row.put("result",b.value());
       else if(hit.returnValue() instanceof FloatValue v)row.put("result",v.value());
       else if(hit.returnValue() instanceof ObjectReference v){row.put("resultClass",v.referenceType().name());}
       log("hook-return",row);
      }
     }
    }}finally{events.resume();}
   }
   if(!completed||!exits.isEmpty())throw new AssertionError("Missing complete original damage/hooks path");log("observed",Map.of("complete",true));
  }finally{try{vm.dispose();}catch(VMDisconnectedException ignored){}output.close();}
 }
}
