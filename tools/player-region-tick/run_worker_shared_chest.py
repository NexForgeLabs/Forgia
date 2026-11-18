#!/usr/bin/env python3
"""Shared chest: original client open/click fences plus original worker menu tick and synchronization."""
import argparse,hashlib,json,shutil,socket,subprocess,time
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
from verify_resources import nbt_items
TOOLS=Path(__file__).resolve().parent;REPO=TOOLS.parents[1];JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--production-source-root',type=Path);a=p.parse_args();root=a.server.resolve();s=Session(root,'worker-shared-chest');r={'status':'running'};debug=None
 assert not list((root/'mods').glob('*.jar'))
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 r.update(installation=s.install,actualLaunch=s.install['launch'],sources={},productionSourceRoot=str(a.production_source_root.resolve()) if a.production_source_root else None)
 files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerSharedChest.java',TOOLS/'proof_events.py',TOOLS/'install.py']
 files+=list((REPO/'src/main/java/net/neoforged/neoforge/server/threading/region').glob('RegionPlayer*.java'))
 files+=[REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java']
 files+=[REPO/'patches'/name for name in ['net/minecraft/server/level/ServerPlayer.java.patch','net/minecraft/world/entity/player/Inventory.java.patch','net/minecraft/world/inventory/AbstractContainerMenu.java.patch','net/minecraft/world/level/block/entity/ChestBlockEntity.java.patch']]
 for f in files:
  relative=f.relative_to(REPO)
  if a.production_source_root and str(relative).startswith(('src/','patches/')):
   frozen=a.production_source_root.resolve()/relative
   if frozen.is_file():f=frozen
   else:
    original=subprocess.run(['git','show','HEAD:'+str(relative)],cwd=REPO,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if original.returncode:continue  # A new file belonging to a later candidate, absent from this frozen product.
    assert original.stdout==f.read_bytes(),'Unfrozen changed production source '+str(relative)
  dest=s.output/'source-snapshot'/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,dest);r['sources'][str(relative)]=hashlib.sha256(f.read_bytes()).hexdigest()
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 95 47');time.sleep(2);s.command('fill 38 99 14 60 99 34 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
  s.command('tp WorkerA 47.5 100 22.5');s.command('tp WorkerB 49.5 100 26.5');time.sleep(3)
  s.command('setblock 48 100 24 minecraft:chest[type=single,facing=north]');s.command('item replace block 48 100 24 container.0 with minecraft:diamond 11')
  # Observe a real automatic keepalive exchange for both clients before the strict positive window.
  # The original 15-second challenge otherwise legitimately closes owner admission during JDI setup.
  until=time.monotonic()+25;primed={}
  while time.monotonic()<until:
   for row in read_complete_events(s.output/'packets.ndjson'):
    if row['direction']=='send' and row['name']=='keep_alive':primed[row['bot']]=row
   if set(primed)=={'WorkerA','WorkerB'}:break
   time.sleep(.25)
  else:raise TimeoutError('Original automatic keepalive preparation did not complete')
  r['keepalivePreparation']=primed;time.sleep(.5)
  fixture=s.command('data get block 48 100 24');before=s.snapshot('before');actors={x['name']:x['uuid'] for x in before['players']};r.update(fixtureRead=fixture,before=before,actors=actors)
  # Prepare original ChestMenu before attaching, using a real client open and close outside measured window.
  def use(name,seq):s.request('packet',name=name,packet='block_place',data={'hand':0,'location':{'x':48,'y':100,'z':24},'direction':1,'cursorX':.5,'cursorY':1.0,'cursorZ':.5,'insideBlock':False,'sequence':seq})
  use('WorkerA',1);time.sleep(.5);client=s.request('snapshot')['WorkerA'];assert client['windowId']>0
  s.request('packet',name='WorkerA',packet='close_window',data={'windowId':client['windowId']});time.sleep(.5)
  trace=s.output/'jdi.ndjson';done=s.output/'observer-done'
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerSharedChest.java'),str(port),str(trace),actors['WorkerA'],actors['WorkerB'],str(done)])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if trace.exists() and any(x['event']=='armed' for x in read_complete_events(trace)):break
   if debug.poll() is not None:raise AssertionError('Original menu observer exited before arm')
   time.sleep(.1)
  else:raise TimeoutError('Original menu observer not armed')
  s.request('mark',label='shared_open_before');use('WorkerA',2);use('WorkerB',3);time.sleep(2)
  opened=s.snapshot('opened');clientsOpen=s.request('snapshot');r.update(opened=opened,clientsOpen=clientsOpen)
  assert all(clientsOpen[name]['windowId']>0 for name in actors)
  s.request('mark',label='shared_open_after')
  s.request('mark',label='shared_click_before');s.request('quick_move',names=['WorkerB']);time.sleep(2)
  # A's original menu notices B's original synchronous click result at the next full worker tick.
  r['afterClick']=s.snapshot('after-click');r['clientsAfterClick']=s.request('snapshot')
  r['containerAfterRead']=s.command('data get block 48 100 24 Items')
  r['inventoryReads']={name:s.command('data get entity '+name+' Inventory') for name in actors}
  r['xpReads']={name:s.command('data get entity '+name+' XpTotal') for name in actors}
  assert nbt_items(r['containerAfterRead'])=={} and sum(nbt_items(v).get('minecraft:diamond',0) for v in r['inventoryReads'].values())==11
  s.request('mark',label='shared_click_after')
  s.request('mark',label='shared_close_before')
  for name in actors:s.request('packet',name=name,packet='close_window',data={'windowId':clientsOpen[name]['windowId']})
  time.sleep(.5);s.snapshot('closed');s.request('mark',label='shared_close_after');done.write_text('normal client close completed\n')
  debug.wait(timeout=20);assert debug.returncode==0,'Original shared menu observer failed';debug=None
  # Vanilla suppresses feedback already predicted by the clicking client. A normal reopen obtains
  # a fresh authoritative complete inventory, independently of that client's predicted slot list.
  s.request('mark',label='confirm_before');use('WorkerB',4);time.sleep(.5)
  r['clientsConfirmed']=s.request('snapshot');s.request('mark',label='confirm_after')
  s.request('packet',name='WorkerB',packet='close_window',data={'windowId':r['clientsConfirmed']['WorkerB']['windowId']});time.sleep(.3)
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();r['status']='passed'
 except BaseException as error:r.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0

if __name__=='__main__':main()
