#!/usr/bin/env python3
"""Independently verify original material pickup/events, packet feedback and item/XP conservation."""
import argparse,base64,hashlib,json,re,subprocess,uuid,zipfile
from pathlib import Path
from verify_resources import nbt_items
TOOLS=Path(__file__).resolve().parent

def require(ok,message):
 if not ok:raise AssertionError(message)

def events(path):
 data=path.read_bytes();require(data and data.endswith(b"\n"),"uncommitted closed item evidence "+str(path))
 return [json.loads(line) for line in data.splitlines()]

def identity(text):
 match=re.search(r'UUID: \[I; ([^\]]+)\]',text);require(match is not None,'missing independent item UUID')
 words=[int(x.strip())&0xffffffff for x in match[1].split(',')];require(len(words)==4,'invalid item UUID')
 return str(uuid.UUID(int=sum(x<<(96-32*i) for i,x in enumerate(words))))

def verify(root,installer):
 out=root/'worker-item-pickup';load=lambda p:json.loads(Path(p).read_text());install=load(root/'installation.json');result=load(out/'result.json')
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'item runner or process failure')
 require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'unsafe installer input or observer Mod')
 require(result['installation']==dict(install,launch=result['actualLaunch']),'item installation/launch mismatch')
 require(hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'item installer changed')
 relative=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as archive:require((root/'libraries'/relative).read_bytes()==archive.read('maven/'+relative),'item installed core differs')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'item source snapshot changed '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in result['actualLaunch']) and not any(x.startswith('-javaagent:') for x in result['actualLaunch']),'item observer not original read-only external debugger')
 commands=events(out/'commands.ndjson');packets=events(out/'packets.ndjson')
 states=[json.loads(c['response']) for c in commands if c['command']=='neoforge regionthreading status players']
 require([r['seq'] for r in packets]==list(range(1,len(packets)+1)),'item frame trace truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   value=base64.b64decode(row['data']['base64'],validate=True)
   require(len(value)==row['data']['bytes'] and hashlib.sha256(value).hexdigest()==row['data']['sha256'],'item raw frame checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_item_pickup_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 marks={r['name']:r['seq'] for r in packets if r['direction']=='control'}
 require([c['label'] for c in result['cases']]==['isolated','foreign-watcher','capacity','component','equipment'],'missing or reordered item cases')
 def client_inventory(end):
  slots={}
  for row in raw:
   if row['seq']>=end:break
   if row['bot']!='WorkerA' or row['direction']!='frame':continue
   data=row['data']
   if row['name']=='window_items' and data['windowId']==0:slots=dict(enumerate(data['items']))
   elif row['name']=='set_slot' and data['windowId']==0:slots[data['slot']]=data['item']
   elif row['name']=='set_slot' and data['windowId']==-2:
    slot=data['slot'];slots[slot+36 if slot<9 else slot]=data['item']
  return sum(stack.get('itemCount',0) for slot,stack in slots.items() if 9<=slot<=44 and stack.get('itemId')==805)
 accepted=[]
 for index,case in enumerate(result['cases']):
  label=case['label'];initial=case['initialCount'];total=initial+5;actor=case['actor'];target=case['target']
  require(case['pickedUp']==5 and identity(case['initialEntityRead'])==target,'wrong original item source')
  position=re.search(r'Pos: \[([^\]]+)\]',case['initialEntityRead']);require(position is not None and [float(x.strip().removesuffix('d')) for x in position[1].split(',')]==[48.5,100,24.5],'item source not in adjacent region')
  require(re.search(r'Item: \{count: 5, id: "minecraft:diamond"\}',case['initialEntityRead']) is not None,'wrong actual initial ground stack')
  for name in ['selectedRead','initialEntityRead','inventoryBeforeRead','inventoryAfterRead','remainingRead','xpRead']:require(any(c['response']==case[name] for c in commands),'item independent read missing '+name)
  require(re.search(r': 8\s*$',case['selectedRead']) and nbt_items(case['inventoryBeforeRead'])=={'minecraft:diamond':initial} and nbt_items(case['inventoryAfterRead'])=={'minecraft:diamond':total},'item server inventory not conserved')
  require(case['remainingRead'].strip()=='Test failed' and re.search(r': 0\s*$',case['xpRead']),'item duplicated on ground or changed XP')
  for state in [case['before'],case['after']]:
   require(state in states and state['persistent']==install['persistent'] and state['requested']==install['playerPlanningRequested'],'invented item state/mode')
   require(state['activeBatches']==state['stateClaims']==state['phases']['pending']==state['phases']['running']==0,'item work still active at quiescent read')
   require(all(x['ownerCurrent'] for x in state['players']) and all(x['failed']==0 for x in state['phases']['phases'].values()),'item stale owner/original body failure')
  player=next(x for x in case['after']['players'] if x['uuid']==actor)
  require(player['name']=='WorkerA' and [player[k] for k in ['x','y','z','regionX','regionZ']]==[47.5,100,24.5,0,0],'item pickup actor not in adjacent owner')
  rows=events(out/('jdi-'+label+'.ndjson'));require(rows[0]['event']=='armed' and rows[-1]['event']=='observed' and [x['seq'] for x in rows]==list(range(1,len(rows)+1)),'item original observer incomplete')
  require(rows[0]['actor']==actor and rows[0]['target']==target and rows[-1]['inventory']==total and rows[-1]['remaining']==0,'item observer belongs to another fixture')
  calls=[];current=None;storage=[]
  for row in rows[1:-1]:
   require(row['entity']==target and (row['thread']=='Server thread' or 'Worker' in row['thread']),'item unknown original identity/thread')
   kind=row['event']
   if kind=='enter':
    require(current is None,'overlapping original item call');current={'enter':row,'pre':[],'post':[]}
    require(row['actor']==actor and row['count']==5 and row['inventory']==initial and row['xp']==0,'item original starting resources changed')
    require(all(any(name in x for x in row['stack']) for name in ['ItemEntity.playerTouch:','ServerPlayer.doTick:','Connection.tick:']),'item escaped complete original body')
    if 'Worker' in row['thread']:require(any('RegionPlayerTickDispatch.runOriginal:' in x for x in row['stack']),'item side task masquerades as original worker')
   elif kind in ['pickup-pre','pickup-post']:
    require(current is not None and current['enter']['thread']==row['thread'],'item event escaped original call')
    current['pre' if kind=='pickup-pre' else 'post'].append(row)
   elif kind=='return':
    require(current is not None and current['enter']['thread']==row['thread'] and row['actor']==actor,'item original return mismatch')
    require(row['countBefore']==5 and row['inventoryBefore']==initial and row['xp']==0 and row['stackCountAfter']==5,'item original resource state changed')
    delta=row['inventoryAfter']-initial;require(delta in [0,5] and row['remaining']+row['inventoryAfter']==total and row['removed']==(delta==5),'item original call lost or duplicated resources')
    require(len(current['pre'])==1 and current['pre'][0]['result']=='DEFAULT' and current['pre'][0]['inventory']==initial,'item original Pre event missing/duplicated/result changed')
    require(len(current['post'])==(1 if delta else 0),'item original Post event missing/duplicated')
    if delta:require(current['post'][0]['originalCount']==5 and current['post'][0]['remaining']==0 and current['post'][0]['inventory']==total,'item original Post state not synchronous')
    current['return']=row;calls.append(current);current=None
   elif kind.startswith('storage-'):storage.append(row)
   else:raise AssertionError('unknown item observer row')
  require(current is None and calls,'item original invocation incomplete')
  effects=[c for c in calls if c['return']['removed']];require(len(effects)==1,'item pickup missing/duplicated')
  effect=effects[0];worker='Worker' in effect['enter']['thread']
  require(worker==(install['playerPlanningRequested'] and label=='isolated'),'unexpected item worker coverage')
  require([x['event'] for x in storage]==['storage-enter','storage-return'] and all(x['thread']=='Server thread' for x in storage) and storage[-1]['levelCallbackClass']=='net.minecraft.world.level.entity.EntityInLevelCallback$1','item original storage callback/thread/cleanup mismatch')
  if worker:require(effect['return']['seq']<storage[0]['seq'] and any('RegionThreadingEngine.dispatchPlayerPrefix:' in x for x in storage[0]['stack']),'item storage escaped physical join')
  begin,end=marks[label+'_before'],marks[label+'_after'];window=[r for r in raw if begin<r['seq']<end]
  moves=[r for r in window if r['bot']=='WorkerA' and r['direction']=='frame_send' and r['name']=='position_look']
  require(len(moves)==1 and [moves[0]['data'][k] for k in ['x','y','z']]==[47.5,100,24.5],'item gameplay not normal client movement')
  collect=[r for r in window if r['bot']=='WorkerA' and r['direction']=='frame' and r['name']=='collect'];require(len(collect)==1 and collect[0]['data']['pickupItemCount']==5,'item client collect missing/duplicated/count changed')
  entity=collect[0]['data']['collectedEntityId'];spawns=[r for r in raw if r['bot']=='WorkerA' and r['direction']=='frame' and r['name']=='spawn_entity' and r['data']['entityId']==entity]
  require(len(spawns)==1 and spawns[0]['data']['objectUUID']==target and [spawns[0]['data'][k] for k in ['x','y','z']]==[48.5,100,24.5],'item collect did not match actual adjacent spawn')
  removed=[r for r in window if r['bot']=='WorkerA' and r['direction']=='frame' and r['name']=='entity_destroy' and entity in r['data']['entityIds']]
  require(len(removed)==1 and removed[0]['seq']>collect[0]['seq'],'item client removal missing/duplicated/out of order')
  other=[r for r in window if r['bot']=='WorkerB' and r['direction']=='frame' and r['name']=='collect'];require(len(other)==(1 if label=='foreign-watcher' else 0),'item original foreign collect broadcast changed')
  require(client_inventory(begin)==initial and client_inventory(end)==total,'actual item client inventory lost/duplicated')
  requests=[r for r in window if r['bot']=='WorkerA' and r['direction']=='frame_send' and r['name']=='client_command' and r['data']['actionId']=='request_stats'];require(len(requests)==1,'item client statistics request missing')
  stat=[e for r in window if r['bot']=='WorkerA' and r['direction']=='frame' and r['name']=='statistics' for e in r['data']['entries'] if e['categoryId']==4 and e['statisticId']==805]
  require(stat and stat[-1]['value']==5*(index+1),'actual client item pickup statistic not updated exactly once')
  accepted.append(dict(label=label,workerEffect=worker,originalCalls=len(calls),pickupPre=len(calls),pickupPost=1,initialInventory=initial,finalInventory=total,pickedUp=5,xp=0,collectPackets=1,storageCallbacks=1,foreignCollectPackets=len(other)))
 actor=result['cases'][0]['actor'];saved=load(root/'world/stats'/f'{actor}.json')
 require(saved['stats']['minecraft:picked_up']['minecraft:diamond']==25,'saved pickup statistic lost/duplicated')
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['stateClaims']==final['activeBatches']==0,'item logout retains owner/scope/claim')
 require(not final['network']['frozen'] and final['network']['readers']==final['network']['pending']==final['network']['terminals']==0,'item logout retains network resources')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'original item server failed')
 return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],cases=accepted,scope='Exact default plain materials merging into existing non-equipment inventory; original pickup/events/stats/menu/removal; other branches serial, no P3-wide or performance claim')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-item-pickup/independent-verdict.json';require(not out.exists(),'preserve item verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as error:out.write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
