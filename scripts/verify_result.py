#!/usr/bin/env python3
"""Independent exported JSON, arithmetic, connectivity and flow check (stdlib)."""
import argparse, json
from decimal import Decimal as D
from collections import defaultdict, deque
p=argparse.ArgumentParser();p.add_argument('input');p.add_argument('result');a=p.parse_args()
def read(path):
    with open(path,encoding='utf-8') as f:return json.load(f,parse_float=D)
def key(v): return ('s',v) if isinstance(v,str) else ('n',D(v))
def eq(a,b,tol=D('0.001')): assert abs(D(a)-D(b))<=tol,(a,b)
source=read(a.input);result=read(a.result)
inputs={key(f['properties']['id']):f for f in source['features']}
targets={k:f for k,f in inputs.items() if f['properties']['object_type']=='oks_connection_point'}
rate=dict(zip([50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400],[74023,78631,83530,89748,97275,105507,120275,135323,150022,190299,224137,264790,324298,325996,327693,418777,428074,683417]))
capacity=dict(zip(rate,map(D,['3.5','8.3','13.2','22.3','40.2','65.1','152.3','274.9','437.4','943.1','1663.4','2627.7','3735.1','5296.8','7165','9391.8','15012.8','22501.9'])))
variants=defaultdict(list);ids=set(inputs)
for f in result['features']:
    q=f['properties'];k=key(q['id']);assert k not in ids,('duplicate',k);ids.add(k);variants[key(q['variant_id'])].append(f)
last_score=D('-Infinity')
for v,fs in variants.items():
    summaries=[f['properties'] for f in fs if f['properties']['object_type']=='variant_summary'];assert len(summaries)==1
    s=summaries[0];nodes={**inputs,**{key(f['properties']['id']):f for f in fs if f['geometry'] and f['geometry']['type']=='Point'}}
    nets=[f for f in fs if f['properties']['object_type']=='heat_network'];cameras=[f for f in fs if f['properties']['object_type']=='heat_chamber']
    length=pipe=D(0);adj=defaultdict(list)
    for f in nets:
        q=f['properties'];start,end=key(q['start_node_id']),key(q['end_node_id']);assert start in nodes and end in nodes
        for k,coord in [(start,f['geometry']['coordinates'][0]),(end,f['geometry']['coordinates'][-1])]:
            for x,y in zip(coord,nodes[k]['geometry']['coordinates']):eq(x,y,D('0.00000001'))
        adj[start].append((end,q));adj[end].append((start,q))
        assert q['flow_tph']<=capacity[q['diameter']];assert q['depth_start'] is None and q['depth_end'] is None
        assert q['laying_method'] in ('base','special');factor=q.get('special_factor',D(1));assert (factor>1)==(q['laying_method']=='special')
        eq(q['cost'],q['length']*rate[q['diameter']]*factor);length+=q['length'];pipe+=q['cost']
    missing={key(x) for x in s['unconnected_oks_ids']};assert missing<=targets.keys()
    assert set(targets)-missing==set(targets)&set(adj)
    for k in adj:
        typ=nodes[k]['properties']['object_type'];assert len(adj[k])<=(1 if typ=='oks_connection_point' else 2 if typ=='technical_node' else 4)
    # Each component is a tree. Root is a chamber on input or a new tie chamber.
    seen=set()
    for start in adj:
        if start in seen:continue
        component=set();stack=[start]
        while stack:
            k=stack.pop()
            if k in component:continue
            component.add(k);stack.extend(x for x,_ in adj[k])
        seen|=component;assert sum(len(adj[k]) for k in component)==2*(len(component)-1)
        roots=[k for k in component if nodes[k]['properties']['object_type']=='heat_chamber' and (k in inputs or len(adj[k])==1)]
        assert len(roots)==1,('root count',roots)
        def demand(k,parent=None):
            total=D(nodes[k]['properties'].get('flow_tph',0)) if k in targets else D(0)
            for child,q in adj[k]:
                if child==parent:continue
                subtotal=demand(child,k);eq(q['flow_tph'],subtotal);total+=subtotal
            return total
        demand(roots[0])
    eq(s['new_network_length'],length)
    eq(s['chamber_construction_cost'],sum((f['properties']['cost'] for f in cameras),D(0)))
    eq(s['existing_chamber_tie_in_cost'],s['existing_chamber_tie_in_count']*5000000)
    eq(s['construction_cost'],pipe+s['chamber_construction_cost']+s['existing_chamber_tie_in_cost'])
    eq(s['unconnected_penalty'],sum((D(100000000)+D(500000)*targets[k]['properties']['flow_tph'] for k in missing),D(0)))
    eq(s['calculated_cost'],s['construction_cost']+s['unconnected_penalty'])
    eq(s['score'],D('.7')*s['calculated_cost']/25000000+D('.3')*length/100,D('0.00000001'))
    assert s['score']>=last_score;last_score=s['score']
    print(f"PASS {v[1]}: {len(targets)-len(missing)}/{len(targets)} targets, {len(nets)} segments, S={s['score']}")
print('PASS: identifiers, endpoints, trees, conservation of flow, capacities, cost and ranking. Spatial constraints are checked by the Java verifier.')
