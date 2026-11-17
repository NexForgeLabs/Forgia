#!/usr/bin/env python3
"""Real complete ServerPlayer.tick failure via an original criterion callback; unknown Mods stay serial."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import time
import zipfile
from run_worker_lifecycle import Session

TOOLS=Path(__file__).resolve().parent


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server',type=Path,required=True);parser.add_argument('--installer',type=Path,required=True);parser.add_argument('--fault-mod',type=Path,required=True)
    args=parser.parse_args();root=args.server.resolve()
    install=json.loads((root/'installation.json').read_text())
    assert install['playerPlanningRequested'] and install['criterionProof'] and install['oracleSha256']
    assert not list(root.glob('player-oracle.ndjson')) and not (root/'world-fault-effect.json').exists() and not (root/'world-failure').exists()
    fault=root/'mods'/args.fault_mod.name
    assert not fault.exists(), 'Preserve the installed fault probe; use a new directory'
    shutil.copy2(args.fault_mod,fault)
    session=Session(root,'world-failure')
    result={'status':'running','scope':'original complete world-body failure with unknown extensions; serial fallback only',
            'installation':install,'faultModSha256':hashlib.sha256(fault.read_bytes()).hexdigest()}
    shutil.copy2(Path(__file__),session.output/Path(__file__).name)
    try:
        session.start()
        session.command('scoreboard objectives add forgia_criterion dummy')
        session.command('player_oracle start')
        session.request('connect',name='WorkerA',port=install['port']);time.sleep(1)
        before=session.snapshot('before-failure')
        assert all(p['worker']==p['failed']==0 for p in before['phases']['phases'].values())
        assert before['refusals']['ENTITY:UNKNOWN_MOD']>0 and before['refusals']['CONNECTION:UNKNOWN_MOD']>0
        session.command('player_world_fault WorkerA')
        session.command('advancement revoke WorkerA only forgia_player:tick')
        session.process.wait(timeout=45)
        effect=json.loads((root/'world-fault-effect.json').read_text())
        stopped=json.loads((root/'world-fault-stopped.json').read_text())
        # MinecraftServer's crash branch skips ServerStopping; ServerStopped still runs after the
        # original tick and shutdown unwind. Preserve that actual event distinction in the result.
        unwound=(json.loads((root/'world-fault-unwound.json').read_text()) if (root/'world-fault-unwound.json').exists()
                 else {'diagnostics':stopped['diagnostics'],'injected':stopped['injected'],
                       'observation':'ServerStopped after crash; original ServerStopping event was not emitted'})
        trace=[json.loads(s) for s in (root/'player-oracle.ndjson').read_text().splitlines()]
        assert effect['injected']==unwound['injected']==stopped['injected']==1 and effect['serverThread'] and effect['criterionAlreadyDone']
        original=[r for r in trace if r['kind']=='ENTITY_THROW']
        assert len(original)==1 and original[0]['tick']==effect['tick'] and original[0]['uuid']==effect['uuid']
        assert sum(r['kind']=='ENTITY_START' and r['tick']==effect['tick'] and r.get('uuid')==effect['uuid'] for r in trace)==1
        diagnostics=unwound['diagnostics'];phases=diagnostics['phases']
        assert diagnostics['activeBatches']==diagnostics['stateClaims']==0
        assert phases['pending']==phases['running']==0
        assert phases['phases']['ENTITY']['failed']==1 and phases['phases']['CONNECTION']['failed']==0
        assert all(p['worker']==0 for p in phases['phases'].values())
        failed=[r for r in phases['recent'] if r.get('failureClass')]
        assert len(failed)==1 and failed[0]['phase']=='ENTITY' and failed[0]['state']=='JOINED'
        assert stopped['phases']['owners']==stopped['phases']['pending']==stopped['phases']['running']==0
        assert stopped['network']['pending']==stopped['network']['readers']==stopped['network']['terminals']==0
        log=(session.output/'boot-1.server.log').read_text()
        assert 'player_world_fault_after_original_criterion_progress' in log and 'Ticking entity' in log and list((root/'crash-reports').glob('*.txt'))
        assert hashlib.sha256(args.installer.read_bytes()).hexdigest()==install['installerSha256']
        relative=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
        with zipfile.ZipFile(args.installer) as archive:assert (root/'libraries'/relative).read_bytes()==archive.read('maven/'+relative)
        result.update(status='passed_expected_failure',effect=effect,unwound=unwound,stopped=stopped,failedInvocation=failed[0])
    except BaseException as error:
        result.update(status='failed',error=repr(error));raise
    finally:
        session.close()
        result.update(serverExits=session.exits,clientsExit=session.bots.returncode)
        (session.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'status':result['status'],'scope':result['scope'],'serverExits':result['serverExits'],'effect':result['effect']}))


if __name__=='__main__':main()
