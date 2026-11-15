#!/usr/bin/env python3
"""Comparable paced stock/Forgia fixture using the existing private-world/JMX controller.

Each invocation is one fresh pinned JVM. This does not use a helper mod or alter the native tick ring.
Compare full before/after NBT hashes across invocations before drawing a performance conclusion.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/block-entity-resource-ab'))
from server_ab import Installed, dump, metrics_summary, graph


def item_count(states, slot):
    return sum(sum(int(m[1]) for m in re.finditer(r'count:\s*(\d+),\s*Slot:\s*' + str(slot) + 'b', state)) for state in states)


def thread_deltas(samples, start, end):
    selected = [sample for sample in samples if sample['beforeNs'] >= start and sample['afterNs'] <= end and 'threads' in sample]
    if len(selected) < 2:
        raise ValueError('Insufficient thread observations')
    elapsed = (selected[-1]['beforeNs'] - selected[0]['beforeNs']) / 1e9
    rows = []
    for before in selected[0]['threads']:
        after = next((row for row in selected[-1]['threads'] if row['id'] == before['id']), None)
        if after is None:
            raise ValueError('Observed thread retired inside measured window')
        rows.append(dict(name=before['name'], cpuSeconds=(after['cpuNs'] - before['cpuNs']) / 1e9,
                         blockedEntrances=after['blockedCount'] - before['blockedCount'],
                         allocatedBytes=after['allocatedBytes'] - before['allocatedBytes']))
    return dict(observationSeconds=elapsed, threads=rows,
                mainCpuSeconds=sum(row['cpuSeconds'] for row in rows if row['name'] == 'Server thread'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=Path, required=True)
    parser.add_argument('--installation', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--universal-sha256', required=True)
    parser.add_argument('--eula', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--bridge', action='store_true')
    parser.add_argument('--diagnostics', action='store_true')
    parser.add_argument('--stock', action='store_true')
    parser.add_argument('--jfr', type=Path, help='Separate diagnostic run: JFR settings file; never pool with unprofiled measurements')
    parser.add_argument('--count', type=int, default=64)
    parser.add_argument('--warmup-ticks', type=int, default=400)
    parser.add_argument('--window-ticks', type=int, default=400)
    parser.add_argument('--windows', type=int, default=2)
    args = parser.parse_args()
    if args.runs.exists():
        raise ValueError('Use a new disposable output directory')
    if not 1 <= args.count <= 256 or args.window_ticks <= 0 or args.window_ticks % 200 or args.warmup_ticks <= 0:
        raise ValueError('count=1..256, positive warmup, and window a positive multiple of 200 required')
    args.runs = args.runs.resolve()
    args.installation = args.installation.resolve()
    args.classes = args.classes.resolve()
    core = args.installation / f'libraries/net/neoforged/neoforge/{args.version}/neoforge-{args.version}-universal.jar'
    actual = hashlib.sha256(core.read_bytes()).hexdigest()
    if actual != args.universal_sha256:
        raise ValueError('Pinned installed core differs: ' + actual)
    if not re.search(r'^\s*eula\s*=\s*true\s*$', args.eula.read_text(), re.M):
        raise ValueError('An already accepted local EULA is required')
    args.runs.mkdir(parents=True)
    args.counts = [args.count]
    args.bridge_enabled = args.bridge
    args.diagnostic_sampling = args.diagnostics
    args.persistent_workers = True
    run = Installed(args, 'regions', 4, True, 0)
    results = []
    failure = None
    try:
        run.prepare()
        run.command(f'function forgia_ab:setup{args.count}')
        run.advance(args.warmup_ticks)
        if args.jfr:
            import subprocess
            with (run.root / 'jfr-start.txt').open('w') as log:
                subprocess.run([str(args.java.with_name('jcmd')), str(run.proc.pid), 'JFR.start',
                                'name=forgia_diagnostic', 'settings=' + str(args.jfr.resolve()),
                                'filename=' + str(run.root / 'steady.jfr'), 'dumponexit=true'],
                               stdout=log, stderr=subprocess.STDOUT, check=True)
            run.advance(200)
        for window in range(args.windows):
            before = run.state(args.count)
            before_graph = None if args.stock else graph(run.command('neoforge regionthreading workgraph')) if args.bridge else None
            ticks = run.advance(args.window_ticks)
            active_start, active_end = run.active_bounds
            after = run.state(args.count)
            produced = item_count(after, 2) - item_count(before, 2)
            if produced != args.count * (ticks // 200):
                raise AssertionError('Incorrect output count')
            if item_count(after, 0) + item_count(after, 2) != args.count * 64:
                raise AssertionError('Inventory conservation failure')
            if before_graph is not None:
                after_graph = graph(run.command('neoforge regionthreading workgraph'))
                if after_graph['furnace.nativeCompleted'] - before_graph['furnace.nativeCompleted'] != args.count * ticks:
                    raise AssertionError('Worker body total mismatch')
                for key in ['furnace.failed', 'furnace.skipped', 'furnace.rejectedTasks', 'resource.refusedWaves']:
                    if after_graph[key] != before_graph[key]:
                        raise AssertionError(key)
            commands = {}
            if not args.stock:
                for command in ['forgia performance json', 'forgia workers', 'forgia scheduler', 'forgia regions', 'forgia region top avg 3', 'forgia diagnostics']:
                    commands[command] = run.command(command)
            time.sleep(.2)
            samples = [json.loads(line) for line in (run.root / 'metrics.jsonl').read_text().splitlines()]
            start, end = active_start + 100_000_000, active_end - 100_000_000
            epoch_offset = time.time_ns() - time.monotonic_ns()
            result = dict(window=window, ticks=ticks, outputItems=produced,
                          activeInnerStartNs=start, activeInnerEndNs=end,
                          activeInnerStartEpochNs=start + epoch_offset, activeInnerEndEpochNs=end + epoch_offset,
                          stateBeforeSha256=hashlib.sha256('\n'.join(before).encode()).hexdigest(),
                          stateAfterSha256=hashlib.sha256('\n'.join(after).encode()).hexdigest(),
                          metrics=metrics_summary(samples, start, end),
                          threadMetrics=thread_deltas(samples, start, end),
                          commands=commands)
            dump(run.root / f'window{window}.json', dict(result=result, before=before, after=after))
            results.append(result)
            print(json.dumps(dict(window=window, mspt=result['metrics']['mspt'], tps=result['metrics']['observedTps'])), flush=True)
    except BaseException as error:
        failure = repr(error)
        raise
    finally:
        try:
            run.close()
        except BaseException as error:
            failure = failure or repr(error)
            raise
        finally:
            dump(args.runs / 'result.json', dict(outcome='FAIL' if failure else 'PASS', failure=failure,
                 version=args.version, universalSha256=actual, bridge=args.bridge, diagnostics=args.diagnostics,
                 stock=args.stock, profiled=args.jfr is not None, count=args.count, warmupTicks=args.warmup_ticks, windowTicks=args.window_ticks,
                 results=results))


if __name__ == '__main__':
    main()
