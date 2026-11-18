#!/usr/bin/env python3
"""Original full movement/teleport-confirm/local click evidence, independently joined to actual wire/save."""
import re
import json
import subprocess
from owner_proof import base, cli, lines, load, require, saved, window
from owner_proof import TOOLS
from verify_resources import nbt_items


def verify(root, installer):
    out, r, install, commands, states, raw, marks, rows, final = base(root, installer, 'owner-routes')
    enabled = install['playerPlanningRequested']
    if 'keepalivePreparation' in r:
        keepalive = [json.loads(line) for line in subprocess.check_output(['node', str(TOOLS / 'decode_owner_routes.cjs'), str(out / 'packets.ndjson'), 'keepalive'], text=True).splitlines()]
        packets = lines(out / 'packets.ndjson')
        require(set(r['keepalivePreparation']) == {'WorkerA', 'WorkerB'}, 'Missing route keepalive preparation actors')
        for name, prepared in r['keepalivePreparation'].items():
            require(prepared in packets and prepared['direction'] == 'send' and prepared['name'] == 'keep_alive' and prepared['seq'] < marks['move-0_before'], 'Invented route keepalive preparation')
            replies = [row for row in keepalive if row['bot'] == name and row['direction'] == 'frame_send' and row['data'] == prepared['data'] and row['seq'] < marks['move-0_before']]
            challenges = [row for row in keepalive if row['bot'] == name and row['direction'] == 'frame' and row['data'] == prepared['data']]
            require(len(replies) == len(challenges) == 1 and challenges[0]['seq'] < replies[0]['seq'], 'Route preparation was not a real original wire challenge/reply')
    require(rows[-1].get('complete') is True, 'Incomplete original routes observer')
    for key in ['before', 'confirmed', 'after']:
        require(r[key] in states, 'Invented original route diagnostic ' + key)
    for key in ['inventoryBefore', 'inventoryAfter', 'xpAfter']:
        require(any(x['response'] == r[key] for x in commands), 'Missing independent route read ' + key)
    require(nbt_items(r['inventoryBefore']) == nbt_items(r['inventoryAfter']) == {'minecraft:diamond': 5}, 'Route resource loss/duplication')
    require('Slot: 10b' in r['inventoryAfter'] and re.search(r': 0\s*$', r['xpAfter']), 'Wrong final route slot/XP')
    calls, active, tracking, capture = [], None, [], None
    for row in rows[1:-1]:
        if row['event'] in ['queue-quiescent', 'capture']:
            require(row['thread'] == 'Server thread', 'Route preparation escaped publisher')
            if row['event'] == 'queue-quiescent':
                require(row['readers'] == 0 and row['actor'] == r['actor'], 'Route receive preparation not quiescent')
            if row['event'] == 'queue-quiescent':
                capture = None
            else:
                capture = row
            continue
        require(row['actor'] == r['actor'] and (row['thread'] == 'Server thread' or 'Worker' in row['thread']), 'Foreign route actor/thread')
        if row['event'] == 'enter':
            require(active is None, 'Same-connection original handlers overlap')
            active, tracking = row, []
            methods = {'move': 'handleMovePlayer:', 'confirm': 'handleAcceptTeleportPacket:', 'click': 'handleContainerClick:', 'stats': 'handleClientCommand:'}
            require(row['kind'] in methods and any(methods[row['kind']] in f for f in row['stack']), 'Missing original route handler stack')
            if 'Worker' in row['thread']:
                require(any('RegionPlayerPacketRouter$Call.run:' in f for f in row['stack']) and any('net.minecraft.network.protocol.game.Serverbound' in f and '.handle:' in f for f in row['stack']), 'Side task masquerades as original route')
        elif row['event'] == 'tracking':
            # A fixture teleport can also call tracking outside the observed packet body.
            require(any('ChunkMap.move:' in f for f in row['stack']), 'Missing original tracking body')
            if active is not None:
                require(active['kind'] == 'move', 'Tracking service in unapproved packet')
                tracking.append(row)
        elif row['event'] == 'return':
            require(active is not None and all(row[k] == active[k] for k in ['actor', 'thread', 'kind']) and row['before'] == active['state'], 'Original route entry/return mismatch')
            require(active['nano'] < row['nano'], 'Invalid route execution interval')
            calls.append((active, row, tracking, capture))
            active = None
        else:
            raise AssertionError('Unknown route trace event')
    require(active is None and [e['kind'] for e, _, _, _ in calls] == ['move', 'move', 'move', 'confirm', 'move', 'click', 'click', 'stats'], 'Original route order/count changed')
    worker_indices = []
    targets = [47.75, 48, 48.25, 49.5]
    workers = 0
    for index, (entry, ret, service, preparation) in enumerate(calls):
        worker = enabled and index < 6
        if enabled and index == 4 and 'Worker' not in entry['thread']:
            # Vanilla clears onGround on teleport. The automatic client landing packet can precede
            # the next physics tick; that transient optional state must retain its full serial path.
            require(preparation is not None and preparation['refusal'] == 'SPATIAL' and preparation['detail'] == 'MOVEMENT_UNBOUNDED', 'Unexpected post-confirm landing fallback')
            worker = False
        require(('Worker' in entry['thread']) == worker, 'Missing/unapproved route worker')
        workers += worker
        if worker:
            worker_indices.append(index)
        for state in [entry['state'], ret['state']]:
            require(state['xp'] == state['selected'] == 0 and state['inventory'] + state['carried'] == 5 and state['y'] == 100 and state['z'] == 24.5, 'Original route state/conservation violated')
        if entry['kind'] == 'move':
            move_index = index if index < 3 else 3
            require(ret['state']['x'] == targets[move_index], 'Original move effect missing')
            require(len(service) == (2 if worker else 1), 'Original tracking service lost/duplicated')
            if worker:
                require('Worker' in service[0]['thread'] and service[1]['thread'] == 'Server thread', 'Tracking execution order changed')
                require(any('RegionPlayerPacketRouter$Call$PublisherRequest.run:' in f for f in service[1]['stack']) and any('RegionPlayerPacketRouter$Call.await:' in f for f in service[1]['stack']), 'Tracking bypassed exact synchronous publisher service')
            require(all(s['state']['x'] == targets[move_index] and entry['nano'] < s['nano'] < ret['nano'] for s in service), 'Tracking saw stale position/outlived handler')
        elif entry['kind'] == 'confirm':
            require(entry['state'] == ret['state'] and ret['state']['x'] == 49.5, 'Confirmation changed already-published destination')
        elif entry['kind'] == 'click':
            require([entry['state']['inventory'], ret['state']['inventory']] == ([5, 0] if index == 5 else [0, 5]), 'Original inventory click result wrong')
        else:
            require(entry['thread'] == 'Server thread' and entry['state'] == ret['state'] and ret['state']['carried'] == 0, 'Global stats fence moved/reordered')
    wire_moves = []
    for index, target in enumerate(targets[:3]):
        w = r['windows'][index]
        require(w['label'] == 'move-' + str(index) and w['target'] == target and w['after'] in states, 'Invented route window')
        player = next(p for p in w['after']['players'] if p['uuid'] == r['actor'])
        require(player['x'] == target and player['regionX'] == (0 if target < 48 else 1) and player['regionZ'] == 0, 'Movement owner not published before next original task')
        sent = [p for p in window(raw, marks, w['label']) if p['bot'] == 'WorkerA' and p['direction'] == 'frame_send']
        require(len(sent) == 1 and sent[0]['name'] == 'position_look' and sent[0]['data'] == dict(x=target, y=100, z=24.5, yaw=0, pitch=0, onGround=True), 'Movement did not come from exact normal wire packet')
        wire_moves.append(sent[0])
    confirm = [p for p in window(raw, marks, 'confirm') if p['bot'] == 'WorkerA']
    teleport = [p for p in confirm if p['direction'] == 'frame' and p['name'] == 'position']
    confirms = [p for p in confirm if p['direction'] == 'frame_send' and p['name'] == 'teleport_confirm']
    moves = [p for p in confirm if p['direction'] == 'frame_send' and p['name'] == 'position_look']
    require(len(teleport) == len(confirms) == len(moves) == 1 and teleport[0]['data']['teleportId'] == confirms[0]['data']['teleportId'] and teleport[0]['seq'] < confirms[0]['seq'] < moves[0]['seq'], 'Teleport/confirm/move wire sequence broken')
    require(teleport[0]['data']['x'] == moves[0]['data']['x'] == 49.5, 'Teleport feedback/destination wrong')
    clicks = [p for p in window(raw, marks, 'click') if p['bot'] == 'WorkerA' and p['direction'] == 'frame_send' and p['name'] == 'window_click']
    require(len(clicks) == 2 and [p['data']['slot'] for p in clicks] == [9, 10] and all(p['data']['mode'] == p['data']['mouseButton'] == p['data']['windowId'] == 0 for p in clicks), 'Inventory clicks not normal original wire inputs')
    require(clicks[0]['data']['cursorItem']['itemCount'] == 5 and clicks[0]['data']['cursorItem']['itemId'] == 805 and clicks[1]['data']['cursorItem']['itemCount'] == 0, 'Client inventory prediction mismatch')
    feedback = [p for p in raw if p['bot'] == 'WorkerA' and p['direction'] == 'frame' and p['name'] == 'window_items' and p['seq'] > clicks[1]['seq'] and p['data']['windowId'] == 0]
    require(feedback and feedback[-1]['data']['items'][9]['itemCount'] == 0 and feedback[-1]['data']['items'][10]['itemCount'] == 5 and feedback[-1]['data']['items'][10]['itemId'] == 805 and feedback[-1]['data']['carriedItem']['itemCount'] == 0, 'Missing authoritative final inventory client feedback')
    require(sum(p['direction'] == 'frame' and p['name'] == 'statistics' and p['bot'] == 'WorkerA' and p['seq'] > clicks[-1]['seq'] for p in raw) == 1, 'Missing final original stats response')
    meta = [m for m in r['after']['packets']['recent'] if m['sequence'] > r['before']['packets']['attempted'] and m['started']]
    require(len(meta) == workers and r['after']['packets']['workers'] - r['before']['packets']['workers'] == workers, 'Route metadata disagrees with original observed bodies')
    if enabled:
        wire = wire_moves + [confirms[0], moves[0]] + clicks
        for execution, index in zip(meta, worker_indices):
            entry, ret, _, _ = calls[index]
            require(execution['receiveSequence'] == wire[index]['receiveOrdinal'] and execution['thread'] == entry['thread'], 'Owner execution not tied to its exact original wire receive')
            require(execution['startNanos'] <= entry['nano'] < ret['nano'] <= execution['returnNanos'], 'Owner metadata not enclosing original body')
        require([m['receiveSequence'] for m in meta] == sorted({m['receiveSequence'] for m in meta}) and all(m['receiveSequence'] > 0 for m in meta), 'Original receive ordering/replay metadata invalid')
        require([(m['initialRegionX'], m['regionX']) for m in meta[:3]] == [(0, 0), (0, 1), (1, 1)], 'Packet ownership did not follow crossing')
        require(all(m['player'] == r['actor'] and m['serverSession'] == r['after']['phases']['serverSession'] and m['identityEpoch'] > 0 and m['criterionGeneration'] > 0 for m in meta), 'Wrong packet lifecycle metadata')
    if 'wall' in r:
        wall = r['wall']
        require(wall['after'] in states and any(x['command'] == 'data get entity WorkerA Pos' and x['response'] == wall['positionRead'] for x in commands), 'Invented wall correction diagnostic/read')
        player = next(p for p in wall['after']['players'] if p['uuid'] == r['actor'])
        require([player[k] for k in ['x', 'y', 'z', 'health']] == [49.5, 100, 24.5, 20], 'Original wall correction changed final position/health')
        require([float(x) for x in re.findall(r'(-?\d+(?:\.\d+)?)d', wall['positionRead'])] == [49.5, 100, 24.5], 'Original wall server position not restored')
        require(not wall['client']['ended'] and wall['client']['health'] == 20 and [wall['client']['position'][k] for k in ['x', 'y', 'z']] == [49.5, 100, 24.5], 'Wall fence disconnected or miscorrected the normal client')
        trace = lines(out / 'jdi-wall.ndjson')
        require(trace[0]['event'] == 'armed' and trace[-1]['event'] == 'observed' and trace[-1]['complete'] and [x['seq'] for x in trace] == list(range(1, len(trace) + 1)), 'Original wall observer incomplete')
        entries = [x for x in trace if x['event'] == 'enter']
        returns = [x for x in trace if x['event'] == 'return']
        require(entries and returns and entries[0]['kind'] == returns[0]['kind'] == 'move' and entries[0]['actor'] == returns[0]['actor'] == r['actor'], 'Wall operation missing original handler')
        require(entries[0]['thread'] == returns[0]['thread'] == 'Server thread' and entries[0]['state'] == returns[0]['state'], 'Obstructed move began on worker or failed complete original correction')
        require(entries[0]['state']['x'] == 49.5 and any('ServerGamePacketListenerImpl.handleMovePlayer:' in x for x in entries[0]['stack']), 'Wall body is not the original move handler')
        if enabled:
            capture = next(x for x in trace if x['event'] == 'capture')
            require(capture['refusal'] == 'SPATIAL' and capture['detail'] == 'CALLBACK_SPATIAL_BOUND_UNKNOWN', 'Wall did not refuse before original owner effects')
        require(load(out / 'debugger-wall/result.json')['exit'] == 0, 'Wall observer did not exit')
        actual = window(raw, marks, 'wall')
        attempted = [x for x in actual if x['bot'] == 'WorkerA' and x['direction'] == 'frame_send' and x['name'] == 'position_look' and x['data']['x'] == 49.75]
        corrections = [x for x in actual if x['bot'] == 'WorkerA' and x['direction'] == 'frame' and x['name'] == 'position']
        confirmations = [x for x in actual if x['bot'] == 'WorkerA' and x['direction'] == 'frame_send' and x['name'] == 'teleport_confirm']
        require(len(attempted) == len(corrections) == len(confirmations) == 1 and corrections[0]['data']['x'] == 49.5 and corrections[0]['data']['teleportId'] == confirmations[0]['data']['teleportId'], 'Wall input/correction/confirm not exact normal wire protocol')
        require(attempted[0]['seq'] < corrections[0]['seq'] < confirmations[0]['seq'] and not any(x['name'] == 'kick_disconnect' for x in actual), 'Wall correction order/liveness wrong')
        original = lines(out / 'packets.ndjson')
        require(wall['prepared'] in original and wall['prepared']['direction'] == 'send' and wall['prepared']['name'] == 'keep_alive' and wall['prepared']['seq'] < marks['wall_before'], 'Invented wall keepalive preparation')
    data = saved(root, r['actor'])
    require(data['Pos'] == [49.5, 100, 24.5] and data['XpTotal'] == 0 and len(data['Inventory']) == 1 and data['Inventory'][0]['Slot'] == 10 and data['Inventory'][0]['id'] == 'minecraft:diamond' and data['Inventory'][0]['count'] == 5, 'Saved original route state wrong')
    return dict(status='passed', version=install['version'], requested=enabled, persistent=install['persistent'], originalMoves=4, originalConfirmations=1, originalClicks=2, workerHandlers=workers, serialStatsFences=1, diamonds=5, xp=0, ownerCrossing=[0, 1], originalWallCorrections=1 if 'wall' in r else 0, scope='Bounded original packet handlers; physical join at original task; current-owner publication and exact synchronous tracking service')


if __name__ == '__main__':
    cli(verify, 'owner-routes')
