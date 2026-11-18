#!/usr/bin/env python3
"""Adversarial mutations of original score/dirty/XP evidence, without changing accepted corpus."""
import argparse,json,tempfile
from pathlib import Path
from verify_worker_scoreboard import verify

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);a=p.parse_args();source=a.server.resolve();installer=a.installer.resolve();positive=verify(source,installer);assert positive['requested'];out=source/'worker-scoreboard';controls=[]
 def mutate(root,change):
  f=root/'worker-scoreboard/jdi.ndjson';r=[json.loads(x) for x in f.read_text().splitlines()];change(r)
  for i,x in enumerate(r,1):x['seq']=i
  f.unlink();f.write_text(''.join(json.dumps(x)+'\n' for x in r))
 def serial(r):
  for x in r:
   if 'thread' in x and 'Worker' in x['thread']:x['thread']='Server thread'
 def foreign_board(r):
  for x in r:
   if x.get('actor')==r[0]['actors'][0] and 'board' in x:x['board']+=1
 def extra_xp(r):
  for x in r:
   if x.get('objective')=='p3_xp' and x.get('value')==21:x['value']=22
 def missing_stack(r):
  for x in r:
   if x['event']=='score-enter':x['stack']=[s for s in x['stack'] if '.forAllObjectives' not in s]
 def foreign_saved(r):next(x for x in r if x['event']=='score-return')['savedData']+=1
 cases=[('worker_missing',serial),('shared_board_identity',foreign_board),('xp_duplicated',extra_xp),('original_traversal_missing',missing_stack),('foreign_saved_callback',foreign_saved),('dirty_false',lambda r:next(x for x in r if x['event']=='dirty-return').update(dirty=False)),('dirty_missing',lambda r:r.remove(next(x for x in r if x['event']=='dirty-return'))),('dirty_duplicate',lambda r:r.insert(2,dict(next(x for x in r if x['event']=='dirty-return')))),('return_missing',lambda r:r.remove(next(x for x in r if x['event']=='score-return')))]
 for name,change in cases:
  with tempfile.TemporaryDirectory(prefix='forgia-score-proof-') as directory:
   root=Path(directory)
   for f in source.iterdir():
    if f.name!='worker-scoreboard':(root/f.name).symlink_to(f,target_is_directory=f.is_dir())
   target=root/'worker-scoreboard';target.mkdir()
   for f in out.iterdir():(target/f.name).symlink_to(f,target_is_directory=f.is_dir())
   mutate(root,change)
   try:verify(root,installer)
   except AssertionError as error:controls.append(dict(control=name,status='rejected',reason=str(error)))
   else:raise AssertionError('Corrupt score proof accepted '+name)
 print(json.dumps(dict(status='passed',negativeControls=len(controls),controls=controls),indent=2))
if __name__=='__main__':main()
