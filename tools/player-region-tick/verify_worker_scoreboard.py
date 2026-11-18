#!/usr/bin/env python3
"""Original shared untracked score changes, synchronous save-dirty effects and TCP XP conservation."""
import argparse,base64,hashlib,json,re,subprocess,zipfile
from pathlib import Path
TOOLS=Path(__file__).resolve().parent

def require(ok,message):
 if not ok:raise AssertionError(message)

def lines(path):
 data=path.read_bytes();require(data and data.endswith(b'\n'),'uncommitted closed score evidence '+str(path));return [json.loads(x) for x in data.splitlines()]

def verify(root,installer):
 out=root/'worker-scoreboard';load=lambda p:json.loads(Path(p).read_text());install=load(root/'installation.json');result=load(out/'result.json')
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'scoreboard original runner/process failed')
 require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'scoreboard observer Mod/unsafe installer inputs')
 require(result['installation']==dict(install,launch=result['actualLaunch']) and hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'scoreboard artifact mismatch')
 relative=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as archive:require((root/'libraries'/relative).read_bytes()==archive.read('maven/'+relative),'scoreboard installed universal mismatch')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'score source snapshot changed '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in result['actualLaunch']) and not any(x.startswith('-javaagent:') for x in result['actualLaunch']),'scoreboard observer not external original debugger')
 commands=lines(out/'commands.ndjson');states=[json.loads(x['response']) for x in commands if x['command']=='neoforge regionthreading status players'];packets=lines(out/'packets.ndjson')
 for state in [result['before'],result['after']]:
  require(state in states and state['persistent']==install['persistent'] and state['requested']==install['playerPlanningRequested'],'invented scoreboard state/mode')
  require(state['activeBatches']==state['stateClaims']==state['phases']['pending']==state['phases']['running']==0 and all(x['failed']==0 for x in state['phases']['phases'].values()),'scoreboard live work/body failure')
 actors=result['actors'];require(set(actors)=={'WorkerA','WorkerB'},'score actors missing')
 require(re.search(r'Count: 3\b',result['orbBeforeRead']) and re.search(r'Value: 7s\b',result['orbBeforeRead']),'wrong actual scoreboard XP fixture')
 for read in [result['orbBeforeRead'],result['xpBeforeRead'],result['xpAfterRead'],result['remainingRead'],*[v for objectives in result['scoreReads'].values() for v in objectives.values()]]:require(any(x['response']==read for x in commands),'missing original scoreboard/resource read')
 require(re.search(r': 0\s*$',result['xpBeforeRead']) and re.search(r': 21\s*$',result['xpAfterRead']) and result['remainingRead'].strip()=='Test failed','XP not conserved under scoreboard protocol')
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'score raw frames truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'score raw frame checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_xp_removal_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()];marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 window=[x for x in raw if marks['score_before']<x['seq']<marks['score_after']]
 moves=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame_send' and x['name']=='position_look'];require(len(moves)==1 and [moves[0]['data'][k] for k in ['x','y','z']]==[47.5,100,24.5],'score XP gameplay not normal client movement')
 collect=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='collect'];require(len(collect)==3 and len({x['data']['collectedEntityId'] for x in collect})==1 and all(x['data']['pickupItemCount']==1 for x in collect),'score XP collection missing/duplicated')
 updates=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='experience'];require(updates and updates[-1]['data']['totalExperience']==21,'score XP actual client feedback mismatch')
 rows=lines(out/'jdi.ndjson');require(rows[0]['event']=='armed' and rows[-1]['event']=='observed' and rows[-1]['complete'] and [x['seq'] for x in rows]==list(range(1,len(rows)+1)),'original score observer incomplete')
 require(set(rows[0]['actors'])==set(actors.values()),'score observer wrong actors')
 active={};calls=[]
 for row in rows[1:-1]:
  thread=row['thread'];require(row['actor'] in actors.values() and (thread=='Server thread' or 'Worker' in thread),'score unknown actor/thread')
  if row['event']=='score-enter':
   require(thread not in active,'overlapping score callback on one thread');require(not active,'shared original scoreboard callbacks overlap across workers')
   require(row['objective'] in ['p3_time','p3_xp'] and isinstance(row['value'],int),'unknown score objective/value')
   require(any('Scoreboard' in x and '.forAllObjectives' in x for x in row['stack']) and any('ServerPlayer.doTick:' in x for x in row['stack']) and any('Connection.tick:' in x for x in row['stack']),'score update escaped complete original connection/criteria traversal')
   if 'Worker' in thread:require(any('RegionPlayerTickDispatch.runOriginal:' in x for x in row['stack']),'score side task masquerades as original worker')
   active[thread]=dict(entry=row,dirty=[])
  elif row['event']=='dirty-return':
   require(thread in active and row['board']==active[thread]['entry']['board'] and row['actor']==active[thread]['entry']['actor'] and row['dirty'] is True,'original saved-data callback not synchronous')
   active[thread]['dirty'].append(row)
  elif row['event']=='score-return':
   call=active.pop(thread,None);require(call is not None and all(row[k]==call['entry'][k] for k in ['actor','board','score','objective','value']),'original score entry/return mismatch')
   require(row['dirty'] is True and len(call['dirty'])==1 and row['savedData']==call['dirty'][0]['savedData'],'original dirty callback missing/duplicated/foreign')
   calls.append(call)
  else:raise AssertionError('unknown original score event')
 require(not active and calls and len({c['entry']['board'] for c in calls})==1 and len({c['dirty'][0]['savedData'] for c in calls})==1,'score callbacks incomplete or players did not share original board/save data')
 for actor in actors.values():
  samples=[c['entry'] for c in calls if c['entry']['actor']==actor and c['entry']['objective']=='p3_time'];require(len(samples)>=2,'missing original player time score writes')
  require(all(samples[i]['value']==samples[i-1]['value']+1 for i in range(1,len(samples))),'time score duplicated/lost original increment')
  require(any('Worker' in x['thread'] for x in samples)==install['playerPlanningRequested'],'missing/forbidden original time score worker')
 xp=[c['entry'] for c in calls if c['entry']['actor']==actors['WorkerA'] and c['entry']['objective']=='p3_xp'];require([x['value'] for x in xp]==[7,14,21],'XP objective not updated at each original synchronous result')
 require(all(('Worker' in x['thread'])==install['playerPlanningRequested'] for x in xp[1:]),'XP score final original worker coverage missing/forbidden')
 require(any('Worker' in c['entry']['thread'] for c in calls)==install['playerPlanningRequested'],'unexpected scoreboard worker coverage')
 require(result['scoreReads']['WorkerA']['p3_xp'].strip()=='WorkerA has 21 [p3_xp]' and result['scoreReads']['WorkerB']['p3_xp'].strip()=="Can't get value of p3_xp for WorkerB; none is set",'original independent XP score mismatch')
 saved=json.loads(subprocess.check_output(['node',str(TOOLS/'read_saved_nbt.cjs'),str(root/'world/data/scoreboard.dat')],text=True))['data'];scores={(x['Name'],x['Objective']):x['Score'] for x in saved['PlayerScores']}
 require(scores[('WorkerA','p3_xp')]==21 and scores.get(('WorkerB','p3_xp'),0)==0,'original saved scoreboard lost/duplicated XP')
 require(all(scores[(name,'p3_time')]>=max(c['entry']['value'] for c in calls if c['entry']['actor']==actor and c['entry']['objective']=='p3_time') for name,actor in actors.items()),'original saved time score lost updates')
 require(all(not x.get('display_auto_update',False) for x in saved['Objectives'] if x['Name'] in ['p3_time','p3_xp']) and not saved.get('DisplaySlots'),'display/tracked objective escaped bounded proof')
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['stateClaims']==final['activeBatches']==0,'scoreboard logout retains scope/claim')
 require(not final['network']['frozen'] and final['network']['pending']==final['network']['readers']==final['network']['terminals']==0,'scoreboard network resources retained')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'original scoreboard server failed')
 return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],originalScoreChanges=len(calls),workerScoreChanges=sum('Worker' in c['entry']['thread'] for c in calls),originalBoardIdentities=1,originalSaveDataIdentities=1,originalDirtyCallbacks=len(calls),xpObjective=[7,14,21],totalXp=21,scope='Exact untracked scoreboard objectives, original shared score/dirty synchronous effects in complete connection worker; display/tracked/unknown callbacks serial; no P3-wide/performance claim')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-scoreboard/independent-verdict.json';require(not out.exists(),'preserve previous score verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as error:out.write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
if __name__=='__main__':main()
