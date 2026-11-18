#!/usr/bin/env python3
"""Independent declared-payload verdict from installed bytes, server reads and decoded wire frames."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from verify_resources import nbt_items

TOOLS = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def load(path):
    return json.loads(path.read_text())


def verify(root, installer, override=None):
    out = root / 'region-payload'
    result = load(out / 'result.json')
    install = load(root / 'installation.json')
    require(result['status'] == 'passed' and result['serverExits'] == [0] and result['clientsExit'] == 0, 'Original runner or process failed')
    require(result['installation'] == install and hashlib.sha256(installer.read_bytes()).hexdigest() == install['installerSha256'], 'Wrong actual installation')
    jars = list((root / 'mods').glob('*.jar'))
    require(len(jars) == 1 and hashlib.sha256(jars[0].read_bytes()).hexdigest() == result['modSha256'], 'Fixture Mod bytes differ')
    require(install['oracleSha256'] is None, 'Oracle must not be installed or approved')
    relative = f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
    with zipfile.ZipFile(installer) as archive:
        require((root / 'libraries' / relative).read_bytes() == archive.read('maven/' + relative), 'Installed universal differs from installer')
    for path, digest in result['sources'].items():
        require(hashlib.sha256((out / 'source' / path).read_bytes()).hexdigest() == digest, 'Changed frozen source ' + path)
    commands = [json.loads(row) for row in (out / 'commands.ndjson').read_text().splitlines()]
    states = [json.loads(row['response']) for row in commands if row['command'] == 'neoforge regionthreading status players']
    for label in ['before', 'after', 'afterDisconnect', 'reconnected', 'final']:
        require(result[label] in states, 'Invented server snapshot ' + label)
    enabled = install['playerPlanningRequested']
    after = result['after']
    for state in states:
        require(state['requested'] == enabled and state['persistent'] == install['persistent'], 'Actual mode differs')
        require(state['activeBatches'] == state['stateClaims'] == 0 and not state['network']['frozen'], 'Leaked owner permission')
        require(all(phase['worker'] == 0 and phase['failed'] == 0 for phase in state['phases']['phases'].values()), 'Payload declaration authorized complete unknown-Mod ticks')
    if enabled:
        require(all(after['refusals'].get(phase + ':UNKNOWN_MOD', 0) > 0 for phase in ['ENTITY', 'CONNECTION']), 'Full tick unknown-Mod gate was bypassed')
    events = override.get('events') if override and 'events' in override else load(out / 'after-events.json')['events']
    require(events == result['eventsAfter']['events'] or override is not None, 'Invented fixture event stream')
    actors = {p['name']: p['uuid'] for p in result['before']['players']}
    decoded = override.get('decoded') if override and 'decoded' in override else [json.loads(row) for row in subprocess.check_output(['node', str(TOOLS / 'decode_region_payload.cjs'), str(out / 'packets.ndjson')], text=True).splitlines()]
    raw = [json.loads(row) for row in (out / 'packets.ndjson').read_text().splitlines()]
    require([row['seq'] for row in raw] == list(range(1, len(raw) + 1)), 'Incomplete or reordered original wire capture')
    counts, ordinals = {}, {}
    for row in raw:
        if row['direction'] in ['frame', 'frame_send']:
            data = base64.b64decode(row['data']['base64'], validate=True)
            require(len(data) == row['data']['bytes'] and hashlib.sha256(data).hexdigest() == row['data']['sha256'], 'Original wire checksum/length mismatch')
        if row['direction'] == 'frame_send':
            counts[row['bot']] = counts.get(row['bot'], 0) + 1
            ordinals[row['seq']] = counts[row['bot']]
    marks = {row['name']: row['seq'] for row in raw if row['direction'] == 'control'}
    measured = [row for row in decoded if marks['declared_before'] < row['seq'] < marks['declared_after']]
    locals_ = [row for row in events if row['kind'] == 'local']
    workers = [row for row in locals_ if row['worker']]
    require(len(locals_) == 22, 'Missing/duplicated original handler bodies')
    require(bool(workers) == enabled, 'Declaration never ran under actual worker permission or ran while disabled')
    for row in locals_:
        require(row['main'] != row['worker'] and ('Worker' in row['thread']) == row['worker'], 'Handler thread disagrees with scope')
    for name, actor in actors.items():
        observed = [row for row in events if row['player'] == actor and row['kind'] in ['main', 'local', 'return'] and not row['text'].startswith('hold')]
        expected = []
        for i in range(10):
            expected += [('main', 'before_' + str(i)), ('local', 'transfer_' + str(i)), ('return', 'transfer_' + str(i)), ('main', 'after_' + str(i))]
        if name == 'WorkerA':
            expected += [('local', 'boom'), ('main', 'after_boom'), ('main', 'after_network_hold')]
        require([(row['kind'], row['text']) for row in observed] == expected, 'Original per-connection MAIN/declared fences reordered or replayed')
        sent = [row['data']['text'] for row in measured if row['bot'] == name and row['direction'] == 'frame_send' and row['name'] == 'custom_payload' and row['data']['channel'] == 'forgia_region:local']
        local_events = [row for row in locals_ if row['player'] == actor]
        require(sent == [row['text'] for row in local_events], 'Handler input is not the exact normal serialized client payload prefix')
        replies = [row['data']['text'] for row in measured if row['bot'] == name and row['direction'] == 'frame' and row['name'] == 'custom_payload' and row['data']['channel'] == 'forgia_region:reply']
        require(replies == ['reply:' + text for text in sent], 'Original outbound replies missing, duplicated or reordered')
        completions = [row for row in events if row['player'] == actor and row['kind'] == 'completion']
        require(sorted(row['text'] for row in completions) == sorted(sent) and all(row['main'] and not row['worker'] and row['thread'] == 'Server thread' for row in completions), 'enqueueWork MAIN semantics/count changed')
        # Original enqueueWork executes inline on MAIN fallback and queues from a worker. A later
        # inline callback can precede an earlier queued callback; preserve, rather than invent, that
        # documented distinction. Worker-originated tasks must remain in original executor order.
        modes = {row['text']: row['worker'] for row in local_events}
        require([row['text'] for row in completions if modes[row['text']]] == [row['text'] for row in local_events if row['worker']], 'Queued worker-origin MAIN continuations reordered')
        for invocation in local_events:
            completion = next(row for row in completions if row['text'] == invocation['text'])
            require(completion['nano'] > invocation['nano'], 'Completion preceded its actual handler')
            returned = next((row for row in events if row['player'] == actor and row['kind'] == 'return' and row['text'] == invocation['text']), None)
            if returned:
                require((completion['nano'] > returned['nano']) == invocation['worker'], 'MAIN inline/worker after-return boundary changed')
        for text in sent:
            require(sum(row['bot'] == name and row['direction'] == 'frame' and row['name'] == 'system_chat' and 'region_payload_done:' + text + '"' in json.dumps(row['data']) for row in measured) == 1, 'Missing real client MAIN completion feedback ' + text)
        for resource_label in ['resourcesBefore', 'resourcesAfter', 'resourcesFinal']:
            resource = result[resource_label][name]
            require(all(any(command['command'] == f'data get entity {name} {field}' and command['response'] == value for command in commands) for field, value in resource.items()), 'Invented original resource read')
            require(nbt_items(resource['Inventory']) == {'minecraft:diamond': 16}, 'Item conservation failed')
            require(re.search(r'\b7\s*$', resource['XpTotal']) and re.search(r'\b20\.0f\s*$', resource['Health']), 'XP or health changed')
        items = result['clientsAfter'][name]['window']['items']
        require(sum(item.get('itemCount', 0) for item in items if item.get('itemId') == 805) == 16, 'Client inventory differs from conserved server inventory')
        require([items[index].get('itemCount', 0) for index in [9, 10]] == ([15, 1] if name == 'WorkerA' else [16, 0]), 'Client slots hide lost/replayed original transfer')
        if enabled:
            require(any(row['player'] == actor for row in workers), 'One sender never reached a worker')
    network = [row for row in events if row['kind'] == 'network']
    require([row['text'] for row in network] == ['network_' + str(i) for i in range(50)], 'NETWORK packet prefix reordered/lost')
    require(all(row['network'] and not row['main'] and not row['worker'] and 'Netty' in row['thread'] for row in network), 'NETWORK moved off original event loop')
    require([row['text'] for row in events if row['kind'] == 'network-main'] == ['network_' + str(i) for i in range(50)] and all(row['main'] for row in events if row['kind'] == 'network-main'), 'NETWORK enqueueWork order/thread changed')
    require(all(row['main'] and not row['worker'] for row in events if row['kind'] == 'main'), 'Unmarked MAIN handler entered worker')
    require(result['networkHold']['worker'] == enabled, 'No real positive declared hold in expected execution mode')
    hold_return = next(row for row in events if row['kind'] == 'return' and row['text'] == 'hold_network')
    if enabled:
        require(all(row['nano'] >= hold_return['nano'] for row in network), 'A direct network callback crossed the live worker fence')
        require(after['network']['deferred'] > result['before']['network']['deferred'], 'No real receive overlapped the frozen worker')
        meta = [row for row in after['packets']['recent'] if row.get('payloadId') == 'forgia_region:local' and row['started']]
        require(len(meta) == len(workers), 'Production owner execution counts differ from handler scopes')
        for row in workers:
            matching = [record for record in meta if record['player'] == row['player'] and record['playerInstance'] == row['instance'] and record['thread'] == row['thread'] and record['startNanos'] <= row['nano'] <= record['returnNanos']]
            require(len(matching) == 1 and matching[0]['receiveSequence'] > 0, 'Handler is outside its exact original owner/receive lease')
            sender = next(name for name, actor in actors.items() if actor == row['player'])
            wire = [packet for packet in measured if packet['bot'] == sender and packet['direction'] == 'frame_send' and packet['name'] == 'custom_payload'
                    and packet['data']['channel'] == 'forgia_region:local' and packet['data']['text'] == row['text']]
            require(len(wire) == 1 and matching[0]['receiveSequence'] == ordinals[wire[0]['seq']], 'Worker handler receipt differs from exact original protocol receive ordinal')
    final_events = load(out / 'final-events.json')['events']
    require(final_events == result['eventsFinal']['events'], 'Final source evidence differs')
    require(not any(row['kind'] == 'local' and row['text'] == 'transfer_stale' for row in final_events), 'Old disconnected payload body executed')
    require(sum(row['direction'] == 'frame_send' and row['name'] == 'custom_payload' and row['data']['channel'] == 'forgia_region:local' and row['data']['text'] == 'transfer_stale' for row in decoded) == 1, 'Cancellation was not tested with a real client packet')
    old = next(row for row in final_events if row['kind'] == 'local' and row['text'] == 'hold_cancel')
    new = next(row for row in final_events if row['kind'] == 'local' and row['text'] == 'new_session')
    require(old['player'] == new['player'] == actors['WorkerB'] and old['instance'] != new['instance'], 'Reconnection reused old player instance')
    for actor in actors.values():
        saved = json.loads(subprocess.check_output(['node', str(TOOLS / 'read_saved_nbt.cjs'), str(root / 'world/playerdata' / (actor + '.dat'))], text=True))
        require(saved['XpTotal'] == 7 and saved['Health'] == 20 and sum(item['count'] for item in saved['Inventory'] if item['id'] == 'minecraft:diamond') == 16
                and all(item['id'] == 'minecraft:diamond' for item in saved['Inventory']), 'Original saved player data failed item/XP/health conservation')
    final = result['final']
    require(not final['players'] and all(final['phases'][key] == 0 for key in ['owners', 'pending', 'running']), 'Live player ownership after logout')
    require(final['network']['pending'] == final['network']['readers'] == final['network']['terminals'] == final['packets']['tasks']['pending'] == final['packets']['tasks']['terminals'] == final['packets']['chatPending'] == final['packets']['connections'] == 0, 'Live queue or retained connection after logout')
    log = (out / 'boot-1.server.log').read_text()
    errors = [line for line in log.splitlines() if '/ERROR]' in line or 'Exception in thread' in line]
    require(len(errors) == 1 and 'Failed to process a synchronized task of the payload: forgia_region:local' in errors[0]
            and log.count('Caused by: java.lang.IllegalArgumentException: declared-proof-failure-after-transfer') == 1,
            'Unexpected server error or missing deliberate failure in original executor/guard boundaries')
    return dict(status='passed', version=install['version'], requested=enabled, persistent=install['persistent'], localBodies=len(locals_), workerBodies=len(workers), defaultMainBodies=sum(row['kind'] == 'main' for row in events), networkBodies=50, diamonds=32, xp=14, failedHandlerReplays=0, oldInstancePayloadBodies=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--installer', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    out = args.output or args.server / 'region-payload/independent-verdict.json'
    require(not out.exists(), 'Preserve prior verdict')
    try:
        result = verify(args.server.resolve(), args.installer.resolve())
    except BaseException as error:
        out.write_text(json.dumps(dict(status='failed', error=repr(error)), indent=2) + '\n')
        raise
    out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
