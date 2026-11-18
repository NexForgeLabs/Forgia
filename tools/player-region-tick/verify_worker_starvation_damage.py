#!/usr/bin/env python3
"""Original complete hurt/events/results and independent TCP health/stat conservation verifier."""
import argparse,base64,hashlib,json,re,subprocess,zipfile
from pathlib import Path
TOOLS=Path(__file__).resolve().parent

def require(ok,message):
 if not ok:raise AssertionError(message)

def lines(path):
 b=path.read_bytes();require(b and b.endswith(b'\n'),'uncommitted closed damage evidence '+str(path));return [json.loads(x) for x in b.splitlines()]

def verify(root,installer):
 out=root/'worker-starvation-damage';load=lambda p:json.loads(Path(p).read_text());install=load(root/'installation.json');r=load(out/'result.json');foreign=r.get('foreignWatcher',False)
 require(r['status']=='passed' and r['serverExits']==[0] and r['clientsExit']==0,'original damage runner/process failure')
 require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'damage proof loaded test Mod or unsafe installer inputs')
 require(r['installation']==dict(install,launch=r['actualLaunch']) and hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'damage installed artifact/launch mismatch')
 path=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as z:require((root/'libraries'/path).read_bytes()==z.read('maven/'+path),'damage actual universal differs')
 for name,digest in r['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'damage source changed '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in r['actualLaunch']) and not any(x.startswith('-javaagent:') for x in r['actualLaunch']),'damage observer not original external debugger')
 commands=lines(out/'commands.ndjson');states=[json.loads(x['response']) for x in commands if x['command']=='neoforge regionthreading status players'];packets=lines(out/'packets.ndjson')
 for label in ['before','after']:
  d=r[label];require(d in states and d['requested']==install['playerPlanningRequested'] and d['persistent']==install['persistent'],'invented damage state/mode')
  require(d['activeBatches']==d['stateClaims']==d['phases']['pending']==d['phases']['running']==0 and all(x['failed']==0 for x in d['phases']['phases'].values()),'damage body failure or live scope')
  require(len(d['players'])==(2 if foreign else 1) and all(x['ownerCurrent'] for x in d['players']),'damage proof wrong player/owner footprint')
 actor=r['actor'];p=next(x for x in r['before']['players'] if x['uuid']==actor);require(p['name']=='WorkerA' and [p[k] for k in ['x','y','z','health','regionX','regionZ']]==[47.5,100,24.5,20,0,0],'damage original actor/health/region mismatch')
 for key,value in [('healthBefore',20),('healthAfter',19),('foodBefore',0),('foodAfter',0)]:
  require(any(x['response']==r[key] for x in commands),'missing original damage resource read '+key)
  match=re.search(r': ([-\d.]+)f?\s*$',r[key]);require(match and float(match[1])==value,'damage original health/food result wrong')
 require(any(x['command']=='effect give WorkerA minecraft:hunger 20 255 true' for x in commands) and any(x['command']=='effect clear WorkerA' for x in commands),'original hunger fixture missing')
 require(not any(x['command'].startswith(('damage ','data merge entity')) for x in commands),'damage gameplay bypassed original timer or used refused player NBT mutation')
 rows=lines(out/'jdi.ndjson');require(rows[0]['event']=='armed' and rows[-1]['event']=='observed' and rows[-1]['complete'] and rows[0]['actor']==actor and [x['seq'] for x in rows]==list(range(1,len(rows)+1)),'original damage observer incomplete')
 expected=[('enter',None),('hook-enter','onEntityIncomingDamage'),('hook-return','onEntityIncomingDamage'),('hook-enter','onDamageBlock'),('hook-return','onDamageBlock'),('hook-enter','onLivingDamagePre'),('hook-return','onLivingDamagePre'),('hook-enter','onVanillaGameEvent'),('hook-return','onVanillaGameEvent'),('game-event','post'),('hook-enter','onLivingDamagePost'),('hook-return','onLivingDamagePost'),('damage-packet','broadcastDamageEvent'),('hook-enter','onPlaySoundAtPosition'),('hook-return','onPlaySoundAtPosition'),('sound-broadcast','broadcast'),('return',None)]
 require([(x['event'],x.get('method')) for x in rows[1:-1]]==expected,'damage original hook/game-event/sound order or count changed')
 entered,returned=rows[1],rows[-2];worker=install['playerPlanningRequested'] and not foreign
 require(entered['source']=='starve' and entered['amount']==1 and entered['health']==20 and returned['health']==19 and returned['result'] is True,'original damage source/amount/synchronous result changed')
 require(all(x['actor']==actor and x['thread']==entered['thread'] for x in rows[1:-1]) and (('Worker' in entered['thread']) if worker else entered['thread']=='Server thread'),'damage entered wrong worker/thread or actor')
 require(all(any(name in f for f in entered['stack']) for name in ['ServerPlayer.hurt:','FoodData.tick:','Player.tick:','ServerPlayer.doTick:','Connection.tick:']),'damage escaped complete original food/player/connection stack')
 if worker:require(any('RegionPlayerTickDispatch.runOriginal:' in f for f in entered['stack']),'damage side task masquerading as full original worker')
 require(rows[3]['result'] is False and rows[7]['result']==1 and rows[9]['result'] is True,'original incoming/pre/game-event synchronous result changed')
 require(all(x['health']==(20 if x['seq']<=8 else 19) for x in rows[1:-1]),'damage event state visibility moved before/after original write')
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'damage raw frame trace truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'damage raw frame checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_damage_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()];marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 window=[x for x in raw if marks['damage_before']<x['seq']<marks['damage_after']]
 moves=[x for x in window if x['direction']=='frame_send' and x['bot']=='WorkerA' and x['name']=='position_look'];require(len(moves)==1 and [moves[0]['data'][k] for k in ['x','y','z']]==[47.5,100,24.5],'damage observation did not originate from ordinary client packet')
 damage=[x for x in window if x['direction']=='frame' and x['bot']=='WorkerA' and x['name']=='damage_event'];require(len(damage)==1,'actual damage feedback missing/duplicated')
 health=[x for x in window if x['direction']=='frame' and x['bot']=='WorkerA' and x['name']=='update_health'];require(health and health[-1]['data']['health']==19 and health[-1]['data']['food']==0 and health[-1]['seq']>damage[0]['seq'],'actual client final health/food or original damage ordering wrong')
 # Player.playSound excludes its own connection; with a sole player the original broadcast has zero recipients.
 if not foreign:require(not any(x['direction']=='frame' and x['name'] in ['sound_effect','entity_sound_effect'] for x in window),'self-only original hurt emitted an unexpected sound recipient')
 stats=load(root/'world/stats'/f'{actor}.json')['stats'].get('minecraft:custom',{});require(stats.get('minecraft:damage_taken')==10,'damage stat not exactly one original health point')
 saved=json.loads(subprocess.check_output(['node',str(TOOLS/'read_saved_nbt.cjs'),str(root/'world/playerdata'/f'{actor}.dat')],text=True));require(saved['Health']==19 and saved['foodLevel']==0 and not saved.get('Inventory') and saved['XpTotal']==0,'saved damage/inventory/XP not conserved')
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['stateClaims']==final['activeBatches']==0,'damage logout retains owner/claim')
 require(not final['network']['frozen'] and final['network']['pending']==final['network']['readers']==final['network']['terminals']==0,'damage logout network retains')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'original damage server failed')
 return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],foreignWatcher=foreign,workerDamage=worker,health=[20,19],originalResult=True,originalDamageEffects=1,damagePackets=1,damageTakenStat=10,originalGameEvents=1,originalSoundBroadcasts=1,originalHooks=6,scope='One nonfatal original starvation hit with exact empty event registries and sole player/no watcher; foreign player serial; original full hurt/hooks/game-event/sound/synchronous result retained; general PvP remains original packet fence')

def main():
 p=argparse.ArgumentParser();p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-starvation-damage/independent-verdict.json';require(not out.exists(),'preserve damage verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as e:out.write_text(json.dumps(dict(status='failed',error=repr(e)),indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
if __name__=='__main__':main()
