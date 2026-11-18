#!/usr/bin/env python3
"""Sequential real-player P5 scale cells; private worlds and all original exits are preserved."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

TOOLS = Path(__file__).resolve().parent
JAVA_HOME = Path('/home/yc/.local/opt/jdk-21.0.13+9')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'installer', 'libraries', 'eula']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--warmup-seconds', type=float, default=10)
    parser.add_argument('--measure-seconds', type=float, default=30)
    parser.add_argument('--broadcast-advancement-fixture', action='store_true',
                        help='Replay the historical noisy setup for diagnosis only')
    parser.add_argument('--mode', choices=['on-fixed','off-fixed','on-persistent','off-persistent'])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    commands = []
    seed = {str(path.relative_to(args.libraries)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in args.libraries.rglob('*.jar')}
    (args.output / 'seed-jar-hashes.json').write_text(json.dumps(seed, indent=2)+'\n')

    def run(label, argv):
        code = subprocess.call([sys.executable, str(TOOLS / 'run_command.py'), '--output', str(args.output / label),
                                '--java-home', str(JAVA_HOME), '--', *map(str, argv)])
        commands.append(dict(label=label, exit=code))
        (args.output / 'matrix-progress.json').write_text(json.dumps(commands, indent=2)+'\n')
        if code:
            raise RuntimeError('Preserved failed P5 scale command ' + label)

    try:
        classes = args.output / 'jmx-classes'
        classes.mkdir()
        run('compile-monitor', [JAVA_HOME/'bin/javac', '--add-modules', 'jdk.attach', '-d', classes, TOOLS/'jmx/PlayerJvmSampler.java'])
        # Single server at a time. Pool order is reversed for the second pair; one pass remains
        # exploratory, rather than pretending this order is a randomized repeated experiment.
        modes = [args.mode] if args.mode else ['off-fixed', 'on-fixed', 'on-persistent', 'off-persistent']
        for mode in modes:
            server = args.output / ('server-' + mode)
            install = [sys.executable, TOOLS/'install.py', '--output', server, '--installer', args.installer,
                       '--libraries', args.libraries, '--eula', args.eula, '--java', JAVA_HOME/'bin/java',
                       '--version', args.version, '--without-oracle', '--link-library-dependencies']
            if mode.startswith('on'):
                install += ['--player-planning']
            if mode.endswith('persistent'):
                install += ['--persistent']
            run('install-'+mode, install)
            fixture = [] if args.broadcast_advancement_fixture else ['--quiet-advancement-fixture']
            run('run-'+mode, [sys.executable, TOOLS/'run_player_scale.py', '--server', server, '--jmx-classes', classes,
                             '--warmup-seconds', args.warmup_seconds, '--measure-seconds', args.measure_seconds, *fixture])
            run('verify-'+mode, [sys.executable, TOOLS/'verify_player_scale.py', '--server', server, '--installer', args.installer])
        after = {str(path.relative_to(args.libraries)): hashlib.sha256(path.read_bytes()).hexdigest()
                 for path in args.libraries.rglob('*.jar')}
        assert seed == after, 'Read-only seed library bytes changed'
    except BaseException as error:
        (args.output/'matrix-result.json').write_text(json.dumps(dict(status='failed', error=repr(error), commands=commands), indent=2)+'\n')
        raise
    (args.output/'matrix-result.json').write_text(json.dumps(dict(status='passed', stage='P5_scale_preliminary_only',
                                                               seedJarHashesUnchanged=len(seed), commands=commands), indent=2)+'\n')


if __name__ == '__main__':
    main()
