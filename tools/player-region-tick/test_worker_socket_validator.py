#!/usr/bin/env python3
"""Reject forged physical-close/current-scope and premature original join evidence."""
import argparse,copy,json
from pathlib import Path
from verify_worker_socket_close import verify

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);a=p.parse_args();root=a.server.resolve();events=[json.loads(l) for l in (root/'worker-socket-close/jdi.ndjson').read_text().splitlines()];verify(root,a.installer,a.artifact_manifest,events=events);controls=[]
 def control(name,mutate):
  rows=copy.deepcopy(events);mutate(rows)
  try:verify(root,a.installer,a.artifact_manifest,events=rows)
  except AssertionError as error:controls.append({'control':name,'status':'rejected','reason':str(error)})
  else:raise AssertionError('tampered socket-close corpus accepted '+name)
 control('no_physical_TCP_close',lambda r:r[3].update(actualConnectionOpen=True))
 control('active_scope_revoked_on_socket_loss',lambda r:r[3].update(originalStateScopeValid=False))
 control('original_effect_not_executed',lambda r:r[2].update(counterAfter=r[2]['counterBefore']))
 control('join_before_binding_retirement',lambda r:r[4].update(bindingReleased=False))
 control('missing_original_completed_join',lambda r:r.pop(4))
 control('socket_close_belongs_to_foreign_tick',lambda r:r[3].update(tick=r[3]['tick']+1))
 print(json.dumps({'status':'passed','negativeControls':len(controls),'controls':controls},indent=2))

if __name__=='__main__':main()
