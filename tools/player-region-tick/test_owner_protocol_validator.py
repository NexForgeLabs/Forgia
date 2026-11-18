#!/usr/bin/env python3
"""Tamper controls for full original P4 owner proofs; never mutate preserved source corpus."""
import argparse
import json
import tempfile
from pathlib import Path
from verify_owner_routes import verify as routes
from verify_owner_async import verify as asynchronous
from verify_packet_queue import verify as queue


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server', type=Path, required=True)
    p.add_argument('--installer', type=Path, required=True)
    p.add_argument('--kind', choices=['routes', 'async', 'queue'], required=True)
    a = p.parse_args()
    source, installer = a.server.resolve(), a.installer.resolve()
    verify, folder = {'routes': (routes, 'owner-routes'), 'async': (asynchronous, 'owner-async'), 'queue': (queue, 'packet-queue')}[a.kind]
    assert verify(source, installer)['requested']
    cases = []

    def case(name, file, mutate):
        cases.append((name, file, mutate))

    def first(rows, condition):
        return next(row for row in rows if condition(row))

    case('truncated_original_sequence', 'jdi.ndjson', lambda rows: rows.pop(2))
    case('false_process_exit', 'result.json', lambda r: r.update(serverExits=[1]))
    case('raw_frame_checksum', 'packets.ndjson', lambda rows: first(rows, lambda r: r['direction'] == 'frame_send')['data'].update(sha256='0' * 64))
    case('retained_packet_after_logout', 'final.json', lambda r: r['packets']['tasks'].update(pending=1))
    case('retained_chat_after_logout', 'final.json', lambda r: r['packets'].update(chatPending=1))
    if a.kind in ['routes', 'async']:
        case('forged_original_receive_ordinal', 'result.json', lambda r: r['after' if a.kind == 'routes' else 'afterCompletion']['packets']['recent'][-1].update(receiveSequence=1))
    if a.kind == 'routes':
        case('owner_worker_missing', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter').update(thread='Server thread'))
        if (source / folder / 'jdi-wall.ndjson').exists():
            case('wall_began_on_worker', 'jdi-wall.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter').update(thread='NeoForge Region Worker #forged'))
            case('wall_not_refused_before_effects', 'jdi-wall.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'capture').update(refusal='NONE'))
            case('wall_wrong_server_correction', 'result.json', lambda r: r['wall']['after']['players'][0].update(x=49.75))
        case('stale_move_effect', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'return')['state'].update(x=47.5))
        case('tracking_service_wrong_executor', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'tracking' and r['thread'] == 'Server thread').update(thread='Foreign thread'))
        case('inventory_duplicate', 'result.json', lambda r: r.update(inventoryAfter=r['inventoryAfter'].replace('count: 5', 'count: 6')))
        case('stale_owner_metadata', 'result.json', lambda r: r['windows'][1]['after']['players'][0].update(regionX=0))
        case('original_handler_absent', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter').update(stack=[]))
    elif a.kind == 'async':
        case('owner_worker_missing', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter').update(thread='Server thread'))
        case('old_owner_callback', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter')['state'].update(x=47.5))
        case('ordered_consumer_reordered', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter').update(text='p4_async_2'))
        case('unheld_original_filter', 'filter.ndjson', lambda rows: first(rows, lambda r: r['path'] == '/v1/chat').update(startNs=10**19))
        case('missing_real_cancellation', 'result.json', lambda r: r['afterDisconnect']['packets'].update(chatCancelled=0))
        case('original_consumer_absent', 'jdi.ndjson', lambda rows: first(rows, lambda r: r['event'] == 'enter').update(stack=[]))
    else:
        case('unbounded_pending', 'jdi.ndjson', lambda rows: rows[2].update(pending=4097))
        case('terminal_overtook_prefix', 'jdi.ndjson', lambda rows: rows[3].update(pending=1))
        case('terminal_reopened_early', 'jdi.ndjson', lambda rows: rows[3].update(terminals=0))
        case('terminal_escaped_executor', 'jdi.ndjson', lambda rows: rows[3].update(thread='Foreign thread'))
        case('duplicate_overflow', 'result.json', lambda r: r['after']['packets']['tasks'].update(overflows=2))
    controls = []
    for name, file, mutate in cases:
        with tempfile.TemporaryDirectory(prefix='forgia-p4-proof-') as temporary:
            root = Path(temporary)
            for f in source.iterdir():
                if f.name != folder:
                    (root / f.name).symlink_to(f, target_is_directory=f.is_dir())
            target = root / folder
            target.mkdir()
            for f in (source / folder).iterdir():
                (target / f.name).symlink_to(f, target_is_directory=f.is_dir())
            path = target / file
            data = [json.loads(x) for x in path.read_text().splitlines()] if file.endswith('.ndjson') else json.loads(path.read_text())
            mutate(data)
            path.unlink()
            path.write_text(''.join(json.dumps(x) + '\n' for x in data) if file.endswith('.ndjson') else json.dumps(data) + '\n')
            try:
                verify(root, installer)
            except AssertionError as error:
                controls.append(dict(control=name, status='rejected', reason=str(error)))
            else:
                raise AssertionError('Tampered proof accepted ' + name)
    print(json.dumps(dict(status='passed', negativeControls=len(controls), controls=controls), indent=2))


if __name__ == '__main__':
    main()
