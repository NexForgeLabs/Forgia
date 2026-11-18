#!/usr/bin/env python3
"""Independent actual-wire and original-state gate for bounded eight-player operations."""
import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from verify_player_scale import lines, require
from verify_resources import nbt_items
from reviewed_artifacts import read_manifest

TOOLS=Path(__file__).resolve().parent
NAMES=[f'Scale{i:02}' for i in range(8)]


def verify(root,installer,result_override=None,wire_override=None):
    output=root/'player-operations'
    r=result_override if result_override is not None else json.loads((output/'result.json').read_text())
    install=json.loads((root/'installation.json').read_text())
    require(r['status']=='passed' and r['serverExits']==[0,0] and r['clientsExit']==0,'Original operations runner/exit failed')
    require(r['installation']==install and install['oracleSha256'] is None,'Wrong operations installation')
    mods=r.get('installedMods',[])
    installed=list((root/'mods').glob('*.jar'))
    require({p.name for p in installed}=={Path(m['path']).name for m in mods},'Unreviewed operations Mod set')
    require(all(hashlib.sha256((root/'mods'/Path(m['path']).name).read_bytes()).hexdigest()==m['sha256'] for m in mods),'Changed operations Mod bytes')
    require(hashlib.sha256(installer.read_bytes()).hexdigest()==install['installerSha256'],'Wrong reviewed product')
    manifest,manifest_digest=read_manifest(TOOLS/'reviewed-artifacts'/(install['version']+'.json'))
    require(manifest['installerSha256']==install['installerSha256'],'Unreviewed installed product')
    for suffix,expected in manifest['installedJars'].items():
        jar=root/f'libraries/net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-{suffix}.jar'
        require(hashlib.sha256(jar.read_bytes()).hexdigest()==expected,'Actual reviewed runtime differs '+suffix)
    suffix=f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
    with zipfile.ZipFile(installer) as archive:
        require((root/'libraries'/suffix).read_bytes()==archive.read('maven/'+suffix),'Installed runtime differs from artifact')
    require(not any(v.startswith(('-agentlib:','-javaagent:')) for v in install['launch']),'Operations baseline uses no debugger/Java agent')
    for path,digest in r['sources'].items():
        require(hashlib.sha256((output/'source'/path).read_bytes()).hexdigest()==digest,'Changed operations source snapshot')
    raw=lines(output/'packets.ndjson');commands=lines(output/'commands.ndjson')
    require([row['seq'] for row in raw]==list(range(1,len(raw)+1)),'Missing original operation capture')
    require(not any(row['direction'] in ['error','load_error'] for row in raw),'Real client errors')
    for row in raw:
        if row['direction'] in ['frame','frame_send']:
            data=base64.b64decode(row['data']['base64'],validate=True)
            require(len(data)==row['data']['bytes'] and hashlib.sha256(data).hexdigest()==row['data']['sha256'],'Original operation frame corrupt')
    wire=wire_override if wire_override is not None else [json.loads(line) for line in subprocess.check_output(
        ['node',str(TOOLS/'decode_player_operations.cjs'),str(output/'packets.ndjson')],text=True).splitlines()]
    states=[json.loads(row['response']) for row in commands if row['command']=='neoforge regionthreading status players']
    marks={row['name']:row for row in raw if row['direction']=='control'}

    def state(row,empty=False):
        require(row in states,'Invented original server state')
        require(row['requested']==install['playerPlanningRequested'] and row['persistent']==install['persistent'],'Actual operations mode mismatch')
        require(row['activeBatches']==row['stateClaims']==row['phases']['pending']==row['phases']['running']==0 and not row['network']['frozen'],'Unjoined operations state')
        require(all(p['failed']==0 for p in row['phases']['phases'].values()) and row['packets']['failed']==0,'Failed original phase or packet')
        if empty:
            require(not row['players'] and row['phases']['owners']==row['network']['pending']==row['network']['readers']==row['network']['terminals']==0,'Logout retained phase/network owner')
            require(row['packets']['connections']==row['packets']['chatPending']==row['packets']['tasks']['pending']==row['packets']['tasks']['terminals']==0,'Logout retained packet/queue references')
        else:
            require({p['name'] for p in row['players']}==set(NAMES) and all(p['ownerCurrent'] for p in row['players']),'Eight actual client owners missing')

    def resources(reads):
        require(set(reads)==set(NAMES),'Missing resource players')
        total=Counter()
        for name,fields in reads.items():
            for field,text in fields.items():
                require(any(row['command']==f'data get entity {name} {field}' and row['response']==text for row in commands),'Invented original resource read')
            total.update(nbt_items(fields['Inventory']))
        return total

    def interval(label, save_allowed=False):
        before,after=marks[label+'_before'],marks[label+'_after']
        selected=[row for row in commands if int(before['nano'])<=row['startNs']<=int(after['nano'])]
        require(all(row['command'].startswith(('data get ','execute if ','neoforge regionthreading status players'))
                    or row['command']=='execute as @e[type=minecraft:item] run data get entity @s Item'
                    or save_allowed and row['command']=='save-all flush' for row in selected),'Command wrote tested gameplay result')
        return [row for row in wire if before['seq']<row['seq']<after['seq']]

    expected={'four_cross_region_attacks','shared_chest_open','shared_chest_race','shared_hopper_open','shared_hopper_race',
              'cross_region_break','cross_region_place','cross_region_milk','normal_mount','normal_dismount','create_and_store_map'}
    require({row['name'] for row in r['operations']}==expected and len(r['operations'])==len(expected),'Operation set incomplete/duplicate')
    checked=[]
    for op in r['operations']:
        name=op['name'];before=op['before'];after=op['after'];packets=interval(name)
        state(before['state']);state(after['state']);pre=resources(before['resources']);post=resources(after['resources'])
        require(all(re.search(r': 7\s*$',p['XpTotal']) for group in [before,after] for p in group['resources'].values()),'Operation XP conservation failed')
        for group in [before,after]:
            for pos,text in group['blocks'].items():
                require(any(row['command']=='data get block '+pos+' Items' and row['response']==text for row in commands),'Invented block entity resource read')
            require(any(row['command']=='execute as @e[type=minecraft:item] run data get entity @s Item' and row['response']==group['ground'] for row in commands),'Invented ground resource read')
        require(all(not row['ended'] for row in after['clients'].values()),'Operation disconnected real player')
        if name=='four_cross_region_attacks':
            sent=[p for p in packets if p['direction']=='frame_send' and p['name']=='use_entity']
            require(len(sent)==4 and {p['bot'] for p in sent}==set(NAMES[::2]),'Four attacks did not originate from normal wire')
            owners={p['name']:p for p in before['state']['players']}
            for attacker in sent:
                i=NAMES.index(attacker['bot']);victim=NAMES[i+1];a,b=owners[attacker['bot']],owners[victim]
                require((a['regionX'],a['regionZ'])!=(b['regionX'],b['regionZ']) and abs(a['x']-b['x'])==2,'Attack did not cross actual region boundary')
                require(attacker['data']['mouse']==1 and re.search(r': 19\.0f\s*$',after['resources'][victim]['Health']) and after['clients'][victim]['health']==19,'Cross-region attack state/health failed')
                feedback=[p for p in packets if p['direction']=='frame' and p['bot']==victim and p['name']=='damage_event' and p['data']['entityId']==attacker['data']['target']]
                require(len(feedback)==1,'Cross-region original damage feedback missing/duplicate')
            require(pre==post=={'minecraft:diamond':128},'Attack item conservation failed')
        elif name in ['shared_chest_race','shared_hopper_race']:
            require(len([p for p in packets if p['direction']=='frame_send' and p['name']=='window_click'])==2,'Shared container race lacks two normal clicks')
            pre_total,post_total=pre.copy(),post.copy()
            pre_total.update(nbt_items(before['ground']));post_total.update(nbt_items(after['ground']))
            for text in before['blocks'].values():pre_total.update(nbt_items(text))
            for text in after['blocks'].values():post_total.update(nbt_items(text))
            require(pre_total==post_total,'Shared machine/container items duplicated or lost')
            require(post_total==({'minecraft:diamond':139} if name=='shared_chest_race' else {'minecraft:diamond':139,'minecraft:iron_ingot':13}),'Shared resource fixture totals differ')
            if name=='shared_chest_race':require(not nbt_items(after['blocks']['48 100 24']),'Shared chest click did not transfer actual items')
            if r.get('schema',1)>=3:
                for actor in NAMES[:2]:
                    window=after['clients'][actor]['window']
                    width=27 if name=='shared_chest_race' else 5
                    actual=Counter()
                    for stack in window['items'][width:]:
                        if stack['itemCount']:
                            require(stack['itemId'] in [805,811],'Unexpected client resource type')
                            actual[{805:'minecraft:diamond',811:'minecraft:iron_ingot'}[stack['itemId']]]+=stack['itemCount']
                    require(actual==nbt_items(after['resources'][actor]['Inventory']),'Reopened authoritative client inventory disagrees with server')
                    require(any(p['direction']=='frame' and p['bot']==actor and p['name']=='window_items' and p['data']['windowId']==window['windowId'] for p in packets),'Reopened client feedback absent from original wire')
        elif name.endswith('_open'):
            require(len([p for p in packets if p['direction']=='frame_send' and p['name']=='block_place'])==2,'Two ordinary BE use packets missing')
            require({p['bot'] for p in packets if p['direction']=='frame' and p['name']=='open_window'}>=set(NAMES[:2]),'Actual client menu feedback missing')
            before_total=pre+nbt_items(before['ground']);after_total=post+nbt_items(after['ground'])
            for text in before['blocks'].values():before_total.update(nbt_items(text))
            for text in after['blocks'].values():after_total.update(nbt_items(text))
            require(before_total==after_total,'Opening shared BE changed conserved total')
        elif name=='cross_region_break':
            require([p['data']['status'] for p in packets if p['direction']=='frame_send' and p['name']=='block_dig']==[0,2],'Survival break wire sequence missing')
            require('Test passed' in r['brokenBlockRead'] and any(row['command']=='execute if block 48 100 24 minecraft:air' and row['response']==r['brokenBlockRead'] for row in commands),'Actual block break failed')
            require(any(p['direction']=='frame' and p['bot']=='Scale00' and p['name']=='block_change' and p['data']['location']==dict(x=48,y=100,z=24) and p['data']['type']==0 for p in packets),'Actual client block break update missing')
            left=pre+nbt_items(before['ground'])+Counter({'minecraft:dirt':1})
            right=post+nbt_items(after['ground'])
            require(left==right,'Broken block drop/inventory conservation failed')
        elif name=='cross_region_place':
            require(len([p for p in packets if p['direction']=='frame_send' and p['name']=='block_place'])==1 and 'Test passed' in r['placedBlockRead'],'Ordinary placement failed')
            require(any(row['command']=='execute if block 48 100 24 minecraft:dirt' and row['response']==r['placedBlockRead'] for row in commands),'Invented block placement read')
            require(pre['minecraft:dirt']-post['minecraft:dirt']==1,'Placed block did not consume exactly one item')
            require(pre+nbt_items(before['ground'])==post+nbt_items(after['ground'])+Counter({'minecraft:dirt':1}),'Placement changed unrelated resources')
            require(any(p['direction']=='frame' and p['bot']=='Scale00' and p['name']=='block_change' and p['data']['location']==dict(x=48,y=100,z=24) and p['data']['type']==10 for p in packets),'Actual client placed block update missing')
        elif name=='cross_region_milk':
            sent=[p for p in packets if p['direction']=='frame_send' and p['name']=='use_entity']
            require(len(sent)==1 and sent[0]['data']['mouse']==0 and sent[0]['data']['hand']==0,'Milk did not use ordinary interaction wire')
            spawn=[p for p in wire if p['direction']=='frame' and p['bot']=='Scale00' and p['name']=='spawn_entity' and p['data']['entityId']==sent[0]['data']['target'] and p['data']['type']==22]
            actor=next(p for p in before['state']['players'] if p['name']=='Scale00')
            require(spawn and int(spawn[-1]['data']['x']//48)!=actor['regionX'],'Milk target not across actual region boundary')
            require(pre['minecraft:bucket']==1 and post['minecraft:bucket']==0 and post['minecraft:milk_bucket']==1,'Original milk conversion failed')
            require(pre+nbt_items(before['ground'])+Counter({'minecraft:milk_bucket':1})==post+nbt_items(after['ground'])+Counter({'minecraft:bucket':1}),'Milking changed unrelated resources')
            require(any(p['direction']=='frame' and p['bot']=='Scale00' and p['name']=='set_slot' and p['data']['item'].get('itemId')==914 and p['data']['item']['itemCount']==1 for p in packets),'Actual milk bucket client feedback missing')
        elif name=='normal_mount':
            sent=[p for p in packets if p['direction']=='frame_send' and p['name']=='use_entity']
            require(len(sent)==1 and sent[0]['data']['mouse']==0,'Mount not ordinary entity interaction')
            text=after['extraRead']
            require('Entity:' in text and 'minecraft:boat' in text and any(row['command']=='data get entity Scale00 RootVehicle' and row['response']==text for row in commands),'No actual original mounted vehicle state')
            require(any(p['direction']=='frame' and p['bot']=='Scale00' and p['name']=='set_passengers' and p['data']['entityId']==sent[0]['data']['target'] and p['data']['passengers'] for p in packets),'Actual client mount feedback missing')
            require(pre+nbt_items(before['ground'])==post+nbt_items(after['ground']),'Mount changed item total')
        elif name=='normal_dismount':
            require(any(p['direction']=='frame_send' and p['name']=='steer_vehicle' and p['data']['jump']==2 for p in packets),'Normal dismount input missing')
            require(any(p['direction']=='frame' and p['bot']=='Scale00' and p['name']=='set_passengers' and not p['data']['passengers'] for p in packets),'Actual dismount feedback missing')
            require(pre+nbt_items(before['ground'])==post+nbt_items(after['ground']),'Dismount changed item total')
        elif name=='create_and_store_map':
            require(len([p for p in packets if p['direction']=='frame_send' and p['name']=='use_item'])==1 and len([p for p in packets if p['direction']=='frame_send' and p['name']=='window_click'])==2,'Map actions not ordinary wire')
            require(pre['minecraft:map']==1 and post['minecraft:filled_map']==1 and post['minecraft:map']==0,'Original map conversion failed')
            require(pre+nbt_items(before['ground'])+Counter({'minecraft:filled_map':1})==post+nbt_items(after['ground'])+Counter({'minecraft:map':1}),'Map conversion changed unrelated resources')
            require(sum(v['itemCount'] for v in after['clients']['Scale00']['window']['items'] if v.get('itemId')==982)==1,'Map client inventory disagrees')
            require(list((root/'world/data').glob('map_*.dat')),'Actual saved map missing')
        checked.append(dict(name=name,status='passed',normalWireFrames=len(packets)))
    require([t['direction'] for t in r['teleports']]==['east','west'],'Missing both teleport directions')
    for teleport in r['teleports']:
        state(teleport['state']);label='teleport_'+teleport['direction']
        left,right=marks[label+'_before']['seq'],marks[label+'_after']['seq']
        packets=[p for p in wire if left<p['seq']<right and p['bot']=='Scale00']
        positions=[p for p in packets if p['direction']=='frame' and p['name']=='position']
        confirms=[p for p in packets if p['direction']=='frame_send' and p['name']=='teleport_confirm']
        require(positions and confirms and positions[-1]['data']['teleportId']==confirms[-1]['data']['teleportId'] and positions[-1]['seq']<confirms[-1]['seq'],'Original teleport/confirm ordering failed')
        actual=next(p for p in teleport['state']['players'] if p['name']=='Scale00')
        require(actual['x']==teleport['targetX'] and actual['regionX']==int(teleport['targetX']//48),'Teleport owner publication failed')
    if r.get('schema',1)>=2:
        dimensions=r['dimensions']
        require('minecraft:overworld' in dimensions['initialDimension'] and any('minecraft:the_nether' in v for v in dimensions['outwardReads'])
                and any('minecraft:overworld' in v for v in dimensions['inwardReads']),'Missing actual portal round trip')
        for fields in ['portalResourcesBefore','portalResourcesAfter']:
            require(resources(r[fields])=={'minecraft:diamond':128},'Portal inventory conservation failed')
            require(all(re.search(r': 7\s*$',v['XpTotal']) for v in r[fields].values()),'Portal XP conservation failed')
        packets=interval('dimension')
        require(len([p for p in packets if p['bot']=='Scale00' and p['direction']=='frame' and p['name']=='respawn'])==2,'Portal client dimension feedback missing/duplicate')
        require(any(p['bot']=='Scale00' and p['direction']=='frame_send' and p['name']=='position_look' for p in packets),'Portal entry did not use normal movement')
        for suffix,wanted in [('nether','minecraft:the_nether'),('overworld','minecraft:overworld')]:
            original=json.loads((output/('dimension-'+suffix+'.json')).read_text());state(original)
            uuid=next(p['uuid'] for p in original['players'] if p['name']=='Scale00')
            if install['playerPlanningRequested'] and not mods:
                require(any(call['player']==uuid and call['dimension']==wanted for call in original['phases']['recent']),'Missing original player phase in portal dimension')

    require(len(r['cycles'])==r['cycleCount']>=2,'Missing repeated real-client recovery cycles')
    worker=Counter()
    for cycle in r['cycles']:
        state(cycle['before']);state(cycle['after']);state(cycle['empty'],True);state(cycle['rejoined'])
        if install['playerPlanningRequested'] and not mods:
            for actor in cycle['before']['players']:
                old={c['playerInstance'] for c in cycle['before']['phases']['recent'] if c['player']==actor['uuid']}
                new={c['playerInstance'] for c in cycle['rejoined']['phases']['recent'] if c['player']==actor['uuid']}
                require(old and new and old.isdisjoint(new),'Reconnection reused a retired player instance or stale trace')
        for sample in cycle['samples']:state(sample)
        for fields in ['beforeResources','afterResources','restoredResources']:
            require(resources(cycle[fields])=={'minecraft:diamond':128},'Cycle item/save conservation failed')
            require(all(re.search(r': 7\s*$',v['XpTotal']) and re.search(r': 20\.0f\s*$',v['Health']) for v in cycle[fields].values()),'Cycle XP/health conservation failed')
        # These windows intentionally contain a disk save followed by logout/login. Saving is
        # permitted here only, never in a gameplay window; arbitrary fixture writes still fail.
        packets=interval('cycle_'+str(cycle['index']),save_allowed=True)
        for player in NAMES:
            require(len([p for p in packets if p['direction']=='frame_send' and p['bot']==player and p['name']=='ping_request'])>=r['cycleSeconds'],'Actual sustained player network load missing')
            feedback=[p for p in packets if p['direction']=='frame' and p['bot']==player]
            inventory=[p for p in feedback if p['name']=='window_items' and p['data']['windowId']==0]
            experience=[p for p in feedback if p['name']=='experience']
            require(inventory and sum(v['itemCount'] for v in inventory[-1]['data']['items'] if v.get('itemId')==805)==16
                    and all(v.get('itemId')==805 for v in inventory[-1]['data']['items'] if v['itemCount']),'Reconnected client did not receive conserved inventory')
            require(experience and experience[-1]['data']['totalExperience']==7 and experience[-1]['data']['level']==1
                    and experience[-1]['data']['experienceBar']==0,'Reconnected client XP feedback disagrees')
        for phase in ['ENTITY','CONNECTION']:
            delta=cycle['after']['phases']['phases'][phase]['worker']-cycle['before']['phases']['phases'][phase]['worker']
            worker[phase]+=delta
            require(delta>0 if install['playerPlanningRequested'] and not mods else delta==0,'Full phase worker/off/unknown-Mod coverage mismatch')
            if install['playerPlanningRequested'] and mods:
                require(cycle['after']['refusals'].get(phase+':UNKNOWN_MOD',0)>cycle['before']['refusals'].get(phase+':UNKNOWN_MOD',0),'Unknown Mod was not refused before full phase work')
    require(r['beforeRestart']['phases']['serverSession']!=r['afterRestart']['phases']['serverSession'],'Original server did not restart')
    state(r['beforeRestart']);state(r['afterRestart']);state(r['final'],True)
    require(resources(r['restoredResources'])=={'minecraft:diamond':128},'Restart inventory conservation failed')
    for player in NAMES:
        feedback=[p for p in wire if p['direction']=='frame' and p['bot']==player]
        inventory=[p for p in feedback if p['name']=='window_items' and p['data']['windowId']==0]
        experience=[p for p in feedback if p['name']=='experience']
        require(inventory and sum(v['itemCount'] for v in inventory[-1]['data']['items'] if v.get('itemId')==805)==16
                and all(v.get('itemId')==805 for v in inventory[-1]['data']['items'] if v['itemCount']),'Restarted client inventory feedback disagrees')
        require(experience and experience[-1]['data']['totalExperience']==7,'Restarted client XP feedback disagrees')
    for actor in r['afterRestart']['players']:
        saved=json.loads(subprocess.check_output(['node',str(TOOLS/'read_saved_nbt.cjs'),str(root/'world/playerdata'/(actor['uuid']+'.dat'))],text=True))
        require(saved['XpTotal']==7 and saved['Health']==20 and sum(v['count'] for v in saved['Inventory'] if v['id']=='minecraft:diamond')==16 and len(saved['Inventory'])==1,'Actual player save differs after restart')
    records={(s['phases']['serverSession'],call['id']):call for s in states for call in s['phases']['recent'] if call['started']}
    invocation_keys=[(session,call['tick'],call['phase'],call['playerInstance']) for (session,_),call in records.items()]
    require(len(invocation_keys)==len(set(invocation_keys)),'Duplicate same-player same-tick phase body')
    overlaps=Counter();by_tick={}
    for (session,_),call in records.items():
        require(call['state']=='JOINED' and call.get('failureClass') is None and call['returnNanos']>=call['startNanos']>0,'Original operation phase did not return/join')
        if not call['worker']:continue
        same_tick=by_tick.setdefault((session,call['tick'],call['phase']),[])
        for other in same_tick:
            if other['player']!=call['player'] and min(other['returnNanos'],call['returnNanos'])>max(other['startNanos'],call['startNanos']):
                overlaps[call['phase']]+=1
        same_tick.append(call)
    for boot in [1,2]:
        log=(output/f'boot-{boot}.server.log').read_text()
        require(not any(text in log for text in ['Failed to handle packet','Caused by:','Exception in thread','Error executing task','Native region worker attempted']),'Original operations server error')
    return dict(status='passed',version=install['version'],requested=install['playerPlanningRequested'],persistent=install['persistent'],
                clients=8,operations=checked,installedMods=mods,sharedContainerClientResyncVerified=r.get('schema',1)>=3,
                restoredClientInventoryAndXpVerified=True,
                teleportDirections=2,portalRoundTrip=r.get('schema',1)>=2,artifactManifestSha256=manifest_digest,
                recoveryCycles=len(r['cycles']),measuredLoadSeconds=sum(c.get('loadElapsedSeconds',r['cycleSeconds']) for c in r['cycles']),
                loadDurationIsLowerBound=any('loadElapsedSeconds' not in c for c in r['cycles']),
                workerCalls=dict(worker),originalPhaseInvocationsObserved=len(records),observedOriginalPhaseOverlapPairs=dict(overlaps),
                serverExits=r['serverExits'],clientsExit=r['clientsExit'],wholeP5Complete=False,
                limitations=['Vanilla hopper/chest automation; no Create or arbitrary Mod client certification',
                             'Bounded cycles and one save/restart are not multi-hour soak or leak freedom',
                             'Original wire feedback and state; independent Mod event observer not installed in worker baseline'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server',type=Path,required=True);parser.add_argument('--installer',type=Path,required=True)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args();output=args.output or args.server/'player-operations/independent-verdict.json'
    require(not output.exists(),'Preserve prior operation verdict')
    try:result=verify(args.server.resolve(),args.installer.resolve())
    except BaseException as error:
        output.write_text(json.dumps(dict(status='failed',error=repr(error)),indent=2)+'\n');raise
    output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))


if __name__=='__main__':main()
