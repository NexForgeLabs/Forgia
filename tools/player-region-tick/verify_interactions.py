#!/usr/bin/env python3
"""Independent schema-3 combat/entity verdict from raw frames, events and final state."""
import argparse
import base64
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
from reviewed_artifacts import verify_installation

TOOLS = Path(__file__).resolve().parent
CORE_SHA = '04d6c8edb1959eee74f90f67971f21c1de1d7bdb482380a1344ffc8007010c8c'
GEOMETRIES = {
    'positive_x': ((47,8.5),(49,8.5)), 'negative_x': ((-49,8.5),(-47,8.5)),
    'zero_x': ((-1,8.5),(1,8.5)), 'positive_z': ((8.5,47),(8.5,49)),
    'negative_z': ((8.5,-49),(8.5,-47)), 'positive_corner': ((47.2,47.2),(48.8,48.8)),
    'negative_corner': ((-48.8,-48.8),(-47.2,-47.2)), 'mixed_corner': ((-0.8,47.2),(0.8,48.8)),
    'same': ((44,8.5),(46,8.5)), 'far': ((41,8.5),(54,8.5)),
}
INSTALLED_JARS = {
    'server': '52883505f0dffe9bbc15d1246bf0dfb0eaf904f2551d8f2a3e21f349f7d0f61f',
    'universal': 'f6eb03586719a5429404201bbcd1cd3b615ac57e8be6a89ce7cb8bbbbea9c3e0',
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def load_lines(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def verify(root, trace=None, packets=None, result=None, raw=None, artifact_manifest=None):
    run = root / 'interaction-run'
    install = json.loads((root / 'installation.json').read_text())
    inputs = json.loads((run / 'inputs.json').read_text())
    result = result if result is not None else json.loads((run / 'result.json').read_text())
    trace = trace if trace is not None else load_lines(root / 'player-oracle.ndjson')
    packets = packets if packets is not None else load_lines(run / 'packets.ndjson')
    if raw is None:
        decoder = subprocess.run(['node', str(TOOLS / 'decode_interaction_frames.cjs'), str(run / 'packets.ndjson')],
                                 capture_output=True, text=True, check=True)
        raw = [json.loads(line) for line in decoder.stdout.splitlines()]
    require(result['status'] == 'passed' and result['serverExit'] == result['clientsExit'] == 0, 'runner/process failure')
    require(inputs['installation'] == install, 'input/installation mismatch')
    artifact = None
    if artifact_manifest is not None:
        artifact = verify_installation(root, run, install, artifact_manifest)
    else:
        require(install['version'] == '21.1.250.115-beta' and install['installerSha256'] == CORE_SHA, 'wrong core installation')
        for suffix, digest in INSTALLED_JARS.items():
            path = root / f'libraries/net/neoforged/neoforge/21.1.250.115-beta/neoforge-21.1.250.115-beta-{suffix}.jar'
            require(hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'actual installed core mismatch ' + suffix)
    for name, digest in inputs['sourceHashes'].items():
        require(hashlib.sha256((run / name).read_bytes()).hexdigest() == digest, 'archived source mismatch ' + name)
    require(inputs['actualOracleSha256'] == install['oracleSha256'] == hashlib.sha256((root / 'mods/forgia-player-oracle-1.0.jar').read_bytes()).hexdigest(), 'oracle hash mismatch')
    config = (root / 'config/forgia.toml').read_text()
    for key, value in [('enableRegionThreadingPlayerTicks', install['playerPlanningRequested']), ('enableRegionThreadingPersistentWorkers', install['persistent'])]:
        require(re.search(r'^' + key + r'\s*=\s*' + str(value).lower() + r'\s*$', config, re.M), 'config mismatch ' + key)
    require(trace[0]['kind'] == 'TRACE_START' and trace[-1]['kind'] == 'TRACE_STOP', 'missing trace boundaries')
    for rows, name in [(trace, 'trace'), (packets, 'packets')]:
        require([row['seq'] for row in rows] == list(range(1, len(rows)+1)), 'truncated/duplicate ' + name)
    for row in packets:
        if row['direction'] in ['frame', 'frame_send']:
            data = row['data']; frame = base64.b64decode(data['base64'], validate=True)
            require(data['bytes'] == len(frame) and data['sha256'] == hashlib.sha256(frame).hexdigest(), 'raw frame integrity')
    planning = json.loads((root / 'player-planning.json').read_text())
    require(planning['persistentWorkers'] == install['persistent'] and planning['requested'] == install['playerPlanningRequested'], 'actual engine mode mismatch')
    snap = planning['snapshot']
    require(all(snap[k] == 0 for k in ['owners', 'pending', 'running']), 'live planning state remains')
    for phase in snap['phases'].values():
        require(phase['worker'] == 0 and phase['failed'] == 0, 'baseline worker/failure mismatch')
    require(all(row['serverThread'] for row in trace if row['kind'] in ['ENTITY_START', 'DOTICK_START', 'CONNECTION_START', 'PACKET_START']), 'unexpected worker in baseline')
    expected = {f'pvp_{g}_{a}_{b}' for g in GEOMETRIES for a,b in [('A','B'), ('B','A')]}
    expected |= {f'cow_{g}_{a}_{op}' for g in ['positive_x', 'negative_x', 'positive_corner'] for a in ['A','B'] for op in ['attack','milk']}
    expected |= {'cow_cancel_attack', 'cow_cancel_milk', 'cow_empty_pass', 'cow_far_milk'}
    require({row['label'] for row in result['scenarios']} == expected and len(result['scenarios']) == len(expected), 'missing/duplicate matrix scenario')
    marks = {row['detail']: i for i,row in enumerate(trace) if row['kind'] == 'MARK'}
    client_marks = {row['name']: row['seq'] for row in packets if row['direction'] == 'control'}
    commands = load_lines(run / 'commands.ndjson')
    command_marks = {row['command'].removeprefix('player_oracle mark '): i for i,row in enumerate(commands) if row['command'].startswith('player_oracle mark ')}
    verdicts = []
    for scenario in result['scenarios']:
        label = scenario['label']; start = label + '_before'; end = label + '_after'
        require(start in marks and end in marks and start in client_marks and end in client_marks, label + ': missing boundaries')
        window = trace[marks[start]+1:marks[end]]
        # Snapshot rows at the ending mark follow it, up to the next non-snapshot row.
        final = []
        for row in trace[marks[end]+1:]:
            if row['kind'] not in ['STATE', 'TARGET_STATE']: break
            final.append(row)
        initial = []
        for row in window:
            if row['kind'] not in ['STATE', 'TARGET_STATE']: break
            initial.append(row)
        actor = next(row for row in initial if row.get('name') == scenario['actor'])
        target = next(row for row in initial if row.get('id') == scenario['targetId'])
        after = next(row for row in final if row.get('id') == target['id'])
        actor_after = next(row for row in final if row.get('id') == actor['id'])
        for state in [actor, target, after, actor_after]:
            coordinate = [math.floor(state['x']/48), math.floor(state['z']/48)]
            require([state.get('regionX'),state.get('regionZ')] == coordinate == [state.get('ownerRegionX'),state.get('ownerRegionZ')]
                    and state.get('indexedRegions') == 1 and state.get('ownerDimension') == state['dimension'] == 'minecraft:overworld', label + ': false/missing actual owner')
            require(not state['removed'], label + ': removed target')
        if label.startswith('pvp_'):
            geometry = label.removeprefix('pvp_').rsplit('_',2)[0]
            a, b = GEOMETRIES[geometry]
            origin, destination = (a,b) if actor['name']=='OracleA' else (b,a)
        elif label.startswith('cow_') and label not in ['cow_cancel_attack','cow_cancel_milk','cow_empty_pass','cow_far_milk']:
            geometry = label.removeprefix('cow_').rsplit('_',2)[0]
            a, b = GEOMETRIES[geometry]
            origin, destination = (a,b) if actor['name']=='OracleA' else (b,a)
        else:
            origin, destination = GEOMETRIES['far' if label=='cow_far_milk' else 'positive_x']
        # Vanilla command Vec3 arguments center integer X/Z literals on their block;
        # fractional literals remain exact. Preserve and verify that real fixture behavior.
        origin = [value+0.5 if isinstance(value,int) else value for value in origin]
        destination = [value+0.5 if isinstance(value,int) else value for value in destination]
        require(math.dist([actor['x'],actor['z']], origin) < 0.01 and math.dist([target['x'],target['z']], destination) < 0.01
                and actor['y'] == target['y'] == 100, label + ': actual coordinates do not match matrix input')
        distance = math.dist([actor['x'], actor['y'], actor['z']], [target['x'], target['y'], target['z']])
        far = '_far' in label
        same = 'pvp_same' in label
        require((abs(distance-13) < 0.01) if far else (distance <= 3), label + ': wrong actual reach')
        owner_a = [actor['ownerRegionX'], actor['ownerRegionZ']]; owner_b = [target['ownerRegionX'], target['ownerRegionZ']]
        require((owner_a == owner_b) == same, label + ': not actual expected boundary')
        if not same:
            require(max(abs(a-b) for a,b in zip(owner_a,owner_b)) == 1, label + ': regions are not adjacent')
        if label.startswith('pvp_'):
            require(target['type'] == 'minecraft:player', label + ': wrong player target')
        else:
            require(target['type'] == 'minecraft:cow' and 'oracle_target' in target['tags'], label + ': wrong ordinary entity')
        outgoing = [row for row in packets if client_marks[start] < row['seq'] < client_marks[end] and row['direction'] == 'send' and row['name'] == 'use_entity']
        require(len(outgoing) == 1 and outgoing[0]['bot'] == scenario['actor'], label + ': no unique real client attack')
        sent = outgoing[0]
        require(sent['data']['target'] == target['id'] and sent['data']['mouse'] == (1 if scenario['operation'] == 'attack' else 0), label + ': wrong standard packet')
        raw_window = [row for row in raw if client_marks[start] < row['seq'] < client_marks[end]]
        raw_send = [row for row in raw_window if row['direction'] == 'frame_send' and row['name'] == 'use_entity']
        require(len(raw_send) == 1 and raw_send[0]['data'] == sent['data'] and raw_send[0]['bot'] == sent['bot'], label + ': raw outgoing does not decode to action')
        reads = commands[command_marks[start]+1:command_marks[end]]
        require(all(row['command'].startswith('data get entity ') for row in reads), label + ': command mutated tested operation')
        health_reads = [row for row in reads if row['command'].endswith(' Health')]
        require(len(health_reads) == 2, label + ': missing original final health read')
        actual_health = [float(re.search(r'([-\d.]+)f\s*$', row['response'])[1]) for row in health_reads]
        require(actual_health == [target['health'], after['health']] == [scenario['before'],scenario['after']], label + ': health state disagrees')
        damage = [row for row in window if row['kind'] == 'DAMAGE_POST']
        successful = scenario['expected'] == 'damage'
        # Derive expectation from the fixed scenario name, not runner success.
        derived = 'reject' if far else 'cancel' if '_cancel_' in label else 'pass' if label == 'cow_empty_pass' else 'milk' if label.endswith('_milk') else 'damage'
        require(scenario['expected'] == derived, label + ': forged expected result')
        require((after['health'] < target['health']) == successful and len(damage) == int(successful), label + ': missing/duplicate damage/final state')
        handlers = [row for row in window if row['kind'] == 'PACKET_START' and row['detail'].startswith('interact:')]
        returns = [row for row in window if row['kind'] == 'PACKET_RETURN' and row['detail'].startswith('interact:')]
        require(len(handlers) == len(returns) == 1 and handlers[0]['id'] == actor['id'], label + ': missing original handler')
        if damage:
            require(damage[0]['id'] == target['id'] and f'source={actor["id"]},' in damage[0]['detail'] and handlers[0]['seq'] < damage[0]['seq'] < returns[0]['seq'], label + ': unrelated damage event')
            require(abs(float(damage[0]['detail'].split('damage=')[1]) - (target['health'] - after['health'])) < 1e-5, label + ': damage amount mismatch')
        receiver = target.get('name', actor['name'])
        feedback = [row for row in packets if client_marks[start] < row['seq'] < client_marks[end] and row['direction'] == 'receive' and row['bot'] == receiver]
        hurt = [row for row in feedback if row['name'] == 'damage_event' and row['data']['entityId'] == target['id']]
        require(len(hurt) == int(successful), label + ': missing/duplicate actual client damage feedback')
        raw_hurt = [row for row in raw_window if row['bot'] == receiver and row['name'] == 'damage_event' and row['data']['entityId'] == target['id']]
        require([row['data'] for row in raw_hurt] == [row['data'] for row in hurt], label + ': raw damage mismatch')
        latency = None
        if successful:
            latency = (int(hurt[0]['nano']) - int(sent['nano'])) / 1e6
            require(latency >= 0, label + ': feedback before send')
            if label.startswith('pvp_'):
                health = [row for row in feedback if row['name'] == 'update_health' and row['data']['health'] == after['health']]
                require(health, label + ': missing actual victim health feedback')
                require(any(row['bot'] == receiver and row['name'] == 'update_health' and row['data'] == health[-1]['data'] for row in raw_window), label + ': raw victim health mismatch')
        if scenario['operation'] == 'interact':
            interactions = [row for row in window if row['kind'] == 'INTERACT_EVENT']
            results = [row for row in window if row['kind'] == 'INTERACT_RESULT']
            require(len(interactions) == len(results) == (0 if far else 1), label + ': wrong sync interaction count')
            if not far:
                expected_result = {'milk':'CONSUME','cancel':'FAIL','pass':'PASS'}[derived]
                require(f'target={target["id"]},hand=MAIN_HAND,result={expected_result}' == results[0]['detail'], label + ': synchronous result mismatch')
                require(interactions[0]['id'] == target['id'] and handlers[0]['seq'] < interactions[0]['seq'] < results[0]['seq'] < returns[0]['seq'], label + ': result outside original handler')
                require(('cancel=true' in interactions[0]['detail']) == (derived=='cancel') and f'source={actor["id"]},' in interactions[0]['detail'], label + ': interaction cancel/source mismatch')
            pre = actor['inventory']; post = actor_after['inventory']
            require(pre == ({} if derived == 'pass' else {'minecraft:bucket':1}), label + ': missing real input inventory')
            require(post == ({'minecraft:milk_bucket':1} if derived == 'milk' else pre), label + ': inventory conservation/result mismatch')
            inv_reads = [row for row in reads if row['command'].endswith(' Inventory')]
            require(len(inv_reads) == 2 and inv_reads[0]['response'] == scenario['inventoryBefore'] and inv_reads[1]['response'] == scenario['inventoryAfter'], label + ': missing original inventory state')
            if derived == 'milk':
                require('minecraft:bucket' in inv_reads[0]['response'] and 'minecraft:milk_bucket' in inv_reads[1]['response'], label + ': independent NBT inventory mismatch')
                slots = [row for row in raw_window if row['bot'] == actor['name'] and row['name'] == 'set_slot']
                require(any(row['data']['slot'] == actor['selectedSlot']+36 and row['data']['item'].get('itemId') == 914
                            and row['data']['item'].get('itemCount') == 1 for row in slots), label + ': no raw client milk inventory feedback')
                received_slots = [row for row in packets if client_marks[start] < row['seq'] < client_marks[end]
                                  and row['direction']=='receive' and row['bot']==actor['name'] and row['name']=='set_slot']
                require([row['data'] for row in slots] == [row['data'] for row in received_slots], label + ': raw/decoded inventory feedback mismatch')
        else:
            attacks = [row for row in window if row['kind'] == 'ATTACK_EVENT']
            require(len(attacks) == (0 if far else 1), label + ': attack event count mismatch')
            if attacks:
                require(attacks[0]['id'] == target['id'] and ('cancel=true' in attacks[0]['detail']) == (derived == 'cancel'), label + ': synchronous cancel mismatch')
        verdicts.append({'label':label, 'distance':distance, 'actorOwner':owner_a, 'targetOwner':owner_b,
                         'health':[target['health'],after['health']], 'damageEvents':len(damage),
                         'clientDamagePackets':len(hurt), 'damageResponseMs':latency, 'verdict':'passed'})
    return {'schema':3, 'status':'passed', 'scope':artifact['scope'] if artifact else 'P0/P1 .115 combat and ordinary entity baseline; worker=0',
            'reviewedArtifact':artifact,
            'persistent':install['persistent'], 'playerPlanningRequested':install['playerPlanningRequested'],
            'installerSha256':install['installerSha256'], 'oracleSha256':install['oracleSha256'],
            'scenarioCount':len(verdicts), 'traceRows':len(trace), 'packetRows':len(packets), 'scenarios':verdicts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--artifact-manifest', type=Path)
    args = parser.parse_args()
    root = args.server.resolve()
    output = args.output or root / 'interaction-run/independent-verdict.json'
    require(not output.exists(), 'preserve existing verdict; use a new output')
    verdict = verify(root, artifact_manifest=args.artifact_manifest)
    output.write_text(json.dumps(verdict, indent=2) + '\n')
    print(json.dumps({key:value for key,value in verdict.items() if key != 'scenarios'}))


if __name__ == '__main__':
    main()
