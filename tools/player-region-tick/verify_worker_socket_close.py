#!/usr/bin/env python3
"""External observation of real TCP close during a complete original player worker body."""
import argparse,base64,hashlib,json
from pathlib import Path
from reviewed_artifacts import read_manifest

def require(ok,message):
 if not ok:raise AssertionError(message)

def verify(root,installer,manifest,events=None):
 out=root/'worker-socket-close';install=json.load(open(root/'installation.json'));i=json.load(open(out/'inputs.json'));r=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(r['status']=='passed' and r['serverExits']==[0] and r['clientsExit']==0,'normal socket-close process failure')
 require(install==i['originalInstallation'] and install['playerPlanningRequested'] and install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'normal socket-close must use production worker admission')
 require(install['version']==m['version'] and install['installerSha256']==m['installerSha256']==hashlib.sha256(installer.read_bytes()).hexdigest(),'wrong socket-close core')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'actual socket-close core mismatch')
 for name,digest in i['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'socket-close original source/observer mismatch')
 events=events if events is not None else [json.loads(l) for l in (out/'jdi.ndjson').read_text().splitlines()]
 require([x['event'] for x in events]==['armed','original_entry','original_body_effect','socket_closed','joined','shutdown_after_gate_close'],'incomplete normal socket-close debugger events')
 entry,effect,close,join,shutdown=events[1:];phase=i['phase']
 require(entry['uuid']==effect['uuid']==close['uuid']==join['uuid'] and entry['tick']==effect['tick']==close['tick']==join['tick'],'socket-close identity/tick mismatch')
 require(entry['phase']==effect['phase']==close['phase']==join['phase']==phase,'socket-close phase mismatch')
 require(entry['thread']==effect['thread'] and 'Region' in entry['thread'] and 'Worker' in entry['thread'] and join['thread']=='Server thread','normal socket-close observed on wrong threads')
 require(effect['counterBefore']==entry['counterBefore'] and effect['counterAfter']==effect['counterBefore']+(-1 if phase=='ENTITY' else 1),'normal socket-close original effect missing/duplicated')
 require(any('RegionPlayerTickDispatch.runOriginal:' in line for line in effect['stack']),'socket-close observed outside original complete call')
 require(close['actualConnectionOpen'] is False and close['originalStateScopeValid'] is True,'physical TCP close revoked active state scope')
 require(join['bindingReleased'],'original socket-close plan retained binding')
 d=json.loads(join['diagnostics']);f=json.loads(shutdown['diagnostics']);require(d==r['joined'] and f==r['closed'],'socket-close runner disagrees with independent observer')
 selected=[x for x in d['phases']['recent'] if x['player']==entry['uuid'] and x['phase']==phase and x['tick']==entry['tick'] and x['started']]
 require(len(selected)==1 and selected[0]['worker'] and selected[0]['state']=='JOINED' and not selected[0].get('failureClass'),'normal TCP close failed/replayed original invocation')
 require(d['network']['frozen'],'gate thawed before actual join')
 require(f['phases']['owners']==f['phases']['pending']==f['phases']['running']==f['activeBatches']==f['stateClaims']==0,'live socket-close player state')
 require(f['network']['closed'] and f['network']['pending']==f['network']['readers']==f['network']['terminals']==0 and not f['network']['frozen'],'socket-close left network references')
 require(all(c['failed']==0 and c['started']==c['returned']==c['joined'] for c in f['phases']['phases'].values()),'normal socket-close original body failed or did not join')
 require(f['network']['deferred']>0,'no actual callback deferred during physical socket close')
 packets=[json.loads(l) for l in (out/'packets.ndjson').read_text().splitlines()]
 require(any(x['bot']=='WorkerA' and x['direction']=='end' for x in packets),'normal client TCP close absent')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   data=base64.b64decode(row['data']['base64'],validate=True);require(len(data)==row['data']['bytes'] and hashlib.sha256(data).hexdigest()==row['data']['sha256'],'socket-close raw frame corrupt')
 log=(out/'boot-1.server.log').read_text();require(not any(s in log for s in ['Failed to handle packet','Caused by:','Exception caught in connection','Error executing task']),'normal socket-close server failure')
 return {'status':'passed','scope':'external breakpoint observation; normal TCP close inside already-started complete original worker; no Mod/class redefinition/admission bypass',
         'version':install['version'],'phase':phase,'persistent':install['persistent'],'originalCounter':[effect['counterBefore'],effect['counterAfter']],
         'completedInvocation':selected[0],'networkAfterShutdown':f['network'],'serverExits':r['serverExits'],'clientsExit':r['clientsExit']}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);a=p.parse_args();out=a.server/'worker-socket-close/independent-verdict.json';require(not out.exists(),'preserve socket-close verdict')
 try:r=verify(a.server.resolve(),a.installer.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
