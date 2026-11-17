#!/usr/bin/env python3
"""Independent bounded lifecycle verdict: original reads, raw protocol frames and installed bytes."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from run_worker_smoke import validate_samples
from verify_resources import nbt_items

TOOLS = Path(__file__).resolve().parent

def require(condition, message):
    if not condition: raise AssertionError(message)

def lines(path): return [json.loads(s) for s in path.read_text().splitlines()]

def integer(value):
    return (value[0] << 32) + (value[1] & 0xffffffff) if isinstance(value, list) else int(value)

def verify(root, installer, packets=None, raw=None):
    output = root / 'worker-lifecycle'
    install = json.loads((root / 'installation.json').read_text())
    result = json.loads((output / 'result.json').read_text())
    require(result['status'] == 'passed' and result['serverExits'] == [0,0] and result['clientsExit'] == 0, 'lifecycle runner or real process failure')
    require(result['installation'] == install and hashlib.sha256(installer.read_bytes()).hexdigest() == install['installerSha256'], 'wrong installer/installation')
    require(not list((root / 'mods').glob('*.jar')) and install['oracleSha256'] is None, 'production lifecycle must not load an oracle')
    relative = f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
    with zipfile.ZipFile(installer) as archive:
        require((root / 'libraries' / relative).read_bytes() == archive.read('maven/' + relative), 'installed core differs from installer')
    for name, digest in result['sources'].items():
        require(hashlib.sha256((output / name).read_bytes()).hexdigest() == digest, 'archived lifecycle source mismatch '+name)
    commands = lines(output / 'commands.ndjson')
    original = [json.loads(row['response']) for row in commands if row['command'] == 'neoforge regionthreading status players']
    def snapshot(label):
        state = json.loads((output / (label + '.json')).read_text())
        require(state in original, 'snapshot absent from original command response '+label)
        require(state['requested'] == install['playerPlanningRequested'] and state['persistent'] == install['persistent'], 'actual mode mismatch')
        require(state['stateClaims'] == state['activeBatches'] == 0, 'live batch/state claim')
        require(all(p['failed'] == 0 for p in state['phases']['phases'].values()), 'player body failed')
        return state
    samples = json.loads((output / 'network-samples.json').read_text())
    require(all(sample in original for sample in samples), 'invented network samples')
    workers = validate_samples(samples, install['playerPlanningRequested'])
    before = samples[-1]; walk = snapshot('walk-after')
    a = next(p for p in before['players'] if p['name'] == 'WorkerA')
    b = next(p for p in walk['players'] if p['name'] == 'WorkerA')
    require(a['x'] == 47.5 and a['regionX'] == 0 and b['x'] == 48.25 and b['regionX'] == 1 and a['uuid'] == b['uuid'] and a['ownerCurrent'] and b['ownerCurrent'], 'normal walking did not publish one actual new owner')
    disconnected = snapshot('after-disconnect')
    require([p['name'] for p in disconnected['players']] == ['WorkerB'] and disconnected['phases']['owners'] == int(install['playerPlanningRequested']), 'old owner remains after disconnect')
    reconnected = snapshot('after-reconnect'); restarted = snapshot('after-restart'); final = snapshot('final')
    require(not final['players'] and all(final['phases'][k] == 0 for k in ['owners','pending','running']), 'live final player binding')
    require(final['network']['pending'] == final['network']['readers'] == final['network']['terminals'] == 0 and not final['network']['frozen'], 'live final network state')
    def player_rows(state): return [r for r in state['phases']['recent'] if r['player'] == a['uuid'] and r['started']]
    if install['playerPlanningRequested']:
        old_instances = {r['playerInstance'] for r in player_rows(before)}
        new_tick = max((r['tick'] for r in disconnected['phases']['recent']), default=-1)
        new_rows = [r for r in player_rows(reconnected) if r['tick'] > new_tick+1]
        require(old_instances and new_rows and all(r['playerInstance'] not in old_instances for r in new_rows), 'old player instance reused after reconnect')
    # Across the two restarts the original server read returns the same five items and seven spendable XP.
    resource_states = []
    for label in ['resources-before-disconnect', 'resources-after-reconnect', 'resources-after-restart']:
        state = json.loads((output / (label + '.json')).read_text())
        boot = 2 if label.endswith('restart') else 1
        for key, response in state.items():
            require(any(row['boot'] == boot and row['command'] == 'data get entity WorkerA '+key and row['response'] == response for row in commands), 'missing original saved-state read '+label+'/'+key)
        require(nbt_items(state['Inventory']) == {'minecraft:diamond':5}, 'lost or duplicated saved inventory')
        require(re.search(r': 7\s*$', state['XpTotal']) and re.search(r': 1\s*$', state['XpLevel']) and re.search(r': 0(?:\.0)?f\s*$', state['XpP']), 'lost or duplicated seven-point XP')
        resource_states.append(state)
    require(result['remainingResourceRead'] == next(row['response'] for row in commands if row['command'] == 'execute if entity @e[tag=lifecycle_resource]') and result['remainingResourceRead'].strip() == 'Test failed', 'ground resources remain after pickup')
    packets = packets if packets is not None else lines(output / 'packets.ndjson')
    require([r['seq'] for r in packets] == list(range(1,len(packets)+1)), 'truncated packet trace')
    for row in packets:
        if row['direction'] in ['frame','frame_send']:
            blob = base64.b64decode(row['data']['base64'],validate=True)
            require(len(blob) == row['data']['bytes'] and hashlib.sha256(blob).hexdigest() == row['data']['sha256'], 'corrupted raw lifecycle frame')
    if raw is None:
        decoded = subprocess.check_output(['node',str(TOOLS / 'decode_lifecycle_frames.cjs'),str(output / 'packets.ndjson')],text=True)
        raw = [json.loads(line) for line in decoded.splitlines()]
    marks = {r['name']:r['seq'] for r in packets if r['direction'] == 'control'}
    def window(start,end): return [r for r in raw if marks[start] < r['seq'] < marks[end]]
    network = window('network_before','network_after')
    actions = [r for r in network if r['direction'] == 'frame_send' and r['bot'] == 'WorkerA' and r['name'] in ['custom_payload','chat_message','ping_request']]
    require(len(actions) == 24, 'missing original network sequence frames')
    for i in range(6):
        group = actions[i*4:(i+1)*4]
        require([r['name'] for r in group] == ['custom_payload','chat_message','ping_request','custom_payload'], 'wire sequence reordered')
        require([group[j]['data']['channel'] for j in [0,3]] == ['minecraft:register','minecraft:unregister'] and group[1]['data']['message'] == f'forgia_lifecycle_{i}' and integer(group[2]['data']['id']) == 9000+i, 'raw sequence contents mismatch')
    pings = [integer(r['data']['id']) for r in network if r['name'] == 'ping_response' and r['direction'] == 'frame' and r['bot'] == 'WorkerA']
    require(pings == list(range(9000,9006)), 'server ping replies missing or reordered')
    log = (output / 'boot-1.server.log').read_text()
    chat = re.findall(r'<WorkerA> (forgia_lifecycle_\d+)',log)
    require(chat == [f'forgia_lifecycle_{i}' for i in range(6)], 'original server chat handling missing/reordered')
    feedback = [r for r in network if r['direction'] == 'frame' and r['bot'] == 'WorkerA' and r['name'] in ['system_chat','player_chat','profileless_chat']]
    observed = [token for row in feedback for token in re.findall(r'forgia_lifecycle_\d+',json.dumps(row['data']))]
    require(observed == [f'forgia_lifecycle_{i}' for i in range(6)], 'raw chat feedback missing/reordered')
    ping_delays = []
    for packet_id in range(9000,9006):
        sent = next(r for r in network if r['name'] == 'ping_request' and r['direction'] == 'frame_send' and r['bot'] == 'WorkerA' and integer(r['data']['id']) == packet_id)
        received = next(r for r in network if r['name'] == 'ping_response' and r['direction'] == 'frame' and r['bot'] == 'WorkerA' and integer(r['data']['id']) == packet_id)
        delay = (int(received['nano']) - int(sent['nano'])) / 1e6
        require(delay >= 0, 'negative actual ping latency')
        ping_delays.append(delay)
    keepalives = {}
    for name in ['WorkerA','WorkerB']:
        challenges = [r for r in network if r['bot'] == name and r['name'] == 'keep_alive' and r['direction'] == 'frame']
        require(challenges, 'no actual timed keepalive challenge '+name)
        for challenge in challenges:
            require(any(r['bot'] == name and r['name'] == 'keep_alive' and r['direction'] == 'frame_send' and r['data'] == challenge['data'] and r['seq'] > challenge['seq'] for r in network), 'no matching normal keepalive reply')
        keepalives[name] = len(challenges)
    moves = [r for r in window('walk_before','walk_after') if r['name'] == 'position_look' and r['direction'] == 'frame_send' and r['bot'] == 'WorkerA']
    require(len(moves) == 1 and moves[0]['data']['x'] == 48.25, 'walk did not originate in one normal client packet')
    pickups = window('pickup_before','pickup_after')
    collect = [r for r in pickups if r['bot'] == 'WorkerA' and r['direction'] == 'frame' and r['name'] == 'collect']
    require(len(collect) == 2, 'missing/duplicate actual item/XP collect feedback')
    require(sorted(row['data']['pickupItemCount'] for row in collect) == [1,5] and len({row['data']['collectedEntityId'] for row in collect}) == 2, 'wrong actual collect identities or amounts')
    require(any(r['name'] == 'experience' and r['data']['level'] == 1 for r in pickups if r['bot'] == 'WorkerA' and r['direction'] == 'frame'), 'missing actual XP feedback')
    dimension_result = None
    if 'dimensionProof' in result:
        proof = result['dimensionProof']
        require('"minecraft:overworld"' in proof['initialDimension'] and '"minecraft:the_nether"' in proof['outwardReads'][-1]
                and '"minecraft:overworld"' in proof['inwardReads'][-1], 'missing original round-trip dimension reads')
        for response in [proof['initialDimension']] + proof['outwardReads'] + proof['inwardReads']:
            require(any(c['command'] == 'data get entity WorkerA Dimension' and c['response'] == response for c in commands), 'forged dimension response')
        for response in proof['cooldownReads']:
            require(any(c['command'] == 'data get entity WorkerA PortalCooldown' and c['response'] == response for c in commands), 'forged portal cooldown response')
        cooldown = [int(re.search(r': (\d+)\s*$', r)[1]) for r in proof['cooldownReads']]
        require(cooldown and cooldown[-1] == 0 and all(0 <= value <= 10 for value in cooldown), 'original player cooldown is not ready for return')
        for label in ['dimension-nether','dimension-overworld']:
            state = snapshot(label)
            require(all(p['ownerCurrent'] for p in state['players']), 'stale dimension owner '+label)
            reads = json.loads((output / (label + '-resources.json')).read_text())
            require(nbt_items(reads['Inventory']) == {'minecraft:diamond':5} and re.search(r': 7\s*$',reads['XpTotal']), 'dimension transition lost/duplicated resources')
            require(all(any(c['command'] == 'data get entity WorkerA '+key and c['response'] == value for c in commands) for key,value in reads.items()), 'missing independent dimension resource reads')
        dimension_frames = window('dimension_before','dimension_after')
        decoded_dimensions = [r for r in packets if marks['dimension_before'] < r['seq'] < marks['dimension_after'] and r['bot'] == 'WorkerA' and r['direction'] == 'receive' and r['name'] == 'respawn']
        raw_dimensions = [r for r in dimension_frames if r['bot'] == 'WorkerA' and r['direction'] == 'frame' and r['name'] == 'respawn']
        require(len(decoded_dimensions) == len(raw_dimensions) == 2 and [r['data'] for r in decoded_dimensions] == [r['data'] for r in raw_dimensions], 'missing/duplicate/raw-mismatched real client dimension transitions')
        require(len([r for r in dimension_frames if r['bot'] == 'WorkerA' and r['direction'] == 'frame_send' and r['name'] == 'position_look']) >= 4, 'no normal client movement through the portals')
        start = next(i for i,c in enumerate(commands) if c['command'] == 'fill 54 100 24 55 102 24 minecraft:nether_portal[axis=x]')
        end = next(i for i,c in enumerate(commands) if c['command'] == 'save-all flush')
        require(all(c['command'].startswith(('data get entity ','neoforge regionthreading status players')) for c in commands[start+1:end]), 'command replaced measured portal gameplay')
        dimension_result = {'dimensions':['minecraft:overworld','minecraft:the_nether','minecraft:overworld'], 'clientRespawns':2, 'diamonds':5,'xp':7,
                            'cooldownObservation':'positive-to-zero' if any(cooldown) else 'already-zero-at-first-sample'}
    for boot in [1,2]:
        log = (output / f'boot-{boot}.server.log').read_text()
        require(not any(s in log for s in ['Failed to handle packet','Caused by:','Exception caught in connection','Error executing task']), 'original server failure')
    return {'status':'passed','scope':'bounded production lifecycle and network liveness; no full cancellation-race or custom payload callback-order certificate',
            'version':install['version'],'persistent':install['persistent'],'requested':install['playerPlanningRequested'],
            'installerSha256':install['installerSha256'],'workers':workers,'keepalives':keepalives,'pingRoundTripMs':ping_delays,'performanceScope':'six liveness samples only; no on/off or pool speedup inference',
            'walk':{'x':[a['x'],b['x']],'regionX':[a['regionX'],b['regionX']]},
            'resources':{'diamonds':5,'xp':7,'reads':len(resource_states),'remainingGroundResources':0},
            'serverExits':result['serverExits'],'clientsExit':result['clientsExit'],'network':final['network'],'dimensionProof':dimension_result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server',type=Path,required=True); parser.add_argument('--installer',type=Path,required=True); parser.add_argument('--output',type=Path)
    args = parser.parse_args(); out = args.output or args.server/'worker-lifecycle/independent-verdict.json'
    require(not out.exists(),'preserve original lifecycle verdict')
    try: result = verify(args.server.resolve(), args.installer.resolve())
    except BaseException as error:
        out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n'); raise
    out.write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(result))

if __name__ == '__main__': main()
