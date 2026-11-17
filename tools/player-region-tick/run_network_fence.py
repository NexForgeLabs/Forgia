#!/usr/bin/env python3
"""Pause a real original worker; submit normal TCP packets; observe fence and later replies."""
import argparse,hashlib,json,socket,subprocess,time,shutil
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
from run_worker_fault import source_line,TOOLS,REPO,JAVA


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--phase',choices=['ENTITY','CONNECTION'],required=True);p.add_argument('--overflow',action='store_true');a=p.parse_args();root=a.server.resolve()
 assert not list((root/'mods').glob('*.jar'))
 s=Session(root,'network-fence');assert s.install['oracleSha256'] is None and s.install['playerPlanningRequested']
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 source=TOOLS/'jdi/PlayerNetworkFence.java';player=REPO/'projects/neoforge/src/main/java/net/minecraft/server/level/ServerPlayer.java';connection=REPO/'projects/neoforge/src/main/java/net/minecraft/network/Connection.java';coordinator=REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerTickCoordinator.java';engine=REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java'
 if a.phase=='ENTITY':
  rows=player.read_text().splitlines();start=next(i for i,line in enumerate(rows) if 'public void tick()' in line);body=next(i+1 for i in range(start,len(rows)) if 'this.containerMenu.broadcastChanges();' in rows[i])
 else:body=source_line(connection,'if (this.tickCount++ % 20 == 0)')
 sources={str(path.relative_to(REPO)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [source,player,connection,coordinator,engine,Path(__file__).resolve()]}
 for name in sources:
  dest=s.output/'source-snapshot'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(REPO/name,dest)
 (s.output/'inputs.json').write_text(json.dumps({'phase':a.phase,'overflow':a.overflow,'sources':sources,'actualLaunch':s.install['launch']},indent=2)+'\n')
 result={'status':'running','phase':a.phase,'overflow':a.overflow};debug=None
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(1)
  for x in [24,120]:s.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('tp WorkerA 24.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');time.sleep(3)
  before=s.snapshot('before');target=next(p for p in before['players'] if p['name']=='WorkerA');assert before['phases']['phases'][a.phase]['worker']>=10
  events=s.output/'jdi.ndjson';signal=s.output/'packets-submitted';n=4200 if a.overflow else 256
  argv=[JAVA,'--add-modules','jdk.jdi',str(source),str(port),a.phase,str(body),str(source_line(coordinator,'while (this.recent.size() > HISTORY_LIMIT)')),str(source_line(engine,'this.playerTicks.close();')),target['uuid'],str(events),str(signal),str(min(n,4096))]
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger-command'),'--']+argv)
  def observed():return read_complete_events(events)
  deadline=time.monotonic()+60
  while not any(x['event']=='original_body_effect' for x in observed()):
   if debug.poll() is not None:raise AssertionError('Debugger exited before original body')
   if time.monotonic()>deadline:raise AssertionError('No real worker body effect')
   time.sleep(.05)
  s.request('mark',label='frozen_before')
  s.request('batch',actions=[{'name':'WorkerA','packet':'ping_request','data':{'id':100000+i}} for i in range(n)])
  time.sleep(.4);s.request('mark',label='still_frozen');signal.write_text('normal packets submitted\n')
  deadline=time.monotonic()+60
  while not any(x['event']=='joined' for x in observed()):
   if debug.poll() is not None:raise AssertionError('Debugger exited before join')
   if time.monotonic()>deadline:raise AssertionError('No real join')
   time.sleep(.05)
  time.sleep(1);s.request('mark',label='after_join');s.snapshot('after')
  s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();debug.wait(timeout=15);assert debug.returncode==0
  result.update(status='passed',events=observed(),sent=n)
 except BaseException as error:result.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 assert result['serverExits']==[0] and result['clientsExit']==0

if __name__=='__main__':main()
