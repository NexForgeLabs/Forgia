#!/usr/bin/env python3
"""P3 first XP branch: normal TCP movement, original pickup/return observer and conservation."""
import argparse,hashlib,json,re,shutil,socket,subprocess,time
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events

TOOLS=Path(__file__).resolve().parent
REPO=TOOLS.parents[1]
JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args()
 root=a.server.resolve();assert not list((root/'mods').glob('*.jar'))
 s=Session(root,'worker-xp-pickup');result={'status':'running','cases':[]};debug=None
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 result.update(installation=s.install,actualLaunch=s.install['launch'],sources={})
 files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerXpPickup.java',TOOLS/'proof_events.py',TOOLS/'install.py',REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerAdmissionPlan.java',REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerStatePlan.java',REPO/'patches/net/minecraft/server/level/ChunkMap.java.patch']
 for file in files:
  relative=file.relative_to(REPO);dest=s.output/'source-snapshot'/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest);result['sources'][str(relative)]=hashlib.sha256(file.read_bytes()).hexdigest()
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [48,120]:s.command(f'fill {x-10} 99 16 {x+10} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('tick freeze')
  cases=[('isolated',3,7,False),('foreign-watcher',3,7,True),('single',1,7,False),('over-budget',2,8,False)]
  for label,count,value,foreign in cases:
   s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
   s.command('tp WorkerA 45 100 24.5');s.command('tp WorkerB '+('53.5' if foreign else '120.5')+' 100 24.5');time.sleep(2)
   s.command(f'summon minecraft:experience_orb 48.5 100 24.5 {{Tags:["p3_xp_{label}"],Value:{value}s,Count:{count},Age:0s,NoGravity:1b,Invulnerable:1b}}')
   time.sleep(1)
   before=s.snapshot(label+'-before');actor=next(x['uuid'] for x in before['players'] if x['name']=='WorkerA')
   initial=s.command(f'data get entity @e[tag=p3_xp_{label},limit=1]');xpBefore=s.command('data get entity WorkerA XpTotal')
   assert re.search(r': 0\s*$',xpBefore)
   trace=s.output/('jdi-'+label+'.ndjson')
   argv=[JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerXpPickup.java'),str(port),actor,str(trace),str(count*value)]
   debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/('debugger-'+label)),'--',*argv])
   until=time.monotonic()+25
   while time.monotonic()<until:
    if trace.exists() and any(x['event']=='armed' for x in read_complete_events(trace)):break
    if debug.poll() is not None:raise AssertionError('Original XP observer exited before arm')
    time.sleep(.1)
   else:raise TimeoutError('Original XP observer did not arm')
   s.request('mark',label=label+'_before')
   s.request('packet',name='WorkerA',packet='position_look',data={'x':47.5,'y':100,'z':24.5,'yaw':0,'pitch':0,'onGround':True})
   debug.wait(timeout=95);assert debug.returncode==0,'Original XP conservation observer failed'
   debug=None;time.sleep(.3)
   after=s.snapshot(label+'-after');xpAfter=s.command('data get entity WorkerA XpTotal');remaining=s.command(f'execute if entity @e[tag=p3_xp_{label}]')
   assert re.search(r': '+str(count*value)+r'\s*$',xpAfter) and remaining.strip()=='Test failed'
   s.request('mark',label=label+'_after')
   result['cases'].append({'label':label,'count':count,'value':value,'foreignWatcher':foreign,'actor':actor,'initialOrbRead':initial,'xpBeforeRead':xpBefore,'xpAfterRead':xpAfter,'remainingRead':remaining,'before':before,'after':after})
  s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();result['status']='passed'
 except BaseException as error:
  result.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode)
  (s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 assert result['serverExits']==[0] and result['clientsExit']==0

if __name__=='__main__':main()
