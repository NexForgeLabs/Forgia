#!/usr/bin/env python3
"""End-to-end installed-server smoke verifying player ticks run on region workers with a clean third-party mod + mod-owned mixin installed."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/modpack-matrix'))
from rcon import Rcon
from run_worker_smoke import validate_samples


def build_clean_mod_jar(server_root: Path, java_bin: Path, dest_jar: Path):
    probe_dir = Path(__file__).resolve().parent / 'clean-mod-probe'
    javac = java_bin.parent / 'javac'
    libs = server_root / 'libraries'
    cp_jars = sorted(set(libs.rglob('*.jar')))
    cp = ':'.join(str(p) for p in cp_jars)
    sources = sorted(str(p) for p in (probe_dir / 'java').rglob('*.java'))
    with tempfile.TemporaryDirectory() as tmp:
        classes_dir = Path(tmp) / 'classes'
        classes_dir.mkdir()
        subprocess.run([str(javac), '-proc:none', '-cp', cp, '-d', str(classes_dir), *sources], check=True)
        with zipfile.ZipFile(dest_jar, 'w') as zf:
            for root_dir in (classes_dir, probe_dir / 'resources'):
                for path in sorted(root_dir.rglob('*')):
                    if path.is_file():
                        zf.write(path, path.relative_to(root_dir).as_posix())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    root = args.server.resolve()
    if (root / 'world/level.dat').exists():
        raise FileExistsError('Use a fresh isolated installation; preserve retained worlds')
    install = json.loads((root / 'installation.json').read_text())
    mod_jar = root / 'mods' / 'clean_mod_probe-1.0.jar'
    build_clean_mod_jar(root, args.java.resolve(), mod_jar)
    mod_sha = hashlib.sha256(mod_jar.read_bytes()).hexdigest()

    output = root / 'clean-mod-smoke'
    output.mkdir(exist_ok=False)
    password = re.search(r'^rcon.password=(.+)$', (root / 'server.properties').read_text(), re.M)[1]
    summary = {
        'status': 'running',
        'installation': install,
        'cleanModProbeSha256': mod_sha,
        'scope': 'Installed-server two-player smoke with clean third-party mod + mod-owned mixin',
        'windows': {},
    }
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
        summary['windows'][label] = {
            'lastRefusal': rows[-1]['lastRefusal'],
            'refusals': rows[-1]['refusals'],
            'admittedWithMods': rows[-1].get('admittedWithMods'),
            'phases': rows[-1]['phases']['phases'],
            'players': rows[-1]['players'],
        }
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
        assert final.get('admittedWithMods', 0) > 0, f'Expected admittedWithMods > 0, got {final}'
        for bad in ('UNKNOWN_MOD', 'UNKNOWN_TRANSFORM', 'UNKNOWN_LISTENER'):
            for key in final.get('refusals', {}):
                assert bad not in key, f'Unexpected extension refusal {key} in {final["refusals"]}'
        expected = install['playerPlanningRequested']
        summary['verification'] = {
            'independent': validate_samples(separate, expected),
            'same-region': validate_samples(same, expected, True),
            'admittedWithMods': final['admittedWithMods'],
        }
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
        (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
        command_log.close()
        log.close()
        bot_error.close()
    server_log = (output / 'server.log').read_text(errors='replace')
    assert 'clean_mod_probe' in server_log, 'clean_mod_probe mod was not loaded by server'
    assert summary['serverExit'] == summary['clientsExit'] == 0, summary
    print(json.dumps({
        'status': summary['status'],
        'admittedWithMods': summary['final']['admittedWithMods'],
        'finalPhases': summary['final']['phases']['phases'],
        'refusals': summary['final']['refusals'],
        'verification': summary['verification'],
    }, indent=2))


if __name__ == '__main__':
    main()
