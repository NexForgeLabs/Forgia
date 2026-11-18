#!/usr/bin/env python3
"""Four fresh declared-payload installations, actual command exits and independent verdicts."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

TOOLS = Path(__file__).resolve().parent
JAVA = '/home/yc/.local/opt/jdk-21.0.13+9'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'installer', 'libraries', 'eula', 'mod']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--mode', choices=['on-fixed', 'on-persistent', 'off-fixed', 'off-persistent'])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(label, argv):
        code = subprocess.call([sys.executable, str(TOOLS / 'run_command.py'), '--output', str(args.output / label), '--java-home', JAVA, '--', *map(str, argv)])
        commands.append(dict(label=label, exit=code))
        (args.output / 'progress.json').write_text(json.dumps(commands, indent=2) + '\n')
        if code:
            raise RuntimeError('Preserved failed declared-payload command ' + label)

    try:
        for mode in [args.mode] if args.mode else ['on-fixed', 'on-persistent', 'off-fixed', 'off-persistent']:
            server = args.output / ('server-' + mode)
            install = [sys.executable, TOOLS / 'install.py', '--output', server, '--installer', args.installer, '--libraries', args.libraries,
                       '--eula', args.eula, '--java', JAVA + '/bin/java', '--version', args.version, '--without-oracle', '--link-library-dependencies']
            if mode.startswith('on'):
                install += ['--player-planning']
            if mode.endswith('persistent'):
                install += ['--persistent']
            run('install-' + mode, install)
            run('run-' + mode, [sys.executable, TOOLS / 'run_region_payload.py', '--server', server, '--mod', args.mod])
            run('verify-' + mode, [sys.executable, TOOLS / 'verify_region_payload.py', '--server', server, '--installer', args.installer])
            if mode.startswith('on'):
                run('controls-' + mode, [sys.executable, TOOLS / 'test_region_payload_validator.py', '--server', server, '--installer', args.installer])
    except BaseException as error:
        (args.output / 'matrix-result.json').write_text(json.dumps(dict(status='failed', error=repr(error), commands=commands), indent=2) + '\n')
        raise
    (args.output / 'matrix-result.json').write_text(json.dumps(dict(status='passed', commands=commands), indent=2) + '\n')


if __name__ == '__main__':
    main()
