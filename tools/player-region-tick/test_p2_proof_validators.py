#!/usr/bin/env python3
"""Adversarial proof controls for the P2 original-hook/network/transition/timer verifiers."""
import argparse,copy,json,subprocess
from pathlib import Path
TOOLS=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--suite',choices=['phase','network','transition','timer'],required=True);p.add_argument('--server',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);a=p.parse_args();root=a.server.resolve();controls=[]
 def control(label,baseline,mutate,validate):
  changed=copy.deepcopy(baseline);mutate(changed)
  try:validate(changed)
  except AssertionError as error:controls.append({'control':label,'status':'rejected','reason':str(error)})
  else:raise AssertionError('Tampered P2 proof accepted: '+label)
 def raw(sub):return [json.loads(x) for x in subprocess.check_output(['node',str(TOOLS/'decode_protocol_frames.cjs'),str(root/sub/'packets.ndjson')],text=True).splitlines()]
 if a.suite=='phase':
  from verify_worker_phase_order import verify
  rows=[json.loads(x) for x in (root/'worker-phase-order/jdi.ndjson').read_text().splitlines()];verify(root,a.artifact_manifest,rows)
  check=lambda data:verify(root,a.artifact_manifest,data)
  for kind in ['firePlayerTickPre','firePlayerTickPost','BE_ENTER','fireEntityTickPost','fireLevelTickPost']:
   control('missing_original_'+kind,rows,lambda data,k=kind:next(r for r in data if r['event']==k).update(event='OTHER'),check)
  control('PlayerTick_side_task',rows,lambda data:next(r for r in data if r['event']=='firePlayerTickPre').update(stack=[]),check)
  control('PlayerTick_wrong_thread',rows,lambda data:next(r for r in data if r['event']=='firePlayerTickPre').update(thread='foreign executor'),check)
  if any(r.get('emptyTimeBefore',0)>=300 for r in rows):
   control('inactive_BE_skip_without_original_eligibility',rows,lambda data:next(r for r in data if r.get('emptyTimeBefore',0)>=300).update(emptyTimeBefore=0),check)
 elif a.suite=='network':
  from verify_network_fence import verify
  rows=[json.loads(x) for x in (root/'network-fence/jdi.ndjson').read_text().splitlines()];wire=raw('network-fence');verify(root,a.artifact_manifest,rows,wire)
  check=lambda data:verify(root,a.artifact_manifest,data,wire)
  control('missing_original_effect',rows,lambda data:data[2].update(counterAfter=data[2]['counterBefore']),check)
  control('network_fence_open',rows,lambda data:data[3].update(frozen=False),check)
  control('callbacks_bypassed_queue',rows,lambda data:data[3].update(queued=0),check)
  control('binding_released_before_real_join',rows,lambda data:data[4].update(bindingReleased=False),check)
  check=lambda data:verify(root,a.artifact_manifest,rows,data)
  control('accepted_ping_reply_missing',wire,lambda data:next(r for r in data if r['name']=='ping_response').update(name='OTHER'),check)
  control('accepted_ping_reply_forged',wire,lambda data:next(r for r in data if r['name']=='ping_response').update(data={'id':'777'}),check)
  if json.load(open(root/'network-fence/inputs.json'))['overflow']:
   control('overflow_disconnect_missing',wire,lambda data:next(r for r in data if r['name']=='kick_disconnect').update(name='OTHER'),check)
 elif a.suite=='transition':
  from verify_p2_matrix import verify
  wire=raw('p2-matrix');verify(root,a.artifact_manifest,wire)
  check=lambda data:verify(root,a.artifact_manifest,data)
  control('actual_credits_missing',wire,lambda data:next(r for r in data if r['name']=='game_state_change' and r['data'].get('reason')=='win_game').update(name='OTHER'),check)
  control('teleport_confirmation_missing',wire,lambda data:next(r for r in data if r['bot']=='WorkerA' and r['direction']=='frame_send' and r['name']=='teleport_confirm').update(name='OTHER'),check)
  control('async_chat_missing',wire,lambda data:next(r for r in data if r['bot']=='WorkerA' and r['direction']=='frame' and r['name'] in ['player_chat','profileless_chat','system_chat'] and 'p2_async_0' in json.dumps(r['data'])).update(name='OTHER'),check)
  if (root/'p2-contract.ndjson').exists():
   rows=[json.loads(x) for x in (root/'p2-contract.ndjson').read_text().splitlines()];check=lambda data:verify(root,a.artifact_manifest,wire,data)
   control('main_payload_moved_to_worker',rows,lambda data:next(r for r in data if r['event']=='payload_enter').update(serverThread=False),check)
   control('enqueueWork_no_longer_inline',rows,lambda data:next(r for r in data if r['event']=='enqueue_inline').update(event='DELAYED'),check)
   control('lost_teleport_cancellation',rows,lambda data:next(r for r in data if r['event']=='teleport_command' and 'cancelled=true' in r['detail']).update(detail='cancelled=false'),check)
 else:
  from verify_p2_timers import verify
  wire=raw('p2-timers');verify(root,a.artifact_manifest,wire);check=lambda data:verify(root,a.artifact_manifest,data)
  control('floating_disconnect_missing',wire,lambda data:next(r for r in data if r['name']=='kick_disconnect' and r['bot']=='WorkerA').update(name='OTHER'),check)
  control('idle_reason_forged',wire,lambda data:next(r for r in data if r['name']=='kick_disconnect' and r['bot']=='WorkerB').update(data={'reason':'foreign'}),check)
  control('idle_clock_shortcut',wire,lambda data:next(r for r in data if r['name']=='kick_disconnect' and r['bot']=='WorkerB').update(nano='0'),check)
 print(json.dumps({'status':'passed','suite':a.suite,'negativeControls':len(controls),'controls':controls},indent=2))

if __name__=='__main__':main()
