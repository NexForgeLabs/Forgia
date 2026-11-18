#!/usr/bin/env python3
"""Original ordered HTTP-filter completions at current owner, publisher broadcast and disconnect cancellation."""
from owner_proof import base, cli, lines, require, saved, window


def verify(root, installer):
    out, r, install, commands, states, raw, marks, rows, final = base(root, installer, 'owner-async')
    enabled = install['playerPlanningRequested']
    require(rows[-1].get('complete') is True, 'Incomplete original async observer')
    for key in ['before', 'movedBeforeCompletion', 'afterCompletion', 'pendingCancellation', 'afterDisconnect']:
        require(r[key] in states, 'Invented original async state ' + key)
    moved, after = r['movedBeforeCompletion'], r['afterCompletion']
    actor = next(p for p in moved['players'] if p['uuid'] == r['actor'])
    require(actor['x'] == 48.25 and actor['regionX'] == 1 and actor['ownerCurrent'], 'Async fixture did not cross owner before completion')
    require(moved['packets']['chatPending'] == (3 if enabled else 0) and after['packets']['chatPending'] == 0, 'Original ordered continuations not pending/released')
    require(r['pendingCancellation']['packets']['chatPending'] == (1 if enabled else 0) and r['afterDisconnect']['packets']['chatPending'] == 0 and r['afterDisconnect']['packets']['chatCancelled'] == (1 if enabled else 0), 'Disconnected chat continuation was not cancelled exactly once')
    require([p['name'] for p in r['afterDisconnect']['players']] == ['WorkerA'], 'Cancellation did not use real player retirement')
    filters = [row for row in lines(out / 'filter.ndjson') if row['path'] == '/v1/chat']
    texts = ['p4_async_' + str(i) for i in range(3)]
    require([f['body']['text'] for f in filters] == texts + ['p4_async_cancel'] and all(f['released'] and f['endNs'] > f['startNs'] for f in filters), 'Original HTTP filter calls/order incomplete')
    require(filters[0]['startNs'] < r['releasedAtNs'] <= filters[0]['endNs'], 'First original filter was not held across movement')
    trace = [row for row in rows[1:-1] if row['event'] != 'queue-quiescent']
    expected = []
    for text in texts:
        if enabled:
            expected.extend([('enter', text, True), ('enter', text, False), ('return', text, False), ('return', text, True)])
        else:
            expected.extend([('enter', text, False), ('return', text, False)])
    require([(x['event'], x['text'], 'Worker' in x['thread']) for x in trace] == expected, 'Original FutureChain/broadcast sequence changed, worker missing or callback duplicated')
    for row in rows[1:-1]:
        require(row['actor'] == r['actor'], 'Async old/foreign actor reached callback')
        if row['event'] == 'queue-quiescent':
            require(row['thread'] == 'Server thread' and row['readers'] == 0, 'Async admission is not original quiescent publisher')
            continue
        require(row['state'] == dict(x=48.25, y=100, z=24.5), 'Completion used old owner position')
        if row['event'] == 'enter':
            require(any('ServerGamePacketListenerImpl.broadcastChatMessage:' in f for f in row['stack']), 'Missing original chat body')
            if 'Worker' in row['thread']:
                require(any('ServerGamePacketListenerImpl.lambda$handleChat$' in f for f in row['stack']) and any('RegionPlayerPacketRouter$Call.run:' in f for f in row['stack']) and any('RegionPlayerChatCompletion.lambda$accept$' in f for f in row['stack']), 'Side task masquerades as original async consumer')
            elif enabled:
                require(any('RegionPlayerPacketRouter$Call$PublisherRequest.run:' in f for f in row['stack']) and any('RegionPlayerPacketRouter$Call.await:' in f for f in row['stack']) and any('FutureChain.lambda$append$' in f for f in row['stack']), 'Broadcast bypassed original chain/synchronous publisher service')
            else:
                require(row['thread'] == 'Server thread' and any('FutureChain.lambda$append$' in f for f in row['stack']), 'Off chat original executor changed')
    for index, text in enumerate(texts):
        block = trace[index * (4 if enabled else 2):(index + 1) * (4 if enabled else 2)]
        require(all(x['nano'] >= block[0]['nano'] for x in block) and block[0]['nano'] < block[-1]['nano'], 'Invalid original async execution interval')
    pending = window(raw, marks, 'async')
    sent = [x for x in pending if x['bot'] == 'WorkerA' and x['direction'] == 'frame_send' and x['name'] == 'chat_message']
    require([x['data']['message'] for x in sent] == texts, 'Chat did not originate from normal client wire packets')
    movements = [x for x in pending if x['bot'] == 'WorkerA' and x['direction'] == 'frame_send' and x['name'] == 'position_look']
    require([x['data']['x'] for x in movements] == [47.75, 48, 48.25] and all(int(x['nano']) < r['releasedAtNs'] for x in movements), 'Movement did not precede original filter completion')
    for name in ['WorkerA', 'WorkerB']:
        feedback = [x for x in pending if x['bot'] == name and x['direction'] == 'frame' and x['name'] == 'player_chat']
        require([x['data']['plainMessage'] for x in feedback] == texts and all(x['data']['senderUuid'] == r['actor'] and x['data']['filterType'] == 0 and int(x['nano']) >= r['releasedAtNs'] for x in feedback), 'Actual client chat output order/content wrong')
    cancel = window(raw, marks, 'cancel')
    require(sum(x['bot'] == 'WorkerB' and x['direction'] == 'frame_send' and x['name'] == 'chat_message' and x['data']['message'] == 'p4_async_cancel' for x in cancel) == 1, 'Missing normal cancellation chat input')
    require(not any(x['direction'] == 'frame' and x['name'] == 'player_chat' and x['data']['plainMessage'] == 'p4_async_cancel' for x in raw), 'Disconnected pending chat broadcast')
    meta = [m for m in after['packets']['recent'] if m['packet'] == 'ASYNC_FILTERED_CHAT' and m['started']]
    require(len(meta) == (3 if enabled else 0), 'Original async worker count wrong')
    if enabled:
        require([m['receiveSequence'] for m in meta] == [p['receiveOrdinal'] for p in sent], 'Async origin not tied to exact normal chat wire receives')
        for index, execution in enumerate(meta):
            block = trace[index * 4:(index + 1) * 4]
            require(execution['thread'] == block[0]['thread'] and execution['startNanos'] <= block[0]['nano'] < block[-1]['nano'] <= execution['returnNanos'], 'Async metadata not enclosing original callback')
        move_meta = [m for m in moved['packets']['recent'] if 'ServerboundMovePlayerPacket' in m['packet']]
        require(move_meta and [m['receiveSequence'] for m in meta] == sorted({m['receiveSequence'] for m in meta}) and max(m['receiveSequence'] for m in meta) < max(m['receiveSequence'] for m in move_meta), 'Async origin sequence/current-owner recheck not exercised')
        require(all(m['player'] == r['actor'] and m['regionX'] == m['initialRegionX'] == 1 and m['regionZ'] == 0 and m['serverSession'] == after['phases']['serverSession'] and m['identityEpoch'] > 0 and m['criterionGeneration'] > 0 for m in meta), 'Async stale ownership/lifecycle metadata')
    data = saved(root, r['actor'])
    require(data['Pos'] == [48.25, 100, 24.5] and data['XpTotal'] == 0 and data['Inventory'] == [], 'Original async saved state changed')
    return dict(status='passed', version=install['version'], requested=enabled, persistent=install['persistent'], orderedOriginalCompletions=3, workerCompletions=len(meta), currentOwner=1, protocolBroadcastsPerClient=3, disconnectedPendingBroadcasts=0, cancelledCompletions=final['packets']['chatCancelled'], scope='Original FutureChain and normal chat wire; current owner after delayed filter; exact publisher broadcast and real disconnect cancellation')


if __name__ == '__main__':
    cli(verify, 'owner-async')
