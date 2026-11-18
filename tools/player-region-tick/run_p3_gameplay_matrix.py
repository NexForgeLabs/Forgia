#!/usr/bin/env python3
"""Fresh-install complete P3 bounded gameplay proof on one explicit final product, both pools/off-on."""
import argparse,json,subprocess,sys
from pathlib import Path
TOOLS=Path(__file__).resolve().parent;JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ['output','installer','libraries','eula']:p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--version',required=True);p.add_argument('--link-library-dependencies',action='store_true');p.add_argument('--kind',choices=['xp','item','chest','score','map','damage','damage-foreign']);p.add_argument('--mode',choices=['on-fixed','on-persistent','off-fixed','off-persistent']);a=p.parse_args();a.output.mkdir(exist_ok=False,parents=True);results=[]
 def run(label,argv):
  code=subprocess.call([sys.executable,str(TOOLS/'run_command.py'),'--output',str(a.output/label),'--java-home',str(Path(JAVA).parent.parent),'--',*map(str,argv)])
  results.append(dict(label=label,exit=code));(a.output/'matrix-progress.json').write_text(json.dumps(results,indent=2)+'\n')
  if code:raise RuntimeError('Preserved failed P3 command '+label)
 kinds={'xp':('run_worker_xp_removal','verify_worker_xp_removal','test_worker_xp_removal_validator'),'item':('run_worker_item_pickup','verify_worker_item_pickup','test_worker_item_validator'),'chest':('run_worker_shared_chest','verify_worker_shared_chest','test_shared_chest_validator'),'score':('run_worker_scoreboard','verify_worker_scoreboard','test_scoreboard_validator'),'map':('run_worker_backpack_map','verify_worker_backpack_map','test_backpack_map_validator'),'damage':('run_worker_starvation_damage','verify_worker_starvation_damage','test_starvation_damage_validator'),'damage-foreign':('run_worker_starvation_damage','verify_worker_starvation_damage',None)}
 try:
  for mode in ([a.mode] if a.mode else ['on-fixed','on-persistent','off-fixed','off-persistent']):
   for kind in ([a.kind] if a.kind else kinds):
    key=kind+'-'+mode;root=a.output/('server-'+key);install=[sys.executable,TOOLS/'install.py','--output',root,'--libraries',a.libraries,'--eula',a.eula,'--installer',a.installer,'--java',JAVA,'--version',a.version,'--without-oracle']
    if a.link_library_dependencies:install+=['--link-library-dependencies']
    if mode.startswith('on'):install+=['--player-planning']
    if mode.endswith('persistent'):install+=['--persistent']
    run('install-'+key,install)
    runner,verifier,controls=kinds[kind]
    run('run-'+key,[sys.executable,TOOLS/(runner+'.py'),'--server',root,*(['--foreign-watcher'] if kind=='damage-foreign' else [])])
    run('verify-'+key,[sys.executable,TOOLS/(verifier+'.py'),'--server',root,'--installer',a.installer])
    if controls and mode=='on-fixed':run('controls-'+key,[sys.executable,TOOLS/(controls+'.py'),'--server',root,'--installer',a.installer])
 except BaseException as error:(a.output/'matrix-result.json').write_text(json.dumps(dict(status='failed',error=repr(error),commands=results),indent=2)+'\n');raise
 (a.output/'matrix-result.json').write_text(json.dumps(dict(status='passed',commands=results),indent=2)+'\n')
if __name__=='__main__':main()
