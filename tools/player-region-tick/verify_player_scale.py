#!/usr/bin/env python3
"""Independent P5 scale correctness gate and native-ring/real-wire performance measurements."""
import argparse
import base64
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import zipfile
from verify_resources import nbt_items

TOOLS = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def lines(path):
    content = path.read_bytes()
    require(content and content.endswith(b'\n'), 'Incomplete original evidence ' + str(path))
    return [json.loads(line) for line in content.splitlines()]


def describe(values):
    require(values and all(math.isfinite(value) and value >= 0 for value in values), 'Missing/invalid measurements')
    values = sorted(values)
    return dict(count=len(values), mean=statistics.fmean(values), p50=values[math.ceil(.5 * len(values))-1],
                p95=values[math.ceil(.95 * len(values))-1], p99=values[math.ceil(.99 * len(values))-1], maximum=values[-1])


def ring_updates(samples):
    require(len(samples) >= 10, 'Insufficient native JVM samples')
    values, intervals = [], []
    for previous, current in zip(samples, samples[1:]):
        elapsed = current['monitorNanos'] - previous['monitorNanos']
        require(0 < elapsed < 2_000_000_000, 'Tick ring sampling gap can lose a complete 100-slot cycle')
        left, right = previous['tickTimesNanos'], current['tickTimesNanos']
        require(len(left) == len(right) == 100 and all(isinstance(value, int) and value >= 0 for value in left + right), 'Invalid original native tick ring')
        changed = [index for index in range(100) if right[index] != left[index]]
        require(len(changed) < 100, 'Whole tick ring overwritten; samples cannot recover per-tick latency')
        # Each observed slot update is one original completed server tick, never every 100 values
        # per poll. Identical same-slot values are conservatively unobservable, not invented samples.
        values.extend(right[index] / 1e6 for index in changed if right[index] > 0)
        intervals.append(dict(sequence=current['seq'], observedUpdates=len(changed), elapsedNanos=elapsed))
    require(len(values) >= 60, 'Insufficient actual complete-tick samples')
    return values, intervals


def native_window(samples):
    ticks, intervals = ring_updates(samples)
    allocated = cpu = 0
    lower_bound = set()
    previous = {row['id']: row for row in samples[0]['threads']}
    server_stacks, barrier_stacks, stack_frames = 0, 0, []
    for sample in samples[1:]:
        current = {row['id']: row for row in sample['threads']}
        lower_bound.update(set(previous) - set(current))
        for identity, row in current.items():
            old = previous.get(identity)
            if old:
                require(row['allocatedBytes'] >= old['allocatedBytes'] >= 0, 'Native allocation counter regressed')
                allocated += row['allocatedBytes'] - old['allocatedBytes']
                if old['cpuNanos'] >= 0 and row['cpuNanos'] >= old['cpuNanos']:
                    cpu += row['cpuNanos'] - old['cpuNanos']
            if row['name'] == 'Server thread' and 'stack' in row:
                server_stacks += 1
                waiting = any('RegionTaskBatch$Barrier' in frame or 'RegionPlayerPacketRouter$Call.await' in frame for frame in row['stack'])
                barrier_stacks += waiting
                if waiting and len(stack_frames) < 3:
                    stack_frames.append(row['stack'])
        previous = current
    seconds = (samples[-1]['monitorNanos'] - samples[0]['monitorNanos']) / 1e9
    return dict(tickMs=describe(ticks), observedTickUpdates=len(ticks), tickRingSampling=intervals,
                elapsedNativeSeconds=seconds, allocatedBytesObserved=allocated, allocatedBytesPerSecond=allocated/seconds,
                exitedThreadAllocationUnobserved=len(lower_bound), allocationIsLowerBound=True, cpuSecondsObserved=cpu/1e9,
                heapUsedBytes=describe([sample['heapUsedBytes'] for sample in samples]),
                gcDelta=[dict(name=row['name'], count=row['count']-old['count'], millis=row['millis']-old['millis'])
                         for old, row in zip(samples[0]['gc'], samples[-1]['gc'])],
                sampledServerStacks=server_stacks, sampledBarrierWaitingStacks=barrier_stacks,
                sampledBarrierWaitingFraction=barrier_stacks/server_stacks if server_stacks else None,
                barrierOverheadNanos=None, barrierLimit='Stack sampling is waiting coverage, not exact overhead; includes waiting for real game bodies',
                barrierStackExamples=stack_frames,
                samplerCallMs=describe([sample['sampleDurationNanos']/1e6 for sample in samples]))


