#!/usr/bin/env python3
"""Confirm the preserved .125 NETWORK-thread defect; this is rejected-product evidence, never a pass."""
import argparse,base64,hashlib,json,subprocess
from pathlib import Path
from reviewed_artifacts import read_manifest
TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();root=a.server.resolve();out=root/'network-contract';r=json.load(open(out/'result.json'));install=json.load(open(root/'installation.json'));m,_=read_manifest(TOOLS/'reviewed-artifacts/21.1.250.125-beta.json')
 assert not a.output.exists();assert r['status']=='reproduced_original_network_contract_violation' and r['serverExits']==[0] and r['clientsExit']==0
 assert install['version']==m['version'] and install['installerSha256']==m['installerSha256'] and install['playerPlanningRequested'] and install['oracleSha256'] is None
 jars=list((root/'mods').glob('*.jar'));assert len(jars)==1 and hashlib.sha256(jars[0].read_bytes()).hexdigest()==r['modSha256']
 for suffix,digest in m['installedJars'].items():assert hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest
 for name,digest in json.load(open(out/'source-inputs.json')).items():assert hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest
 engine='src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java';assert hashlib.sha256((out/'source-snapshot'/engine).read_bytes()).hexdigest()==m['review']['sources'][engine]
 events=[json.loads(x) for x in (out/'jdi.ndjson').read_text().splitlines()];assert [x['event'] for x in events]==['armed','unknown_preflight_frozen','normal_network_payload_queued'];assert events[1]['frozen'] and events[1]['thread']=='Server thread' and events[2]['queued']>=1
 violation=json.load(open(root/'network-contract-violation.json'));assert violation['serverThread'] and violation['thread']=='Server thread' and any('RegionPlayerNetworkGate$Deferred.run' in s for s in violation['stack'])
 commands=[json.loads(x) for x in (out/'commands.ndjson').read_text().splitlines()];assert [json.loads(x['response']) for x in commands if x['command']=='network_contract_counts']==[r['counts']];assert r['counts']=={'total':1,'onServer':1,'onNetwork':0,'main':1}
 for name in ['before','after','final']:
  state=json.load(open(out/(name+'.json')));assert all(v['worker']==v['failed']==0 for v in state['phases']['phases'].values())
 packets=[json.loads(x) for x in (out/'packets.ndjson').read_text().splitlines()]
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);assert len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256']
 raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_protocol_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()];payloads=[x for x in raw if x['direction']=='frame_send' and x['name']=='custom_payload' and x['data']['channel']=='forgia_network:probe'];assert len(payloads)==1
 result={'status':'defect_confirmed_not_accepted','version':install['version'],'ordinaryTcpPayloads':1,'workerCalls':0,'networkHandlerActuallyRanOn':'Server thread','originalMainContinuation':1,'externalObserver':'read-only JDI pause of original preflight; no agent/redefinition/admission bypass','installerSha256':install['installerSha256']};a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))

if __name__=='__main__':main()
