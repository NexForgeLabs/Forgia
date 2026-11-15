#!/usr/bin/env python3
"""Retain commands, exact source/input hashes and actual exits for isolated lifecycle runs."""
import argparse
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('gradle_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=False)
    source_files = sorted(set(command('git', 'ls-files').splitlines())
                          | set(command('git', 'ls-files', '--others', '--exclude-standard').splitlines()) | {
        str(p.relative_to(ROOT)) for p in (ROOT / 'tools/runtime-proof').rglob('*')
        if p.is_file() and '__pycache__' not in p.parts
    })
    inventory = {name: sha(ROOT / name) for name in source_files if (ROOT / name).is_file()}
    (output / 'source-sha256.json').write_text(json.dumps(inventory, indent=2) + '\n')
    for name in source_files:
        if name.startswith(('tools/runtime-proof/', 'tests/src/junit/')) and (ROOT / name).is_file():
            target = output / 'source' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
    (output / 'source.diff').write_text(command('git', 'diff', 'HEAD'))
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS'):
        env.pop(name, None)
    env['JAVA_HOME'] = args.java_home
    env['PATH'] = args.java_home + '/bin:' + env['PATH']
    gradle_args = args.gradle_args[1:] if args.gradle_args[:1] == ['--'] else args.gradle_args
    cmd = ['./gradlew', '--offline', '--no-configuration-cache', '--no-parallel', '--console=plain',
           '--max-workers=1', '-PforgiaVersion=21.1.250.60-beta', *gradle_args]
    record = {
        'gitHead': command('git', 'rev-parse', 'HEAD'), 'branch': command('git', 'branch', '--show-current'),
        'gitStatus': command('git', 'status', '--short'), 'command': cmd,
        'javaHome': args.java_home,
        'javaVersion': subprocess.run([args.java_home + '/bin/java', '-version'], capture_output=True, text=True).stderr,
        'started': datetime.datetime.now().astimezone().isoformat(), 'gradleExitCode': None,
    }
    (output / 'launch.json').write_text(json.dumps(record, indent=2) + '\n')
    with (output / 'output.log').open('w') as log:
        process = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        record['gradlePid'] = process.pid
        (output / 'launch.json').write_text(json.dumps(record, indent=2) + '\n')
        record['gradleExitCode'] = process.wait()
    record['finished'] = datetime.datetime.now().astimezone().isoformat()
    text = (output / 'output.log').read_text()
    matches = re.findall(r"finished with non-zero exit value (\d+)", text)
    if matches:
        record['serverExitCodeFromGradleFailure'] = int(matches[-1])
    for directory in ('tests/build/test-results/junitTest', 'tests/build/neodev'):
        for source in (ROOT / directory).glob('*.xml' if directory.endswith('junitTest') else '*Run*Args.txt'):
            target = output / ('junit' if source.suffix == '.xml' else 'arguments') / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    record['sourceChangesDuringRun'] = [name for name, digest in inventory.items()
                                      if not (ROOT / name).is_file() or sha(ROOT / name) != digest]
    (output / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    raise SystemExit(record['gradleExitCode'])


if __name__ == '__main__':
    main()
