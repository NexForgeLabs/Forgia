#!/usr/bin/env python3
"""Fresh first-increment P4 four-mode installations; preserve every actual failure/exit."""
import argparse,json,subprocess,sys
from pathlib import Path
TOOLS=Path(__file__).resolve().parent;JAVA='/home/yc/.local/opt/jdk-21.0.13+9'
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ['output','installer','libraries','eula']:p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--version',required=True);p.add_argument('--mode',choices=['on-fixed','on-persistent','off-fixed','off-persistent']);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False);rows=[]
 def run(label,argv):
  code=subprocess.call([sys.executable,TOOLS/'run_command.py','--output',a.output/label,'--java-home',JAVA,'--',*map(str,argv)]);rows.append(dict(label=label,exit=code));(a.output/'matrix-progress.json').write_text(json.dumps(rows,indent=2)+'\n')
  if code:raise RuntimeError('Preserved failed P4 command '+label)
 try:
  for mode in ([a.mode] if a.mode else ['on-fixed','on-persistent','off-fixed','off-persistent']):
   root=a.output/('server-'+mode)
   install=[sys.executable,TOOLS/'install.py','--output',root,'--installer',a.installer,'--libraries',a.libraries,'--eula',a.eula,'--java',JAVA+'/bin/java','--version',a.version,'--without-oracle','--link-library-dependencies']
   if mode.startswith('on'):install+=['--player-planning']
   if mode.endswith('persistent'):install+=['--persistent']
   run('install-'+mode,install);run('run-'+mode,[sys.executable,TOOLS/'run_owner_packet.py','--server',root]);run('verify-'+mode,[sys.executable,TOOLS/'verify_owner_packet.py','--server',root,'--installer',a.installer])
   if mode=='on-fixed':run('controls-'+mode,[sys.executable,TOOLS/'test_owner_packet_validator.py','--server',root,'--installer',a.installer])
 except BaseException as error:(a.output/'matrix-result.json').write_text(json.dumps(dict(status='failed',error=repr(error),commands=rows),indent=2)+'\n');raise
 (a.output/'matrix-result.json').write_text(json.dumps(dict(status='passed',scope='P4 first bounded owner packet increment; not full P4 completion',commands=rows),indent=2)+'\n')
if __name__=='__main__':main()
