#!/usr/bin/env python3
"""Independent schema-4 verdict: raw packets + original events + actual owners + resource totals."""
import argparse
import base64
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
from verify_interactions import CORE_SHA, INSTALLED_JARS, require, load_lines
from reviewed_artifacts import verify_installation

TOOLS=Path(__file__).resolve().parent
GEOMETRIES={
    'positive_x':((47.0,8.5),(49.0,8.5)), 'negative_x':((-49.0,8.5),(-47.0,8.5)),
    'positive_corner':((47.2,47.2),(48.8,48.8)), 'negative_corner':((-48.8,-48.8),(-47.2,-47.2)),
}
ITEM_IDS={28:'minecraft:dirt',805:'minecraft:diamond',843:'minecraft:netherite_sword'}


def slot_totals(slots):
    result=Counter()
    for stack in slots:
        if stack['itemCount']:
            require(stack['itemId'] in ITEM_IDS,'unknown fixture item in client slot')
            result[ITEM_IDS[stack['itemId']]]+=stack['itemCount']
    return result



def xp(state):
    if state.get('health',1)<=0: return 0  # dead instance's stale UI fields are not spendable XP
    level=state['experienceLevel']
    require(level<=16,'fixture exceeded tested XP formula range')
    return level*level+6*level+round(state['experienceProgress']*(2*level+7))


def nbt_items(text):
    result=Counter()
    records=[]; depth=0; start=None; quoted=False; escaped=False
    for i,char in enumerate(text):
        if escaped: escaped=False; continue
        if char=='\\' and quoted: escaped=True; continue
        if char=='"': quoted=not quoted
        if quoted: continue
        if char=='{':
            if depth==0: start=i
            depth+=1
        elif char=='}':
            depth-=1
            if depth==0 and start is not None: records.append(text[start:i+1])
    for record in records:
        item=re.search(r'\bid: "([^"]+)"',record); count=re.search(r'\bcount: (\d+)',record)
        if item and count: result[item[1]]+=int(count[1])
    return result


def snapshots(trace,index):
    rows=[]
    for row in trace[index+1:]:
        if row['kind'] not in ['STATE','TARGET_STATE','RESOURCE_STATE','BLOCK_STATE']: break
        rows.append(row)
    players={row['name']:row for row in rows if row['kind']=='STATE'}
    ground=[row for row in rows if row['kind']=='RESOURCE_STATE']
    blocks=[json.loads(row['detail']) for row in rows if row['kind']=='BLOCK_STATE']
    require(len(players)==2 and len(blocks)==1,'missing final player/block snapshot')
    return players,ground,blocks[0]


def totals(snapshot):
    players,ground,block=snapshot; items=Counter(); experience=0
    for player in players.values():
        items.update(player['inventory'])
        if player['carriedCount']: items[player['carriedItem']]+=player['carriedCount']
        experience+=xp(player)
    items.update(block['container'])
    if block['block']=='minecraft:dirt': items['minecraft:dirt']+=1
    for row in ground:
        if row['type']=='minecraft:item': items[row['item']]+=row['count']
        elif row['type']=='minecraft:experience_orb': experience+=row['value']*row['count']
    return +items,experience


def block_updates(rows,position,state_id):
    for row in rows:
        data=row['data']
        if row['name']=='block_change' and data['location']==dict(zip(['x','y','z'],position)) and data['type']==state_id: return True
        if row['name']=='multi_block_change':
            section=data['chunkCoordinates']
            for record in data['records']:
                x=section['x']*16+((record>>8)&15); z=section['z']*16+((record>>4)&15); y=section['y']*16+(record&15)
                if [x,y,z]==position and record>>12==state_id: return True
    return False


def decode(root):
    result=subprocess.run(['node',str(TOOLS/'decode_resource_frames.cjs'),str(root/'resource-run/packets.ndjson')],capture_output=True,text=True,check=True)
    return [json.loads(line) for line in result.stdout.splitlines()]


