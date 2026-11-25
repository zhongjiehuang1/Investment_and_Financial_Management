#!/usr/bin/env python3
"""Isolated loopback Nginx and synthetic backend; never touches deployed services."""
import contextlib,http.client,importlib.util,json,os,pathlib,re,shutil,socket,subprocess,tempfile,threading,time,unittest
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('entrytest',HERE/'test_lab_entry.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
BIN=pathlib.Path(os.environ.get('TEST_NGINX_BINARY') or shutil.which('nginx') or '/nonexistent-test-nginx')
class Backend:
 def __init__(self):self.started=0;self.cv=threading.Condition();self.gate=threading.Event();self.closed=False;self.sock=socket.socket();self.sock.bind(('127.0.0.1',0));self.sock.listen();self.sock.settimeout(.2);self.port=self.sock.getsockname()[1];self.thread=threading.Thread(target=self.loop,daemon=True);self.thread.start()
 def loop(self):
  while not self.closed:
   try:c,_=self.sock.accept()
   except socket.timeout:continue
   except OSError:break
   threading.Thread(target=self.handle,args=(c,),daemon=True).start()
 def handle(self,c):
  with contextlib.closing(c):
   c.settimeout(5);buf=b''
   try:
    while b'\r\n\r\n' not in buf:buf+=c.recv(65536)
    with self.cv:self.started+=1;self.cv.notify_all()
    self.gate.wait(5);c.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK')
   except OSError:pass
 def wait(self,n):
  with self.cv:return self.cv.wait_for(lambda:self.started>=n,timeout=3)
 def close(self):self.closed=True;self.gate.set();self.sock.close();self.thread.join(timeout=2)
def socket_port():
 with contextlib.closing(socket.socket()) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
@unittest.skipUnless(BIN.is_file(), 'Install Nginx or set TEST_NGINX_BINARY for isolated integration checks')
class TestRealNginx(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.path=pathlib.Path(self.t.name);(self.path/'logs').mkdir();self.backend=Backend();self.port=socket_port();text=mod.entry.render(mod.config(),{a:[1,2] for a in mod.entry.APPS})
  def extract(pattern):
   match=re.search(pattern,text,re.S);self.assertIsNotNone(match);return match.group(0)
  maps='\n'.join(extract(p) for p in (r'map "\$request_method:\$uri" \$four_files_body \{.*?\n    \}',r'map "\$content_length:\$http_transfer_encoding" \$four_workspace_size \{.*?\n    \}',r'map "\$request_method:\$uri:\$four_workspace_size" \$four_files_large_body \{.*?\n    \}'))
  zones='\n'.join(extract(p) for p in (r'limit_conn_zone \$four_files_body zone=four_files_body:1m;',r'limit_conn_zone \$four_files_large_body zone=four_files_large_body:1m;'))
  busy=extract(r'location @workspace_busy \{.*?\n        \}')
  conf=f'''worker_processes 1; pid {self.path}/nginx.pid; error_log {self.path}/error.log notice; events {{ worker_connections 128; }} http {{ {maps}\n{zones}\nserver {{ listen 127.0.0.1:{self.port}; server_name starhub.example.invalid; client_max_body_size 6m; limit_conn four_files_body 3; limit_conn four_files_large_body 1; limit_conn_status 429;
  location ~ ^/(?:starhub/)?api/workspace$ {{ recursive_error_pages on; error_page 429 = @workspace_busy; proxy_request_buffering off; proxy_next_upstream off; proxy_pass http://127.0.0.1:{self.backend.port}; }}
  {busy}\n}} }}''';(self.path/'nginx.conf').write_text(conf)
  r=subprocess.run([str(BIN),'-t','-c',str(self.path/'nginx.conf'),'-p',str(self.path)],capture_output=True,timeout=5)
  if r.returncode:raise AssertionError(r.stderr.decode())
  self.proc=subprocess.Popen([str(BIN),'-c',str(self.path/'nginx.conf'),'-p',str(self.path),'-g','daemon off;'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  for _ in range(100):
   try:
    with socket.create_connection(('127.0.0.1',self.port),timeout=.1):break
   except OSError:time.sleep(.02)
  else:raise AssertionError('isolated nginx did not start')
 def tearDown(self):
  self.backend.close();self.proc.terminate()
  try:self.proc.wait(timeout=5)
  except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait()
  self.t.cleanup()
 def post(self,body=None,chunked=False,path='/api/workspace'):
  c=http.client.HTTPConnection('127.0.0.1',self.port,timeout=5)
  try:
   if chunked:c.request('POST',path,body=iter([b'x']),headers={'Transfer-Encoding':'chunked'},encode_chunked=True)
   else:c.request('POST',path,body=body,headers={'Content-Type':'application/json'})
   r=c.getresponse();data=r.read();return r.status,data
  finally:c.close()
 def worker(self,body,results,chunked=False):results.append(self.post(body,chunked))
 def test_two_small_allowed(self):
  results=[];a=threading.Thread(target=self.worker,args=(b'x'*8192,results));a.start();self.assertTrue(self.backend.wait(1));b=threading.Thread(target=self.worker,args=(b'xx',results));b.start();self.assertTrue(self.backend.wait(2));self.backend.gate.set();a.join();b.join();self.assertEqual([200,200],sorted(x[0] for x in results))
 def reject_second(self,body,chunked=False):
  results=[];a=threading.Thread(target=self.worker,args=(body,results,chunked));a.start();self.assertTrue(self.backend.wait(1));status,data=self.post(body,chunked);self.assertEqual(429,status);self.assertEqual('SERVER_BUSY',json.loads(data)['code']);self.assertEqual('NOT_STARTED',json.loads(data)['dispatchState']);self.assertEqual(1,self.backend.started);self.backend.gate.set();a.join();self.assertEqual(200,results[0][0])
 def test_two_large_second_rejected(self):self.reject_second(b'x'*8193)
 def test_chunked_is_conservative(self):self.reject_second(None,True)
 def test_missing_length_is_conservative(self):
  def raw_post():
   with socket.create_connection(('127.0.0.1',self.port),timeout=5) as c:
    c.sendall(b'POST /api/workspace HTTP/1.0\r\nHost: starhub.example.invalid\r\n\r\n');raw=b''
    while True:
     part=c.recv(65536)
     if not part:break
     raw+=part
    return raw
  result=[];a=threading.Thread(target=lambda:result.append(raw_post()));a.start();self.assertTrue(self.backend.wait(1));second=raw_post();self.assertIn(b' 429 ',second.split(b'\r\n',1)[0]);self.assertIn(b'"dispatchState":"NOT_STARTED"',second);self.assertEqual(1,self.backend.started);self.backend.gate.set();a.join();self.assertIn(b' 200 ',result[0].split(b'\r\n',1)[0])
 def test_context_alias_shares_the_large_admission(self):
  results=[];a=threading.Thread(target=self.worker,args=(b'x'*8193,results));a.start();self.assertTrue(self.backend.wait(1));status,data=self.post(b'x'*8193,path='/starhub/api/workspace');self.assertEqual(429,status);self.assertEqual('NOT_STARTED',json.loads(data)['dispatchState']);self.assertEqual(1,self.backend.started);self.backend.gate.set();a.join();self.assertEqual(200,results[0][0])
 def test_total_three_blocks_fourth_before_proxy(self):
  results=[];threads=[]
  for count in range(1,4):
   t=threading.Thread(target=self.worker,args=(b'x',results));threads.append(t);t.start();self.assertTrue(self.backend.wait(count))
  status,data=self.post(b'x');self.assertEqual(429,status);self.assertEqual('NOT_STARTED',json.loads(data)['dispatchState']);self.assertEqual(3,self.backend.started);self.backend.gate.set()
  for t in threads:t.join()
  self.assertEqual([200,200,200],sorted(x[0] for x in results))
if __name__=='__main__':unittest.main()
