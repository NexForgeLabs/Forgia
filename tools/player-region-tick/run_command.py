#!/usr/bin/env python3
"""Run once in background; persist argv, elapsed time and actual exit without shell pipelines."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    env = os.environ.copy()
    if args.java_home:
        env['JAVA_HOME'] = str(args.java_home.resolve())
        env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
    record = {'argv': command, 'cwd': str(Path.cwd()), 'javaHome': env.get('JAVA_HOME'), 'startNs': time.time_ns(), 'pid': os.getpid()}
    (args.output / 'command.json').write_text(json.dumps(record, indent=2) + '\n')
    start = time.monotonic()
    with (args.output / 'stdout.log').open('wb') as log:
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        (args.output / 'child.pid').write_text(str(process.pid) + '\n')
        code = process.wait()
    record.update(exit=code, elapsedSeconds=time.monotonic() - start, endNs=time.time_ns())
    (args.output / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    (args.output / 'exit').write_text(str(code) + '\n')
    raise SystemExit(code)


if __name__ == '__main__':
    main()
