#!/usr/bin/env python3
"""Reject forged complete-body debugger effects, premature joins and missing physical retirement."""
import argparse,copy,json
from pathlib import Path
from verify_worker_fault import verify

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--installer',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);a=p.parse_args();root=a.server.resolve()
 events=[json.loads(l) for l in (root/'worker-fault/jdi.ndjson').read_text().splitlines()];verify(root,a.installer,a.artifact_manifest,events=events);controls=[]
 def control(name,mutate):
  rows=copy.deepcopy(events);mutate(rows)
  try:verify(root,a.installer,a.artifact_manifest,events=rows)
  except AssertionError as error:controls.append({'control':name,'status':'rejected','reason':str(error)})
  else:raise AssertionError('tampered worker fault accepted: '+name)
 control('missing_original_body_effect',lambda rows:rows.pop(2))
 control('counter_effect_was_not_originally_executed',lambda rows:rows[2].update(counterAfter=rows[2]['counterBefore']))
 control('foreign_worker_phase',lambda rows:rows[2].update(phase='FOREIGN'))
 control('failure_join_on_worker_instead_of_publisher',lambda rows:rows[4].update(thread=rows[1]['thread']))
 control('terminal_plan_retains_live_player_binding',lambda rows:rows[4].update(bindingReleased=False))
 def original_stack(rows):rows[2]['stack']=[s for s in rows[2]['stack'] if 'RegionPlayerTickDispatch.runOriginal:' not in s]
 control('side_task_instead_of_original_complete_call',original_stack)
 control('duplicate_injected_effect',lambda rows:rows.insert(3,copy.deepcopy(rows[2])))
 def queue(rows):
  d=json.loads(rows[5]['diagnostics']);d['network']['pending']=1;rows[5]['diagnostics']=json.dumps(d)
 control('live_retained_network_callback_after_shutdown',queue)
 print(json.dumps({'status':'passed','negativeControls':len(controls),'controls':controls},indent=2))

if __name__=='__main__':main()
