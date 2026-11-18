#!/usr/bin/env python3
"""Mutate real full original damage evidence; accepted files remain immutable."""
import argparse,base64,hashlib,json,subprocess,tempfile
from pathlib import Path
from verify_worker_starvation_damage import verify
TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser();p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();source=a.server.resolve();installer=a.installer.resolve();positive=verify(source,installer);assert positive['workerDamage'];out=source/'worker-starvation-damage';controls=[]
 def trace(root,change):
  f=root/'worker-starvation-damage/jdi.ndjson';r=[json.loads(x) for x in f.read_text().splitlines()];change(r)
  for i,x in enumerate(r,1):x['seq']=i
  f.unlink();f.write_text(''.join(json.dumps(x)+'\n' for x in r))
 def serial(r):
  for x in r:
   if 'thread' in x:x['thread']='Server thread'
 def missing_stack(r):r[1]['stack']=[x for x in r[1]['stack'] if 'FoodData.tick:' not in x]
 def raw(root,kind):
  f=root/'worker-starvation-damage/packets.ndjson';r=[json.loads(x) for x in f.read_text().splitlines()];marks={x['name']:x['seq'] for x in r if x['direction']=='control'}
  parsed=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_damage_frames.cjs'),str(f)],text=True).splitlines()];x=next(x for x in parsed if marks['damage_before']<x['seq']<marks['damage_after'] and x['direction']=='frame' and x['name']==kind);row=r[x['seq']-1];b=bytearray(base64.b64decode(row['data']['base64']));b[1]^=1;row['data'].update(base64=base64.b64encode(b).decode(),sha256=hashlib.sha256(b).hexdigest(),bytes=len(b));f.unlink();f.write_text(''.join(json.dumps(x)+'\n' for x in r))
 cases=[('worker_missing',lambda p:trace(p,serial)),('original_food_body_missing',lambda p:trace(p,missing_stack)),('wrong_source',lambda p:trace(p,lambda r:r[1].update(source='generic'))),('wrong_damage_amount',lambda p:trace(p,lambda r:r[1].update(amount=2))),('false_original_result',lambda p:trace(p,lambda r:r[-2].update(result=False))),('wrong_final_health',lambda p:trace(p,lambda r:r[-2].update(health=18))),('post_event_missing',lambda p:trace(p,lambda r:r.remove(next(x for x in r if x.get('method')=='onLivingDamagePost')))),('game_event_missing',lambda p:trace(p,lambda r:r.remove(next(x for x in r if x['event']=='game-event')))),('sound_missing',lambda p:trace(p,lambda r:r.remove(next(x for x in r if x['event']=='sound-broadcast')))),('original_pre_result',lambda p:trace(p,lambda r:next(x for x in r if x['event']=='hook-return' and x['method']=='onLivingDamagePre').update(result=2))),('raw_health_changed',lambda p:raw(p,'update_health'))]
 for name,change in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-damage-proof-') as directory:
   root=Path(directory)
   for f in source.iterdir():
    if f.name!='worker-starvation-damage':(root/f.name).symlink_to(f,target_is_directory=f.is_dir())
   target=root/'worker-starvation-damage';target.mkdir()
   for f in out.iterdir():(target/f.name).symlink_to(f,target_is_directory=f.is_dir())
   change(root)
   try:verify(root,installer)
   except AssertionError as error:controls.append(dict(control=name,status='rejected',reason=str(error)))
   else:raise AssertionError('Corrupt original damage proof accepted '+name)
 print(json.dumps(dict(status='passed',negativeControls=len(controls),controls=controls),indent=2))
if __name__=='__main__':main()