def verify(root, installer, override=None):
    output = root / 'player-scale'
    result = json.loads((output / 'result.json').read_text())
    install = json.loads((root / 'installation.json').read_text())
    require(result['status'] == 'passed' and result['serverExits'] == [0] and result['clientsExit'] == result['samplerExit'] == 0, 'Original scale runner/process did not pass')
    require(result['installation'] == install and hashlib.sha256(installer.read_bytes()).hexdigest() == install['installerSha256'], 'Wrong installed artifact')
    mods = result.get('installedMods', [])
    installed = list((root / 'mods').glob('*.jar'))
    require(install['oracleSha256'] is None, 'Scale run cannot approve an oracle Mod')
    if mods:
        require({path.name for path in installed} == {Path(entry['path']).name for entry in mods}, 'Mixed Mod set differs from explicit manifest')
        require(all(hashlib.sha256((root/'mods'/Path(entry['path']).name).read_bytes()).hexdigest() == entry['sha256'] for entry in mods), 'Mixed Mod bytes changed')
    else:
        require(not installed, 'Native scale baseline loaded a gameplay/observer Mod')
    require(not any(value.startswith(('-agentlib:', '-javaagent:')) for value in install['launch']), 'Debugger/Java instrumentation invalidates normal performance baseline')
    suffix = f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
    with zipfile.ZipFile(installer) as archive:
        require((root / 'libraries' / suffix).read_bytes() == archive.read('maven/' + suffix), 'Installed runtime differs from installer')
    for path, digest in result['sources'].items():
        require(hashlib.sha256((output / 'source' / path).read_bytes()).hexdigest() == digest, 'Changed source snapshot ' + path)
    commands = lines(output / 'commands.ndjson')
    states = [json.loads(row['response']) for row in commands if row['command'] == 'neoforge regionthreading status players']
    raw = lines(output / 'packets.ndjson')
    require([row['seq'] for row in raw] == list(range(1, len(raw)+1)), 'Missing/reordered original wire capture')
    require(not any(row['direction'] in ['load_error', 'error'] for row in raw), 'Client load error')
    for row in raw:
        if row['direction'] in ['frame', 'frame_send']:
            data = base64.b64decode(row['data']['base64'], validate=True)
            require(len(data) == row['data']['bytes'] and hashlib.sha256(data).hexdigest() == row['data']['sha256'], 'Original raw frame integrity failed')
    topology=result.get('clientTopology','single-protocol-process')
    if topology=='maximum-eight-per-process':
        groups=json.loads((output/'packets.ndjson.groups.json').read_text())
        require(groups['schema']==1 and groups['maxClientsPerProcess']==8 and groups['groups'],'Missing original independent client groups')
        mapped={(row['childGroup'],row['childSeq']):row for row in raw if 'childGroup' in row}
        require(len(mapped)==sum('childGroup' in row for row in raw),'Duplicate grouped capture identity')
        seen=set();actors=set()
        for group in groups['groups']:
            require(group['exit']==0 and group['signal'] is None and group['failure'] is None and 0<len(group['names'])<=8,'Original protocol process failed/overbooked')
            require(not actors.intersection(group['names']),'Actor shared between protocol processes')
            actors.update(group['names'])
            original=lines(output/Path(group['capture']).name)
            require([row['seq'] for row in original]==list(range(1,len(original)+1)),'Original child capture missing/reordered')
            for row in original:
                key=(group['index'],row['seq']);merged=dict(mapped.get(key,{}))
                require(merged,'Original child record absent from merged capture')
                merged['seq']=merged.pop('childSeq');merged.pop('childGroup')
                require(merged==row and row['bot'] in group['names'],'Merged capture changed original client record')
                seen.add(key)
        require(seen==set(mapped) and actors=={actor['name'] for actor in result['windows'][-1]['actors']},'Invented client group record/actor')
        require(all(int(a['nano'])<=int(b['nano']) for a,b in zip(raw,raw[1:])),'Merged events not in original monotonic timestamp order')
    else:
        require(topology=='single-protocol-process','Unknown real-client topology')
    decoded = [json.loads(row) for row in subprocess.check_output(['node', str(TOOLS / 'decode_protocol_frames.cjs'), str(output / 'packets.ndjson')], text=True).splitlines()]
    ping_replies = {}
    for packet in decoded:
        if packet['direction'] == 'frame' and packet['name'] == 'ping_response':
            key = (packet['bot'], json.dumps(packet['data']['id'], sort_keys=True))
            ping_replies.setdefault(key, []).append(packet)
    marks = {row['name']: row['seq'] for row in raw if row['direction'] == 'control'}
    native = override if override is not None else lines(output / 'jmx.ndjson')
    require([row['seq'] for row in native] == list(range(1, len(native)+1)), 'Missing original JVM samples')
    rows = []
    for window in result['windows']:
        name, count = window['name'], window['count']
        require(window['before'] in states and window['after'] in states and all(row in states for row in window['samples']), 'Invented scale server snapshot')
        before, after = window['before'], window['after']
        players = after['players']
        require(len(players) == count and all(row['ownerCurrent'] and row['health'] == 20 for row in players), 'Wrong real client count/owner/health')
        require({row['name'] for row in players} == {actor['name'] for actor in window['actors']}, 'Actor set differs from normal logged-in clients')
        by_name = {row['name']: row for row in players}
        for actor in window['actors']:
            actual = by_name[actor['name']]
            require(actual['y'] == actor['y'] and actual['z'] == actor['z'] and abs(actual['x']-actor['x']) <= .051, 'Movement crossed undocumented owner/position')
        regions = {(row['regionX'], row['regionZ']) for row in players}
        require(len(regions) == (1 if window['distribution'] == 'same' else count), 'Actual region distribution differs from fixture')
        for state in [before, *window['samples'], after]:
            require(state['requested'] == install['playerPlanningRequested'] and state['persistent'] == install['persistent'], 'Wrong actual runtime mode')
            require(state['activeBatches'] == state['stateClaims'] == state['phases']['pending'] == state['phases']['running'] == 0 and not state['network']['frozen'], 'Live unjoined batch/claims')
            require(all(phase['failed'] == 0 for phase in state['phases']['phases'].values()) and state['packets']['failed'] == 0, 'Player phase/packet failed')
        for resource_set in [window['beforeResources'], window['afterResources']]:
            for actor, values in resource_set.items():
                for field, value in values.items():
                    require(any(row['command'] == f'data get entity {actor} {field}' and row['response'] == value for row in commands), 'Invented resource read')
                require(nbt_items(values['Inventory']) == {'minecraft:diamond':16} and re.search(r': 7\s*$', values['XpTotal']) and re.search(r': 20\.0f\s*$', values['Health']), 'Resource/health conservation failed')
        for actor in window['actors']:
            client = window['clients'][actor['name']]
            require(not client['ended'] and client['health'] == 20, 'Real client ended or received incorrect health')
            inventory = client['window']['items']
            require(sum(stack.get('itemCount',0) for stack in inventory if stack.get('itemId') == 805) == 16,
                    'Actual client inventory differs from conserved authoritative items')
        phase_coverage = {}
        records = {row['id']: row for state in window['samples'] for row in state['phases']['recent']}
        lower_tick = max((row['tick'] for row in before['phases']['recent']), default=-1)
        records = [row for row in records.values() if row['tick'] > lower_tick]
        require(len({(row['phase'], row['tick'], row['playerInstance']) for row in records if row['started']}) == sum(row['started'] for row in records), 'Duplicate same-player same-tick original phase')
        overlaps = Counter()
        for phase in ['ENTITY', 'CONNECTION']:
            left, right = before['phases']['phases'][phase], after['phases']['phases'][phase]
            worker, serial = right['worker']-left['worker'], right['serial']-left['serial']
            untracked = right['untrackedSerial']-left['untrackedSerial']
            require(worker >= 0 and serial >= 0 and untracked >= 0, 'Regressed phase accounting')
            if not install['playerPlanningRequested']:
                require(worker == 0, 'Disabled mode entered worker')
                phase_coverage[phase] = dict(worker=0, serial=None, workerFraction=0.0,
                                             accounting='Original off path has no player coordinator counters; serial invocation count unmeasured')
            elif mods:
                require(worker == 0 and after['refusals'].get(phase+':UNKNOWN_MOD',0) > before['refusals'].get(phase+':UNKNOWN_MOD',0), 'Mixed unknown extension acquired full-player worker permission')
            elif window['distribution'] == 'spread':
                require(worker > 0, 'No production full phase worker coverage in independent spread workload')
            if install['playerPlanningRequested']:
                require(worker+serial+untracked >= count * 30, 'Missing complete phase activity')
                phase_coverage[phase] = dict(worker=worker, serial=serial+untracked, workerFraction=worker/(worker+serial+untracked), untrackedSerial=untracked)
            calls = [row for row in records if row['phase'] == phase and row['worker'] and row['started']]
            calls_by_tick = {}
            for left in calls:
                require(left['state'] == 'JOINED' and left.get('failureClass') is None and left['returnNanos'] >= left['startNanos'] > 0, 'Bad actual body/join trace')
                previous_calls = calls_by_tick.setdefault(left['tick'], [])
                for right in previous_calls:
                    if left['player'] == right['player']:
                        continue
                    width = min(left['returnNanos'], right['returnNanos']) - max(left['startNanos'], right['startNanos'])
                    if width > 0:
                        if window['distribution'] == 'same':
                            raise AssertionError('Same-region complete player bodies overlapped')
                        overlaps[phase] += 1
                previous_calls.append(left)
        wire = [row for row in decoded if marks[name+'_before'] < row['seq'] < marks[name+'_after']]
        pings = [row for row in wire if row['direction'] == 'frame_send' and row['name'] == 'ping_request']
        latencies = []
        for request in pings:
            key = (request['bot'], json.dumps(request['data']['id'], sort_keys=True))
            replies = [row for row in ping_replies.get(key,[]) if row['seq'] > request['seq']]
            require(len(replies) == 1, 'Normal client ping response missing/duplicated')
            latencies.append((int(replies[0]['nano'])-int(request['nano']))/1e6)
        require(len(pings) >= count * max(5, int(window['elapsedSeconds'])), 'Load did not originate from sustained normal TCP clients')
        movement = [row for row in wire if row['direction'] == 'frame_send' and row['name'] == 'position_look']
        require(len(movement) >= len(pings), 'Ordinary movement load missing')
        if window['crossRegionAttack']:
            attack = window['crossRegionAttack']
            require(re.search(r': 19\.0f\s*$', attack['healthRead']) and attack['clients']['Scale01']['health'] == 19, 'Normal cross-region attack failed')
            target = attack['target']
            attacks = [row for row in raw if marks[name+'_attack_before'] < row['seq'] < marks[name+'_attack_after'] and row['direction'] == 'send' and row['name'] == 'use_entity']
            require(len(attacks) == 1 and attacks[0]['bot'] == 'Scale00' and attacks[0]['data']['target'] == target and attacks[0]['data']['mouse'] == 1, 'Attack did not come from normal player action')
            damage = [row for row in raw if marks[name+'_attack_before'] < row['seq'] < marks[name+'_attack_after'] and row['direction'] == 'receive' and row['name'] == 'damage_event' and row['bot'] == 'Scale01' and row['data']['entityId'] == target]
            require(len(damage) == 1, 'Cross-region attack feedback/occurrence mismatch')
        monitor_samples = [row for row in native if row['window'] == name]
        require(all(window['startMonitorNanos']-500_000_000 <= row['monitorNanos'] <= window['endMonitorNanos']+500_000_000 for row in monitor_samples), 'JVM sample outside actual performance window')
        perf = native_window(monitor_samples)
        rows.append(dict(name=name, count=count, distribution=window['distribution'], correctness='passed', phaseCoverage=phase_coverage,
                         observedOriginalBodyOverlapPairs=dict(overlaps), serialReasons={key:value-before['refusals'].get(key,0) for key,value in after['refusals'].items() if value > before['refusals'].get(key,0)},
                         pingMs=describe(latencies), performance=perf, crossRegionAttackPassed=window['crossRegionAttack'] is not None))
    final = result['final']
    require(final in states and not final['players'] and final['phases']['owners'] == final['phases']['pending'] == final['phases']['running'] == final['stateClaims'] == final['activeBatches'] == 0, 'Scale logout leaked owner/state/batch')
    require(final['network']['pending'] == final['network']['readers'] == final['network']['terminals'] == final['packets']['tasks']['pending'] == final['packets']['tasks']['terminals'] == final['packets']['connections'] == final['packets']['chatPending'] == 0, 'Scale logout leaked network/packet references')
    last = result['windows'][-1]
    for actor in last['after']['players']:
        saved = json.loads(subprocess.check_output(['node',str(TOOLS/'read_saved_nbt.cjs'),str(root/'world/playerdata'/(actor['uuid']+'.dat'))],text=True))
        require(saved['XpTotal'] == 7 and saved['Health'] == 20
                and sum(stack['count'] for stack in saved['Inventory'] if stack['id'] == 'minecraft:diamond') == 16
                and all(stack['id'] == 'minecraft:diamond' for stack in saved['Inventory']), 'Original saved player NBT violates resource/health conservation')
    log = (output / 'boot-1.server.log').read_text()
    require(not any(message in log for message in ['Failed to handle packet', 'Caused by:', 'Exception in thread', 'Error executing task', 'Native region worker attempted']), 'Original scale server failure')
    return dict(status='passed', scope='P5 restricted real-client scale and preliminary measurements, not complete P5 long-soak/mixed-Mod acceptance',
                version=install['version'], requested=install['playerPlanningRequested'], persistent=install['persistent'], windows=rows,
                installedMods=mods,**(dict(clientTopology=topology) if 'clientTopology' in result else {}),
                serverExits=result['serverExits'], clientsExit=result['clientsExit'], samplerExit=result['samplerExit'],
                limitations=['Restricted completed-advancement, ordinary-material inventory, inert loaded terrain fixture',
                             'Native tick ring update samples; equal-value same-slot updates may be unobservable, ring sampled via standard local JMX',
                             'Barrier stack waiting coverage is not exact barrier overhead; no pure-overhead value manufactured',
                             'Thread allocation is a sampled lower bound for threads that exit between samples; client/sampler CPU and logging share this WSL host',
                             'Single short window per cell is preliminary; no speedup claim without controlled repetitions, correctness and network-tail gates'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--installer', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    output = args.output or args.server / 'player-scale/independent-verdict.json'
    require(not output.exists(), 'Preserve previous scale verdict')
    try:
        result = verify(args.server.resolve(), args.installer.resolve())
    except BaseException as error:
        output.write_text(json.dumps(dict(status='failed', error=repr(error)), indent=2)+'\n')
        raise
    output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(dict(status='passed', windows=len(result['windows']), requested=result['requested'], persistent=result['persistent'])))


if __name__ == '__main__':
    main()
