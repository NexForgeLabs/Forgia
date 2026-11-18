#!/usr/bin/env python3
"""Reject changed original menu/effect/synchronization proofs; accepted original corpus is immutable."""
import argparse,json,tempfile
from pathlib import Path
from verify_worker_shared_chest import verify

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();source=a.server.resolve();installer=a.installer.resolve();positive=verify(source,installer);assert positive['requested'];out=source/'worker-shared-chest';controls=[]
 def mutate_trace(root,change):
  f=root/'worker-shared-chest/jdi.ndjson';r=[json.loads(x) for x in f.read_text().splitlines()];change(r)
  for i,x in enumerate(r,1):x['seq']=i
  f.unlink();f.write_text(''.join(json.dumps(x)+'\n' for x in r))
 def serial(r):
  for x in r:
   if 'thread' in x and 'Worker' in x['thread']:x['thread']='Server thread'
 def original_stack(r):
  for x in r:
   if x['event']=='enter' and 'Worker' in x['thread']:x['stack']=[s for s in x['stack'] if 'RegionPlayerTickDispatch.runOriginal:' not in s]
 def chest_identity(r):
  for x in r:
   if x.get('actor')==r[0]['actors'][0] and 'chest' in x:x['chest']+=1
 def menu_identity(r):
  for x in r:
   if 'menu' in x:x['menu']=1
 def bad_return(r):next(x for x in r if x['event']=='return' and x['method']=='stillValid')['valid']=False
 def remove_zero(r):
  for x in r:
   if x.get('count')==0:x['count']=11
 def alter_result(root,kind):
  f=root/'worker-shared-chest/result.json';r=json.loads(f.read_text())
  if kind=='inventory':r['inventoryReads']['WorkerB']=r['inventoryReads']['WorkerB'].replace('count: 11','count: 12')
  elif kind=='owner':next(x for x in r['opened']['players'] if x['name']=='WorkerB')['regionX']=0
  elif kind=='client':r['clientsConfirmed']['WorkerB']['window']['items'][-1]['itemCount']=12
  f.unlink();f.write_text(json.dumps(r))
 def packet_serial(rows):
  for row in rows:
   if row.get('phase')=='PACKET' and 'thread' in row and 'Worker' in row['thread']:row['thread']='Server thread'
 def packet_stack(rows):
  for row in rows:
   if row.get('phase')=='PACKET' and row['event']=='enter':row['stack']=[f for f in row['stack'] if 'ServerGamePacketListenerImpl.handleContainerClick:' not in f]
 cases=[('owner_packet_worker_missing',lambda p:mutate_trace(p,packet_serial)),('owner_packet_original_handler_missing',lambda p:mutate_trace(p,packet_stack)),('worker_missing',lambda p:mutate_trace(p,serial)),('complete_stack_missing',lambda p:mutate_trace(p,original_stack)),('shared_chest_identity',lambda p:mutate_trace(p,chest_identity)),('distinct_menu_identity',lambda p:mutate_trace(p,menu_identity)),('synchronous_validity_false',lambda p:mutate_trace(p,bad_return)),('post_click_visibility_missing',lambda p:mutate_trace(p,remove_zero)),('open_event_missing',lambda p:mutate_trace(p,lambda r:r.remove(next(x for x in r if x['event']=='open-event')))),('close_event_duplicate',lambda p:mutate_trace(p,lambda r:r.insert(-1,dict(next(x for x in r if x['event']=='close-event'))))),*[(kind,lambda p,k=kind:alter_result(p,k)) for kind in ['inventory','owner','client']]]
 if not any(x.get('protocol')==2 for x in map(json.loads,(out/'jdi.ndjson').read_text().splitlines())):cases=[c for c in cases if not c[0].startswith('owner_packet_')]
 for name,change in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-shared-chest-') as directory:
   root=Path(directory)
   for f in source.iterdir():
    if f.name!='worker-shared-chest':(root/f.name).symlink_to(f,target_is_directory=f.is_dir())
   target=root/'worker-shared-chest';target.mkdir()
   for f in out.iterdir():(target/f.name).symlink_to(f,target_is_directory=f.is_dir())
   change(root)
   try:verify(root,installer)
   except AssertionError as error:controls.append(dict(control=name,status='rejected',reason=str(error)))
   else:raise AssertionError('Corrupt shared chest proof accepted '+name)
 print(json.dumps(dict(status='passed',negativeControls=len(controls),controls=controls),indent=2))
if __name__=='__main__':main()
