import hashlib
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
import socket
from pathlib import Path
import stat
import tempfile
import threading
import types
import time
import unittest
from unittest import mock

SPEC=importlib.util.spec_from_file_location('lab_entry',Path(__file__).with_name('lab_entry.py'))
entry=importlib.util.module_from_spec(SPEC)
import sys
sys.modules[SPEC.name]=entry
SPEC.loader.exec_module(entry)
TOKEN='synthetic-test-token-at-least-thirty-two-characters'
PAGE=b'<!doctype html><title>Portfolio synthetic test</title>'

def config():
    apps={name:{'hostname':name+'.example.invalid','certificate':'/tmp/test.crt','privateKey':'/tmp/test.key',
                 'recoveryToken':TOKEN,'ingressToken':TOKEN} for name in entry.APPS}
    apps['portfolio']['indexSha256']=hashlib.sha256(PAGE).hexdigest()
    return {'nodeId':1,'listenPort':18443,'admissionPort':943,'runtimeDirectory':'/tmp/four-entry-test',
            'nodes':{str(n):{'tomcat':f'http://127.0.0.1:{880+n}','controller':f'http://127.0.0.1:{940+n}',
                            'appToken':TOKEN,'readyToken':TOKEN} for n in (1,2)},'applications':apps}

def mysql_uuid(node):
    return str(node) * 8 + '-' + str(node) * 4 + '-4' + str(node) * 3 + '-8' + str(node) * 3 + '-' + str(node) * 12


def boot_uuid(node):
    digit = str(node + 2)
    return digit * 8 + '-' + digit * 4 + '-4' + digit * 3 + '-8' + digit * 3 + '-' + digit * 12


def status_document(node, mode='dual'):
    return {'nodeId':node, 'observedAtMillis':int(time.time()*1000), 'mysqlReady':True,
            'tomcatReady':True, 'replicationMode':mode, 'singleNodeAuthorized':mode=='single',
            'onlineNodeIds':[1,2] if mode=='dual' else [node], 'configuredNodeIds':[1,2],
            'memberUuid':mysql_uuid(node), 'viewId':'view', 'membershipDigest':'a'*64,
            'controllerBootId':boot_uuid(node), 'authorityEpoch':'1',
            'reserveGuaranteed':True, 'capacityModelValidated':True, 'floorBytes':4_000_000_000,
            'boundaryPolicy':'ext4-root-plus-kernel-v1', 'physicalBytesPerPayloadByte':6,
            'fixedOverheadBytes':1234, 'volumes':[{'id':'data','availableBytes':4_001_000_000}]}


def probe_config(local, peer):
    value=config()
    for fixture in (local,peer):
        value['nodes'][str(fixture.node_id)].update(tomcat=fixture.url,controller=fixture.url)
    return value


