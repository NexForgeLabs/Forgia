#!/usr/bin/env python3
"""Recheck P2 original state, raw packets, asynchronous filtering and serial callback results."""
import argparse,base64,hashlib,json,re,subprocess
from pathlib import Path
from reviewed_artifacts import read_manifest
TOOLS=Path(__file__).resolve().parent

def i64(value):
 return (int(value[0]) << 32) + (int(value[1]) & 0xffffffff) if isinstance(value,list) else int(value)

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,manifest,raw=None,callbacks=None):
 out=root/'p2-matrix';install=json.load(open(root/'installation.json'));result=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0 and not result.get('cleanupError'),'P2 runner/process cleanup failed')
 require(result['installation']==install and install['version']==m['version'] and install['installerSha256']==m['installerSha256'],'wrong P2 installation')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'installed P2 core changed')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/name).read_bytes()).hexdigest()==digest,'archived P2 runner dependency changed')
 mod=bool(result['contractModSha256']);jars=list((root/'mods').glob('*.jar'))
 require((len(jars)==1 and hashlib.sha256(jars[0].read_bytes()).hexdigest()==result['contractModSha256']) if mod else not jars,'wrong actual extension set')
 commands=[json.loads(x) for x in (out/'commands.ndjson').read_text().splitlines()]
 originals=[json.loads(x['response']) for x in commands if x['command']=='neoforge regionthreading status players']
 states={p.stem:json.load(open(p)) for p in out.glob('*.json') if p.stem not in ['result','before-save'] and 'players' in json.load(open(p))}
 for name,state in states.items():
  require(state in originals,'invented server state '+name)
  require(state['requested']==install['playerPlanningRequested'] and state['persistent']==install['persistent'],'actual P2 mode mismatch')
  require(state['activeBatches']==state['stateClaims']==state['phases']['pending']==state['phases']['running']==0,'live claims outside P2 barrier '+name)
  require(not state['network']['frozen'] and state['network']['pending']==state['network']['readers']==state['network']['terminals']==0,'live network references outside barrier '+name)
  require(all(c['failed']==0 for c in state['phases']['phases'].values()),'original player phase failed '+name)
  if not install['playerPlanningRequested'] or mod:require(all(c['worker']==0 for c in state['phases']['phases'].values()),'off/unknown Mod entered worker')
  rows=state['phases']['recent'];started=[x for x in rows if x['started']]
  require(len({(x['tick'],x['phase'],x['playerInstance']) for x in started})==len(started),'duplicate original phase in snapshot '+name)
  require(all(x['state']=='JOINED' and x['returnNanos']>=x['startNanos'] for x in started),'body lacks physical return/join '+name)
 stable=states['stable-after'];actor=next(p for p in stable['players'] if p['name']=='WorkerA');uuid=actor['uuid']
 coverage={}
 for window in ['stable','frozen','stepped','spectator','riding','after-teleports','after-async','credits-window','end-returned']:
  before=states[window+'-before'];after=states[window+'-after'];floor=max((r['tick'] for r in before['phases']['recent']),default=-1)
  records=[r for r in after['phases']['recent'] if r['tick']>floor and r['player']==uuid and r['started']]
  coverage[window]={phase:{'calls':sum(x['phase']==phase for x in records),'worker':sum(x['phase']==phase and x['worker'] for x in records)} for phase in ['ENTITY','CONNECTION']}
  if install['playerPlanningRequested']:
   if window not in ['credits-window']:
    require(all(coverage[window][phase]['calls']>=5 for phase in ['ENTITY','CONNECTION']),'original eligible player did not tick '+window)
   if window=='riding':require(all(x['worker']==0 for x in coverage[window].values()),'riding branch entered worker')
   if window=='stable' and not mod:require(all(x['worker']>=5 for x in coverage[window].values()),'bounded no-Mod branch never entered both real worker phases')
   if window=='credits-window':require(all(x['worker']==0 for x in coverage[window].values()),'removed/credits player admitted into worker')
 if mod and install['playerPlanningRequested']:
  require(all(stable['refusals'].get(p+':UNKNOWN_MOD',0)>0 for p in ['ENTITY','CONNECTION']),'unknown callback Mod was granted production permission')
 def actor_at(name):return next(p for p in states[name]['players'] if p['name']=='WorkerA')
 require(actor_at('same-region-teleport')['x']==28.5 and actor_at('cross-region-teleport')['x']==120.5,'normal same/cross-region teleport did not change actual state')
 require(actor_at('same-region-teleport')['ownerCurrent'] and actor_at('cross-region-teleport')['ownerCurrent'],'teleport owner not republished before next phase')
 require('minecraft:the_end' in result['endOutwardRead'] and 'minecraft:overworld' in result['endReturnRead'],'no original End round trip')
 require(any(c['response']==result['endOutwardRead'] for c in commands) and any(c['response']==result['endReturnRead'] for c in commands),'invented dimension read')
 after=states['after-credits-respawn'];old={r['playerInstance'] for r in states['in-end']['phases']['recent'] if r['player']==uuid and r['started']};new={r['playerInstance'] for r in states['end-returned-after']['phases']['recent'] if r['player']==uuid and r['started'] and r['tick']>max(r['tick'] for r in after['phases']['recent'])}
 if install['playerPlanningRequested']:require(old and new and old.isdisjoint(new),'credits reused old player execution identity')
 final=states['final'];require(not final['players'] and all(final['phases'][k]==0 for k in ['owners','pending','running']),'logout retained player/session bindings')
 packets=[json.loads(x) for x in (out/'packets.ndjson').read_text().splitlines()]
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'truncated P2 client capture')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'corrupt original P2 frame')
 marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 raw=raw if raw is not None else [json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_protocol_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 sent=[r for r in raw if r['bot']=='WorkerA' and r['direction']=='frame_send'];incoming=[r for r in raw if r['bot']=='WorkerA' and r['direction']=='frame']
 require(not any(r['name'] in ['kick_disconnect','disconnect'] and r['seq']<marks['end_after'] for r in incoming),'P2 player disconnected before all gameplay transitions')
 teleports=[r for r in sent if marks['teleports_before']<r['seq']<marks['teleports_after'] and r['name']=='chat_command']
 require([x['data']['command'] for x in teleports]==['tp @s 28.5 100 24.5','tp @s 120.5 100 27.5','tp @s 24.5 100 24.5'],'measured teleports did not originate from ordinary client command packets')
 positions=[r for r in incoming if r['name']=='position']
 for index,r in enumerate(positions):
  upper=positions[index+1]['seq'] if index+1<len(positions) else float('inf')
  # Configuration creates a new listener and may reuse teleportId=1. A later generation's
  # acknowledgement cannot certify an earlier packet with the same numeric ID.
  require(any(x['name']=='teleport_confirm' and x['data']['teleportId']==r['data']['teleportId'] and r['seq']<x['seq']<upper for x in sent),'original server teleport lacks its own ordered acknowledgement before the next position')
 async_in=[r for r in incoming if marks['async_before']<r['seq']<marks['async_after']]
 chats=[r for r in async_in if r['name'] in ['player_chat','profileless_chat','system_chat'] and 'p2_async_' in json.dumps(r['data'])]
 require(len(chats)==5 and all('p2_async_'+str(i) in json.dumps(chats[i]['data']) for i in range(5)),'asynchronous chat chain reordered/lost/duplicated a message')
 pings=[r for r in async_in if r['name']=='ping_response' and 24000<=i64(r['data']['id'])<24005]
 require([i64(x['data']['id']) for x in pings]==list(range(24000,24005)),'mixed packets reordered/lost ping replies')
 filters=[json.loads(x) for x in (out/'filter.ndjson').read_text().splitlines()];filtered=[x for x in filters if x['path']=='/v1/chat' and x['body'].get('text','').startswith('p2_async_')]
 require([x['body']['text'] for x in filtered]==['p2_async_'+str(i) for i in range(5)] and all(x['endNs']-x['startNs']>=30_000_000 for x in filtered),'test never exercised the original asynchronous HTTP filter completion')
 wins=[r for r in incoming if r['name']=='game_state_change' and r['data']['reason']=='win_game'];require(len(wins)==1 and marks.get('exit_portal_before',marks['end_before'])<wins[0]['seq']<marks['credits_ready'],'actual End credits feedback missing/duplicated')
 spawn=[r for r in sent if r['name']=='client_command' and marks['credits_ready']<r['seq']<marks['end_after']];require(len(spawn)==1 and spawn[0]['data']['actionId']=='perform_respawn','not exactly one real credits respawn packet')
 require(any(r['name']=='respawn' and r['data']['worldState']['name']=='minecraft:the_end' for r in incoming) and any(r['name']=='respawn' and r['data']['worldState']['name']=='minecraft:overworld' and r['seq']>spawn[0]['seq'] for r in incoming),'missing original two-direction dimension packets')
 callback_count=0
 if mod:
  callbacks=callbacks if callbacks is not None else [json.loads(x) for x in (root/'p2-contract.ndjson').read_text().splitlines()]
  require([x['seq'] for x in callbacks]==list(range(1,len(callbacks)+1)),'truncated synchronous callback trace')
  require(all(x['serverThread'] and x['thread']=='Server thread' and not x.get('frozen') for x in callbacks),'callback escaped unchanged main-thread/quiescent contract')
  require(all(x.get('running',0)==0 for x in callbacks if x['event'] in ['payload_enter','payload_return','enqueue_inline','chat']),'packet/chat callback ran inside active tick body')
  enters=[x for x in callbacks if x['event']=='payload_enter'];expected=['mixed_'+str(i) for i in range(5)]+['cancel_tp','reconfigure','after_reconfigure','cancel_dimension']
  require([x['detail'] for x in enters]==expected,'same-connection main-thread payload handling reordered/lost/duplicated')
  for x in enters:
   at=callbacks.index(x);require(callbacks[at+1]['event']=='enqueue_inline' and callbacks[at+1]['detail']==x['detail'] and callbacks[at+2]['event']=='payload_return' and callbacks[at+2]['detail']==x['detail']+':done=true','enqueueWork changed original inline completion semantics')
  cancelled=[x for x in callbacks if x['event']=='teleport_command' and 'cancelled=true' in x['detail']];require(len(cancelled)==1 and actor_at('cancelled-teleport')['x']<48,'cancelled command teleport reported a moved player')
  require(any(x['event']=='travel' and x['detail']=='minecraft:the_end:cancelled=true' for x in callbacks) and 'minecraft:overworld' in result['cancelledDimensionRead'],'cancelled dimension transfer changed original synchronous result')
  require(all(p['name']!='WorkerA' for p in states['in-configuration']['players']) and any(p['name']=='WorkerA' for p in states['after-reconfigure']['players']),'configuration did not retire and restore actual game player')
  config=[r for r in incoming if r['name']=='start_configuration'];require(len(config)==1,'missing original reconfiguration start')
  require(any(r['name']=='configuration_acknowledged' and r['seq']>config[0]['seq'] for r in sent) and any(r['name']=='finish_configuration' and r['seq']>config[0]['seq'] and r['state']=='configuration' for r in sent),'missing real config handshake completion')
  stopped=json.load(open(root/'p2-stopped.json'));require(not stopped['network']['frozen'] and stopped['network']['pending']==stopped['network']['readers']==stopped['network']['terminals']==stopped['phases']['owners']==stopped['phases']['pending']==stopped['phases']['running']==stopped['activeBatches']==stopped['stateClaims']==0,'unknown extension shutdown left live references')
  callback_count=len(enters)
 log=(out/'boot-1.server.log').read_text();require(not any(token in log for token in ['Failed to handle packet','Failed to decode packet','Error executing task','Ticking entity']),'original protocol/body failure in P2 run')
 return {'status':'passed','version':install['version'],'persistent':install['persistent'],'requested':install['playerPlanningRequested'],'unknownContractMod':mod,'coverage':coverage,'orderedAsyncChats':5,'orderedMixedPings':5,'mainThreadPayloads':callback_count,'endCredits':1,'oldInstances':sorted(old),'newInstances':sorted(new),'scope':'Bounded P2 full original calls/eligibility/fences/lifecycle; no P3 gameplay expansion or P5 performance certification'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'p2-matrix/independent-verdict.json';require(not out.exists(),'preserve original P2 verdict')
 try:r=verify(a.server.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
