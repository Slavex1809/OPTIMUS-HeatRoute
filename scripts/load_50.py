#!/usr/bin/env python3
"""50 concurrent clients: upload, start, poll and export a small synthetic job."""
import argparse,concurrent.futures,json,time,urllib.request,statistics
p=argparse.ArgumentParser();p.add_argument('--url',default='http://localhost:8080');p.add_argument('--clients',type=int,default=50);a=p.parse_args()
def call(path,data=None,headers=None):
    with urllib.request.urlopen(urllib.request.Request(a.url+path,data,headers or {}),timeout=120) as r:return json.load(r)
def client(i):
    features=[{'type':'Feature','properties':{'id':'network','object_type':'heat_network','diameter':500},'geometry':{'type':'LineString','coordinates':[[37.6,55.7],[37.6,55.702]]}},{'type':'Feature','properties':{'id':i,'object_type':'oks_connection_point','flow_tph':20},'geometry':{'type':'Point','coordinates':[37.601,55.701]}}]
    payload=json.dumps({'type':'FeatureCollection','features':features}).encode();boundary='optimus-load-'+str(i)
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="load.geojson"\r\nContent-Type: application/geo+json\r\n\r\n'.encode()+payload+f'\r\n--{boundary}--\r\n'.encode())
    begin=time.monotonic();state=call('/api/v1/calculations',body,{'Content-Type':'multipart/form-data; boundary='+boundary});id=state['id'];call('/api/v1/calculations/'+id+'/run',b'')
    while time.monotonic()-begin<180:
        state=call('/api/v1/calculations/'+id)
        if state['status']=='FAILED':raise RuntimeError(state['error'])
        if state['status']=='COMPLETED':break
        time.sleep(.2)
    else:raise TimeoutError(id)
    result=call('/api/v1/calculations/'+id+'/result');summaries=[f['properties'] for f in result['features'] if f['properties']['object_type']=='variant_summary'];assert summaries and all(not s['unconnected_oks_ids'] for s in summaries)
    return time.monotonic()-begin
with concurrent.futures.ThreadPoolExecutor(max_workers=a.clients) as pool:durations=list(pool.map(client,range(a.clients)))
print(json.dumps({'clients':a.clients,'completed':len(durations),'errors':0,'medianSeconds':statistics.median(durations),'maxSeconds':max(durations),'scope':'Small synthetic calculations; not 50 simultaneous 3 GiB datasets'},indent=2))
