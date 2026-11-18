#!/usr/bin/env python3
"""First bounded P4 real TCP packet/serial-fence sequence, never a synthetic handler substitute."""
import argparse,hashlib,json,shutil,socket,subprocess,time
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
TOOLS=Path(__file__).resolve().parent;REPO=TOOLS.parents[1];JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args();s=Session(a.server.resolve(),'owner-packet');r=dict(status='running',sources={});debug=None
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}');r.update(installation=s.install,actualLaunch=s.install['launch'])
 try:
  files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerOwnerPacket.java',TOOLS/'verify_owner_packet.py',TOOLS/'decode_owner_packet.cjs',REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerPacketRouter.java',REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java',REPO/'patches/net/minecraft/network/protocol/PacketUtils.java.patch',REPO/'patches/net/minecraft/network/Connection.java.patch']
  for f in files:
   relative=f.relative_to(REPO);target=s.output/'source-snapshot'/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target);r['sources'][str(relative)]=hashlib.sha256(f.read_bytes()).hexdigest()
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [48,120]:s.command(f'fill {x-10} 99 16 {x+10} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
  s.command('tp WorkerA 47.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');s.command('item replace entity WorkerA hotbar.3 with minecraft:diamond 1');time.sleep(3)
  r['before']=s.snapshot('before');r['inventoryBefore']=s.command('data get entity WorkerA Inventory');r['selectedBefore']=s.command('data get entity WorkerA SelectedItemSlot');r['actor']=next(x['uuid'] for x in r['before']['players'] if x['name']=='WorkerA')
  trace=s.output/'jdi.ndjson';debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerOwnerPacket.java'),str(port),str(trace),r['actor']])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if any(x['event']=='armed' for x in read_complete_events(trace)):break
   if debug.poll() is not None:raise AssertionError('Original packet observer exited before arm')
   time.sleep(.1)
  else:raise TimeoutError('Original packet observer did not arm')
  s.request('mark',label='packet_before');slots=[1,2,9,3,0,1];actions=[]
  for slot in slots:actions.extend([dict(name='WorkerA',packet='held_item_slot',data=dict(slotId=slot)),dict(name='WorkerA',packet='client_command',data=dict(actionId='request_stats'))])
  s.request('batch',actions=actions[:-2])
  until=time.monotonic()+20
  while time.monotonic()<until:
   if sum(x['event']=='return' for x in read_complete_events(trace))==10:break
   if debug.poll() is not None:raise AssertionError('Packet observer exited before serial fences')
   time.sleep(.05)
  else:raise TimeoutError('Original serial packet prefix incomplete')
  # Let vanilla clear the transient occupied-hand equipment history after normal deselection.
  time.sleep(.6);s.request('batch',actions=actions[-2:]);debug.wait(timeout=50);assert debug.returncode==0,'Original packet observer incomplete';debug=None;time.sleep(.3);s.request('mark',label='packet_after')
  r['after']=s.snapshot('after');r['inventoryAfter']=s.command('data get entity WorkerA Inventory');r['selectedAfter']=s.command('data get entity WorkerA SelectedItemSlot');r['xpAfter']=s.command('data get entity WorkerA XpTotal')
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();r['status']='passed'
 except BaseException as error:r.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0
if __name__=='__main__':main()
