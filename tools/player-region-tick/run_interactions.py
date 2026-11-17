#!/usr/bin/env python3
"""Reusable real-TCP combat/entity matrix. Commands only arrange fixtures/read state."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
import time

from run_baseline import REPO, Rcon

# Coordinates are inputs, never accepted as evidence of actual engine ownership.
GEOMETRIES = {
    'positive_x': ((47, 8.5), (49, 8.5)),
    'negative_x': ((-49, 8.5), (-47, 8.5)),
    'zero_x': ((-1, 8.5), (1, 8.5)),
    'positive_z': ((8.5, 47), (8.5, 49)),
    'negative_z': ((8.5, -49), (8.5, -47)),
    'positive_corner': ((47.2, 47.2), (48.8, 48.8)),
    'negative_corner': ((-48.8, -48.8), (-47.2, -47.2)),
    'mixed_corner': ((-0.8, 47.2), (0.8, 48.8)),
    'same': ((44, 8.5), (46, 8.5)),
    'far': ((41, 8.5), (54, 8.5)),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    args = parser.parse_args()
    root = args.server.resolve()
    if (root / 'player-oracle.ndjson').exists() or (root / 'world/level.dat').exists():
        raise FileExistsError('Use a fresh installation per suite; preserve the existing oracle trace')
    installation = json.loads((root / 'installation.json').read_text())
    output = root / 'interaction-run'
    output.mkdir(exist_ok=False)
    sources = ['run_interactions.py', 'clients.cjs', 'verify_interactions.py', 'decode_interaction_frames.cjs', 'package-lock.json', 'reviewed_artifacts.py']
    hashes = {}
    for name in sources:
        source = REPO / 'tools/player-region-tick' / name
        shutil.copy2(source, output / name)
        hashes[name] = hashlib.sha256(source.read_bytes()).hexdigest()
    (output / 'inputs.json').write_text(json.dumps({
        'schema': 3, 'installation': installation, 'sourceHashes': hashes, 'geometries': GEOMETRIES,
        'actualOracleSha256': hashlib.sha256((root / 'mods/forgia-player-oracle-1.0.jar').read_bytes()).hexdigest()
    }, indent=2) + '\n')
    password = re.search(r'^rcon.password=(.+)$', (root / 'server.properties').read_text(), re.M)[1]
    commands = (output / 'commands.ndjson').open('x')
    log = (output / 'server.log').open('xb')
    process = subprocess.Popen(installation['launch'], cwd=root, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    (output / 'server.pid').write_text(str(process.pid) + '\n')
    summary = {'schema': 3, 'status': 'running', 'playerWorkersVerified': False, 'scenarios': []}
    client = bots = bot_log = None
    responses = queue.Queue()
    request_id = 0

    def command(text):
        start = time.monotonic_ns()
        response = client.command(text)
        commands.write(json.dumps({'command': text, 'response': response, 'startNs': start, 'endNs': time.monotonic_ns()}) + '\n')
        commands.flush()
        if any(error in response for error in ['Unknown or incomplete command', 'Incorrect argument', 'That position is not loaded', 'No entity was found']):
            raise AssertionError(response)
        return response

    def request(op, **data):
        nonlocal request_id
        request_id += 1
        bots.stdin.write(json.dumps({'requestId': request_id, 'op': op, **data}) + '\n')
        bots.stdin.flush()
        row = json.loads(responses.get(timeout=30))
        if row.get('error') or row['requestId'] != request_id:
            raise AssertionError(row)
        return row['result']

    def marker(label):
        command('player_oracle mark ' + label)

    def position(name, xy):
        command(f'tp {name} {xy[0]} 100 {xy[1]}')

    def health(selector):
        response = command(f'data get entity {selector} Health')
        return float(re.search(r'([-\d.]+)f\s*$', response)[1])

    def window(label, actor, target, target_id, operation, expected):
        time.sleep(0.7)  # original attack cooldown/invulnerability; no extra simulated game body
        marker(label + '_before')
        before = health(target)
        inv_before = command(f'data get entity {actor} Inventory')
        request('mark', label=label + '_before')
        token = request('packet', name=actor, packet='use_entity', data={
            'target': target_id, 'mouse': 1 if operation == 'attack' else 0,
            **({} if operation == 'attack' else {'hand': 0}), 'sneaking': False})
        # Keep a full quiet observation interval even when rejection/cancellation is expected.
        time.sleep(0.6)
        after = health(target)
        inv_after = command(f'data get entity {actor} Inventory')
        request('mark', label=label + '_after')
        marker(label + '_after')
        row = {'label': label, 'actor': actor, 'target': target, 'targetId': target_id,
               'operation': operation, 'expected': expected, 'before': before, 'after': after,
               'inventoryBefore': inv_before, 'inventoryAfter': inv_after, 'send': token,
               'clients': request('snapshot')}
        summary['scenarios'].append(row)
        (output / 'progress.json').write_text(json.dumps(summary, indent=2) + '\n')
        if operation == 'attack' and ((after < before) != (expected == 'damage')):
            raise AssertionError('Actual health differs from expected: ' + repr(row))

    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f'Server exited {process.returncode}')
            try:
                client = Rcon('127.0.0.1', installation['rconPort'], password, timeout=10)
                break
            except OSError:
                time.sleep(0.5)
        if client is None:
            raise TimeoutError('server startup timeout')
        for rule in ['doMobSpawning false', 'naturalRegeneration false', 'doWeatherCycle false', 'randomTickSpeed 0', 'spawnChunkRadius 0']:
            command('gamerule ' + rule)
        command('difficulty normal')
        command('player_oracle start')
        # Small separated pads; all tested operations below come exclusively from client packets.
        pads = set()
        for pair in GEOMETRIES.values():
            for x, z in pair:
                key = (int(x) // 16, int(z) // 16)
                pads.add(key)
                command(f'forceload add {key[0]*16} {key[1]*16}')
        time.sleep(2)
        for x, z in pads:
            command(f'fill {x*16} 99 {z*16} {x*16+15} 99 {z*16+15} minecraft:stone')
        bot_log = (output / 'clients.stderr.log').open('xb')
        bots = subprocess.Popen(['node', str(REPO / 'tools/player-region-tick/clients.cjs'), str(output / 'packets.ndjson')],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=bot_log, text=True)

        def read_responses():
            with (output / 'clients.stdout.log').open('x') as capture:
                for line in bots.stdout:
                    capture.write(line); capture.flush()
                    try:
                        value = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(value, dict) and 'requestId' in value:
                        responses.put(line)
        threading.Thread(target=read_responses, daemon=True).start()
        ids = {}
        for name, point in zip(['OracleA', 'OracleB'], GEOMETRIES['positive_x']):
            ids[name] = request('connect', name=name, port=installation['port'])['id']
            position(name, point)
            command('clear ' + name)
        time.sleep(3)
        for geometry, (a, b) in GEOMETRIES.items():
            position('OracleA', a); position('OracleB', b)
            time.sleep(0.4)
            for actor, victim in [('OracleA', 'OracleB'), ('OracleB', 'OracleA')]:
                # Undo vanilla knockback between independent trials.
                position('OracleA', a); position('OracleB', b)
                window(f'pvp_{geometry}_{actor[-1]}_{victim[-1]}', actor, victim, ids[victim], 'attack',
                       'reject' if geometry == 'far' else 'damage')
        cow = '@e[type=minecraft:cow,tag=oracle_target,limit=1]'
        command('summon minecraft:cow 49 100 8.5 {Tags:["oracle_target"],NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Attributes:[{Name:"minecraft:generic.knockback_resistance",Base:1d}]}')
        time.sleep(0.3)
        marker('cow_created')
        # The trace remains live after the explicit marker flush. Ignore only the currently
        # incomplete trailing write; the independent verifier requires the final complete trace.
        trace = [json.loads(line) for line in (root / 'player-oracle.ndjson').read_text().split('\n')[:-1]]
        cow_id = next(row['id'] for row in reversed(trace) if row['kind'] == 'TARGET_STATE')
        for geometry in ['positive_x', 'negative_x', 'positive_corner']:
            a, b = GEOMETRIES[geometry]
            for reverse in [False, True]:
                actor = 'OracleB' if reverse else 'OracleA'
                origin, destination = (b, a) if reverse else (a, b)
                position(actor, origin)
                position('OracleA' if reverse else 'OracleB', (destination[0], destination[1]+0.9))
                command(f'tp {cow} {destination[0]} 100 {destination[1]}')
                command(f'data merge entity {cow} {{Health:10f,Motion:[0d,0d,0d]}}')
                command('clear ' + actor)
                window(f'cow_{geometry}_{actor[-1]}_attack', actor, cow, cow_id, 'attack', 'damage')
                command(f'item replace entity {actor} weapon.mainhand with minecraft:bucket 1')
                window(f'cow_{geometry}_{actor[-1]}_milk', actor, cow, cow_id, 'interact', 'milk')
        a, b = GEOMETRIES['positive_x']
        position('OracleA', a); position('OracleB', b)
        command(f'tp {cow} {b[0]} 100 {b[1]}')
        command(f'tag {cow} add oracle_cancel')
        command('clear OracleA')
        window('cow_cancel_attack', 'OracleA', cow, cow_id, 'attack', 'cancel')
        command('item replace entity OracleA weapon.mainhand with minecraft:bucket 1')
        window('cow_cancel_milk', 'OracleA', cow, cow_id, 'interact', 'cancel')
        command(f'tag {cow} remove oracle_cancel')
        command('clear OracleA')
        window('cow_empty_pass', 'OracleA', cow, cow_id, 'interact', 'pass')
        command('item replace entity OracleA weapon.mainhand with minecraft:bucket 1')
        position('OracleA', GEOMETRIES['far'][0])
        command(f'tp {cow} 54 100 8.5')
        window('cow_far_milk', 'OracleA', cow, cow_id, 'interact', 'reject')
        summary['diagnosticsRaw'] = command('neoforge regionthreading status raw')
        if installation.get('artifactManifestSha256'):
            command('neoforge regionthreading status players')
        request('close'); bots.wait(timeout=10)
        summary['clientsExit'] = bots.returncode
        time.sleep(0.5)
        if installation.get('artifactManifestSha256'):
            command('neoforge regionthreading status players')
        command('player_oracle planning')
        command('player_oracle stop')
        summary['status'] = 'passed'
    except BaseException as error:
        summary.update(status='failed', error=repr(error))
        raise
    finally:
        if bots and bots.poll() is None:
            try: request('close')
            except Exception: bots.terminate()
            try: bots.wait(timeout=10)
            except subprocess.TimeoutExpired: bots.kill(); bots.wait()
            summary['clientsExit'] = bots.returncode
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
        (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
        log.close(); commands.close()
        if bot_log: bot_log.close()


if __name__ == '__main__':
    main()