def verify(root,trace=None,packets=None,raw=None,result=None,artifact_manifest=None):
    run=root/'resource-run'
    install=json.loads((root/'installation.json').read_text()); inputs=json.loads((run/'inputs.json').read_text())
    result=result if result is not None else json.loads((run/'result.json').read_text())
    trace=trace if trace is not None else load_lines(root/'player-oracle.ndjson')
    packets=packets if packets is not None else load_lines(run/'packets.ndjson')
    raw=raw if raw is not None else decode(root)
    require(result['status']=='passed' and result['serverExit']==result['clientsExit']==0,'runner/process did not finish')
    require(install==inputs['installation'],'input/installation mismatch')
    artifact=None
    if artifact_manifest is not None:
        artifact=verify_installation(root,run,install,artifact_manifest)
    else:
        require(install['installerSha256']==CORE_SHA and install['version']=='21.1.250.115-beta','wrong baseline installer')
        for suffix,digest in INSTALLED_JARS.items():
            path=root/f'libraries/net/neoforged/neoforge/21.1.250.115-beta/neoforge-21.1.250.115-beta-{suffix}.jar'
            require(hashlib.sha256(path.read_bytes()).hexdigest()==digest,'actual core mismatch')
    require(install['oracleSha256']==inputs['actualOracleSha256']==hashlib.sha256((root/'mods/forgia-player-oracle-1.0.jar').read_bytes()).hexdigest(),'oracle mismatch')
    for name,digest in inputs['sourceHashes'].items(): require(hashlib.sha256((run/name).read_bytes()).hexdigest()==digest,'archived source mismatch')
    require(trace[0]['kind']=='TRACE_START' and trace[-1]['kind']=='TRACE_STOP','trace truncated')
    for rows in [trace,packets]: require([r['seq'] for r in rows]==list(range(1,len(rows)+1)),'sequence truncated/duplicated')
    for row in packets:
        if row['direction'] in ['frame','frame_send']:
            frame=base64.b64decode(row['data']['base64'],validate=True)
            require(len(frame)==row['data']['bytes'] and hashlib.sha256(frame).hexdigest()==row['data']['sha256'],'raw frame corrupt')
    planning=json.loads((root/'player-planning.json').read_text())
    require(planning['persistentWorkers']==install['persistent'] and planning['requested']==install['playerPlanningRequested'],'actual pool/planning mismatch')
    config=(root/'config/forgia.toml').read_text()
    for key,value in [('enableRegionThreadingPlayerTicks',install['playerPlanningRequested']),('enableRegionThreadingPersistentWorkers',install['persistent'])]:
        require(re.search(r'^'+key+r'\s*=\s*'+str(value).lower()+r'\s*$',config,re.M),'configuration mismatch')
    snap=planning['snapshot']; require(all(snap[k]==0 for k in ['owners','pending','running']),'live planning state remains')
    require(all(p['worker']==p['failed']==0 for p in snap['phases'].values()),'baseline worker/failure mismatch')
    require(all(r['serverThread'] for r in trace if r['kind'] in ['ENTITY_START','CONNECTION_START','PACKET_START']),'unexpected baseline worker')
    expected={f'{kind}_{g}_{actor}' for g in GEOMETRIES for actor in ['A','B'] for kind in ['break','place','open','click']}
    expected|={f'{kind}_{mode}' for kind in ['break','place'] for mode in ['cancel','far']}
    expected|={f'shared_{kind}_{g}' for kind in ['open','race'] for g in ['positive_x','negative_corner']}
    expected|={'item_race','xp_race','death_drop','death_respawn','death_recover'}
    require({s['label'] for s in result['scenarios']}==expected and len(result['scenarios'])==len(expected),'matrix scenario missing or duplicate')
    marks={r['detail']:i for i,r in enumerate(trace) if r['kind']=='MARK'}
    client_marks={r['name']:r['seq'] for r in packets if r['direction']=='control'}
    commands=load_lines(run/'commands.ndjson')
    command_marks={r['command'].removeprefix('player_oracle mark '):i for i,r in enumerate(commands) if r['command'].startswith('player_oracle mark ')}
    verdicts=[]; observed_handoffs=[]
    for scenario in result['scenarios']:
        label=scenario['label']; kind=scenario['kind']; before=label+'_before'; after=label+'_after'
        if label.startswith(('break_','place_','open_','click_')) and label.rsplit('_',1)[-1] in ['A','B']:
            geometry=label.split('_',1)[1].rsplit('_',1)[0]
            a,b=GEOMETRIES[geometry]; actor_name='Oracle'+label[-1]
            origin,destination=(a,b) if actor_name=='OracleA' else (b,a)
            expected_pos=[math.floor(destination[0]),100,math.floor(destination[1])]
        elif label.startswith('shared_'):
            geometry=label.split('_',2)[2]; a,b=GEOMETRIES[geometry]; expected_pos=[math.floor(b[0]),100,math.floor(b[1])]
        else: expected_pos=[48,100,8] if label in ['item_race','xp_race'] else [49,100,8]
        require(scenario['blockPosition']==expected_pos,label+': target coordinate does not match matrix')
        require(all(before in m and after in m for m in [marks,client_marks,command_marks]),label+': boundaries missing')
        pre=snapshots(trace,marks[before]); post=snapshots(trace,marks[after]); window=trace[marks[before]+1:marks[after]]
        if label.startswith(('break_','place_','open_','click_')) and label.rsplit('_',1)[-1] in ['A','B']:
            require(math.dist([pre[0][actor_name]['x'],pre[0][actor_name]['z']],origin)<.01,label+': actor coordinate does not match matrix')
        for snapshot,reads in [(pre,scenario['beforeReads']),(post,scenario['afterReads'])]:
            players,ground,block=snapshot
            position=[block[k] for k in ['x','y','z']]
            require(position==scenario['blockPosition'],label+': wrong actual target block')
            coordinate=[math.floor(block['x']/48),math.floor(block['z']/48)]
            require([block['regionX'],block['regionZ']]==coordinate==[block['ownerRegionX'],block['ownerRegionZ']] and block['registeredOwner'],label+': false block owner')
            for state in list(players.values())+ground:
                coordinate=[math.floor(state['x']/48),math.floor(state['z']/48)]
                # Dead/removed old player is retained solely for death state; it grants no owner authority.
                if state.get('health',1)>0 and not state['removed']:
                    layout=[state.get('regionX'),state.get('regionZ')]; owner=[state.get('ownerRegionX'),state.get('ownerRegionZ')]
                    require(layout==coordinate and state.get('indexedRegions')==1,label+': false entity layout/index')
                    if owner!=coordinate:
                        # Packet-created death drops can cross the boundary before the next entity
                        # publication. Validate the ACTUAL old owner using independent original spawn
                        # bytes and a later snapshot of the SAME live entity; never forge current owner.
                        require(state['kind']=='RESOURCE_STATE' and label.startswith('death_') and max(abs(a-b) for a,b in zip(owner,coordinate))==1,label+': false entity owner')
                        spawn=[r for r in raw if r['direction']=='frame' and r['name'] in ['spawn_entity','spawn_entity_experience_orb']
                               and r['data']['entityId']==state['id'] and r['seq']>client_marks['death_drop_before']
                               and r['seq']<client_marks['death_drop_after']]
                        require(spawn and all([math.floor(r['data']['x']/48),math.floor(r['data']['z']/48)]==owner for r in spawn),label+': uncorroborated old ground owner')
                        if state['type']=='minecraft:item': require(all(r['data'].get('objectUUID')==state['uuid'] for r in spawn),label+': spawn identity mismatch')
                        future=[r for r in trace if r['kind']=='RESOURCE_STATE' and r.get('id')==state['id'] and r.get('uuid')==state['uuid'] and r['instance']==state['instance']
                                and r['seq']>state['seq'] and r['tick']>state['tick'] and not r['removed'] and r.get('indexedRegions')==1
                                and [r.get('ownerRegionX'),r.get('ownerRegionZ')]==[math.floor(r['x']/48),math.floor(r['z']/48)]]
                        require(future,label+': no actual later ownership publication')
                        observed_handoffs.append({'label':label,'entity':state['id'],'instance':state['instance'],'tick':state['tick'],
                            'actualOwner':owner,'coordinateRegion':coordinate,'spawnFrameSeq':spawn[0]['seq'],'laterMatchingSeq':future[0]['seq']})
            for name,player in players.items():
                require(nbt_items(reads[name]['inventory'])==Counter(player['inventory']),label+': independent inventory NBT mismatch')
                require(float(re.search(r'([-\d.]+)f\s*$',reads[name]['health'])[1])==player['health'],label+': independent health mismatch')
                level=int(re.search(r'has (\d+)',reads[name]['level'])[1]); points=int(re.search(r'has (\d+)',reads[name]['points'])[1])
                require(level==player['experienceLevel'] and points==round(player['experienceProgress']*(2*level+7)),label+': independent XP read mismatch')
            actual_type=block['block'].split(':')[1]
            require(all(('Test passed' in text)==(name==actual_type) for name,text in reads['blocks'].items()),label+': independent final block read mismatch')
            if 'container' in reads: require(nbt_items(reads['container'])==Counter(block['container']),label+': independent container NBT mismatch')
        require(totals(pre)==totals(post),label+': item/XP total not conserved')
        interval_commands=commands[command_marks[before]+1:command_marks[after]]
        require(all(r['command'].startswith(('data get ','experience query ','execute if block ')) for r in interval_commands),label+': command wrote tested result')
        def flatten(reads):
            values=[reads[name][key] for name in ['OracleA','OracleB'] for key in ['inventory','health','level','points']]
            values.extend(reads['blocks'][block] for block in ['air','dirt','chest'])
            if 'container' in reads: values.append(reads['container'])
            return values
        require([r['response'] for r in interval_commands]==flatten(scenario['beforeReads'])+flatten(scenario['afterReads']),label+': runner reads disagree with original command responses')
        active={}; spans=[]; tick_active={}; tick_spans=[]
        for row in window:
            if row['kind']=='PACKET_START': active[(row['id'],row['detail'])]=row['seq']
            elif row['kind']=='PACKET_RETURN':
                key=(row['id'],row['detail']); require(key in active,label+': original handler return without start')
                spans.append((row['id'],row['detail'].split(':')[0],active.pop(key),row['seq']))
            elif row['kind']=='DOTICK_START': tick_active[row['id']]=row['seq']
            elif row['kind']=='DOTICK_RETURN' and row['id'] in tick_active: tick_spans.append((row['id'],tick_active.pop(row['id']),row['seq']))
        require(not active,label+': original handler did not return')
        parent_kinds={'BLOCK_BREAK_EVENT':'action','DESTROY_RESULT':'action','RIGHT_BLOCK_EVENT':'useItemOn','BLOCK_PLACE_EVENT':'useItemOn',
                      'USE_BLOCK_RESULT':'useItemOn','MENU_OPEN_EVENT':'useItemOn','MENU_CLOSE_EVENT':'containerClose','QUICK_MOVE_RESULT':'containerClick',
                      'DEATH_DROPS_EVENT':'interact','DEATH_XP_EVENT':'interact'}
        for event in window:
            if event['kind'] in parent_kinds:
                require(any(kind==parent_kinds[event['kind']] and start<event['seq']<end for _,kind,start,end in spans),label+': event outside full original handler')
            if event['kind'] in ['ITEM_PICKUP_POST','XP_PICKUP_EVENT'] or (event['kind']=='XP_CHANGE_EVENT' and event['detail']!='amount=0'):
                require(any(entity==event['id'] and start<event['seq']<end for entity,start,end in tick_spans),label+': pickup outside complete original doTick')
        wire=[r for r in raw if client_marks[before]<r['seq']<client_marks[after]]
        outgoing=[r for r in packets if client_marks[before]<r['seq']<client_marks[after] and r['direction']=='send']
        for sent in outgoing:
            if sent['name'] in ['block_dig','block_place','window_click','use_entity','client_command','position_look']:
                expected_data=sent['data']
                if sent['name']=='client_command' and expected_data.get('actionId')==0: expected_data={'actionId':'perform_respawn'}
                require(any(r['direction']=='frame_send' and r['bot']==sent['bot'] and r['name']==sent['name'] and r['data']==expected_data for r in wire),label+': raw action mismatch')
        def events(name): return [r for r in window if r['kind']==name]
        def sent(name): return [r for r in wire if r['direction']=='frame_send' and r['name']==name]
        def received(name): return [r for r in wire if r['direction']=='frame' and r['name']==name]
        position=scenario['blockPosition']; actor=pre[0][scenario['actors'][0]]
        if kind.startswith(('break','place')) or kind in ['open','click','shared_open','shared_race']:
            near=not kind.endswith('_far')
            if kind not in ['click','shared_race']:
                distance=math.dist([actor['x'],actor['y']+1.62,actor['z']],[position[0]+.5,position[1]+.5,position[2]+.5])
                require((distance<4.5)==near,label+': actual block reach mismatch')
                if near:
                    require([actor['ownerRegionX'],actor['ownerRegionZ']]!=[pre[2]['ownerRegionX'],pre[2]['ownerRegionZ']],label+': target did not cross region')
            reject=kind.endswith(('_cancel','_far'))
            if kind.startswith('break'):
                require(len(sent('block_dig'))==2 and [r['data']['status'] for r in sent('block_dig')]==[0,2],label+': no standard digging sequence')
                require(all(r['data']['location']==dict(zip(['x','y','z'],position)) for r in sent('block_dig')),label+': wrong dig target')
                require(pre[2]['block']=='minecraft:dirt' and post[2]['block']==('minecraft:dirt' if reject else 'minecraft:air'),label+': actual break failed/rejected incorrectly')
                require(len(events('BLOCK_BREAK_EVENT'))==(0 if kind.endswith('_far') else 1),label+': break event count')
                require(len(events('DESTROY_RESULT'))==(0 if kind.endswith('_far') else 1),label+': synchronous destroy count')
                if events('DESTROY_RESULT'): require(events('DESTROY_RESULT')[0]['detail'].endswith('result='+str(not reject).lower()),label+': synchronous destroy result')
                if not reject: require(block_updates([r for r in wire if r['bot']==actor['name']],position,0),label+': ack without actual client air update')
            elif kind.startswith('place'):
                require(len(sent('block_place'))==1,label+': no standard placement')
                require(sent('block_place')[0]['data']['location']==dict(zip(['x','y','z'],[position[0],position[1]-1,position[2]])),label+': wrong support target')
                require(pre[2]['block']=='minecraft:air' and post[2]['block']==('minecraft:air' if reject else 'minecraft:dirt'),label+': placement final state')
                require(len(events('BLOCK_PLACE_EVENT'))==int(not reject),label+': placement event count')
                if kind.endswith('_cancel'): require(events('USE_BLOCK_RESULT') and events('USE_BLOCK_RESULT')[0]['detail'].endswith('result=FAIL'),label+': cancel return lost')
                if not reject:
                    require(block_updates([r for r in wire if r['bot']==actor['name']],position,10),label+': ack without actual client dirt update')
                    require(any(r['bot']==actor['name'] and r['data']['item'].get('itemId')==28 and r['data']['item']['itemCount']==1 for r in received('set_slot')),label+': missing actual inventory decrement feedback')
            else:
                require(pre[2]['block']==post[2]['block']=='minecraft:chest' and pre[2]['blockEntity']==post[2]['blockEntity']=='minecraft:chest'
                        and pre[2]['blockEntityInstance']==post[2]['blockEntityInstance']!=0,label+': lost/shared BE identity')
                count=len(scenario['actors'])
                require(all(r['data']['location']==dict(zip(['x','y','z'],position)) for r in sent('block_place')),label+': opened a different BE target')
                if kind in ['open','shared_open']:
                    require(len(sent('block_place'))==len(events('MENU_OPEN_EVENT'))==len(received('open_window'))==count,label+': no actual container open')
                    require(pre[2]['container']==post[2]['container']=={'minecraft:diamond':11},label+': open mutated inventory')
                    require(all(post[0][name]['menuId']>0 for name in scenario['actors']),label+': no actual active server menu')
                else:
                    require(len(sent('window_click'))==count and all(r['data']['mode']==1 and r['data']['slot']==0 and r['data']['windowId']==pre[0][r['bot']]['menuId'] for r in sent('window_click')),label+': no standard quick-move on actual menu')
                    require(pre[2]['container']=={'minecraft:diamond':11} and post[2]['container']=={},label+': chest final inventory')
                    moved=events('QUICK_MOVE_RESULT')
                    require(len(moved)==count and sum(int(re.search(r'count=(\d+)',r['detail'])[1]) for r in moved)==11,label+': duplicate/lost original quick-move result')
                    require(sum(p['inventory'].get('minecraft:diamond',0) for p in post[0].values())==11,label+': duplicated shared stack')
                    require(received('window_items') or received('set_slot'),label+': missing actual inventory feedback')
                for name in scenario['actors']:
                    inventories=[r['data'] for r in received('window_items') if r['bot']==name and r['data']['windowId']==post[0][name]['menuId']]
                    require(inventories,label+': no authoritative reopened inventory')
                    require(slot_totals(inventories[-1]['items'][-36:])==Counter(post[0][name]['inventory']) and slot_totals(inventories[-1]['items'][:-36])==Counter(post[2]['container']),label+': raw client/container inventory disagrees')
        elif kind in ['item_race','xp_race']:
            require(len(sent('position_look'))==2 and {r['bot'] for r in sent('position_look')}=={'OracleA','OracleB'},label+': no two real movement contenders')
            for name,expected_x in [('OracleA',47.7),('OracleB',48.3)]:
                state=post[0][name]
                require(abs(state['x']-expected_x)<.001 and abs(state['z']-8.5)<.001 and state['y']==100,label+': contender did not actually reach pickup area')
            wanted='minecraft:item' if kind=='item_race' else 'minecraft:experience_orb'
            initial=[r for r in pre[1] if r['type']==wanted]; require(len(initial)==1,label+': no unique original pickup target')
            target=initial[0]; require(not [r for r in post[1] if r['id']==target['id']],label+': target remains after pickup')
            require(target['count']==(5 if kind=='item_race' else 1) and (target.get('item')=='minecraft:diamond' if kind=='item_race' else target['value']==7),label+': wrong actual initial resource')
            original=events('ITEM_PICKUP_POST' if kind=='item_race' else 'XP_PICKUP_EVENT')
            require(len(original)==1 and f'target={target["id"]},' in original[0]['detail'],label+': duplicate/missing pickup event')
            collect=[r for r in received('collect') if r['bot']==original[0]['name'] and r['data']['collectedEntityId']==target['id']]
            require(len(collect)==1 and collect[0]['data']['collectorEntityId']==original[0]['id'],label+': missing actual client collect feedback')
            if kind=='item_race':
                require(sum(p['inventory'].get('minecraft:diamond',0) for p in post[0].values())==5 and collect[0]['data']['pickupItemCount']==5,label+': item pickup amount')
                require(any(r['bot']==original[0]['name'] and r['data']['item'].get('itemId')==805 and r['data']['item']['itemCount']==5 for r in received('set_slot')),label+': no actual item inventory feedback')
            else:
                changes=[r for r in events('XP_CHANGE_EVENT') if r['detail']!='amount=0']
                require(sum(xp(p) for p in post[0].values())==7 and len(changes)==1 and changes[0]['detail']=='amount=7',label+': duplicate XP change')
                require(any(r['bot']==original[0]['name'] and r['data']['level']==1 and r['data']['experienceBar']==0 for r in received('experience')),label+': missing actual seven-point client XP feedback')
        elif kind=='death':
            require(len(sent('use_entity'))==1 and sent('use_entity')[0]['data']['mouse']==1,label+': death was not real TCP combat')
            require(len(events('DAMAGE_POST'))==1 and f'source={pre[0]["OracleB"]["id"]},' in events('DAMAGE_POST')[0]['detail'],label+': no unique real PvP damage')
            require(pre[0]['OracleA']['health']>0 and post[0]['OracleA']['health']==0,label+': no actual death')
            require(len(events('DEATH_DROPS_EVENT'))==1 and json.loads(events('DEATH_DROPS_EVENT')[0]['detail'])=={'minecraft:diamond':5},label+': duplicate/missing death drop')
            require(len(events('DEATH_XP_EVENT'))==1 and events('DEATH_XP_EVENT')[0]['detail']=='amount=7',label+': death XP mismatch')
            require(any(r['bot']=='OracleA' and r['data']['health']==0 for r in received('update_health')),label+': no actual client death health')
        elif kind=='respawn':
            require(len(sent('client_command'))==len(events('RESPAWN'))==len(events('CLONE'))==1 and received('respawn'),label+': no unique real respawn')
            require(pre[0]['OracleA']['instance']!=post[0]['OracleA']['instance'] and post[0]['OracleA']['health']==20,label+': old instance reused')
        elif kind=='recover':
            require(sent('position_look') and not post[1] and post[0]['OracleB']['inventory'].get('minecraft:diamond',0)==5 and xp(post[0]['OracleB'])==7,label+': death resources not actually recovered')
        else: require(False,'unknown scenario kind')
        verdicts.append({'label':label,'kind':kind,'status':'passed','beforeItems':dict(totals(pre)[0]),'afterItems':dict(totals(post)[0]),
                         'beforeXp':totals(pre)[1],'afterXp':totals(post)[1],'target':pre[2]})
    return {'schema':4,'status':'passed','scope':artifact['scope'] if artifact else 'P0/P1 serial .115 resource baseline; worker=0','reviewedArtifact':artifact,'persistent':install['persistent'],
            'playerPlanningRequested':install['playerPlanningRequested'],'installerSha256':install['installerSha256'],'oracleSha256':install['oracleSha256'],
            'scenarioCount':len(verdicts),'traceRows':len(trace),'packetRows':len(packets),'observedGroundOwnerHandoffs':observed_handoffs,'scenarios':verdicts}


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--server',type=Path,required=True); parser.add_argument('--output',type=Path)
    parser.add_argument('--artifact-manifest',type=Path)
    args=parser.parse_args(); root=args.server.resolve(); output=args.output or root/'resource-run/independent-verdict.json'
    require(not output.exists(),'preserve prior verdict')
    verdict=verify(root,artifact_manifest=args.artifact_manifest); output.write_text(json.dumps(verdict,indent=2)+'\n'); print(json.dumps({k:v for k,v in verdict.items() if k!='scenarios'}))

if __name__=='__main__': main()
