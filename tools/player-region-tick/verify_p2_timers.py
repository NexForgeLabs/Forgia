#!/usr/bin/env python3
"""Independent normal floating/idle wire feedback and original cleanup checks."""
import argparse,base64,hashlib,json,subprocess
from pathlib import Path
from reviewed_artifacts import read_manifest
TOOLS=Path(__file__).resolve().parent

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,manifest,raw=None):
 out=root/'p2-timers';install=json.load(open(root/'installation.json'));result=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(result['status']=='passed' and result['serverExits']==[0] and result['clientsExit']==0,'original timers run/process failed')
 require(install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')),'timers require original production environment')
 require(install['version']==m['version'] and install['installerSha256']==m['installerSha256'],'wrong timers core')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'timers installed core changed')
 for name,digest in result['sources'].items():require(hashlib.sha256((out/name).read_bytes()).hexdigest()==digest,'timers source snapshot changed')
 commands=[json.loads(l) for l in (out/'commands.ndjson').read_text().splitlines()];original=[json.loads(r['response']) for r in commands if r['command']=='neoforge regionthreading status players']
 states=[]
 for p in out.glob('*.json'):
  state=json.load(open(p))
  if 'players' not in state:continue
  require(state in original,'invented original timer state')
  require(state['activeBatches']==state['stateClaims']==state['phases']['pending']==state['phases']['running']==0 and not state['network']['frozen'] and state['network']['pending']==state['network']['readers']==state['network']['terminals']==0,'timer lifecycle leaked bookkeeping')
  require(all(x['failed']==0 for x in state['phases']['phases'].values()),'normal timer caused original body failure')
  if not install['playerPlanningRequested']:require(all(x['worker']==0 for x in state['phases']['phases'].values()),'off timer entered worker')
  states.append(state)
 stable=json.load(open(out/'stable.json'));floating=json.load(open(out/'floating-input.json'));final=json.load(open(out/'final.json'))
 if install['playerPlanningRequested']:require(all(x['worker']>=10 for x in stable['phases']['phases'].values()),'timers never began from original dual worker branch')
 require(next(p for p in floating['players'] if p['name']=='WorkerA')['y']==104,'floating packet did not move the actual player above support')
 require(not final['players'] and final['phases']['owners']==0,'timed out sessions retained live owners')
 packets=[json.loads(l) for l in (out/'packets.ndjson').read_text().splitlines()]
 require([x['seq'] for x in packets]==list(range(1,len(packets)+1)),'truncated timer capture')
 for row in packets:
  if row['direction'] in ['frame','frame_send']:
   b=base64.b64decode(row['data']['base64'],validate=True);require(len(b)==row['data']['bytes'] and hashlib.sha256(b).hexdigest()==row['data']['sha256'],'corrupt timer raw frame')
 raw=raw if raw is not None else [json.loads(l) for l in subprocess.check_output(['node',str(TOOLS/'decode_protocol_frames.cjs'),str(out/'packets.ndjson')],text=True).splitlines()]
 kicks=[r for r in raw if r['direction']=='frame' and r['name']=='kick_disconnect'];require(len(kicks)==2,'normal timer did not cause exactly two real disconnect packets')
 reasons={'WorkerA':'multiplayer.disconnect.flying','WorkerB':'multiplayer.disconnect.idling'}
 for name,reason in reasons.items():
  actual=[r for r in kicks if r['bot']==name];require(len(actual)==1 and reason in json.dumps(actual[0]['data']),'wrong original timer disconnect reason '+name)
 mark=next(r for r in packets if r['direction']=='control' and r['name']=='timers_before')
 idle=next(r for r in kicks if r['bot']=='WorkerB');require(int(idle['nano'])-int(mark['nano'])>=50_000_000_000,'idle timeout was synthesized instead of waiting original minute')
 moves=[r for r in raw if r['bot']=='WorkerA' and r['direction']=='frame_send' and r['name']=='position_look' and r['seq']>mark['seq']]
 require(len(moves)==16 and all(not r['data']['onGround'] for r in moves) and moves[-1]['data']['y']==104,'floating input did not use original normal movement packets')
 log=(out/'boot-1.server.log').read_text();require('was kicked for floating too long' in log and not any(token in log for token in ['Failed to handle packet','Ticking entity','Error executing task']),'original floating timer report/body mismatch')
 return {'status':'passed','persistent':install['persistent'],'requested':install['playerPlanningRequested'],'floatingDisconnectPackets':1,'idleDisconnectPackets':1,'idleObservedSeconds':(int(idle['nano'])-int(mark['nano']))/1e9,'normalMovementPackets':16,'scope':'Real vanilla floating and one-minute idle timers; no Mod, no accelerated clock, no performance claim'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'p2-timers/independent-verdict.json';require(not out.exists(),'preserve prior timer verdict')
 try:r=verify(a.server.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
