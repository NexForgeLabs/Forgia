#!/usr/bin/env python3
"""Adversarial copies of original P4 proof. Retained raw corpus is never modified."""
import argparse,base64,hashlib,json,tempfile
from pathlib import Path
from verify_owner_packet import verify
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();source=a.server.resolve();installer=a.installer.resolve();positive=verify(source,installer);assert positive['requested'];controls=[]
 def change(root,name,mutate):
  path=root/'owner-packet'/name
  if name.endswith('.ndjson'):r=[json.loads(x) for x in path.read_text().splitlines()];mutate(r);text=''.join(json.dumps(x)+'\n' for x in r)
  else:r=json.loads(path.read_text());mutate(r);text=json.dumps(r)+'\n'
  path.unlink();path.write_text(text)
 def serial(rows):
  for row in rows:
   if 'thread' in row and 'Worker' in row['thread']:row['thread']='Server thread'
 def foreign_fence(rows):
  next(r for r in rows if r.get('kind')=='stats')['thread']='NeoForge Region Worker #forged'
 def swapped_slot(rows):
  next(r for r in rows if r['event']=='return' and r.get('kind')=='select')['selectedAfter']=2
 def side_task(rows):
  for row in rows:
   if row['event']=='enter' and 'Worker' in row['thread']:row['stack']=[x for x in row['stack'] if 'ServerboundSetCarriedItemPacket.handle:' not in x]
 def corrupt_wire(rows):
  for row in rows:
   if row['direction']!='frame_send' or row['data']['state']!='play':continue
   data=bytearray(base64.b64decode(row['data']['base64']))
   if data[0]!=0x2f:continue
   data[-1]=8;row['data'].update(base64=base64.b64encode(data).decode(),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data));return
  raise AssertionError('No actual original held slot frame')
 cases=[('worker_missing','jdi.ndjson',serial),('unknown_stats_moved_to_worker','jdi.ndjson',foreign_fence),('original_selected_effect_wrong','jdi.ndjson',swapped_slot),('side_task_no_original_handler','jdi.ndjson',side_task),('raw_slot_packet_forged','packets.ndjson',corrupt_wire),('original_sequence_missing','jdi.ndjson',lambda r:r.pop(4)),('server_slot_forged','result.json',lambda r:r.update(selectedAfter=r['selectedBefore'])),('inventory_duplicated','result.json',lambda r:r.update(inventoryAfter=r['inventoryAfter'].replace('count: 1','count: 2'))),('diagnostic_worker_count_forged','result.json',lambda r:r['after']['packets'].update(workers=9999))]
 for name,file,mutate in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-owner-packet-proof-') as temp:
   root=Path(temp)
   for f in source.iterdir():
    if f.name!='owner-packet':(root/f.name).symlink_to(f,target_is_directory=f.is_dir())
   out=root/'owner-packet';out.mkdir()
   for f in (source/'owner-packet').iterdir():(out/f.name).symlink_to(f,target_is_directory=f.is_dir())
   change(root,file,mutate)
   try:verify(root,installer)
   except AssertionError as error:controls.append(dict(control=name,status='rejected',reason=str(error)))
   else:raise AssertionError('Tampered owner packet proof accepted '+name)
 print(json.dumps(dict(status='passed',negativeControls=len(controls),controls=controls),indent=2))
if __name__=='__main__':main()
