#!/usr/bin/env python3
"""Adversarial proof controls against a retained real schema-3 matrix corpus."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
from verify_interactions import verify, load_lines, TOOLS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--artifact-manifest', type=Path)
    args = parser.parse_args()
    root = args.server.resolve()
    trace = load_lines(root / 'player-oracle.ndjson')
    packets = load_lines(root / 'interaction-run/packets.ndjson')
    result = json.loads((root / 'interaction-run/result.json').read_text())
    raw = [json.loads(line) for line in subprocess.check_output([
        'node', str(TOOLS / 'decode_interaction_frames.cjs'), str(root / 'interaction-run/packets.ndjson')], text=True).splitlines()]
    verify(root, trace=trace, packets=packets, result=result, raw=raw, artifact_manifest=args.artifact_manifest)
    controls = []

    def control(name, mutate):
        t, p, r, wire = copy.deepcopy((trace, packets, result, raw))
        mutate(t, p, r, wire)
        # Keep sequence continuity; missing evidence must fail a substantive check.
        for index,row in enumerate(t,1): row['seq'] = index
        mapping = {row['seq']:i for i,row in enumerate(p,1)}
        for row in wire: row['seq'] = mapping.get(row['seq'], -1)
        for index,row in enumerate(p,1): row['seq'] = index
        try:
            verify(root, trace=t, packets=p, result=r, raw=wire, artifact_manifest=args.artifact_manifest)
        except (AssertionError, StopIteration) as error:
            controls.append({'control':name, 'status':'rejected', 'reason':str(error) or 'required actual state missing'})
        else:
            raise AssertionError('tampered corpus accepted: ' + name)

    def remove_feedback(t,p,r,w):
        p[:] = [row for row in p if not (row['direction'] == 'receive' and row['bot'] == 'OracleB' and row['name'] == 'update_health')]
    control('missing_victim_health_feedback', remove_feedback)
    control('missing_actual_damage_feedback', lambda t,p,r,w: p.__setitem__(slice(None), [row for row in p if not (row['direction']=='receive' and row['name']=='damage_event')]))
    def duplicate(t,p,r,w):
        index = next(i for i,row in enumerate(t) if row['kind']=='DAMAGE_POST')
        t.insert(index, copy.deepcopy(t[index]))
    control('duplicate_server_damage', duplicate)
    def owner(t,p,r,w):
        for row in t:
            if row['kind']=='STATE' and row.get('name')=='OracleB': row['ownerRegionX'] += 1
    control('forged_engine_owner', owner)
    def final(t,p,r,w):
        start=next(i for i,row in enumerate(t) if row['kind']=='MARK' and row['detail']=='cow_positive_x_A_attack_after')
        index=next(i for i in range(start+1,len(t)) if t[i]['kind']=='TARGET_STATE')
        del t[index]
    control('missing_final_entity_state', final)
    control('forged_remote_success', lambda t,p,r,w: next(row for row in r['scenarios'] if row['label']=='pvp_far_A_B').update(expected='damage'))
    def inventory(t,p,r,w):
        start=next(i for i,row in enumerate(t) if row['kind']=='MARK' and row['detail']=='cow_positive_x_A_milk_after')
        next(row for row in t[start+1:] if row['kind']=='STATE' and row['name']=='OracleA')['inventory']={'minecraft:bucket':1}
    control('missing_real_milk_inventory', inventory)
    def sync(t,p,r,w):
        next(row for row in t if row['kind']=='INTERACT_RESULT' and 'result=FAIL' in row['detail'])['detail']='target=0,hand=MAIN_HAND,result=CONSUME'
    control('forged_sync_cancel_return', sync)
    def raw_attack(t,p,r,w):
        next(row for row in w if row['name']=='use_entity')['data']['mouse']=0
    control('raw_action_disagrees_with_decoded_packet', raw_attack)
    def frame(t,p,r,w):
        next(row for row in p if row['direction']=='frame_send' and row['data']['state']=='play')['data']['sha256']='0'*64
    control('damaged_raw_frame', frame)
    print(json.dumps({'status':'passed','positiveCorpus':'passed','negativeControls':len(controls),'controls':controls},indent=2))


if __name__=='__main__': main()
