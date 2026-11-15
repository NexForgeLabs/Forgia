#!/usr/bin/env python3
"""Export primary controlled_server results only after exact workload/NBT equivalence checks."""
import argparse
import hashlib
import json
from pathlib import Path


def export(paths):
    runs = []
    reference = None
    shape = None
    for path in paths:
        data = json.loads(path.read_text())
        if data.get('outcome') != 'PASS' or data.get('profiled', False):
            raise ValueError('Primary export requires complete unprofiled PASS runs: ' + str(path))
        current_shape = (data['count'], data['warmupTicks'], data['windowTicks'], len(data['results']))
        signatures = [(row['window'], row['ticks'], row['outputItems'], row['stateBeforeSha256'], row['stateAfterSha256']) for row in data['results']]
        if shape is None:
            shape, reference = current_shape, signatures
        elif current_shape != shape or signatures != reference:
            raise ValueError('Workload or exact NBT differs: ' + str(path))
        rows = []
        for result in data['results']:
            metrics = result['metrics']
            thread = result['threadMetrics']
            rows.append(dict(window=result['window'], ticks=result['ticks'], outputItems=result['outputItems'],
                             mspt=metrics['mspt'], observedRingTps=metrics['observedTps'],
                             allocationMiBPerSecond=metrics['serverAndWorkerAllocatedBytes'] / metrics['allocationObservationSeconds'] / 2**20,
                             allocationObservationSeconds=metrics['allocationObservationSeconds'],
                             mainCpuSeconds=thread['mainCpuSeconds'], workerCpuSeconds=metrics['workerCpuSeconds'],
                             threadObservationSeconds=thread['observationSeconds'],
                             monitorBlockedEntrances=sum(row['blockedEntrances'] for row in thread['threads']),
                             gcCollectionCount=metrics['gcCount'], gcCollectionMillis=metrics['gcMillis']))
        runs.append(dict(evidence=str(path), evidenceSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                         version=data['version'], universalSha256=data['universalSha256'],
                         bridge=data['bridge'], diagnostics=data['diagnostics'], stock=data['stock'], windows=rows))
    return dict(schemaVersion=1, exactNbtAndOutputEquivalence=True,
                workload=dict(count=shape[0], warmupTicks=shape[1], measuredTicksPerWindow=shape[2], windowsPerJvm=shape[3],
                              seed=813584, layout='regions', configuredWorkers=4, heap='2GiB fixed G1', java='Temurin 21.0.13+9'),
                caveats=['Consecutive windows are not independent JVM repeats.',
                         'Ring TPS uses conservative active-interval reconstruction and can miss an equal-valued endpoint.',
                         'Stock baseline is NeoForge 21.1.249; both Forgia builds use Minecraft 1.21.1 with their declared upstream revision.',
                         'GC collection time is not pause duration; monitor entrances exclude other forms of contention.',
                         'No connected players, modpack, chunk loading throughput or new generation throughput.'],
                runs=runs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('results', type=Path, nargs='+')
    args = parser.parse_args()
    data = export(args.results)
    args.output.write_text(json.dumps(data, indent=2) + '\n')
    print('Verified', len(data['runs']), 'JVMs and', sum(len(run['windows']) for run in data['runs']), 'windows')


if __name__ == '__main__':
    main()
