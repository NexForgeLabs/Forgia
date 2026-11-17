/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.util.*;

/** Read-only external diagnostic of the existing pre-admission optional-state predicate. */
public class PlayerStateDiagnostic {
    static VirtualMachine vm;
    static ObjectReference object(ObjectReference owner,String name) { return (ObjectReference)owner.getValue(owner.referenceType().fieldByName(name)); }
    static Object scalar(Value v) {
        if(v==null)return null;
        if(v instanceof PrimitiveValue p) {
            if(v instanceof BooleanValue b)return b.value();
            if(v instanceof FloatValue||v instanceof DoubleValue)return p.doubleValue();
            return p.longValue();
        }
        if(v instanceof StringReference s)return s.value();
        var o=(ObjectReference)v;
        return o.referenceType().name()+"#"+o.uniqueID();
    }
    static Value call(ObjectReference owner,ThreadReference thread,String name) throws Exception {
        Method method=owner.referenceType().allMethods().stream().filter(m->m.name().equals(name)&&m.argumentTypeNames().isEmpty()&&!m.isAbstract()).findFirst().orElseThrow();
        return owner.invokeMethod(thread,method,List.of(),ObjectReference.INVOKE_SINGLE_THREADED);
    }
    static String uuid(ObjectReference p) {
        var u=object(p,"uuid");return new UUID(((LongValue)u.getValue(u.referenceType().fieldByName("mostSigBits"))).value(),((LongValue)u.getValue(u.referenceType().fieldByName("leastSigBits"))).value()).toString();
    }
    static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";
    }
    public static void main(String[] args) throws Exception {
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);options.get("timeout").setValue("10000");vm=connector.attach(options);
        try(var out=Files.newBufferedWriter(Path.of(args[2]),StandardOpenOption.CREATE_NEW)) {
            ReferenceType type=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan").getFirst();
            var req=vm.eventRequestManager().createBreakpointRequest(type.methodsByName("intrinsic").getFirst().location());req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);req.enable();
            var result=new LinkedHashMap<String,Object>();
            var admissions=new LinkedHashMap<String,Object>();
            var engine=vm.classesByName("net.neoforged.neoforge.server.threading.region.RegionThreadingEngine").getFirst();
            var method=engine.methodsByName("capturePlayerAdmissionPlan").getFirst();
            var admission=vm.eventRequestManager().createBreakpointRequest(method.allLineLocations().stream().filter(l->l.lineNumber()==Integer.parseInt(args[3])).findFirst().orElseThrow());admission.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);admission.enable();
            boolean captured=false;long deadline=System.nanoTime()+30_000_000_000L;
            while((!captured||admissions.size()<2)&&System.nanoTime()<deadline) {
                EventSet events=vm.eventQueue().remove(1000);if(events==null)continue;
                try {
                    for(Event event:events)if(event instanceof BreakpointEvent b && b.thread().name().equals("Server thread")) {
                        var thread=b.thread();var frame=thread.frame(0);var player=(ObjectReference)frame.getArgumentValues().getFirst();if(!uuid(player).equals(args[1]))continue;
                        if(b.request()==admission) {
                            var capture=(ObjectReference)frame.getValue(frame.visibleVariableByName("capture"));
                            var phase=(ObjectReference)frame.getArgumentValues().get(1);
                            String phaseName=((StringReference)phase.getValue(phase.referenceType().fieldByName("name"))).value();
                            var refusal=object(capture,"refusal");
                            String refusalName=((StringReference)refusal.getValue(refusal.referenceType().fieldByName("name"))).value();
                            admissions.put(phaseName,Map.of("refusal",refusalName,"detail",((StringReference)capture.getValue(capture.referenceType().fieldByName("detail"))).value()));
                            continue;
                        }
                        if(captured)continue;
                        var data=new LinkedHashMap<String,Object>();data.put("uuid",args[1]);data.put("thread",thread.name());data.put("playerObjectId",player.uniqueID());
                        var fields=new LinkedHashMap<String,Object>();
                        for(String name:List.of("moveDist","nextStep","appliedScale","wasOnFire","remainingFireTicks","isInPowderSnow","forcedPose","mainSupportingBlockPos","lastHurtMob","lastHurtByMob","lastBodyItemStack","lastItemInMainHand","sleepingPos","airSupply","noPhysics","onGround")) {
                            Field f=player.referenceType().fieldByName(name);fields.put(name,f==null?"absent":scalar(player.getValue(f)));
                        }
                        data.put("fields",fields);
                        var getters=new LinkedHashMap<String,Object>();
                        for(String name:List.of("isUsingItem","isSprinting","isShiftKeyDown","isSwimming","isInWater","isInLava","isInFluidType","getAirSupply","getMaxAirSupply","getForcedPose","getLastHurtMob","getLastHurtByMob","getHealth","getMaxHealth","getScale","isInWall","getRemainingFireTicks","getXRot","getYRot"))getters.put(name,scalar(call(player,thread,name)));
                        data.put("getters",getters);
                        var combat=object(player,"combatTracker");var combatFields=new LinkedHashMap<String,Object>();for(String name:List.of("inCombat","takingDamage"))combatFields.put(name,scalar(combat.getValue(combat.referenceType().fieldByName(name))));data.put("combat",combatFields);
                        var food=(ObjectReference)call(player,thread,"getFoodData");data.put("foodLevel",scalar(call(food,thread,"getFoodLevel")));
                        for(String name:List.of("lastBodyItemStack","lastItemInMainHand")) {
                            ObjectReference item=object(player,name);data.put(name+"Empty",scalar(call(item,thread,"isEmpty")));
                        }
                        for(String name:List.of("lastHandItemStacks","lastArmorItemStacks")) {
                            var list=object(player,name);data.put(name+"Size",scalar(call(list,thread,"size")));
                        }
                        ObjectReference support=object(player,"mainSupportingBlockPos");data.put("mainSupportingBlockPosPresent",scalar(call(support,thread,"isPresent")));
                        data.put("source","original RegionPlayerAdmissionPlan.intrinsic pre-body predicate; no field writes, class redefinition or game Mod");
                        result.putAll(data);captured=true;
                    }
                } finally {events.resume();}
            }
            if(!captured||admissions.size()<2)throw new AssertionError("No original two-phase pre-admission state captured");
            result.put("actualAdmissionResults",admissions);out.write(json(result));out.newLine();out.flush();
        } finally {vm.dispose();}
    }
}
