import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch,Mock
from types import SimpleNamespace
import json,stat,time
import http.server,os,threading,urllib.error,urllib.request
spec=importlib.util.spec_from_file_location('migrate_finance_revision',Path(__file__).with_name('migrate_finance_revision.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class MigrationTest(unittest.TestCase):
 def test_private_bearer_transport_ignores_environment_proxy_and_refuses_redirect(self):
  observed={'origin':0,'proxy':0}
  class Proxy(http.server.BaseHTTPRequestHandler):
   def do_GET(self):observed['proxy']+=1;self.send_response(502);self.end_headers()
   def log_message(self,*args):pass
  proxy=http.server.ThreadingHTTPServer(('127.0.0.1',0),Proxy)
  class Origin(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    observed['origin']+=1
    if self.path=='/redirect':self.send_response(302);self.send_header('Location','http://127.0.0.1:'+str(proxy.server_port)+'/sink')
    else:self.send_response(204)
    self.end_headers()
   def log_message(self,*args):pass
  origin=http.server.ThreadingHTTPServer(('127.0.0.1',0),Origin)
  workers=[threading.Thread(target=s.serve_forever,daemon=True) for s in [proxy,origin]]
  for worker in workers:worker.start()
  try:
   value='http://127.0.0.1:'+str(proxy.server_port)
   with patch.dict(os.environ,{'HTTP_PROXY':value,'http_proxy':value,'HTTPS_PROXY':value,'https_proxy':value,'ALL_PROXY':value,'all_proxy':value,'NO_PROXY':'','no_proxy':''}):
    opener=module.private_opener();url='http://127.0.0.1:'+str(origin.server_port)
    with opener.open(urllib.request.Request(url+'/direct',headers={'Authorization':'Bearer synthetic-local-test-only'}),timeout=2) as response:self.assertEqual(response.status,204)
    with self.assertRaises(urllib.error.HTTPError) as failure:opener.open(urllib.request.Request(url+'/redirect',headers={'Authorization':'Bearer synthetic-local-test-only'}),timeout=2)
    self.assertEqual(failure.exception.code,302);failure.exception.close()
   self.assertEqual(observed,{'origin':2,'proxy':0})
  finally:
   for server in [origin,proxy]:server.shutdown();server.server_close()
 def test_private_rewrite_preserves_historical_hmac_and_tls_urls(self):
  old='idempotency.secret=synthetic-historical\\\\key\nnode1.user=iafm_atomic\nnode2.user=iafm_atomic\nnode1.password=old\nnode2.password=old\natomic.migrationId=old\nnode1.url=jdbc:mysql://127.0.0.1:331/IAFM?sslMode=VERIFY_CA\n'
  new=module.rewrite_properties(old,{'username':'iafm_atomic_v2','password':'new-private-value','migrationId':'new'})
  self.assertIn('idempotency.secret=synthetic-historical\\\\key\n',new);self.assertIn('sslMode=VERIFY_CA',new);self.assertIn('node1.user=iafm_atomic_v2',new)
  with self.assertRaises(module.Closed):module.rewrite_properties(old+'node1.user=duplicate\n',{'username':'new','password':'new','migrationId':'new'})
 def test_private_material_cannot_be_a_symlink_or_group_readable(self):
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'private';path.write_text('{}');path.chmod(0o640)
   with self.assertRaises(module.Closed):module.private(path)
   path.chmod(0o600);self.assertEqual(module.private(path),path)
   link=Path(folder)/'alias';link.symlink_to(path)
   with self.assertRaises(module.Closed):module.private(link)
 def test_only_explicit_finance_schema_names_and_identifiers_enter_sql(self):
  self.assertEqual(len(module.ALL_SCHEMAS),4)
  for unsafe in ['schema;DROP DATABASE x','schema.name','schema`']:
   with self.assertRaises(module.Closed):module.ident(unsafe)
 def test_controller_requires_complete_dual_permit_and_only_protected_loopback(self):
  proof=['11111111-1111-4111-8111-111111111111','current-view','4','dual','a'*64,'22222222-2222-4222-8222-222222222222','synthetic-private-permit-'+'b'*32]
  def capture(mode='dual',url='http://127.0.0.1:941/authority',current=True):
   path=Mock();path.lstat.return_value=SimpleNamespace(st_mode=stat.S_IFREG|0o640,st_uid=0)
   path.read_text.return_value=json.dumps({'authorityUrl':url,'token':'synthetic-token-'+'a'*32})
   headers=dict(zip(['X-Four-Project-'+x for x in ['MySQL-UUID','View-ID','Epoch','Mode','Membership','Controller-Boot-ID','Permit']],proof));headers['X-Four-Project-Mode']=mode
   reply=Mock();reply.status=204;reply.headers=headers;reply.__enter__=Mock(return_value=reply);reply.__exit__=Mock(return_value=False)
   fields={'mysqlReady':True,'replicationMode':'dual','singleNodeAuthorized':False,'configuredNodeIds':[1,2],'onlineNodeIds':[1,2],'nodeId':1,'observedAtMillis':int(time.time()*1000),'authorityEpoch':'4' if current else None,'membershipDigest':proof[4],'memberUuid':proof[0],'viewId':proof[1],'controllerBootId':proof[5]}
   status=Mock();status.status=200;status.read.return_value=json.dumps(fields).encode();status.__enter__=Mock(return_value=status);status.__exit__=Mock(return_value=False)
   opener=Mock();opener.open.side_effect=[reply,status]
   with patch.object(module,'AUTHORITY',path),patch.object(module.urllib.request,'build_opener',return_value=opener):return module.authority_dual()
  self.assertEqual(capture(),tuple(proof))
  for mode in ['single','RECONCILING','']:
   with self.assertRaises(module.Closed):capture(mode)
  with self.assertRaises(module.Closed):capture(current=False)
  for url in ['https://127.0.0.1:941/authority','http://example.com:941/authority','http://127.0.0.1:941/ready']:
   with self.assertRaises(module.Closed):capture(url=url)
 def test_controller_closed_or_changed_preflight_never_bypasses_authority(self):
  db=Mock()
  with patch.object(module.os,'geteuid',return_value=0),patch.object(module,'authority_dual',side_effect=module.Closed('not dual')):
   with self.assertRaises(module.Closed):module.preflight(db)
  db.query.assert_not_called()
 def test_metadata_confirmation_requires_dual_same_permit_and_both_apply(self):
  proof=('11111111-1111-4111-8111-111111111111','current-view','4','dual','a'*64,'22222222-2222-4222-8222-222222222222','synthetic-private-permit-'+'b'*32)
  cfg=Mock();cfg.read_text.return_value=json.dumps({'authorityUrl':'http://127.0.0.1:941/authority','token':'synthetic-token-'+'a'*32})
  db=Mock();db.scalar.return_value=proof[0]+':1-9'
  headers=dict(zip(['X-Four-Project-'+x for x in ['MySQL-UUID','View-ID','Epoch','Mode','Membership','Controller-Boot-ID','Permit']],proof));headers['X-Four-Project-Durability']='dual'
  reply=Mock();reply.status=204;reply.headers=headers;reply.__enter__=Mock(return_value=reply);reply.__exit__=Mock(return_value=False)
  opener=Mock();opener.open.return_value=reply
  with patch.object(module,'AUTHORITY',cfg),patch.object(module,'authority_dual',return_value=proof),patch.object(module.urllib.request,'build_opener',return_value=opener):
   self.assertEqual(module.confirm_dual(db,proof),proof[0]+':1-9')
   request=opener.open.call_args[0][0];self.assertEqual(request.full_url,'http://127.0.0.1:941/confirm');self.assertEqual(json.loads(request.data)['gtidSet'],proof[0]+':1-9');self.assertEqual(json.loads(request.data)['permit'],proof[6])
   headers['X-Four-Project-Durability']='single'
   with self.assertRaises(module.Closed):module.confirm_dual(db,proof)
 def test_changed_metadata_authority_does_not_query_or_confirm_gtids(self):
  db=Mock();proof=('uuid','view','1','dual','membership','permit')
  with patch.object(module,'authority_dual',return_value=(*proof[:2],'2',*proof[3:])):
   with self.assertRaises(module.Closed):module.confirm_dual(db,proof)
  db.scalar.assert_not_called()
  db.query.assert_not_called()
 def test_preflight_rejects_changed_permit_after_genuine_mysql_policy_checks(self):
  db=Mock();cfg=Mock();cfg.read_text.return_value=json.dumps({'authorityUrl':'http://127.0.0.1:941/authority'})
  first=('11111111-1111-4111-8111-111111111111','view','4','dual','a'*64,'22222222-2222-4222-8222-222222222222','permit')
  def query(sql):
   if sql.startswith('SELECT @@datadir'):return [['/var/lib/codex-validation/data/','0','0','ON','1','1']]
   if 'replication_group_members' in sql:return [['ONLINE','PRIMARY'],['ONLINE','PRIMARY']]
   if sql.startswith('SELECT @@GLOBAL.server_id'):return [['1','1','7','7','BEFORE_AND_AFTER','1','1','ON','1','1','MYSQL','TLSv1.3','VERIFY_IDENTITY','1','1','TLSv1.3','0','1','1','1','0','0','0','1']]
   return []
  db.query.side_effect=query
  db.scalar.side_effect=lambda sql:'524288' if 'communication_max_message_size' in sql else (first[0] if '@@server_uuid' in sql else '0')
  with patch.object(module,'AUTHORITY',cfg),patch.object(module.os,'geteuid',return_value=0),patch.object(module,'authority_dual',side_effect=[first,(*first[:2],'5',*first[3:])]),patch.object(module.subprocess,'run',return_value=SimpleNamespace(returncode=3)),patch.object(module.subprocess,'check_output',return_value=''),patch.object(module,'inventory',return_value={}):
   with self.assertRaises(module.Closed):module.preflight(db)
if __name__=='__main__':unittest.main()
