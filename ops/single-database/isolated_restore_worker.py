#!/usr/bin/env python3
"""One fresh standalone socket restore. Must run ONLY in the reviewed transient sandbox."""
import gzip,hashlib,json,os,pathlib,signal,stat,subprocess,sys,time,uuid,selectors,re
from sql_import_validator import validate_gzip
D=pathlib.Path(__file__).resolve().parent
class Stop(Exception):pass
KNOWN_FAILURE_CODES=['ALIAS_DIRECTORY_REQUIRED', 'ALIAS_MOUNT_ENTRY_REQUIRED', 'ALIAS_NOT_OWNED_SAME_INODE', 'ALIAS_UID_MAPPING_CHANGED', 'ATTESTATION_CHANGED', 'ATTESTATION_IDENTITY_MISMATCH', 'ATTESTATION_SIZE_LIMIT', 'BINARY_BINDING', 'CANONICAL_AUDIT_SOURCE', 'CANONICAL_UUID', 'CHILD_FAILED_fingerprint', 'CHILD_FAILED_initialize', 'CONTENT_MISMATCH', 'SCHEMA_MISMATCH', 'EXACT_OWNED_CGROUP', 'HOST_DEFAULT_DIRECTORY_EXPOSED', 'HOST_MOUNT_NAMESPACE_NOT_ISOLATED', 'IMPORT_FAILED', 'IMPORT_PIPE_TIMEOUT_OR_EXIT', 'IMPORT_TIMEOUT', 'IMPORT_TIMEOUT_OR_EXIT', 'INITIALIZER_APPARMOR_NOT_ENFORCED', 'ISOLATION_FLAGS', 'MYSQL_PID_SOURCE_PROFILE', 'MYSQL_TERMINAL_UNPROVEN', 'MYSQL_VERSION', 'OWNED_STATE_ROOT', 'OWNED_TREE_SYMLINK', 'PHASE_TIMEOUT', 'PRODUCTION_DATADIR_VISIBLE', 'RESOURCE_FLOOR', 'ROOT_ATTESTATION_PARENT_REQUIRED', 'ROOT_ATTESTATION_REQUIRED', 'ROOT_WORKER_REFUSED', 'SANDBOX_REQUIRED', 'START_DISK_FLOOR', 'START_FAILED', 'START_MEMORY_FLOOR', 'STATE_ROOT_NOT_EMPTY', 'TRANSFER_HASH', 'TRANSFER_LIMIT', 'UNIT_ATTESTATION_TIMEOUT', 'UNIT_SANDBOX_PROPERTIES_CHANGED']
def safe_failure_code(error):return str(error)if isinstance(error,Stop)and str(error)in KNOWN_FAILURE_CODES else'UNCLASSIFIED'
def need(ok,code):
 if not ok:raise Stop(code)
def sha(p):
 h=hashlib.sha256()
 with open(p,'rb')as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def save(path,value):
 fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'w')as f:json.dump(value,f,sort_keys=True);f.write('\n');f.flush();os.fsync(f.fileno())
 fd=os.open(path.parent,os.O_RDONLY);os.fsync(fd);os.close(fd)
next_resource_check=0.0
def attestation_stat(value):
 need(stat.S_ISREG(value.st_mode)and value.st_uid==0 and stat.S_IMODE(value.st_mode)==0o644 and value.st_nlink==1,'ROOT_ATTESTATION_REQUIRED')
def wait_attestation(path,deadline):
 while not path.exists():resources();need(time.monotonic()<deadline,'UNIT_ATTESTATION_TIMEOUT');time.sleep(2)
def validate_attestation(value,run,unit,request_sha,pid,start_ticks,invocation):
 need(value['runId']==run and value['unit']==unit and value['requestSha256']==request_sha and value['MainPID']==str(pid)and value['startTicks']==start_ticks and value['InvocationID']==invocation and value['ControlGroup']=='/system.slice/'+unit,'ATTESTATION_IDENTITY_MISMATCH')
 need(value.get('PrivateMounts')=='yes'and value.get('ProtectSystem')=='strict'and '/var/lib/codex-validation'in value.get('InaccessiblePaths','')and '/var/lib/private/codex-restore-'+run+':/var/lib/mysql'in value.get('BindPaths',''),'UNIT_SANDBOX_PROPERTIES_CHANGED')
 return value
