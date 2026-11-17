"""Schema-4 scenarios: fixture writes outside measured windows; game actions inside are TCP packets."""
import json
import math
import time

GEOMETRIES={
    'positive_x':((47.0,8.5),(49.0,8.5)),
    'negative_x':((-49.0,8.5),(-47.0,8.5)),
    'positive_corner':((47.2,47.2),(48.8,48.8)),
    'negative_corner':((-48.8,-48.8),(-47.2,-47.2)),
}


def run_scenarios(command,request,root,output,summary,ids):
    sequence=0
    watched=(49,100,8)
    def packet(name,kind,data): return request('packet',name=name,packet=kind,data=data)
    def move(name,x,z): packet(name,'position_look',{'x':x,'y':100.0,'z':z,'yaw':0,'pitch':0,'onGround':True})
    def position(name,point): command(f'tp {name} {point[0]:.3f} 100.0 {point[1]:.3f}')
    def watch(pos):
        nonlocal watched
        watched=pos; command('resource_oracle watch '+' '.join(map(str,pos)))
    def close():
        state=request('snapshot')
        for name in ['OracleA','OracleB']:
            packet(name,'close_window',{'windowId':state[name].get('windowId') or 0})
        time.sleep(.1)
    def clean():
        close(); command('resource_oracle cancel false')
        for name in ['OracleA','OracleB']: command('clear '+name)
        for kind in ['item','experience_orb']:
            command(f'execute if entity @e[type=minecraft:{kind}] run kill @e[type=minecraft:{kind}]')
    def setup(actor,origin,destination,block):
        clean(); position(actor,origin)
        x,z=math.floor(destination[0]),math.floor(destination[1])
        centre=x+.5
        interior=centre+(1.7 if centre%48<24 else -1.7)
        position('OracleB' if actor=='OracleA' else 'OracleA',(interior,z+.5))
        watch((x,100,z)); command(f'setblock {x} 100 {z} {block}')
        time.sleep(.3)
    def read(chest=False):
        result={}
        for name in ['OracleA','OracleB']:
            result[name]={'inventory':command(f'data get entity {name} Inventory'),'health':command(f'data get entity {name} Health'),
                          'level':command(f'experience query {name} levels'),'points':command(f'experience query {name} points')}
        pos=' '.join(map(str,watched))
        result['blocks']={b:command(f'execute if block {pos} minecraft:{b}') for b in ['air','dirt','chest']}
        if chest: result['container']=command('data get block '+pos+' Items')
        return result
    def window(label,kind,actors,action,chest=False,delay=.5):
        command('player_oracle mark '+label+'_before'); request('mark',label=label+'_before')
        before=read(chest); action(); time.sleep(delay); after=read(chest)
        request('mark',label=label+'_after'); command('player_oracle mark '+label+'_after')
        summary['scenarios'].append({'label':label,'kind':kind,'actors':actors,'blockPosition':watched,'beforeReads':before,'afterReads':after})
        (output/'progress.json').write_text(json.dumps(summary,indent=2)+'\n')
    def dig(actor):
        nonlocal sequence
        sequence+=1; packet(actor,'block_dig',{'status':0,'location':dict(zip(['x','y','z'],watched)),'face':1,'sequence':sequence})
        time.sleep(1.0)
        sequence+=1; packet(actor,'block_dig',{'status':2,'location':dict(zip(['x','y','z'],watched)),'face':1,'sequence':sequence})
    def use(actor,support=False):
        nonlocal sequence
        sequence+=1; x,y,z=watched
        packet(actor,'block_place',{'hand':0,'location':{'x':x,'y':y-1 if support else y,'z':z},'direction':1,
            'cursorX':.5,'cursorY':1.0,'cursorZ':.5,'insideBlock':False,'sequence':sequence})
    def quick_move(names):
        request('quick_move',names=names)
        time.sleep(.15)
        # A correctly predicted click need not receive changed-slot packets. Reopen using
        # ordinary close/use packets to obtain an authoritative full inventory observation.
        state=request('snapshot')
        for name in names:
            packet(name,'close_window',{'windowId':state[name]['windowId']})
            use(name)
    for geometry,(a,b) in GEOMETRIES.items():
        for actor,origin,destination in [('OracleA',a,b),('OracleB',b,a)]:
            prefix=geometry+'_'+actor[-1]
            setup(actor,origin,destination,'minecraft:dirt')
            # Observe after the original drop's pickup delay and bounded item motion settle.
            # Keep all original state/event/client/conservation checks; never replay the action.
            window('break_'+prefix,'break',[actor],lambda:dig(actor),delay=2.0)
            setup(actor,origin,destination,'minecraft:air')
            command(f'item replace entity {actor} weapon.mainhand with minecraft:dirt 2')
            window('place_'+prefix,'place',[actor],lambda:use(actor,True))
            setup(actor,origin,destination,'minecraft:chest')
            command('item replace block '+' '.join(map(str,watched))+' container.0 with minecraft:diamond 11')
            window('open_'+prefix,'open',[actor],lambda:use(actor),chest=True)
            window('click_'+prefix,'click',[actor],lambda:quick_move([actor]),chest=True)
    for kind in ['break','place']:
        for mode in ['cancel','far']:
            actor='OracleA'; origin=(47.0,8.5) if mode=='cancel' else (36.5,8.5)
            setup(actor,origin,(49.0,8.5),'minecraft:dirt' if kind=='break' else 'minecraft:air')
            if kind=='place': command('item replace entity OracleA weapon.mainhand with minecraft:dirt 2')
            if mode=='cancel': command('resource_oracle cancel true')
            window(kind+'_'+mode,kind+'_'+mode,[actor],lambda:dig(actor) if kind=='break' else use(actor,True))
    for geometry in ['positive_x','negative_corner']:
        a,b=GEOMETRIES[geometry]; setup('OracleA',a,b,'minecraft:chest'); position('OracleB',b)
        command('item replace block '+' '.join(map(str,watched))+' container.0 with minecraft:diamond 11')
        window('shared_open_'+geometry,'shared_open',['OracleA','OracleB'],lambda:(use('OracleA'),use('OracleB')),chest=True)
        window('shared_race_'+geometry,'shared_race',['OracleA','OracleB'],lambda:quick_move(['OracleA','OracleB']),chest=True)
    for kind in ['item','xp']:
        clean(); watch((48,100,8))
        command('fill 35 100 -8 63 103 24 minecraft:air'); command('fill 35 99 -8 63 99 24 minecraft:stone')
        position('OracleA',(46.0,8.5)); position('OracleB',(50.0,8.5))
        for name in ['OracleA','OracleB']:
            command('experience set '+name+' 0 levels'); command('experience set '+name+' 0 points')
        time.sleep(.4)  # let the original connection reset/teleport confirmation finish before movement
        if kind=='item': command('summon minecraft:item 48.2 100.0 8.5 {Tags:["resource_fixture"],NoGravity:1b,PickupDelay:0s,Item:{id:"minecraft:diamond",count:5}}')
        else: command('summon minecraft:experience_orb 48.2 100.0 8.5 {Tags:["resource_fixture"],NoGravity:1b,Value:7s,Count:1}')
        def race():
            request('batch',actions=[{'name':name,'packet':'position_look','data':{'x':x,'y':100.0,'z':8.5,'yaw':0,'pitch':0,'onGround':True}} for name,x in [('OracleA',47.7),('OracleB',48.3)]])
        window(kind+'_race',kind+'_race',['OracleA','OracleB'],race,delay=1.0)
    clean(); watch((49,100,8))
    command('fill 35 100 -8 63 103 24 minecraft:air'); command('fill 35 99 -8 63 99 24 minecraft:stone')
    position('OracleA',(49.0,8.5)); position('OracleB',(47.0,8.5))
    command('spawnpoint OracleA 63 100 8')
    command('item replace entity OracleA weapon.mainhand with minecraft:diamond 5')
    command('item replace entity OracleB weapon.mainhand with minecraft:netherite_sword 1')
    command('effect give OracleB minecraft:strength 30 20 true')
    command('experience set OracleA 1 levels'); command('experience set OracleA 0 points')
    command('experience set OracleB 0 levels'); command('experience set OracleB 0 points')
    time.sleep(.4)
    def death():
        packet('OracleB','use_entity',{'target':ids['OracleA'],'mouse':1,'sneaking':False})
        time.sleep(.1); move('OracleB',37.5,8.5)
    window('death_drop','death',['OracleB'],death,delay=.3)
    window('death_respawn','respawn',['OracleA'],lambda:packet('OracleA','client_command',{'actionId':0}),delay=.6)
    def recover():
        # Read actual drops, then approach them with ordinary bounded movement packets.
        trace=[json.loads(line) for line in (root/'player-oracle.ndjson').read_text().split('\n')[:-1]]
        start=next(i for i in range(len(trace)-1,-1,-1) if trace[i]['kind']=='MARK')
        resources=[r for r in trace[start+1:] if r['kind']=='RESOURCE_STATE']
        current=(37.5,8.5)
        for target in sorted(resources,key=lambda r: 0 if r['type']=='minecraft:item' else 1):
            destination=(target['x'],target['z'])
            steps=max(1,math.ceil(math.dist(current,destination)/.2))
            for step in range(1,steps+1):
                move('OracleB',current[0]+(destination[0]-current[0])*step/steps,current[1]+(destination[1]-current[1])*step/steps); time.sleep(.05)
            current=destination
        time.sleep(.4)
    window('death_recover','recover',['OracleB'],recover,delay=2.2)