class HttpFixture:
    def __init__(self, node_id=1):
        self.node_id=node_id;self.mode='dual';self.status_patch={};self.recovery_failure=None
        self.calls=[];self.bad_identity=False;self.redirect=False;self.ready=True;self.phase=0;self.change=False
        self.missing_boot=False;self.change_boot=False;self.duplicate_proof=False
        fixture=self
        class Handler(BaseHTTPRequestHandler):
            def do_HEAD(self):self.do_GET()
            def do_GET(self):
                fixture.calls.append((self.path,self.command,self.headers.get('Authorization')))
                status=200;headers={'Content-Type':'application/json'};body=b'{}'
                if self.path=='/authority':
                    status=204;fixture.phase+=1
                    fields={'MySQL-UUID':mysql_uuid(fixture.node_id),'View-ID':'view',
                            'Epoch':str(fixture.phase if fixture.change else 1),'Mode':fixture.mode,'Membership':'a'*64,'Permit':'b'*64,
                            'Controller-Boot-ID':('22222222-2222-4222-8222-'+str(fixture.phase).zfill(12)) if fixture.change_boot else boot_uuid(fixture.node_id)}
                    if fixture.missing_boot:fields.pop('Controller-Boot-ID')
                    headers.update({'X-Four-Project-'+k:v for k,v in fields.items()})
                elif self.path=='/ready':status=204 if fixture.ready else 503
                elif self.path=='/status':
                    value=status_document(fixture.node_id,fixture.mode)
                    value.update(mysqlReady=fixture.ready,tomcatReady=fixture.ready)
                    value.update(fixture.status_patch);body=json.dumps(value).encode()
                elif self.path.endswith('/recovery-ready'):
                    status=503 if fixture.recovery_failure and self.path.startswith('/'+entry.CONTEXT[fixture.recovery_failure]+'/') else 204
                elif self.path.endswith('/api/server'):body=json.dumps({'serverId':3-fixture.node_id if fixture.bad_identity else fixture.node_id}).encode()
                elif self.path=='/index.html':headers={'Content-Type':'text/html','ETag':'synthetic-v1','Content-Length':str(len(PAGE))};body=PAGE
                elif self.path=='/redirect':status=302;headers['Location']='http://127.0.0.1:1/credential-leak'
                else:status=404
                self.send_response(status)
                for k,v in headers.items():self.send_header(k,v)
                if fixture.duplicate_proof and self.path=='/authority':self.send_header('X-Four-Project-Permit','b'*64)
                self.end_headers()
                if self.command!='HEAD' and status!=204:self.wfile.write(body)
            def log_message(self,*args):pass
        # Dual-node Prober.all legitimately opens eight concurrent probes per
        # synthetic server. The stdlib's default backlog of five can reset
        # these test-only loopback sockets before the handler accepts them.
        class ProbeServer(ThreadingHTTPServer):
            request_queue_size=32
        self.server=ProbeServer(('127.0.0.1',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.url='http://127.0.0.1:'+str(self.server.server_port)
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join()

class EntryTest(unittest.TestCase):
    @staticmethod
    def local_audit(value):
        entry._private_audit_file(Path(value['runtimeDirectory'])/'private'/entry.AUDIT_FILENAME,os.geteuid())

    def test_durability_log_format_contains_only_fixed_safe_fields_and_configured_host(self):
        import re
        value=config();text=entry.render(value,{a:[1,2] for a in entry.APPS})
        raw=re.search(r"log_format four_response_durability escape=json '([^']+)';",text).group(1)
        fields=json.loads(raw)
        self.assertEqual(set(fields),{'time','application','host','status','responseDurability'})
        self.assertEqual(set(re.findall(r'\$[A-Za-z_][A-Za-z_0-9]*',raw)),{'$time_iso8601','$four_audit_application','$server_name','$status','$four_audit_durability'})
        self.assertEqual(fields['host'],'$server_name')
        application=re.search(r'map \$server_name \$four_audit_application \{(.*?)\n    \}',text,re.S).group(1)
        self.assertIn('default unknown;',application)
        for app in entry.APPS:self.assertIn(value['applications'][app]['hostname']+' '+app+';',application)
        self.assertIn('/private/'+entry.AUDIT_FILENAME+' four_response_durability;',text)
        self.assertIn('log_subrequest off;',text)
        self.assertNotIn('access_log off;',text)

    def test_durability_map_is_case_sensitive_exact_and_conservative_for_started_streams(self):
        import re
        text=entry.render(config(),{a:[1,2] for a in entry.APPS})
        body=re.search(r'map "\$upstream_http_x_starhub_operation_state:\$upstream_http_x_four_project_durability" \$four_audit_durability \{(.*?)\n    \}',text,re.S).group(1)
        self.assertIn('default unconfirmed;',body)
        rules=[]
        for line in body.splitlines():
            parts=line.strip().removesuffix(';').split()
            if len(parts)==2 and parts[0].startswith('~'):
                self.assertFalse(parts[0].startswith('~*'))
                pattern=parts[0][1:].replace(r'\z',r'\Z')
                rules.append((re.compile(pattern),parts[1]))
        def mode(state,raw):
            value=state+':'+raw
            return next((target for pattern,target in rules if pattern.fullmatch(value)),'unconfirmed')
        for state in ('','COMPLETED','COMPLETED_NO_RESULT','ABSENT'):
            for raw in ('single','dual'):self.assertEqual(mode(state,raw),raw)
        for state in ('SENT','UNKNOWN','completed','COMPLETED, SENT'):
            for raw in ('single','dual'):self.assertEqual(mode(state,raw),'unconfirmed')
        for raw in ('','DUAL','Single',' dual','dual ','dual, dual','dual\n','single:extra','user-supplied-token'):
            self.assertEqual(mode('',raw),'unconfirmed')

    def test_audit_file_is_private_single_link_append_preserving_and_refuses_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            private=Path(folder)/'private';private.mkdir(mode=0o700);path=private/entry.AUDIT_FILENAME
            entry._private_audit_file(path,os.geteuid());self.assertEqual(stat.S_IMODE(path.stat().st_mode),0o600)
            path.write_bytes(b'prior-minimal-record\n');entry._private_audit_file(path,os.geteuid());self.assertEqual(path.read_bytes(),b'prior-minimal-record\n')
            path.chmod(0o644)
            with self.assertRaises(entry.Closed):entry._private_audit_file(path,os.geteuid())
            self.assertEqual(stat.S_IMODE(path.stat().st_mode),0o644);path.chmod(0o600)
            drift=types.SimpleNamespace(st_mode=stat.S_IFREG|0o600,st_uid=os.geteuid()+1,st_nlink=1)
            with mock.patch.object(entry.os,'fstat',return_value=drift),self.assertRaises(entry.Closed):
                entry._private_audit_file(path,os.geteuid())
            linked=private/'hard-link';os.link(path,linked)
            with self.assertRaises(entry.Closed):entry._private_audit_file(path,os.geteuid())
            linked.unlink();path.unlink();path.symlink_to(private/'other')
            with self.assertRaises(OSError):entry._private_audit_file(path,os.geteuid())
            path.unlink();private.chmod(0o750)
            with self.assertRaises(entry.Closed):entry._private_audit_file(path,os.geteuid())
        with mock.patch.object(entry.os,'geteuid',return_value=0),mock.patch.object(entry,'_private_audit_file') as prepare:
            entry.prepare_audit_log(config());prepare.assert_called_once_with(Path(config()['runtimeDirectory'])/'private'/entry.AUDIT_FILENAME,0)

    def test_deduplicated_publication_rechecks_log_drift_before_admission_can_refresh(self):
        with tempfile.TemporaryDirectory() as folder:
            value=config();value['runtimeDirectory']=folder;private=Path(folder)/'private';private.mkdir(mode=0o700)
            calls=[];publisher=entry.Publisher(value,command=lambda *a,**kw:(calls.append(a) or types.SimpleNamespace(returncode=0)),audit=self.local_audit,master=lambda:None)
            live={a:[1] for a in entry.APPS};publisher.publish(live);path=private/entry.AUDIT_FILENAME
            with mock.patch.object(entry.os,'fsync',wraps=os.fsync) as synced:
                publisher.publish(live);self.assertEqual(synced.call_count,2)
            self.assertEqual(len(calls),1);path.chmod(0o644)
            with self.assertRaises(entry.Closed):publisher.publish(live)
            self.assertEqual(len(calls),1)

    def test_private_write_scope_accepts_only_explicit_unique_known_list_on_private_entries(self):
        for port in (18443,18444):
            for apps in ([],['starhub'],list(entry.APPS)):
                value=config();value.update(listenPort=port,listenAddress='127.0.0.1',privateWriteApplications=apps)
                self.assertEqual(entry.validate(value)['privateWriteApplications'],apps)
        for invalid in (None,'starhub',('starhub',),True,{},['missing'],['starhub','starhub'],['starhub',{}],['starhub',True]):
            value=config();value['privateWriteApplications']=invalid
            with self.subTest(invalid=invalid),self.assertRaises(entry.Closed):entry.validate(value)
        for address in ('127.0.0.1','0.0.0.0'):
            value=config();value.update(listenPort=443,listenAddress=address,privateWriteApplications=['starhub'])
            with self.assertRaises(entry.Closed):entry.validate(value)

    def test_private_write_scope_blocks_only_unapproved_app_posts_without_altering_star_routes(self):
        health={app:[1,2] for app in entry.APPS}
        value=config();baseline=entry.render(value,health)
        self.assertNotIn('ENTRY_PHASE_CLOSED',baseline)
        value['privateWriteApplications']=['starhub'];restricted=entry.render(value,health)
        self.assertEqual(restricted.count('"code":"ENTRY_PHASE_CLOSED"'),3)
        self.assertEqual(restricted.count('if ($request_method = POST) { return 419; }'),3)
        for app in entry.APPS:
            start=restricted.index('server_name '+value['applications'][app]['hostname']+';')
            following=restricted.find('\n    server {',start)
            block=restricted[start:following if following>=0 else len(restricted)]
            self.assertEqual('ENTRY_PHASE_CLOSED' in block,app!='starhub')
            self.assertIn('auth_request /_entry_admit;',block)
            if app=='starhub':
                self.assertNotIn('return 419',block)
                for route in ('provider-media','provider-operation','guest-session','account','/realtime'):
                    self.assertIn(route,block)
                self.assertIn('proxy_request_buffering off;',block)
                self.assertIn('proxy_next_upstream off;',block)
            else:
                self.assertIn('error_page 419 =503 @entry_phase_closed;',block)
                self.assertIn('Cache-Control "no-store" always',block)
                self.assertNotIn('$request_method = GET',block)
                self.assertNotIn('$request_method = HEAD',block)

    def test_private_backend_routing_requires_strict_boolean_and_private_listener_even_when_false(self):
        for port in (18443,18444):
            for enabled in (False,True):
                value=config();value.update(listenPort=port,listenAddress='127.0.0.1',privateAcceptanceBackendRouting=enabled)
                self.assertIs(entry.validate(value)['privateAcceptanceBackendRouting'],enabled)
        for invalid in (None,0,1,'true','false',[],{}):
            value=config();value['privateAcceptanceBackendRouting']=invalid
            with self.subTest(invalid=invalid),self.assertRaises(entry.Closed):entry.validate(value)
        for address in ('127.0.0.1','0.0.0.0'):
            for enabled in (False,True):
                value=config();value.update(listenPort=443,listenAddress=address,privateAcceptanceBackendRouting=enabled)
                with self.assertRaises(entry.Closed):entry.validate(value)
        value=config();value.update(listenAddress='0.0.0.0',privateAcceptanceBackendRouting=True)
        with self.assertRaises(entry.Closed):entry.validate(value)

    def test_private_backend_header_preserves_cookie_and_defaults_to_original_policy(self):
        import re
        health={app:[1,2] for app in entry.APPS};value=config();baseline=entry.render(value,health)
        value['privateAcceptanceBackendRouting']=False
        self.assertEqual(entry.render(value,health),baseline)
        self.assertNotIn('private_acceptance_backend',baseline)
        value['privateAcceptanceBackendRouting']=True;text=entry.render(value,health)
        cookie=re.search(r'map \$cookie_JSESSIONID \$four_starhub_cookie_upstream \{(.*?)\n    \}',text,re.S).group(1)
        self.assertIn('~[.]n1$ four_starhub_n1;',cookie);self.assertIn('~[.]n2$ four_starhub_n2;',cookie)
        body=re.search(r'map \$http_x_private_acceptance_backend \$four_starhub_upstream \{(.*?)\n    \}',text,re.S).group(1)
        self.assertIn('default $four_starhub_cookie_upstream;',body)
        rules=[]
        for line in body.splitlines():
            parts=line.strip().removesuffix(';').split()
            if len(parts)==2 and parts[0].startswith('~'):
                self.assertFalse(parts[0].startswith('~*'))
                rules.append((re.compile(parts[0][1:].replace(r'\z',r'\Z')),parts[1]))
        def route(header):return next((target for pattern,target in rules if pattern.fullmatch(header)),'$four_starhub_cookie_upstream')
        self.assertEqual(route('n1'),'four_starhub_n1');self.assertEqual(route('n2'),'four_starhub_n2')
        for raw in ('','N1','n3',' n1','n1 ','n1, n2','n1\n','http://127.0.0.1:881','four_starhub_n1'):
            self.assertEqual(route(raw),'$four_starhub_cookie_upstream')
        self.assertNotIn('proxy_set_header Cookie',text);self.assertNotIn('Set-Cookie',text)
        for app in ('iafm','ucost','portfolio'):
            target='$cookie_UCOST_SESSION' if app=='ucost' else '$cookie_JSESSIONID'
            self.assertIn('map '+target+' $four_'+app+'_upstream {',text)
            self.assertNotIn('$four_'+app+'_cookie_upstream',text)
        self.assertEqual(text.count('auth_request /_entry_admit;'),baseline.count('auth_request /_entry_admit;'))
        self.assertEqual(text.count('proxy_next_upstream off;'),baseline.count('proxy_next_upstream off;'))

    def test_private_backend_targets_remain_existing_health_filtered_groups(self):
        value=config();value['privateAcceptanceBackendRouting']=True
        for available in ([1,2],[1],[2],[]):
            text=entry.render(value,{app:available for app in entry.APPS})
            for preference in ('any','n1','n2'):
                self.assertIn(entry.upstream('starhub',preference,available,value['nodes']),text)
            self.assertNotIn('four_starhub_private',text)
            self.assertIn('proxy_pass http://$four_starhub_upstream/starhub/realtime;',text)

    def test_systemd_notification_contains_only_local_readiness(self):
        with tempfile.TemporaryDirectory() as folder, socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM) as receiver:
            path=str(Path(folder)/'notify');receiver.bind(path);receiver.settimeout(1)
            with mock.patch.dict(os.environ,{'NOTIFY_SOCKET':path}):entry.notify_ready()
            self.assertEqual(receiver.recv(64),b'READY=1')
        with mock.patch.dict(os.environ,{'NOTIFY_SOCKET':'https://external.invalid'}):
            with self.assertRaises(entry.Closed):entry.notify_ready()

    def test_configuration_and_secrets_stay_private(self):
        value=config();entry.validate(value)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'config.json';path.write_text(json.dumps(value));path.chmod(0o600)
            self.assertEqual(entry.load_config(path)['nodeId'],1)
            path.chmod(0o640)
            with self.assertRaises(entry.Closed):entry.load_config(path)
            link=Path(folder)/'link';link.symlink_to(path)
            with self.assertRaises(entry.Closed):entry.load_config(link)
        for update in ({'listenAddress':'0.0.0.0'}, {'admissionPort':1943}, {'runtimeDirectory':'/tmp/a;echo'}):
            bad=config();bad.update(update)
            with self.assertRaises(entry.Closed):entry.validate(bad)
        bad=config();bad['nodes']['2']['controller']='http://127.0.0.1:941@external.invalid'
        with self.assertRaises(entry.Closed):entry.validate(bad)
        for field,url in [('controller','http://127.0.0.1:1942'),('controller','http://127.0.0.1:941'),('tomcat','http://127.0.0.1:881')]:
            bad=config();bad['nodes']['2'][field]=url
            with self.assertRaises(entry.Closed):entry.validate(bad)

    def test_missing_or_changed_controller_boot_identity_closes_real_probe(self):
        fixture=HttpFixture()
        try:
            value=config();value['nodes']['1'].update(tomcat=fixture.url,controller=fixture.url)
            probe=entry.Prober(value)
            fixture.missing_boot=True
            states,_=probe.node(1);self.assertFalse(any(states[a] for a in entry.APPS[:-1]));self.assertTrue(states['portfolio'])
            fixture.missing_boot=False;fixture.change_boot=True
            states,_=probe.node(1);self.assertFalse(any(states[a] for a in entry.APPS[:-1]));self.assertTrue(states['portfolio'])
        finally:fixture.close()

    def test_duplicate_controller_headers_are_rejected_before_proof_use(self):
        fixture=HttpFixture()
        try:
            fixture.duplicate_proof=True
            with self.assertRaises(entry.Closed):entry.Http(1).get(fixture.url+'/authority',TOKEN)
        finally:fixture.close()

    def test_live_http_requires_authority_status_runtime_recovery_and_actual_node_identity(self):
        fixture=HttpFixture();peer=HttpFixture(2)
        try:
            value=probe_config(fixture,peer)
            probe=entry.Prober(value);states,fingerprint=probe.node(1)
            self.assertTrue(all(states.values()));self.assertEqual(len(fingerprint),64)
            paths=[p for p,_,_ in fixture.calls]
            for app in entry.APPS[:-1]:
                self.assertIn('/'+entry.CONTEXT[app]+entry.RECOVERY[app],paths)
                self.assertIn('/'+entry.CONTEXT[app]+'/api/server',paths)
            self.assertNotIn('/api/cluster-health',paths)
            self.assertNotIn('/ready',paths);self.assertNotIn('/ready',[p for p,_,_ in peer.calls])
            self.assertEqual(paths.count('/status'),1)
            self.assertEqual([p for p,_,_ in peer.calls],['/status'])
            for app in entry.APPS[:-1]:
                self.assertEqual(paths.count('/'+entry.CONTEXT[app]+entry.RECOVERY[app]),1)
            self.assertEqual(sum(p=='/index.html' and m=='GET' for p,m,_ in fixture.calls),1)
            probe.node(1)
            self.assertEqual(sum(p=='/index.html' and m=='GET' for p,m,_ in fixture.calls),1)
            fixture.bad_identity=True
            states,_=probe.node(1);self.assertTrue(states['portfolio']);self.assertFalse(any(states[a] for a in entry.APPS[:-1]))
            fixture.bad_identity=False;fixture.ready=False
            states,_=probe.node(1);self.assertFalse(states['iafm']);self.assertTrue(states['portfolio'])
            fixture.ready=True;fixture.change=True
            states,_=probe.node(1);self.assertFalse(states['iafm'])
        finally:fixture.close();peer.close()

    @staticmethod
    def capacity_observation(value, started=None, ended=None, received=None):
        now=int(time.time()*1000)
        reply=entry.Reply(200,{'content-type':'application/json'},json.dumps(value).encode())
        return entry.StatusObservation(reply,now if started is None else started,
                                       now if ended is None else ended,time.monotonic() if received is None else received)

    @staticmethod
    def authority_tuple(node=1,mode='dual'):
        return (mysql_uuid(node),'view','1',mode,'a'*64,'b'*64,boot_uuid(node))

    def test_status_is_bound_to_every_local_grant_identity_and_strict_capacity_field(self):
        baseline=status_document(1);grant=self.authority_tuple()
        self.assertEqual(entry.capacity_status(self.capacity_observation(baseline),1,grant,local=True),baseline)
        changes={'nodeId':[2,True,'1'], 'memberUuid':[mysql_uuid(2),'invalid',None],
                 'viewId':['old',None], 'authorityEpoch':['2',1,True,'01'],
                 'replicationMode':['single',None], 'membershipDigest':['c'*64,None],
                 'controllerBootId':[boot_uuid(2),None], 'mysqlReady':[False,1,'true'],
                 'tomcatReady':[False,1,'true'], 'configuredNodeIds':[[1,1],[1,True],[1],None],
                 'onlineNodeIds':[[1],[1,1],[1,True],None], 'singleNodeAuthorized':[True,0,'false'],
                 'reserveGuaranteed':[False,1,'true'], 'capacityModelValidated':[False,1,'true'],
                 'floorBytes':[4_000_000_001,True,'4000000000'], 'boundaryPolicy':['other',None],
                 'physicalBytesPerPayloadByte':[0,True,'6',1_000_001],
                 'fixedOverheadBytes':[-1,True,'1234',2**63],
                 'volumes':[[],None,[{'id':'data','availableBytes':3_999_999_999}],
                            [{'id':'data','availableBytes':True}],[{'id':'data','availableBytes':'4000000000'}],
                            [{'id':'','availableBytes':4_000_000_000}],baseline['volumes']*2]}
        for key,values in changes.items():
            for replacement in values:
                with self.subTest(field=key,replacement=replacement):
                    value=copy.deepcopy(baseline);value[key]=replacement
                    with self.assertRaises(entry.Closed):entry.capacity_status(self.capacity_observation(value),1,grant,local=True)
        for key in baseline:
            with self.subTest(missing=key):
                value=copy.deepcopy(baseline);value.pop(key)
                with self.assertRaises(entry.Closed):entry.capacity_status(self.capacity_observation(value),1,grant,local=True)

    def test_peer_status_requires_current_distinct_dual_data_plane_but_not_peer_tomcat(self):
        value=status_document(2);value['tomcatReady']=False;grant=self.authority_tuple()
        entry.capacity_status(self.capacity_observation(value),2,grant,local=False)
        for key,replacement in [('nodeId',1),('memberUuid',mysql_uuid(1)),('controllerBootId',boot_uuid(1)),
                                ('authorityEpoch','0'),('replicationMode','single'),('viewId','old'),
                                ('membershipDigest','c'*64),('mysqlReady',False),('singleNodeAuthorized',True),
                                ('capacityModelValidated',False),('reserveGuaranteed',False),('floorBytes',0)]:
            with self.subTest(field=key):
                bad=copy.deepcopy(value);bad[key]=replacement
                with self.assertRaises(entry.Closed):entry.capacity_status(self.capacity_observation(bad),2,grant,local=False)

    def test_status_must_be_fresh_from_this_request_at_completion_without_clock_or_cache_fallback(self):
        now=int(time.time()*1000);mono=time.monotonic();grant=self.authority_tuple()
        for stamp in [True,'1',0,now-12_001,now+2_001]:
            with self.subTest(stamp=stamp):
                value=status_document(1);value['observedAtMillis']=stamp
                with self.assertRaises(entry.Closed):entry.capacity_status(self.capacity_observation(value,now-20_000,now+5000),1,grant,local=True,now_millis=now,now_monotonic=mono)
        value=status_document(1)
        for started,ended,received in [(now+3_000,now+4_000,mono),(now-10_000,now-3_000,mono),(now,now,mono-12.001),(now,now,mono+1)]:
            with self.subTest(started=started,ended=ended,received=received):
                with self.assertRaises(entry.Closed):entry.capacity_status(self.capacity_observation(value,started,ended,received),1,grant,local=True,now_millis=now,now_monotonic=mono)

    def test_status_rejects_ambiguous_json_and_http_or_content_type_failures(self):
        value=status_document(1);grant=self.authority_tuple()
        for reply in [entry.Reply(503,{'content-type':'application/json'},json.dumps(value).encode()),
                      entry.Reply(200,{'content-type':'text/html'},json.dumps(value).encode()),
                      entry.Reply(200,{'content-type':'application/json'},b'{"nodeId":1,"nodeId":1}')]:
            with self.subTest(status=reply.status,body=reply.body[:20]):
                observed=self.capacity_observation(value);observed.reply=reply
                with self.assertRaises(entry.Closed):entry.capacity_status(observed,1,grant,local=True)

    def test_dual_live_probe_accepts_peer_tomcat_failure_but_closes_on_either_capacity_or_any_local_recovery_failure(self):
        local=HttpFixture();peer=HttpFixture(2)
        try:
            peer.status_patch['tomcatReady']=False;probe=entry.Prober(probe_config(local,peer))
            states,_=probe.node(1);self.assertTrue(all(states.values()))
            for fixture in (local,peer):
                fixture.status_patch['capacityModelValidated']=False
                states,_=probe.node(1);self.assertTrue(states['portfolio']);self.assertFalse(any(states[a] for a in entry.APPS[:-1]))
                fixture.status_patch.pop('capacityModelValidated')
            for app in entry.APPS[:-1]:
                local.calls.clear();local.recovery_failure=app
                states,_=probe.node(1);self.assertFalse(any(states[a] for a in entry.APPS[:-1]));self.assertTrue(states['portfolio'])
                paths=[p for p,_,_ in local.calls]
                for checked in entry.APPS[:-1]:self.assertEqual(paths.count('/'+entry.CONTEXT[checked]+entry.RECOVERY[checked]),1)
            local.recovery_failure=None;peer.close()
            states,_=probe.node(1);self.assertFalse(any(states[a] for a in entry.APPS[:-1]));self.assertTrue(states['portfolio'])
        finally:local.close();peer.close()

    def test_all_preserves_node_tuple_interface_and_never_duplicates_runtime_recovery(self):
        first=HttpFixture();second=HttpFixture(2)
        try:
            observed=entry.Prober(probe_config(first,second)).all()
            self.assertEqual(set(observed),{1,2})
            for node,(states,fingerprint) in observed.items():
                self.assertTrue(all(states.values()));self.assertEqual(len(fingerprint),64)
            for fixture in (first,second):
                paths=[p for p,_,_ in fixture.calls]
                self.assertNotIn('/ready',paths);self.assertEqual(paths.count('/status'),2)
                self.assertEqual(paths.count('/authority'),2)
                for app in entry.APPS[:-1]:
                    self.assertEqual(paths.count('/'+entry.CONTEXT[app]+entry.RECOVERY[app]),1)
                    self.assertEqual(paths.count('/'+entry.CONTEXT[app]+'/api/server'),1)
        finally:first.close();second.close()

    def test_single_live_probe_requires_fresh_fenced_authority_and_skips_only_peer_status(self):
        local=HttpFixture();peer=HttpFixture(2)
        try:
            local.mode='single';probe=entry.Prober(probe_config(local,peer))
            states,fingerprint=probe.node(1);self.assertTrue(all(states.values()));self.assertEqual(len(fingerprint),64)
            self.assertEqual(peer.calls,[])
            for patch in [{'singleNodeAuthorized':False},{'singleNodeAuthorized':1},{'onlineNodeIds':[1,2]},
                          {'memberUuid':mysql_uuid(2)},{'controllerBootId':boot_uuid(2)}]:
                local.status_patch=patch
                states,_=probe.node(1);self.assertFalse(any(states[a] for a in entry.APPS[:-1]));self.assertTrue(states['portfolio'])
            local.status_patch={};local.change=True
            states,_=probe.node(1);self.assertFalse(any(states[a] for a in entry.APPS[:-1]))
        finally:local.close();peer.close()

    def test_redirects_and_external_addresses_never_receive_probe_credentials(self):
        fixture=HttpFixture()
        try:
            response=entry.Http(1).get(fixture.url+'/redirect',TOKEN)
            self.assertEqual(response.status,302);self.assertEqual(len(fixture.calls),1)
            for address in ['http://127.0.0.1:80@external.invalid/x','https://example.invalid/x']:
                with self.assertRaises(entry.Closed):entry.Http(1).get(address,TOKEN)
        finally:fixture.close()

    def test_hysteresis_stale_health_and_epoch_changes_fail_closed(self):
        now=[10.0];state=entry.Admission(15,lambda:now[0]);up={a:True for a in entry.APPS}
        observations={1:(up,'epoch-a'),2:({a:False for a in entry.APPS},'')}
        desired=state.observe(observations);state.publish(desired);self.assertFalse(state.admitted('iafm'))
        desired=state.observe(observations);state.publish(desired);self.assertTrue(state.admitted('iafm'))
        observations[1]=(up,'epoch-b');desired=state.observe(observations);state.publish(desired)
        self.assertFalse(state.admitted('iafm'));self.assertTrue(state.admitted('portfolio'))
        state.publish(state.observe(observations));self.assertTrue(state.admitted('iafm'))
        now[0]=26;self.assertFalse(state.admitted('iafm'))
        now[0]=9;self.assertFalse(state.admitted('iafm'))
        self.assertFalse(state.admitted('unknown'))

    def test_equal_round_robin_sticky_both_cookie_names_and_no_post_replay(self):
        value=config();text=entry.render(value,{a:[1,2] for a in entry.APPS})
        self.assertIn('map $cookie_JSESSIONID',text);self.assertIn('map $cookie_UCOST_SESSION',text)
        self.assertIn('~[.]n1$ four_iafm_n1;',text);self.assertIn('~[.]n2$ four_ucost_n2;',text)
        any_block=entry.upstream('iafm','any',[1,2],value['nodes'])
        self.assertIn('127.0.0.1:881 max_fails',any_block);self.assertIn('127.0.0.1:882 max_fails',any_block);self.assertNotIn('backup',any_block)
        sticky=entry.upstream('iafm','n1',[1,2],value['nodes']);self.assertIn('127.0.0.1:882 backup',sticky)
        fallback=entry.upstream('iafm','n1',[2],value['nodes']);self.assertNotIn('backup',fallback);self.assertNotIn(':881',fallback)
        self.assertIn('server 127.0.0.1:9 down;',entry.upstream('iafm','any',[],value['nodes']))
        self.assertNotIn('non_idempotent',text);self.assertGreaterEqual(text.count('proxy_next_upstream off;'),8)
        self.assertIn('if ($request_method ~ ^(GET|HEAD)$)',text)
        self.assertIn('proxy_pass_request_headers off;',text);self.assertIn('internal;',text)
        self.assertNotIn('Bearer '+TOKEN,text);self.assertIn('X-IAFM-Ingress-Token',text)
        self.assertIn('$request_uri;',text);self.assertIn('$four_iafm_uri;',text);self.assertIn('ssl default_server;',text);self.assertIn('error_page 403 500',text)
        self.assertIn('proxy_set_header X-Forwarded-Port $four_forwarded_port;',text)
        self.assertNotIn('proxy_set_header X-Forwarded-Port $server_port;',text)

    def test_root_safe_read_error_page_preserves_admission503_mapping_in_its_own_scope(self):
        import re
        text=entry.render(config(),{app:[1,2] for app in entry.APPS})
        roots=re.findall(r'        location / \{(.*?)\n        \}\n        location @safe_read',text,re.S)
        self.assertEqual(len(roots),4)
        for block in roots:
            # Nginx inherits a parent's error_page list only when the child has
            # none. The local418 dispatcher therefore requires a local full
            # admission mapping too; otherwise an auth_request403 leaks as403.
            directives=re.findall(r'^\s*error_page ([^;]+);',block,re.M)
            effective={}
            for directive in directives:
                fields=directive.split();target=next(i for i,v in enumerate(fields) if v.startswith('='))
                for status in fields[:target]:effective[int(status)]=tuple(fields[target:])
            self.assertEqual(effective[418],('=','@safe_read'))
            for status in (403,500,502,503,504):
                self.assertEqual(effective[status],('=503','@entry_unavailable'))
            self.assertIn('auth_request /_entry_admit;',block)
            self.assertIn('proxy_next_upstream off;',block)
            self.assertIn('if ($request_method ~ ^(GET|HEAD)$) { return 418; }',block)

    def test_health_error_after_safe_read_dispatch_can_reach_a_terminal503(self):
        import re
        text=entry.render(config(),{app:[1,2] for app in entry.APPS})
        roots=re.findall(r'        location / \{(.*?)\n        \}\n        location @safe_read',text,re.S)
        terminals=re.findall(r'        location @entry_unavailable \{(.*?)\n        \}',text,re.S)
        self.assertEqual((len(roots),len(terminals)),(4,4))
        # The actual Nginx component regression additionally exercises this
        # two-hop418/auth403 path and ensures backend403 is passed through.
        for block in roots:self.assertIn('recursive_error_pages on;',block)
        for block in terminals:
            self.assertIn('recursive_error_pages off;',block)
            self.assertIn('return 503 ',block)

    def test_prefile_body_limits_are_global_per_kind_and_cover_context_aliases(self):
        import re
        text=entry.render(config(),{a:[1,2] for a in entry.APPS})
        for name,kind in [('files','files'),('guest','guest'),('provider','provider'),('account','account')]:
            block=re.search(r'map "\$request_method:\$uri" \$four_'+name+r'_body \{(.*?)\n    \}',text,re.S).group(1)
            self.assertIn('default "";',block);self.assertIn('$server_name:'+kind,block);self.assertNotIn('$binary_remote_addr',block)
            pattern=re.search(r'~([^ ]+) "',block).group(1)
            route={'files':'api/workspace','guest':'api/guest-session','provider':'api/run','account':'api/account'}[name]
            self.assertRegex('POST:/'+route,pattern);self.assertRegex('POST:/starhub/'+route,pattern)
            self.assertIsNone(re.search(pattern,'GET:/'+route));self.assertIsNone(re.search(pattern,'GET:/realtime'))
        self.assertIn('limit_conn four_files_body 1;',text);self.assertIn('limit_conn four_guest_body 1;',text)
        self.assertIn('limit_conn four_provider_body 2;',text);self.assertIn('limit_conn_status 429;',text)
        self.assertIn('if ($four_starhub_post_denied) { return 404; }',text)
        self.assertIn('if ($uri ~ ";") { return 400; }',text)
        self.assertIn('provider-media',text);self.assertIn('client_max_body_size 16k;',text);self.assertIn('client_max_body_size 1m;',text)
        self.assertIn('proxy_pass_request_body off;',text)

    def test_bounded_star_json_streams_to_tomcat_without_request_spooling(self):
        text=entry.render(config(),{a:[1,2] for a in entry.APPS})
        star=text.split('server_name starhub.example.invalid;',1)[1].split('    server {',1)[0]
        self.assertIn('proxy_http_version 1.1;',star)
        self.assertIn('proxy_request_buffering off;',star)
        self.assertNotIn('proxy_request_buffering on;',star)
        self.assertNotIn('non_idempotent',star)
        self.assertIn('proxy_next_upstream off;',star)
        self.assertIn('client_max_body_size 6m;',star)
        self.assertIn('proxy_request_buffering on;',text)

    def test_failed_nginx_validation_cannot_publish_a_live_configuration(self):
        with tempfile.TemporaryDirectory() as folder:
            value=config();value['runtimeDirectory']=folder
            private=Path(folder)/'private';private.mkdir(mode=0o700)
            existing=private/'nginx.conf';existing.write_text('existing private config')
            publisher=entry.Publisher(value,command=lambda *a,**kw:types.SimpleNamespace(returncode=1),audit=self.local_audit,master=lambda:None)
            with self.assertRaises(entry.Closed):publisher.publish({a:[1] for a in entry.APPS})
            self.assertEqual(existing.read_text(),'existing private config')
            self.assertIsNone(publisher.previous)
            self.assertEqual(stat.S_IMODE((private/'nginx.candidate.conf').stat().st_mode),0o600)

    def test_loopback_admission_returns_only_freshness_authorization_without_user_data(self):
        state=entry.Admission();state.observed=state.clock();state.publish({a:[1] for a in entry.APPS})
        server=ThreadingHTTPServer(('127.0.0.1',0),entry.handler(state));thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            base='http://127.0.0.1:'+str(server.server_port)
            self.assertEqual(entry.Http(1).get(base+'/admit/iafm').status,204)
            status=entry.Http(1).get(base+'/status').object()
            self.assertTrue(status['fresh']);self.assertEqual(status['healthyNodeIds']['iafm'],[1])
            self.assertNotIn(TOKEN,json.dumps(status))
            self.assertEqual(entry.Http(1).get(base+'/ready').status,403)
            state.publish({a:[] for a in entry.APPS});self.assertEqual(entry.Http(1).get(base+'/admit/iafm').status,403)
        finally:server.shutdown();server.server_close();thread.join()

    def test_successful_publication_is_private_deduplicated_and_refuses_an_unrelated_pid(self):
        with tempfile.TemporaryDirectory() as folder:
            value=config();value['runtimeDirectory']=folder
            private=Path(folder)/'private';private.mkdir(mode=0o700)
            calls=[]
            publisher=entry.Publisher(value,command=lambda *args,**kw:(calls.append(args) or types.SimpleNamespace(returncode=0)),audit=self.local_audit,master=lambda:None)
            live={a:[1] for a in entry.APPS};publisher.publish(live)
            self.assertEqual(stat.S_IMODE((private/'nginx.conf').stat().st_mode),0o600)
            before=(private/'nginx.conf').read_text();publisher.publish(live);self.assertEqual(len(calls),1)
            (private/'nginx.pid').write_text(str(os.getpid()))
            with self.assertRaises(entry.Closed):publisher.publish({a:[2] for a in entry.APPS})
            self.assertEqual((private/'nginx.conf').read_text(),before)

    def test_nginx_test_can_create_only_safe_empty_pid_with_no_live_master(self):
        for initial in ('absent','empty'):
            with tempfile.TemporaryDirectory() as folder:
                value=config();value['runtimeDirectory']=folder;private=Path(folder)/'private';private.mkdir(mode=0o700);pid=private/'nginx.pid'
                if initial=='empty':pid.touch(mode=0o644)
                calls=[]
                def command(args,**kwargs):
                    calls.append(args);self.assertIn('-t',args)
                    if not pid.exists():pid.touch(mode=0o644)
                    return types.SimpleNamespace(returncode=0)
                publisher=entry.Publisher(value,command=command,audit=self.local_audit,master=lambda:None)
                publisher.publish({a:[] for a in entry.APPS});self.assertIsNotNone(publisher.previous);self.assertEqual(len(calls),1);self.assertEqual(pid.read_bytes(),b'')
                self.assertEqual(stat.S_IMODE((private/'nginx.conf').stat().st_mode),0o600)

    def test_empty_with_master_and_stale_numeric_never_trigger_test_or_reload(self):
        for content,master in [(b'',{'pid':123,'startTicks':1}),(b'123\n',None)]:
            with tempfile.TemporaryDirectory() as folder:
                value=config();value['runtimeDirectory']=folder;private=Path(folder)/'private';private.mkdir(mode=0o700);(private/'nginx.pid').write_bytes(content);calls=[]
                publisher=entry.Publisher(value,command=lambda *a,**k:calls.append(a),audit=self.local_audit,master=lambda:master)
                with self.assertRaises(entry.Closed):publisher.publish({a:[] for a in entry.APPS})
                self.assertEqual(calls,[]);self.assertIsNone(publisher.previous)

    def test_pid_symlink_wrong_owner_and_nonprivate_parent_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            private=Path(folder)/'private';private.mkdir(mode=0o700);pid=private/'nginx.pid';target=Path(folder)/'target';target.write_bytes(b'');pid.symlink_to(target)
            with self.assertRaises(entry.Closed):entry._private_nginx_pid(pid,os.geteuid(),os.getegid())
            pid.unlink();pid.touch(mode=0o644)
            with self.assertRaises(entry.Closed):entry._private_nginx_pid(pid,os.geteuid()+1,os.getegid())
            real_lstat=Path.lstat
            def wrong_pid_owner(path,*args,**kwargs):
                row=real_lstat(path,*args,**kwargs)
                if path==pid:return types.SimpleNamespace(st_mode=row.st_mode,st_uid=os.geteuid()+1,st_gid=row.st_gid,st_nlink=row.st_nlink,st_size=row.st_size)
                return row
            with mock.patch.object(Path,'lstat',wrong_pid_owner):
                with self.assertRaises(entry.Closed):entry._private_nginx_pid(pid,os.geteuid(),os.getegid())
            private.chmod(0o750)
            with self.assertRaises(entry.Closed):entry._private_nginx_pid(pid,os.geteuid(),os.getegid())
            self.assertEqual(target.read_bytes(),b'')

    def test_live_master_is_bound_across_validation_and_reload(self):
        with tempfile.TemporaryDirectory() as folder:
            value=config();value['runtimeDirectory']=folder;private=Path(folder)/'private';private.mkdir(mode=0o700);(private/'nginx.pid').write_text('123\n');calls=[];proof={'pid':123,'startTicks':42,'executable':(1,2),'cmdlineSha256':'a'*64}
            publisher=entry.Publisher(value,command=lambda args,**kw:(calls.append(args) or types.SimpleNamespace(returncode=0)),audit=self.local_audit,master=lambda:proof)
            publisher.publish({a:[1] for a in entry.APPS});self.assertEqual(len(calls),2);self.assertEqual(calls[1][1:3],['-s','reload']);self.assertEqual((private/'nginx.pid').read_text(),'123\n')
            old=(private/'nginx.conf').read_text();publisher.master=mock.Mock(side_effect=[proof,proof,{**proof,'startTicks':43}]);calls.clear()
            with self.assertRaises(entry.Closed):publisher.publish({a:[2] for a in entry.APPS})
            self.assertEqual(len(calls),1);self.assertIn('-t',calls[0]);self.assertEqual((private/'nginx.conf').read_text(),old)

    def test_master_change_during_nginx_test_refuses_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            value=config();value['runtimeDirectory']=folder;private=Path(folder)/'private';private.mkdir(mode=0o700);pid=private/'nginx.pid';pid.write_text('123\n');proof={'pid':123,'startTicks':1};calls=[]
            publisher=entry.Publisher(value,command=lambda args,**kw:(calls.append(args) or types.SimpleNamespace(returncode=0)),audit=self.local_audit,master=mock.Mock(side_effect=[proof,{**proof,'startTicks':2}]))
            with self.assertRaises(entry.Closed):publisher.publish({a:[1] for a in entry.APPS})
            self.assertIsNone(publisher.previous);self.assertFalse((private/'nginx.conf').exists());self.assertEqual(len(calls),1)

    def nginx_proc_fixture(self,base):
        base=base.resolve();proc=base/'proc';(proc/'self').mkdir(parents=True);(proc/'self/status').write_text('synthetic proc view');binary=base/'nginx';binary.write_bytes(b'synthetic trusted launch binary');binary.chmod(0o755);unitfile=base/'nginx.service';unitfile.write_text('synthetic reviewed unit');unitfile.chmod(0o644)
        value=config();value['runtimeDirectory']=str(base);value['nginxBinary']=str(binary);conf=str(base/'private/nginx.conf');unit='codex-lab-nginx.service';group='/system.slice/'+unit;cgroups=base/'cgroups';(cgroups/group.lstrip('/')).mkdir(parents=True);(cgroups/group.lstrip('/')/'cgroup.procs').write_text('124\n')
        state={'Id':unit,'LoadState':'loaded','ActiveState':'inactive','SubState':'dead','MainPID':'0','ControlPID':'0','InvocationID':'a'*32,'ControlGroup':group,'User':'root','Group':'','Type':'simple','FragmentPath':str(unitfile),'DropInPaths':'','ExecStart':'{ path='+str(binary)+' ; argv[]='+str(binary)+' -c '+conf+' -g daemon off; ; ignore_errors=no ; start_time=[synthetic] ; stop_time=[n/a] ; pid=124 ; code=(null) ; status=0/0 }'}
        def process(pid,exe,title,uid=None):
            row=proc/str(pid);row.mkdir();(row/'comm').write_text(Path(exe).name+'\n');(row/'exe').symlink_to(exe);(row/'cmdline').write_bytes(title.encode()+b'\0');(row/'stat').write_text(str(pid)+' (nginx) S '+'0 '*18+'42');(row/'status').write_text('Uid:\t'+('\t'.join([str(os.geteuid() if uid is None else uid)]*4))+'\n');(row/'cgroup').write_text('0::'+group+'\n');return row
        def observe():return entry._entry_nginx_master(value,proc,os.geteuid(),lambda _:dict(state),cgroups)
        return value,binary,conf,state,process,observe,cgroups

    def test_systemd_proc_identity_excludes_python_literal_and_foreign_master_without_exe_access(self):
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder);value,binary,conf,state,process,observe,cgroups=self.nginx_proc_fixture(base);python=base/'python';python.write_bytes(b'synthetic other executable')
            process(123,python,'python -c "nginx: master process '+str(binary)+' -c '+conf+'"')
            self.assertIsNone(observe());row=process(124,binary,'nginx: master process '+str(binary)+' -c '+conf+'.other -g daemon off;');self.assertIsNone(observe())
            (row/'cmdline').write_bytes(('nginx: master process '+str(binary)+' -c '+conf+' -g daemon off;').encode()+b'\0');state.update(ActiveState='active',SubState='running',MainPID='124')
            process(125,binary,'nginx: worker process',os.geteuid()+1)
            real_stat=Path.stat
            def worker_exe_denied(path,*args,**kwargs):
                if path.name=='exe':raise PermissionError('synthetic actual checker capability boundary')
                return real_stat(path,*args,**kwargs)
            with mock.patch.object(Path,'stat',worker_exe_denied):found=observe()
            self.assertEqual(found['pid'],124);self.assertEqual(found['startTicks'],42);self.assertEqual(found['invocationId'],'a'*32);self.assertFalse(found['procExeInodeVerified']);self.assertEqual(found['identityAuthority'],'local-systemd-plus-visible-proc')

    def test_live_service_and_proc_bindings_reject_wrong_pid_args_user_cgroup_unit_or_incarnation(self):
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder);value,binary,conf,state,process,observe,cgroups=self.nginx_proc_fixture(base);row=process(124,binary,'nginx: master process '+str(binary)+' -c '+conf+' -g daemon off;');state.update(ActiveState='active',SubState='running',MainPID='124');baseline=dict(state)
            for change in ({'MainPID':'126'},{'InvocationID':'bad'},{'ControlGroup':'/system.slice/other.service'},{'User':'www-data'},{'ControlPID':'127'},{'ExecStart':state['ExecStart'].replace(conf,conf+'.other')},{'DropInPaths':'/unreviewed/override.conf'}):
                state.clear();state.update(baseline,**change)
                with self.assertRaises(entry.Closed):observe()
            state.clear();state.update(baseline);(row/'cgroup').write_text('0::/system.slice/other.service\n')
            with self.assertRaises(entry.Closed):observe()
            (row/'cgroup').write_text('0::'+state['ControlGroup']+'\n');(cgroups/state['ControlGroup'].lstrip('/')/'cgroup.procs').write_text('125\n')
            with self.assertRaises(entry.Closed):observe()
            value['nginxUnit']='unexpected.service'
            with self.assertRaises(entry.Closed):observe()

    def test_systemd_invocation_change_during_proc_observation_and_unknown_root_master_refuse(self):
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder);value,binary,conf,state,process,observe,cgroups=self.nginx_proc_fixture(base);process(124,binary,'nginx: master process '+str(binary)+' -c '+conf+' -g daemon off;');state.update(ActiveState='active',SubState='running',MainPID='124')
            calls=[dict(state),{**state,'InvocationID':'b'*32}]
            with self.assertRaises(entry.Closed):entry._entry_nginx_master(value,base/'proc',os.geteuid(),lambda _:calls.pop(0),cgroups)
            process(126,binary,'nginx: master process '+str(binary)+' -c '+conf+' -g daemon off;')
            with self.assertRaises(entry.Closed):observe()

if __name__=='__main__':unittest.main()
