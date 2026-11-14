import contextlib,hashlib,importlib.util,io,json,os,pathlib,subprocess,sys,tempfile,unittest
from unittest import mock
D=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('maintain_system',D/'maintain_system.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class MaintenanceTests(unittest.TestCase):
 def test_default_is_offline(self):
  r=subprocess.run([sys.executable,str(D/'maintain_system.py')],capture_output=True,text=True,check=True);v=json.loads(r.stdout);self.assertEqual(v['mode'],'OFFLINE_PLAN_ONLY');self.assertEqual(v['networkCalls'],0);self.assertFalse(v['filesChanged'])
 def run_case(self,fail=None):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)/'private';seen=[]
   def action(node,phase,code,socket=None):
    seen.append((node,phase))
    if (node,phase)==fail:raise RuntimeError('simulated unknown')
    return {'node':node,'phase':phase,'status':'PASS'}
   argv=['maintain_system.py','--execute','--expected-worker-sha256',hashlib.sha256((D/'maintenance_remote.py').read_bytes()).hexdigest()]
   with mock.patch.object(m,'ROOT',root),mock.patch.object(m,'call',action),mock.patch.object(sys,'argv',argv),mock.patch.object(m.signal,'signal'),contextlib.redirect_stdout(io.StringIO()):
    if fail:
     with self.assertRaises(SystemExit):m.main()
    else:m.main()
   self.assertEqual((root/'maintenance-exclusive.lock').exists(),bool(fail))
   return seen
 def test_success_order_and_no_second_mysql(self):
  s=self.run_case();self.assertEqual(s,[(1,'preflight'),(2,'preflight'),(1,'stop-app'),(2,'stop-app'),(1,'assert-stopped'),(2,'assert-stopped'),(2,'stop-db'),(1,'clean'),(2,'clean'),(2,'start-db'),(1,'start-app'),(2,'start-app'),(1,'verify'),(2,'verify')])
 def test_unknown_stop_does_not_clean_or_reverse_unknown(self):
  s=self.run_case((2,'stop-db'));self.assertNotIn((1,'clean'),s);self.assertNotIn((2,'clean'),s);self.assertNotIn((2,'start-db'),s);self.assertNotIn((1,'start-app'),s)
 def test_failed_database_start_never_starts_applications(self):
  s=self.run_case((2,'start-db'));self.assertNotIn((1,'start-app'),s);self.assertNotIn((2,'start-app'),s)
spec2=importlib.util.spec_from_file_location('maintenance_remote',D/'maintenance_remote.py');remote=importlib.util.module_from_spec(spec2);spec2.loader.exec_module(remote)
class ColdStartTests(unittest.TestCase):
 def simulated(self,responses,wait=90,unit_active=True):
  now=[0.0];seen=[]
  def tick(seconds):now[0]+=seconds
  def run(*args,**kwargs):
   seen.append(1)
   item=responses.pop(0) if len(responses)>1 else responses[0]
   if isinstance(item,Exception):raise item
   return item
  with mock.patch.object(remote.subprocess,'run',side_effect=run),mock.patch.object(remote.time,'monotonic',side_effect=lambda:now[0]),mock.patch.object(remote.time,'sleep',side_effect=tick),mock.patch.object(remote,'active',return_value=unit_active):
   remote.mysql_identity(wait=wait)
  return len(seen)
 @staticmethod
 def failure():return subprocess.CompletedProcess([],1,b'',b'ERROR 2002 (HY000): private connection unavailable')
 @staticmethod
 def success():return subprocess.CompletedProcess([],0,(remote.UUID+'\t332\n').encode(),b'')
 def test_connection_refused_then_identity(self):
  self.assertEqual(self.simulated([self.failure(),self.success()]),2)
 def test_timeout_and_inactive_refuse(self):
  with self.assertRaises(RuntimeError):self.simulated([self.failure()])
  with self.assertRaises(RuntimeError):self.simulated([self.failure()],unit_active=False)
 def test_wrong_identity_and_nonconnection_error_refuse_immediately(self):
  for item in (subprocess.CompletedProcess([],0,b'wrong-uuid\t332\n',b''),subprocess.CompletedProcess([],0,(remote.UUID+'\t331\n').encode(),b''),subprocess.CompletedProcess([],1,b'',b'ERROR 1045 (28000): denied'),subprocess.TimeoutExpired('mysql',15)):
   results=[item,self.success()]
   with self.assertRaises(RuntimeError):self.simulated(results)
   self.assertEqual(len(results),1)
 def test_immediate_preflight_does_not_retry(self):
  results=[self.failure(),self.success()]
  with self.assertRaises(RuntimeError):self.simulated(results,wait=0)
  self.assertEqual(len(results),1)
 def test_wrong_identity_does_not_start_monitor(self):
  seen=[]
  with mock.patch.object(remote.os,'geteuid',return_value=0),mock.patch.object(sys,'argv',['maintenance_remote.py','2','start-db']),mock.patch.object(remote,'services',side_effect=lambda units,op:seen.append((units,op))),mock.patch.object(remote.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'wrong\t332\n',b'')):
   with self.assertRaises(RuntimeError):remote.main()
  self.assertEqual(seen,[((remote.MYSQL,),'start')])
if __name__=='__main__':unittest.main()
