"""Optional original Nether portal round trip, entered only by normal client movement."""
import math
import re
import time


def run_dimensions(session):
    def move(start, destination):
        steps=max(1,math.ceil(math.dist(start,destination)/.25))
        for step in range(1,steps+1):
            point=[a+(b-a)*step/steps for a,b in zip(start,destination)]
            session.request('packet',name='WorkerA',packet='position_look',data={
                'x':point[0],'y':point[1],'z':point[2],'yaw':0,'pitch':0,'onGround':True})
            time.sleep(.06)
        return list(destination)
    def client_position():
        row=session.request('snapshot')['WorkerA']['position']
        return [row[k] for k in ['x','y','z']]
    def dimension():return session.command('data get entity WorkerA Dimension')
    def await_dimension(wanted):
        deadline=time.monotonic()+30
        reads=[]
        while time.monotonic()<deadline:
            response=dimension();reads.append(response)
            if '"'+wanted+'"' in response:
                time.sleep(1)
                return reads
            time.sleep(.5)
        raise AssertionError('Normal portal movement did not reach '+wanted)
    # Fixture setup is entirely before the measured portal window. Move away before creating portals.
    move(client_position(),[49.75,100,27.0])
    for text in [
        'execute in minecraft:the_nether run forceload add -16 -16 31 31',
        'execute in minecraft:the_nether run fill 2 99 -1 11 99 8 minecraft:stone',
        'execute in minecraft:the_nether run fill 2 100 -1 11 105 8 minecraft:air',
        'execute in minecraft:the_nether run fill 5 99 3 8 103 3 minecraft:obsidian',
        'execute in minecraft:the_nether run fill 6 100 3 7 102 3 minecraft:nether_portal[axis=x]',
        'fill 49 99 20 58 99 29 minecraft:stone',
        'fill 53 99 24 56 103 24 minecraft:obsidian',
        'fill 54 100 24 55 102 24 minecraft:nether_portal[axis=x]']:
        session.command(text)
    initial=dimension()
    session.request('mark',label='dimension_before')
    move([49.75,100,27.0],[54.5,100,24.5])
    outward=await_dimension('minecraft:the_nether')
    session.snapshot('dimension-nether');session.resources('dimension-nether-resources')
    portal=client_position()
    outside=move(portal,[portal[0],portal[1],portal[2]+2.5])
    # Read the original player cooldown (10 ticks). It may already be zero after the walk out.
    deadline=time.monotonic()+45;cooldown=[]
    while time.monotonic()<deadline:
        value=session.command('data get entity WorkerA PortalCooldown');cooldown.append(value)
        if re.search(r': 0\s*$',value):break
        time.sleep(.5)
    else:raise AssertionError('Original portal cooldown did not expire')
    move(outside,portal)
    inward=await_dimension('minecraft:overworld')
    session.snapshot('dimension-overworld');session.resources('dimension-overworld-resources')
    session.request('mark',label='dimension_after')
    return {'initialDimension':initial,'outwardReads':outward,'inwardReads':inward,'cooldownReads':cooldown,
            'scope':'Original Nether portal round trip via client movement; fixture blocks only, no command teleport'}
