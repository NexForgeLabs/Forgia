#!/usr/bin/env python3
"""Sequential eight-player operations matrix; fresh worlds, original failures retained."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

TOOLS=Path(__file__).resolve().parent
JAVA_HOME=Path('/home/yc/.local/opt/jdk-21.0.13+9')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['output','installer','libraries','eula']:
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--version',required=True)
    parser.add_argument('--cycles',type=int,default=3)
    parser.add_argument('--cycle-seconds',type=float,default=20)
    parser.add_argument('--mods-manifest',type=Path)
    parser.add_argument('--mode',choices=['on-fixed','on-persistent','off-fixed','off-persistent'])
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=False);commands=[]
    seed={str(p.relative_to(args.libraries)):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.libraries.rglob('*.jar')}
    (args.output/'seed-jar-hashes.json').write_text(json.dumps(seed,indent=2)+'\n')
    def run(label,argv):
        code=subprocess.call([sys.executable,str(TOOLS/'run_command.py'),'--output',str(args.output/label),
                              '--java-home',str(JAVA_HOME),'--',*map(str,argv)])
        commands.append(dict(label=label,exit=code));(args.output/'matrix-progress.json').write_text(json.dumps(commands,indent=2)+'\n')
        if code:raise RuntimeError('Preserved operations matrix failure '+label)
    try:
        for mode in [args.mode] if args.mode else ['on-persistent','off-persistent','off-fixed','on-fixed']:
            server=args.output/('server-'+mode)
            argv=[sys.executable,TOOLS/'install.py','--output',server,'--installer',args.installer,'--libraries',args.libraries,
                  '--eula',args.eula,'--java',JAVA_HOME/'bin/java','--version',args.version,'--without-oracle','--link-library-dependencies']
            if mode.startswith('on'):argv+=['--player-planning']
            if mode.endswith('persistent'):argv+=['--persistent']
            run('install-'+mode,argv)
            argv=[sys.executable,TOOLS/'run_player_operations.py','--server',server,'--cycles',args.cycles,'--cycle-seconds',args.cycle_seconds]
            if args.mods_manifest:argv+=['--mods-manifest',args.mods_manifest]
            run('run-'+mode,argv)
            run('verify-'+mode,[sys.executable,TOOLS/'verify_player_operations.py','--server',server,'--installer',args.installer])
        after={str(p.relative_to(args.libraries)):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.libraries.rglob('*.jar')}
        assert seed==after,'Read-only seed libraries changed'
    except BaseException as error:
        (args.output/'matrix-result.json').write_text(json.dumps(dict(status='failed',error=repr(error),commands=commands,wholeP5Complete=False),indent=2)+'\n');raise
    (args.output/'matrix-result.json').write_text(json.dumps(dict(status='passed_bounded_gameplay_and_recovery',commands=commands,
                                                               seedJarHashesUnchanged=len(seed),wholeP5Complete=False),indent=2)+'\n')


if __name__=='__main__':main()
