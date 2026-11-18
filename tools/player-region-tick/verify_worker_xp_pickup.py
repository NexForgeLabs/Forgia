#!/usr/bin/env python3
"""Independent original XP body/thread, raw TCP feedback and complete resource conservation proof."""
import argparse,base64,hashlib,json,re,subprocess,uuid,zipfile
from pathlib import Path

TOOLS=Path(__file__).resolve().parent

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,installer):
 out=root/'worker-xp-pickup';load=lambda p:json.loads(Path(p).read_text())
 install=load(root/'installation.json');result=load(out/'result.json')
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'XP runner or original server/client process failed')
 require(install.get('installerInputsPrivate') is True,'XP corpus predates private installer input isolation')
 require(install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'XP worker proof loaded an observer Mod')
 require(result['installation']==dict(install,launch=result['actualLaunch']),'XP launch/installation receipt mismatch')
 require(hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'XP installer changed')
 relative=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
 with zipfile.ZipFile(installer) as archive:
  require((root/'libraries'/relative).read_bytes()==archive.read('maven/'+relative),'XP installed core differs from installer')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'XP source snapshot changed: '+name)
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in result['actualLaunch']) and not any(x.startswith('-javaagent:') for x in result['actualLaunch']),'XP observer not original external debugger')
 commands=[json.loads(x) for x in (out/'commands.ndjson').read_text().splitlines()]
 states=[json.loads(c['response']) for c in commands if c['command']=='neoforge regionthreading status players']
 packets=[json.loads(x) for x in (out/'packets.ndjson').read_text().splitlines()]
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'XP raw packet trace is truncated')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   data=base64.b64decode(row['data']['base64'],validate=True)
   require(len(data)==row['data']['bytes'] and hashlib.sha256(data).hexdigest()==row['data']['sha256'],'XP raw frame checksum mismatch')
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_lifecycle_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 spawns=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_resource_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 marks={r['name']:r['seq'] for r in packets if r['direction']=='control'}
 require([c['label'] for c in result['cases']]==['isolated','foreign-watcher','single','over-budget'],'XP cases missing/reordered')
 accepted=[]
 for case in result['cases']:
  label=case['label'];before,after=case['before'],case['after'];actor=case['actor'];value=case['value'];count=case['count']
  for state in [before,after]:
   require(state in states and state['persistent']==install['persistent'] and state['requested']==install['playerPlanningRequested'],'invented XP diagnostic/mode')
   require(state['activeBatches']==state['stateClaims']==state['phases']['running']==state['phases']['pending']==0,'XP scope/claim still active at original quiescent read')
   require(all(x['ownerCurrent'] for x in state['players']) and all(v['failed']==0 for v in state['phases']['phases'].values()),'XP stale owner/original body failure')
  a=next(x for x in after['players'] if x['uuid']==actor)
  require(a['name']=='WorkerA' and a['regionX']==0 and a['x']==47.5,'XP pickup actor not in original adjacent region fixture')
  require(re.search(r'Count: '+str(count)+r'\b',case['initialOrbRead']) and re.search(r'Value: '+str(value)+r's\b',case['initialOrbRead']),'XP source count/value fixture not independently read')
  position=re.search(r'Pos: \[([^\]]+)\]',case['initialOrbRead'])
  require(position is not None and [float(x.strip().removesuffix('d')) for x in position[1].split(',')]==[48.5,100,24.5],'XP source position not across the actual region boundary')
  identity=re.search(r'UUID: \[I; ([^\]]+)\]',case['initialOrbRead'])
  require(identity is not None,'XP source UUID not independently read')
  words=[int(x.strip()) & 0xffffffff for x in identity[1].split(',')];require(len(words)==4,'XP UUID word count changed')
  original_orb=str(uuid.UUID(int=sum(word << (96-32*i) for i,word in enumerate(words))))
  for key in ['initialOrbRead','xpBeforeRead','xpAfterRead','remainingRead']:require(any(c['response']==case[key] for c in commands),'missing original XP conservation read '+key)
  require(re.search(r': 0\s*$',case['xpBeforeRead']) and re.search(r': '+str(count*value)+r'\s*$',case['xpAfterRead']) and case['remainingRead'].strip()=='Test failed','XP sum not conserved or remaining orb exists')
  rows=[json.loads(x) for x in (out/('jdi-'+label+'.ndjson')).read_text().splitlines()]
  require(rows[0]['event']=='armed' and rows[-1]['event']=='observed' and [x['seq'] for x in rows]==list(range(1,len(rows)+1)),'XP observer incomplete')
  require(rows[-1]['xp']==count*value and rows[-1]['remainingOrbCount']==0,'XP observer final conservation mismatch')
  current=None;effects=[];orbs=set()
  for row in rows[1:-1]:
   require(row['actor']==actor and (row['thread']=='Server thread' or 'Worker' in row['thread']),'XP unknown observer actor/thread')
   orbs.add(row['orb'])
   if row['event']=='enter':
    require(current is None,'overlapping original XP call on one actor');current=row
    require(row['value']==value,'XP orb value changed inside measured gameplay')
    require(any('ExperienceOrb.playerTouch:' in x for x in row['stack']) and any('ServerPlayer.doTick:' in x for x in row['stack']) and any('Connection.tick:' in x for x in row['stack']),'XP callback escaped complete original player/connection body')
    if 'Worker' in row['thread']:require(any('RegionPlayerTickDispatch.runOriginal:' in x for x in row['stack']),'XP callback was a side task')
   elif row['event']=='return':
    require(current is not None and current['orb']==row['orb'] and current['thread']==row['thread'] and current['count']==row['countBefore'] and current['xp']==row['xpBefore'],'XP entry/return identity mismatch')
    delta=row['countBefore']-row['countAfter'];xp=row['xpAfter']-row['xpBefore']
    require(delta in [0,1] and xp==delta*value,'XP callback duplicated or lost resource')
    require(row['removed']==(row['countAfter']==0),'XP original removal state inconsistent')
    require(row['xpBefore']+row['countBefore']*value==count*value and row['xpAfter']+row['countAfter']*value==count*value,'XP intermediate sum not conserved')
    if delta:effects.append(row)
    current=None
   else:raise AssertionError('unknown original XP observer event')
  require(current is None and len(orbs)==1 and len(effects)==count,'XP invocation trace incomplete or duplicated')
  require(orbs=={original_orb},'Observed XP body belongs to another server orb instance')
  require(all(effects[i]['countBefore']==count-i for i in range(count)),'XP consumption order changed')
  worker=[x for x in effects if 'Worker' in x['thread']]
  if install['playerPlanningRequested'] and label=='isolated':require(worker and any(x['countBefore']==2 and x['countAfter']==1 for x in worker) and all(x['countBefore']>=2 and x['countAfter']>=1 for x in worker),'no non-final original XP worker effect')
  else:require(not worker,'unadmitted XP case ran on worker')
  require(effects[-1]['thread']=='Server thread','final original pickup/removal escaped complete serial fence')
  begin,end=marks[label+'_before'],marks[label+'_after'];window=[x for x in raw if begin<x['seq']<end]
  moves=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame_send' and x['name']=='position_look']
  require(len(moves)==1 and [moves[0]['data'][k] for k in ['x','y','z']]==[47.5,100,24.5],'XP gameplay did not originate from one normal movement packet')
  collected=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='collect']
  require(len(collected)==count and len({x['data']['collectedEntityId'] for x in collected})==1 and all(x['data']['collectorEntityId']==collected[0]['data']['collectorEntityId'] and x['data']['pickupItemCount']==1 for x in collected),'actual XP collection feedback lost/duplicated')
  spawn=[x for x in spawns if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='spawn_entity_experience_orb' and x['data']['entityId']==collected[0]['data']['collectedEntityId']]
  require(len(spawn)==1 and [spawn[0]['data'][k] for k in ['x','y','z','count']]==[48.5,100,24.5,value],'XP collect ID does not match the actual cross-region spawned orb frame')
  updates=[x for x in window if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='experience']
  require(updates and updates[-1]['data']['totalExperience']==count*value,'actual client XP feedback missing/wrong')
  other=[x for x in window if x['bot']=='WorkerB' and x['direction']=='frame' and x['name']=='collect']
  require(len(other)==(count if case['foreignWatcher'] else 0),'original foreign observer packet behavior changed')
  accepted.append({'label':label,'count':count,'value':value,'totalXp':count*value,'originalEffects':len(effects),'workerEffects':len(worker),'finalEffectThread':effects[-1]['thread'],'collectPackets':len(collected),'foreignCollectPackets':len(other)})
 final=load(out/'final.json');require(final in states and not final['players'] and final['phases']['owners']==final['phases']['pending']==final['phases']['running']==final['activeBatches']==final['stateClaims']==0,'XP logout retains player/lease/claim')
 require(not final['network']['frozen'] and final['network']['readers']==final['network']['pending']==final['network']['terminals']==0,'XP logout retains network callbacks')
 require(not any(x in (out/'boot-1.server.log').read_text() for x in ['Failed to handle packet','Caused by:','Error executing task']),'original XP server failed')
 return {'status':'passed','version':install['version'],'requested':install['playerPlanningRequested'],'persistent':install['persistent'],'cases':accepted,'scope':'P3 first merged vanilla XP non-final pickup; final/single/foreign observer/over-budget remain full serial; no broader gameplay or performance certificate'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-xp-pickup/independent-verdict.json';require(not out.exists(),'preserve previous XP verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
