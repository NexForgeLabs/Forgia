#!/usr/bin/env python3
"""Reject semantic call/event/phase reordering without deleting trace sequence continuity."""
import argparse,copy,json
from pathlib import Path
from verify_phase_order import verify


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args();root=a.server.resolve()
 rows=[json.loads(s) for s in (root/'player-oracle.ndjson').read_text().splitlines()];verify(root,rows)
 complete=set(r['tick'] for r in rows if r['kind']=='SERVER_PRE') & set(r['tick'] for r in rows if r['kind']=='SERVER_POST')
 control=[]
 def mutate(name,old,new):
  data=copy.deepcopy(rows)
  row=next(r for r in data if r['tick'] in complete and r['kind']==old)
  row['kind']=new
  try:verify(root,data)
  except AssertionError as error:control.append({'control':name,'status':'rejected','reason':str(error)})
  else:raise AssertionError('tampered phase order accepted '+name)
 mutate('missing_original_PlayerTick_Pre','PLAYER_PRE','UNRELATED_EVENT')
 mutate('missing_complete_BE_start','BE_START','UNRELATED_EVENT')
 mutate('missing_connection_join','CONNECTIONS_RETURN','UNRELATED_EVENT')
 mutate('duplicate_original_world_start','ENTITY_RETURN','ENTITY_START')
 mutate('missing_original_listener_return','LISTENER_RETURN','UNRELATED_EVENT')
 print(json.dumps({'status':'passed','negativeControls':len(control),'controls':control},indent=2))

if __name__=='__main__':main()
