#!/usr/bin/env python3
"""Mutate isolated copies of real XP proof inputs; original corpora are never edited."""
import argparse,base64,hashlib,json,subprocess,tempfile
from pathlib import Path
from verify_worker_xp_removal import verify

TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args()
 source=a.server.resolve();installer=a.installer.resolve();positive=verify(source,installer)
 assert positive['requested'] and positive['status']=='passed','Use the on-mode positive original worker corpus'
 out=source/'worker-xp-removal';controls=[]
 def mutate_trace(root,label,change):
  f=root/'worker-xp-removal'/('jdi-'+label+'.ndjson');rows=[json.loads(x) for x in f.read_text().splitlines()];change(rows);f.unlink();f.write_text(''.join(json.dumps(x)+'\n' for x in rows))
 def make_serial(rows):
  for row in rows:
   if 'thread' in row and 'Worker' in row['thread']:row['thread']='Server thread'
 def make_worker(rows,final):
  current=None
  for i,row in enumerate(rows):
   if row['event']=='enter':current=i
   if row['event']=='return' and row['countBefore']-row['countAfter']==1 and (not final or row['countAfter']==0):
    row['thread']=rows[current]['thread']='NeoForge Region Worker #mutated'
    rows[current]['stack'].append('net.neoforged.neoforge.server.threading.region.RegionPlayerTickDispatch.runOriginal:1')
    return
  raise AssertionError('Missing original effect to mutate')
 def duplicate_xp(rows):
  row=next(x for x in rows if x['event']=='return' and x['countBefore']-x['countAfter']==1);row['xpAfter']+=1
 def omit_original_stack(rows):
  for row in rows:
   if row['event']=='enter' and 'Worker' in row['thread']:row['stack']=[x for x in row['stack'] if 'ServerPlayer.doTick:' not in x]
 def remove_return(rows):
  i=next(i for i,r in enumerate(rows) if r['event']=='return' and 'Worker' in r['thread']);rows.pop(i)
  for i,r in enumerate(rows,1):r['seq']=i
 def change_position(root):
  f=root/'worker-xp-removal/result.json';r=json.loads(f.read_text());r['cases'][0]['initialOrbRead']=r['cases'][0]['initialOrbRead'].replace('48.5d','40.5d');f.unlink();f.write_text(json.dumps(r))
 def change_identity(rows):
  for r in rows:
   if 'orb' in r:r['orb']='00000000-0000-0000-0000-000000000001'
 def corrupt_raw(root,kind):
  f=root/'worker-xp-removal/packets.ndjson';rows=[json.loads(x) for x in f.read_text().splitlines()]
  marks={r['name']:r['seq'] for r in rows if r['direction']=='control'}
  raw=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_xp_removal_frames.cjs'),str(f)],text=True).splitlines()]
  candidates=[r for r in raw if marks['isolated_before']<r['seq']<marks['isolated_after'] and r['bot']=='WorkerA' and r['direction']=='frame' and r['name']==kind]
  picked=candidates[0] if kind=='collect' else candidates[-1];row=rows[picked['seq']-1];b=bytearray(base64.b64decode(row['data']['base64']))
  b[-1]=(b[-1]^1) if kind=='entity_destroy' else (0 if kind=='collect' else 20)
  row['data'].update(base64=base64.b64encode(b).decode(),sha256=hashlib.sha256(b).hexdigest(),bytes=len(b))
  f.unlink();f.write_text(''.join(json.dumps(r)+'\n' for r in rows))
 def storage_change(rows,kind):
  entry=next(x for x in rows if x['event']=='storage-enter');ret=next(x for x in rows if x['event']=='storage-return')
  if kind=='thread':entry['thread']='NeoForge Region Worker #invalid'
  elif kind=='retained':ret['levelCallbackClass']='net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback'
  elif kind=='identity':entry['orb']='00000000-0000-0000-0000-000000000001'
  elif kind=='duplicate':rows.insert(-1,dict(ret))
  elif kind=='order':
   rows.remove(entry);rows.insert(next(i for i,x in enumerate(rows) if x['event']=='return' and x['countAfter']==0),entry)
  elif kind=='missing':rows.remove(ret)
  for i,row in enumerate(rows,1):row['seq']=i
 cases=[
  ('wrong_server_orb_position',change_position),
  ('wrong_server_orb_identity',lambda r:mutate_trace(r,'isolated',change_identity)),
  ('worker_effect_missing',lambda r:mutate_trace(r,'isolated',make_serial)),
  ('single_worker_effect_missing',lambda r:mutate_trace(r,'single',make_serial)),
  ('foreign_connection_on_worker',lambda r:mutate_trace(r,'foreign-watcher',lambda rows:make_worker(rows,False))),
  ('xp_duplicated_inside_body',lambda r:mutate_trace(r,'isolated',duplicate_xp)),
  ('original_complete_body_stack_missing',lambda r:mutate_trace(r,'isolated',omit_original_stack)),
  ('original_return_missing',lambda r:mutate_trace(r,'isolated',remove_return)),
  ('raw_collect_amount_changed',lambda r:corrupt_raw(r,'collect')),
  ('raw_final_xp_changed',lambda r:corrupt_raw(r,'experience')),
  ('raw_final_remove_changed',lambda r:corrupt_raw(r,'entity_destroy')),
  *[(f'storage_{kind}',lambda r,k=kind:mutate_trace(r,'isolated',lambda rows:storage_change(rows,k))) for kind in ['thread','retained','identity','duplicate','order','missing']],
 ]
 for name,change in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-xp-proof-') as tmp:
   root=Path(tmp)
   for file in source.iterdir():
    if file.name=='worker-xp-removal':continue
    (root/file.name).symlink_to(file,target_is_directory=file.is_dir())
   target=root/'worker-xp-removal';target.mkdir()
   for file in out.iterdir():(target/file.name).symlink_to(file,target_is_directory=file.is_dir())
   change(root)
   try:verify(root,installer)
   except AssertionError as error:controls.append({'control':name,'status':'rejected','reason':str(error)})
   else:raise AssertionError('Corrupted XP proof was accepted: '+name)
 print(json.dumps({'status':'passed','positiveCorpus':positive['status'],'negativeControls':len(controls),'controls':controls},indent=2))

if __name__=='__main__':main()
