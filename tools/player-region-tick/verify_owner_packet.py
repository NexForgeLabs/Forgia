#!/usr/bin/env python3
"""Verify full original P4 packet/serial-fence bodies, real wire input, saved slot and conservation."""
import argparse,base64,hashlib,json,re,subprocess,zipfile
from pathlib import Path
from verify_resources import nbt_items
TOOLS=Path(__file__).resolve().parent
def require(ok,message):
 if not ok:raise AssertionError(message)
def lines(path):
 data=path.read_bytes();require(data and data.endswith(b'\n'),'uncommitted packet proof '+str(path));return [json.loads(x) for x in data.splitlines()]
def verify(root,installer):
 load=lambda p:json.loads(Path(p).read_text());out=root/'owner-packet';r=load(out/'result.json');install=load(root/'installation.json')
 require(r['status']=='passed' and r['serverExits']==[0] and r['clientsExit']==0,'packet runner/process failure')
 require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'unexpected Mod or unsafe installer')
 require(r['installation']==dict(install,launch=r['actualLaunch']) and hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'packet artifact/launch mismatch')
 relative=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as z:require((root/'libraries'/relative).read_bytes()==z.read('maven/'+relative),'installed packet universal differs')
 for name,digest in r['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'packet source snapshot changed '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in r['actualLaunch']) and not any(x.startswith('-javaagent:') for x in r['actualLaunch']),'packet observer not original external debugger')
 commands=lines(out/'commands.ndjson');states=[json.loads(x['response']) for x in commands if x['command']=='neoforge regionthreading status players']
 for s in [r['before'],r['after']]:
  require(s in states and s['requested']==install['playerPlanningRequested'] and s['persistent']==install['persistent'],'invented packet mode/state')
  require(s['activeBatches']==s['stateClaims']==s['phases']['pending']==s['phases']['running']==0,'packet owner work remains active')
  require(all(v['failed']==0 for v in s['phases']['phases'].values()),'original player tick failure')
 for key in ['inventoryBefore','inventoryAfter','selectedBefore','selectedAfter','xpAfter']:require(any(x['response']==r[key] for x in commands),'missing packet independent read '+key)
 require(nbt_items(r['inventoryBefore'])==nbt_items(r['inventoryAfter'])=={'minecraft:diamond':1} and 'Slot: 3b' in r['inventoryAfter'],'packet inventory lost/duplicated/moved')
 require(re.search(r': 0\s*$',r['selectedBefore']) and re.search(r': 1\s*$',r['selectedAfter']) and re.search(r': 0\s*$',r['xpAfter']),'packet selected slot/XP final state wrong')
 rows=lines(out/'jdi.ndjson');require(rows[0]['event']=='armed' and rows[0]['actor']==r['actor'] and rows[-1]['event']=='observed' and rows[-1]['calls']==12,'packet original observer incomplete')
 require([x['seq'] for x in rows]==list(range(1,len(rows)+1)),'packet observer truncated/reordered')
 queues=[x for x in rows if x['event']=='queue-quiescent'];require(len(queues)==6 and all(x['thread']=='Server thread' and x['readers']==0 and x['actor']==r['actor'] and any('RegionThreadingEngine.tryRunPlayerPacket:' in f for f in x['stack']) for x in queues),'missing original queue/receive-quiescent preparation proof')
 calls=[];current=None
 for row in rows[1:-1]:
  if row['event']=='queue-quiescent':continue
  require(row['actor']==r['actor'] and (row['thread']=='Server thread' or 'Worker' in row['thread']),'foreign packet actor/thread')
  if row['event']=='enter':
   require(current is None,'same-connection original packet bodies overlap');current=row
   method='handleSetCarriedItem:' if row['kind']=='select' else 'handleClientCommand:'
   require(any(method in x for x in row['stack']),'observer outside original packet handler')
   if 'Worker' in row['thread']:require(any('RegionPlayerPacketRouter$Call.run:' in x for x in row['stack']) and any('ServerboundSetCarriedItemPacket.handle:' in x for x in row['stack']),'packet side task masquerades as original owner handler')
  elif row['event']=='return':
   require(current is not None and all(current[k]==row[k] for k in ['actor','thread','kind','slot']) and current['selected']==row['selectedBefore'],'packet original entry/return mismatch')
   calls.append((current,row));current=None
  else:raise AssertionError('unknown packet original event')
 require(current is None and len(calls)==12,'missing original packet returns')
 selected=0;workers=0
 for index,slot in enumerate([1,2,9,3,0,1]):
  (entry,ret),(fence,fenceRet)=calls[2*index:2*index+2];require(entry['kind']=='select' and entry['slot']==slot and entry['selected']==selected,'original selection queue order changed')
  selected=slot if 0<=slot<9 else selected;require(ret['selectedAfter']==selected,'original selected slot effect missing/wrong')
  worker=install['playerPlanningRequested'] and index in [0,1,5];require(('Worker' in entry['thread'])==worker,'missing/unapproved owner packet worker')
  workers+=worker
  require(fence['kind']=='stats' and fence['thread']==fenceRet['thread']=='Server thread' and fence['selected']==fenceRet['selectedBefore']==fenceRet['selectedAfter']==selected,'unknown/global stats fence moved/reordered or observed stale state')
 before,after=r['before']['packets'],r['after']['packets'];require(after['workers']-before['workers']==workers and after['failed']==before['failed']==0,'original packet diagnostics disagree with independent observer')
 packets=lines(out/'packets.ndjson');require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'packet raw frames truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   value=base64.b64decode(row['data']['base64'],validate=True);require(len(value)==row['data']['bytes'] and hashlib.sha256(value).hexdigest()==row['data']['sha256'],'packet raw checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_owner_packet.cjs'),str(out/'packets.ndjson')],text=True).splitlines()];marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 window=[x for x in raw if marks['packet_before']<x['seq']<marks['packet_after'] and x['bot']=='WorkerA'];outgoing=[x for x in window if x['direction']=='frame_send']
 expected=[(name,data) for slot in [1,2,9,3,0,1] for name,data in [('held_item_slot',{'slotId':slot}),('client_command',{'actionId':'request_stats'})]]
 require([(x['name'],x['data']) for x in outgoing]==expected,'packet operations not the actual normal client sequence')
 require(sum(x['direction']=='frame' and x['name']=='statistics' for x in window)==6,'actual client stats fence feedback missing/duplicated')
 # Vanilla does not echo a successful client-selected slot; independent server/save reads prove effect.
 require(not any(x['direction']=='frame' and x['name']=='held_item_slot' for x in window),'unexpected selected-slot server correction')
 if 'receiveSequence' in next(iter(after['recent']),{}):
  ordinals={};counts={}
  for frame in packets:
   if frame['direction']=='frame_send':
    counts[frame['bot']]=counts.get(frame['bot'],0)+1;ordinals[frame['seq']]=counts[frame['bot']]
  metadata=[m for m in after['recent'] if m['sequence']>before['attempted'] and m['started'] and m['player']==r['actor']]
  expected_receives=[ordinals[x['seq']] for i,x in enumerate([p for p in outgoing if p['name']=='held_item_slot']) if install['playerPlanningRequested'] and i in [0,1,5]]
  require([m['receiveSequence'] for m in metadata]==expected_receives,'hotbar worker not tied to original normal wire receive ordinals')
 saved=json.loads(subprocess.check_output(['node',str(TOOLS/'read_saved_nbt.cjs'),str(root/'world/playerdata'/f'{r["actor"]}.dat')],text=True));require(saved['SelectedItemSlot']==1 and saved['XpTotal']==0 and len(saved['Inventory'])==1 and saved['Inventory'][0]['Slot']==3 and saved['Inventory'][0]['count']==1 and saved['Inventory'][0]['id']=='minecraft:diamond','saved packet inventory/XP/selected slot wrong')
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['stateClaims']==final['activeBatches']==0,'packet logout retains owner/claim')
 require(final['packets']['connections']==final['packets']['chatPending']==final['packets']['tasks']['pending']==final['packets']['tasks']['terminals']==0,'packet logout retains packet/receipt/chat references')
 require(not final['network']['frozen'] and final['network']['pending']==final['network']['readers']==final['network']['terminals']==0,'packet logout retains network callbacks')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'packet server failed')
 return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],originalSelections=6,workerSelections=workers,serialStatsFences=6,clientStatsReplies=6,selected=[0,1],diamonds=1,xp=0,scope='P4 first bounded empty hotbar selection only; original PacketUtils queue/handler/error boundary; other packet/async owner protocols and P4-wide acceptance remain pending')
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();out=a.server/'owner-packet/independent-verdict.json';require(not out.exists(),'preserve previous packet verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as error:out.write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
if __name__=='__main__':main()
