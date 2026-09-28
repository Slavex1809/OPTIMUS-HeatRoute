#!/usr/bin/env python3
"""HTTP input boundary test: two actual features plus JSON whitespace, not a geometry benchmark."""
import argparse,http.client,json,time,urllib.parse
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8080');p.add_argument('--bytes',type=int,default=3*1024**3);a=p.parse_args();u=urllib.parse.urlsplit(a.url)
features=[{'type':'Feature','properties':{'id':'net','object_type':'heat_network','diameter':500},'geometry':{'type':'LineString','coordinates':[[37.6,55.7],[37.6,55.702]]}},{'type':'Feature','properties':{'id':'cp','object_type':'oks_connection_point','flow_tph':20},'geometry':{'type':'Point','coordinates':[37.601,55.701]}}]
geo=json.dumps({'type':'FeatureCollection','features':features}).encode();assert a.bytes>=len(geo)
boundary='optimus-transfer';head=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic-transfer.geojson"\r\nContent-Type: application/geo+json\r\n\r\n'.encode();tail=f'\r\n--{boundary}--\r\n'.encode()
c=(http.client.HTTPSConnection if u.scheme=='https' else http.client.HTTPConnection)(u.hostname,u.port,timeout=600);start=time.monotonic();c.putrequest('POST',u.path.rstrip('/')+'/api/v1/calculations');c.putheader('Content-Type','multipart/form-data; boundary='+boundary);c.putheader('Content-Length',len(head)+a.bytes+len(tail));c.endheaders();c.send(head);c.send(geo);left=a.bytes-len(geo);block=b' '*1048576
while left:n=min(left,len(block));c.send(block[:n]);left-=n
c.send(tail);r=c.getresponse();raw=r.read();c.close();assert r.status==201,(r.status,raw[:500]);state=json.loads(raw);assert state['inputBytes']==a.bytes and state['audit']['featureCount']==2
print(json.dumps({'status':'PASS','inputBytes':a.bytes,'httpStatus':r.status,'jobId':state['id'],'elapsedSeconds':time.monotonic()-start,'scope':'Two features plus JSON whitespace; does not establish large-geometry capacity'},indent=2))
