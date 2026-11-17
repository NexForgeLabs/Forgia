#!/usr/bin/env python3
"""Run off-mode full-entry/lifecycle oracle on a prepared isolated installation."""
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

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/modpack-matrix'))
from rcon import Rcon


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True, type=Path)
    parser.add_argument('--oracle', type=Path, help='Optional updated test Mod; archives the original before a first run')
    args = parser.parse_args()
    root = args.server.resolve()
    installation = json.loads((root / 'installation.json').read_text())
    output = root / 'run'
    output.mkdir(exist_ok=False)
    oracle_path = root / 'mods/forgia-player-oracle-1.0.jar'
    if args.oracle:
        shutil.copy2(oracle_path, output / 'original-installed-oracle.jar')
        shutil.copy2(args.oracle.resolve(), oracle_path)
    (output / 'inputs.json').write_text(json.dumps({
        'installation': installation,
        'actualOracleSha256': hashlib.sha256(oracle_path.read_bytes()).hexdigest(),
        'driverSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'clientsSha256': hashlib.sha256((REPO / 'tools/player-region-tick/clients.cjs').read_bytes()).hexdigest()
    }, indent=2) + '\n')
    for source in ['run_baseline.py', 'clients.cjs', 'verify_oracle.py', 'oracle.gradle', 'package-lock.json']:
        shutil.copy2(REPO / 'tools/player-region-tick' / source, output / source)
    password = re.search(r'^rcon.password=(.+)$', (root / 'server.properties').read_text(), re.M)[1]
    commands = (output / 'commands.ndjson').open('w')
    scope = 'P1 original-thread player planning' if installation.get('playerPlanningRequested') else 'Player planning off; original tick paths'
    summary = {'status': 'running', 'scope': scope, 'persistent': installation['persistent'], 'attacks': []}
    log = (output / 'server.log').open('wb')
    process = subprocess.Popen(installation['launch'], cwd=root, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    (output / 'server.pid').write_text(str(process.pid) + '\n')
    client = None
    bots = None
    bot_log = None
    request_id = 0
    responses = queue.Queue()

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

    def health(name):
        response = command(f'data get entity {name} Health')
        return float(re.search(r'([-\d.]+)f\s*$', response)[1])

    def marker(label):
        command('player_oracle mark ' + label)

    def attack(label, attacker, victim, entity_id):
        marker(label + '_before')
        before = health(victim)
        start = time.monotonic_ns()
        request('packet', name=attacker, packet='use_entity', data={'target': entity_id, 'mouse': 1, 'sneaking': False})
        deadline = time.monotonic() + 3
        after = before
        while time.monotonic() < deadline and after == before:
            time.sleep(0.05)
            after = health(victim)
        elapsed = (time.monotonic_ns() - start) / 1e6
        marker(label + '_after')
        state = request('snapshot')
        summary['attacks'].append({'label': label, 'attacker': attacker, 'victim': victim, 'before': before, 'after': after,
                                   'observedResponseMs': elapsed, 'clientHealth': state[victim]['health'], 'clients': state})
        if after >= before:
            raise AssertionError('normal protocol attack produced no actual server damage: ' + label)
        time.sleep(0.7)

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
            raise TimeoutError('Server startup timeout')
        command('gamerule doMobSpawning false')
        command('gamerule naturalRegeneration false')
        command('gamerule doWeatherCycle false')
        command('gamerule randomTickSpeed 0')
        command('gamerule spawnChunkRadius 0')
        command('difficulty normal')
        if installation.get('criterionProof'):
            command('scoreboard objectives add forgia_criterion dummy')
        command('player_oracle start')
        command('forceload add 32 0 63 31')
        time.sleep(2)
        command('fill 40 99 4 55 99 16 minecraft:stone')
        bot_log = (output / 'clients.stderr.log').open('wb')
        bots = subprocess.Popen(['node', str(REPO / 'tools/player-region-tick/clients.cjs'), str(output / 'packets.ndjson')],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=bot_log, text=True)
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
                        responses.put(line)
        threading.Thread(target=read_responses, daemon=True).start()
        a = request('connect', name='OracleA', port=installation['port'])
        command('tp OracleA 47.0 100 8.5')
        time.sleep(4)
        marker('single_begin')
        time.sleep(1)
        marker('single_end')
        b = request('connect', name='OracleB', port=installation['port'])
        command('tp OracleB 49.0 100 8.5')
        command('effect give @a minecraft:resistance 1 0 true')
        time.sleep(4)
        marker('multi_begin')
        time.sleep(1)
        marker('multi_end')
        if installation.get('criterionProof'):
            def score(name):
                response = command(f'scoreboard players get {name} forgia_criterion')
                return int(re.search(r'has (-?\d+)', response)[1])
            first = {name: score(name) for name in ['OracleA', 'OracleB']}
            if first != {'OracleA': 1, 'OracleB': 1}:
                raise AssertionError('Original TICK reward did not run once per actual player: ' + repr(first))
            command('player_oracle mark criterion_before_revoke')
            command('advancement revoke OracleA only forgia_player:tick')
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and score('OracleA') != 2:
                time.sleep(0.05)
            time.sleep(0.3)
            final = {name: score(name) for name in ['OracleA', 'OracleB']}
            if final != {'OracleA': 2, 'OracleB': 1}:
                raise AssertionError('Original revoke/reward result was lost, repeated or shared: ' + repr(final))
            command('player_oracle mark criterion_after_revoke')
            command('player_oracle criterion_storage')
            storage = json.loads((root / 'player-criterion-storage.json').read_text())
            if storage['OracleA']['totalExperience'] != 6 or storage['OracleB']['totalExperience'] != 3:
                raise AssertionError('Synchronous original XP rewards differ: ' + repr(storage))
            if any(value['localStorage'] != installation.get('playerPlanningRequested', False) for value in storage.values()):
                raise AssertionError('Actual original-owner storage differs from startup mode')
            summary['criterionProof'] = {'before': first, 'afterRevoke': final, 'storage': storage,
                                         'scope': 'Original TICK criterion -> original award -> synchronous XP/function; no worker claim'}
        attack('cross_A_B', 'OracleA', 'OracleB', b['id'])
        command('tp OracleA 47.0 100 8.5')
        command('tp OracleB 49.0 100 8.5')
        time.sleep(1)
        attack('cross_B_A', 'OracleB', 'OracleA', a['id'])
        command('tp OracleA 44.0 100 8.5')
        command('tp OracleB 46.0 100 8.5')
        time.sleep(1)
        attack('same_A_B', 'OracleA', 'OracleB', b['id'])
        command('tp OracleA 41.0 100 8.5')
        command('tp OracleB 54.0 100 8.5')
        time.sleep(1)
        marker('far_before')
        before = health('OracleB')
        request('packet', name='OracleA', packet='use_entity', data={'target': b['id'], 'mouse': 1, 'sneaking': False})
        time.sleep(0.5)
        after = health('OracleB')
        marker('far_after')
        summary['remoteControl'] = {'before': before, 'after': after, 'distance': 13}
        if before != after:
            raise AssertionError('Remote attack must retain vanilla rejection')
        command('tick freeze')
        marker('frozen_begin')
        time.sleep(0.5)
        marker('frozen_end')
        command('tick unfreeze')
        marker('move_before')
        request('packet', name='OracleA', packet='position_look', data={'x': 41.25, 'y': 100, 'z': 8.5, 'yaw': 0, 'pitch': 0, 'onGround': True})
        time.sleep(0.5)
        marker('move_after')
        command('gamemode spectator OracleA')
        time.sleep(0.5)
        marker('spectator_begin')
        time.sleep(0.5)
        marker('spectator_end')
        command('gamemode survival OracleA')
        command('tp OracleA 47 100 8.5')
        command('summon minecraft:boat 46 100 8.5 {Tags:["oracle_ride"],Invulnerable:1b}')
        mounted = command('ride OracleA mount @e[type=minecraft:boat,tag=oracle_ride,limit=1]')
        if 'started riding' not in mounted:
            raise AssertionError('riding fixture was not actually mounted: ' + mounted)
        time.sleep(0.5)
        marker('riding_begin')
        time.sleep(0.5)
        marker('riding_end')
        command('ride OracleA dismount')
        command('kill @e[tag=oracle_ride]')
        marker('dimension_before')
        command('execute in minecraft:the_nether run tp OracleA 0 100 0')
        time.sleep(2)
        marker('dimension_after')
        command('execute in minecraft:overworld run tp OracleA 47 100 8.5')
        time.sleep(2)
        marker('dimension_returned')
        command('kill OracleA')
        time.sleep(0.3)
        request('packet', name='OracleA', packet='client_command', data={'actionId': 0})
        time.sleep(1)
        marker('respawn_after')
        request('disconnect', name='OracleB')
        time.sleep(0.3)
        request('connect', name='OracleB', port=installation['port'])
        time.sleep(1)
        marker('reconnect_after')
        summary['diagnostics'] = command('neoforge regionthreading status')
        summary['diagnosticsRaw'] = command('neoforge regionthreading status raw')
        request('close')
        bots.wait(timeout=10)
        summary['clientsExit'] = bots.returncode
        marker('clients_closed')
        time.sleep(0.5)
        if installation.get('playerPlanningRequested'):
            command('player_oracle planning')
        command('player_oracle stop')
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
