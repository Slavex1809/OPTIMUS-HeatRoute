#!/usr/bin/env python3
"""Run real HTTP import/calculation/export. Python standard library only."""
import argparse,json,pathlib,time,urllib.request,uuid
p=argparse.ArgumentParser();p.add_argument('--url',default='http://localhost:8080');p.add_argument('--file',default='data/corrected.geojson');p.add_argument('--timeout',type=int,default=1800);p.add_argument('--out',default='evidence/http-result.geojson');a=p.parse_args()
def request(path,data=None,headers={}):
    with urllib.request.urlopen(urllib.request.Request(a.url+path,data,headers),timeout=120) as r:return r.read()
print(request('/actuator/health').decode())
boundary='optimus-'+uuid.uuid4().hex
raw=pathlib.Path(a.file).read_bytes()
body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="dataset.geojson"\r\nContent-Type: application/geo+json\r\n\r\n'.encode()+raw+f'\r\n--{boundary}--\r\n'.encode())
job=json.loads(request('/api/v1/calculations',body,{'Content-Type':'multipart/form-data; boundary='+boundary}));id=job['id'];request('/api/v1/calculations/'+id+'/run',b'');deadline=time.monotonic()+a.timeout
while time.monotonic()<deadline:
    job=json.loads(request('/api/v1/calculations/'+id));print(job.get('status'),job.get('progress',{}),flush=True)
    if job['status']=='FAILED':raise RuntimeError(job['error'])
    if job['status']=='COMPLETED':break
    time.sleep(2)
else:raise TimeoutError(id)
result=request('/api/v1/calculations/'+id+'/result');out=pathlib.Path(a.out);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(result);out.with_suffix('.report.json').write_text(json.dumps(job,ensure_ascii=False,indent=2));print(json.dumps(job['report'],ensure_ascii=False,indent=2))
