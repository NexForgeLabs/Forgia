#!/usr/bin/env python3
"""Create a real map through normal use, move it by normal inventory clicks, then observe full worker map ticks."""
import argparse,hashlib,json,re,shutil,socket,subprocess,time
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
TOOLS=Path(__file__).resolve().parent;REPO=TOOLS.parents[1];JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--production-source-root',type=Path);a=p.parse_args();root=a.server.resolve();s=Session(root,'worker-backpack-map');r={'status':'running'};debug=None
 assert not list((root/'mods').glob('*.jar'))
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 r.update(installation=s.install,actualLaunch=s.install['launch'],sources={},productionSourceRoot=str(a.production_source_root.resolve()) if a.production_source_root else None)
 files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerBackpackMap.java',TOOLS/'proof_events.py',TOOLS/'install.py']+list((REPO/'src/main/java/net/neoforged/neoforge/server/threading/region').glob('RegionPlayer*.java'))
 files+=[REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java',REPO/'patches/net/minecraft/world/level/saveddata/maps/MapItemSavedData.java.patch',REPO/'patches/net/minecraft/server/level/ServerLevel.java.patch']
 for f in files:
  relative=f.relative_to(REPO)
  if a.production_source_root and str(relative).startswith(('src/','patches/')):
   frozen=a.production_source_root.resolve()/relative
   if frozen.is_file():f=frozen
   else:
    original=subprocess.run(['git','show','HEAD:'+str(relative)],cwd=REPO,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if original.returncode:continue
    assert original.stdout==f.read_bytes(),'Unfrozen changed production source '+str(relative)
  dest=s.output/'source-snapshot'/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,dest);r['sources'][str(relative)]=hashlib.sha256(f.read_bytes()).hexdigest()
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [48,120]:s.command(f'fill {x-10} 99 16 {x+10} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
  s.command('tp WorkerA 47.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');time.sleep(2)
  s.request('packet',name='WorkerA',packet='held_item_slot',data={'slotId':0});s.command('item replace entity WorkerA hotbar.0 with minecraft:map')
  s.request('mark',label='map_create_before');s.request('packet',name='WorkerA',packet='use_item',data={'hand':0,'sequence':1,'rotation':{'x':0,'y':0}});time.sleep(.5)
  client=s.request('snapshot')['WorkerA'];window=client['window'];item=window['items'][36];assert item['itemCount']==1
  s.request('packet',name='WorkerA',packet='window_click',data={'windowId':0,'stateId':window['stateId'],'slot':36,'mouseButton':0,'mode':0,'changedSlots':[{'location':36,'item':{'itemCount':0}}],'cursorItem':item});time.sleep(.2)
  s.request('packet',name='WorkerA',packet='window_click',data={'windowId':0,'stateId':window['stateId'],'slot':9,'mouseButton':0,'mode':0,'changedSlots':[{'location':9,'item':item}],'cursorItem':{'itemCount':0}});time.sleep(2)
  r['createdItem']=item;r['inventoryBeforeRead']=s.command('data get entity WorkerA Inventory');r['selectedRead']=s.command('data get entity WorkerA SelectedItemSlot');r['xpBeforeRead']=s.command('data get entity WorkerA XpTotal')
  s.request('mark',label='map_create_after');s.command('save-all flush')
  # Preserve immutable baseline bytes from this isolated world; subsequent save mutates its live file.
  maps=list((root/'world/data').glob('map_*.dat'));assert len(maps)==1
  r['mapFile']=maps[0].name;shutil.copy2(maps[0],s.output/'map-before.dat');r['mapBeforeSha256']=hashlib.sha256((s.output/'map-before.dat').read_bytes()).hexdigest()
  before=s.snapshot('before');actor=next(x['uuid'] for x in before['players'] if x['name']=='WorkerA');r.update(before=before,actor=actor)
  trace=s.output/'jdi.ndjson';done=s.output/'observer-done'
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerBackpackMap.java'),str(port),str(trace),actor,str(done)])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if trace.exists() and any(x['event']=='armed' for x in read_complete_events(trace)):break
   if debug.poll() is not None:raise AssertionError('Original map observer exited before arm')
   time.sleep(.1)
  else:raise TimeoutError('Original map observer not armed')
  s.request('mark',label='map_before');time.sleep(1)
  s.request('packet',name='WorkerA',packet='position_look',data={'x':47.5,'y':100,'z':24.5,'yaw':90,'pitch':0,'onGround':True});time.sleep(2)
  r['after']=s.snapshot('after');r['inventoryAfterRead']=s.command('data get entity WorkerA Inventory');r['xpAfterRead']=s.command('data get entity WorkerA XpTotal')
  s.request('mark',label='map_after');done.write_text('ordinary client rotation completed\n');debug.wait(timeout=20);assert debug.returncode==0;debug=None
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop()
  shutil.copy2(maps[0],s.output/'map-after.dat');r['mapAfterSha256']=hashlib.sha256((s.output/'map-after.dat').read_bytes()).hexdigest();r['status']='passed'
 except BaseException as error:r.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0
if __name__=='__main__':main()
