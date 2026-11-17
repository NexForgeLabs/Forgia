#!/usr/bin/env python3
"""Reject adversarial resource evidence derived from a real successful schema-4 corpus."""
import argparse
import copy
import json
from pathlib import Path
from verify_resources import verify,decode,load_lines


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--server',type=Path,required=True);parser.add_argument('--artifact-manifest',type=Path);args=parser.parse_args();root=args.server.resolve()
    trace=load_lines(root/'player-oracle.ndjson'); packets=load_lines(root/'resource-run/packets.ndjson');raw=decode(root)
    result=json.loads((root/'resource-run/result.json').read_text());verify(root,trace,packets,raw,result,artifact_manifest=args.artifact_manifest)
    controls=[]
    def mark(rows,label): return next(i for i,r in enumerate(rows) if r['kind']=='MARK' and r['detail']==label)
    def final_block(rows,label): return next(r for r in rows[mark(rows,label+'_after')+1:] if r['kind']=='BLOCK_STATE')
    def control(name,mutate):
        t,p,w,r=copy.deepcopy((trace,packets,raw,result));mutate(t,p,w,r)
        for index,row in enumerate(t,1):row['seq']=index
        mapping={row['seq']:i for i,row in enumerate(p,1)}
        for row in w:row['seq']=mapping.get(row['seq'],-1)
        for index,row in enumerate(p,1):row['seq']=index
        try:verify(root,t,p,w,r,artifact_manifest=args.artifact_manifest)
        except (AssertionError,StopIteration,KeyError) as error:controls.append({'control':name,'status':'rejected','reason':str(error) or 'required actual state missing'})
        else:raise AssertionError('tampered resource corpus accepted: '+name)
    def remove_feedback(names):
        def mutate(t,p,w,r):
            ids={row['seq'] for row in w if row['direction']=='frame' and row['name'] in names}
            w[:]=[row for row in w if row['seq'] not in ids]
            p[:]=[row for row in p if row['seq'] not in ids and not (row['direction']=='receive' and row['name'] in names)]
        return mutate
    control('ack_only_no_actual_block_update',remove_feedback(['block_change','multi_block_change']))
    def deduction(t,p,w,r):
        start=mark(t,'place_positive_x_A_after')
        next(row for row in t[start+1:] if row['kind']=='STATE' and row['name']=='OracleA')['inventory']['minecraft:dirt']=2
    control('placement_inventory_not_decremented',deduction)
    def duplicate_ground(kind,label):
        def mutate(t,p,w,r):
            start,end=mark(t,label+'_before'),mark(t,label+'_after')
            original=next(row for row in t[start+1:end] if row['kind']=='RESOURCE_STATE' and row['type']==kind)
            t.insert(end+3,copy.deepcopy(original))
        return mutate
    control('duplicated_item_on_ground',duplicate_ground('minecraft:item','item_race'))
    def duplicate_xp(t,p,w,r):
        index=next(i for i,row in enumerate(t) if row['kind']=='XP_CHANGE_EVENT' and row['detail']=='amount=7')
        t.insert(index,copy.deepcopy(t[index]))
    control('duplicated_nonzero_XP_event',duplicate_xp)
    control('duplicated_XP_orb',duplicate_ground('minecraft:experience_orb','xp_race'))
    def cancelled(t,p,w,r):
        row=final_block(t,'place_cancel');state=json.loads(row['detail']);state['block']='minecraft:dirt';row['detail']=json.dumps(state)
    control('cancelled_placement_still_mutates',cancelled)
    def owner(t,p,w,r):
        row=final_block(t,'break_positive_x_A');state=json.loads(row['detail']);state['ownerRegionX']+=1;row['detail']=json.dumps(state)
    control('forged_block_engine_owner',owner)
    def ground_owner(t,p,w,r):
        start=mark(t,'death_drop_after')
        row=next(row for row in t[start+1:] if row['kind']=='RESOURCE_STATE' and row['type']=='minecraft:item')
        row['ownerRegionX']+=4
    control('forged_transient_ground_owner',ground_owner)
    def missing_event(t,p,w,r):del t[next(i for i,row in enumerate(t) if row['kind']=='BLOCK_PLACE_EVENT')]
    control('missing_original_place_event',missing_event)
    def shared(t,p,w,r):
        start=mark(t,'shared_race_positive_x_before');end=mark(t,'shared_race_positive_x_after')
        row=next(row for row in t[start+1:end] if row['kind']=='QUICK_MOVE_RESULT' and row['detail'].endswith('count=0'))
        row['detail']='slot=0,item=minecraft:diamond,count=11'
    control('two_winners_for_one_shared_stack',shared)
    def missing_be(t,p,w,r):t.remove(final_block(t,'click_positive_x_A'))
    control('missing_final_BE_state',missing_be)
    def death(t,p,w,r):
        index=next(i for i,row in enumerate(t) if row['kind']=='DEATH_DROPS_EVENT');t.insert(index,copy.deepcopy(t[index]))
    control('duplicate_death_drop_event',death)
    control('missing_actual_XP_feedback',remove_feedback(['experience']))
    control('missing_actual_collect_feedback',remove_feedback(['collect']))
    def raw_action(t,p,w,r):
        row=next(row for row in w if row['direction']=='frame_send' and row['name']=='block_place');row['data']['location']['x']+=1
    control('raw_standard_action_disagrees',raw_action)
    print(json.dumps({'status':'passed','positiveCorpus':'passed','negativeControls':len(controls),'controls':controls},indent=2))

if __name__=='__main__':main()
