#!/usr/bin/env python3
"""Production-only packet liveness, walking, reconnect and saved-resource restart evidence.

This is a bounded lifecycle suite, not proof of every queue cancellation race or
NeoForge custom main-thread payload callback ordering. All measured gameplay is
from normal TCP client packets; commands establish fixtures or independently read.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
from run_worker_smoke import REPO, Rcon, validate_samples
from dimension_scenarios import run_dimensions

TOOLS = Path(__file__).resolve().parent
SAVE_FLUSH_TIMEOUT_SECONDS = 90


class Session:
    def __init__(self, root, output_name='worker-lifecycle', client_script=None, *, client_args=()):
        self.root = root
        if (root / 'world/level.dat').exists():
            raise FileExistsError('Use a fresh isolated installation; retained worlds are preserved')
        self.install = json.loads((root / 'installation.json').read_text())
        if output_name == 'worker-lifecycle':
            assert self.install['oracleSha256'] is None and not list((root / 'mods').glob('*.jar'))
        self.output = root / output_name
        self.output.mkdir(exist_ok=False)
        self.commands = (self.output / 'commands.ndjson').open('x')
        self.boot = 0
        self.process = self.rcon = self.log = None
        self.exits = []
        self.password = re.search(r'^rcon.password=(.+)$', (root / 'server.properties').read_text(), re.M)[1]
        self.responses = queue.Queue()
        self.request_id = 0
        self.client_log = (self.output / 'clients.stderr.log').open('x')
        self.bots = subprocess.Popen(['node', str(client_script or TOOLS / 'lifecycle_clients.cjs'), str(self.output / 'packets.ndjson'), *client_args],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.client_log, text=True)
        def reader():
            with (self.output / 'clients.stdout.log').open('x') as capture:
                for line in self.bots.stdout:
                    capture.write(line); capture.flush()
                    try: row = json.loads(line)
                    except json.JSONDecodeError: continue
                    if isinstance(row, dict) and 'requestId' in row: self.responses.put(row)
        threading.Thread(target=reader, daemon=True).start()
        self.sources = {}
        for name in ['run_worker_lifecycle.py', 'lifecycle_clients.cjs', 'run_worker_smoke.py', 'verify_worker_lifecycle.py', 'decode_lifecycle_frames.cjs', 'dimension_scenarios.py', 'package-lock.json']:
            source = TOOLS / name
            shutil.copy2(source, self.output / name)
            self.sources[name] = hashlib.sha256(source.read_bytes()).hexdigest()

    def start(self):
        self.boot += 1
        self.log = (self.output / f'boot-{self.boot}.server.log').open('xb')
        self.process = subprocess.Popen(self.install['launch'], cwd=self.root, stdout=self.log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
        (self.output / f'boot-{self.boot}.pid').write_text(str(self.process.pid)+'\n')
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if self.process.poll() is not None: raise RuntimeError('server exited during lifecycle boot')
            try:
                self.rcon = Rcon('127.0.0.1', self.install['rconPort'], self.password, timeout=15)
                return
            except OSError: time.sleep(.5)
        raise TimeoutError('lifecycle server boot timeout')

    def command(self, text):
        before = time.monotonic_ns()
        # Explicit disk flush can outlast the ordinary control deadline. Scope the
        # budget to that command; never retry it or change game protocol timeouts.
        save_flush = text == 'save-all flush'
        if save_flush:
            original_timeout = self.rcon.sock.gettimeout()
            self.rcon.sock.settimeout(SAVE_FLUSH_TIMEOUT_SECONDS)
        try:
            try:
                response = self.rcon.command(text)
            finally:
                if save_flush:
                    self.rcon.sock.settimeout(original_timeout)
        except OSError as error:
            # Preserve VM state before the existing failure cleanup terminates this isolated server.
            if self.process is not None and self.process.poll() is None:
                stem = self.output / ('rcon-failure-' + str(time.monotonic_ns()))
                detail = dict(command=text, pid=self.process.pid, error=repr(error))
                try:
                    with stem.with_suffix('.threads.log').open('xb') as capture:
                        dump = subprocess.run([shutil.which('jcmd') or 'jcmd', str(self.process.pid), 'Thread.print', '-l'], stdout=capture, stderr=subprocess.STDOUT, timeout=15)
                    detail['threadDumpExit'] = dump.returncode
                except Exception as diagnostic:
                    detail['threadDumpError'] = repr(diagnostic)
                stem.with_suffix('.json').write_text(json.dumps(detail, indent=2)+'\n')
            raise
        self.commands.write(json.dumps({'boot': self.boot, 'command': text, 'response': response, 'startNs': before, 'endNs': time.monotonic_ns()})+'\n')
        self.commands.flush()
        assert not any(e in response for e in ['Unknown or incomplete command', 'Incorrect argument', 'That position is not loaded', 'No entity was found']), response
        return response

    def request(self, op, **data):
        self.request_id += 1
        self.bots.stdin.write(json.dumps({'requestId': self.request_id, 'op': op, **data})+'\n'); self.bots.stdin.flush()
        row = self.responses.get(timeout=30)
        assert row.get('requestId') == self.request_id and 'error' not in row, row
        return row['result']

    def snapshot(self, label):
        state = json.loads(self.command('neoforge regionthreading status players'))
        (self.output / (label + '.json')).write_text(json.dumps(state, indent=2)+'\n')
        return state

    def resources(self, label):
        row = {key: self.command('data get entity WorkerA '+key) for key in ['Inventory', 'XpTotal', 'XpLevel', 'XpP', 'Pos']}
        (self.output / (label + '.json')).write_text(json.dumps(row, indent=2)+'\n')
        return row

    def stop(self):
        if self.process and self.process.poll() is None:
            try:
                try:
                    if self.rcon: self.command('stop')
                    else: self.process.stdin.write(b'stop\n'); self.process.stdin.flush()
                except Exception as error:
                    # An interrupted RCON exchange can leave a response in the socket. Preserve
                    # the error, then use the owned server's console for graceful shutdown.
                    (self.output / f'boot-{self.boot}.stop-fallback.json').write_text(
                        json.dumps(dict(error=repr(error), fallback='owned server console stop'), indent=2)+'\n')
                    self.process.stdin.write(b'stop\n'); self.process.stdin.flush()
                self.process.wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired):
                self.process.terminate()
                try: self.process.wait(timeout=10)
                except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        if self.process:
            self.exits.append(self.process.returncode)
            self.process = None
        if self.rcon: self.rcon.close(); self.rcon = None
        if self.log: self.log.close(); self.log = None

    def close(self):
        self.stop()
        if self.bots.poll() is None:
            try: self.request('close'); self.bots.wait(timeout=10)
            except Exception:
                self.bots.terminate()
                try: self.bots.wait(timeout=10)
                except subprocess.TimeoutExpired: self.bots.kill(); self.bots.wait()
        self.commands.close(); self.client_log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--dimensions', action='store_true', help='Add a real client-movement Nether portal round trip')
    args = parser.parse_args()
    session = Session(args.server.resolve())
    result = {'status': 'running', 'installation': session.install, 'sources': session.sources}
    try:
        session.start()
        for rule in ['doMobSpawning false', 'naturalRegeneration false', 'randomTickSpeed 0', 'spawnChunkRadius 0', 'doWeatherCycle false']:
            session.command('gamerule '+rule)
        session.command('forceload add 0 0 143 47'); time.sleep(2)
        session.command('fill 40 99 16 55 99 32 minecraft:stone')
        session.command('fill 112 99 16 128 99 32 minecraft:stone')
        for name in ['WorkerA', 'WorkerB']: session.request('connect', name=name, port=session.install['port'])
        session.command('advancement grant @a everything'); session.command('clear @a')
        session.command('experience add @a -1000000 points'); session.command('experience set @a 0 levels'); session.command('experience set @a 0 points')
        session.command('tp WorkerA 47.5 100 24.5'); session.command('tp WorkerB 120.5 100 24.5'); time.sleep(3)
        samples = [session.snapshot('network-0')]
        session.request('mark', label='network_before')
        # Last observation is >15s after connection: require actual original keepalive challenge/reply.
        for i in range(6):
            session.request('network_sequence', name='WorkerA', text=f'forgia_lifecycle_{i}', id=9000+i)
            time.sleep(3)
            samples.append(session.snapshot('network-'+str(i+1)))
        session.request('mark', label='network_after')
        (session.output / 'network-samples.json').write_text(json.dumps(samples, indent=2)+'\n')
        result['networkWorkers'] = validate_samples(samples, session.install['playerPlanningRequested'])
        session.request('mark', label='walk_before')
        session.request('packet', name='WorkerA', packet='position_look', data={'x':48.25,'y':100,'z':24.5,'yaw':0,'pitch':0,'onGround':True})
        time.sleep(2)
        session.snapshot('walk-after'); session.request('mark', label='walk_after')
        # Resource fixture is outside the measured pickup window. The player moves with a normal packet.
        session.command('summon minecraft:item 50.0 100 24.5 {Tags:["lifecycle_resource"],NoGravity:1b,PickupDelay:0s,Item:{id:"minecraft:diamond",count:5}}')
        session.command('summon minecraft:experience_orb 50.0 100 24.5 {Tags:["lifecycle_resource"],NoGravity:1b,Value:7s}')
        session.request('mark', label='pickup_before')
        for x in [48.75,49.25,49.75]:
            session.request('packet', name='WorkerA', packet='position_look', data={'x':x,'y':100,'z':24.5,'yaw':0,'pitch':0,'onGround':True})
            time.sleep(.3)
        time.sleep(1)
        session.resources('resources-before-disconnect'); session.snapshot('before-disconnect')
        result['remainingResourceRead'] = session.command('execute if entity @e[tag=lifecycle_resource]')
        session.request('mark', label='pickup_after')
        session.request('disconnect', name='WorkerA'); time.sleep(.5); session.snapshot('after-disconnect')
        session.request('mark', label='reconnect_before')
        session.request('connect', name='WorkerA', port=session.install['port']); time.sleep(2)
        session.resources('resources-after-reconnect'); session.snapshot('after-reconnect')
        session.request('mark', label='reconnect_after')
        if args.dimensions:
            result['dimensionProof'] = run_dimensions(session)
        # Stop while real clients remain connected, then restore this suite's saved private world.
        session.command('save-all flush')
        session.stop(); time.sleep(.5)
        for name in ['WorkerA','WorkerB']: session.request('disconnect', name=name)
        session.start()
        session.request('mark', label='restart_before')
        session.request('connect', name='WorkerA', port=session.install['port']); time.sleep(2)
        session.resources('resources-after-restart'); session.snapshot('after-restart')
        session.request('mark', label='restart_after')
        session.request('close'); session.bots.wait(timeout=10); time.sleep(.5)
        session.snapshot('final')
        result['status'] = 'passed'
    except BaseException as error:
        result.update(status='failed', error=repr(error))
        raise
    finally:
        session.close()
        result.update(serverExits=session.exits, clientsExit=session.bots.returncode)
        (session.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
    assert result['serverExits'] == [0,0] and result['clientsExit'] == 0, result


if __name__ == '__main__': main()
