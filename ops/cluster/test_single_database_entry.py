import importlib.util,json,time,unittest,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('single_entry_candidate',HERE/'lab_entry.py');E=importlib.util.module_from_spec(s);sys.modules[s.name]=E;s.loader.exec_module(E)
s=importlib.util.spec_from_file_location('single_monitor_candidate',HERE/'single_database_monitor.py');M=importlib.util.module_from_spec(s);s.loader.exec_module(M)
ID='a8d67a2b-838d-4a97-a8cb-5b2c508d8a61'
class HTTP:
 def __init__(self,status,bad_app=None):self.status=status;self.bad=bad_app
 def get(self,url,token=None,**kwargs):
  if url.endswith('/status'):return E.Reply(200,{'content-type':'application/json'},json.dumps(self.status).encode())
  if url.endswith('/api/server'):return E.Reply(200,{'content-type':'application/json'},b'{"serverId":1}')
  return E.Reply(503 if self.bad and self.bad in url else 204,{},b'')
class EntryTests(unittest.TestCase):
 def probe(self,override=None,bad=None):
  status={'topology':'single-database','mode':'single','nodeId':2,'mysqlUuid':ID,'mysqlReady':True,'ready':True,'reserveGuaranteed':True,'floorBytes':4_000_000_000,'observedAtMillis':int(time.time()*1000)};status.update(override or {})
  c={'databaseTopology':'single-database','singleDatabase':{'mysqlUuid':ID,'monitorUrl':'http://127.0.0.1:942','monitorToken':'t'*32},'nodes':{'1':{'tomcat':'http://127.0.0.1:881'}},'applications':{a:{'recoveryToken':'r'*32} for a in E.APPS}}
  p=E.Prober(c,HTTP(status,bad));p.portfolio=lambda *args:True;return p.node(1)
 def test_no_old_controller_and_one_application_failure_is_isolated(self):
  result,identity=self.probe(bad='starhub');self.assertTrue(identity);self.assertTrue(result['iafm']);self.assertFalse(result['starhub']);self.assertTrue(result['ucost']);self.assertTrue(result['portfolio'])
 def test_database_down_preserves_static_site(self):
  result,_=self.probe({'mysqlReady':False});self.assertEqual(result,{'iafm':False,'starhub':False,'ucost':False,'portfolio':True})
 def test_wrong_database_identity_refused(self):self.assertFalse(self.probe({'mysqlUuid':'wrong'})[1])
 def test_stale_observer_refused(self):self.assertFalse(self.probe({'observedAtMillis':int(time.time()*1000)-10000})[1])
 def test_monitor_rejects_replication_or_weak_durability(self):
  c={'mysqlUuid':ID,'mysqlPort':332,'dataPath':'/tmp'};v=[ID,2,332,0,0,1,1,1,1,'ON',0,'ON','ON',1,'/tmp'];self.assertTrue(M.database_ready(v,c))
  for index,value in [(0,'wrong'),(3,1),(7,2),(8,0),(10,2),(13,0)]:
   bad=list(v);bad[index]=value;self.assertFalse(M.database_ready(bad,c))
  bad=list(v);bad[14]='/var';self.assertFalse(M.database_ready(bad,c))
if __name__=='__main__':unittest.main()
