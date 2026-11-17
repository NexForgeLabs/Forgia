#!/usr/bin/env python3
"""Independent explicit NETWORK thread, MAIN enqueueWork and unknown-extension refusal proof."""
import argparse,base64,hashlib,json,subprocess
from pathlib import Path
from reviewed_artifacts import read_manifest
TOOLS=Path(__file__).resolve().parent

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,manifest):
 out=root/'network-contract';install=json.load(open(root/'installation.json'));result=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'NETWORK contract run/process did not pass')
 require(install['installerSha256']==m['installerSha256'] and install['version']==m['version'],'wrong NETWORK core')
 jars=list((root/'mods').glob('*.jar'));require(len(jars)==1 and hashlib.sha256(jars[0].read_bytes()).hexdigest()==result['modSha256'],'wrong unknown NETWORK probe Mod')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'NETWORK installed core mismatch')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'NETWORK source snapshot changed')
 require(not any(s.startswith(('-agentlib:','-javaagent:')) for s in result['installation']['launch']),'positive NETWORK test must use ordinary production code without debugger')
 commands=[json.loads(l) for l in (out/'commands.ndjson').read_text().splitlines()];counts=[json.loads(c['response']) for c in commands if c['command']=='network_contract_counts'];require(counts==[result['counts']],'NETWORK counts not from original server')
 c=counts[0];require(c['total']==c['onNetwork']==c['main']==500 and c['onServer']==c['wrongNetworkLoop']==c['wrongOrder']==0 and c['threads'] and all('Netty' in t for t in c['threads']),'explicit NETWORK handler moved from its original Netty loop or MAIN continuation changed')
 require(not (root/'network-contract-violation.json').exists(),'original NETWORK thread violation observed')
 original=[json.loads(c['response']) for c in commands if c['command']=='neoforge regionthreading status players']
 for name in ['before','after','final']:
  state=json.load(open(out/(name+'.json')));require(state in original,'invented NETWORK diagnostic')
  require(state['activeBatches']==state['stateClaims']==state['phases']['running']==state['phases']['pending']==state['network']['pending']==state['network']['readers']==state['network']['terminals']==state['network']['deferred']==0 and not state['network']['frozen'],'unknown extension acquired/froze a player boundary')
  require(all(v['worker']==v['failed']==0 for v in state['phases']['phases'].values()),'unknown NETWORK Mod entered worker')
  if name=='after' and install['playerPlanningRequested']:require(all(state['refusals'].get(p+':UNKNOWN_MOD',0)>0 for p in ['ENTITY','CONNECTION']),'unknown NETWORK Mod did not exercise whole-call refusal')
 final=json.load(open(out/'final.json'));require(not final['players'] and final['phases']['owners']==0,'NETWORK disconnect retained live player bindings')
 packets=[json.loads(l) for l in (out/'packets.ndjson').read_text().splitlines()];require([r['seq'] for r in packets]==list(range(1,len(packets)+1)),'truncated NETWORK wire capture')
 for r in packets:
  if r['direction'] in ['frame','frame_send']:
   b=base64.b64decode(r['data']['base64'],validate=True);require(len(b)==r['data']['bytes'] and hashlib.sha256(b).hexdigest()==r['data']['sha256'],'corrupt NETWORK frame')
 raw=[json.loads(l) for l in subprocess.check_output(['node',str(TOOLS/'decode_protocol_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 wire=[r for r in raw if r['direction']=='frame_send' and r['bot']=='WorkerA' and r['name']=='custom_payload' and r['data']['channel']=='forgia_network:probe'];require(len(wire)==500,'NETWORK operation did not originate from 500 actual normal client packets')
 for index,r in enumerate(wire):
  b=bytes(r['data']['data']['data']);require(b[0]==len(b)-1 and b[1:].decode()=='network_'+str(index),'NETWORK input sequence changed')
 return {'status':'passed','requested':install['playerPlanningRequested'],'persistent':install['persistent'],'networkCallbacks':500,'mainContinuations':500,'wrongThread':0,'deferredNetworkCallbacks':0,'scope':'Unknown Mod whole-call serial fallback preserves explicit original Netty NETWORK execution and MAIN enqueueWork; no permission exception or debugger'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'network-contract/independent-verdict.json';require(not out.exists(),'preserve old NETWORK verdict')
 try:r=verify(a.server.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
