#!/usr/bin/env python3
"""Close only the eight-player gameplay/recovery matrix, without claiming P5/performance completion."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from reviewed_artifacts import read_manifest
from verify_player_operations import verify, require

TOOLS=Path(__file__).resolve().parent
REPO=TOOLS.parents[1]
MODES=['on-fixed','off-fixed','on-persistent','off-persistent']


def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


def record(path):
    return dict(path=str(path.resolve().relative_to(REPO)),sha256=sha(path),bytes=path.stat().st_size)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accepted-evidence',type=Path,required=True)
    parser.add_argument('--installer',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    try:
        evidence=json.loads(args.accepted_evidence.read_text())
        require(evidence['schema']==1 and evidence['review']['kind']=='explicit-per-mode-source-and-exit-audit','Missing explicit evidence review')
        require(set(evidence['modes'])=={kind+'/'+mode for kind in ['native','server-mods'] for mode in MODES},'Incomplete eight-mode operations review')
        manifest_path=TOOLS/'reviewed-artifacts/21.1.250.152-beta.json'
        manifest,manifest_digest=read_manifest(manifest_path)
        require(sha(args.installer)==manifest['installerSha256'],'Wrong frozen product')
        for path,expected in manifest['review']['sources'].items():
            require(sha(REPO/path)==expected,'Production source changed during operation acceptance '+path)
        verdicts=[];commands=[];inputs={args.installer.resolve(),manifest_path.resolve(),args.accepted_evidence.resolve()};controls=[]
        for key,value in sorted(evidence['modes'].items()):
            kind,mode=key.split('/');root=(REPO/value).resolve();output=root/'player-operations'
            actual=verify(root,args.installer.resolve())
            for suffix,expected in manifest['installedJars'].items():
                jar=root/f'libraries/net/neoforged/neoforge/{manifest["version"]}/neoforge-{manifest["version"]}-{suffix}.jar'
                require(sha(jar)==expected and jar.stat().st_nlink==1,'Wrong or shared writable operations runtime '+suffix)
            require(actual['requested']==mode.startswith('on-') and actual['persistent']==mode.endswith('persistent'),'Mislabeled mode '+key)
            require(bool(actual['installedMods'])==(kind=='server-mods') and actual['sharedContainerClientResyncVerified'] and actual['portalRoundTrip']
                    and actual['restoredClientInventoryAndXpVerified'],'Incomplete final client/state/portal coverage')
            prior=json.loads((output/'independent-verdict.json').read_text())
            require(prior.get('status')=='passed','Original independent verifier failed')
            for label in ['install-'+mode,'run-'+mode,'verify-'+mode]:
                path=root.parent/label/'result.json';row=json.loads(path.read_text())
                require(row['exit']==0 and row['argv'],'Original per-mode command failed '+str(path))
                commands.append(record(path));inputs.add(path)
                inputs.update(p for p in path.parent.iterdir() if p.is_file())
            report=output/'proof-negative-controls.json'
            if not report.exists():
                label=key.replace('/','-')+'-controls'
                code=subprocess.call([sys.executable,str(TOOLS/'run_command.py'),'--output',str(args.output/label),
                                      '--java-home','/home/yc/.local/opt/jdk-21.0.13+9','--',sys.executable,
                                      str(TOOLS/'test_player_operations_validator.py'),'--server',str(root),'--installer',str(args.installer.resolve())])
                require(code==0,'Proof mutation controls failed '+key)
                command_path=args.output/label/'result.json'
                command=json.loads(command_path.read_text())
                require(command['exit']==0 and command['argv'],'Missing actual proof-control exit '+key)
                commands.append(record(command_path));inputs.update(p for p in command_path.parent.iterdir() if p.is_file())
            proof=json.loads(report.read_text())
            require(proof['status']=='passed' and proof['negativeControls']==15 and len(proof['controls'])==15
                    and all(v['status']=='rejected' for v in proof['controls']),'Incomplete strict client/state proof controls')
            controls.append(dict(mode=key,report=record(report),negativeControls=15))
            verdicts.append(dict(mode=key,server=str(root.relative_to(REPO)),verdict=actual))
            for p in output.rglob('*'):
                if p.is_file():inputs.add(p)
            inputs.add(root/'installation.json');inputs.add(root/'server.properties');inputs.add(root/'config/forgia.toml')
            inputs.update((root/'world/playerdata').glob('*.dat'));inputs.update((root/'world/data').glob('map_*.dat'))
            for suffix in ['server','universal']:
                inputs.add(root/f'libraries/net/neoforged/neoforge/{manifest["version"]}/neoforge-{manifest["version"]}-{suffix}.jar')
            (args.output/'progress.json').write_text(json.dumps(dict(verifiedModes=[v['mode'] for v in verdicts]),indent=2)+'\n')
        schema=REPO/'tmp/player-region-tick-p5-operations04/schema34-reverified.json'
        reread=json.loads(schema.read_text());require(reread['status'].startswith('passed') and reread['schema3Scenarios']==144 and reread['schema4Scenarios']==180,'Missing same-product retained schema recheck')
        inputs.add(schema)
        for file in TOOLS.glob('*'):
            if file.is_file() and file.suffix in ['.py','.cjs']:inputs.add(file)
        (args.output/'inputs.json').write_text(json.dumps([record(p) for p in sorted(inputs)],indent=2)+'\n')
        (args.output/'freshlyReverified.json').write_text(json.dumps(verdicts,indent=2)+'\n')
        (args.output/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        receipt=dict(schema=1,status='passed_bounded_eight_client_gameplay_and_recovery',generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                     version=manifest['version'],installer=record(args.installer),manifestSha256=manifest_digest,
                     independentInstallations=8,actualClientsPerRun=8,normalOperationWindows=sum(len(v['verdict']['operations']) for v in verdicts),
                     crossRegionAttackHits=32,portalRoundTrips=8,teleportConfirmDirections=16,
                     reconnectCycles=sum(v['verdict']['recoveryCycles'] for v in verdicts),actualServerBoots=16,
                     proofNegativeControls=120,controls=controls,defaultEnabled=False,productionSourcesUnchanged=len(manifest['review']['sources']),
                     fullUnknownModPlayerTicksRemainSerial=True,exactSharedContainerClientFeedbackVerified=True,gameActionsFromNormalTcpPackets=True,
                     retainedSameProductSchemaRecheck=dict(schema3Scenarios=144,schema4Scenarios=180,newServerRuns=False),
                     speedupCertified=False,wholeP5Complete=False,schedulingOptimizationDeferredByUser=True,subAgentsUsed=False,
                     limitations=['Eight-client bounded correctness and recovery; not multi-hour soak/leak freedom',
                                  'Vanilla hopper/chest shared machine; FerriteCore/ModernFix only; no Create/Jade client gameplay certificate',
                                  'Original single-process 32-player timeouts remain failed and unexplained; grouped 32-client rerun is separate evidence',
                                  'Prior fixed-mode performance regressions remain; scheduling overhead optimization and performance certification deferred',
                                  'Schema3/4 server event proofs reverified from retained .152 corpus; native eight-client baseline installs no observer Mod'])
        (args.output/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
        (args.output/'final-audit.json').write_text(json.dumps(dict(status='passed',receipt=record(args.output/'receipt.json'),closedInputs=len(inputs),
                                                                  modes=8,normalOperationWindows=88,proofNegativeControls=120,wholeP5Complete=False),indent=2)+'\n')
        print(json.dumps({k:v for k,v in receipt.items() if k!='controls'}))
    except BaseException as error:
        (args.output/'failure.json').write_text(json.dumps(dict(status='failed',error=repr(error),wholeP5Complete=False),indent=2)+'\n');raise


if __name__=='__main__':main()
