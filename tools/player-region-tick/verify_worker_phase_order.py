#!/usr/bin/env python3
"""Independent NO-Mod original hook/worker/Level-BE-connection-PlayerList order proof."""
import argparse,hashlib,json
from pathlib import Path
from reviewed_artifacts import read_manifest

def require(value,message):
 if not value:raise AssertionError(message)

def verify(root,manifest,rows=None):
 out=root/'worker-phase-order';install=json.load(open(root/'installation.json'));r=json.load(open(out/'result.json'));m,_=read_manifest(manifest)
 require(r['status']=='passed' and r['serverExits']==[0] and r['clientsExit']==0,'original external phase observer did not finish')
 require(install['oracleSha256'] is None and not list((root/'mods').glob('*.jar')) and install['installerSha256']==m['installerSha256'],'phase observer did not use actual reviewed production core')
 for suffix,digest in m['installedJars'].items():require(hashlib.sha256((root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar').read_bytes()).hexdigest()==digest,'phase-order installed core changed')
 for name,digest in r['sources'].items():require(hashlib.sha256((out/'source-snapshot'/name).read_bytes()).hexdigest()==digest,'original phase observer/source snapshot changed')
 require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in r['actualLaunch']) and not any(x.startswith('-javaagent:') for x in r['actualLaunch']),'phase proof introduced runtime instrumentation')
 rows=rows if rows is not None else [json.loads(x) for x in (out/'jdi.ndjson').read_text().splitlines()]
 require([x['seq'] for x in rows]==list(range(1,len(rows)+1)) and rows[0]['event']=='armed' and rows[-1]['event']=='observed' and rows[-1]['completeTicks']==30,'truncated original hook observations')
 players=set(rows[0]['players']);require(len(players)==2,'not two actual protocol players')
 frames={}
 for row in rows[1:-1]:frames.setdefault(row['tick'],[]).append(row)
 require(len(frames)==30,'original event observer did not cover thirty complete server ticks')
 counts={};native={actor:{"ENTITY":0,"CONNECTION":0} for actor in players}
 for tick,events in frames.items():
  def exact(kind,actor=None):
   found=[e for e in events if e['event']==kind and (actor is None or e['uuid']==actor)]
   require(len(found)==1,'missing/duplicated original hook '+str((tick,kind,actor)))
   return found[0]
  start=exact('fireServerTickPre');end=exact('fireServerTickPost');connections=exact('CONNECTIONS_ENTER');playerlist=exact('PLAYERLIST_ENTER')
  require(events[0]==start and events[-1]==end and connections['seq']<playerlist['seq'],'original server/connection/playerlist order changed')
  levels={e['levelId'] for e in events if e['event']=='fireLevelTickPre'};require(len(levels)==3,'original dimension loop omitted a level')
  spans=[]
  for level in levels:
   parts={kind:[e for e in events if e['event']==kind and e['levelId']==level] for kind in ['fireLevelTickPre','BE_ENTER','fireLevelTickPost']}
   require(len(parts['fireLevelTickPre'])==len(parts['fireLevelTickPost'])==1 and len(parts['BE_ENTER'])<=1,'missing/duplicated original Level/BE phase')
   pre,post=parts['fireLevelTickPre'][0],parts['fireLevelTickPost'][0]
   actors=[e for e in events if e['event']=='fireEntityTickPre' and pre['seq']<e['seq']<post['seq']]
   if parts['BE_ENTER']:
    be=parts['BE_ENTER'][0]
    require(pre['seq']<be['seq']<post['seq']<connections['seq'],'original Level/BE fence crossed connection phase')
    spans.append((pre['seq'],be['seq'],post['seq']))
   else:
    # ServerLevel's original empty-world branch stops ticking entities/BE after 300 empty ticks.
    # It still receives Level Pre/Post. Never use that exception for a world containing a player.
    require(not actors and pre.get('emptyTimeBefore',-1)>=300,'BE omitted without original empty-Level eligibility evidence')
    require(pre['seq']<post['seq']<connections['seq'],'empty-Level fence crossed connection phase')
  for actor in players:
   ep,eq,pp,pq=[exact(k,actor) for k in ['fireEntityTickPre','fireEntityTickPost','firePlayerTickPre','firePlayerTickPost']]
   require(any(pre<ep['seq']<eq['seq']<be<post for pre,be,post in spans),'original world event hooks escaped their Level-before-BE position')
   require(connections['seq']<pp['seq']<pq['seq']<playerlist['seq'],'PlayerTick Pre/Post moved outside original connection phase')
   require(ep['thread']==eq['thread'] and pp['thread']==pq['thread'],'original pre/post pair crossed execution threads')
   for event in [ep,eq,pp,pq]:
    worker='Worker' in event['thread']
    require(event['thread']=='Server thread' or worker,'original hook ran on an unknown executor')
    if not install['playerPlanningRequested']:require(not worker,'off mode hook entered worker')
    if worker:require(any('RegionPlayerTickDispatch.runOriginal:' in x for x in event['stack']),'hook came from a side task instead of full original worker call')
   native[actor]['ENTITY']+=int('Worker' in ep['thread']);native[actor]['CONNECTION']+=int('Worker' in pp['thread'])
   for event in [pp,pq]:require(any('ServerPlayer.doTick:' in x for x in event['stack']) and any('Connection.tick:' in x for x in event['stack']) and any('Player.tick:' in x for x in event['stack']),'PlayerTick hook outside complete original doTick/Player.tick')
  for event in events:
   if not event['uuid']:require(event['thread']=='Server thread','global phase escaped original server thread')
   counts[event['event']]=counts.get(event['event'],0)+1
 if install['playerPlanningRequested']:require(all(count>=10 for phases in native.values() for count in phases.values()),'no substantive original hook coverage in both native phases/players')
 final=json.load(open(out/'final.json'));require(not final['players'] and final['phases']['owners']==final['phases']['running']==final['phases']['pending']==final['activeBatches']==final['stateClaims']==final['network']['pending']==0,'phase observer left live player/lease/queue state')
 return {'status':'passed','requested':install['playerPlanningRequested'],'persistent':install['persistent'],'completeServerTicks':30,'actualPlayerTickPre':counts['firePlayerTickPre'],'actualPlayerTickPost':counts['firePlayerTickPost'],'counts':counts,'nativeCallsPerPlayerPhase':native,'scope':'Read-only external breakpoints inside original hooks/complete calls; no Mod/agent/redefinition; debugger timing excluded from overlap and performance'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--artifact-manifest',type=Path,required=True);p.add_argument('--output',type=Path);a=p.parse_args();out=a.output or a.server/'worker-phase-order/independent-verdict.json';require(not out.exists(),'preserve prior phase-order verdict')
 try:r=verify(a.server.resolve(),a.artifact_manifest.resolve())
 except BaseException as error:out.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
 out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
