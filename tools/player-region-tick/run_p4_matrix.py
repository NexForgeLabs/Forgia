#!/usr/bin/env python3
"""Fresh isolated original P4 owner protocols, with actual recorded command exits."""
import argparse
import json
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
JAVA = '/home/yc/.local/opt/jdk-21.0.13+9'
KINDS = {'routes': ('run_owner_routes', 'verify_owner_routes'),
         'async': ('run_owner_async', 'verify_owner_async'),
         'queue': ('run_packet_queue', 'verify_packet_queue')}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'installer', 'libraries', 'eula']:
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--version', required=True)
    p.add_argument('--kind', choices=KINDS, required=True)
    p.add_argument('--mode', choices=['on-fixed', 'on-persistent', 'off-fixed', 'off-persistent'])
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(label, argv):
        code = subprocess.call([sys.executable, str(TOOLS / 'run_command.py'), '--output', str(a.output / label), '--java-home', JAVA, '--', *map(str, argv)])
        commands.append(dict(label=label, exit=code))
        (a.output / 'matrix-progress.json').write_text(json.dumps(commands, indent=2) + '\n')
        if code:
            raise RuntimeError('Preserved failed P4 command ' + label)

    try:
        modes = [a.mode] if a.mode else (['on-fixed', 'on-persistent'] if a.kind == 'queue' else ['on-fixed', 'on-persistent', 'off-fixed', 'off-persistent'])
        for mode in modes:
            root = a.output / ('server-' + mode)
            install = [sys.executable, TOOLS / 'install.py', '--output', root, '--installer', a.installer, '--libraries', a.libraries, '--eula', a.eula, '--java', JAVA + '/bin/java', '--version', a.version, '--without-oracle', '--link-library-dependencies']
            if mode.startswith('on'):
                install += ['--player-planning']
            if mode.endswith('persistent'):
                install += ['--persistent']
            run('install-' + mode, install)
            runner, verifier = KINDS[a.kind]
            run('run-' + mode, [sys.executable, TOOLS / (runner + '.py'), '--server', root])
            run('verify-' + mode, [sys.executable, TOOLS / (verifier + '.py'), '--server', root, '--installer', a.installer])
            if mode == 'on-fixed':
                run('controls-' + mode, [sys.executable, TOOLS / 'test_owner_protocol_validator.py', '--kind', a.kind, '--server', root, '--installer', a.installer])
    except BaseException as error:
        (a.output / 'matrix-result.json').write_text(json.dumps(dict(status='failed', error=repr(error), commands=commands), indent=2) + '\n')
        raise
    (a.output / 'matrix-result.json').write_text(json.dumps(dict(status='passed', kind=a.kind, commands=commands), indent=2) + '\n')


if __name__ == '__main__':
    main()
