#!/usr/bin/env python3
"""Deliberately fail a real PlayerTick.Pre inside original Connection.tick; verify join and no replay."""
import argparse
from collections import Counter
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    args = parser.parse_args()
    root = args.server.resolve()
    if (root / 'world/level.dat').exists():
        raise FileExistsError('Use a fresh isolated installation; preserve retained worlds')
    installation = json.loads((root / 'installation.json').read_text())
    assert installation.get('playerPlanningRequested'), 'Use a new --player-planning installation'
    out = root / 'failure-run'
    out.mkdir(exist_ok=False)
    password = re.search(r'^rcon.password=(.+)$', (root / 'server.properties').read_text(), re.M)[1]
    log = (out / 'server.log').open('wb')
    process = subprocess.Popen(installation['launch'], cwd=root, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
    (out / 'server.pid').write_text(str(process.pid) + '\n')
    client = None
    bots = None
    bot_log = None
    responses = queue.Queue()
    commands = []
    next_id = 0
    result = {'status': 'running', 'scope': 'P1 real connection entrance failure; no worker execution'}

    def command(text):
        response = client.command(text)
        commands.append({'command': text, 'response': response})
        return response

    def request(op, **data):
        nonlocal next_id
        next_id += 1
        bots.stdin.write(json.dumps({'requestId': next_id, 'op': op, **data}) + '\n')
        bots.stdin.flush()
        value = json.loads(responses.get(timeout=30))
        assert value['requestId'] == next_id and not value.get('error'), value
        return value['result']

    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None: raise RuntimeError('Private server exited during startup')
            try:
                client = Rcon('127.0.0.1', installation['rconPort'], password, timeout=10)
                break
            except OSError:
                time.sleep(0.5)
        assert client is not None, 'Private startup timeout'
        command('player_oracle start')
        bot_log = (out / 'clients.stderr.log').open('wb')
        bots = subprocess.Popen(['node', str(REPO / 'tools/player-region-tick/clients.cjs'), str(out / 'packets.ndjson')],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=bot_log, text=True)
        def reader():
            with (out / 'clients.stdout.log').open('w') as capture:
                for line in bots.stdout:
                    capture.write(line); capture.flush()
                    try: value = json.loads(line)
                    except json.JSONDecodeError: continue
                    if isinstance(value, dict) and 'requestId' in value: responses.put(line)
        threading.Thread(target=reader, daemon=True).start()
        request('connect', name='OracleFault', port=installation['port'])
        time.sleep(0.3)
        command('player_oracle mark before_failure')
        command('player_oracle fail_connection OracleFault')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if request('snapshot')['OracleFault']['ended']: break
            time.sleep(0.1)
        else: raise AssertionError('Original connection exception did not disconnect the real client')
        time.sleep(0.5)
        command('player_oracle mark after_failure')
        command('player_oracle planning')
        planning = json.loads((root / 'player-planning.json').read_text())
        snapshot = planning['snapshot']
        phase = snapshot['phases']['CONNECTION']
        assert planning['injectedFailures'] == 1
        assert snapshot['pending'] == snapshot['running'] == snapshot['owners'] == 0
        assert phase['failed'] == 1 and phase['worker'] == 0
        assert phase['started'] == phase['joined'] == phase['returned'] + 1
        failed = [row for row in snapshot['recent'] if row.get('failureClass')]
        assert len(failed) == 1 and failed[0]['failureClass'] == 'net.minecraft.ReportedException', failed
        request('close')
        bots.wait(timeout=10)
        result['clientsExit'] = bots.returncode
        command('player_oracle stop')
        rows = [json.loads(line) for line in (root / 'player-oracle.ndjson').read_text().splitlines()]
        counts = Counter(row['kind'] for row in rows)
        injected = [row for row in rows if row['kind'] == 'INJECTED_FAILURE']
        assert len(injected) == counts['CONNECTION_THROW'] == counts['LISTENER_THROW'] == counts['DOTICK_THROW'] == 1
        assert counts['PLAYER_PRE'] == counts['PLAYER_POST'] + 1
        assert sum(row['kind'] == 'CONNECTION_START' and row.get('instance') == injected[0]['instance'] and row['tick'] == injected[0]['tick'] for row in rows) == 1
        result.update(status='passed_expected_failure', planning=planning, traceCounts=dict(counts),
                      failedTick=injected[0]['tick'], failedInvocation=failed[0], effects=planning['injectedFailures'])
    except BaseException as error:
        result.update(status='failed', error=repr(error))
        raise
    finally:
        if bots and bots.poll() is None:
            try: request('close'); bots.wait(timeout=10)
            except Exception: bots.kill(); bots.wait()
        if process.poll() is None:
            try:
                process.stdin.write(b'stop\n')
                process.stdin.flush()
                result['stopVia'] = 'owned_process_stdin'
                process.wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        if client: client.close()
        result['serverExit'] = process.returncode
        if result['status'] == 'passed_expected_failure': assert process.returncode == 0
        (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        log.close()
        if bot_log: bot_log.close()


if __name__ == '__main__':
    main()
