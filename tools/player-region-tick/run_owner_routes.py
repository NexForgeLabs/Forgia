#!/usr/bin/env python3
"""Original bounded movement/confirmation/inventory handlers from ordinary TCP packets."""
import argparse,hashlib,json,shutil,socket,subprocess,time,re
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
TOOLS=Path(__file__).resolve().parent;REPO=TOOLS.parents[1];JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args();s=Session(a.server.resolve(),'owner-routes');r=dict(status='running',sources={},windows=[]);debug=None
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}');r.update(installation=s.install,actualLaunch=s.install['launch'])
 try:
  files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerOwnerRoutes.java']+list((REPO/'src/main/java/net/neoforged/neoforge/server/threading/region').glob('RegionPlayer*.java'))+[REPO/'patches/net/minecraft/server/level/ChunkMap.java.patch']
  for f in files:
   relative=f.relative_to(REPO);q=s.output/'source-snapshot'/relative;q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,q);r['sources'][str(relative)]=hashlib.sha256(f.read_bytes()).hexdigest()
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [48,120]:s.command(f'fill {x-10} 99 16 {x+10} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points')
  s.command('tp WorkerA 47.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');s.command('item replace entity WorkerA inventory.0 with minecraft:diamond 5');time.sleep(3)
  r['before']=s.snapshot('before');r['actor']=next(x['uuid'] for x in r['before']['players'] if x['name']=='WorkerA');r['inventoryBefore']=s.command('data get entity WorkerA Inventory');r['walkBefore']=s.command('data get entity WorkerA')
  trace=s.output/'jdi.ndjson';debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerOwnerRoutes.java'),str(port),str(trace),r['actor'],str(s.output/'done')])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if any(x['event']=='armed' for x in read_complete_events(trace)):break
   if debug.poll() is not None:raise AssertionError('Owner route observer exited before arm')
   time.sleep(.1)
  else:raise TimeoutError('Owner route observer not armed')
  for i,x in enumerate([47.75,48.0,48.25]):
   label='move-'+str(i);s.request('mark',label=label+'_before');s.request('packet',name='WorkerA',packet='position_look',data=dict(x=x,y=100,z=24.5,yaw=0,pitch=0,onGround=True));time.sleep(.3);r['windows'].append(dict(label=label,target=x,after=s.snapshot(label+'-after')));s.request('mark',label=label+'_after')
  s.request('mark',label='confirm_before');s.command('tp WorkerA 49.5 100 24.5');time.sleep(1);r['confirmed']=s.snapshot('confirmed');s.request('mark',label='confirm_after')
  client=s.request('snapshot')['WorkerA'];window=client['window'];item=window['items'][9];assert item['itemCount']==5;state=window['stateId']
  s.request('mark',label='click_before');s.request('packet',name='WorkerA',packet='window_click',data=dict(windowId=0,stateId=state,slot=9,mouseButton=0,mode=0,changedSlots=[dict(location=9,item=dict(itemCount=0))],cursorItem=item));time.sleep(.3)
  # The first pickup is bounded owner work. A nonempty cursor then uses the complete serial fence.
  s.request('packet',name='WorkerA',packet='window_click',data=dict(windowId=0,stateId=state-1,slot=10,mouseButton=0,mode=0,changedSlots=[dict(location=10,item=item)],cursorItem=dict(itemCount=0)));time.sleep(.5);s.request('mark',label='click_after')
  r['after']=s.snapshot('after');r['inventoryAfter']=s.command('data get entity WorkerA Inventory');r['xpAfter']=s.command('data get entity WorkerA XpTotal')
  s.request('packet',name='WorkerA',packet='client_command',data=dict(actionId='request_stats'));time.sleep(.2);(s.output/'done').write_text('normal packet sequence finished\n');debug.wait(timeout=15);assert debug.returncode==0,'Owner route observer failed';debug=None
  # A wall inside the original swept body must use the complete original correction fence.
  s.command('setblock 50 100 24 minecraft:stone');s.command('setblock 50 101 24 minecraft:stone')
  old=max([row['seq'] for row in read_complete_events(s.output/'packets.ndjson') if row['bot']=='WorkerA' and row['direction']=='send' and row['name']=='keep_alive'],default=0)
  until=time.monotonic()+25;prepared=None
  while time.monotonic()<until:
   replies=[row for row in read_complete_events(s.output/'packets.ndjson') if row['bot']=='WorkerA' and row['direction']=='send' and row['name']=='keep_alive' and row['seq']>old]
   if replies:prepared=replies[-1];break
   time.sleep(.25)
  else:raise TimeoutError('Wall fixture did not observe original keepalive reply')
  time.sleep(.3);walltrace=s.output/'jdi-wall.ndjson'
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger-wall'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerOwnerRoutes.java'),str(port),str(walltrace),r['actor'],str(s.output/'wall-done')])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if any(row['event']=='armed' for row in read_complete_events(walltrace)):break
   if debug.poll() is not None:raise AssertionError('Wall observer exited before arm')
   time.sleep(.1)
  else:raise TimeoutError('Wall observer did not arm')
  s.request('mark',label='wall_before');s.request('packet',name='WorkerA',packet='position_look',data=dict(x=49.75,y=100,z=24.5,yaw=0,pitch=0,onGround=True));time.sleep(1)
  r['wall']=dict(prepared=prepared,after=s.snapshot('wall-after'),positionRead=s.command('data get entity WorkerA Pos'),client=s.request('snapshot')['WorkerA'])
  s.request('mark',label='wall_after');(s.output/'wall-done').write_text('original normal packet wall correction completed\n');debug.wait(timeout=18);assert debug.returncode==0;debug=None
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();r['status']='passed'
 except BaseException as error:r.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0
if __name__=='__main__':main()
