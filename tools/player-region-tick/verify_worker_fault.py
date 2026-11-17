#!/usr/bin/env python3
"""Recheck external JDI proof of complete original player-worker body effects/failure/join/retirement."""
import argparse,base64,hashlib,json,subprocess,zipfile
from pathlib import Path
from reviewed_artifacts import read_manifest

TOOLS=Path(__file__).resolve().parent

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,installer,manifest,events=None,packets=None):
 out=root/'worker-fault';install=json.load(open(root/'installation.json'));inputs=json.load(open(out/'inputs.json'));result=json.load(open(out/'result.json'))
 m,_=read_manifest(manifest)
 require(result['status']=='passed_expected_failure' and result['clientsExit']==0 and len(result['serverExits'])==1,'worker fault runner/process failure')
 require(install==inputs['originalInstallation'] and install['version']==m['version'] and install['installerSha256']==m['installerSha256']==hashlib.sha256(installer.read_bytes()).hexdigest(),'wrong reviewed original core')
 require(install['playerPlanningRequested'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'worker fault must use production admission without Mods')
 for suffix,digest in m['installedJars'].items():
  jar=root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar'
  require(hashlib.sha256(jar.read_bytes()).hexdigest()==digest,'actual worker-fault core mismatch')
 for name,digest in inputs['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'original fault probe/source snapshot mismatch')
 launch=inputs['actualLaunch'];require(len([s for s in launch if s.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:')])==1,'missing actual loopback-only external debugger launch')
 require(not any(s.startswith('-javaagent:') for s in launch),'unexpected Java instrumentation agent')
 events=events if events is not None else [json.loads(s) for s in (out/'jdi.ndjson').read_text().splitlines()]
 require([r['event'] for r in events]==['armed','original_entry','original_body_effect','injected','joined','shutdown_after_gate_close'],'missing/duplicate original debugger events')
 entry,effect,injection,join,shutdown=events[1:]
 phase=inputs['phase'];require(result['phase']==phase,'phase mismatch')
 require(entry['phase']==effect['phase']==injection['phase']==join['phase']==phase and entry['uuid']==effect['uuid']==injection['uuid']==join['uuid'],'foreign debugger player/phase')
 require(entry['tick']==effect['tick']==injection['tick']==join['tick'],'debugger failure escaped original tick')
 require(entry['thread']==effect['thread'] and 'Region' in entry['thread'] and 'Worker' in entry['thread'] and join['thread']==shutdown['thread']=='Server thread','original worker/server join thread mismatch')
 require(any('RegionPlayerTickDispatch.runOriginal:' in s for s in entry['stack']) and any('RegionPlayerTickDispatch.runOriginal:' in s for s in effect['stack']),'breakpoint outside complete original worker invocation')
 required='ServerPlayer.tick:' if phase=='ENTITY' else 'Connection.tick:'
 require(any(required in s for s in effect['stack']),'body effect not inside complete original method')
 require(effect['counterBefore']==entry['counterBefore'] and effect['counterAfter']==effect['counterBefore']+(-1 if phase=='ENTITY' else 1),'no actual once-only original body counter effect')
 require(join['bindingReleased'],'joined plan still retains original binding')
 observed=json.loads(join['diagnostics']);final=json.loads(shutdown['diagnostics'])
 require(observed==result['joined'] and final==result['closed'],'runner diagnostics differ from independent debugger')
 failures=[r for r in observed['phases']['recent'] if r.get('failureClass')]
 require(len(failures)==1,'duplicate/missing independently observed original failure')
 failed=failures[0]
 require(failed['player']==entry['uuid'] and failed['tick']==entry['tick'] and failed['phase']==phase and failed['worker'] and failed['started'] and failed['state']=='JOINED' and failed['thread']==entry['thread'],'wrong actual original failed invocation')
 require(failed['startNanos']>0 and failed['returnNanos']>=failed['startNanos'],'no physical original body return interval')
 require(sum(r['player']==failed['player'] and r['tick']==failed['tick'] and r['phase']==phase and r['started'] for r in observed['phases']['recent'])==1,'original failed invocation replayed')
 require(observed['network']['frozen'],'failed worker fence thawed before publisher join')
 require(final['activeBatches']==final['stateClaims']==final['phases']['owners']==final['phases']['pending']==final['phases']['running']==0,'live original scope/batch/player bookkeeping after failure')
 require(final['network']['closed'] and final['network']['pending']==final['network']['readers']==final['network']['terminals']==0 and not final['network']['frozen'],'network resources survive final gate close')
 for p,counts in final['phases']['phases'].items():
  require(counts['failed']==int(p==phase) and counts['started']==counts['returned']+counts['failed']==counts['joined'],'original phase lost a return/join or failed twice')
 peers=[r for r in final['phases']['recent'] if r['player']!=failed['player'] and r['tick']==failed['tick'] and r['phase']==phase and r['worker'] and r['state']=='JOINED' and not r.get('failureClass')]
 same_group=inputs.get('sameGroup',False)
 if same_group:
  group=entry.get('groupPlayers',[])
  require(len(group)==2 and group[0]==entry['uuid'] and len(set(group))==2,'fault was not in first member of an actual two-player conflict group')
  suffix=[r for r in final['phases']['recent'] if r['player']==group[1] and r['tick']==failed['tick'] and r['phase']==phase]
  require(any(r['state']=='CANCELLED' and not r['started'] and not r['worker'] for r in suffix),'unstarted same-group suffix was not cancelled at physical join')
  started=[r for r in suffix if r['started']]
  if phase=='ENTITY':require(not started,'world suffix ran after original fatal world-body failure')
  else:require(len(started)==1 and not started[0]['worker'] and started[0]['state']=='JOINED' and not started[0].get('failureClass'),'connection suffix did not run exactly once at original serial traversal position')
 else:
  require(len(entry.get('groupPlayers',[entry['uuid']]))==1,'independent fault probe unexpectedly shared a conflict group')
 packets=packets if packets is not None else [json.loads(s) for s in (out/'packets.ndjson').read_text().splitlines()]
 require([r['seq'] for r in packets]==list(range(1,len(packets)+1)),'truncated client trace')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   data=base64.b64decode(row['data']['base64'],validate=True);require(len(data)==row['data']['bytes'] and hashlib.sha256(data).hexdigest()==row['data']['sha256'],'corrupt original TCP protocol frame')
 client_disconnects=None
 if phase=='CONNECTION':
  raw=[json.loads(s) for s in subprocess.check_output(['node',str(TOOLS/'decode_lifecycle_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
  kicks=[r for r in raw if r['direction']=='frame' and r['bot']=='WorkerA' and r['name']=='kick_disconnect']
  require(len(kicks)==1 and 'Internal server error' in json.dumps(kicks[0]['data']),'no actual original client disconnect feedback')
  decoded=[r for r in packets if r['direction']=='receive' and r['bot']=='WorkerA' and r['name']=='kick_disconnect']
  require(len(decoded)==1 and decoded[0]['data']==kicks[0]['data'],'raw/decoded original disconnect mismatch')
  client_disconnects=1
 log=(out/'boot-1.server.log').read_text()
 require('player_worker_fault_after_original_body_'+phase in log and ('Ticking entity' if phase=='ENTITY' else 'Failed to handle packet') in log,'original server did not report injected failure')
 return {'status':'passed_expected_failure','scope':'independent external debugger; no oracle Mod or runtime admission bypass; timings excluded from overlap/performance claims',
         'version':install['version'],'installerSha256':install['installerSha256'],'phase':phase,'persistent':install['persistent'],
         'originalCounter':[effect['counterBefore'],effect['counterAfter']],'failedInvocation':failed,'otherGroupsJoinedSameTick':len(peers),
         'sameGroup':same_group,'clientDisconnectPackets':client_disconnects,'networkAfterShutdown':final['network'],'serverExits':result['serverExits'],'clientsExit':result['clientsExit']}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-fault/independent-verdict.json';require(not out.exists(),'preserve original worker-fault verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
