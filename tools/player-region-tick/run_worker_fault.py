#!/usr/bin/env python3
"""External JDI fault at a real original worker-body effect; no oracle Mod or runtime admission exception."""
import argparse,hashlib,json,re,socket,subprocess,time,zipfile,shutil
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events

TOOLS=Path(__file__).resolve().parent
JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'
REPO=TOOLS.parents[1]


def source_line(path,needle):
 rows=path.read_text().splitlines();found=[i for i,line in enumerate(rows,1) if needle in line]
 assert len(found)==1,(str(path),needle,found)
 return found[0]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--phase',choices=['ENTITY','CONNECTION'],required=True);p.add_argument('--same-group', action='store_true');a=p.parse_args();root=a.server.resolve()
 assert not list((root/'mods').glob('*.jar'))
 s=Session(root,'worker-fault');assert s.install['oracleSha256'] is None and s.install['playerPlanningRequested']
 original_install=json.loads((root/'installation.json').read_text())
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 assert port not in [s.install['port'],s.install['rconPort']]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 source=TOOLS/'jdi/PlayerTickFault.java'
 player=REPO/'projects/neoforge/src/main/java/net/minecraft/server/level/ServerPlayer.java'
 connection=REPO/'projects/neoforge/src/main/java/net/minecraft/network/Connection.java'
 coordinator=REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerTickCoordinator.java'
 engine=REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java'
 if a.phase=='ENTITY':
  rows=player.read_text().splitlines();start=next(i for i,line in enumerate(rows) if 'public void tick()' in line)
  body=next(i+1 for i in range(start,len(rows)) if 'this.containerMenu.broadcastChanges();' in rows[i])
 else:body=source_line(connection,'if (this.tickCount++ % 20 == 0)')
 join=source_line(coordinator,'while (this.recent.size() > HISTORY_LIMIT)')
 close=source_line(engine,'this.playerTicks.close();')
 sources={str(path.relative_to(REPO)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [source,player,connection,coordinator,engine,Path(__file__).resolve(),TOOLS/'proof_events.py']}
 for name in sources:
  dest=s.output/'source-snapshot'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(REPO/name,dest)
 (s.output/'inputs.json').write_text(json.dumps({'phase':a.phase,'originalInstallation':original_install,'actualLaunch':s.install['launch'],'sources':sources,'lineNumbers':{'body':body,'join':join,'close':close},'sameGroup':a.same_group},indent=2)+'\n')
 result={'status':'running','phase':a.phase,'driverSources':s.sources,'scope':'external JDI original complete player worker-body fault; original core/no Mod/no transformed method/no runtime admission bypass'}
 debug=None
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0']:
   s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in ([24,32] if a.same_group else [24,120]):s.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
  s.command('tp WorkerA 24.5 100 24.5');s.command('tp WorkerB '+('32.5' if a.same_group else '120.5')+' 100 24.5');time.sleep(3)
  before=s.snapshot('before-fault');target=next(x for x in before['players'] if x['name']=='WorkerA')
  assert before['phases']['phases'][a.phase]['worker']>=10 and all(v['failed']==0 for v in before['phases']['phases'].values()),before
  events=s.output/'jdi.ndjson'
  argv=[JAVA,'--add-modules','jdk.jdi',str(source),str(port),a.phase,str(body),str(join),str(close),target['uuid'],str(events)]
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger-command'),'--']+argv)
  deadline=time.monotonic()+90
  while time.monotonic()<deadline:
   observed=read_complete_events(events)
   if any(r['event']=='joined' for r in observed):break
   if debug.poll() is not None:raise AssertionError('Debugger exited before original worker join')
   time.sleep(.1)
  else:raise AssertionError('No independently observed original worker failure/join')
  if a.phase=='ENTITY':s.process.wait(timeout=45)
  else:
   deadline=time.monotonic()+15
   while time.monotonic()<deadline:
    state=json.loads(s.command('neoforge regionthreading status players'))
    if all(x['name']!='WorkerA' for x in state['players']):break
    time.sleep(.1)
   else:raise AssertionError('Original connection failure did not disconnect the real TCP client')
   s.snapshot('after-disconnect')
   s.request('close');s.bots.wait(timeout=10)
  s.stop();debug.wait(timeout=20)
  assert debug.returncode==0,'Original worker debugger failed'
  observed=[json.loads(l) for l in events.read_text().splitlines()]
  assert [r['event'] for r in observed]==['armed','original_entry','original_body_effect','injected','joined','shutdown_after_gate_close'],observed
  joined=json.loads(observed[4]['diagnostics']);closed=json.loads(observed[5]['diagnostics'])
  failed=[r for r in joined['phases']['recent'] if r.get('failureClass')]
  assert len(failed)==1 and failed[0]['worker'] and failed[0]['phase']==a.phase and failed[0]['state']=='JOINED' and failed[0]['tick']==observed[3]['tick']
  assert closed['activeBatches']==closed['stateClaims']==closed['phases']['owners']==closed['phases']['pending']==closed['phases']['running']==0
  assert closed['network']['closed'] and closed['network']['pending']==closed['network']['readers']==closed['network']['terminals']==0 and not closed['network']['frozen']
  assert all(v['failed']==(1 if k==a.phase else 0) for k,v in closed['phases']['phases'].items())
  log=(s.output/'boot-1.server.log').read_text();assert 'player_worker_fault_after_original_body_'+a.phase in log
  assert ('Ticking entity' in log) if a.phase=='ENTITY' else ('Failed to handle packet' in log)
  assert hashlib.sha256(a.installer.read_bytes()).hexdigest()==original_install['installerSha256']
  relative=f'net/neoforged/neoforge/{original_install["version"]}/neoforge-{original_install["version"]}-universal.jar'
  with zipfile.ZipFile(a.installer) as z:assert (root/'libraries'/relative).read_bytes()==z.read('maven/'+relative)
  result.update(status='passed_expected_failure',independentEvents=observed,joined=joined,closed=closed,failedInvocation=failed[0])
 except BaseException as error:result.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:
   debug.terminate()
   try:debug.wait(timeout=5)
   except subprocess.TimeoutExpired:debug.kill();debug.wait()
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode)
  (s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'status':result['status'],'phase':a.phase,'failedInvocation':result['failedInvocation'],'serverExits':result['serverExits']}))

if __name__=='__main__':main()
