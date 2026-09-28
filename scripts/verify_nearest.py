#!/usr/bin/env python3
"""Independently verify selected entries against every polygon boundary segment."""
import json,math,re,sys
from map_geometry import utm
source=json.load(open(sys.argv[1])); report=json.load(open(sys.argv[2]));report=report.get('report',report)
assert report['entryPolicy']=='nearest'
byid={str(f['properties']['id']):f for f in source['features']}
def distance(p,a,b):
 dx,dy=b[0]-a[0],b[1]-a[1];den=dx*dx+dy*dy
 t=max(0,min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den)) if den else 0
 return math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy)
checked=0;max_error=0
for d in report['targetDiagnostics']:
 if d['status']!='CONNECTED' or not d.get('ownBuildingId'):continue
 p=utm(byid[str(d['targetId'])]['geometry']['coordinates']);g=byid[str(d['ownBuildingId'])]['geometry'];polys=[g['coordinates']] if g['type']=='Polygon' else g['coordinates']
 segments=[(utm(a),utm(b)) for poly in polys for ring in poly for a,b in zip(ring,ring[1:])]
 minimum=min(distance(p,a,b) for a,b in segments)
 entry=utm(list(map(float,re.findall(r'[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?',d['selectedEntryWgs84'])[:2])))
 error=abs(math.dist(p,entry)-minimum);on_boundary=min(distance(entry,a,b) for a,b in segments)
 assert error<.001 and on_boundary<.001,(d['targetId'],error,on_boundary)
 checked+=1;max_error=max(max_error,error,on_boundary)
print(json.dumps({'entryPolicy':'nearest','connectedEntriesChecked':checked,'maximumErrorM':max_error,'toleranceM':.001,'status':'PASS'},indent=2))
