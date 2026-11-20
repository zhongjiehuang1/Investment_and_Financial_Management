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
   def action(node,phase,code,socket=None,units=None):
    seen.append((node,phase))
    if (node,phase)==fail:raise RuntimeError('simulated unknown')
    return {'node':node,'phase':phase,'status':'PASS',**({'monitoringUnits':m.monitoring_allowlist(node)} if phase=='preflight' else {})}
   def stop(node,code,socket=None):
    seen.append((node,'bounded-nginx-stop'))
    if (node,'bounded-nginx-stop')==fail:raise RuntimeError('simulated unknown nginx stop')
    return {'node':node,'phase':'bounded-nginx-stop','status':'PASS'}
   argv=['maintain_system.py','--execute','--expected-worker-sha256',hashlib.sha256((D/'maintenance_remote.py').read_bytes()).hexdigest(),'--expected-fast-stop-sha256',hashlib.sha256((D/'fast_nginx_stop.py').read_bytes()).hexdigest()]
   def wait():seen.append(('local','wait-60s'));return 60000
   with mock.patch.object(m,'ROOT',root),mock.patch.object(m,'call',action),mock.patch.object(m,'bounded_nginx_stop',side_effect=stop),mock.patch.object(m,'wait_after_cleanup',side_effect=wait),mock.patch.object(sys,'argv',argv),mock.patch.object(m.signal,'signal'),contextlib.redirect_stdout(io.StringIO()):
    if fail:
     with self.assertRaises(SystemExit):m.main()
    else:m.main()
   self.assertEqual((root/'maintenance-exclusive.lock').exists(),bool(fail))
   return seen
 def test_success_order_and_no_second_mysql(self):
  s=self.run_case();self.assertEqual(s,[(1,'preflight'),(2,'preflight'),(1,'bounded-nginx-stop'),(1,'stop-app'),(2,'bounded-nginx-stop'),(2,'stop-app'),(1,'assert-stopped'),(2,'assert-stopped'),(1,'stop-monitoring'),(2,'stop-monitoring'),(2,'stop-db'),(1,'clean'),(2,'clean'),('local','wait-60s'),(2,'start-db'),(1,'start-monitoring'),(2,'start-monitoring'),(1,'start-app'),(2,'start-app'),(1,'verify'),(2,'verify'),(1,'verify-monitoring'),(2,'verify-monitoring')])
 def test_wait_uses_monotonic_and_retries_early_wakeup(self):
  now=[5.0];requests=[]
  def sleep(seconds):requests.append(seconds);now[0]+=min(seconds,17)
  self.assertEqual(m.wait_after_cleanup(lambda:now[0],sleep),60000)
  self.assertEqual(requests,[60,43,26,9])
 def test_monitoring_stop_unknown_prevents_cleanup_and_start(self):
  s=self.run_case((2,'stop-monitoring'));self.assertNotIn((1,'clean'),s);self.assertNotIn((2,'start-db'),s)
 def test_unknown_nginx_stop_does_not_continue_or_restart(self):
  for node in (1,2):
   s=self.run_case((node,'bounded-nginx-stop'))
   self.assertNotIn((node,'stop-app'),s);self.assertNotIn((1,'clean'),s);self.assertNotIn((2,'start-db'),s)
 def test_wrong_bounded_stop_hash_refuses_before_network_or_lock(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)/'private'
   argv=['maintain_system.py','--execute','--expected-worker-sha256',hashlib.sha256((D/'maintenance_remote.py').read_bytes()).hexdigest(),'--expected-fast-stop-sha256','0'*64]
   with mock.patch.object(m,'ROOT',root),mock.patch.object(m,'call') as call,mock.patch.object(sys,'argv',argv):
    with self.assertRaises(ValueError):m.main()
    call.assert_not_called();self.assertFalse(root.exists())
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
 def test_application_start_waits_for_recorded_cold_start_bound(self):
  seen=[]
  with mock.patch.object(remote.os,'geteuid',return_value=0),mock.patch.object(sys,'argv',['maintenance_remote.py','2','start-app']),mock.patch.object(remote,'services'),mock.patch.object(remote,'healthy',side_effect=lambda node,wait:seen.append((node,wait))),contextlib.redirect_stdout(io.StringIO()):
   remote.main()
  self.assertEqual(seen,[(2,300)])
 def test_only_old_unheld_regular_single_link_allowlisted_logs_are_deleted(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)/'logs';root.mkdir();proc=pathlib.Path(d)/'proc';proc.mkdir();fd=proc/'123'/'fd';fd.mkdir(parents=True)
   for name in ('catalina.2026-11-18.log','localhost.2026-11-18.log','catalina.2026-11-17.log','unrelated.log','catalina.2026-11-16.log','catalina.2026-11-15.log'):
    (root/name).write_text('x')
   old=1
   for path in root.iterdir():os.utime(path,(old,old))
   os.utime(root/'catalina.2026-11-16.log',None)
   os.link(root/'catalina.2026-11-15.log',root/'hardlink')
   (fd/'1').symlink_to(root/'localhost.2026-11-18.log')
   (root/'catalina.2026-11-14.log').symlink_to(root/'catalina.2026-11-17.log')
   count=remote.remove_unheld_logs(root,r'(?:catalina|localhost)\.\d{4}-\d{2}-\d{2}\.log',proc=proc)
   self.assertEqual(count,2)
   self.assertTrue((root/'localhost.2026-11-18.log').exists())
   self.assertTrue((root/'catalina.2026-11-16.log').exists())
   self.assertTrue((root/'catalina.2026-11-15.log').exists())
   self.assertTrue((root/'unrelated.log').exists())
   self.assertTrue((root/'catalina.2026-11-14.log').is_symlink())
 def test_monitoring_units_are_allowlisted_and_start_in_reverse_order(self):
  units=remote.monitoring_allowlist(1)
  self.assertEqual(units,('portfolio-metrics@1.service','portfolio-total@1.service','portfolio-total@1.socket','portfolio-visitor-peer.service','portfolio-traffic@1.service','portfolio-traffic@1.socket'))
  self.assertEqual(remote.monitoring_allowlist(2),('portfolio-metrics@2.service','portfolio-total@2.service','portfolio-total@2.socket','portfolio-traffic@2.service','portfolio-traffic@2.socket'))
  self.assertEqual(list(units),m.monitoring_allowlist(1));self.assertEqual(list(remote.monitoring_allowlist(2)),m.monitoring_allowlist(2))
  self.assertEqual(remote.monitoring_argument(1,','.join(units)),list(units))
  for invalid in (','.join(reversed(units)),units[0]+','+units[0],'ssh.service','portfolio-total@2.service'):
   with self.assertRaises(RuntimeError):remote.monitoring_argument(1,invalid)
  seen=[]
  with mock.patch.object(remote.os,'geteuid',return_value=0),mock.patch.object(sys,'argv',['maintenance_remote.py','1','start-monitoring',','.join(units)]),mock.patch.object(remote,'stopped',return_value=True),mock.patch.object(remote,'services',side_effect=lambda names,op:seen.append((list(names),op))),contextlib.redirect_stdout(io.StringIO()):
   remote.main()
  self.assertEqual(seen,[(list(reversed(units)),'start')])
 def test_only_previously_active_total_units_are_restarted(self):
  active=['portfolio-metrics@1.service','portfolio-total@1.service','portfolio-total@1.socket','portfolio-traffic@1.socket']
  self.assertEqual(remote.monitoring_argument(1,','.join(active)),active)
  seen=[]
  with mock.patch.object(remote.os,'geteuid',return_value=0),mock.patch.object(sys,'argv',['maintenance_remote.py','1','start-monitoring',','.join(active)]),mock.patch.object(remote,'stopped',return_value=True),mock.patch.object(remote,'services',side_effect=lambda names,op:seen.append((list(names),op))),contextlib.redirect_stdout(io.StringIO()):
   remote.main()
  self.assertEqual(seen,[(list(reversed(active)),'start')])
 def test_socket_stop_does_not_require_nonexistent_main_pid_property(self):
  with mock.patch.object(remote,'command',return_value='ControlPID=0\nControlGroup=\nActiveState=inactive\n'):
   self.assertTrue(remote.stopped('portfolio-traffic@1.socket'))
   self.assertFalse(remote.stopped('portfolio-traffic@1.service'))
if __name__=='__main__':unittest.main()
