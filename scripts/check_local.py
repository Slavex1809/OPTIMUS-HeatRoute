#!/usr/bin/env python3
"""Check the shipped local launcher, healthcheck and result viewer."""
import pathlib,subprocess,tempfile,time,urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='optimus-start-check-') as tmp:
 log=open(pathlib.Path(tmp)/'server.log','w')
 server=subprocess.Popen(['bash',str(root/'start.sh'),'--local','--optimus.storage='+tmp+'/jobs'],stdout=log,stderr=subprocess.STDOUT)
 try:
  for attempt in range(60):
   try:
    with urllib.request.urlopen('http://127.0.0.1:8080/actuator/health',timeout=2) as r:assert r.status==200
    break
   except Exception:
    if server.poll() is not None:raise RuntimeError('Launcher failed: '+(pathlib.Path(tmp)/'server.log').read_text())
    time.sleep(.5)
  else:raise RuntimeError('Health timeout')
  subprocess.run(['bash',str(root/'scripts/healthcheck.sh')],check=True)
  with urllib.request.urlopen('http://127.0.0.1:8080/') as r:assert 'Сложная территория —<br>оптимальное подключение.' in r.read().decode()
  with urllib.request.urlopen('http://127.0.0.1:8080/result-viewer.html') as r:assert r.read()==(root/'visualization/result.html').read_bytes()
  print('PASS: shipped start.sh --local, Java health, shell healthcheck, UI, exact standalone result viewer over HTTP')
 finally:
  server.terminate();server.wait(timeout=30);log.close()
