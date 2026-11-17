#!/usr/bin/env python3
"""P2 original eligibility, serial callback contract, async chat and End/credits TCP corpus.

Unknown test extensions always remain serial. No owner routing or wider gameplay
admission is added. Commands prepare terrain/read state; measured actions use TCP.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import shutil
import threading
import time
from run_worker_lifecycle import Session

TOOLS = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--contract-mod', type=Path)
    args = parser.parse_args()
    root = args.server.resolve()
    if args.contract_mod:
        assert not list((root/'mods').glob('*.jar'))
        shutil.copy2(args.contract_mod, root/'mods'/args.contract_mod.name)
    session = Session(root, 'p2-matrix')
    sources = {}
    for name in ['run_p2_matrix.py', 'lifecycle_clients.cjs', 'run_worker_lifecycle.py', 'package-lock.json']:
        shutil.copy2(TOOLS/name, session.output/name)
        sources[name] = hashlib.sha256((TOOLS/name).read_bytes()).hexdigest()
    http_log = (session.output/'filter.ndjson').open('x')
    log_lock = threading.Lock()
    class Filter(BaseHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            start = time.monotonic_ns()
            if self.path.endswith('/chat'): time.sleep(.15 if body.get('text', '').endswith('0') else .04)
            data = b'{"response":true}'
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            with log_lock:
                http_log.write(json.dumps({'path':self.path,'body':body,'startNs':start,'endNs':time.monotonic_ns()})+'\n');http_log.flush()
    http = ThreadingHTTPServer(('127.0.0.1', 0), Filter)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    properties = root/'server.properties'
    config = {'apiServer':f'http://127.0.0.1:{http.server_port}', 'apiKey':'isolated-p2-local-only', 'serverId':'p2'}
    with properties.open('a') as stream: stream.write('text-filtering-config='+json.dumps(config,separators=(',',':'))+'\n')
    result = {'status':'running','installation':session.install,'sources':sources,'contractModSha256':hashlib.sha256(args.contract_mod.read_bytes()).hexdigest() if args.contract_mod else None,'windows':{}}
    def client(text):
        session.request('chat',name='WorkerA',text='/'+text)
        time.sleep(.35)
    def payload(text):
        session.request('payload',name='WorkerA',text=text)
        time.sleep(.4)
    def position():
        row=session.request('snapshot')['WorkerA']['position'];return [row[k] for k in ['x','y','z']]
    def move(target):
        start=position(); steps=max(1,math.ceil(math.dist(start,target)/.25))
        assert steps < 100, (start,target)
        for i in range(1,steps+1):
            point=[a+(b-a)*i/steps for a,b in zip(start,target)]
            session.request('packet',name='WorkerA',packet='position_look',data=dict(zip(['x','y','z'],point),yaw=0,pitch=0,onGround=True))
            time.sleep(.06)
    def window(name):
        session.request('mark',label=name+'_before');a=session.snapshot(name+'-before');time.sleep(.8);b=session.snapshot(name+'-after');session.request('mark',label=name+'_after')
        result['windows'][name]={'before':a,'after':b}
    def dimension(wanted):
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            read=session.command('data get entity WorkerA Dimension')
            if '"'+wanted+'"' in read: time.sleep(.5);return read
            time.sleep(.3)
        raise AssertionError('dimension transition missing: '+wanted)
    try:
        session.start()
        session.rcon.sock.settimeout(120)
        for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false','doImmediateRespawn false']:
            session.command('gamerule '+rule)
        session.command('forceload add 0 0 143 47');time.sleep(1)
        for x in [24,120]: session.command(f'fill {x-8} 99 16 {x+8} 99 32 minecraft:stone')
        for name in ['WorkerA','WorkerB']:session.request('connect',name=name,port=session.install['port'])
        session.command('op WorkerA');session.command('advancement grant @a everything');session.command('clear @a');session.command('experience add @a -1000000 points');session.command('experience set @a 0 levels');session.command('experience set @a 0 points')
        session.command('spawnpoint WorkerA 24 100 24');session.command('tp WorkerA 24.5 100 24.5');session.command('tp WorkerB 120.5 100 24.5');time.sleep(3)
        window('stable')
        # Freeze/step are ordinary operator command packets. Players retain vanilla frozen eligibility.
        client('tick freeze');window('frozen');client('tick step 3');window('stepped');client('tick unfreeze')
        client('gamemode spectator');window('spectator');client('gamemode survival')
        session.command('summon minecraft:boat 24.5 100 24.5 {Tags:["p2_boat"],NoGravity:1b}')
        client('ride @s mount @e[tag=p2_boat,limit=1]');
        session.request('packet',name='WorkerA',packet='steer_vehicle',data={'sideways':0,'forward':0,'jump':0});window('riding')
        client('ride @s dismount');session.command('kill @e[tag=p2_boat]');client('tp @s 24.5 100 24.5')
        # Both same-region and cross-region teleports, each using the ordinary command protocol.
        session.request('mark',label='teleports_before')
        client('tp @s 28.5 100 24.5');session.snapshot('same-region-teleport')
        client('tp @s 120.5 100 27.5');session.snapshot('cross-region-teleport')
        client('tp @s 24.5 100 24.5');session.request('mark',label='teleports_after');window('after-teleports')
        session.request('mark',label='async_before')
        for i in range(5):
            session.request('network_sequence',name='WorkerA',text=f'p2_async_{i}',id=24000+i)
            session.request('packet',name='WorkerA',packet='position_look',data={'x':24.5+i*.05,'y':100,'z':24.5,'yaw':0,'pitch':0,'onGround':True})
            if args.contract_mod:session.request('payload',name='WorkerA',text='mixed_'+str(i))
            time.sleep(.12)
        time.sleep(2);session.request('mark',label='async_after');window('after-async')
        if args.contract_mod:
            payload('cancel_tp');session.request('mark',label='cancel_tp_before');client('tp @s 120.5 100 24.5');session.snapshot('cancelled-teleport');session.request('mark',label='cancel_tp_after')
            payload('reconfigure');time.sleep(1);session.snapshot('in-configuration');
            session.command('p2_return_config');session.request('wait_play',name='WorkerA');time.sleep(2);session.snapshot('after-reconfigure');window('reconfigured')
            # Reconfiguration replaces the player/listener through the original vanilla protocol.
            payload('after_reconfigure')
        client('tp @s 24.5 100 24.5')
        # Prepare an ordinary End portal, a safe arrival area, and its exit. No command changes dimension.
        for text in ['execute in minecraft:the_end run forceload add 80 -16 127 31',
                     'execute in minecraft:the_end run fill 96 49 -3 108 49 3 minecraft:stone',
                     'execute in minecraft:the_end run fill 96 50 -3 108 54 3 minecraft:air',
                     'execute in minecraft:the_end run setblock 104 50 0 minecraft:end_portal',
                     'setblock 27 100 24 minecraft:end_portal']:
            session.command(text)
        if args.contract_mod:
            payload('cancel_dimension');session.request('mark',label='cancel_dimension_before');move([27.5,100,24.5]);time.sleep(1)
            result['cancelledDimensionRead']=session.command('data get entity WorkerA Dimension');session.snapshot('cancelled-dimension');session.request('mark',label='cancel_dimension_after')
            assert 'minecraft:overworld' in result['cancelledDimensionRead']
            move([24.5,100,24.5]);time.sleep(1)
        session.request('mark',label='end_before');move([27.5,100,24.5]);result['endOutwardRead']=dimension('minecraft:the_end');session.snapshot('in-end')
        # Vanilla rebuilds its five-by-five arrival platform. Place the exit fixture afterwards,
        # at the actual arrival Y, so walking does not collide with the prebuilt surrounding floor.
        times=set();deadline=time.monotonic()+30
        while len(times)<5 and time.monotonic()<deadline:
            times.add(session.command('execute in minecraft:the_end run time query gametime').strip());time.sleep(.15)
        assert len(times)>=5, 'Original End world did not advance beyond the dimension transfer'
        result['endWorldAdvanceReads']=sorted(times)
        here=position()
        session.command(f'execute in minecraft:the_end run setblock 102 {int(here[1])} 0 minecraft:end_portal')
        result['exitFixtureRead']=session.command(f'execute in minecraft:the_end if block 102 {int(here[1])} 0 minecraft:end_portal run time query gametime')
        assert 'The time is' in result['exitFixtureRead'], result['exitFixtureRead']
        session.request('mark',label='exit_portal_before');move([102.5,here[1],.5]);time.sleep(1)
        session.request('wait_credits',name='WorkerA')
        session.snapshot('credits');window('credits-window');session.request('mark',label='credits_ready')
        session.request('packet',name='WorkerA',packet='client_command',data={'actionId':0});result['endReturnRead']=dimension('minecraft:overworld');session.snapshot('after-credits-respawn');time.sleep(.5)
        here=position();move([here[0],100,here[2]])
        session.request('mark',label='end_after');window('end-returned')
        session.resources('before-save');session.command('save-all flush')
        session.request('close');session.bots.wait(timeout=10);time.sleep(.5);session.snapshot('final')
        session.stop();result['status']='passed'
    except BaseException as error:
        result.update(status='failed',error=repr(error));raise
    finally:
        try: session.close()
        except BaseException as cleanup:
            result['cleanupError']=repr(cleanup)
        http.shutdown();http.server_close();http_log.close()
        result.update(serverExits=session.exits,clientsExit=session.bots.returncode)
        (session.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    assert result['serverExits']==[0] and result['clientsExit']==0,result
    print(json.dumps({'status':result['status'],'contractMod':bool(args.contract_mod),'mode':[session.install['persistent'],session.install['playerPlanningRequested']]}))


if __name__=='__main__':main()
