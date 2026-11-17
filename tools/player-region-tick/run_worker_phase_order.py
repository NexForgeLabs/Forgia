#!/usr/bin/env python3
"""Read-only external breakpoints at ORIGINAL event hooks and phase entrances; no game Mod."""
import argparse,hashlib,json,socket,subprocess,time,shutil
from pathlib import Path
from run_worker_lifecycle import Session
from run_worker_fault import TOOLS,REPO,JAVA

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args();root=a.server.resolve();assert not list((root/'mods').glob('*.jar'))
 s=Session(root,'worker-phase-order')
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 sources={}
 for file in [TOOLS/'jdi/PlayerPhaseOrder.java',Path(__file__).resolve(),REPO/'src/main/java/net/neoforged/neoforge/event/EventHooks.java']:
  relative=str(file.relative_to(REPO));dest=s.output/'source-snapshot'/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest);sources[relative]=hashlib.sha256(file.read_bytes()).hexdigest()
 result={'status':'running','installation':s.install,'sources':sources,'actualLaunch':s.install['launch']};debug=None
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(1)
  for x in [24,120]:s.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('tp WorkerA 24.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');time.sleep(3)
  # Also observe the original empty-Level >=300-tick BE eligibility, with active players elsewhere.
  time.sleep(16)
  state=s.snapshot('before');ids=','.join(p['uuid'] for p in state['players'])
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger-command'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerPhaseOrder.java'),str(port),ids,str(s.output/'jdi.ndjson')]);debug.wait(timeout=100);assert debug.returncode==0
  s.snapshot('after');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();result['status']='passed'
 except BaseException as error:result.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 assert result['serverExits']==[0] and result['clientsExit']==0

if __name__=='__main__':main()
