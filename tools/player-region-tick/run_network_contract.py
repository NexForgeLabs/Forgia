#!/usr/bin/env python3
"""Original explicit NETWORK payload and enqueueWork contracts under unknown-Mod fallback."""
import argparse,hashlib,json,socket,subprocess,time,shutil
from pathlib import Path
from run_worker_lifecycle import Session
from run_worker_fault import TOOLS,REPO,JAVA
from proof_events import read_complete_events

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--mod',type=Path,required=True);p.add_argument('--reproduce-125',action='store_true');a=p.parse_args();root=a.server.resolve();shutil.copy2(a.mod,root/'mods'/a.mod.name);s=Session(root,'network-contract')
 result={'status':'running','installation':s.install,'modSha256':hashlib.sha256(a.mod.read_bytes()).hexdigest()};debug=None
 sources={}
 for file in [Path(__file__).resolve(),TOOLS/'lifecycle_clients.cjs',TOOLS/'network-contract/java/forgia/networkcontract/NetworkContract.java',REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java']:
  name=str(file.relative_to(REPO));dest=s.output/'source-snapshot'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest);sources[name]=hashlib.sha256(file.read_bytes()).hexdigest()
 result['sources']=sources
 if a.reproduce_125:
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
  s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 try:
  s.start()
  for rule in ['doMobSpawning false','randomTickSpeed 0','spawnChunkRadius 0']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(1)
  for x in [24,120]:s.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('tp WorkerA 24.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');time.sleep(2);s.snapshot('before')
  if a.reproduce_125:
   source=TOOLS/'jdi/PlayerAdmissionFence.java';engine=REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java';rows=engine.read_text().splitlines();start=next(i for i,x in enumerate(rows) if 'private int dispatchPlayerPrefix(' in x);line=next(i+1 for i in range(start,len(rows)) if 'frozen = true;' in rows[i]);events=s.output/'jdi.ndjson'
   debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger-command'),'--',JAVA,'--add-modules','jdk.jdi',str(source),str(port),str(line),str(events),str(s.output/'resume.signal')]);deadline=time.monotonic()+60
   while not any(r['event']=='unknown_preflight_frozen' for r in read_complete_events(events)):
    if debug.poll() is not None or time.monotonic()>deadline:raise AssertionError('Unknown preflight debugger did not pause')
    time.sleep(.05)
   s.request('payload',name='WorkerA',channel='forgia_network:probe',text='unknown-network-contract')
   time.sleep(.4);(s.output/'resume.signal').write_text('normal payload submitted\n');debug.wait(timeout=20);assert debug.returncode==0
  else:
   for i in range(500):
    s.request('payload',name='WorkerA',channel='forgia_network:probe',text='network_'+str(i))
    if i%25==0:time.sleep(.02)
  time.sleep(2);result['counts']=json.loads(s.command('network_contract_counts'));state=s.snapshot('after');
  assert all(v['worker']==0 for v in state['phases']['phases'].values())
  if not a.reproduce_125:assert state['network']['deferred']==0, state['network']
  s.request('mark',label='payloads_received');s.request('close');s.bots.wait(timeout=10);time.sleep(.3);s.snapshot('final');s.stop()
  expected=1 if a.reproduce_125 else 500
  assert result['counts']['total']==result['counts']['main']==expected,result['counts']
  if a.reproduce_125:
   assert result['counts']['onServer']==1,result['counts'];result['status']='reproduced_original_network_contract_violation'
  else:
   assert result['counts']['onServer']==0 and result['counts']['onNetwork']==expected and result['counts']['wrongNetworkLoop']==result['counts']['wrongOrder']==0,result['counts'];result['status']='passed'
 except BaseException as error:result.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();result.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result['counts']))

if __name__=='__main__':main()
