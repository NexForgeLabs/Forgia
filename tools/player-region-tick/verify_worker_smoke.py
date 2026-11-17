#!/usr/bin/env python3
"""Recheck stored P2 production smoke against installed bytes, original diagnostics and actual packets."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
from run_worker_smoke import validate_samples


def verify(root, installer):
    output = root / 'worker-smoke'
    install = json.loads((root / 'installation.json').read_text())
    result = json.loads((output / 'result.json').read_text())
    assert result['status'] == 'passed' and result['serverExit'] == result['clientsExit'] == 0
    assert result['installation'] == install
    assert hashlib.sha256(installer.read_bytes()).hexdigest() == install['installerSha256']
    assert not list((root / 'mods').glob('*.jar')) and install['oracleSha256'] is None
    relative = f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
    installed_universal = root / 'libraries' / relative
    with zipfile.ZipFile(installer) as archive:
        assert installed_universal.read_bytes() == archive.read('maven/' + relative), 'installed core differs from installer'
    commands = [json.loads(line) for line in (output / 'commands.ndjson').read_text().splitlines()]
    stored_diagnostics = [json.loads(row['response']) for row in commands if row['command'] == 'neoforge regionthreading status players']
    assert stored_diagnostics[-1] == result['final']
    for label in ['independent', 'same-region', 'boundary']:
        samples = json.loads((output / (label + '.json')).read_text())
        assert samples and all(sample in stored_diagnostics for sample in samples), 'sample differs from actual original status response'
        assert all(sample['persistent'] == install['persistent'] and sample['requested'] == install['playerPlanningRequested'] for sample in samples)
        assert all(phase['failed'] == 0 for sample in samples for phase in sample['phases']['phases'].values()), 'player body failed before or within a sampled window'
    expected = install['playerPlanningRequested']
    windows = {label: validate_samples(json.loads((output / (label + '.json')).read_text()), expected, same)
               for label, same in [('independent', False), ('same-region', True)]}
    packets = [json.loads(line) for line in (output / 'packets.ndjson').read_text().splitlines()]
    attacks = [row for row in packets if row['direction'] == 'send' and row['name'] == 'use_entity' and row['data']['mouse'] == 1]
    assert len(attacks) == 1 and attacks[0]['bot'] == 'WorkerA'
    attack = result['crossRegionAttack']
    victim_id = attack['clients']['WorkerB']['id']
    assert attacks[0]['data']['target'] == victim_id
    damage = [row for row in packets if row['direction'] == 'receive' and row['name'] == 'damage_event'
              and row['bot'] == 'WorkerB' and row['data']['entityId'] == victim_id and int(row['nano']) > int(attacks[0]['nano'])]
    health = [row for row in packets if row['direction'] == 'receive' and row['name'] == 'update_health'
              and row['bot'] == 'WorkerB' and row['data']['health'] == attack['after'] and int(row['nano']) > int(attacks[0]['nano'])]
    reads = [float(re.search(r'([-\d.]+)f\s*$', row['response'])[1]) for row in commands if row['command'] == 'data get entity WorkerB Health']
    assert reads == [attack['before'], attack['after']] and attack['before'] == 20 and attack['after'] == 19
    assert len(damage) == 1 and health and attack['clients']['WorkerB']['health'] == attack['after']
    a, b = sorted(attack['coordinates'], key=lambda row: row['name'])
    assert a['ownerCurrent'] and b['ownerCurrent'] and (a['regionX'], a['regionZ']) != (b['regionX'], b['regionZ'])
    distance = sum((a[key] - b[key]) ** 2 for key in ['x', 'y', 'z']) ** .5
    assert distance <= 3
    final = result['final']
    assert final['phases']['owners'] == final['phases']['pending'] == final['phases']['running'] == 0
    assert final['network']['readers'] == final['network']['pending'] == final['activeBatches'] == final['stateClaims'] == 0
    assert not final['network']['frozen']
    log = (output / 'server.log').read_text()
    assert not any(message in log for message in ['Failed to handle packet', 'Caused by:', 'Exception caught in connection', 'Error executing task'])
    return {'status': 'passed', 'scope': 'P2 production-only two-player smoke; not P3-P5 full acceptance',
            'version': install['version'], 'persistent': install['persistent'], 'requested': expected,
            'windows': windows, 'finalPhases': final['phases']['phases'], 'network': final['network'],
            'crossRegionAttack': {'before': reads[0], 'after': reads[1], 'clientDamageEvents': len(damage),
                                  'clientHealth': attack['clients']['WorkerB']['health'], 'distance': distance,
                                  'regions': [[a['regionX'], a['regionZ']], [b['regionX'], b['regionZ']]]},
            'installerSha256': install['installerSha256'], 'installedUniversalSha256': hashlib.sha256(installed_universal.read_bytes()).hexdigest(),
            'cleanup': 'zero live owners/plans/workers/network callbacks/batches/state claims; server/client exit 0',
            'limitations': ['Restricted stationary empty-inventory completed-advancement fixture',
                            'Internal complete-call timestamps; no observer Mod or independent method instrumentation',
                            'No MSPT speedup, full failure/lifecycle matrix, mixed-mod parallelism or P4 owner routing claim',
                            'Vanilla protocol library recipe PartialReadError diagnostics are preserved']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--installer', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    path = args.output or args.server / 'worker-smoke/independent-verdict.json'
    if path.exists():
        raise FileExistsError('Preserve existing verdict: ' + str(path))
    try:
        verdict = verify(args.server.resolve(), args.installer.resolve())
    except BaseException as error:
        path.write_text(json.dumps({'status': 'failed', 'error': repr(error)}, indent=2) + '\n')
        raise
    path.write_text(json.dumps(verdict, indent=2) + '\n')
    print(json.dumps(verdict))


if __name__ == '__main__':
    main()
