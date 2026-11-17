#!/usr/bin/env python3
"""Real original floating/idle timeout disconnects; no Mod, normal TCP input and wall clock."""
import argparse,json,time,hashlib,shutil
from pathlib import Path
from run_worker_lifecycle import Session
TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args();root=a.server.resolve()
 assert not list((root/'mods').glob('*.jar'))
 props=root/'server.properties';props.write_text(props.read_text().replace('allow-flight=true','allow-flight=false'))
 s=Session(root,'p2-timers');result={'status':'running','installation':s.install}
 for name in ['run_p2_timers.py','lifecycle_clients.cjs','run_worker_lifecycle.py']:
  shutil.copy2(TOOLS/name,s.output/name)
 result['sources']={name:hashlib.sha256((TOOLS/name).read_bytes()).hexdigest() for name in ['run_p2_timers.py','lifecycle_clients.cjs','run_worker_lifecycle.py']}
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(1)
  for x in [24,120]:s.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('tp WorkerA 24.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');s.command('op WorkerA');time.sleep(3)
  s.snapshot('stable');s.request('mark',label='timers_before')
  s.request('chat',name='WorkerA',text='/setidletimeout 1');time.sleep(.4)
  for i in range(1,17):
   s.request('packet',name='WorkerA',packet='position_look',data={'x':24.5,'y':100+i*.25,'z':24.5,'yaw':0,'pitch':0,'onGround':False});time.sleep(.06)
  s.snapshot('floating-input');s.request('mark',label='floating_input_done');start=time.monotonic();samples=[]
  while time.monotonic()-start<90:
   time.sleep(1);state=s.snapshot('timer-'+str(len(samples)));samples.append(state)
   if not state['players']:break
  assert not samples[-1]['players'],'Original timers did not remove both actual TCP players'
  s.request('mark',label='timers_after');s.snapshot('final');s.request('close');s.bots.wait(timeout=10);s.stop();result.update(status='passed',elapsedSeconds=time.monotonic()-start)
 except BaseException as error:result.update(status='failed',error=repr(error));raise
 finally:
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 assert result['serverExits']==[0] and result['clientsExit']==0

if __name__=='__main__':main()
