#!/usr/bin/env python3
"""Proof negative controls: missing worker, reordered/duplicated callbacks, MAIN/NETWORK regressions, lost replies."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
from verify_region_payload import verify, require, load, TOOLS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--installer', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = args.server.resolve()
    out = root / 'region-payload'
    verdict = verify(root, args.installer.resolve())
    require(verdict['requested'], 'Positive worker evidence required')
    original = load(out / 'after-events.json')['events']
    decoded = [json.loads(row) for row in subprocess.check_output(['node', str(TOOLS / 'decode_region_payload.cjs'), str(out / 'packets.ndjson')], text=True).splitlines()]
    controls = []

    def reject(label, change):
        evidence = dict(events=copy.deepcopy(original), decoded=copy.deepcopy(decoded))
        change(evidence)
        try:
            verify(root, args.installer.resolve(), evidence)
        except AssertionError as failure:
            controls.append(dict(label=label, rejected=True, reason=str(failure)))
        else:
            raise AssertionError('Corrupted proof accepted: ' + label)

    reject('no_actual_worker', lambda data: [row.update(worker=False, main=True, thread='Server thread') for row in data['events'] if row['kind'] == 'local'])
    reject('duplicate_begun_handler', lambda data: data['events'].append(copy.deepcopy(next(row for row in data['events'] if row['kind'] == 'local'))))
    reject('lost_main_fence', lambda data: data['events'].remove(next(row for row in data['events'] if row['kind'] == 'main')))
    reject('network_on_server', lambda data: next(row for row in data['events'] if row['kind'] == 'network').update(main=True, network=False, thread='Server thread'))
    reject('completion_on_worker', lambda data: next(row for row in data['events'] if row['kind'] == 'completion').update(main=False, worker=True, thread='NeoForge Region Worker #1'))
    reject('missing_client_reply', lambda data: data['decoded'].remove(next(row for row in data['decoded'] if row['direction'] == 'frame' and row['name'] == 'custom_payload' and row['data']['channel'] == 'forgia_region:reply')))
    reject('invented_client_input', lambda data: next(row for row in data['decoded'] if row['direction'] == 'frame_send' and row['name'] == 'custom_payload' and row['data']['channel'] == 'forgia_region:local')['data'].update(text='different_client_payload'))
    reject('wrong_player_instance', lambda data: next(row for row in data['events'] if row['kind'] == 'local' and row['worker']).update(instance=-1))
    reject('network_crossed_join', lambda data: next(row for row in data['events'] if row['kind'] == 'network').update(nano=0))
    path = args.output or out / 'proof-negative-controls.json'
    require(not path.exists(), 'Preserve earlier controls')
    path.write_text(json.dumps(dict(status='passed', controls=controls), indent=2) + '\n')
    print(json.dumps(dict(status='passed', controls=len(controls))))


if __name__ == '__main__':
    main()
