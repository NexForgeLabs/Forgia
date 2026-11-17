#!/usr/bin/env python3
"""Independent original worker/physical join/TCP-order/overflow proof checks."""
import argparse,base64,hashlib,json,subprocess
from pathlib import Path
from reviewed_artifacts import read_manifest
TOOLS=Path(__file__).resolve().parent

def i64(value):
 return (int(value[0]) << 32) + (int(value[1]) & 0xffffffff) if isinstance(value,list) else int(value)

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,manifest,events=None,raw=None):
 out=root/'network-fence';install=json.load(open(root/'installation.json'));inputs=json.load(open(out/'inputs.json'));result=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'original network fence run did not complete')
 require(install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')) and install['playerPlanningRequested'],'network fence requires actual production workers')
 require(install['version']==m['version'] and install['installerSha256']==m['installerSha256'],'wrong network fence installer')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'installed core changed')
 for name,digest in inputs['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'probe/source snapshot changed')
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in inputs['actualLaunch']) and not any(x.startswith('-javaagent:') for x in inputs['actualLaunch']),'unexpected instrumentation/admission mechanism')
 events=events if events is not None else [json.loads(l) for l in (out/'jdi.ndjson').read_text().splitlines()]
 require([x['event'] for x in events]==['armed','original_entry','original_body_effect','packets_fenced','joined','shutdown_after_gate_close'],'missing original worker fence events')
 entry,effect,fence,join,close=events[1:]
 require(entry['phase']==effect['phase']==fence['phase']==join['phase']==inputs['phase'] and entry['uuid']==effect['uuid']==fence['uuid']==join['uuid'],'foreign phase/player')
 require(entry['tick']==effect['tick']==fence['tick']==join['tick'],'worker/queue crossed original physical call')
 require(entry['counterBefore']==effect['counterBefore'] and effect['counterAfter']==effect['counterBefore']+(-1 if inputs['phase']=='ENTITY' else 1) and fence['counterAfter']==effect['counterAfter'],'missing original complete-body side effect')
 require(any('RegionPlayerTickDispatch.runOriginal:' in x for x in effect['stack']) and 'Worker' in entry['thread'] and join['thread']=='Server thread','not an original worker/publisher pair')
 require(fence['frozen'] and fence['originalStateScopeValid'] and fence['queued']==(4096 if inputs['overflow'] else 256),'normal TCP callbacks bypassed original frozen boundary')
 joined=json.loads(join['diagnostics']);final=json.loads(close['diagnostics'])
 require(join['bindingReleased'] and joined['network']['frozen'],'join lost binding retirement/fence')
 calls=[x for x in joined['phases']['recent'] if x['tick']==entry['tick'] and x['player']==entry['uuid'] and x['phase']==entry['phase'] and x['started']]
 require(len(calls)==1 and calls[0]['worker'] and calls[0]['state']=='JOINED' and not calls[0].get('failureClass'),'fenced original call failed or replayed')
 require(final['network']['closed'] and not final['network']['frozen'] and all(final['network'][x]==0 for x in ['pending','readers','terminals']),'final packet resources survived shutdown')
 require(all(final['phases'][x]==0 for x in ['owners','pending','running']) and final['activeBatches']==final['stateClaims']==0,'final player/batch claims survived shutdown')
 require(all(x['failed']==0 and x['started']==x['returned']==x['joined'] for x in final['phases']['phases'].values()),'original tick failure or missing join')
 packets=[json.loads(x) for x in (out/'packets.ndjson').read_text().splitlines()]
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'truncated original TCP capture')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'corrupt original frame')
 marks={x['name']:x['seq'] for x in packets if x['direction']=='control'}
 raw=raw if raw is not None else [json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_protocol_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 sent=[x for x in raw if x['bot']=='WorkerA' and x['direction']=='frame_send' and x['name']=='ping_request' and marks['frozen_before']<x['seq']<marks['still_frozen']]
 received=[x for x in raw if x['bot']=='WorkerA' and x['direction']=='frame' and x['name']=='ping_response' and i64(x['data']['id'])>=100000]
 n=4200 if inputs['overflow'] else 256;accepted=min(n,4096)
 require([i64(x['data']['id']) for x in sent]==list(range(100000,100000+n)),'not original complete normal client packet sequence')
 require([i64(x['data']['id']) for x in received]==list(range(100000,100000+accepted)),'lost/reordered/duplicated original accepted ping callbacks')
 require(all(x['seq']>marks['still_frozen'] for x in received),'server executed queued callback/replied before physical worker return')
 kicks=[x for x in raw if x['bot']=='WorkerA' and x['name']=='kick_disconnect' and x['direction']=='frame']
 if inputs['overflow']:
  require(len(kicks)==1 and 'Player network queue limit exceeded' in json.dumps(kicks[0]['data']),'overflow did not end in exactly one real protocol disconnect')
  require(kicks[0]['seq']>received[-1]['seq'],'terminal overflow disconnect overtook accepted callbacks')
 else:require(not kicks,'normal queue pressure disconnected real client')
 return {'status':'passed','phase':inputs['phase'],'persistent':install['persistent'],'overflow':inputs['overflow'],'sent':n,'orderedAcceptedReplies':accepted,'queuedWhileOriginalWorkerPaused':fence['queued'],'disconnectPackets':len(kicks),'finalNetwork':final['network'],'scope':'No-Mod real normal TCP packets; original full worker body paused through external JDI; timings excluded from performance'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'network-fence/independent-verdict.json';require(not out.exists(),'preserve prior network verdict')
 try:r=verify(a.server.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
