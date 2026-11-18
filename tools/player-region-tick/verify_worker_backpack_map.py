#!/usr/bin/env python3
"""Real map creation and original worker carrier/decorations/packet/save proof; no invalid map-ID shortcut."""
import argparse,base64,hashlib,json,re,subprocess,zipfile
from pathlib import Path
from verify_resources import nbt_items
TOOLS=Path(__file__).resolve().parent

def require(ok,message):
 if not ok:raise AssertionError(message)

def lines(path):
 b=path.read_bytes();require(b and b.endswith(b'\n'),'uncommitted closed map proof '+str(path));return [json.loads(x) for x in b.splitlines()]

def verify(root,installer):
 out=root/'worker-backpack-map';load=lambda p:json.loads(Path(p).read_text());install=load(root/'installation.json');result=load(out/'result.json')
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'original map runner/process failure')
 require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'map observer Mod/unsafe installer input')
 require(result['installation']==dict(install,launch=result['actualLaunch']) and hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'map artifact/launch mismatch')
 relative=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as archive:require((root/'libraries'/relative).read_bytes()==archive.read('maven/'+relative),'map installed universal differs')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'map source snapshot changed '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in result['actualLaunch']) and not any(x.startswith('-javaagent:') for x in result['actualLaunch']),'map observer not original external debugger')
 commands=lines(out/'commands.ndjson');states=[json.loads(x['response']) for x in commands if x['command']=='neoforge regionthreading status players'];packets=lines(out/'packets.ndjson')
 for state in [result['before'],result['after']]:
  require(state in states and state['requested']==install['playerPlanningRequested'] and state['persistent']==install['persistent'],'invented map state/mode')
  require(state['activeBatches']==state['stateClaims']==state['phases']['pending']==state['phases']['running']==0 and all(x['failed']==0 for x in state['phases']['phases'].values()),'map work active/original body failure')
 player=next(x for x in result['after']['players'] if x['uuid']==result['actor']);require(player['name']=='WorkerA' and [player[k] for k in ['x','y','z','regionX','regionZ']]==[47.5,100,24.5,0,0],'original map actor/owner changed')
 item=result['createdItem'];require(item['itemId']==982 and item['itemCount']==1 and item['components']==[{'type':'map_id','data':0}],'map did not come from actual vanilla-created map-ID')
 for key in ['inventoryBeforeRead','inventoryAfterRead','selectedRead','xpBeforeRead','xpAfterRead']:require(any(x['response']==result[key] for x in commands),'missing original map independent read '+key)
 require(nbt_items(result['inventoryBeforeRead'])==nbt_items(result['inventoryAfterRead'])=={'minecraft:filled_map':1} and 'Slot: 9b' in result['inventoryBeforeRead'] and '"minecraft:map_id": 0' in result['inventoryBeforeRead'],'map not retained as same real single backpack item')
 require(re.search(r': 0\s*$',result['selectedRead']) and all(re.search(r': 0\s*$',result[k]) for k in ['xpBeforeRead','xpAfterRead']),'map was equipped or changed XP')
 require(result['mapFile']=='map_0.dat','map save binding differs from actual item')
 saved={}
 for label in ['Before','After']:
  path=out/('map-'+label.lower()+'.dat');require(hashlib.sha256(path.read_bytes()).hexdigest()==result['map'+label+'Sha256'],'map original saved file changed')
  saved[label]=json.loads(subprocess.check_output(['node',str(TOOLS/'read_saved_nbt.cjs'),str(path)],text=True))['data']
 require(saved['Before']==saved['After'],'non-selected original map branch changed saved terrain/map properties')
 require(saved['After']['dimension']=='minecraft:overworld' and not saved['After']['locked'] and len(saved['After']['colors'])==16384,'map proof used a missing/invalid saved map')
 colors=bytes(x&255 for x in saved['After']['colors']);digest=hashlib.sha256(colors).hexdigest()
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'map raw frames truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'map raw frame checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_map_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()];marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 creation=[x for x in raw if marks['map_create_before']<x['seq']<marks['map_create_after'] and x['bot']=='WorkerA' and x['direction']=='frame_send']
 use=[x for x in creation if x['name']=='use_item'];clicks=[x for x in creation if x['name']=='window_click']
 require(len(use)==1 and use[0]['data']['hand']==0 and len(clicks)==2 and [x['data']['slot'] for x in clicks]==[36,9] and all(x['data']['windowId']==0 and x['data']['mode']==0 for x in clicks),'map creation/backpack transfer not ordinary use/two inventory clicks')
 require(clicks[0]['data']['cursorItem']==item and clicks[1]['data']['changedSlots']==[{'location':9,'item':item}] and clicks[1]['data']['cursorItem']['itemCount']==0,'original normal client map transfer changed item/count')
 window=[x for x in raw if marks['map_before']<x['seq']<marks['map_after']]
 turns=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame_send' and x['name']=='position_look'];require(len(turns)==1 and [turns[0]['data'][k] for k in ['x','y','z','yaw','pitch']]==[47.5,100,24.5,90,0],'map change did not originate from one ordinary client rotation')
 rows=lines(out/'jdi.ndjson');require(rows[0]['event']=='armed' and rows[-1]['event']=='observed' and rows[-1]['complete'] and [x['seq'] for x in rows]==list(range(1,len(rows)+1)),'original map observer incomplete')
 require(rows[0]['actor']==result['actor'],'wrong original map observer actor');active={};calls=[]
 for row in rows[1:-1]:
  require(row['event']!='terrain-update','non-selected worker map unexpectedly entered terrain update')
  require(row['actor']==result['actor'] and row['colorsSha256']==digest and row['decoration']['size']==1 and row['decoration']['name']=='WorkerA','map pixels/foreign decoration/resource mismatch')
  require(row['thread']=='Server thread' or 'Worker' in row['thread'],'unknown original map thread')
  key=row['thread']
  if row['event']=='enter':
   require(key not in active and not active,'original same-map calls overlap');active[key]=row
   require(row['method'] in ['tickCarriedBy','getUpdatePacket'] and any('ServerPlayer.doTick:' in x for x in row['stack']) and any('Connection.tick:' in x for x in row['stack']),'map method escaped full original connection')
   if row['method']=='tickCarriedBy':require(any('MapItem.inventoryTick:' in x for x in row['stack']) and any('Inventory.tick:' in x for x in row['stack']),'map carrier callback escaped full original inventory tick')
   require(('Worker' in key)==install['playerPlanningRequested'],'unexpected original map worker coverage')
   if 'Worker' in key:require(any('RegionPlayerTickDispatch.runOriginal:' in x for x in row['stack']),'map side task masquerades as full worker')
  elif row['event']=='return':
   entry=active.pop(key,None);require(entry is not None and all(row[k]==entry[k] for k in ['actor','map','method','colorsSha256']),'original map entry/return mismatch')
   require(row['carriers']==1,'map acquired foreign/duplicate carrier')
   if row.get('packet') is not None:require(row['packet']=='net.minecraft.network.protocol.game.ClientboundMapItemDataPacket' and row['mapId']==0,'original map packet return invalid binding')
   calls.append((entry,row))
  else:raise AssertionError('unknown original map event')
 require(not active and calls and len({x['map'] for x,_ in calls})==1,'original map proof missing/shared wrong data')
 for method in ['tickCarriedBy','getUpdatePacket']:require(any(x['method']==method and x['decoration']['rot']==0 for x,_ in calls) and any(x['method']==method and y['decoration']['rot']==4 for x,y in calls),'map original orientation update missing '+method)
 require(any(x['method']=='tickCarriedBy' and x['decoration']['rot']==0 and y['decoration']['rot']==4 and y['dirty'] for x,y in calls),'map worker produced no original decoration/dirty side effect')
 require(any(y.get('packet') and y['decoration']['rot']==4 for _,y in calls),'original map update returned no real packet with new orientation')
 maps=[x for x in raw if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='map'];require(maps and all(x['data']['itemDamage']==0 for x in maps),'actual client map feedback wrong ID')
 require(any(x['data'].get('icons')==[{'type':0,'x':95,'z':49,'direction':0}] for x in maps) and any(x['data'].get('icons')==[{'type':0,'x':95,'z':49,'direction':4}] and x['seq']>turns[0]['seq'] for x in maps),'actual client map rotation feedback missing/wrong')
 require(all(x['data']['columns']==0 for x in window if x['name']=='map'),'measured backpack map emitted terrain changes')
 pixels=bytearray(16384);initialized=False
 for row in maps:
  packet=row['data'];width=packet['columns']
  if not width:continue
  value=packet['data'];data=bytes(value['data']) if isinstance(value,dict) else bytes(value)
  require(len(data)==width*packet['rows'],'map raw color patch length mismatch')
  for y in range(packet['rows']):pixels[(packet['y']+y)*128+packet['x']:(packet['y']+y)*128+packet['x']+width]=data[y*width:(y+1)*width]
  initialized=True
 require(initialized and bytes(pixels)==colors,'actual client map pixels differ from original saved terrain')
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['stateClaims']==final['activeBatches']==0,'map logout retained state/claim')
 require(not final['network']['frozen'] and final['network']['pending']==final['network']['readers']==final['network']['terminals']==0,'map network resources retained')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'original map server failed')
 return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],originalMapCalls=len(calls),workerMapCalls=sum('Worker' in x['thread'] for x,_ in calls),originalMapIdentities=1,actualMapId=0,backpackMaps=1,carriers=1,orientation=[0,4],colorsSha256=digest,clientSavedPixelsEqual=True,xp=0,scope='One exact non-selected vanilla filled map, real original data/carrier/decoration/update packets and saved pixels; selected/terrain/foreign carriers/unknown components remain serial; no P3-wide/performance claim')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-backpack-map/independent-verdict.json';require(not out.exists(),'preserve previous map verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as error:out.write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
if __name__=='__main__':main()
