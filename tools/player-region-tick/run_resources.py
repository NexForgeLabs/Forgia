#!/usr/bin/env python3
"""Run real block/container/pickup/XP scenarios in a new isolated installation."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
from run_baseline import REPO, Rcon
from resource_scenarios import run_scenarios


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True, type=Path)
    args = parser.parse_args()
    root=args.server.resolve()
    if (root/'player-oracle.ndjson').exists() or (root/'world/level.dat').exists():
        raise FileExistsError('Use a fresh installation per suite; preserve the existing oracle trace')
    install=json.loads((root/'installation.json').read_text())
    output=root/'resource-run'; output.mkdir(exist_ok=False)
    sources=['run_resources.py','resource_scenarios.py','clients.cjs','verify_resources.py','decode_resource_frames.cjs','package-lock.json','verify_interactions.py','reviewed_artifacts.py']
    manifest={}
    for name in sources:
        path=REPO/'tools/player-region-tick'/name
        shutil.copy2(path,output/name); manifest[name]=hashlib.sha256(path.read_bytes()).hexdigest()
    (output/'inputs.json').write_text(json.dumps({'schema':4,'installation':install,'sourceHashes':manifest,
        'actualOracleSha256':hashlib.sha256((root/'mods/forgia-player-oracle-1.0.jar').read_bytes()).hexdigest()},indent=2)+'\n')
    password=re.search(r'^rcon.password=(.+)$',(root/'server.properties').read_text(),re.M)[1]
    commands=(output/'commands.ndjson').open('x'); log=(output/'server.log').open('xb')
    process=subprocess.Popen(install['launch'],cwd=root,stdout=log,stderr=subprocess.STDOUT,stdin=subprocess.PIPE)
    (output/'server.pid').write_text(str(process.pid)+'\n')
    summary={'schema':4,'status':'running','scope':'Schema 4 resource compatibility; oracle enforces serial player calls; worker=0','scenarios':[]}
    client=bots=bot_log=None; request_id=0; responses=queue.Queue()
    def command(text):
        start=time.monotonic_ns(); response=client.command(text)
        commands.write(json.dumps({'command':text,'response':response,'startNs':start,'endNs':time.monotonic_ns()})+'\n'); commands.flush()
        if any(e in response for e in ['Unknown or incomplete command','Incorrect argument','That position is not loaded','No entity was found']): raise AssertionError(response)
        return response
    def request(op,**data):
        nonlocal request_id
        request_id+=1; bots.stdin.write(json.dumps({'requestId':request_id,'op':op,**data})+'\n'); bots.stdin.flush()
        row=json.loads(responses.get(timeout=30))
        if row.get('error') or row['requestId']!=request_id: raise AssertionError(row)
        return row['result']
    try:
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            if process.poll() is not None: raise RuntimeError(f'server exited {process.returncode}')
            try: client=Rcon('127.0.0.1',install['rconPort'],password,timeout=10); break
            except OSError: time.sleep(.5)
        if client is None: raise TimeoutError('server startup timeout')
        for rule in ['doMobSpawning false','naturalRegeneration false','doWeatherCycle false','randomTickSpeed 0','spawnChunkRadius 0','keepInventory false','doImmediateRespawn false']:
            command('gamerule '+rule)
        command('difficulty normal'); command('player_oracle start')
        # Covers each side of +/-48 boundaries and corners, plus the refusal pad.
        command('forceload add -64 -64 63 63'); time.sleep(2)
        for x,z in [(48,8),(47,8),(-49,8),(-48,8),(48,48),(47,47),(-49,-49),(-48,-48)]:
            command(f'fill {x-3} 99 {z-3} {x+3} 99 {z+3} minecraft:stone')
        bot_log=(output/'clients.stderr.log').open('xb')
        bots=subprocess.Popen(['node',str(REPO/'tools/player-region-tick/clients.cjs'),str(output/'packets.ndjson')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=bot_log,text=True)
        def reader():
            with (output/'clients.stdout.log').open('x') as capture:
                for line in bots.stdout:
                    capture.write(line); capture.flush()
                    try: row=json.loads(line)
                    except json.JSONDecodeError: continue
                    if isinstance(row,dict) and 'requestId' in row: responses.put(line)
        threading.Thread(target=reader,daemon=True).start()
        ids={}
        for name,x in [('OracleA',47.0),('OracleB',49.0)]:
            ids[name]=request('connect',name=name,port=install['port'])['id']; command(f'tp {name} {x} 100 8.5'); command('clear '+name)
        time.sleep(3)
        run_scenarios(command,request,root,output,summary,ids)
        summary['diagnosticsRaw']=command('neoforge regionthreading status raw')
        if install.get('artifactManifestSha256'): command('neoforge regionthreading status players')
        request('close'); bots.wait(timeout=10); summary['clientsExit']=bots.returncode
        time.sleep(.5)
        if install.get('artifactManifestSha256'): command('neoforge regionthreading status players')
        command('player_oracle planning'); command('player_oracle stop'); summary['status']='passed'
    except BaseException as error:
        summary.update(status='failed',error=repr(error)); raise
    finally:
        if bots and bots.poll() is None:
            try: request('close')
            except Exception: bots.terminate()
            try: bots.wait(timeout=10)
            except subprocess.TimeoutExpired: bots.kill(); bots.wait()
            summary['clientsExit']=bots.returncode
        if process.poll() is None:
            try:
                if client: client.command('stop')
                else: process.stdin.write(b'stop\n'); process.stdin.flush()
                process.wait(timeout=45)
            except (OSError,subprocess.TimeoutExpired):
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        if client: client.close()
        summary['serverExit']=process.returncode
        (output/'result.json').write_text(json.dumps(summary,indent=2)+'\n'); commands.close(); log.close()
        if bot_log: bot_log.close()

if __name__=='__main__': main()
