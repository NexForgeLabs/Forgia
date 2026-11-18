"""Shared mechanical checks for preserved original P4 wire/JDI evidence; no runner assertions trusted."""
import argparse
import base64
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def load(path):
    return json.loads(Path(path).read_text())


def lines(path):
    data = path.read_bytes()
    require(data and data.endswith(b'\n'), 'Incomplete original evidence ' + str(path))
    return [json.loads(row) for row in data.splitlines()]


def base(root, installer, kind):
    out = root / kind
    result, install = load(out / 'result.json'), load(root / 'installation.json')
    require(result['status'] == 'passed' and result['serverExits'] == [0] and result['clientsExit'] == 0, 'Original runner/process failure')
    require(install['installerInputsPrivate'] and install['oracleSha256'] is None and not list((root / 'mods').glob('*.jar')), 'Unexpected observer Mod or unsafe installation')
    require(result['installation'] == dict(install, launch=result['actualLaunch']), 'Original installation/launch mismatch')
    require(hashlib.sha256(installer.read_bytes()).hexdigest() == install['installerSha256'], 'Wrong installer')
    relative = f'net/neoforged/neoforge/{install["version"]}/neoforge-{install["version"]}-universal.jar'
    with zipfile.ZipFile(installer) as z:
        require((root / 'libraries' / relative).read_bytes() == z.read('maven/' + relative), 'Wrong installed universal')
    require(any(x.startswith('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:') for x in result['actualLaunch']) and not any(x.startswith('-javaagent:') for x in result['actualLaunch']), 'Observer is not external original JDI')
    for name, digest in result['sources'].items():
        require(hashlib.sha256((out / 'source-snapshot' / name).read_bytes()).hexdigest() == digest, 'Changed source snapshot ' + name)
    commands = lines(out / 'commands.ndjson')
    states = [json.loads(row['response']) for row in commands if row['command'] == 'neoforge regionthreading status players']
    for state in states:
        require(state['requested'] == install['playerPlanningRequested'] and state['persistent'] == install['persistent'], 'Wrong original mode')
        require(state['activeBatches'] == state['stateClaims'] == state['phases']['pending'] == state['phases']['running'] == 0, 'Unjoined work or claims')
        require(all(p['ownerCurrent'] for p in state['players']) and all(p['failed'] == 0 for p in state['phases']['phases'].values()), 'Stale owner or failed original tick')
        require(state['packets']['failed'] == 0, 'Original owner handler failed')
    final = load(out / 'final.json')
    require(final in states and not final['players'] and final['phases']['owners'] == 0, 'Logout missing or retained player owners')
    require(not final['network']['frozen'] and final['network']['pending'] == final['network']['readers'] == final['network']['terminals'] == 0, 'Retained network gate')
    require(final['packets']['connections'] == final['packets']['chatPending'] == final['packets']['tasks']['pending'] == final['packets']['tasks']['terminals'] == 0, 'Retained packet/receipt/chat references')
    packets = lines(out / 'packets.ndjson')
    require([r['seq'] for r in packets] == list(range(1, len(packets) + 1)), 'Original wire sequence missing/reordered')
    for row in packets:
        if row['direction'] in ['frame', 'frame_send']:
            value = base64.b64decode(row['data']['base64'], validate=True)
            require(len(value) == row['data']['bytes'] and hashlib.sha256(value).hexdigest() == row['data']['sha256'], 'Original raw frame checksum/length mismatch')
    raw = [json.loads(x) for x in subprocess.check_output(['node', str(TOOLS / 'decode_owner_routes.cjs'), str(out / 'packets.ndjson')], text=True).splitlines()]
    # channelRead0 counts all original protocol arrivals, including handshake/login/configuration.
    # Join execution origin metadata to actual serialized frames, not a runner-provided sequence.
    counts, ordinals = {}, {}
    for row in packets:
        if row['direction'] == 'frame_send':
            counts[row['bot']] = counts.get(row['bot'], 0) + 1
            ordinals[row['seq']] = counts[row['bot']]
    for row in raw:
        if row['direction'] == 'frame_send':
            row['receiveOrdinal'] = ordinals[row['seq']]
    marks = {r['name']: r['seq'] for r in packets if r['direction'] == 'control'}
    rows = lines(out / 'jdi.ndjson')
    require(rows[0]['event'] == 'armed' and rows[0]['actor'] == result['actor'] and rows[-1]['event'] == 'observed', 'Original observer did not finish')
    require([r['seq'] for r in rows] == list(range(1, len(rows) + 1)), 'Original observer sequence missing/reordered')
    require(not any(x in (out / 'boot-1.server.log').read_text() for x in ['Failed to handle packet', 'Caused by:', 'Error executing task']), 'Original server failure')
    require(load(out / 'debugger/result.json')['exit'] == 0, 'Observer wrapper did not exit successfully')
    return out, result, install, commands, states, raw, marks, rows, final


def window(raw, marks, label):
    return [r for r in raw if marks[label + '_before'] < r['seq'] < marks[label + '_after']]


def saved(root, actor):
    return json.loads(subprocess.check_output(['node', str(TOOLS / 'read_saved_nbt.cjs'), str(root / 'world/playerdata' / f'{actor}.dat')], text=True))


def cli(verify, kind):
    p = argparse.ArgumentParser()
    p.add_argument('--server', type=Path, required=True)
    p.add_argument('--installer', type=Path, required=True)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    out = a.output or a.server / kind / 'independent-verdict.json'
    require(not out.exists(), 'Preserve previous verdict')
    try:
        result = verify(a.server.resolve(), a.installer.resolve())
    except BaseException as error:
        out.write_text(json.dumps(dict(status='failed', error=repr(error)), indent=2) + '\n')
        raise
    out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
