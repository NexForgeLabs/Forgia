#!/usr/bin/env python3
"""Original shared container menu/return/event and raw client/resource verification."""
import argparse,base64,hashlib,json,re,subprocess,zipfile
from pathlib import Path
from verify_resources import nbt_items
TOOLS=Path(__file__).resolve().parent

def require(ok,message):
 if not ok:raise AssertionError(message)

def lines(path):
 data=path.read_bytes();require(data and data.endswith(b'\n'),'uncommitted closed chest evidence '+str(path));return [json.loads(x) for x in data.splitlines()]

def verify(root,installer):
 out=root/'worker-shared-chest';load=lambda p:json.loads(Path(p).read_text());install=load(root/'installation.json');result=load(out/'result.json')
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'original chest runner/process failure')
 require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'chest installed observer Mod or unsafe inputs')
 require(result['installation']==dict(install,launch=result['actualLaunch']) and hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'wrong chest installation/artifact')
 path=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as archive:require((root/'libraries'/path).read_bytes()==archive.read('maven/'+path),'chest installed universal differs')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'chest source changed '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in result['actualLaunch']) and not any(x.startswith('-javaagent:') for x in result['actualLaunch']),'chest observer not original external debugger')
 commands=lines(out/'commands.ndjson');states=[json.loads(x['response']) for x in commands if x['command']=='neoforge regionthreading status players'];packets=lines(out/'packets.ndjson')
 require([r['seq'] for r in packets]==list(range(1,len(packets)+1)),'chest original frames truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   data=base64.b64decode(row['data']['base64'],validate=True);require(len(data)==row['data']['bytes'] and hashlib.sha256(data).hexdigest()==row['data']['sha256'],'chest raw frame checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_resource_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 marks={r['name']:r['seq'] for r in packets if r['direction']=='control'};actors=result['actors'];require(set(actors)=={'WorkerA','WorkerB'},'chest actors missing')
 for state in [result['before'],result['opened'],result['afterClick']]:
  require(state in states and state['requested']==install['playerPlanningRequested'] and state['persistent']==install['persistent'],'invented chest diagnostic')
  require(state['activeBatches']==state['stateClaims']==state['phases']['pending']==state['phases']['running']==0,'chest scope/claim still active')
  require(all(x['ownerCurrent'] for x in state['players']) and all(x['failed']==0 for x in state['phases']['phases'].values()),'chest stale owner/body failed')
 positions={'WorkerA':[47.5,100,22.5,0,0],'WorkerB':[49.5,100,26.5,1,0]}
 for player in result['opened']['players']:require(player['uuid']==actors[player['name']] and [player[k] for k in ['x','y','z','regionX','regionZ']]==positions[player['name']],'shared chest actors did not straddle original regions')
 require(any(x['response']==result['fixtureRead'] for x in commands) and nbt_items(result['fixtureRead'])=={'minecraft:diamond':11},'original shared chest fixture not independently read')
 require(any(x['response']==result['containerAfterRead'] for x in commands) and not nbt_items(result['containerAfterRead']),'shared chest still has resources')
 require(set(result['inventoryReads'])==set(actors) and sum(nbt_items(v).get('minecraft:diamond',0) for v in result['inventoryReads'].values())==11,'shared chest resource loss/duplication')
 for read in [*result['inventoryReads'].values(),*result['xpReads'].values()]:require(any(x['response']==read for x in commands),'missing shared resource independent read')
 require(all(re.search(r': 0\s*$',v) for v in result['xpReads'].values()),'shared chest changed XP')
 rows=lines(out/'jdi.ndjson');require(rows[0]['event']=='armed' and rows[-1]['event']=='observed' and rows[-1]['complete'] and [x['seq'] for x in rows]==list(range(1,len(rows)+1)),'chest original observer incomplete')
 require(set(rows[0]['actors'])==set(actors.values()),'chest observer actor mismatch')
 active={};calls=[];events=[]
 for row in rows[1:-1]:
  require(row['actor'] in actors.values() and (row['thread']=='Server thread' or 'Worker' in row['thread']),'chest unknown observer owner/thread')
  if row['event'] in ['open-event','close-event']:
   require(row['thread']=='Server thread' and any('ServerGamePacketListenerImpl.' in x for x in row['stack']),'chest lifecycle event escaped original packet fence');events.append(row);continue
  key=row['thread']
  if row['event']=='enter':
   require(key not in active,'overlapping original menu call on one thread');active[key]=row
   require(row['method'] in ['stillValid','broadcastChanges'] and row['phase'] in ['ENTITY','CONNECTION','PACKET'] and row['count'] in [0,11],'unknown original chest method/state')
   if 'Worker' in key:
    require(not any('Worker' in k for k in active if k!=key),'same shared chest methods overlap on different workers')
    require(row['phase'] in ['ENTITY','CONNECTION'] and any('RegionPlayerTickDispatch.runOriginal:' in x for x in row['stack']),'chest method escaped original full worker')
    require(any(('ServerPlayer.tick:' if row['phase']=='ENTITY' else 'ServerPlayer.doTick:') in x for x in row['stack']),'chest stage stack mismatch')
   if row['phase']=='CONNECTION':require(any('Connection.tick:' in x for x in row['stack']),'chest connection body incomplete')
  elif row['event']=='return':
   entry=active.pop(key,None);require(entry is not None and all(row[k]==entry[k] for k in ['actor','menu','chest','method','phase','count']),'chest original entry/return mismatch or concurrent inventory mutation')
   require(row['valid'] is True,'original chest synchronous stillValid returned false');calls.append((entry,row))
  else:raise AssertionError('unknown original chest observer event')
 require(not active and calls,'chest method trace incomplete')
 require(len({x['chest'] for x,_ in calls})==1,'players did not share exact original chest identity')
 require(all(len({x['menu'] for x,_ in calls if x['actor']==actor})==1 for actor in actors.values()) and len({x['menu'] for x,_ in calls})==2,'players did not retain distinct original menus')
 for actor in actors.values():
  require(sum(x['event']=='open-event' and x['actor']==actor for x in events)==1 and sum(x['event']=='close-event' and x['actor']==actor for x in events)==1,'original open/close event lost/duplicated')
  for phase,method in [('ENTITY','broadcastChanges'),('ENTITY','stillValid'),('CONNECTION','stillValid')]:
   measured=[x for x,_ in calls if x['actor']==actor and x['phase']==phase and x['method']==method]
   require(measured and any(x['count']==11 for x in measured) and any(x['count']==0 for x in measured),'original chest state visibility incomplete '+phase+method)
   require(all(('Worker' in x['thread'])==install['playerPlanningRequested'] for x in measured),'unexpected shared chest worker coverage')
 def window(label):return [r for r in raw if marks[label+'_before']<r['seq']<marks[label+'_after']]
 opened=window('shared_open');click=window('shared_click');closed=window('shared_close')
 for name,actor in actors.items():
  use=[x for x in opened if x['bot']==name and x['direction']=='frame_send' and x['name']=='block_place'];require(len(use)==1 and use[0]['data']['location']=={'x':48,'y':100,'z':24},'shared chest open not normal client block-use')
  feedback=[x for x in opened if x['bot']==name and x['direction']=='frame' and x['name']=='open_window'];require(len(feedback)==1,'shared open client feedback missing/duplicated')
  win=result['clientsOpen'][name]['windowId'];require(win==feedback[0]['data']['windowId'],'invented actual chest window ID')
  items=[x for x in opened if x['bot']==name and x['direction']=='frame' and x['name']=='window_items' and x['data']['windowId']==win]
  require(items and items[-1]['data']['items'][0].get('itemId')==805 and items[-1]['data']['items'][0]['itemCount']==11,'client shared chest initial inventory mismatch')
  updates=[x for x in click if x['bot']==name and x['direction']=='frame' and ((x['name']=='set_slot' and x['data']['windowId']==win and x['data']['slot']==0 and x['data']['item']['itemCount']==0) or (x['name']=='window_items' and x['data']['windowId']==win and x['data']['items'][0]['itemCount']==0))]
  if name=='WorkerA':require(updates,'non-clicking client did not receive original shared slot becoming empty')
  closes=[x for x in closed if x['bot']==name and x['direction']=='frame_send' and x['name']=='close_window' and x['data']['windowId']==win];require(len(closes)==1,'shared close not normal client packet')
 clicks=[x for x in click if x['direction']=='frame_send' and x['name']=='window_click'];require(len(clicks)==1 and clicks[0]['bot']=='WorkerB' and clicks[0]['data']['mode']==1 and clicks[0]['data']['slot']==0,'shared item transfer not original client shift-click')
 confirmation=window('confirm');win=result['clientsConfirmed']['WorkerB']['windowId']
 feedback=[x for x in confirmation if x['bot']=='WorkerB' and x['direction']=='frame' and x['name']=='window_items' and x['data']['windowId']==win]
 require(len(feedback)==1 and feedback[0]['data']['items'][0]['itemCount']==0 and sum(stack.get('itemCount',0) for stack in feedback[0]['data']['items'][27:] if stack.get('itemId')==805)==11,'authoritative normal reopen did not confirm clicking-client inventory')
 require(feedback[0]['data']==result['clientsConfirmed']['WorkerB']['window'],'invented confirmed client slots')
 require(sum(stack.get('itemCount',0) for stack in result['clientsAfterClick']['WorkerA']['window']['items'][27:] if stack.get('itemId')==805)==0,'non-clicking client acquired duplicate inventory')
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['activeBatches']==final['stateClaims']==0,'shared chest logout retained owner/claim')
 require(not final['network']['frozen'] and final['network']['pending']==final['network']['readers']==final['network']['terminals']==0,'shared chest network resources retained')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'original shared chest server failed')
 return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],originalMenuCalls=len(calls),workerMenuCalls=sum('Worker' in x['thread'] for x,_ in calls),sharedOriginalChestIdentities=1,distinctOriginalMenus=2,originalOpenEvents=2,originalCloseEvents=2,diamondsTransferred=11,xp=0,scope='Exact shared single-chest full worker menu ticks and original synchronous validity/slot feedback; normal packet open/click remain original server fences; P4 owner packet routing not started')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-shared-chest/independent-verdict.json';require(not out.exists(),'preserve prior chest verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as error:out.write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
if __name__=='__main__':main()
