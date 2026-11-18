#!/usr/bin/env python3
"""One real nonfatal starvation damage: normal TCP movement, original hurt and health/stat feedback."""
import argparse,hashlib,json,shutil,socket,subprocess,time,re
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
TOOLS=Path(__file__).resolve().parent;REPO=TOOLS.parents[1];JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'
def main():
 p=argparse.ArgumentParser();p.add_argument('--server',type=Path,required=True);p.add_argument('--production-source-root',type=Path);p.add_argument('--foreign-watcher',action='store_true');a=p.parse_args();root=a.server.resolve();s=Session(root,'worker-starvation-damage');r={'status':'running'};debug=None
 assert not list((root/'mods').glob('*.jar'))
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}');r.update(installation=s.install,actualLaunch=s.install['launch'],sources={},foreignWatcher=a.foreign_watcher)
 files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerStarvationDamage.java',TOOLS/'proof_events.py',TOOLS/'install.py']+list((REPO/'src/main/java/net/neoforged/neoforge/server/threading/region').glob('RegionPlayer*.java'))+[REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java',REPO/'patches/net/minecraft/server/level/ServerPlayer.java.patch',REPO/'patches/net/minecraft/server/level/ChunkMap.java.patch']
 for f in files:
  relative=f.relative_to(REPO)
  if a.production_source_root and str(relative).startswith(('src/','patches/')):
   frozen=a.production_source_root.resolve()/relative
   if frozen.is_file():f=frozen
   else:
    original=subprocess.run(['git','show','HEAD:'+str(relative)],cwd=REPO,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if original.returncode:continue
    assert original.stdout==f.read_bytes(),'Unfrozen changed production source '+str(relative)
  d=s.output/'source-snapshot'/relative;d.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,d);r['sources'][str(relative)]=hashlib.sha256(f.read_bytes()).hexdigest()
 try:
  s.start();s.command('difficulty normal');
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  s.command('fill 38 99 16 60 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('clear @a');s.command('effect clear @a');s.command('attribute WorkerA minecraft:generic.max_health base set 20');s.command('tp WorkerA 47.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');time.sleep(2)
  if not a.foreign_watcher:s.request('disconnect',name='WorkerB')
  time.sleep(2)
  s.command('effect give WorkerA minecraft:hunger 20 255 true')
  until=time.monotonic()+12
  while time.monotonic()<until:
   if re.search(r': 0\s*$',s.command('data get entity WorkerA foodLevel')):break
   time.sleep(.15)
  else:raise AssertionError('Original hunger fixture did not exhaust food')
  s.command('effect clear WorkerA');time.sleep(.6);s.command('tick freeze')
  before=s.snapshot('before');actor=next(x['uuid'] for x in before['players'] if x['name']=='WorkerA');r['actor']=actor;r['before']=before;r['foodBefore']=s.command('data get entity WorkerA foodLevel');r['healthBefore']=s.command('data get entity WorkerA Health')
  assert re.search(r': 20\.0f\s*$',r['healthBefore']) and re.search(r': 0\s*$',r['foodBefore']), 'Original fixture is not full-health empty-food'
  trace=s.output/'jdi.ndjson';debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerStarvationDamage.java'),str(port),str(trace),actor,str(s.output/'observer-done')])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if trace.exists() and any(x['event']=='armed' for x in read_complete_events(trace)):break
   if debug.poll() is not None:raise AssertionError('Damage observer exited before arm')
   time.sleep(.1)
  s.request('mark',label='damage_before');s.request('packet',name='WorkerA',packet='position_look',data={'x':47.5,'y':100,'z':24.5,'yaw':0,'pitch':0,'onGround':True});s.command('tick unfreeze');
  debug.wait(timeout=18);assert debug.returncode==0;debug=None;s.command('tick freeze')
  r['after']=s.snapshot('after');r['healthAfter']=s.command('data get entity WorkerA Health');r['foodAfter']=s.command('data get entity WorkerA foodLevel');r['stats']=s.command('data get entity WorkerA');s.request('mark',label='damage_after');(s.output/'observer-done').write_text('normal movement and original starvation tick complete\n')
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();r['status']='passed'
 except BaseException as e:r.update(status='failed',error=repr(e));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0
if __name__=='__main__':main()
