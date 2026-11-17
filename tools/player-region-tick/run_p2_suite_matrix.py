#!/usr/bin/env python3
"""Recorded, fresh-install P2 matrices. Never reuse worlds or operate an existing server."""
import argparse,json,subprocess,sys
from pathlib import Path
TOOLS=Path(__file__).resolve().parent
JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ['output','installer','libraries','eula','artifact-manifest']:p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--version',required=True);p.add_argument('--oracle',type=Path);p.add_argument('--contract-mod',type=Path)
 p.add_argument('--network-mod',type=Path)
 p.add_argument('--suite',required=True,choices=['functional','smoke','network','same-group','fault','socket','transitions','contract','timers','lifecycle','respawn','phase-order','network-contract'])
 a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False);results=[]
 def run(label,argv):
  destination=a.output/label
  code=subprocess.call([sys.executable,str(TOOLS/'run_command.py'),'--output',str(destination),'--java-home',str(Path(JAVA).parent.parent),'--']+list(map(str,argv)))
  results.append({'label':label,'exit':code,'path':str(destination/'result.json')})
  (a.output/'matrix-progress.json').write_text(json.dumps({'suite':a.suite,'commands':results},indent=2)+'\n')
  if code:raise RuntimeError('Preserved failed matrix command '+label)
 def install(key,mode,oracle=False):
  root=a.output/('server-'+key)
  argv=[sys.executable,TOOLS/'install.py','--output',root,'--libraries',a.libraries,'--eula',a.eula,'--installer',a.installer,'--java',JAVA,'--version',a.version]
  if oracle:
   assert a.oracle;argv+=['--oracle',a.oracle,'--artifact-manifest',a.artifact_manifest]
  else:argv+=['--without-oracle']
  if mode.startswith('on'):argv+=['--player-planning']
  if mode.endswith('persistent'):argv+=['--persistent']
  run('install-'+key,argv);return root
 def command(tool,root,*args):return [sys.executable,TOOLS/(tool+'.py'),'--server',root,*args]
 manifest=['--artifact-manifest',a.artifact_manifest];installer=['--installer',a.installer]
 try:
  modes=['on-fixed','on-persistent'] if a.suite in ['network','same-group','fault','socket'] else ['on-fixed','on-persistent','off-fixed','off-persistent']
  for mode in modes:
   if a.suite=='functional':
    for kind in ['interactions','resources']:
     key=kind+'-'+mode;root=install(key,mode,True)
     run('run-'+key,command('run_'+kind,root));run('verify-'+key,command('verify_'+kind,root,*manifest));run('controls-'+key,command('test_'+('interaction' if kind=='interactions' else 'resource')+'_validator',root,*manifest))
     run('phase-'+key,command('verify_phase_order',root,'--output',root/'phase-order.json'));run('phase-controls-'+key,command('test_phase_order_validator',root))
   elif a.suite in ['network','same-group','fault','socket']:
    for phase in ['ENTITY','CONNECTION']:
     for pressure in ([False,True] if a.suite=='network' else [False]):
      key=mode+'-'+phase.lower()+('-overflow' if pressure else '');root=install(key,mode)
      if a.suite=='network':
       run('run-'+key,command('run_network_fence',root,'--phase',phase,*(['--overflow'] if pressure else [])));run('verify-'+key,command('verify_network_fence',root,*manifest))
      elif a.suite=='socket':
       run('run-'+key,command('run_worker_socket_close',root,*installer,'--phase',phase));run('verify-'+key,command('verify_worker_socket_close',root,*installer,*manifest))
      else:
       run('run-'+key,command('run_worker_fault',root,*installer,'--phase',phase,*(['--same-group'] if a.suite=='same-group' else [])));run('verify-'+key,command('verify_worker_fault',root,*installer,*manifest));run('controls-'+key,command('test_worker_fault_validator',root,*installer,*manifest))
   else:
    root=install(mode,mode)
    if a.suite=='smoke':
     run('run-'+mode,command('run_worker_smoke',root));run('verify-'+mode,command('verify_worker_smoke',root,*installer))
    elif a.suite in ['transitions','contract']:
     extra=['--contract-mod',a.contract_mod] if a.suite=='contract' else []
     if a.suite=='contract':assert a.contract_mod
     run('run-'+mode,command('run_p2_matrix',root,*extra));run('verify-'+mode,command('verify_p2_matrix',root,*manifest))
    elif a.suite=='timers':
     run('run-'+mode,command('run_p2_timers',root));run('verify-'+mode,command('verify_p2_timers',root,*manifest))
    elif a.suite=='lifecycle':
     run('run-'+mode,command('run_worker_lifecycle',root,'--dimensions'));run('verify-'+mode,command('verify_worker_lifecycle',root,*installer))
    elif a.suite=='network-contract':
     assert a.network_mod
     run('run-'+mode,command('run_network_contract',root,'--mod',a.network_mod));run('verify-'+mode,command('verify_network_contract',root,*manifest))
    elif a.suite=='phase-order':
     run('run-'+mode,command('run_worker_phase_order',root));run('verify-'+mode,command('verify_worker_phase_order',root,*manifest))
    elif a.suite=='respawn':
     run('run-'+mode,command('run_worker_respawn',root));run('verify-'+mode,command('verify_worker_respawn',root,*installer,*manifest))
 except BaseException as error:
  (a.output/'matrix-result.json').write_text(json.dumps({'status':'failed','error':repr(error),'commands':results},indent=2)+'\n');raise
 (a.output/'matrix-result.json').write_text(json.dumps({'status':'passed','suite':a.suite,'commands':results},indent=2)+'\n')

if __name__=='__main__':main()
