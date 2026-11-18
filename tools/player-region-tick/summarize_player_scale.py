#!/usr/bin/env python3
"""Pair original scale cells only after independent correctness; one pass never proves a speedup."""
import argparse
import hashlib
import json
from pathlib import Path
from verify_player_scale import verify, require

MODES = ['off-fixed', 'on-fixed', 'on-persistent', 'off-persistent']


def evidence_roots(args):
    """Keep the original whole-matrix gate; a reviewed map audits each complete subrun anew."""
    if args.matrix:
        summary = json.loads((args.matrix / 'matrix-result.json').read_text())
        require(summary['status'] == 'passed', 'Incomplete or failed original scale matrix')
        roots = {mode: args.matrix for mode in MODES}
        labels = {args.matrix: [row['label'] for row in summary['commands']]}
        require(all(row['exit'] == 0 for row in summary['commands']), 'Original matrix command failed')
        provenance = dict(kind='complete_original_matrix', path=str(args.matrix))
    else:
        review = json.loads(args.accepted_evidence.read_text())
        require(review.get('schema') == 1 and review.get('review', {}).get('kind') == 'explicit-per-mode-source-and-exit-audit'
                and review['review'].get('reason'), 'Missing explicit partial-matrix review')
        require(review['installerSha256'] == hashlib.sha256(args.installer.read_bytes()).hexdigest(), 'Reviewed installer differs')
        require(set(review['modes']) == set(MODES), 'Reviewed scale mode set incomplete')
        roots = {mode: Path(review['modes'][mode]) for mode in MODES}
        labels = {}
        for mode, root in roots.items():
            labels.setdefault(root, set()).update(['compile-monitor', 'install-'+mode, 'run-'+mode, 'verify-'+mode])
        provenance = dict(kind='reviewed_complete_subruns_from_preserved_partial_matrices',
                          path=str(args.accepted_evidence), sha256=hashlib.sha256(args.accepted_evidence.read_bytes()).hexdigest(),
                          review=review['review'])
    commands = []
    for root, names in labels.items():
        progress = {row['label']: row['exit'] for row in json.loads((root/'matrix-progress.json').read_text())}
        for label in sorted(names):
            path = root / label / 'result.json'
            command = json.loads(path.read_text())
            require(progress.get(label) == command['exit'] == 0 and command['argv'], 'Actual subrun command failed/missing')
            commands.append(dict(path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    return roots, commands, provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--matrix', type=Path)
    source.add_argument('--accepted-evidence', type=Path, help='Explicit audit map of complete per-mode subruns; never rewrites failed matrices')
    parser.add_argument('--installer', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'Preserve previous scale summary')
    roots, commands, provenance = evidence_roots(args)
    verdicts = {}
    fixtures = {}
    for mode in MODES:
        root = (roots[mode] / ('server-' + mode)).resolve()
        run = json.loads((root / 'player-scale/result.json').read_text())
        fixtures[mode] = (run.get('quietAdvancementFixture', False), run.get('networkDiagnostics', False),
                          run.get('clientTopology', 'single-protocol-process'))
        actual = verify(root, args.installer.resolve())
        prior = json.loads((root / 'player-scale/independent-verdict.json').read_text())
        require(actual == prior and len(actual['windows']) == 9, 'Stored versus freshly verified scale evidence differs')
        require(actual['requested'] == mode.startswith('on-') and actual['persistent'] == mode.endswith('persistent'), 'Reviewed mode mislabeled')
        verdicts[mode] = actual
    require(len(set(fixtures.values())) == 1, 'Mixed advancement fixture, network instrumentation or client topology')
    quiet_fixture, network_diagnostics, topology = next(iter(fixtures.values()))
    paired = []
    for pool in ['fixed', 'persistent']:
        off = {row['name']:row for row in verdicts['off-' + pool]['windows']}
        on = {row['name']:row for row in verdicts['on-' + pool]['windows']}
        require(set(off) == set(on), 'Missing paired load cells')
        for name, before in off.items():
            after = on[name]
            off_ms, on_ms = before['performance']['tickMs'], after['performance']['tickMs']
            off_ping, on_ping = before['pingMs'], after['pingMs']
            paired.append(dict(pool=pool, name=name, clients=before['count'], distribution=before['distribution'],
                               offTickMs=off_ms, onTickMs=on_ms, tickMeanDeltaPercent=(on_ms['mean']/off_ms['mean']-1)*100,
                               tickP99DeltaPercent=(on_ms['p99']/off_ms['p99']-1)*100,
                               offPingMs=off_ping, onPingMs=on_ping, pingP99DeltaPercent=(on_ping['p99']/off_ping['p99']-1)*100,
                               onPhaseCoverage=after['phaseCoverage'], onSerialReasons=after['serialReasons'],
                               offAllocatedBytesPerSecond=before['performance']['allocatedBytesPerSecond'],
                               onAllocatedBytesPerSecond=after['performance']['allocatedBytesPerSecond'],
                               onSampledBarrierWaitingFraction=after['performance']['sampledBarrierWaitingFraction'],
                               exactBarrierOverheadNanos=None, correctness='passed', speedupCertified=False,
                               reason='Single preliminary same-host window; controlled repeats and sustained/mixed correctness remain required'))
    result = dict(schema=1, status='passed_scale_correctness_preliminary_performance', version=next(iter(verdicts.values()))['version'],
                  actualLoadCells=36, pairedCells=18, actualClients=[1,8,32], distributions=['same','spread','boundary'],
                  quietAdvancementFixture=quiet_fixture, networkDiagnostics=network_diagnostics, clientTopology=topology,
                  commands=commands, evidenceProvenance=provenance, comparisons=paired, wholeP5Complete=False, defaultEnabled=False,
                  unmeasured=['Exact dispatch/preparation/barrier CPU overhead', 'Controlled randomized repeated on/off performance trials',
                              'Mixed Mod gameplay, long-soak and recovery beyond these bounded scale windows'],
                  safety='No observer Mod/FakePlayer/JDI; original full calls and actual normal client packets; unknown extensions remain serial')
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    table = ['| Pool | Clients | Distribution | Tick mean off/on ms | Tick p99 off/on ms | Ping p99 off/on ms | Worker ENTITY/CONNECTION |',
             '|---|---:|---|---:|---:|---:|---:|']
    for row in paired:
        percent = '/'.join(f"{row['onPhaseCoverage'][phase]['workerFraction']:.1%}" for phase in ['ENTITY','CONNECTION'])
        table.append(f"| {row['pool']} | {row['clients']} | {row['distribution']} | {row['offTickMs']['mean']:.3f}/{row['onTickMs']['mean']:.3f} | {row['offTickMs']['p99']:.3f}/{row['onTickMs']['p99']:.3f} | {row['offPingMs']['p99']:.3f}/{row['onPingMs']['p99']:.3f} | {percent} |")
    args.output.with_suffix('.md').write_text('Exploratory measurements; all paired correctness gates passed. No speedup certification from one pass.\n\n'+'\n'.join(table)+'\n')
    print(json.dumps(dict(status=result['status'], actualLoadCells=36, pairedCells=18, speedupCertified=False)))


if __name__ == '__main__':
    main()
