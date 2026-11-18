#!/usr/bin/env python3
"""Original HTTP-filter futures follow the player's current owner; original chat order/global broadcast remain synchronous."""
import argparse,hashlib,json,shutil,socket,subprocess,time,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from run_worker_lifecycle import Session
from proof_events import read_complete_events
TOOLS=Path(__file__).resolve().parent;REPO=TOOLS.parents[1];JAVA='/home/yc/.local/opt/jdk-21.0.13+9/bin/java'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',type=Path,required=True);a=p.parse_args();root=a.server.resolve();s=Session(root,'owner-async');r=dict(status='running',sources={});debug=None;release=threading.Event();cancelRelease=threading.Event();entered=threading.Event();cancelEntered=threading.Event();log=(s.output/'filter.ndjson').open('x');lock=threading.Lock()
 class Filter(BaseHTTPRequestHandler):
  def log_message(self,*unused):pass
  def do_POST(self):
   body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));start=time.monotonic_ns();text=body.get('text','');released=True
   if self.path.endswith('/chat'):
    if text=='p4_async_0':entered.set();released=release.wait(15)
    elif text=='p4_async_cancel':cancelEntered.set();released=cancelRelease.wait(15)
    else:time.sleep(.04)
   data=b'{"response":true}';self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
   with lock:log.write(json.dumps(dict(path=self.path,body=body,startNs=start,endNs=time.monotonic_ns(),released=released))+'\n');log.flush()
 http=ThreadingHTTPServer(('127.0.0.1',0),Filter);threading.Thread(target=http.serve_forever,daemon=True).start()
 with (root/'server.properties').open('a') as f:f.write('text-filtering-config='+json.dumps(dict(apiServer=f'http://127.0.0.1:{http.server_port}',apiKey='isolated-p4',serverId='p4'),separators=(',',':'))+'\n')
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 s.install['launch'].insert(1,f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}');r.update(installation=s.install,actualLaunch=s.install['launch'])
 try:
  files=[Path(__file__).resolve(),TOOLS/'jdi/PlayerOwnerAsync.java']+list((REPO/'src/main/java/net/neoforged/neoforge/server/threading/region').glob('RegionPlayer*.java'))+[REPO/'patches/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch']
  for f in files:
   relative=f.relative_to(REPO);q=s.output/'source-snapshot'/relative;q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,q);r['sources'][str(relative)]=hashlib.sha256(f.read_bytes()).hexdigest()
  s.start()
  for rule in ['doMobSpawning false','naturalRegeneration false','randomTickSpeed 0','spawnChunkRadius 0','doWeatherCycle false']:s.command('gamerule '+rule)
  s.command('forceload add 0 0 143 47');time.sleep(2)
  for x in [48,120]:s.command(f'fill {x-10} 99 16 {x+10} 99 32 minecraft:stone')
  for name in ['WorkerA','WorkerB']:s.request('connect',name=name,port=s.install['port'])
  s.command('advancement grant @a everything');s.command('clear @a');s.command('experience add @a -1000000 points');s.command('experience set @a 0 levels');s.command('experience set @a 0 points');s.command('tp WorkerA 47.5 100 24.5');s.command('tp WorkerB 120.5 100 24.5');time.sleep(3)
  r['before']=s.snapshot('before');r['actor']=next(x['uuid'] for x in r['before']['players'] if x['name']=='WorkerA');trace=s.output/'jdi.ndjson'
  debug=subprocess.Popen(['python3',str(TOOLS/'run_command.py'),'--output',str(s.output/'debugger'),'--',JAVA,'--add-modules','jdk.jdi',str(TOOLS/'jdi/PlayerOwnerAsync.java'),str(port),str(trace),r['actor'],str(s.output/'done')])
  until=time.monotonic()+25
  while time.monotonic()<until:
   if any(x['event']=='armed' for x in read_complete_events(trace)):break
   if debug.poll() is not None:raise AssertionError('Async observer exited before arm')
   time.sleep(.1)
  else:raise TimeoutError('Async observer did not arm')
  s.request('mark',label='async_before')
  for i in range(3):s.request('chat',name='WorkerA',text='p4_async_'+str(i))
  assert entered.wait(5),'Original HTTP filter did not receive first chat'
  for x in [47.75,48,48.25]:s.request('packet',name='WorkerA',packet='position_look',data=dict(x=x,y=100,z=24.5,yaw=0,pitch=0,onGround=True));time.sleep(.2)
  r['movedBeforeCompletion']=s.snapshot('moved-before-completion');r['releasedAtNs']=time.monotonic_ns();release.set();time.sleep(1.5);r['afterCompletion']=s.snapshot('after-completion');s.request('mark',label='async_after')
  s.request('mark',label='cancel_before');s.request('chat',name='WorkerB',text='p4_async_cancel');assert cancelEntered.wait(5),'Original cancellation filter did not receive chat';r['pendingCancellation']=s.snapshot('pending-cancellation');s.request('disconnect',name='WorkerB');time.sleep(.5);r['afterDisconnect']=s.snapshot('after-disconnect');cancelRelease.set();time.sleep(.5);s.request('mark',label='cancel_after')
  (s.output/'done').write_text('original futures and disconnect completed\n');debug.wait(timeout=15);assert debug.returncode==0;debug=None
  s.command('save-all flush');s.request('close');s.bots.wait(timeout=10);time.sleep(.5);s.snapshot('final');s.stop();r['status']='passed'
 except BaseException as error:r.update(status='failed',error=repr(error));raise
 finally:
  release.set();cancelRelease.set()
  if debug and debug.poll() is None:debug.terminate();debug.wait(timeout=10)
  s.close();http.shutdown();http.server_close();log.close();r.update(serverExits=s.exits,clientsExit=s.bots.returncode);(s.output/'result.json').write_text(json.dumps(r,indent=2)+'\n')
 assert r['serverExits']==[0] and r['clientsExit']==0
if __name__=='__main__':main()
