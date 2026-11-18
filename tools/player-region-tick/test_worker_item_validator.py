#!/usr/bin/env python3
"""Adversarial proof mutations from real item corpus; originals always preserved."""
import argparse,base64,hashlib,json,subprocess,tempfile
from pathlib import Path
from verify_worker_item_pickup import verify
TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();source=a.server.resolve();installer=a.installer.resolve()
 positive=verify(source,installer);assert positive['requested'];out=source/'worker-item-pickup';controls=[]
 def rows(root,label,change):
  p=root/'worker-item-pickup'/('jdi-'+label+'.ndjson');r=[json.loads(x) for x in p.read_text().splitlines()];change(r)
  for i,x in enumerate(r,1):x['seq']=i
  p.unlink();p.write_text(''.join(json.dumps(x)+'\n' for x in r))
 def serial(r):
  for x in r:
   if 'thread' in x and 'Worker' in x['thread']:x['thread']='Server thread'
 def foreign(r):
  for x in r:
   if x['event'] in ['enter','pickup-pre','pickup-post','return']:x['thread']='NeoForge Region Worker #forged'
   if x['event']=='enter':x['stack'].append('net.neoforged.neoforge.server.threading.region.RegionPlayerTickDispatch.runOriginal:1')
 def alter(r,event,key,value):next(x for x in r if x['event']==event)[key]=value
 def raw(root,name):
  p=root/'worker-item-pickup/packets.ndjson';r=[json.loads(x) for x in p.read_text().splitlines()];marks={x['name']:x['seq'] for x in r if x['direction']=='control'}
  decoded=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_item_pickup_frames.cjs'),str(p)],text=True).splitlines()]
  picked=next(x for x in decoded if marks['isolated_before']<x['seq']<marks['isolated_after'] and x['bot']=='WorkerA' and x['direction']=='frame' and x['name']==name)
  row=r[picked['seq']-1];b=bytearray(base64.b64decode(row['data']['base64']));b[-1]^=1;row['data'].update(base64=base64.b64encode(b).decode(),sha256=hashlib.sha256(b).hexdigest(),bytes=len(b))
  p.unlink();p.write_text(''.join(json.dumps(x)+'\n' for x in r))
 cases=[('worker_missing',lambda p:rows(p,'isolated',serial)),('foreign_worker',lambda p:rows(p,'foreign-watcher',foreign)),('capacity_worker',lambda p:rows(p,'capacity',foreign)),('unknown_component_worker',lambda p:rows(p,'component',foreign)),('equipped_worker',lambda p:rows(p,'equipment',foreign)),
 ('pre_missing',lambda p:rows(p,'isolated',lambda r:r.remove(next(x for x in r if x['event']=='pickup-pre')))),('pre_result',lambda p:rows(p,'isolated',lambda r:alter(r,'pickup-pre','result','TRUE'))),('post_duplicate',lambda p:rows(p,'isolated',lambda r:r.insert(-1,dict(next(x for x in r if x['event']=='pickup-post'))))),('post_inventory',lambda p:rows(p,'isolated',lambda r:alter(r,'pickup-post','inventory',7))),('post_copy',lambda p:rows(p,'isolated',lambda r:alter(r,'pickup-post','originalCount',4))),
 ('storage_worker',lambda p:rows(p,'isolated',lambda r:alter(r,'storage-enter','thread','NeoForge Region Worker #invalid'))),('storage_retained',lambda p:rows(p,'isolated',lambda r:alter(r,'storage-return','levelCallbackClass','net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback'))),('xp_created',lambda p:rows(p,'isolated',lambda r:alter(r,'return','xp',1))),
 *[(f'raw_{name}',lambda p,n=name:raw(p,n)) for name in ['collect','entity_destroy','statistics']]]
 for name,change in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-item-proof-') as directory:
   root=Path(directory)
   for file in source.iterdir():
    if file.name!='worker-item-pickup':(root/file.name).symlink_to(file,target_is_directory=file.is_dir())
   target=root/'worker-item-pickup';target.mkdir()
   for file in out.iterdir():(target/file.name).symlink_to(file,target_is_directory=file.is_dir())
   change(root)
   try:verify(root,installer)
   except AssertionError as error:controls.append(dict(control=name,status='rejected',reason=str(error)))
   else:raise AssertionError('Corrupted item proof accepted '+name)
 print(json.dumps(dict(status='passed',negativeControls=len(controls),controls=controls),indent=2))

if __name__=='__main__':main()
