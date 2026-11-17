#!/usr/bin/env python3
"""P2 production-only installed-server smoke; no oracle Mod, callback permission or fake player."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/modpack-matrix'))
from rcon import Rcon


def validate_samples(samples, expect_workers, same_region=False):
    records = {}
    lower_tick = max((row['tick'] for row in samples[0]['phases']['recent']), default=-1)
    for sample in samples:
        assert sample['activeBatches'] == sample['stateClaims'] == 0, sample
        assert not sample['network']['frozen'] and sample['network']['readers'] >= 0
        for record in sample['phases']['recent']:
            if record['tick'] > lower_tick:
                records[record['id']] = record
    coordinates = [(row['regionX'], row['regionZ']) for row in samples[-1]['players']]
    assert len(coordinates) == 2 and all(row['ownerCurrent'] for row in samples[-1]['players'])
    assert (coordinates[0] == coordinates[1]) == same_region, coordinates
    result = {}
    for phase in ['ENTITY', 'CONNECTION']:
        rows = [row for row in records.values() if row['phase'] == phase and row['started']]
        worker = [row for row in rows if row['worker']]
        assert all(row['state'] == 'JOINED' and row.get('failureClass') is None for row in rows), rows
        assert len({(row['tick'], row['playerInstance']) for row in rows}) == len(rows), 'duplicate complete phase invocation'
        assert all(row['returnNanos'] >= row['startNanos'] > 0 for row in rows), rows
        overlap = []
        for i, left in enumerate(worker):
            for right in worker[:i]:
                if left['tick'] == right['tick'] and left['player'] != right['player']:
                    width = min(left['returnNanos'], right['returnNanos']) - max(left['startNanos'], right['startNanos'])
                    if width > 0:
                        overlap.append({'tick': left['tick'], 'nanos': width, 'threads': [left['thread'], right['thread']], 'ids': [left['id'], right['id']]})
        if expect_workers:
            assert len(worker) >= 10, (phase, 'no complete original worker coverage', samples[-1]['lastRefusal'], samples[-1]['refusals'])
            if same_region:
                assert not overlap, (phase, 'same-region bodies overlapped', overlap)
            else:
                assert overlap, (phase, 'independent full bodies did not actually overlap')
                assert any(len(set(pair['threads'])) == 2 for pair in overlap)
        else:
            assert not worker, (phase, 'off mode entered a worker')
        result[phase] = {'sampledCalls': len(rows), 'workerCalls': len(worker), 'overlapCount': len(overlap), 'overlapExamples': overlap[:5]}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--allow-unimplemented', action='store_true', help='Investigation only: collect rejected admission details without claiming pass')
    args = parser.parse_args()
    root = args.server.resolve()
    if (root / 'world/level.dat').exists():
        raise FileExistsError('Use a fresh isolated installation; preserve retained worlds')
    install = json.loads((root / 'installation.json').read_text())
    assert install['oracleSha256'] is None and not list((root / 'mods').glob('*.jar')), 'production smoke must not install an observer Mod'
    output = root / 'worker-smoke'
    output.mkdir(exist_ok=False)
    password = re.search(r'^rcon.password=(.+)$', (root / 'server.properties').read_text(), re.M)[1]
    summary = {'status': 'running', 'installation': install, 'scope': 'P2 complete production player calls; no Mod instrumentation', 'windows': {}}
    command_log = (output / 'commands.ndjson').open('w')
    log = (output / 'server.log').open('wb')
    process = subprocess.Popen(install['launch'], cwd=root, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    (output / 'server.pid').write_text(str(process.pid) + '\n')
    client = None
    bots = None
    responses = queue.Queue()
    request_id = 0
    bot_error = (output / 'clients.stderr.log').open('w')

    def command(text):
        begin = time.monotonic_ns()
        response = client.command(text)
        command_log.write(json.dumps({'command': text, 'response': response, 'startNs': begin, 'endNs': time.monotonic_ns()}) + '\n')
        command_log.flush()
        if any(error in response for error in ['Unknown or incomplete command', 'Incorrect argument', 'That position is not loaded', 'No entity was found']):
            raise AssertionError(response)
        return response

    def request(op, **data):
        nonlocal request_id
        request_id += 1
        bots.stdin.write(json.dumps({'requestId': request_id, 'op': op, **data}) + '\n')
        bots.stdin.flush()
        row = responses.get(timeout=30)
        assert row.get('requestId') == request_id and 'error' not in row, row
        return row['result']

    def diagnostics():
        return json.loads(command('neoforge regionthreading status players'))

    def sample(label):
        rows = []
        for _ in range(12):
            time.sleep(.5)
            rows.append(diagnostics())
        (output / (label + '.json')).write_text(json.dumps(rows, indent=2) + '\n')
        summary['windows'][label] = {'lastRefusal': rows[-1]['lastRefusal'], 'phases': rows[-1]['phases']['phases'], 'players': rows[-1]['players']}
        return rows

    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('server stopped during startup')
            try:
                client = Rcon('127.0.0.1', install['rconPort'], password, timeout=15)
                break
            except OSError:
                time.sleep(.5)
        assert client is not None, 'server startup timeout'
        for rule in ['doMobSpawning false', 'naturalRegeneration false', 'randomTickSpeed 0', 'spawnChunkRadius 0', 'doWeatherCycle false']:
            command('gamerule ' + rule)
        command('forceload add 0 0 143 47')
        time.sleep(2)
        command('fill 16 99 16 32 99 32 minecraft:stone')
        command('fill 112 99 16 128 99 32 minecraft:stone')
        command('fill 40 99 16 55 99 32 minecraft:stone')
        bots = subprocess.Popen(['node', str(REPO / 'tools/player-region-tick/clients.cjs'), str(output / 'packets.ndjson')],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=bot_error, text=True)
        def read_responses():
            with (output / 'clients.stdout.log').open('w') as capture:
                for line in bots.stdout:
                    capture.write(line)
                    capture.flush()
                    try:
                        value = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(value, dict) and 'requestId' in value:
                        responses.put(value)
        threading.Thread(target=read_responses, daemon=True).start()
        a = request('connect', name='WorkerA', port=install['port'])
        b = request('connect', name='WorkerB', port=install['port'])
        # This first conservative production branch requires completed optional location criteria,
        # empty inventory and stationary inert support. Commands establish that documented fixture;
        # no criterion, menu or item functionality is removed from the original player tick.
        command('advancement grant @a everything')
        command('clear @a')
        command('experience add @a -1000000 points')
        command('experience set @a 0 levels')
        command('experience set @a 0 points')
        command('tp WorkerA 24 100 24')
        command('tp WorkerB 120 100 24')
        time.sleep(3)
        separate = sample('independent')
        command('tp WorkerB 30 100 24')
        time.sleep(2)
        same = sample('same-region')
        command('tp WorkerA 47 100 24')
        command('tp WorkerB 49 100 24')
        time.sleep(2)
        boundary = sample('boundary')
        before = float(re.search(r'([-\d.]+)f\s*$', command('data get entity WorkerB Health'))[1])
        request('packet', name='WorkerA', packet='use_entity', data={'target': b['id'], 'mouse': 1, 'sneaking': False})
        time.sleep(1)
        after = float(re.search(r'([-\d.]+)f\s*$', command('data get entity WorkerB Health'))[1])
        state = request('snapshot')
        assert after < before and state['WorkerB']['health'] == after, 'cross-region standard attack did not actually damage target'
        summary['crossRegionAttack'] = {'before': before, 'after': after, 'clients': state, 'coordinates': boundary[-1]['players']}
        request('close')
        bots.wait(timeout=10)
        time.sleep(.5)
        final = diagnostics()
        summary['final'] = final
        assert final['phases']['owners'] == final['phases']['pending'] == final['phases']['running'] == 0
        assert final['network']['pending'] == final['network']['readers'] == 0
        assert final['activeBatches'] == final['stateClaims'] == 0
        if args.allow_unimplemented:
            summary['status'] = 'investigation-only'
        else:
            expected = install['playerPlanningRequested']
            summary['verification'] = {'independent': validate_samples(separate, expected), 'same-region': validate_samples(same, expected, True)}
            summary['status'] = 'passed'
    except BaseException as error:
        summary['status'] = 'failed'
        summary['error'] = repr(error)
        raise
    finally:
        if bots and bots.poll() is None:
            try: request('close')
            except Exception: bots.terminate()
            try: bots.wait(timeout=10)
            except subprocess.TimeoutExpired: bots.kill(); bots.wait()
        if process.poll() is None:
            try:
                if client: client.command('stop')
                else: process.stdin.write(b'stop\n'); process.stdin.flush()
                process.wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        if client: client.close()
        summary['serverExit'] = process.returncode
        summary['clientsExit'] = bots.returncode if bots else None
        summary['driverSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
        command_log.close()
        log.close()
        bot_error.close()
    assert summary['serverExit'] == summary['clientsExit'] == 0, summary
    print(json.dumps({'status': summary['status'], 'verification': summary.get('verification'), 'crossRegionAttack': summary.get('crossRegionAttack')}))


if __name__ == '__main__':
    main()
