#!/usr/bin/env python3
"""50 concurrent users, each imports, calculates and exports its own small dataset.
This is a concurrency smoke test, not a benchmark of 50 simultaneous 3 GiB jobs.
"""
import argparse,concurrent.futures,json,pathlib,time,urllib.request,uuid
p=argparse.ArgumentParser();p.add_argument('--url',default='http://localhost:8080');p.add_argument('--users',type=int,default=50);p.add_argument('--out',default='evidence/concurrency.json');a=p.parse_args()
features=[{'type':'Feature','geometry':{'type':'LineString','coordinates':[[37.60,55.74],[37.60,55.75]]},'properties':{'id':'network','object_type':'heat_network','diameter':500}}, {'type':'Feature','geometry':{'type':'Point','coordinates':[37.6008,55.745]},'properties':{'id':12,'object_type':'oks_connection_point','flow_tph':20}}]
payload=json.dumps({'type':'FeatureCollection','features':features}).encode()
def req(path,body=None,headers={}):
    with urllib.request.urlopen(urllib.request.Request(a.url+path,body,headers),timeout=180) as r:return json.load(r)
def user(i):
    started=time.monotonic();boundary=uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="load-{i}.geojson"\r\nContent-Type: application/geo+json\r\n\r\n'.encode()+payload+f'\r\n--{boundary}--\r\n'.encode())
    job=req('/api/v1/calculations',body,{'Content-Type':'multipart/form-data; boundary='+boundary});id=job['id'];req('/api/v1/calculations/'+id+'/run',b'')
    while time.monotonic()-started<180:
        state=req('/api/v1/calculations/'+id)
        if state['status']=='FAILED':raise RuntimeError(state['error'])
        if state['status']=='COMPLETED':
            result=req('/api/v1/calculations/'+id+'/result');assert result['type']=='FeatureCollection';assert state['report']['connected']==1
            return {'user':i,'seconds':round(time.monotonic()-started,3),'variants':len(state['report']['fingerprints'])}
        time.sleep(.25)
    raise TimeoutError(id)
started=time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=a.users) as pool:results=list(pool.map(user,range(a.users)))
times=sorted(x['seconds'] for x in results);report={'users':a.users,'succeeded':len(results),'elapsedSeconds':round(time.monotonic()-started,3),'p95Seconds':times[min(len(times)-1,int(.95*len(times)))],'scenario':'Small synthetic dataset per user; queued workers; not 50 full-size jobs','results':results}
out=pathlib.Path(a.out);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False,indent=2))
