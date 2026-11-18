#!/usr/bin/env python3
"""Close a bounded P3 acceptance from an explicit same-product evidence map.

Read-only against installations and corpora. Writes a new audit directory only.
Failed/interrupted matrices remain failures; completed subcases may be accepted
only when explicitly mapped and all their original command exits are present.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]
MODES = ('on-fixed', 'on-persistent', 'off-fixed', 'off-persistent')
KINDS = {
    'xp': 'worker-xp-removal', 'item': 'worker-item-pickup',
    'chest': 'worker-shared-chest', 'score': 'worker-scoreboard',
    'map': 'worker-backpack-map', 'damage': 'worker-starvation-damage',
    'damage-foreign': 'worker-starvation-damage',
}
P2_COMMAND_COUNTS = {
    'functional': 48, 'smoke': 12, 'network': 24, 'same-group': 16,
    'fault': 16, 'socket': 12, 'transitions': 12, 'contract': 12,
    'network-contract': 12, 'timers': 12, 'lifecycle': 12,
    'respawn': 12, 'phase-order': 12,
}


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def load(path):
    return json.loads(Path(path).read_text())


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def record(path):
    path = Path(path)
    return dict(path=str(path.relative_to(REPO)), sha256=sha(path), bytes=path.stat().st_size)


def command(path):
    result = load(Path(path) / 'result.json')
    require(result.get('exit') == 0 and result.get('argv'), 'Missing successful real command: ' + str(path))
    return result


def audit(args):
    product = args.product_root.resolve()
    evidence = load(args.evidence_map)
    manifest_path = TOOLS / 'reviewed-artifacts/21.1.250.134-beta.json'
    from reviewed_artifacts import read_manifest
    manifest, _ = read_manifest(manifest_path)
    require(manifest['version'] == evidence['version'], 'Evidence/product version mismatch')
    version = manifest['version']
    installer = product / 'artifacts' / ('neoforge-' + version + '-installer.jar')
    require(sha(installer) == manifest['installerSha256'], 'Final installer differs from reviewed bytes')
    for name, digest in manifest['review']['sources'].items():
        require(sha(REPO / name) == digest, 'Production source changed: ' + name)
        require(sha(product / 'source' / name) == digest, 'Build source snapshot differs: ' + name)
    for label in ('build-final-01', 'regression-01', 'gametest-command',
                  'static-final-command', 'static-closeout-command', 'artifact-tool-controls', 'ndjson-tool-controls', 'isolation-tool-controls'):
        command(product / label)
    for label in ('chain-a-command', 'chain-b-command'):
        command(product / label)
    for label in ('secondary-command', 'supplemental-controls-command', 'gameplay-retry01-command'):
        wrapper = load(product / label / 'result.json')
        require(isinstance(wrapper.get('exit'), int) and wrapper.get('argv'), 'Wrapper exit remains unknown: ' + label)
    junit = load(product / 'regression-01/junit-summary.json')
    require(junit == dict(tests=557, failures=0, errors=0, skipped=0), 'Full final JUnit result differs')
    import xml.etree.ElementTree as ET
    game_xml = list((product / 'gametest-server/tests').glob('*.junit.xml'))
    require(len(game_xml) == 1, 'Missing unique final GameTest XML')
    cases = ET.parse(game_xml[0]).getroot().findall('.//testcase')
    selected = {'physicalEntitySectionCrossing', 'blockEntityOwnerOperations', 'unsafeTickerForcesWholeRegionFallback'}
    require({case.get('name') for case in cases if case.find('passed') is not None} >= selected
            and all(case.find('failure') is None and case.find('error') is None for case in cases),
            'Selected GameTests did not all pass')
    patches = load(product / 'patch-proof-01.json')
    require(patches['status'] == 'passed' and patches['fileCount'] == 9, 'Patch proof incomplete')
    require(sha(REPO / patches['archive']) == patches['archiveSha256'], 'Applied patch archive changed')
    with zipfile.ZipFile(REPO / patches['archive']) as archive:
        for row in patches['files']:
            require(sha(REPO / row['patch']) == row['patchSha256'], 'Persisted patch changed')
            require(sha(REPO / row['generated']) == row['sourceSha256'], 'Generated source changed')
            name = row['generated'].split('/src/main/java/', 1)[1]
            require(hashlib.sha256(archive.read(name)).hexdigest() == row['sourceSha256'], 'Applied patch archive differs')
    require(set(evidence['p2']) == set(P2_COMMAND_COUNTS), 'Missing/extra P2 acceptance suites')
    verdicts, roots, accepted_commands, controls = [], set(), [], []
    schema_counts = {3: 0, 4: 0}

    def proof_controls(path, category):
        command(path)
        data = load(path / 'stdout.log')
        require(data['status'] == 'passed' and data['negativeControls'] > 0
                and data['negativeControls'] == len(data['controls'])
                and all(row['status'] == 'rejected' for row in data['controls']),
                'Proof controls incomplete: ' + str(path))
        controls.append(dict(category=category, command=record(path / 'result.json'),
                             negativeControls=data['negativeControls']))
    for suite, count in P2_COMMAND_COUNTS.items():
        matrix = REPO / evidence['p2'][suite]
        result = load(matrix / 'matrix-result.json')
        require(result['status'] == 'passed' and result['suite'] == suite and len(result['commands']) == count,
                'Incomplete P2 suite: ' + suite)
        require(len({r['label'] for r in result['commands']}) == count, 'Repeated P2 command label')
        for row in result['commands']:
            require(row['exit'] == 0, 'P2 command failed')
            command(matrix / row['label'])
            accepted_commands.append(matrix / row['label'] / 'result.json')
            if row['label'].startswith(('controls-', 'phase-controls-')):
                proof_controls(matrix / row['label'], 'p2-' + suite)
        for root in matrix.glob('server-*'):
            roots.add(root)
            found = list(root.glob('*/independent-verdict*.json'))
            if suite == 'functional':
                found.append(root / 'phase-order.json')
            require(found, 'P2 installation has no independent verdict: ' + str(root))
            for path in found:
                verdict = load(path)
                expected_status = 'passed_expected_failure' if suite in {'same-group', 'fault'} else 'passed'
                require(verdict['status'] == expected_status, 'Failed mapped independent verdict: ' + str(path))
                if expected_status == 'passed_expected_failure':
                    require(verdict['sameGroup'] == (suite == 'same-group') and verdict['failedInvocation']['worker']
                            and verdict['failedInvocation']['state'] == 'JOINED'
                            and verdict['failedInvocation']['failureClass'] == {
                                'ENTITY': 'net.minecraft.ReportedException', 'CONNECTION': 'java.lang.RuntimeException'}[verdict['phase']],
                            'Missing original injected-failure/join contract: ' + str(path))
                if suite == 'functional' and path.name != 'phase-order.json':
                    require(verdict['installerSha256'] == manifest['installerSha256'], 'Functional artifact mismatch')
                    require(verdict['schema'] in schema_counts, 'Unexpected functional schema')
                    schema_counts[verdict['schema']] += verdict['scenarioCount']
                verdicts.append(record(path))
    require(schema_counts == {3: 144, 4: 180}, 'Original schema 3/4 cases incomplete')
    pairs = {(r['kind'], r['mode']) for r in evidence['p3']}
    require(len(evidence['p3']) == 28 and pairs == {(k, m) for k in KINDS for m in MODES}, 'P3 four-mode matrix incomplete')
    gameplay = []
    for row in evidence['p3']:
        kind, mode = row['kind'], row['mode']
        key = kind + '-' + mode
        matrix = REPO / row['matrix']
        root = matrix / ('server-' + key)
        for stage in ['install', 'run', 'verify'] + (['controls'] if kind != 'damage-foreign' and mode == 'on-fixed' else []):
            path = matrix / (stage + '-' + key)
            command(path)
            accepted_commands.append(path / 'result.json')
            if stage == 'controls':
                proof_controls(path, 'p3-' + kind)
        verdict = root / KINDS[kind] / 'independent-verdict.json'
        result = load(verdict)
        require(result['status'] == 'passed' and result['version'] == version
                and result['requested'] == mode.startswith('on') and result['persistent'] == mode.endswith('persistent'),
                'Wrong P3 result/mode/product: ' + key)
        gameplay.append(dict(kind=kind, mode=mode, evidence=record(verdict), result=result))
        verdicts.append(record(verdict))
        roots.add(root)
    require(set(evidence['supplementalControls']) == {'network-overflow', 'socket', 'transition', 'timer', 'lifecycle', 'phase'},
            'Missing supplemental P2 proof controls')
    for category, name in evidence['supplementalControls'].items():
        path = REPO / name
        proof_controls(path, 'p2-' + category)
        accepted_commands.append(path / 'result.json')
    require(len(roots) == 88, 'Unexpected accepted isolated installation count')
    seed = REPO / 'tmp/player-region-tick-p3-01/server-on-fixed-01/libraries'
    seed_hashes = load(REPO / 'tmp/player-region-tick-p3-01/seed-library-hashes.json')
    for name, digest in seed_hashes.items():
        require(sha(seed / name) == digest, 'Seed dependency changed: ' + name)
    private_inodes, isolation = set(), []
    for root in sorted(roots):
        installation = load(root / 'installation.json')
        require(installation['version'] == version and installation['installerSha256'] == manifest['installerSha256']
                and installation['installerInputsPrivate'], 'Wrong/unsafe installation: ' + str(root))
        for kind, digest in manifest['installedJars'].items():
            jar = root / f'libraries/net/neoforged/neoforge/{version}/neoforge-{version}-{kind}.jar'
            require(sha(jar) == digest, 'Actual installed core changed: ' + str(jar))
        checked = 0
        for jar in (root / 'libraries').rglob('*.jar'):
            relative = str(jar.relative_to(root / 'libraries'))
            if not relative.startswith(('net/minecraft/', 'net/neoforged/neoforge/')):
                continue
            identity = (jar.stat().st_dev, jar.stat().st_ino)
            require(identity not in private_inodes and (not (seed / relative).exists()
                    or identity != ((seed / relative).stat().st_dev, (seed / relative).stat().st_ino)), 'Shared mutable core/Minecraft inode')
            private_inodes.add(identity)
            checked += 1
        isolation.append(dict(server=str(root.relative_to(REPO)), privateCoreMinecraftJars=checked,
                              postSuccessSharedDependencies=installation.get('linkedLibraryDependencies', 0)))
    protected = ['region-threading/PLAYER-WORKER-EXECUTION-AUDIT.md',
                 'region-threading/PLAYER-REGION-TICK-DESIGN.zh.md',
                 'tests/src/junit/java/net/neoforged/neoforge/unittest/RegionThreadingDeclarationsTest.java',
                 'tools/player-region-tick/verify_interactions.py', 'tools/player-region-tick/verify_resources.py',
                 'src/main/java/net/neoforged/neoforge/common/ForgiaConfig.java',
                 'src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingConfig.java']
    for name in protected:
        original = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=REPO)
        require(original == (REPO / name).read_bytes(), 'Protected source or historical gate changed: ' + name)
    subprocess.run(['git', 'diff', '--check'], cwd=REPO, check=True)
    processes = subprocess.check_output(['ps', '-eo', 'pid,args'], text=True)
    active_java = [line for line in processes.splitlines() if 'bin/java ' in line
                   and 'nogui' in line and '/player-region-tick-p3-' in line]
    require(not active_java, 'Owned P3 servers still alive: ' + str(active_java))
    active_clients = [line for line in processes.splitlines() if 'node ' in line
                      and '/player-region-tick-p3-' in line and any(name in line for name in ('clients.cjs', 'network_client.cjs'))]
    require(not active_clients, 'Owned P3 TCP clients still alive: ' + str(active_clients))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    snapshot = output / 'source'
    sources = set(subprocess.check_output(['git', 'diff', '--name-only'], cwd=REPO, text=True).splitlines())
    sources.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=REPO, text=True).splitlines())
    source_index = []
    for name in sorted(sources):
        source = REPO / name
        require(source.is_file(), 'Expected retained changed source: ' + name)
        target = snapshot / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        source_index.append(dict(source=name, snapshot=str(target.relative_to(REPO)), sha256=sha(source)))
    inputs = set(accepted_commands)
    inputs.update(path for path in product.iterdir() if path.is_file() and path.suffix in {'.json', '.py'})
    inputs.add(installer)
    inputs.add(manifest_path)
    for path in accepted_commands:
        inputs.update(p for p in path.parent.rglob('*') if p.is_file())
    for root in roots:
        for path in root.rglob('*'):
            if path.is_file() and not set(path.relative_to(root).parts).intersection({'libraries', '.cache'}):
                inputs.add(path)
    for label in ['build-final-01', 'regression-01', 'gametest-command', 'gametest-server/tests',
                  'artifact-tool-controls', 'ndjson-tool-controls', 'isolation-tool-controls', 'static-final-command', 'static-closeout-command']:
        inputs.update(p for p in (product / label).rglob('*') if p.is_file())
    history = []
    for base in sorted((REPO / 'tmp').glob('player-region-tick-p3-*')):
        for path in sorted(base.rglob('command.json')):
            result_path = path.with_name('result.json')
            result = load(result_path) if result_path.exists() else None
            own_wrapper = result is None and load(path).get('pid') == os.getppid()
            history.append(dict(command=record(path), result=record(result_path) if result else None,
                                exit=result.get('exit') if result else None,
                                acceptance=('current_audit_wrapper_in_progress_read_its_final_result_separately' if own_wrapper
                                            else 'unknown_exit_not_accepted' if result is None else 'preserved_command_exit')))
    failures = [row for row in history if row['exit'] != 0
                and row['acceptance'] != 'current_audit_wrapper_in_progress_read_its_final_result_separately']
    indexes = {'inputs': [record(p) for p in sorted(inputs)], 'sources': source_index,
               'isolation': isolation, 'acceptedCommands': [record(p) for p in accepted_commands],
               'commandHistory': history, 'preservedNonzeroOrInterruptedCommands': failures, 'proofControls': controls}
    for name, rows in indexes.items():
        (output / (name + '.json')).write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
    receipt = dict(schema=1, status='P3_complete_design_section_7_bounded_category_protocols',
                   generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), version=version,
                   p3Status='complete_bounded_protocols', p4Status='not_started', p5Status='not_started',
                   installer=record(installer), manifest=record(manifest_path), evidenceMap=record(args.evidence_map.resolve()),
                   junit=junit, selectedGameTestsPassed=3, persistedPatchProof=record(product / 'patch-proof-01.json'),
                   schema3Scenarios=schema_counts[3], schema4Scenarios=schema_counts[4],
                   proofNegativeControls=sum(row['negativeControls'] for row in controls),
                   independentVerdicts=verdicts, gameplay=gameplay, p2Suites=evidence['p2'],
                   acceptedIsolatedInstallations=len(roots), seedJarHashesUnchanged=len(seed_hashes),
                   preservedNonzeroOrInterruptedCommands=len(failures),
                   indexes={name: record(output / (name + '.json')) for name in indexes},
                   supportedScope='Final/single XP, plain material inventory/pickup, shared single chest full menu ticks, untracked scoreboard, real backpack map, one nonfatal sole-player starvation hit',
                   limitations=['General PvP/entity/block/open/click packets retain original server fences; P4 routing not started',
                                'Unknown extensions whole-call serial before freeze; oracle never admitted to worker',
                                'P2 stationary/inert loaded terrain, empty equipment/effects, bounded low XP and closed advancement/criterion prerequisites remain; no general fresh-player admission claim',
                                'Equipment/effects, arbitrary items/containers, tracked scores, selected terrain maps, general damage remain whole-call serial',
                                'Installed new worker positives use the positive-X boundary and diamond material; original negative-coordinate/corner gameplay compatibility is separately covered by serial-oracle schema3/4',
                                'Finite original TCP/JDI tests and native protocol tests; no P5 performance/long-soak or graphical GUI pause certificate',
                                'No category-specific installed post-pickup fault injection; committed callback failure/stop/join/idempotence tested through original native kernel'],
                   baselineHead=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
                   subAgentUsed=False, commitPushDeploymentPerformed=False, existingServerOperations=False)
    (output / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    result = dict(status='passed', receipt=record(output / 'receipt.json'),
                  independentVerdicts=len(verdicts), acceptedCommands=len(accepted_commands), closedInputs=len(inputs),
                  installations=len(roots), productionSources=len(manifest['review']['sources']), ownedServersAlive=0)
    (output / 'final-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['product-root', 'evidence-map', 'output']:
        p.add_argument('--' + name, type=Path, required=True)
    audit(p.parse_args())


if __name__ == '__main__':
    main()
