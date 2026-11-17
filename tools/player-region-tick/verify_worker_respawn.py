#!/usr/bin/env python3
"""Original state reads + raw death/respawn packets + current owner/generation and new-instance workers."""
import argparse,base64,hashlib,json,re,subprocess,zipfile
from pathlib import Path
from reviewed_artifacts import read_manifest

TOOLS=Path(__file__).resolve().parent

def require(ok,message):
 if not ok:raise AssertionError(message)

def verify(root,installer,manifest):
 out=root/'worker-respawn';install=json.load(open(root/'installation.json'));r=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(r['status']=='passed' and r['serverExits']==[0] and r['clientsExit']==0,'respawn runner/process failure')
 require(r['installation']==install and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'respawn must use actual production admission')
 for name,digest in r['sources'].items():require(hashlib.sha256((out/name).read_bytes()).hexdigest()==digest,'archived respawn dependency source mismatch '+name)
 require(hashlib.sha256((out/'run_worker_respawn.py').read_bytes()).hexdigest()==r['driverSha256'],'archived respawn driver mismatch')
 require(install['version']==m['version'] and install['installerSha256']==m['installerSha256']==hashlib.sha256(installer.read_bytes()).hexdigest(),'wrong respawn core')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'actual respawn core mismatch')
 commands=[json.loads(l) for l in (out/'commands.ndjson').read_text().splitlines()];original=[json.loads(c['response']) for c in commands if c['command']=='neoforge regionthreading status players']
 def state(label):
  d=json.load(open(out/(label+'.json')));require(d in original,'invented respawn snapshot');require(d['activeBatches']==d['stateClaims']==0,'live respawn claims');return d
 initial=state('before-combat-fixture');before=state('before-death');dead=state('dead');after=state('after-respawn');final=state('final');samples=json.load(open(out/'new-instance-samples.json'));require(all(x in original for x in samples),'invented new-instance sample')
 actor=next(p for p in before['players'] if p['name']=='WorkerB');target=next(p for p in before['players'] if p['name']=='WorkerA')
 require(target['health']==20 and actor['ownerCurrent'] and target['ownerCurrent'] and actor['regionX']==0 and target['regionX']==1,'not actual cross-region lethal attack fixture')
 require(next(p for p in dead['players'] if p['name']=='WorkerA')['health']==0 and next(p for p in after['players'] if p['name']=='WorkerA')['health']==20,'missing real death or respawn')
 for key in ['beforeHealthRead','deadHealthRead','healthAfterRead','inventoryAfterRead','xpAfterRead']:require(any(c['response']==r[key] for c in commands),'missing independent original respawn read')
 require(re.search(r': 20(?:\.0)?f\s*$',r['beforeHealthRead']) and re.search(r': 0(?:\.0)?f\s*$',r['deadHealthRead']) and re.search(r': 20(?:\.0)?f\s*$',r['healthAfterRead']),'original health reads disagree')
 require(r['inventoryAfterRead'].strip().endswith('[]') and re.search(r': 0\s*$',r['xpAfterRead']),'unexpected respawn resources')
 old={row['playerInstance'] for row in initial['phases']['recent'] if row['player']==target['uuid'] and row['started']}
 lower=max(row['tick'] for row in after['phases']['recent']) if after['phases']['recent'] else -1
 entries={row['id']:row for sample in samples for row in sample['phases']['recent'] if row['tick']>lower and row['player']==target['uuid'] and row['started']}
 counts={}
 for phase in ['ENTITY','CONNECTION']:
  records=[row for row in entries.values() if row['phase']==phase]
  worker=[row for row in records if row['worker']]
  if install['playerPlanningRequested']:
   require(old and len(records)>=10 and all(row['playerInstance'] not in old for row in records),'old instance retained or new player never ticked '+phase)
   if not worker:
    require(any(value>after['refusals'].get(key,0) for key,value in samples[-1]['refusals'].items() if key.startswith(phase+':')), 'no actual serial refusal evidence in the observed phase '+phase)
   require(len({(row['tick'],row['playerInstance']) for row in records})==len(records),'duplicate original respawn invocation '+phase)
  else:require(not worker,'off respawn entered worker')
  counts[phase]=len(worker)
 for sample in samples:
  require(all(x['ownerCurrent'] for x in sample['players']) and all(p['failed']==0 for p in sample['phases']['phases'].values()),'stale respawn owner or body failure')
 settled_counts=None
 if (out/'settled-instance-samples.json').exists():
  settled=json.load(open(out/'settled-instance-samples.json'));require(all(x in original for x in settled),'invented settled-player samples')
  floor=max(row['tick'] for row in settled[0]['phases']['recent']) if settled[0]['phases']['recent'] else -1
  records={row['id']:row for sample in settled for row in sample['phases']['recent'] if row['tick']>floor and row['player']==target['uuid'] and row['started']}
  settled_counts={}
  for phase in ['ENTITY','CONNECTION']:
   actual=[x for x in records.values() if x['phase']==phase];worker=[x for x in actual if x['worker']]
   if install['playerPlanningRequested']:
    require(len(worker)>=10 and all(x['playerInstance'] not in old for x in actual),'settled new instance did not re-enter both original worker phases '+phase)
    require(len({(x['tick'],x['playerInstance']) for x in actual})==len(actual) and all(x['state']=='JOINED' and not x.get('failureClass') for x in actual),'settled new instance duplicated/failed '+phase)
   else:require(not worker,'off settled respawn entered worker')
   settled_counts[phase]=len(worker)
  require(all(next(p for p in x['players'] if p['name']=='WorkerA')['y']==100.0 for x in settled),'normal movement did not settle actual respawn Y')
 require(not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['activeBatches']==final['stateClaims']==0,'live respawn state after logout')
 require(final['network']['pending']==final['network']['readers']==final['network']['terminals']==0 and not final['network']['frozen'],'live respawn network state')
 packets=[json.loads(l) for l in (out/'packets.ndjson').read_text().splitlines()];marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'raw respawn frame corrupt')
 raw=[json.loads(l) for l in subprocess.check_output(['node',str(TOOLS/'decode_lifecycle_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 def window(a,b):return [x for x in raw if marks[a]<x['seq']<marks[b]]
 death=window('death_before','death_after');spawn=window('respawn_before','respawn_after')
 require(len([x for x in death if x['direction']=='frame_send' and x['name']=='use_entity' and x['bot']=='WorkerB' and x['data']['mouse']==1])==1,'not one actual normal TCP attack')
 require(len([x for x in death if x['direction']=='frame' and x['name']=='damage_event' and x['bot']=='WorkerA'])==1 and any(x['name']=='update_health' and x['bot']=='WorkerA' and x['data']['health']==0 for x in death),'missing actual death feedback')
 require(len([x for x in spawn if x['direction']=='frame_send' and x['name']=='client_command' and x['bot']=='WorkerA' and x['data']['actionId']=='perform_respawn'])==1,'not one standard respawn request')
 require(len([x for x in spawn if x['direction']=='frame' and x['name']=='respawn' and x['bot']=='WorkerA'])==1,'missing actual original respawn packet')
 if settled_counts is not None:
  settle=window('settle_before','settle_after')
  moves=[x for x in settle if x['bot']=='WorkerA' and x['direction']=='frame_send' and x['name']=='position_look']
  require(len(moves)==1 and [moves[0]['data'][key] for key in ['x','y','z']]==[24.5,100.0,24.5],'settlement did not originate in one normal client simulation packet')
  begin=next(i for i,c in enumerate(commands) if c['command']=='data get entity WorkerA Health' and c['response']==r['deadHealthRead'])
  require(all(c['command'].startswith(('data get entity ','neoforge regionthreading status players','stop')) for c in commands[begin:]),'command replaced measured respawn/settlement gameplay')
 require(not any(token in (out/'boot-1.server.log').read_text() for token in ['Failed to handle packet','Caused by:','Error executing task']),'original respawn body failed')
 return {'status':'passed','scope':'production TCP cross-region death/respawn and new-instance worker readmission; internal complete-call metadata, no event observer Mod',
         'version':install['version'],'persistent':install['persistent'],'requested':install['playerPlanningRequested'],'newInstanceWorkerCalls':counts,'oldPlayerInstances':sorted(old),'health':[20,0,20],
         'settledNewInstanceWorkerCalls':settled_counts,
         'workerReadmission': 'both phases observed after normal client settlement' if settled_counts and all(settled_counts.values()) else ('both phases observed' if all(counts.values()) else 'partial; serial refusal retained for an unadmitted new-instance phase'),
         'serverExits':r['serverExits'],'clientsExit':r['clientsExit'],'network':final['network']}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-respawn/independent-verdict.json';require(not out.exists(),'preserve respawn verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
