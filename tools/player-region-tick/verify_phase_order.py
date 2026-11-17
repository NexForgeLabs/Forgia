#!/usr/bin/env python3
"""Full original call/event/Level/BE/connection/PlayerList order in retained oracle functional traces."""
import argparse
from collections import defaultdict
import json
from pathlib import Path


def require(value,message):
    if not value:raise AssertionError(message)

def verify(root, rows=None):
    rows=rows if rows is not None else [json.loads(s) for s in (root/'player-oracle.ndjson').read_text().splitlines()]
    require(rows[0]['kind']=='TRACE_START' and rows[-1]['kind']=='TRACE_STOP','missing complete trace boundaries')
    require([r['seq'] for r in rows]==list(range(1,len(rows)+1)),'truncated/duplicate trace')
    require(all(r['serverThread'] for r in rows if r['kind'] in ['ENTITY_START','CONNECTION_START','DOTICK_START']), 'oracle must keep original complete calls serial')
    frames=defaultdict(list)
    for r in rows:frames[r['tick']].append(r)
    entry=['CONNECTION_START','LISTENER_START','DOTICK_START','PLAYER_PRE','PLAYER_POST','DOTICK_RETURN','LISTENER_RETURN','CONNECTION_RETURN']
    counts={'completeServerTicks':0,'completePlayerConnections':0,'completeWorldCalls':0,'transitionPartialConnections':0,'stableTwoPlayerTicks':0}
    for tick,frame in frames.items():
        kinds=[r['kind'] for r in frame]
        # Start/stop commands can enable/disable observation inside an existing original server frame.
        if kinds.count('SERVER_PRE')!=1 or kinds.count('SERVER_POST')!=1:continue
        counts['completeServerTicks']+=1
        def only(kind):
            found=[r for r in frame if r['kind']==kind];require(len(found)==1,f'tick {tick}: expected one {kind}');return found[0]['seq']
        con_start,con_end=only('CONNECTIONS_START'),only('CONNECTIONS_RETURN')
        list_start,list_end=only('PLAYERLIST_START'),only('PLAYERLIST_RETURN')
        require(only('SERVER_PRE')<con_start<con_end<list_start<list_end<only('SERVER_POST'),f'tick {tick}: connection/PlayerList phase fence changed')
        levels=defaultdict(list)
        for r in frame:
            if r['kind'] in ['LEVEL_PRE','LEVEL_POST','BE_START','BE_RETURN']:levels[r['detail']].append(r)
        require(len(levels)==3,f'tick {tick}: missing original dimension phase')
        prior=only('SERVER_PRE')
        for dim,events in sorted(levels.items(),key=lambda item:item[1][0]['seq']):
            require([r['kind'] for r in events] in [['LEVEL_PRE','LEVEL_POST'],['LEVEL_PRE','BE_START','BE_RETURN','LEVEL_POST']],f'tick {tick}: Level/BE order changed {dim}')
            require(prior<events[0]['seq']<events[-1]['seq']<con_start,f'tick {tick}: Level escaped world/connection fence')
            prior=events[-1]['seq']
        players=defaultdict(list)
        for r in frame:
            if r.get('name') and r['kind'] in entry+['ENTITY_START','ENTITY_RETURN']:
                players[(r['uuid'],r['instance'])].append(r)
        stable=0
        for identity,events in players.items():
            connection=[r for r in events if r['kind'] in entry]
            starts=[r for r in connection if r['kind']=='CONNECTION_START'];ends=[r for r in connection if r['kind']=='CONNECTION_RETURN']
            require(len(starts)<=1 and len(ends)<=1,f'tick {tick}: duplicate original connection body {identity}')
            if starts and ends:
                require([r['kind'] for r in connection]==entry,f'tick {tick}: original listener/doTick/PlayerTick nesting changed {identity}')
                require(con_start<starts[0]['seq']<ends[0]['seq']<con_end,f'tick {tick}: player outside full original connection traversal')
                counts['completePlayerConnections']+=1
            elif connection:counts['transitionPartialConnections']+=1
            world=[r for r in events if r['kind'] in ['ENTITY_START','ENTITY_RETURN']]
            if world:
                require([r['kind'] for r in world]==['ENTITY_START','ENTITY_RETURN'],f'tick {tick}: duplicate/unbalanced original world call {identity}')
                levels_for_player=levels[world[0]['dimension']]
                be=next((r['seq'] for r in levels_for_player if r['kind']=='BE_START'),None)
                require(be is not None and levels_for_player[0]['seq']<world[0]['seq']<world[1]['seq']<be,f'tick {tick}: world call moved past BE/Level fence')
                counts['completeWorldCalls']+=1
                if starts and ends:stable+=1
        if stable==2:counts['stableTwoPlayerTicks']+=1
    require(counts['stableTwoPlayerTicks']>=10,'no substantive stable two-player phase/event coverage')
    retired={}
    for r in rows:
        if r['kind']=='CLONE':retired[int(r['detail'].split('=')[1])]=r['seq']
        elif r['kind']=='LOGOUT':retired[r['instance']]=r['seq']
        elif r['kind'] in ['ENTITY_START','CONNECTION_START'] and r.get('instance') in retired:
            require(r['seq']<retired[r['instance']],'old respawn/logout instance started another original call')
    return {'status':'passed','scope':'original complete calls and phase/event order with unknown oracle Mod; serial only',**counts}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    require(not args.output.exists(),'preserve existing phase-order verdict')
    try:r=verify(args.server.resolve())
    except BaseException as error:args.output.write_text(json.dumps({'status':'failed','error':repr(error)},indent=2)+'\n');raise
    args.output.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))

if __name__=='__main__':main()
