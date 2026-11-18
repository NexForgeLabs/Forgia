#!/usr/bin/env python3
"""Close a declared-payload API acceptance without changing retained installations or failed evidence."""
import argparse
import datetime
import hashlib
import importlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from reviewed_artifacts import read_manifest

REPO = Path(__file__).resolve().parents[2]
MODES = ['on-fixed', 'on-persistent', 'off-fixed', 'off-persistent']


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def load(path):
    return json.loads(path.read_text())


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def record(path):
    return dict(path=str(path.relative_to(REPO)), sha256=sha(path), bytes=path.stat().st_size)


def audit(args):
    root = args.product_root.resolve()
    evidence = load(args.evidence_map.resolve())
    manifest_path = REPO / evidence['manifest']
    manifest, manifest_digest = read_manifest(manifest_path)
    version = manifest['version']
    installer = REPO / evidence['installer']
    require(sha(installer) == manifest['installerSha256'], 'Installer differs from reviewed artifact')
    for name, digest in manifest['review']['sources'].items():
        require(sha(REPO / name) == sha(REPO / evidence['frozenSource'] / name) == digest, 'Production changed after freeze ' + name)
    accepted_commands = set()
    for item in evidence['checks']:
        path = REPO / item / 'result.json'
        result = load(path)
        require(result.get('exit') == 0 and result.get('argv'), 'Missing actual successful check ' + str(path))
        if './gradlew' in result['argv']:
            require('BUILD SUCCESSFUL' in path.with_name('stdout.log').read_text(), 'Missing actual Gradle success')
        accepted_commands.add(path)
    junit = load(REPO / evidence['junitSummary'])
    require(junit == dict(classes=22, tests=684, failures=0, errors=0, skipped=0), 'Wrong final JUnit summary')
    xmls = list((REPO / evidence['junitXml']).glob('TEST-*.xml'))
    require(len(xmls) == 22 and all(sum(int(ET.parse(path).getroot().get(key)) for path in xmls) == junit[key] for key in ['tests', 'failures', 'errors', 'skipped']), 'JUnit XML differs from summary')
    background = []
    for path in xmls:
        for stream in ['system-out', 'system-err']:
            text = ET.parse(path).getroot().findtext(stream) or ''
            background += [(path.name, line) for line in text.splitlines() if 'Exception in thread' in line]
    require(len(background) == 1 and background[0][0].endswith('RegionEntityTickPlanTest.xml')
            and 'java.lang.IllegalStateException: boom' in background[0][1], 'Unexpected uncaught background exception')
    game_xml = list((REPO / evidence['gameTestDirectory'] / 'tests').glob('*.junit.xml'))
    require(len(game_xml) == 1, 'Missing original GameTest XML')
    tests = ET.parse(game_xml[0]).getroot().findall('.//testcase')
    selected = {'physicalEntitySectionCrossing', 'blockEntityOwnerOperations', 'unsafeTickerForcesWholeRegionFallback'}
    require({test.get('name') for test in tests if test.find('passed') is not None} >= selected
            and all(test.find('failure') is None and test.find('error') is None for test in tests), 'Selected GameTests failed')
    installations, verdicts, controls = set(), [], []
    for group in evidence['matrices']:
        matrix = REPO / group['path']
        summary = load(matrix / 'matrix-result.json')
        require(summary['status'] == 'passed', 'Incomplete/failed accepted matrix ' + str(matrix))
        for command in summary['commands']:
            path = matrix / command['label'] / 'result.json'
            result = load(path)
            require(command['exit'] == result.get('exit') == 0 and result.get('argv'), 'Matrix child command not actually successful ' + str(path))
            accepted_commands.add(path)
        expected_modes = group.get('modes', ['on-fixed', 'on-persistent'] if group['kind'] == 'queue' else MODES)
        for mode in expected_modes:
            if group['kind'] == 'functional':
                cases = [('interactions', 'interaction-run', 'verify_interactions'), ('resources', 'resource-run', 'verify_resources')]
            else:
                cases = [(None, group['corpus'], group['verifier'])]
            for subtype, corpus, verifier_name in cases:
                server = matrix / ('server-' + (subtype + '-' if subtype else 'chest-' if group['kind'] == 'chest' else '') + mode)
                installation = load(server / 'installation.json')
                require(installation['version'] == version and installation['installerSha256'] == manifest['installerSha256'] and installation['installerInputsPrivate'], 'Wrong or unsafe actual installation')
                require(installation['playerPlanningRequested'] == mode.startswith('on') and installation['persistent'] == mode.endswith('persistent'), 'Wrong mode')
                for suffix, digest in manifest['installedJars'].items():
                    path = server / f'libraries/net/neoforged/neoforge/{version}/neoforge-{version}-{suffix}.jar'
                    require(sha(path) == digest and path.stat().st_nlink == 1, 'Core is wrong or shares a writable inode')
                # Freshly recompute each verdict from its original raw inputs. An old passed flag
                # or a runner assertion cannot substitute for this independent closeout.
                verifier = importlib.import_module(verifier_name).verify
                if subtype:
                    verdict = verifier(server, artifact_manifest=manifest_path)
                elif group['kind'] == 'network-contract':
                    verdict = verifier(server, manifest_path)
                else:
                    verdict = verifier(server, installer)
                require(verdict['status'] == 'passed', 'Fresh verdict failed')
                saved = load(server / corpus / 'independent-verdict.json')
                require(saved == verdict, 'Stored and independently recomputed verdict differ')
                verdicts.append(dict(installation=str(server.relative_to(REPO)), kind=subtype or group['kind'], mode=mode, verdict=verdict))
                installations.add(server)
                for path in (server / corpus).glob('*controls*.json'):
                    result = load(path)
                    require(result.get('status') == 'passed', 'Failed proof negative controls')
                    controls.append(record(path))
    require(len(installations) == 34, 'Missing expected complete same-product installation matrix')
    schema3 = sum(row['verdict'].get('scenarioCount', 0) for row in verdicts if row['kind'] == 'interactions')
    schema4 = sum(row['verdict'].get('scenarioCount', 0) for row in verdicts if row['kind'] == 'resources')
    require(schema3 == 144 and schema4 == 180, 'Historical schema3/4 corpus incomplete')
    require(sum(row['verdict']['workerBodies'] > 0 for row in verdicts if row['kind'] == 'payload') == 2, 'Both real worker pools are not covered')
    owned = []
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit() or int(proc.name) == os.getpid():
            continue
        try:
            argv = (proc / 'cmdline').read_bytes().split(b'\0')
        except OSError:
            continue
        if not argv:
            continue
        executable = Path(os.fsdecode(argv[0])).name
        if executable in ['java', 'node'] and any(str(root).encode() in arg for arg in argv):
            owned.append(int(proc.name))
    require(not owned, 'Owned isolated JVM/clients remain alive')
    failure_history = []
    for path in root.rglob('command.json'):
        if 'source' in path.parts or any(part.startswith('frozen-source') for part in path.parts):
            continue
        result = path.with_name('result.json')
        if str(args.output) in load(path).get('argv', []):
            continue  # This audit's active wrapper has not returned yet; the caller checks its exit.
        if not result.exists() or load(result).get('exit') != 0:
            failure_history.append(dict(command=record(path), result=record(result) if result.exists() else None, accepted=False))
    args.output.mkdir(parents=True, exist_ok=False)
    source_records = []
    changed = set(subprocess.check_output(['git', 'diff', '--name-only'], cwd=REPO, text=True).splitlines())
    changed.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=REPO, text=True).splitlines())
    for name in sorted(changed):
        path = REPO / name
        if path.is_file():
            target = args.output / 'source' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            source_records.append(record(path))
    inputs = set(accepted_commands) | {installer, manifest_path, args.evidence_map.resolve(), game_xml[0]} | set(xmls)
    for path in accepted_commands:
        inputs.update(file for file in path.parent.iterdir() if file.is_file())
        for argument in load(path)['argv']:
            candidate = REPO / argument
            if candidate.is_file() and candidate.suffix in ['.py', '.cjs', '.gradle']:
                inputs.add(candidate)
    for server in installations:
        for directory, dirs, files in os.walk(server):
            # Terrain is not an input to these offline verifiers; saved player NBT is. All original
            # logs, wire/JDI, oracle events, source snapshots and configuration remain closed inputs.
            dirs[:] = [name for name in dirs if name not in ['libraries', 'world', 'world_nether', 'world_the_end']]
            for name in files:
                inputs.add(Path(directory) / name)
        inputs.update((server / 'world/playerdata').glob('*.dat'))
        for suffix in ['server', 'universal']:
            inputs.add(server / f'libraries/net/neoforged/neoforge/{version}/neoforge-{version}-{suffix}.jar')
    control_summaries = []
    for path in sorted(accepted_commands):
        if not path.parent.name.startswith(('controls-', 'phase-controls-')):
            continue
        report_path = path.with_name('stdout.log')
        report = json.loads(report_path.read_text())
        require(report.get('status') == 'passed', 'Failed original proof controls report')
        count = report.get('negativeControls', report.get('controls'))
        require(isinstance(count, int) and count > 0, 'No proof controls counted')
        if isinstance(report.get('controls'), list):
            require(len(report['controls']) == count and all(row.get('status') == 'rejected' or row.get('rejected') is True for row in report['controls']), 'A corrupted proof was accepted')
        control_summaries.append(dict(report=record(report_path), negativeControls=count))
    require(sum(row['negativeControls'] for row in control_summaries) >= 18, 'Missing positive-pool proof controls')
    indexes = dict(inputs=[record(path) for path in sorted(inputs)], acceptedCommands=[record(path) for path in sorted(accepted_commands)], freshlyReverified=verdicts,
                   proofControlSummaries=control_summaries,
                   proofControls=controls, preservedFailures=failure_history, sources=source_records)
    for name, data in indexes.items():
        (args.output / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    receipt = dict(schema=1, status='complete_bounded_declared_server_play_payload_api', generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   version=version, installer=record(installer), manifest=record(manifest_path), manifestSha256=manifest_digest,
                   productionSources=len(manifest['review']['sources']), junit=junit, selectedGameTestsPassed=3,
                   independentInstallations=len(installations), acceptedCommands=len(accepted_commands), schema3Scenarios=schema3, schema4Scenarios=schema4,
                   closedInputs=len(inputs),
                   proofNegativeControls=sum(row['negativeControls'] for row in control_summaries),
                   defaultEnabled=False, fullUnknownModPlayerTicksRemainSerial=True, existingMainNetworkContractsPreserved=True,
                   startedHandlerReplays=0, physicalJoinBeforeCleanup=True, ownedJvmsAndClientsAlive=0,
                   p5Started=False, overallStatus='partial_P5_not_started', baselineHead='9b4859bb830f3c0beb0dc2f7cd44eee32699dbdd',
                   noSubAgents=True, existingServerOperations=False, commitPushPerformed=False,
                   limitations=['Developer declaration promises all transitive handler/listener/transformation safety; not a proof or sandbox for arbitrary mod code',
                                'Only exact declared server PLAY payload handler; full existing player-state/spatial prerequisites still apply and refusals use complete MAIN fallback',
                                'Each synchronous handler physically joins at its original MAIN queue position; no new packet concurrency or speedup claim',
                                'enqueueWork remains queued from worker and inline on MAIN fallback; inline fallback may precede an earlier queued continuation',
                                'No general custom bounds/global/cross-player/lifecycle permission; use original MAIN escape, do not wait on its future from worker',
                                'This supplementary acceptance reruns schema3/4, original P4 routes/async/queue, smoke, NETWORK contract and shared chest; not every historical P2/P3 fault/lifecycle suite',
                                'P4 original unexplained persistent foreign-starvation probe stall remains failed/excluded; this API does not claim to resolve it or certify long-soak/P5'])
    (args.output / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    final = dict(status='passed', receipt=record((args.output / 'receipt.json').resolve()), installations=len(installations),
                 acceptedCommands=len(accepted_commands), schema3Scenarios=schema3, schema4Scenarios=schema4, preservedFailedOrUnknownCommands=len(failure_history), p5Started=False)
    (args.output / 'final-audit.json').write_text(json.dumps(final, indent=2) + '\n')
    print(json.dumps(final))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product-root', type=Path, required=True)
    parser.add_argument('--evidence-map', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    audit(parser.parse_args())


if __name__ == '__main__':
    main()
