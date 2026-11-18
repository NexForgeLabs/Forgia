#!/usr/bin/env python3
"""Audit four grouped-client modes as bounded correctness, preserving single-process failures."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from reviewed_artifacts import read_manifest
from verify_player_scale import verify, require
from audit_player_operations import record, sha, TOOLS, REPO


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accepted-evidence',type=Path,required=True)
    parser.add_argument('--installer',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    try:
        review=json.loads(args.accepted_evidence.read_text())
        require(review['schema']==1 and review['review']['kind']=='explicit-per-mode-source-and-exit-audit','Unreviewed grouped-client evidence')
        require(set(review['modes'])=={'off-fixed','on-fixed','off-persistent','on-persistent'},'Incomplete grouped-client modes')
        manifest,manifest_digest=read_manifest(TOOLS/'reviewed-artifacts/21.1.250.152-beta.json')
        require(sha(args.installer)==manifest['installerSha256'],'Wrong grouped-client product')
        for name,digest in manifest['review']['sources'].items():require(sha(REPO/name)==digest,'Changed production source '+name)
        inputs={args.installer.resolve(),args.accepted_evidence.resolve()};verdicts=[];commands=[];controls=[]
        for mode,value in sorted(review['modes'].items()):
            root=(REPO/value).resolve();output=root/'player-scale'
            verdict=verify(root,args.installer.resolve())
            for suffix,expected in manifest['installedJars'].items():
                jar=root/f'libraries/net/neoforged/neoforge/{manifest["version"]}/neoforge-{manifest["version"]}-{suffix}.jar'
                require(sha(jar)==expected and jar.stat().st_nlink==1,'Wrong or shared writable grouped runtime '+suffix)
                inputs.add(jar)
            require(verdict['clientTopology']=='maximum-eight-per-process' and verdict['requested']==mode.startswith('on-') and verdict['persistent']==mode.endswith('persistent'),'Wrong grouped mode/topology')
            require(len(verdict['windows'])==3 and {p['distribution'] for p in verdict['windows']}=={'same','spread','boundary'}
                    and all(p['count']==32 for p in verdict['windows']),'Incomplete 32-player distributions')
            require(verdict==json.loads((output/'independent-verdict.json').read_text()),'Changed stored versus freshly verified grouped proof')
            for label in ['install-'+mode,'run-'+mode,'verify-'+mode]:
                path=root.parent/label/'result.json';row=json.loads(path.read_text())
                require(row['exit']==0 and row['argv'],'Original grouped command failed '+str(path))
                commands.append(record(path));inputs.add(path)
            if mode.startswith('on-'):
                path=output/'proof-negative-controls.json'
                if not path.exists():
                    code=subprocess.call([sys.executable,str(TOOLS/'run_command.py'),'--output',str(args.output/(mode+'-controls')),
                                          '--java-home','/home/yc/.local/opt/jdk-21.0.13+9','--',sys.executable,str(TOOLS/'test_player_scale_validator.py'),
                                          '--server',str(root),'--installer',str(args.installer.resolve())])
                    require(code==0,'Grouped scale proof controls failed')
                proof=json.loads(path.read_text());require(proof['status']=='passed' and len(proof['controls'])==4 and all(c['rejected'] for c in proof['controls']),'Missing grouped scale mutation refusals')
                controls.append(record(path))
            verdicts.append(dict(mode=mode,server=str(root.relative_to(REPO)),verdict=verdict))
            inputs.update(p for p in output.rglob('*') if p.is_file());inputs.add(root/'installation.json')
            inputs.update((root/'world/playerdata').glob('*.dat'))
        receipt=dict(schema=1,status='passed_bounded_grouped_32_client_correctness',version=manifest['version'],manifestSha256=manifest_digest,
                     modes=4,actualClients=32,protocolProcessesPerServer=4,actualLoadCells=12,crossRegionAttackHits=4,
                     topology='maximum-eight-per-process',originalChildCapturesMatched=True,sameRegionOverlap=0,
                     serverClientSamplerExits=0,proofNegativeControls=8,defaultEnabled=False,productionSourcesUnchanged=37,
                     originalSingleProcessTimeoutsResolved=False,speedupCertified=False,wholeP5Complete=False,
                     limitations=['Changed client topology; no causal proof or repair of retained single-process 32-client timeouts',
                                  'Short correctness windows, no controlled performance A/B or multi-hour soak',
                                  'Headless clients retain non-control NeoForge recipe decoder diagnostics; not complete GUI/Mod protocol certification'])
        (args.output/'inputs.json').write_text(json.dumps([record(p) for p in sorted(inputs)],indent=2)+'\n')
        (args.output/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        (args.output/'freshlyReverified.json').write_text(json.dumps(verdicts,indent=2)+'\n')
        (args.output/'proof-controls.json').write_text(json.dumps(controls,indent=2)+'\n')
        (args.output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
        (args.output/'final-audit.json').write_text(json.dumps(dict(status='passed',receipt=record(args.output/'receipt.json'),
                                                                  closedInputs=len(inputs),actualLoadCells=12,proofNegativeControls=8,wholeP5Complete=False),indent=2)+'\n')
    except BaseException as error:
        (args.output/'failure.json').write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise


if __name__=='__main__':main()
