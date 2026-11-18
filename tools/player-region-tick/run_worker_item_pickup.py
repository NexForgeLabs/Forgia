#!/usr/bin/env python3
"""Original bounded material pickup: ordinary TCP movement, native body/events and closed resources."""
import argparse,hashlib,json,re,shutil,socket,subprocess,time,uuid
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
from verify_worker_lifecycle import nbt_items

TOOLS=Path(__file__).resolve().parent
REPO=TOOLS.parents[1]
JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'

def identity(snbt):
 match=re.search(r'UUID: \[I; ([^\]]+)\]',snbt);words=[int(x.strip())&0xffffffff for x in match[1].split(',')]
 return str(uuid.UUID(int=sum(x<<(96-32*i) for i,x in enumerate(words))))

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args()
 root=a.server.resolve();assert not list((root/'mods').glob('*.jar'))
 s=Session(root,'worker-item-pickup');r={'status':'running','cases':[]};debug=None
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}')
 r.update(installation=s.install,actualLaunch=s.install['launch'],sources={})
 files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerItemPickup.java',TOOLS/'proof_events.py',TOOLS/'install.py']
 files+=list((REPO/'src/main/java/net/neoforged/neoforge/server/threading/region').glob('RegionPlayer*.java'))
 files+=[REPO/'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java']
 files+=[REPO/'patches'/name for name in ['net/minecraft/server/level/ChunkMap.java.patch','net/minecraft/server/level/ServerPlayer.java.patch','net/minecraft/world/entity/player/Inventory.java.patch','net/minecraft/world/inventory/AbstractContainerMenu.java.patch']]
 for file in files:
  relative=file.relative_to(REPO);dest=s.output/'source-snapshot'/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest);r['sources'][str(relative)]=hashlib.sha256(file.read_bytes()).hexdigest()
 try:
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [48,120]:s.command(f'fill {x-10} 99 16 {x+10} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('tick freeze')
  for index,label in enumerate(['isolated','foreign-watcher','capacity','component','equipment']):
   s.command('clear @a');s.command('tp WorkerA 45 100 24.5');s.command('tp WorkerB '+('53.5' if label=='foreign-watcher' else '120.5')+' 100 24.5')
   s.request('packet',name='WorkerA',packet='held_item_slot',data={'slotId':8})
   initialCount=64 if label=='capacity' else 1
   material='minecraft:diamond'+('[minecraft:custom_name=\'{"text":"bounded negative control"}\']' if label=='component' else '')
   s.command(f'item replace entity WorkerA '+('hotbar.8' if label=='equipment' else 'inventory.0')+f' with {material} {initialCount}')
   time.sleep(2)
   selected=s.command('data get entity WorkerA SelectedItemSlot');assert re.search(r': 8\s*$',selected)
   s.command(f'summon minecraft:item 48.5 100 24.5 {{Tags:["p3_item_{label}"],NoGravity:1b,Invulnerable:1b,Motion:[0.0d,0.0d,0.0d],PickupDelay:40s,Item:{{id:"minecraft:diamond",count:5}}}}')
   initial=s.command(f'data get entity @e[tag=p3_item_{label},limit=1]');target=identity(initial)
   before=s.snapshot(label+'-before');actor=next(x['uuid'] for x in before['players'] if x['name']=='WorkerA')
   inventoryBefore=s.command('data get entity WorkerA Inventory');assert nbt_items(inventoryBefore)=={'minecraft:diamond':initialCount}
   trace=s.output/('jdi-'+label+'.ndjson')
   argv=[JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerItemPickup.java'),str(port),actor,str(trace),target,str(initialCount+5)]
   debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/('debugger-'+label)),'--',*argv])
   until=time.monotonic()+25
   while time.monotonic()<until:
    if trace.exists() and any(x['event']=='armed' for x in read_complete_events(trace)):break
    if debug.poll() is not None:raise AssertionError('Original material observer exited before arm')
    time.sleep(.1)
   else:raise TimeoutError('Original material observer did not arm')
   s.request('mark',label=label+'_before')
   s.request('packet',name='WorkerA',packet='position_look',data={'x':47.5,'y':100,'z':24.5,'yaw':0,'pitch':0,'onGround':True})
   s.command('tick unfreeze')
   debug.wait(timeout=125);assert debug.returncode==0,'Original material conservation observer failed';debug=None
   s.command('tick freeze');time.sleep(.3)
   after=s.snapshot(label+'-after');inventoryAfter=s.command('data get entity WorkerA Inventory');remaining=s.command(f'execute if entity @e[tag=p3_item_{label}]');xp=s.command('data get entity WorkerA XpTotal')
   assert nbt_items(inventoryAfter)=={'minecraft:diamond':initialCount+5} and remaining.strip()=='Test failed' and re.search(r': 0\s*$',xp)
   s.request('packet',name='WorkerA',packet='client_command',data={'actionId':1});time.sleep(.3)
   s.request('mark',label=label+'_after')
   r['cases'].append(dict(label=label,initialCount=initialCount,pickedUp=5,actor=actor,target=target,selectedRead=selected,initialEntityRead=initial,inventoryBeforeRead=inventoryBefore,inventoryAfterRead=inventoryAfter,remainingRead=remaining,xpRead=xp,before=before,after=after))
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();r['status']='passed'
 except BaseException as error:r.update(status='failed',error=repr(error));raise
 finally:
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode)
  (s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0

if __name__=='__main__':main()
