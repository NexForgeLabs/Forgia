#!/usr/bin/env python3
"""Production-only TCP lethal attack/respawn and new-instance worker re-admission evidence."""
import argparse,hashlib,json,re,shutil,time,socket,subprocess
from pathlib import Path
from run_worker_lifecycle import Session


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--diagnose',action='store_true');a=p.parse_args();root=a.server.resolve();s=Session(root,'worker-respawn')
 assert s.install['oracleSha256'] is None and not list((root/'mods').glob('*.jar'))
 shutil.copy2(Path(__file__),s.output/Path(__file__).name)
 if a.diagnose:
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));debug_port=sock.getsockname()[1]
  s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{debug_port}')
 result={'status':'running','installation':s.install,'sources':s.sources,'driverSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doImmediateRespawn false','keepInventory false']:
   s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [24,48,120]:s.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
  ids={name:s.request('connect',name=name,port=s.install['port'])['id'] for name in ['WorkerA','WorkerB']}
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
  s.command('tp WorkerA 24.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');s.command('spawnpoint WorkerA 24 100 24');time.sleep(4)
  s.snapshot('before-combat-fixture')
  s.command('tp WorkerA 49.5 100 24.5');s.command('tp WorkerB 47.5 100 24.5')
  s.command('item replace entity WorkerB weapon.mainhand with minecraft:netherite_sword 1');s.command('effect give WorkerB minecraft:strength 60 20 true');time.sleep(1)
  before=s.snapshot('before-death');result['beforeHealthRead']=s.command('data get entity WorkerA Health')
  s.request('mark',label='death_before')
  s.request('packet',name='WorkerB',packet='use_entity',data={'target':ids['WorkerA'],'mouse':1,'sneaking':False});time.sleep(1)
  result['deadHealthRead']=s.command('data get entity WorkerA Health');assert re.search(r': 0(?:\.0)?f\s*$',result['deadHealthRead'])
  s.snapshot('dead');s.request('mark',label='death_after')
  s.request('mark',label='respawn_before')
  s.request('packet',name='WorkerA',packet='client_command',data={'actionId':0})
  deadline=time.monotonic()+15
  while time.monotonic()<deadline:
   client=s.request('snapshot')['WorkerA']
   if client['health']==20 and abs(client['position']['x']-24.5)<.01:break
   time.sleep(.1)
  else:raise AssertionError('Normal client respawn failed')
  s.request('mark',label='respawn_after');spawn=s.snapshot('after-respawn')
  if a.diagnose:
   uuid=next(row for row in spawn['players'] if row['name']=='WorkerA')['uuid']
   tool=Path(__file__).resolve().parent/'jdi/PlayerStateDiagnostic.java'
   dest=s.output/'PlayerStateDiagnostic.java';shutil.copy2(tool,dest)
   source=Path(__file__).resolve().parents[2]/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java'
   lines=source.read_text().splitlines();start=next(i for i,text in enumerate(lines) if 'public RegionPlayerAdmissionPlan.Capture capturePlayerAdmissionPlan' in text)
   line=next(i+1 for i in range(start,len(lines)) if 'return capture;' in lines[i])
   argv=['/home/yc/.local/opt/jdk-21.0.13+9/bin/java','--add-modules','jdk.jdi',str(tool),str(debug_port),uuid,str(s.output/'original-state-diagnostic.json'),str(line)]
   subprocess.run(['python3',str(tool.parents[1]/'run_command.py'),'--output',str(s.output/'diagnostic-command'),'--']+argv,check=True)
  samples=[]
  for i in range(8):time.sleep(.5);samples.append(s.snapshot('new-instance-'+str(i)))
  (s.output/'new-instance-samples.json').write_text(json.dumps(samples,indent=2)+'\n')
  # Vanilla's forced respawn starts at blockY + .1. A normal client sends its next
  # gravity/ground simulation position; preserve the pre-settlement serial negative window above.
  s.request('mark',label='settle_before')
  s.request('packet',name='WorkerA',packet='position_look',data={'x':24.5,'y':100.0,'z':24.5,'yaw':0,'pitch':0,'onGround':True})
  time.sleep(1)
  settled=[]
  for i in range(8):time.sleep(.5);settled.append(s.snapshot('settled-instance-'+str(i)))
  (s.output/'settled-instance-samples.json').write_text(json.dumps(settled,indent=2)+'\n')
  s.request('mark',label='settle_after')
  result['healthAfterRead']=s.command('data get entity WorkerA Health');result['inventoryAfterRead']=s.command('data get entity WorkerA Inventory');result['xpAfterRead']=s.command('data get entity WorkerA XpTotal')
  s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');result['status']='passed'
 except BaseException as error:result.update(status='failed',error=repr(error));raise
 finally:
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 assert result['serverExits']==[0] and result['clientsExit']==0

if __name__=='__main__':main()
