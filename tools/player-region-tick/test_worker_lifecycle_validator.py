#!/usr/bin/env python3
"""Negative proof controls against an unmodified real production lifecycle corpus."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
from verify_worker_lifecycle import verify, lines, TOOLS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server',type=Path,required=True)
    parser.add_argument('--installer',type=Path,required=True)
    args=parser.parse_args(); root=args.server.resolve(); installer=args.installer.resolve()
    packets=lines(root/'worker-lifecycle/packets.ndjson')
    raw=[json.loads(s) for s in subprocess.check_output(['node',str(TOOLS/'decode_lifecycle_frames.cjs'),str(root/'worker-lifecycle/packets.ndjson')],text=True).splitlines()]
    verify(root,installer,packets=packets,raw=raw)
    marks={r['name']:r['seq'] for r in packets if r['direction']=='control'}
    controls=[]
    def control(name, mutate):
        p,w=copy.deepcopy((packets,raw));mutate(p,w)
        try: verify(root,installer,packets=p,raw=w)
        except AssertionError as error:controls.append({'control':name,'status':'rejected','reason':str(error)})
        else:raise AssertionError('tampered lifecycle corpus accepted: '+name)
    control('missing_ping_feedback',lambda p,w:w.__setitem__(slice(None),[r for r in w if r['name']!='ping_response']))
    def reorder_ping(p,w):
        r=next(r for r in w if r['name']=='ping_response');r['data']['id']=[0,9005]
    control('reordered_server_ping_id',reorder_ping)
    control('missing_raw_chat_feedback',lambda p,w:w.__setitem__(slice(None),[r for r in w if r['name'] not in ['system_chat','player_chat','profileless_chat']]))
    def payload_order(p,w):
        r=next(r for r in w if r['direction']=='frame_send' and r['name']=='custom_payload' and marks['network_before']<r['seq']<marks['network_after'])
        r['data']['channel']='minecraft:unregister'
    control('raw_register_unregister_order_changed',payload_order)
    control('missing_original_keepalive',lambda p,w:w.__setitem__(slice(None),[r for r in w if r['name']!='keep_alive']))
    def reply(p,w):
        for r in w:
            if r['name']=='keep_alive' and r['direction']=='frame_send':r['data']={'keepAliveId':[0,0]}
    control('wrong_keepalive_response_identity',reply)
    def walk(p,w):
        next(r for r in w if marks['walk_before']<r['seq']<marks['walk_after'] and r['direction']=='frame_send' and r['name']=='position_look')['data']['x']=47.5
    control('walk_without_actual_boundary_packet',walk)
    def duplicate(p,w):
        i=next(i for i,r in enumerate(w) if r['name']=='collect');w.insert(i,copy.deepcopy(w[i]))
    control('duplicate_collect_feedback',duplicate)
    def amount(p,w):next(r for r in w if r['name']=='collect')['data']['pickupItemCount']=99
    control('wrong_pickup_amount',amount)
    control('missing_raw_XP_feedback',lambda p,w:w.__setitem__(slice(None),[r for r in w if r['name']!='experience']))
    def corrupt(p,w):next(r for r in p if r['direction']=='frame_send')['data']['sha256']='0'*64
    control('corrupted_outgoing_raw_bytes',corrupt)
    if 'dimension_before' in marks:
        def missing_dimension(p,w):
            for row in p:
                if marks['dimension_before'] < row['seq'] < marks['dimension_after'] and row['bot']=='WorkerA' and row['direction']=='receive' and row['name']=='respawn':
                    row['direction']='decoded'
        control('missing_actual_dimension_feedback',missing_dimension)
        def raw_dimension(p,w):
            row=next(r for r in w if marks['dimension_before']<r['seq']<marks['dimension_after'] and r['name']=='respawn' and r['bot']=='WorkerA')
            row['data']['forged_dimension_field']=True
        control('raw_dimension_feedback_disagrees',raw_dimension)
    print(json.dumps({'status':'passed','positiveCorpus':'passed','negativeControls':len(controls),'controls':controls},indent=2))


if __name__=='__main__':main()
