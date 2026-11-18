#!/usr/bin/env python3
"""Mutate copies in memory; original gameplay, saves, packets and failed proofs stay intact."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
from verify_player_operations import verify, require, TOOLS


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server',type=Path,required=True);parser.add_argument('--installer',type=Path,required=True)
    args=parser.parse_args();root=args.server.resolve();installer=args.installer.resolve();output=root/'player-operations'
    target=output/'proof-negative-controls.json';require(not target.exists(),'Preserve previous operation controls')
    positive=verify(root,installer)
    original=json.loads((output/'result.json').read_text())
    wire=[json.loads(line) for line in subprocess.check_output(
        ['node',str(TOOLS/'decode_player_operations.cjs'),str(output/'packets.ndjson')],text=True).splitlines()]
    controls=[]
    def reject(label,result_change=None,wire_change=None):
        result=copy.deepcopy(original);packets=copy.deepcopy(wire)
        if result_change:result_change(result)
        if wire_change:wire_change(packets)
        try:verify(root,installer,result_override=result,wire_override=packets)
        except AssertionError as error:controls.append(dict(control=label,status='rejected',reason=str(error)))
        else:raise AssertionError('Corrupt operation proof accepted '+label)
    reject('missing_operation',result_change=lambda r:r['operations'].pop(0))
    reject('missing_real_attack',wire_change=lambda rows:rows.remove(next(p for p in rows if p['direction']=='frame_send' and p['name']=='use_entity' and p['data']['mouse']==1)))
    reject('missing_victim_feedback',wire_change=lambda rows:rows.remove(next(p for p in rows if p['direction']=='frame' and p['bot']=='Scale01' and p['name']=='damage_event')))
    reject('wrong_region_owner',result_change=lambda r:r['operations'][0]['before']['state']['players'][0].update(regionX=999))
    reject('invented_resource_total',result_change=lambda r:r['cycles'][0]['afterResources']['Scale00'].update(Inventory='invented 17 diamonds'))
    reject('missing_shared_click',wire_change=lambda rows:rows.remove(next(p for p in rows if p['direction']=='frame_send' and p['name']=='window_click')))
    reject('missing_normal_mount',wire_change=lambda rows:rows.remove(next(p for p in rows if p['direction']=='frame_send' and p['name']=='use_entity' and p['data']['mouse']==0 and p['data']['target']!=next(q for q in rows if q['direction']=='frame_send' and q['name']=='use_entity' and q['data']['mouse']==0)['data']['target'])))
    reject('missing_dismount_feedback',wire_change=lambda rows:rows.__setitem__(slice(None),[p for p in rows if not(p['direction']=='frame' and p['name']=='set_passengers' and not p['data']['passengers'])]))
    reject('missing_teleport_confirmation',wire_change=lambda rows:rows.__setitem__(slice(None),[p for p in rows if not(p['direction']=='frame_send' and p['name']=='teleport_confirm')]))
    reject('logout_queue_retained',result_change=lambda r:r['cycles'][0]['empty']['network'].update(pending=1))
    reject('missing_recovery_cycle',result_change=lambda r:r['cycles'].pop())
    reject('failed_server_exit',result_change=lambda r:r.update(serverExits=[0,1]))
    if original.get('schema',1)>=2:
        reject('missing_dimension_feedback',wire_change=lambda rows:rows.remove(next(p for p in rows if p['direction']=='frame' and p['name']=='respawn')))
    if original.get('schema',1)>=3:
        def stale_inventory(r):
            op=next(op for op in r['operations'] if op['name']=='shared_chest_race')
            next(v for v in op['after']['clients']['Scale00']['window']['items'] if v.get('itemId')==805)['itemCount']+=1
        reject('stale_or_duplicated_client_inventory',result_change=stale_inventory)
        reject('missing_reopened_inventory_feedback',wire_change=lambda rows:rows.__setitem__(slice(None),[p for p in rows if not(p['direction']=='frame' and p['name']=='window_items')]))
    target.write_text(json.dumps(dict(status='passed',positive=positive['status'],negativeControls=len(controls),controls=controls),indent=2)+'\n')
    print(json.dumps(dict(status='passed',negativeControls=len(controls))))


if __name__=='__main__':main()
