#!/usr/bin/env python3
"""Eight real TCP players: cross-region operations and repeated logout/save/restart.

Commands establish fixtures outside marked operation windows or independently read
the result. This suite grants no extension permission and makes no speedup claim.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
from run_worker_lifecycle import Session
from verify_resources import nbt_items
from dimension_scenarios import run_dimensions

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]
NAMES = [f'Scale{i:02}' for i in range(8)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--cycles', type=int, default=3)
    parser.add_argument('--cycle-seconds', type=float, default=20)
    parser.add_argument('--mods-manifest', type=Path)
    args = parser.parse_args()
    assert args.cycles >= 2 and args.cycle_seconds >= 5
    root = args.server.resolve()
    assert not list((root/'mods').glob('*.jar')), 'Use a fresh installation'
    mods=[]
    if args.mods_manifest:
        mods=json.loads(args.mods_manifest.read_text())['mods']
        assert mods
        for entry in mods:
            source=Path(entry['path'])
            assert hashlib.sha256(source.read_bytes()).hexdigest()==entry['sha256']
            shutil.copy2(source,root/'mods'/source.name)
    s = Session(root, 'player-operations', TOOLS/'gameplay_clients.cjs')
    result = dict(schema=3, status='running', installation=s.install, operations=[], cycles=[], sources={}, installedMods=mods,
                  cycleSeconds=args.cycle_seconds, cycleCount=args.cycles,
                  scope='Eight native TCP clients, vanilla shared machine, bounded recovery; no performance or arbitrary Mod certificate')
    for source in [Path(__file__).resolve(), TOOLS/'gameplay_clients.cjs', TOOLS/'run_worker_lifecycle.py',
                   TOOLS/'verify_player_operations.py', TOOLS/'decode_player_operations.cjs', TOOLS/'dimension_scenarios.py']:
        target = s.output/'source'/source.relative_to(REPO)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        result['sources'][str(source.relative_to(REPO))] = hashlib.sha256(source.read_bytes()).hexdigest()
    players = {}
    sequence = 100

    def packet(name, kind, **data):
        return s.request('packet', name=name, packet=kind, data=data)

    def use(name, x=48, y=100, z=24):
        nonlocal sequence
        sequence += 1
        packet(name, 'block_place', hand=0, location=dict(x=x,y=y,z=z), direction=1,
               cursorX=.5, cursorY=1., cursorZ=.5, insideBlock=False, sequence=sequence)

    def snapshot(label, empty=False):
        state = s.snapshot(label)
        assert state['activeBatches'] == state['stateClaims'] == state['phases']['pending'] == state['phases']['running'] == 0
        assert not state['network']['frozen']
        assert all(p['failed'] == 0 for p in state['phases']['phases'].values()) and state['packets']['failed'] == 0
        if empty:
            assert not state['players']
            assert state['phases']['owners'] == state['network']['pending'] == state['network']['readers'] == state['network']['terminals'] == 0
            assert state['packets']['connections'] == state['packets']['chatPending'] == state['packets']['tasks']['pending'] == state['packets']['tasks']['terminals'] == 0
        return state

    def resources():
        return {name: {field:s.command(f'data get entity {name} {field}') for field in ['Inventory','XpTotal','Health']}
                for name in NAMES}

    def item_sum(reads):
        total = Counter()
        for row in reads.values():
            total.update(nbt_items(row['Inventory']))
        return total

    def connect_all():
        for name in NAMES:
            players[name] = s.request('connect', name=name, port=s.install['port'])

    def prepare(actors):
        for actor in actors:
            x,z = int(actor['x']), int(actor['z'])
            s.command(f'forceload add {x-6} {z-6} {x+6} {z+6}')
            s.command(f'fill {x-6} 99 {z-6} {x+6} 99 {z+6} minecraft:stone')
            s.command(f"tp {actor['name']} {actor['x']} 100 {actor['z']}")
        time.sleep(1)

    def observe(label, action, blocks=(), delay=.7, extra_read=None):
        # A fixture teleport after dismount can be between its original position publication and
        # the next native entity owner update. Retain every sample and wait before marking action.
        for attempt in range(30):
            ready=snapshot(label+'-prepare-'+str(attempt))
            if len(ready['players'])==8 and all(p['ownerCurrent'] for p in ready['players']):break
            time.sleep(.1)
        else:raise AssertionError('Fixture owner did not settle before normal operation '+label)
        before = dict(state=snapshot(label+'-before'), resources=resources(),
                      blocks={p:s.command('data get block '+p+' Items') for p in blocks},
                      ground=s.command('execute as @e[type=minecraft:item] run data get entity @s Item'))
        s.request('mark', label=label+'_before')
        action()
        time.sleep(delay)
        after = dict(state=snapshot(label+'-after'), resources=resources(),
                     blocks={p:s.command('data get block '+p+' Items') for p in blocks}, clients=s.request('snapshot'),
                     ground=s.command('execute as @e[type=minecraft:item] run data get entity @s Item'))
        if extra_read:
            after['extraRead']=extra_read()
        s.request('mark', label=label+'_after')
        row = dict(name=label,before=before,after=after)
        result['operations'].append(row)
        (s.output/'progress.json').write_text(json.dumps(result,indent=2)+'\n')
        return row

    def close_containers(names):
        clients=s.request('snapshot')
        for name in names:
            packet(name,'close_window',windowId=clients[name]['windowId'])
        time.sleep(.2)

    def container_race():
        s.request('quick_move',names=NAMES[:2])
        time.sleep(.2)
        # Correct client prediction may receive no changed-slot packet. Reopen normally so the
        # test client observes a fresh authoritative full menu instead of stale predicted slots.
        close_containers(NAMES[:2])
        use(NAMES[0]);use(NAMES[1])

    def disconnect_all():
        for name in NAMES:
            s.request('disconnect', name=name)
        time.sleep(1)

    actors=[dict(name=name,x=24.5+i*96,y=100,z=24.5) for i,name in enumerate(NAMES)]
    try:
        s.start()
        s.command('difficulty normal')
        for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:
            s.command('gamerule '+rule)
        connect_all()
        s.command('advancement grant @a everything')
        s.command('clear @a')
        s.command('experience add @a -1000000 points')
        s.command('experience set @a 0 levels'); s.command('experience set @a 0 points'); s.command('experience add @a 7 points')
        s.command('item replace entity @a inventory.0 with minecraft:diamond 16')
        prepare(actors)
        time.sleep(2)
        result['initial']=snapshot('initial')

        # Four independent boundary pairs. Each attack is the ordinary use_entity action.
        pairs=[dict(name=name,x=47.+i%2*2,y=100,z=24.5+i//2*96) for i,name in enumerate(NAMES)]
        prepare(pairs)
        def attacks():
            s.request('batch',actions=[dict(name=NAMES[i],packet='use_entity',data=dict(target=players[NAMES[i+1]]['id'],mouse=1,sneaking=False)) for i in range(0,8,2)])
        attack=observe('four_cross_region_attacks',attacks)
        assert all(re.search(r': 19\.0f\s*$',attack['after']['resources'][NAMES[i]]['Health']) for i in [1,3,5,7])
        s.command('effect give @a minecraft:instant_health 1 5 true');time.sleep(.3)

        # Two players race for a single shared chest while six other real clients stay logged in.
        s.command('setblock 48 100 24 minecraft:chest[type=single,facing=north]')
        s.command('item replace block 48 100 24 container.0 with minecraft:diamond 11')
        observe('shared_chest_open',lambda:(use(NAMES[0]),use(NAMES[1])),blocks=['48 100 24'])
        row=observe('shared_chest_race',container_race,blocks=['48 100 24'])
        assert item_sum(row['after']['resources']) == {'minecraft:diamond':139}
        assert not nbt_items(row['after']['blocks']['48 100 24'])
        close_containers(NAMES[:2])

        # A vanilla hopper feeding a chest is actual shared BE automation. Its original ticks race
        # with two normal menu clicks; iron is counted in both BEs and every player's inventory.
        s.command('setblock 48 100 24 minecraft:air')
        s.command('setblock 48 98 24 minecraft:stone')
        s.command('setblock 48 99 24 minecraft:chest[type=single]')
        s.command('setblock 48 100 24 minecraft:hopper[facing=down,enabled=true]')
        s.command('item replace block 48 100 24 container.0 with minecraft:iron_ingot 13')
        observe('shared_hopper_open',lambda:(use(NAMES[0]),use(NAMES[1])),blocks=['48 100 24','48 99 24'],delay=.2)
        row=observe('shared_hopper_race',container_race,blocks=['48 100 24','48 99 24'])
        totals=item_sum(row['after']['resources'])
        for text in row['after']['blocks'].values():totals.update(nbt_items(text))
        assert totals == {'minecraft:diamond':139,'minecraft:iron_ingot':13}
        close_containers(NAMES[:2])

        # Native survival break and place cross the x=48 region boundary.
        s.command('setblock 48 100 24 minecraft:dirt')
        def dig():
            nonlocal sequence
            sequence+=1;packet(NAMES[0],'block_dig',status=0,location=dict(x=48,y=100,z=24),face=1,sequence=sequence)
            time.sleep(1)
            sequence+=1;packet(NAMES[0],'block_dig',status=2,location=dict(x=48,y=100,z=24),face=1,sequence=sequence)
        observe('cross_region_break',dig,delay=2)
        result['brokenBlockRead']=s.command('execute if block 48 100 24 minecraft:air')
        assert 'Test passed' in result['brokenBlockRead']
        # Remove the previous lower chest and move the second actor out of the placement volume.
        # Both are fixture work before the normal placement packet, never a bypass of collision/use.
        s.command('setblock 48 99 24 minecraft:stone')
        s.command('tp Scale01 49.5 100 26.5')
        s.command('item replace entity Scale00 hotbar.0 with minecraft:dirt 2')
        packet(NAMES[0],'held_item_slot',slotId=0)
        observe('cross_region_place',lambda:use(NAMES[0],48,99,24))
        result['placedBlockRead']=s.command('execute if block 48 100 24 minecraft:dirt')
        assert 'Test passed' in result['placedBlockRead']

        # A normal interaction with an ordinary entity across the same boundary.
        s.command('setblock 48 100 24 minecraft:air')
        s.command('summon minecraft:cow 48.5 100 24.5 {Tags:["operations_cow"],NoAI:1b,NoGravity:1b,Silent:1b,DeathLootTable:"minecraft:empty"}')
        time.sleep(.3)
        from_client=s.request('entities',name=NAMES[0])
        # The original spawn position and entity ID come from the real client's received packets.
        cow=next(e for e in from_client if e['type']==22 and abs(e['x']-48.5)<.01 and abs(e['z']-24.5)<.01)
        s.command('item replace entity Scale00 hotbar.0 with minecraft:bucket')
        row=observe('cross_region_milk',lambda:packet(NAMES[0],'use_entity',target=cow['entityId'],mouse=0,hand=0,sneaking=False))
        assert nbt_items(row['after']['resources'][NAMES[0]]['Inventory'])['minecraft:milk_bucket']==1
        s.command('kill @e[tag=operations_cow]')

        # Mount/dismount and vehicle input retain the ordinary serial eligibility path.
        s.command('summon minecraft:boat 48.5 100 24.5 {Tags:["operations_boat"],NoGravity:1b,Type:"oak"}')
        time.sleep(.3)
        boat=next(e for e in s.request('entities',name=NAMES[0]) if e['type']==10 and abs(e['x']-48.5)<.01 and abs(e['z']-24.5)<.01)
        row=observe('normal_mount',lambda:packet(NAMES[0],'use_entity',target=boat['entityId'],mouse=0,hand=0,sneaking=False),
                    extra_read=lambda:s.command('data get entity Scale00 RootVehicle'))
        assert 'Entity:' in row['after']['extraRead'] and 'minecraft:boat' in row['after']['extraRead']
        observe('normal_dismount',lambda:packet(NAMES[0],'steer_vehicle',sideways=0,forward=0,jump=2))
        s.command('kill @e[tag=operations_boat]')
        s.command('tp Scale00 47.0 100 24.5')

        # A normal use creates a real saved map; ordinary inventory clicks move it to the backpack.
        s.command('item replace entity Scale00 hotbar.0 with minecraft:map')
        def create_map():
            nonlocal sequence
            sequence+=1;packet(NAMES[0],'use_item',hand=0,sequence=sequence,rotation=dict(x=0,y=0));time.sleep(.4)
            window=s.request('snapshot')[NAMES[0]]['window'];item=window['items'][36]
            assert item['itemCount']==1
            packet(NAMES[0],'window_click',windowId=0,stateId=window['stateId'],slot=36,mouseButton=0,mode=0,
                   changedSlots=[dict(location=36,item=dict(itemCount=0))],cursorItem=item);time.sleep(.2)
            packet(NAMES[0],'window_click',windowId=0,stateId=window['stateId'],slot=10,mouseButton=0,mode=0,
                   changedSlots=[dict(location=10,item=item)],cursorItem=dict(itemCount=0))
        row=observe('create_and_store_map',create_map,delay=1)
        assert nbt_items(row['after']['resources'][NAMES[0]]['Inventory'])['minecraft:filled_map']==1

        # Same-dimension command teleport is a fixture; landing is the real automatic client
        # confirm plus normal movement packet. Observe both directions and the original owners.
        for direction,x in [('east',49.5),('west',47.0)]:
            s.request('mark',label='teleport_'+direction+'_before')
            response=s.command(f'tp Scale00 {x} 100 24.5')
            time.sleep(.5)
            state=snapshot('teleport-'+direction)
            result.setdefault('teleports',[]).append(dict(direction=direction,targetX=x,response=response,state=state,client=s.request('snapshot')[NAMES[0]]))
            s.request('mark',label='teleport_'+direction+'_after')

        # Plain invariant fixture for bounded load/reconnect/save/restart, after operations.
        s.command('clear @a')
        s.command('item replace entity @a inventory.0 with minecraft:diamond 16')
        s.command('tp Scale01 120.5 100 24.5')
        result['portalResourcesBefore']=resources()
        result['dimensions']=run_dimensions(s,actor=NAMES[0])
        result['portalResourcesAfter']=resources()
        prepare(actors)
        for cycle in range(args.cycles):
            s.request('mark',label=f'cycle_{cycle}_before')
            before=snapshot(f'cycle-{cycle}-before')
            before_resources=resources()
            s.request('load_start',actors=actors,intervalMillis=500,baseId=800000000+cycle*100000)
            started=time.monotonic(); samples=[]
            while time.monotonic()-started<args.cycle_seconds:
                time.sleep(min(5,max(0,args.cycle_seconds-(time.monotonic()-started))))
                assert s.process.poll() is None
                samples.append(snapshot(f'cycle-{cycle}-sample-{len(samples)}'))
                assert all(not row['ended'] for row in s.request('snapshot').values())
            load_elapsed=time.monotonic()-started
            s.request('load_stop')
            after_resources=resources();after=snapshot(f'cycle-{cycle}-after')
            s.command('save-all flush')
            disconnect_all();empty=snapshot(f'cycle-{cycle}-empty',empty=True)
            connect_all();time.sleep(1)
            restored=resources();rejoined=snapshot(f'cycle-{cycle}-rejoined')
            for group in [before_resources,after_resources,restored]:
                assert item_sum(group)=={'minecraft:diamond':128}
                assert all(re.search(r': 7\s*$',row['XpTotal']) and re.search(r': 20\.0f\s*$',row['Health']) for row in group.values())
            s.request('mark',label=f'cycle_{cycle}_after')
            result['cycles'].append(dict(index=cycle,elapsedSeconds=time.monotonic()-started,loadElapsedSeconds=load_elapsed,before=before,after=after,
                                         samples=samples,empty=empty,rejoined=rejoined,beforeResources=before_resources,
                                         afterResources=after_resources,restoredResources=restored))
            (s.output/'progress.json').write_text(json.dumps(result,indent=2)+'\n')

        s.command('save-all flush')
        result['beforeRestart']=snapshot('before-restart')
        s.stop()  # Real clients remain connected. Original server shutdown saves and closes them.
        for name in NAMES:s.request('disconnect',name=name)
        s.start();connect_all();time.sleep(2)
        result['afterRestart']=snapshot('after-restart');result['restoredResources']=resources()
        assert item_sum(result['restoredResources'])=={'minecraft:diamond':128}
        s.command('save-all flush')
        s.request('close');s.bots.wait(timeout=15);time.sleep(1)
        result['final']=snapshot('final',empty=True)
        s.stop()
        result['status']='passed'
    except BaseException as error:
        result.update(status='failed',error=repr(error))
        raise
    finally:
        try:s.close()
        except BaseException as error:
            result.update(status='failed',cleanupError=repr(error));raise
        finally:
            result.update(serverExits=s.exits,clientsExit=s.bots.returncode)
            (s.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    assert result['serverExits']==[0,0] and result['clientsExit']==0


if __name__=='__main__':
    main()
