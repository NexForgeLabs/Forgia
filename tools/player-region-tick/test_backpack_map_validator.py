#!/usr/bin/env python3
"""Proof-tool controls from actual original map ticks and client feedback."""
import argparse,base64,hashlib,json,subprocess,tempfile
from pathlib import Path
from verify_worker_backpack_map import verify
TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();source=a.server.resolve();installer=a.installer.resolve();positive=verify(source,installer);assert positive['requested'];out=source/'worker-backpack-map';controls=[]
 def trace(root,change):
  p=root/'worker-backpack-map/jdi.ndjson';r=[json.loads(x) for x in p.read_text().splitlines()];change(r)
  for i,x in enumerate(r,1):x['seq']=i
  p.unlink();p.write_text(''.join(json.dumps(x)+'\n' for x in r))
 def serial(r):
  for x in r:
   if 'thread' in x and 'Worker' in x['thread']:x['thread']='Server thread'
 def pixel(r):
  for x in r:
   if 'colorsSha256' in x:x['colorsSha256']='0'*64
 def wrong_map(r):next(x for x in r if x['event']=='return')['map']+=1
 def no_rotation(r):
  for x in r:
   if 'decoration' in x:x['decoration']['rot']=0
 def no_original(r):
  for x in r:
   if x['event']=='enter':x['stack']=[s for s in x['stack'] if 'Connection.tick:' not in s]
 def raw(root):
  p=root/'worker-backpack-map/packets.ndjson';r=[json.loads(x) for x in p.read_text().splitlines()];decoded=[json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_map_frames.cjs'),str(p)],text=True).splitlines()]
  row=next(x for x in decoded if x['name']=='map' and x['data'].get('icons') and x['data']['icons'][0]['direction']==4);target=r[row['seq']-1];b=bytearray(base64.b64decode(target['data']['base64']));assert b[-3]==4;b[-3]=5;target['data'].update(base64=base64.b64encode(b).decode(),sha256=hashlib.sha256(b).hexdigest(),bytes=len(b))
  p.unlink();p.write_text(''.join(json.dumps(x)+'\n' for x in r))
 def created_id(root):
  p=root/'worker-backpack-map/result.json';r=json.loads(p.read_text());r['createdItem']['components'][0]['data']=1;p.unlink();p.write_text(json.dumps(r))
 cases=[('worker_missing',lambda p:trace(p,serial)),('original_pixels_changed',lambda p:trace(p,pixel)),('foreign_map_instance',lambda p:trace(p,wrong_map)),('foreign_carrier',lambda p:trace(p,lambda r:next(x for x in r if x['event']=='return').update(carriers=2))),('rotation_missing',lambda p:trace(p,no_rotation)),('original_stack_missing',lambda p:trace(p,no_original)),('original_return_missing',lambda p:trace(p,lambda r:r.remove(next(x for x in r if x['event']=='return')))),('terrain_update_entered',lambda p:trace(p,lambda r:r.insert(1,dict(event='terrain-update',actor=r[0]['actor'],thread='NeoForge Region Worker #invalid')))),('raw_rotation_changed',raw),('invalid_created_map_id',created_id)]
 for name,change in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-map-proof-') as directory:
   root=Path(directory)
   for f in source.iterdir():
    if f.name!='worker-backpack-map':(root/f.name).symlink_to(f,target_is_directory=f.is_dir())
   target=root/'worker-backpack-map';target.mkdir()
   for f in out.iterdir():(target/f.name).symlink_to(f,target_is_directory=f.is_dir())
   change(root)
   try:verify(root,installer)
   except AssertionError as error:controls.append(dict(control=name,status='rejected',reason=str(error)))
   else:raise AssertionError('Corrupt map proof accepted '+name)
 print(json.dumps(dict(status='passed',negativeControls=len(controls),controls=controls),indent=2))
if __name__=='__main__':main()
