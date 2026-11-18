#!/usr/bin/env python3
"""Preserved scale corpus proof mutations; never starts a JVM or alters original test evidence."""
import argparse
import copy
import json
from pathlib import Path
from verify_player_scale import verify, lines, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--installer', type=Path, required=True)
    args = parser.parse_args()
    root, installer = args.server.resolve(), args.installer.resolve()
    verdict = verify(root, installer)
    native = lines(root / 'player-scale/jmx.ndjson')
    first = verdict['windows'][0]['name']
    controls = []

    def reject(label, change):
        values = copy.deepcopy(native)
        change(values)
        try:
            verify(root, installer, values)
        except AssertionError as failure:
            controls.append(dict(label=label, rejected=True, reason=str(failure)))
        else:
            raise AssertionError('Corrupted scale proof accepted: ' + label)

    reject('missing_native_sample', lambda values: values.pop(len(values)//2))
    reject('no_measured_tick_ring', lambda values: [row.update(tickTimesNanos=[0]*100) for row in values if row['window']==first])
    reject('negative_original_tick', lambda values: next(row for row in values if row['window']==first)['tickTimesNanos'].__setitem__(0,-1))
    reject('invented_window_time', lambda values: next(row for row in values if row['window']==first).update(monitorNanos=1))
    path = root / 'player-scale/proof-negative-controls.json'
    require(not path.exists(), 'Preserve previous scale controls')
    path.write_text(json.dumps(dict(status='passed', controls=controls), indent=2)+'\n')
    print(json.dumps(dict(status='passed', negativeControls=len(controls), controls=controls)))


if __name__ == '__main__':
    main()
