#!/usr/bin/env python3
"""P5 normal TCP clients and native JVM monitoring on one new isolated installation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from run_worker_lifecycle import Session
from sample_player_scale_host import HostSampler

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]
JAVA_HOME = Path('/home/yc/.local/opt/jdk-21.0.13+9')


def actors_for(count, distribution):
    actors = []
    for index in range(count):
        if distribution == 'same':
            x, z = 8.5 + index % 6 * 6, 8.5 + index // 6 * 6
        elif distribution == 'spread':
            x, z = 24.5 + index % 8 * 96, 24.5 + index // 8 * 96
        else:
            pair = index // 2
            x, z = 47.0 + index % 2 * 2 + pair // 4 * 192, 24.5 + pair % 4 * 96
        actors.append(dict(name=f'Scale{index:02}', x=x, y=100, z=z))
    return actors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--jmx-classes', type=Path, required=True)
    parser.add_argument('--counts', default='1,8,32')
    parser.add_argument('--distributions', default='same,spread,boundary')
    parser.add_argument('--warmup-seconds', type=float, default=10)
    parser.add_argument('--measure-seconds', type=float, default=30)
    parser.add_argument('--mods-manifest', type=Path, help='Explicit pinned ordinary server Mod set; no worker permission is granted')
    parser.add_argument('--grouped-clients', action='store_true', help='At most eight protocol clients per independent Node process; preserve child captures')
    parser.add_argument('--quiet-advancement-fixture', action='store_true', help='Suppress setup-only advancement announcements; retain original grants and restore the gamerule')
    parser.add_argument('--network-diagnostics', action='store_true', help='Capture diagnostic-only host, TCP queue and client event-loop samples')
    args = parser.parse_args()
    if args.network_diagnostics and args.grouped_clients:
        parser.error('Network diagnostics currently require a single protocol process')
    counts = [int(value) for value in args.counts.split(',')]
    distributions = args.distributions.split(',')
    assert all(value in [1, 8, 32] for value in counts)
    assert all(value in ['same', 'spread', 'boundary'] for value in distributions)
    assert args.measure_seconds >= 5 and args.warmup_seconds >= 2
    root = args.server.resolve()
    assert not (root / 'world/level.dat').exists()
    mods = []
    if args.mods_manifest:
        mods = json.loads(args.mods_manifest.read_text())['mods']
        assert mods and not list((root / 'mods').glob('*.jar'))
        for entry in mods:
            source = Path(entry['path'])
            assert source.is_file() and hashlib.sha256(source.read_bytes()).hexdigest() == entry['sha256']
            shutil.copy2(source, root / 'mods' / source.name)
    else:
        assert not list((root / 'mods').glob('*.jar'))
    with (root / 'server.properties').open('a') as stream:
        stream.write('\nmax-players=64\nenable-jmx-monitoring=true\nview-distance=2\nsimulation-distance=2\n')
    session = Session(root, 'player-scale', TOOLS / ('grouped_scale_clients.cjs' if args.grouped_clients else 'scale_clients.cjs'),
                      client_args=['--network-diagnostics'] if args.network_diagnostics else [])
    result = dict(status='running', installation=session.install, counts=counts, distributions=distributions,
                  warmupSeconds=args.warmup_seconds, measureSeconds=args.measure_seconds, windows=[], sources={}, installedMods=mods,
                  quietAdvancementFixture=args.quiet_advancement_fixture, networkDiagnostics=args.network_diagnostics,
                  clientTopology='maximum-eight-per-process' if args.grouped_clients else 'single-protocol-process')
    for file in [Path(__file__).resolve(), TOOLS / 'scale_clients.cjs', TOOLS / 'grouped_scale_clients.cjs', TOOLS / 'jmx/PlayerJvmSampler.java', TOOLS / 'verify_player_scale.py',
                 TOOLS / 'run_worker_lifecycle.py', TOOLS / 'decode_protocol_frames.cjs', TOOLS / 'sample_player_scale_host.py']:
        path = file.relative_to(REPO)
        target = session.output / 'source' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, target)
        result['sources'][str(path)] = hashlib.sha256(file.read_bytes()).hexdigest()
    monitor = monitor_log = None
    label = session.output / 'monitor-window.txt'
    stop = session.output / 'monitor-stop'
    players = {}

    def checkpoint(name):
        state = session.snapshot(name)
        assert state['activeBatches'] == state['stateClaims'] == 0
        assert state['phases']['pending'] == state['phases']['running'] == 0
        assert not state['network']['frozen']
        assert all(value['failed'] == 0 for value in state['phases']['phases'].values())
        assert state['packets']['failed'] == 0
        return state

    def resources(actors):
        return {actor['name']: {field: session.command(f"data get entity {actor['name']} {field}")
                                for field in ['Inventory', 'XpTotal', 'Health']} for actor in actors}

    host_sampler = None
    try:
        session.start()
        if args.network_diagnostics:
            host_sampler = HostSampler(session.process.pid, session.bots.pid, session.install['port'], session.output, label)
        session.command('difficulty normal')
        for rule in ['doMobSpawning false', 'naturalRegeneration false', 'randomTickSpeed 0', 'spawnChunkRadius 0', 'doWeatherCycle false', 'doDaylightCycle false']:
            session.command('gamerule ' + rule)
        session.command('time set noon')
        monitor_log = (session.output / 'jmx.stderr.log').open('xb')
        monitor = subprocess.Popen([str(JAVA_HOME / 'bin/java'), '--add-modules', 'jdk.attach', '-cp', str(args.jmx_classes.resolve()),
                                    'PlayerJvmSampler', str(session.process.pid), str(session.output / 'jmx.ndjson'), str(label), str(stop)],
                                   stdout=monitor_log, stderr=subprocess.STDOUT)
        (session.output / 'jmx.pid').write_text(str(monitor.pid) + '\n')
        deadline = time.monotonic() + 25
        while not (session.output / 'jmx.ndjson').exists() or (session.output / 'jmx.ndjson').stat().st_size == 0:
            if monitor.poll() is not None or time.monotonic() > deadline:
                raise AssertionError('Native JVM sampler did not start')
            time.sleep(.1)
        for count in counts:
            for index in range(len(players), count):
                name = f'Scale{index:02}'
                players[name] = session.request('connect', name=name, port=session.install['port'])
            previous_announcements = None
            if args.quiet_advancement_fixture:
                response = session.command('gamerule announceAdvancements')
                previous_announcements = response.strip().rsplit(' ', 1)[-1]
                if previous_announcements not in ('true', 'false'):
                    raise ValueError(f'Unexpected announceAdvancements query: {response!r}')
                session.command('gamerule announceAdvancements false')
            try:
                session.command('advancement grant @a everything')
            finally:
                if previous_announcements is not None:
                    session.command(f'gamerule announceAdvancements {previous_announcements}')
            session.command('clear @a')
            session.command('experience add @a -1000000 points')
            session.command('experience set @a 0 levels')
            session.command('experience set @a 0 points')
            session.command('experience add @a 7 points')
            session.command('item replace entity @a inventory.0 with minecraft:diamond 16')
            for distribution in distributions:
                window_name = f'n{count}-{distribution}'
                actors = actors_for(count, distribution)
                session.command('forceload remove all')
                pads = set()
                for actor in actors:
                    x, z = int(actor['x']), int(actor['z'])
                    pad = (x // 16, z // 16)
                    if pad not in pads:
                        session.command(f'forceload add {x-5} {z-5} {x+5} {z+5}')
                        # Several same-region actors can share this chunk. Fill its entire support
                        # plane before deduplicating, rather than only the first actor's small pad.
                        session.command(f'fill {pad[0]*16} 99 {pad[1]*16} {pad[0]*16+15} 99 {pad[1]*16+15} minecraft:stone')
                        pads.add(pad)
                    session.command(f"tp {actor['name']} {actor['x']} 100 {actor['z']}")
                label.write_text('warmup-' + window_name)
                session.request('load_start', actors=actors, intervalMillis=500, baseId=count * 10000000 + distributions.index(distribution) * 1000000)
                until = time.monotonic() + args.warmup_seconds
                while time.monotonic() < until:
                    if session.process.poll() is not None or monitor.poll() is not None:
                        raise AssertionError('Server or sampler exited during warmup')
                    time.sleep(.2)
                before_resources = resources(actors)
                before = checkpoint(window_name + '-before')
                session.request('mark', label=window_name + '_before')
                label.write_text(window_name)
                start = time.monotonic_ns()
                until = time.monotonic() + args.measure_seconds
                samples = []
                next_sample = time.monotonic()
                while time.monotonic() < until:
                    if session.process.poll() is not None or monitor.poll() is not None:
                        raise AssertionError('Server or sampler exited in measured window')
                    if time.monotonic() >= next_sample:
                        samples.append(checkpoint(window_name + '-sample-' + str(len(samples))))
                        next_sample = time.monotonic() + .7
                    time.sleep(.05)
                end = time.monotonic_ns()
                label.write_text('post-' + window_name)
                session.request('mark', label=window_name + '_after')
                load = session.request('load_stop')
                time.sleep(.6)
                after = checkpoint(window_name + '-after')
                after_resources = resources(actors)
                clients = session.request('snapshot')
                assert all(not row['ended'] for row in clients.values())
                attack = None
                if count > 1 and distribution == 'boundary':
                    session.request('mark', label=window_name + '_attack_before')
                    target = players['Scale01']['id']
                    session.request('packet', name='Scale00', packet='use_entity', data=dict(target=target, mouse=1, sneaking=False))
                    time.sleep(.6)
                    attack = dict(target=target, healthRead=session.command('data get entity Scale01 Health'), clients=session.request('snapshot'), owners=after['players'])
                    session.request('mark', label=window_name + '_attack_after')
                    # Restoring a fixture is outside the measured gameplay/performance window.
                    session.command('effect give Scale01 minecraft:instant_health 1 5 true')
                    time.sleep(.1)
                row = dict(name=window_name, count=count, distribution=distribution, actors=actors, before=before, after=after,
                           samples=samples, beforeResources=before_resources, afterResources=after_resources, clients=clients,
                           startMonitorNanos=start, endMonitorNanos=end, elapsedSeconds=(end-start)/1e9, load=load, crossRegionAttack=attack)
                result['windows'].append(row)
                (session.output / 'progress.json').write_text(json.dumps(dict(status='running', windows=result['windows']), indent=2) + '\n')
        label.write_text('cleanup')
        session.command('save-all flush')
        session.request('close')
        session.bots.wait(timeout=15)
        time.sleep(.6)
        result['final'] = checkpoint('final')
        assert not result['final']['players']
        stop.write_text('normal sampler stop\n')
        monitor.wait(timeout=15)
        assert monitor.returncode == 0
        session.stop()
        result['status'] = 'passed'
    except BaseException as error:
        result.update(status='failed', error=repr(error))
        raise
    finally:
        if host_sampler:
            host_sampler.close()
        if monitor and monitor.poll() is None:
            stop.write_text('failure cleanup sampler stop\n')
            try:
                monitor.wait(timeout=10)
            except subprocess.TimeoutExpired:
                monitor.terminate()
                monitor.wait(timeout=10)
        try:
            session.close()
        except BaseException as cleanup_error:
            result.update(status='failed', cleanupError=repr(cleanup_error))
            raise
        finally:
            if monitor_log:
                monitor_log.close()
            result.update(serverExits=session.exits, clientsExit=session.bots.returncode, samplerExit=monitor.returncode if monitor else None)
            (session.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    assert result['serverExits'] == [0] and result['clientsExit'] == result['samplerExit'] == 0


if __name__ == '__main__':
    main()
