#!/usr/bin/env python3
"""Validate full original entry nesting/order and actual off-mode damage/lifecycle evidence."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path


def validate(root):
    run = root / 'run'
    result = json.loads((run / 'result.json').read_text())
    assert result['status'] == 'passed', result
    assert result['serverExit'] == 0 and result['clientsExit'] == 0, result
    rows = [json.loads(line) for line in (root / 'player-oracle.ndjson').read_text().splitlines()]
    assert rows and rows[0]['kind'] == 'TRACE_START' and rows[-1]['kind'] == 'TRACE_STOP'
    assert [row['seq'] for row in rows] == list(range(1, len(rows) + 1)), 'trace truncated or repeated'
    assert all(row['serverThread'] for row in rows), 'off original tick/packet body ran outside server thread'
    assert not any(row['kind'].endswith('_THROW') for row in rows), 'original body failed'
    marks = {row['detail']: row for row in rows if row['kind'] == 'MARK'}
    assertions = []
    entry = ['CONNECTION_START', 'LISTENER_START', 'DOTICK_START', 'PLAYER_PRE', 'PLAYER_POST', 'DOTICK_RETURN', 'LISTENER_RETURN', 'CONNECTION_RETURN']
    coverage = {}
    windows = [('single', ['OracleA']), ('multi', ['OracleA', 'OracleB']), ('frozen', ['OracleA', 'OracleB'])]
    windows += [(label, ['OracleA', 'OracleB']) for label in ['spectator', 'riding'] if label + '_begin' in marks]
    for label, names in windows:
        start, end = marks[label + '_begin']['tick'], marks[label + '_end']['tick']
        # Exclude boundary ticks where command execution may occur inside a frame.
        ticks = range(start + 1, end)
        assert len(ticks) >= 3, (label, start, end)
        coverage[label] = {'completeTicks': len(ticks), 'players': names}
        for tick in ticks:
            frame = [row for row in rows if row['tick'] == tick]
            kinds = [row['kind'] for row in frame]
            assert kinds.count('SERVER_PRE') == kinds.count('SERVER_POST') == 1, (label, tick, 'server frame')
            assert kinds.count('CONNECTIONS_START') == kinds.count('CONNECTIONS_RETURN') == 1
            connection_start = next(row['seq'] for row in frame if row['kind'] == 'CONNECTIONS_START')
            connection_return = next(row['seq'] for row in frame if row['kind'] == 'CONNECTIONS_RETURN')
            list_start = next(row['seq'] for row in frame if row['kind'] == 'PLAYERLIST_START')
            assert connection_return < list_start, ('PlayerList moved before connection join', tick)
            levels = defaultdict(list)
            for row in frame:
                if row['kind'] in ['LEVEL_PRE', 'LEVEL_POST', 'BE_START', 'BE_RETURN']:
                    levels[row['detail']].append(row)
            assert len(levels) == 3, ('missing dimension phase', tick)
            # Do not freeze HashMap dimension iteration order across processes. Freeze relative barriers.
            previous_end = 0
            for dimension, events in sorted(levels.items(), key=lambda pair: pair[1][0]['seq']):
                observed = [row['kind'] for row in events]
                assert observed in [['LEVEL_PRE', 'BE_START', 'BE_RETURN', 'LEVEL_POST'], ['LEVEL_PRE', 'LEVEL_POST']], (dimension, tick, events)
                # Empty dimensions stop simulating after their vanilla empty-time threshold. The
                # level events still bracket the call; do not invent a BE phase which did not run.
                assert previous_end < events[0]['seq'] < events[-1]['seq'] < connection_start
                previous_end = events[-1]['seq']
            for name in names:
                player_rows = [row for row in frame if row.get('name') == name]
                counts = Counter(row['kind'] for row in player_rows)
                for kind in ['ENTITY_START', 'ENTITY_RETURN', *entry]:
                    assert counts[kind] == 1, (label, tick, name, kind, counts)
                observed = [row['kind'] for row in player_rows if row['kind'] in entry]
                assert observed == entry, (label, tick, name, observed)
                entity_start = next(row for row in player_rows if row['kind'] == 'ENTITY_START')
                entity_return = next(row for row in player_rows if row['kind'] == 'ENTITY_RETURN')
                if name == 'OracleA' and label in ['riding', 'spectator']:
                    assert entity_start[label if label == 'spectator' else 'passenger'], entity_start
                be_start = next(row['seq'] for row in frame if row['kind'] == 'BE_START' and row['detail'] == entity_start['dimension'])
                assert entity_start['seq'] < entity_return['seq'] < be_start < connection_start
                assert entity_start.get('indexedRegions') == 1, entity_start
                if entity_start.get('schema', 1) >= 2:
                    assert (entity_start['ownerRegionX'], entity_start['ownerRegionZ'], entity_start['ownerDimension']) == (entity_start['regionX'], entity_start['regionZ'], entity_start['dimension']), entity_start
        assertions.append(label + ': original two full entries once per stable tick; event/BE/Level/connection/PlayerList order')
    packets = [json.loads(line) for line in (run / 'packets.ndjson').read_text().splitlines()]
    sends = [index for index, row in enumerate(packets) if row['direction'] == 'send' and row['name'] == 'use_entity']
    assert len(sends) == 4, 'Expected three legitimate attack packets and one remote refusal control'
    for attack_index, attack in enumerate(result['attacks']):
        label = attack['label']
        start, end = marks[label + '_before']['seq'], marks[label + '_after']['seq']
        samples = [row for row in rows if start < row['seq'] < end]
        damage = [row for row in samples if row['kind'] == 'DAMAGE_POST' and row.get('name') == attack['victim']]
        assert len(damage) == 1, ('damage must occur exactly once', attack, damage)
        assert damage[0]['health'] == attack['after'] < attack['before'], (attack, damage)
        assert attack['clientHealth'] == attack['after'], ('client/server health mismatch', attack)
        states = [row for row in rows if start < row['seq'] < start + 4 and row['kind'] == 'STATE']
        positions = {row['name']: row for row in states}
        a, b = positions[attack['attacker']], positions[attack['victim']]
        if a.get('schema', 1) >= 2:
            for state in [a, b]:
                assert (state['ownerRegionX'], state['ownerRegionZ'], state['ownerDimension']) == (state['regionX'], state['regionZ'], state['dimension']), state
        region_a, region_b = (a['regionX'], a['regionZ']), (b['regionX'], b['regionZ'])
        distance = ((a['x'] - b['x']) ** 2 + (a['y'] - b['y']) ** 2 + (a['z'] - b['z']) ** 2) ** 0.5
        assert distance <= 3, ('fixture outside original touch range', positions)
        if label.startswith('cross'):
            assert region_a != region_b and abs(region_a[0] - region_b[0]) == 1
        else:
            assert region_a == region_b
        attack.update(damageEvents=1, attackerRegion=region_a, victimRegion=region_b, distance=distance)
        sent = packets[sends[attack_index]]
        feedback_window = packets[sends[attack_index] + 1:sends[attack_index + 1]]
        assert sent['bot'] == attack['attacker'] and sent['data']['mouse'] == 1 and sent['data']['target'] == b['id'], sent
        damage_feedback = [row for row in feedback_window if row['direction'] == 'receive' and row['name'] == 'damage_event' and row['bot'] == attack['victim'] and row['data']['entityId'] == b['id']]
        health_feedback = [row for row in feedback_window if row['direction'] == 'receive' and row['name'] == 'update_health' and row['bot'] == attack['victim'] and row['data']['health'] == attack['after']]
        assert len(damage_feedback) == 1 and health_feedback, ('actual protocol feedback missing/repeated', attack, damage_feedback, health_feedback)
        attack['clientDamageEvents'] = len(damage_feedback)
        attack['packetDamageResponseMs'] = (int(damage_feedback[0]['nano']) - int(sent['nano'])) / 1e6
        attack['packetHealthResponseMs'] = (int(health_feedback[0]['nano']) - int(sent['nano'])) / 1e6
        assert attack['packetDamageResponseMs'] >= 0 and attack['packetHealthResponseMs'] >= 0
    far = [row for row in rows if marks['far_before']['seq'] < row['seq'] < marks['far_after']['seq'] and row['kind'] == 'DAMAGE_POST']
    assert not far and result['remoteControl']['before'] == result['remoteControl']['after']
    counts = Counter(row['kind'] for row in rows)
    assert counts['DIMENSION'] >= 2 and counts['CLONE'] == 1 and counts['RESPAWN'] == 1, counts
    logins = [row for row in rows if row['kind'] == 'LOGIN' and row.get('name') == 'OracleB']
    assert len(logins) == 2 and logins[0]['uuid'] == logins[1]['uuid'] and logins[0]['instance'] != logins[1]['instance']
    clones = [row for row in rows if row['kind'] == 'CLONE']
    old_instance = int(clones[0]['detail'].split('=')[1])
    assert clones[0]['instance'] != old_instance, 'respawn did not change player instance'
    assert not any(row['seq'] > clones[0]['seq'] and row.get('instance') == old_instance and row['kind'] in ['ENTITY_START', 'CONNECTION_START'] for row in rows), 'old respawn instance ticked again'
    for logout in [row for row in rows if row['kind'] == 'LOGOUT']:
        assert not any(row['seq'] > logout['seq'] and row.get('instance') == logout['instance'] and row['kind'] in ['ENTITY_START', 'CONNECTION_START'] for row in rows), 'old disconnected instance ticked again'
    criterion = result.get('criterionProof')
    if criterion is not None:
        assert criterion['before'] == {'OracleA': 1, 'OracleB': 1}
        assert criterion['afterRevoke'] == {'OracleA': 2, 'OracleB': 1}
        events = [row for row in rows if row['kind'] == 'CRITERION_PROGRESS']
        for name, expected in [('OracleA', ['GRANT:tick', 'REVOKE:tick', 'GRANT:tick']), ('OracleB', ['GRANT:tick'])]:
            assert [row['detail'] for row in events if row.get('name') == name] == expected, (name, events)
        assert criterion['storage'] == json.loads((root / 'player-criterion-storage.json').read_text())
        for event in [row for row in events if row['detail'] == 'GRANT:tick']:
            assert any(row['kind'] == 'ENTITY_START' and row.get('instance') == event['instance'] and row['tick'] == event['tick'] and row['seq'] < event['seq'] for row in rows)
            assert any(row['kind'] == 'ENTITY_RETURN' and row.get('instance') == event['instance'] and row['tick'] == event['tick'] and row['seq'] > event['seq'] for row in rows)
        assert criterion['storage']['OracleA']['totalExperience'] == 6
        assert criterion['storage']['OracleB']['totalExperience'] == 3
        assert criterion['storage']['OracleA']['advancementInstance'] != criterion['storage']['OracleB']['advancementInstance']
    planning = None
    planning_path = root / 'player-planning.json'
    if planning_path.exists():
        planning = json.loads(planning_path.read_text())
        snapshot = planning['snapshot']
        assert planning['requested'] is True
        assert snapshot['pending'] == snapshot['running'] == snapshot['owners'] == 0, 'Player planning retained live state after logout'
        assert len(snapshot['recent']) <= 256
        assert all(row['serverSession'] == snapshot['serverSession'] for row in snapshot['recent'])
        assert len({row['id'] for row in snapshot['recent']}) == len(snapshot['recent'])
        for phase in ['ENTITY', 'CONNECTION']:
            phase_counts = snapshot['phases'][phase]
            actual_entries = sum(row['kind'] == ('ENTITY_START' if phase == 'ENTITY' else 'CONNECTION_START') and 'name' in row for row in rows)
            assert phase_counts['serial'] == actual_entries, (phase, 'ledger counts do not match full original entrance trace', phase_counts, actual_entries)
            assert phase_counts['serial'] == phase_counts['started'] + phase_counts['untrackedSerial']
            assert phase_counts['started'] > 100 and phase_counts['worker'] == 0
            assert phase_counts['started'] == phase_counts['joined'] == phase_counts['returned']
            assert phase_counts['failed'] == 0
            assert phase_counts['reasons'].get('DUPLICATE', 0) == 0
        inputs = json.loads((run / 'inputs.json').read_text())
        assert planning['persistentWorkers'] == inputs['installation']['persistent'], 'Actual engine pool differs from requested fixture'
    return {'status': 'passed', 'criterionProof': criterion, 'playerPlanning': planning, 'traceSchema': rows[0].get('schema', 1), 'authoritativeRegionCoordinatesVerified': rows[0].get('schema', 1) >= 2,
            'protocolDiagnostics': {'partialReadErrors': (run / 'clients.stdout.log').read_text().count('PartialReadError:') if (run / 'clients.stdout.log').exists() else None,
                                    'rawFrames': sum(row['direction'] == 'frame' for row in packets),
                                    'decodedPackets': sum(row['direction'] == 'decoded' for row in packets),
                                    'limitation': 'NeoForge recipe list is not decoded by this vanilla protocol library; original diagnostics preserved. No crafting compatibility claim.'},
            'scope': 'Original full-entrance oracle and optional serial planning/criterion proof; no player worker certification', 'traceRows': len(rows),
            'coverage': coverage, 'assertions': assertions, 'counts': dict(counts), 'attacks': result['attacks'],
            'remoteControl': result['remoteControl'], 'lifecycle': {'dimensionEvents': counts['DIMENSION'], 'cloneEvents': counts['CLONE'],
            'respawnEvents': counts['RESPAWN'], 'reconnectedWithNewInstance': True}, 'serverExit': result['serverExit'], 'clientsExit': result['clientsExit']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='New verdict path; existing evidence is refused')
    args = parser.parse_args()
    output = args.output or args.server / 'run' / 'oracle-verdict.json'
    if output.exists():
        raise FileExistsError(output)
    try:
        result = validate(args.server)
    except BaseException as error:
        output.write_text(json.dumps({'status': 'failed', 'error': repr(error)}, indent=2) + '\n')
        raise
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