def resources(force=False):
 global next_resource_check
 now=time.monotonic()
 if not force and now<next_resource_check:return
 next_resource_check=now+2
 values={k:int(v.split()[0])*1024 for k,v in (x.split(':',1)for x in pathlib.Path('/proc/meminfo').read_text().splitlines())};s=os.statvfs(root)
 need(values['MemAvailable']>=100*1024**2 and s.f_bfree*s.f_frsize>=4000000000,'RESOURCE_FLOOR')
def run(args,timeout,name,input_bytes=None):
 resources(True);log=alias/(name+'.private.log');fd=os.open(log,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
 with os.fdopen(fd,'wb')as out:
  child=subprocess.Popen(args,stdin=subprocess.PIPE if input_bytes is not None else subprocess.DEVNULL,stdout=out,stderr=out,start_new_session=False);children.append(child)
  if name=='initialize':
   profile=(pathlib.Path('/proc')/str(child.pid)/'attr/current').read_text().strip();need(profile=='/usr/sbin/mysqld (enforce)','INITIALIZER_APPARMOR_NOT_ENFORCED');receipt['initializerAppArmorProfile']=profile
  if input_bytes is not None:child.stdin.write(input_bytes);child.stdin.close()
  deadline=time.monotonic()+timeout
  while child.poll()is None:
   try:resources();need(time.monotonic()<deadline,'PHASE_TIMEOUT')
   except BaseException:child.terminate();child.wait(timeout=10);raise
   time.sleep(2)
  need(child.returncode==0,'CHILD_FAILED_'+name)
def pipe_write(child,data,deadline):
 fd=child.stdin.fileno();os.set_blocking(fd,False);offset=0
 with selectors.DefaultSelector()as selector:
  selector.register(fd,selectors.EVENT_WRITE)
  while offset<len(data):
   resources();need(time.monotonic()<deadline and child.poll()is None,'IMPORT_PIPE_TIMEOUT_OR_EXIT')
   if not selector.select(min(2,max(0,deadline-time.monotonic()))):continue
   try:offset+=os.write(fd,data[offset:offset+65536])
   except BlockingIOError:pass
def mysql(sql):
 return subprocess.run(['/usr/bin/mysql','--defaults-file='+str(defaults),'--no-login-paths','--protocol=SOCKET','--socket='+str(sock),'--skip-reconnect','--batch','--raw','--skip-column-names'],input=sql.encode(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10,check=True).stdout.decode().strip()
def identity(child):
 p=pathlib.Path('/proc')/str(child.pid);return {'pid':child.pid,'startTicks':(p/'stat').read_text().split(') ',1)[1].split()[19],'exeSha256':sha(p/'exe'),'cgroup':(p/'cgroup').read_text().strip(),'uid':p.stat().st_uid,'appArmorProfile':(p/'attr/current').read_text().strip()}
def same(child,proof):
 if child.poll()is not None:return False
 try:return identity(child)==proof
 except FileNotFoundError:return False
def alias_identity(owned,alias,host,self_namespace,host_namespace):
 need((owned.st_dev,owned.st_ino)==(alias.st_dev,alias.st_ino),'ALIAS_NOT_OWNED_SAME_INODE')
 need(owned.st_uid==alias.st_uid,'ALIAS_UID_MAPPING_CHANGED')
 need((alias.st_dev,alias.st_ino)!=(host['dev'],host['ino']),'HOST_DEFAULT_DIRECTORY_EXPOSED')
 need(self_namespace!=host_namespace,'HOST_MOUNT_NAMESPACE_NOT_ISOLATED')
 return {'ownedDevice':owned.st_dev,'ownedInode':owned.st_ino,'aliasDevice':alias.st_dev,'aliasInode':alias.st_ino,'selfMountNamespace':self_namespace,'hostMountNamespace':host_namespace,'sameOwnedBacking':True,'hostDefaultDirectoryHidden':True,'ownedUid':owned.st_uid,'aliasUid':alias.st_uid}
request=json.loads((D/'request.json').read_bytes());runid=str(uuid.UUID(request['runId']));need(runid==request['runId']and request['unit']=='codex-restore-'+runid+'.service','CANONICAL_UUID');need(os.getuid()!=0,'ROOT_WORKER_REFUSED')
need(os.environ.get('INVOCATION_ID')and os.environ.get('STATE_DIRECTORY'),'SANDBOX_REQUIRED');need(pathlib.Path('/proc/self/cgroup').read_text().strip()=='0::/system.slice/'+request['unit'],'EXACT_OWNED_CGROUP');root=pathlib.Path(os.environ['STATE_DIRECTORY']).resolve(strict=True);need(root.name=='codex-restore-'+runid and root.parent in (pathlib.Path('/var/lib'),pathlib.Path('/var/lib/private')),'OWNED_STATE_ROOT');need(not list(root.iterdir()),'STATE_ROOT_NOT_EMPTY')
alias=pathlib.Path('/var/lib/mysql');binding={};unit_properties={};production_denied=False
need(sha('/usr/sbin/mysqld')==request['mysqldSha256']and sha('/usr/bin/mysqldump')==request['mysqldumpSha256'],'BINARY_BINDING');need(re.search(r'(?<![0-9])26\.10\.0(?![0-9])',subprocess.check_output(['/usr/sbin/mysqld','--no-defaults','--version'],stderr=subprocess.DEVNULL).decode()) is not None,'MYSQL_VERSION')
children=[];server=None;serverproof=None;defaults=root/'client.cnf';sock=alias/'mysql.sock';receipt={'format':'isolated-single-db-restore-v1','runId':runid,'sourceServer':request['sourceServer'],'maintenanceRunId':request['maintenanceRunId'],'invocationId':os.environ['INVOCATION_ID'],'unit':request['unit'],'workerPid':os.getpid(),'workerCgroup':pathlib.Path('/proc/self/cgroup').read_text().strip(),'status':'FAILED','mountAliasBinding':binding,'unitSandboxProperties':unit_properties,'productionDatadirDeniedInsideUnit':production_denied,'productionActions':False,'keysReceived':False,'backupBinding':{k:request[k]for k in ('sourceManifestSha256','sourceCipherSha256','compressedSha256','expandedSha256','expectedFingerprint')}};stage='receive'
try:
 stage='namespace-bind-preflight'
 alias=pathlib.Path('/var/lib/mysql');need(alias.is_dir()and not alias.is_symlink(),'ALIAS_DIRECTORY_REQUIRED');binding=alias_identity(root.stat(),alias.stat(),request['hostDefaultMysqlIdentity'],os.readlink('/proc/self/ns/mnt'),request['hostMountNamespace']);need(any(line.split()[4]=='/var/lib/mysql'for line in pathlib.Path('/proc/self/mountinfo').read_text().splitlines()),'ALIAS_MOUNT_ENTRY_REQUIRED')
 try:pathlib.Path('/var/lib/codex-validation/data').stat()
 except (PermissionError,FileNotFoundError):production_denied=True
 else:raise Stop('PRODUCTION_DATADIR_VISIBLE')
 save(root/'preflight-ready.json',{'runId':runid,'unit':request['unit'],'workerPid':os.getpid(),'invocationId':os.environ['INVOCATION_ID'],'workerCgroup':pathlib.Path('/proc/self/cgroup').read_text().strip()})
 parent_stat=D.lstat();need(not D.is_symlink()and parent_stat.st_uid==0 and not stat.S_IMODE(parent_stat.st_mode)&0o022,'ROOT_ATTESTATION_PARENT_REQUIRED');attestation=D/'attestation.json';deadline=time.monotonic()+45
 wait_attestation(attestation,deadline)
 fd=os.open(attestation,os.O_RDONLY|os.O_NOFOLLOW)
 try:
  before=os.fstat(fd);attestation_stat(before);raw=os.read(fd,65537);need(len(raw)<=65536,'ATTESTATION_SIZE_LIMIT');after=os.fstat(fd);need((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns),'ATTESTATION_CHANGED');attested=json.loads(raw)
 finally:os.close(fd)
 unit_properties=validate_attestation(attested,runid,request['unit'],sha(D/'request.json'),os.getpid(),pathlib.Path('/proc/self/stat').read_text().split(') ',1)[1].split()[19],os.environ['INVOCATION_ID']);receipt['attestationSha256']=hashlib.sha256(raw).hexdigest()
 if request.get('offlineWindowId'):
  need(attested.get('offlineWindowId')==request['offlineWindowId']and attested.get('offlineWindowProofSha256')==request['offlineWindowProofSha256'],'ATTESTATION_IDENTITY_MISMATCH');receipt['offlineWindowId']=request['offlineWindowId'];receipt['offlineWindowProofSha256']=request['offlineWindowProofSha256']
 receipt.update(mountAliasBinding=binding,unitSandboxProperties=unit_properties,productionDatadirDeniedInsideUnit=production_denied)
 # Receive only the already authenticated gzip; never keys or account/grant material.
 resources(True);s=os.statvfs(root);need(s.f_bfree*s.f_frsize>=request['minimumStartPhysicalFreeBytes'],'START_DISK_FLOOR');mem={k:int(v.split()[0])*1024 for k,v in (x.split(':',1)for x in pathlib.Path('/proc/meminfo').read_text().splitlines())};need(mem['MemAvailable']>=1024*1024**2 and mem['SwapFree']>=1024**3,'START_MEMORY_FLOOR')
 compressed=root/'business.sql.gz';fd=os.open(compressed,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600);h=hashlib.sha256();total=0
 with os.fdopen(fd,'wb')as f:
  while True:
   b=sys.stdin.buffer.read(1024*1024)
   if not b:break
   total+=len(b);need(total<=request['compressedBytes'],'TRANSFER_LIMIT');h.update(b);f.write(b)
  f.flush();os.fsync(f.fileno())
 need(total==request['compressedBytes']and h.hexdigest()==request['compressedSha256'],'TRANSFER_HASH');stage='validate'
 # No mysqld exists during parser memory use. Full expanded hash precedes SQL execution.
 validated=validate_gzip(compressed,request['expandedBytes'],request['expandedSha256'],request['schemas']);need(validated['tableCount']==request['tableCount'],'CONTENT_MISMATCH');receipt['validator']=validated
 for name in ('data','tmp'):os.mkdir(root/name,0o700)
 defaults.write_text('[client]\nuser=root\nprotocol=SOCKET\nsocket='+str(sock)+'\n');defaults.chmod(0o600)
 args=['/usr/sbin/mysqld','--no-defaults','--datadir='+str(alias/'data'),'--socket='+str(sock),'--pid-file='+str(alias/'mysql.pid'),'--log-error='+str(alias/'mysql.private.log'),'--tmpdir='+str(alias/'tmp'),'--skip-networking','--port=0','--mysqlx=OFF','--skip-log-bin','--gtid-mode=OFF','--enforce-gtid-consistency=OFF','--persisted-globals-load=OFF','--event-scheduler=OFF','--secure-file-priv=NULL','--local-infile=OFF','--skip-name-resolve','--innodb-buffer-pool-size=64M','--innodb-redo-log-capacity=64M','--max-connections=4','--table-open-cache=200','--performance-schema=OFF','--max-allowed-packet=64M','--sql-mode=NO_AUTO_VALUE_ON_ZERO']
 stage='initialize';run(args+['--initialize-insecure'],120,'initialize');stage='start';resources(True)
 out=open(alias/'server.private.log','xb');os.chmod(root/'server.private.log',0o600);server=subprocess.Popen(args,stdin=subprocess.DEVNULL,stdout=out,stderr=out);out.close();children.append(server);serverproof=identity(server);need(serverproof['exeSha256']==request['mysqldSha256']and serverproof['uid']==os.getuid()and serverproof['appArmorProfile']=='/usr/sbin/mysqld (enforce)','MYSQL_PID_SOURCE_PROFILE');receipt['mysqldIdentity']=serverproof
 deadline=time.monotonic()+60
 while True:
  resources();need(server.poll()is None and time.monotonic()<deadline,'START_FAILED')
  if sock.exists():
   try:mysql('SELECT 1');break
   except Exception:pass
  time.sleep(2)
 # Fingerprint helper independently checks datadir/socket/network/binlog/GR/event/GTID isolation.
 stage='isolation';isolation=json.loads(mysql("SELECT JSON_OBJECT('network',@@skip_networking,'binlog',@@log_bin,'events',@@event_scheduler,'gtid',@@gtid_mode,'persisted',@@persisted_globals_load,'datadir',@@datadir,'socket',@@socket,'uuid',@@server_uuid,'port',@@port,'groupPlugins',(SELECT COUNT(*) FROM information_schema.PLUGINS WHERE PLUGIN_NAME='group_replication' AND PLUGIN_STATUS='ACTIVE'))"));need(isolation['network']==1 and isolation['port']==0 and isolation['groupPlugins']==0 and isolation['binlog']==0 and isolation['events']=='OFF'and isolation['gtid']=='OFF'and isolation['persisted']==0 and os.path.samefile(pathlib.Path(isolation['datadir']),root/'data')and isolation['socket']==str(sock),'ISOLATION_FLAGS');receipt['isolation']=isolation
 stage='import';resources(True);log=open(root/'import.private.log','xb');os.chmod(root/'import.private.log',0o600)
 importer=subprocess.Popen(['/usr/bin/mysql','--defaults-file='+str(defaults),'--no-login-paths','--protocol=SOCKET','--socket='+str(sock),'--skip-reconnect','--binary-mode','--local-infile=0','--max-allowed-packet=64M'],stdin=subprocess.PIPE,stdout=log,stderr=log);children.append(importer);deadline=time.monotonic()+600
 with gzip.open(compressed,'rb')as f:
  while True:
   b=f.read(1024*1024)
   if not b:break
   resources();need(time.monotonic()<deadline and importer.poll()is None,'IMPORT_TIMEOUT_OR_EXIT');pipe_write(importer,b,deadline)
 importer.stdin.close()
 while importer.poll()is None:resources();need(time.monotonic()<deadline,'IMPORT_TIMEOUT');time.sleep(2)
 log.close();need(importer.returncode==0,'IMPORT_FAILED');receipt['importExitCode']=0
 stage='fingerprint'
 actual=mysql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('mysql','sys','performance_schema','information_schema') ORDER BY SCHEMA_NAME").splitlines();need(actual==sorted(request['schemas']),'CONTENT_MISMATCH')
 fpcode="import sys;sys.path.insert(0,"+repr(str(D))+");from isolated_data_digest import data_digest;print(data_digest("+repr(str(defaults))+","+repr(request['schemas'])+"))"
 run(['/usr/bin/python3','-c',fpcode],900,'fingerprint');actualdigest=(root/'fingerprint.private.log').read_text().strip();need(actualdigest==request['expectedFingerprint'],'CONTENT_MISMATCH')
 stage='schema-fingerprint'
 schema_algorithm=request.get('schemaAlgorithm','mysql-schema-dump-sha256-v1')
 if schema_algorithm=='mysql-schema-dump-sha256-v1':
  ddlcode="import sys;sys.path.insert(0,"+repr(str(D))+");from isolated_data_digest import schema_digest;print(schema_digest("+repr(str(defaults))+","+repr(request['schemas'])+",output_path="+repr(str(root/'schema-dump.private.sql'))+"))"
  run(['/usr/bin/python3','-c',ddlcode],300,'schema-fingerprint');ddldigest=(root/'schema-fingerprint.private.log').read_text().strip();need(ddldigest==request['expectedSchemaFingerprint'],'SCHEMA_MISMATCH');schema_evidence={'schemaDefinitionSha256':ddldigest,'schemaAlgorithm':schema_algorithm}
 else:
  need(schema_algorithm=='mysql-schema-dump-charset-semantic-v2','SCHEMA_MISMATCH')
  ddlcode="import sys,json;sys.path.insert(0,"+repr(str(D))+");from isolated_data_digest import schema_digest;from schema_fingerprint import schema_hashes,schema_column_digests;p="+repr(str(root/'schema-dump.private.sql'))+";raw=schema_digest("+repr(str(defaults))+","+repr(request['schemas'])+",output_path=p);f=open(p,'rb');h=schema_hashes(f);f.close();assert raw==h['rawSha256'];c=schema_column_digests('/usr/bin/mysql',"+repr(str(defaults))+","+repr(request['schemas'])+",allowed_schemas=set("+repr(request['schemas'])+"));print(json.dumps({'schema':h,'columns':c}))"
  run(['/usr/bin/python3','-c',ddlcode],300,'schema-fingerprint');h=json.loads((root/'schema-fingerprint.private.log').read_text());need(h['schema']['schemaSha256']==request['expectedSchemaFingerprint'] and h['columns']['algorithm']==request['expectedColumnAlgorithm'] and h['columns']['sha256']==request['expectedColumnFingerprint'] and h['columns']['columnCount']==request['expectedColumnCount'],'SCHEMA_MISMATCH');schema_evidence={'schemaDefinitionSha256':h['schema']['schemaSha256'],'rawSchemaDefinitionSha256':h['schema']['rawSha256'],'sourceRawSchemaDefinitionSha256':request['sourceRawSchemaFingerprint'],'schemaAlgorithm':schema_algorithm,'columnMetadataAlgorithm':h['columns']['algorithm'],'columnMetadataSha256':h['columns']['sha256'],'columnCount':h['columns']['columnCount']}
 receipt['contentProof']={'matchesAuthenticatedBackupMetadata':True,'nativeDataSha256':actualdigest,**schema_evidence,'schemaCount':len(actual),'tableCount':validated['tableCount'],'routinesEventsTriggersAccepted':False};receipt['status']='PASS'
except BaseException as error:
 receipt.update(failedStage=stage,errorClass=type(error).__name__,failedCheckCode=safe_failure_code(error),status='FAILED')
finally:
 # Never signal a PID based only on its number; compare executable/start/cgroup ownership.
 for child in reversed(children):
  if child is server:continue
  if child.poll()is None:
   child.terminate()
   try:child.wait(timeout=10)
   except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
 if server is not None and server.poll()is None:
  if same(server,serverproof):
   try:mysql('SHUTDOWN')
   except Exception:pass
   try:server.wait(timeout=30)
   except subprocess.TimeoutExpired:
    if same(server,serverproof):server.terminate()
    try:server.wait(timeout=10)
    except subprocess.TimeoutExpired:
     if same(server,serverproof):server.kill()
     server.wait(timeout=10)
  else:receipt['status']='FAILED';receipt['failedStage']='PID_BINDING_CHANGED'
 receipt.update(mountAliasBinding=binding,unitSandboxProperties=unit_properties,productionDatadirDeniedInsideUnit=production_denied)
 receipt['ownedMysqlExited']=server is None or server.poll()is not None;need(receipt['ownedMysqlExited'],'MYSQL_TERMINAL_UNPROVEN')
 save(root/'native-result.json',receipt)
 entries=[]
 for p in sorted(root.rglob('*')):
  st=p.lstat();need(not stat.S_ISLNK(st.st_mode),'OWNED_TREE_SYMLINK')
  if p.is_file():entries.append({'path':str(p.relative_to(root)),'bytes':st.st_size,'sha256':sha(p)})
  elif not p.is_dir():raise Stop('OWNED_TREE_SPECIAL_FILE')
 save(root/'owned-files.json',{'runId':runid,'files':entries})
 # Native result is durable BEFORE stdout; unit terminal proof is the launcher's later responsibility.
 print(json.dumps({'runId':runid,'status':receipt['status'],'receiptSha256':sha(root/'native-result.json'),'manifestSha256':sha(root/'owned-files.json'),'ownedMysqlExited':receipt['ownedMysqlExited'],'unitTerminalStillRequiresLauncherProof':True}),flush=True)
