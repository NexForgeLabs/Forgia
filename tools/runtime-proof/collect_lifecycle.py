#!/usr/bin/env python3
"""Collect separate, explicitly scoped lifecycle counts and immutable evidence references."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def reference(path):
    return {'path': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'bytes': path.stat().st_size}


def counts(passed=0, failed=0, skipped=0, not_processed=0, aborted=0):
    return {'Passed': passed, 'Failed': failed, 'Skipped': skipped, 'Not Processed': not_processed, 'Aborted': aborted}


def framework(path):
    cases = {}
    rows = list(ET.parse(path).getroot().iter('testcase'))
    for case in rows:
        state = 'Failed' if case.find('failure') is not None else 'Not Processed' if case.find('skipped') is not None else 'Passed'
        previous = cases.setdefault(case.attrib['name'], state)
        if previous != state:
            raise ValueError('Conflicting duplicate framework ID: ' + case.attrib['name'])
    total = counts()
    total.update(Counter(cases.values()))
    return {'population': 'distinct framework IDs; overlaps GameTest functions, do not add',
            'rawXmlRows': len(rows), 'counts': total, 'ids': cases}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', default='tmp/lifecycle-20261115')
    parser.add_argument('--source-revision', required=True)
    parser.add_argument('--output', default='region-threading/LIFECYCLE-CERTIFICATION-2026-11-15.json')
    args = parser.parse_args()
    root = ROOT / args.evidence
    names = [*[f'gc-fixed-{i}' for i in range(1, 7)], 'gc-persistent-1', 'unit-fixed', 'unit-persistent',
             'associated', 'races-final-fixed', 'races-final-persistent']
    runs = []
    for name in names:
        directory = root / name
        execution = json.loads((directory / 'result.json').read_text())
        log = (directory / 'output.log').read_text()
        server = None
        if (directory / 'server-execution.json').exists():
            server = json.loads((directory / 'server-execution.json').read_text()).get('exitCode')
        failure_exit = re.findall(r'finished with non-zero exit value (\d+)', log)
        if failure_exit:
            server = int(failure_exit[-1])
        row = {'id': name, 'execution': execution, 'serverExitCode': server,
               'runtimeMarkers': [line for line in log.splitlines() if line.startswith(('LIFECYCLE_', 'C6_FIRST_ORIGINAL_MOD_WORKERS', 'C6_REAL_MOD_', 'C6_EXPECTED_FAILURE_ACCOUNTING', 'SAVE_RELOAD_'))]}
        if name.startswith(('unit-', 'races-')):
            total = counts()
            suites = []
            for xml in sorted((directory / 'junit').glob('TEST-*.xml')):
                suite = ET.parse(xml).getroot()
                failed = int(suite.get('failures', '0')) + int(suite.get('errors', '0'))
                skipped = int(suite.get('skipped', '0'))
                passed = int(suite.get('tests')) - failed - skipped
                total['Passed'] += passed
                total['Failed'] += failed
                total['Skipped'] += skipped
                suites.append({'class': suite.get('name'), 'counts': counts(passed, failed, skipped)})
            row.update({'population': 'selected JUnit executions', 'counts': total, 'suites': suites,
                        'testJvmExitCode': 0 if execution['gradleExitCode'] == 0 else None})
        else:
            completed = re.findall(r'=+ (\d+) GAME TESTS COMPLETE', log)
            selected = re.findall(r'kept (\d+) of (\d+) tests', log)
            if name == 'gc-fixed-3':
                row.update({'population': 'selected GameTest functions', 'counts': counts(aborted=1), 'status': 'Aborted on integrity refusal / failed Worker assertion'})
            elif name in ('gc-fixed-1', 'gc-fixed-2'):
                row.update({'population': 'requested runtime case; server never launched', 'counts': counts(not_processed=1),
                            'status': 'prelaunch init failure' if name.endswith('1') else 'test compilation failure'})
            else:
                if not completed:
                    raise ValueError('No completed GameTest tracker: ' + name)
                failed = re.findall(r'(\d+) required tests failed', log)
                failed_count = int(failed[-1]) if failed else 0
                if not failed_count and not re.search(r'All ' + completed[-1] + r' required tests passed', log):
                    raise ValueError('No explicit completed GameTest verdict: ' + name)
                row.update({'population': 'selected GameTest functions', 'counts': counts(passed=int(completed[-1]) - failed_count, failed=failed_count)})
                if selected:
                    row['registeredGameTestFunctions'] = int(selected[-1][1])
                    row['outsideSelectionNotProcessed'] = int(selected[-1][1]) - int(selected[-1][0])
                xmls = list((directory / 'server/tests').glob('*.xml'))
                if len(xmls) != 1:
                    raise ValueError('Expected exactly one raw framework XML: ' + name)
                row['framework'] = framework(xmls[0])
            if (directory / 'level-gc.json').exists():
                gc = json.loads((directory / 'level-gc.json').read_text())
                row['gcCertification'] = gc
                row['gcCertificationCounts'] = counts(passed=1) if gc['verdict'] == 'CERTIFIED' else counts(failed=1)
                row['gcPopulationNote'] = 'post-shutdown certification is separate from the pre-shutdown GameTest body; overlapping populations, never add'
        paths = [directory / file for file in ('launch.json', 'result.json', 'output.log', 'source.diff', 'source-sha256.json',
                 'server-launch.json', 'server-execution.json', 'gc.log', 'level-gc.json', 'retention-path.json', 'retention-parser.log', 'level-gc.json.hprof')]
        for subdir, glob in [('junit', '*.xml'), ('arguments', '*'), ('server/tests', '*.xml'), ('server/config', 'forgia.toml'), ('artifacts', '*')]:
            paths.extend((directory / subdir).glob(glob))
        row['evidence'] = [reference(path) for path in sorted(set(paths)) if path.is_file()]
        runs.append(row)
    by_id = {row['id']: row for row in runs}
    for name in ('gc-fixed-6', 'gc-persistent-1'):
        row = by_id[name]
        gc = row['gcCertification']
        if not (gc['verdict'] == 'CERTIFIED' and gc['weakReferencesCleared'] and len(gc['referenceQueueArrivals']) == 28
                and gc['observations'][-1]['consecutiveAllCleared'] == 3 and gc['gcCollectionsObserved'] >= 3
                and gc['oldCallbackForeignReadWriteRefusals'] == 12 and row['serverExitCode'] == 0
                and row['execution']['gradleExitCode'] == 0 and not row['execution']['sourceChangesDuringRun']):
            raise ValueError('Final real-Level GC requirements not met: ' + name)
    for name, passed in [('unit-fixed', 116), ('unit-persistent', 116), ('races-final-fixed', 4), ('races-final-persistent', 4), ('associated', 4)]:
        row = by_id[name]
        if row['counts'] != counts(passed=passed) or row['execution']['gradleExitCode'] != 0:
            raise ValueError('Selected regression incomplete/failed: ' + name)
    if by_id['associated']['serverExitCode'] != 0:
        raise ValueError('Associated server did not exit successfully')
    frozen = json.loads((root / 'frozen-c6-audit.json').read_text())
    preserved = json.loads((root / 'source-preservation.json').read_text())
    if (frozen['matching'] != frozen['checked'] or not preserved['unchangedProductionSinceC6']
            or not all(item['match'] for item in preserved['unchangedC6Definitions'].values())
            or not all(item['match'] for item in preserved['runtimeArtifactsComparedWithC6'])):
        raise ValueError('Frozen C6 evidence changed')
    for name, expected in json.loads((root / 'preflight.json').read_text())['frozenC6'].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected:
            raise ValueError('Frozen C6 document changed: ' + name)
    result = {
        'schemaVersion': 1,
        'mission': 'Lifecycle certification, separate from frozen C6; not C7',
        'results': {'realLevelGc': 'CERTIFIED', 'runtimeModHotUnload': 'NOT CERTIFIED'},
        'baselineHead': 'acd5207cbbe55e138cb9ba6c30b774b7322b1bc0', 'baselineBranch': 'main', 'baselineWorkingTree': 'clean',
        'deliveredTestAndAuditRevision': args.source_revision,
        'reportRevision': 'the Git commit containing this receipt; no circular self-hash',
        'productionRevision': '0ede200610867aea46d5473c7f6ec4fccdd063e0',
        'productionFingerprintPreservedFromC6': 'f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787',
        'environment': {'minecraft': '1.21.1', 'neoforge': '21.1.250.60-beta', 'fml': '4.0.42', 'modlauncher': '11.0.5',
                        'secureJarHandler': '3.0.8', 'gradle': '8.13', 'jdk': 'Temurin 21.0.13+9',
                        'os': preserved['os'], 'collector': 'G1',
                        'originalMod': 'piq_fc_arcade 0.31.0-alpha.61',
                        'originalModSha256': 'd4cb3a41cd3d98c99a0118b1e97475b5781106711ac1d4e321d87c4c2506c510'},
        'runtimeModUnload': {
            'supported': False, 'verdict': 'NOT CERTIFIED',
            'actualBoundary': 'normal real-Level unload/close followed by whole-server shutdown; original FML container and TransformingClassLoader remain loaded',
            'unprocessedUnsupportedScenarios': {name: 'Not Processed: no supported runtime Mod unload/reload lifecycle'
                                              for name in ('queued-hot-unload', 'running-hot-unload', 'releasing-hot-unload', 'reload-after-hot-unload')},
            'counts': counts(not_processed=4),
            'basis': 'installed loader API/bytecode/event inventories, repository startup/shutdown source, JLS 12.7, and same-container/same-defining-loader post-server runtime observations',
        },
        'finalRealLevelRuns': ['gc-fixed-6', 'gc-persistent-1'],
        'regressionAccounting': '116 selected JUnit executions per configuration; the four newly strengthened race cases were rerun separately in each configuration. These reruns overlap the 116-case populations. All four original-Mod associated GameTests completed. Framework IDs and GameTest bodies are overlapping populations. Original failed/aborted attempts below remain failures/aborts.',
        'newRaceScope': 'queued, queued plus same-delegate rebind, running, releasing; actual NeoForge unload callback, repeated unload, shutdown during pending cleanup, physical joins, every retained old callback attempted and zero forbidden state changes. Uses JUnit world seams and the existing fixed executor delivery gate in both flag profiles; not extra persistent-queue cases, real-Level GC or Mod hot-unload.',
        'c6': {'acceptance': 'C6 FINAL ARCHITECTURE ACCEPTANCE: PASS', 'preserved': True, 'invalidatedEvidence': [],
               'checkedEvidenceReferences': frozen['checked'], 'matchedEvidenceReferences': frozen['matching'],
               'anotherUnfilteredProfilePairRequired': False, 'anotherUnfilteredProfilePairRun': False,
               'reason': 'production sources/artifacts and canonical profile definitions unchanged; only opt-in test instrumentation, component tests, read-only diagnostics and documentation added'},
        'productionChanges': [], 'productionCommits': [], 'productionDeployment': False, 'secondWorkerAdapter': False, 'c7Started': False,
        'limitations': ['Whole stopped server graph, not an individual Level detached from a still-live server map.',
                        'No general Mod classloader collection/unloading or hot reload certification.',
                        'The real-Level fixture has no connected players and two original Mod sources in the End; all three real Levels are tracked.',
                        'GameTest-only exit observation plus public GameTestTicker.clear are necessary to observe collection before JVM exit; no Core/Level integrity override.',
                        'Optional native interception retains bounded callback metadata until normal dead-key pruning; this test proves it does not pin retired worlds, not that every metadata row is eagerly deleted.',
                        'C6 opt-in workload skips and manual/framework registrations retain their original coverage limits.'],
        'runs': runs,
        'capabilityEvidence': [reference(path) for path in sorted((root / 'capability').iterdir()) if path.is_file()],
        'preservationEvidence': [reference(root / name) for name in ('preflight.json', 'frozen-c6-audit.json', 'source-preservation.json')],
        'audit': 'region-threading/LIFECYCLE-AUDIT.md', 'report': 'region-threading/LIFECYCLE-CERTIFICATION-2026-11-15.md',
    }
    (ROOT / args.output).write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'results': result['results'], 'runs': len(runs), 'output': args.output}, indent=2))


if __name__ == '__main__':
    main()
