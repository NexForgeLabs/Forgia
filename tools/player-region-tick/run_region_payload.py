#!/usr/bin/env python3
"""Public declared payload API, normal TCP actions, original MAIN/NETWORK and conservation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import time
from run_worker_lifecycle import Session

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--mod', type=Path, required=True)
    args = parser.parse_args()
    root = args.server.resolve()
    shutil.copy2(args.mod, root / 'mods' / args.mod.name)
    session = Session(root, 'region-payload')
    result = dict(status='running', installation=session.install,
                  modSha256=hashlib.sha256(args.mod.read_bytes()).hexdigest(), sources={})
    files = [Path(__file__).resolve(), TOOLS / 'lifecycle_clients.cjs', TOOLS / 'verify_region_payload.py',
             TOOLS / 'decode_region_payload.cjs', TOOLS / 'region-payload/java/forgia/regionpayload/RegionPayloadProbe.java']
    files += list((REPO / 'src/main/java/net/neoforged/neoforge/network/handling').glob('*Region*Payload*.java'))
    files += [REPO / 'src/main/java/net/neoforged/neoforge/server/threading/region' / name for name in
              ['RegionThreadingEngine.java', 'RegionPlayerAdmissionPlan.java', 'RegionPlayerPacketPlan.java', 'RegionPlayerPacketRouter.java', 'RegionPlayerNetworkGate.java']]
    for file in files:
        relative = file.relative_to(REPO)
        target = session.output / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, target)
        result['sources'][str(relative)] = hashlib.sha256(file.read_bytes()).hexdigest()

    def payload(name, kind, text):
        return session.request('payload', name=name, channel='forgia_region:' + kind, text=text)

    def wait_marker(label):
        marker = root / ('region-payload-' + label + '.json')
        until = time.monotonic() + 20
        while not marker.exists():
            if time.monotonic() > until:
                raise TimeoutError('Declared payload did not begin ' + label)
            time.sleep(.01)
        return json.loads(marker.read_text())

    def dump(label):
        session.command('region_payload_dump')
        data = json.loads((root / 'region-payload-events.json').read_text())
        (session.output / (label + '-events.json')).write_text(json.dumps(data, indent=2) + '\n')
        return data

    try:
        session.start()
        for rule in ['doMobSpawning false', 'naturalRegeneration false', 'randomTickSpeed 0', 'spawnChunkRadius 0', 'doWeatherCycle false']:
            session.command('gamerule ' + rule)
        session.command('forceload add 0 0 143 47')
        for x in [24, 120]:
            session.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
        for name in ['WorkerA', 'WorkerB']:
            session.request('connect', name=name, port=session.install['port'])
            session.request('register_channels', name=name, channels=['forgia_region:reply'])
        session.command('advancement grant @a everything')
        session.command('clear @a')
        session.command('experience add @a -1000000 points')
        session.command('experience set @a 0 levels')
        session.command('experience set @a 0 points')
        session.command('experience add @a 7 points')
        session.command('tp WorkerA 24.5 100 24.5')
        session.command('tp WorkerB 120.5 100 24.5')
        session.command('item replace entity @a inventory.0 with minecraft:diamond 16')
        time.sleep(2)
        result['before'] = session.snapshot('before')
        result['resourcesBefore'] = {name: {field: session.command(f'data get entity {name} {field}') for field in ['Inventory', 'XpTotal', 'Health']} for name in ['WorkerA', 'WorkerB']}
        session.request('mark', label='declared_before')
        # MAIN fences alternate with the declared handler; per-connection order must be exact.
        for i in range(10):
            for name in ['WorkerA', 'WorkerB']:
                payload(name, 'main', 'before_' + str(i))
                payload(name, 'local', 'transfer_' + str(i))
                payload(name, 'main', 'after_' + str(i))
            time.sleep(.03)
        payload('WorkerA', 'local', 'boom')
        payload('WorkerA', 'main', 'after_boom')
        time.sleep(.5)
        # Force incoming NETWORK work while the original declared worker is physically active.
        payload('WorkerA', 'local', 'hold_network')
        result['networkHold'] = wait_marker('hold_network')
        for i in range(50):
            payload('WorkerA', 'network', 'network_' + str(i))
        payload('WorkerA', 'main', 'after_network_hold')
        time.sleep(1)
        result['after'] = session.snapshot('after')
        result['eventsAfter'] = dump('after')
        result['resourcesAfter'] = {name: {field: session.command(f'data get entity {name} {field}') for field in ['Inventory', 'XpTotal', 'Health']} for name in ['WorkerA', 'WorkerB']}
        result['clientsAfter'] = session.request('snapshot')
        session.request('mark', label='declared_after')
        # A queued old session must never run a declared body after physical channel close.
        payload('WorkerB', 'local', 'hold_cancel')
        result['cancelHold'] = wait_marker('hold_cancel')
        payload('WorkerB', 'local', 'transfer_stale')
        session.request('disconnect', name='WorkerB')
        time.sleep(1.5)
        result['afterDisconnect'] = session.snapshot('after-disconnect')
        result['eventsDisconnected'] = dump('disconnected')
        session.request('connect', name='WorkerB', port=session.install['port'])
        session.request('register_channels', name='WorkerB', channels=['forgia_region:reply'])
        time.sleep(1)
        payload('WorkerB', 'local', 'new_session')
        time.sleep(.5)
        result['reconnected'] = session.snapshot('reconnected')
        result['eventsFinal'] = dump('final')
        result['resourcesFinal'] = {name: {field: session.command(f'data get entity {name} {field}') for field in ['Inventory', 'XpTotal', 'Health']} for name in ['WorkerA', 'WorkerB']}
        session.command('save-all flush')
        session.request('close')
        session.bots.wait(timeout=10)
        time.sleep(.5)
        result['final'] = session.snapshot('final')
        session.stop()
        result['status'] = 'passed'
    except BaseException as error:
        result.update(status='failed', error=repr(error))
        raise
    finally:
        session.close()
        result.update(serverExits=session.exits, clientsExit=session.bots.returncode)
        (session.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    assert result['serverExits'] == [0] and result['clientsExit'] == 0


if __name__ == '__main__':
    main()
