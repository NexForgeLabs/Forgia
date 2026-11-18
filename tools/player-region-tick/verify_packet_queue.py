#!/usr/bin/env python3
"""Actual normal-wire overflow, original accepted prefix drain, protocol terminal and real retirement."""
import json
from owner_proof import base, cli, require, saved, window


def verify(root, installer):
    out, r, install, commands, states, raw, marks, rows, final = base(root, installer, 'packet-queue')
    require(install['playerPlanningRequested'], 'Queue pressure requires enabled original bounded task protocol')
    require([x['event'] for x in rows] == ['armed', 'paused', 'bounded', 'drained', 'observed'] and rows[-1]['bounded'], 'Original bounded-prefix/terminal trace incomplete')
    require(rows[1]['pending'] == 0 and rows[2]['pending'] == 4096 and rows[2]['terminals'] == 1 and rows[2]['frozen'] is False, 'Original queue did not reach exact open-receive bound')
    require(rows[3]['pending'] == 0 and rows[3]['terminals'] == 1, 'Protocol terminal overtook accepted prefix or reopened admission')
    for row in rows[1:-1]:
        require(row['thread'] == 'Server thread' and any('RegionPlayerPacketTasks$Task.run:' in f for f in row['stack']), 'Pressure/terminal not at original PacketUtils executor position')
    require(any('RegionPlayerPacketTasks.disconnectOverflow:' in f for f in rows[3]['stack']), 'Terminal is not original synchronous disconnect')
    for key in ['before', 'after']:
        require(r[key] in states, 'Invented original pressure diagnostic')
    before, after = r['before']['packets'], r['after']['packets']
    require(before['tasks']['overflows'] == 0 and after['tasks']['overflows'] == final['packets']['tasks']['overflows'] == 1, 'Overflow terminal missing/duplicated')
    require(after['tasks']['pending'] == after['tasks']['terminals'] == 0 and [x['name'] for x in r['after']['players']] == ['WorkerB'], 'Actual overflow player not retired')
    packets = window(raw, marks, 'queue')
    outgoing = [x for x in packets if x['bot'] == 'WorkerA' and x['direction'] == 'frame_send' and x['name'] == 'held_item_slot']
    require(len(outgoing) == 4201 and all(x['data'] == dict(slotId=0) for x in outgoing), 'Queue pressure not actual 4201 normal held-slot packets')
    kicked = [x for x in packets if x['bot'] == 'WorkerA' and x['direction'] == 'frame' and x['name'] == 'kick_disconnect']
    require(len(kicked) == 1 and 'Player packet queue limit exceeded' in json.dumps(kicked[0]['data']), 'Missing/duplicate explicit protocol overflow reason')
    require(kicked[0]['seq'] > outgoing[-1]['seq'], 'Protocol terminal was observed before sent pressure prefix')
    data = saved(root, r['actor'])
    require(data['SelectedItemSlot'] == 0 and data['XpTotal'] == 0 and len(data['Inventory']) == 1 and data['Inventory'][0]['id'] == 'minecraft:iron_sword' and data['Inventory'][0]['count'] == 1, 'Original overflow save lost/duplicated state')
    return dict(status='passed', version=install['version'], requested=True, persistent=install['persistent'], actualNormalPackets=4201, pendingBound=4096, originalTerminals=1, pendingAtTerminal=0, finalPending=0, finalTerminals=0, scope='Original PacketUtils queue bound and full accepted prefix drained before explicit protocol terminal; actual disconnect retirement')


if __name__ == '__main__':
    cli(verify, 'packet-queue')
