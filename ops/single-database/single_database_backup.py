"""Single-server encrypted backup; without --execute no files or network are touched."""
import argparse,base64,datetime,gzip,hashlib,json,os,pathlib,shlex,stat,subprocess,sys,tarfile,threading,uuid
P=pathlib.Path;HERE=P(__file__).resolve().parent
REMOTE=HERE/'remote_backup.py';ENV=HERE/'recovery_envelope.cjs';CAPTURE=HERE/'capture_config.py';VERIFY=HERE/'archive_verify.py';SCHEMA=HERE/'schema_fingerprint.py'
REMOTE_SHA='107decfa039638dbacdd02485337536464ecb03c3d8b89783cf852c6e1f4cffe'
SCHEMA_SHA='5791644716552b00ea582186e4b0fb6107fc31e93634c4a2251954748b2e5c21'
CAPTURE_SHA='e36ff56a733603026f4ffe4eaa684c657679998c8e0efc64a01d545ac84ec0d9'
VERIFY_SHA='8bbce637013e5e6d96f6687b092ea96b463bec1becc6da4be87f483b249c4729'
ENV_SHA='5e22f75a7dab7b366ad34a8bab9e52ea19e27da49c9f05952b0e1aae0c315f7d'
NODE=P('/Users/zhongjiehuang/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
KEYS=[P('/Users/zhongjiehuang/Documents/Server_Recovery_Keys/Primary/archive-master.key'),P('/Users/zhongjiehuang/Documents/Server_Recovery_Keys/Secondary/archive-master.key')]
BACKUPS=P.home()/'Library/CloudStorage/OneDrive-Personal/Zhongjie_Huang_s_Database/Historical_Archives/Database_Backups'
PRIVATE=P.home()/'Library/Application Support/ServerRecovery/Private/Work/Single_Database_Backup';S2=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','-i','/Users/zhongjiehuang/Documents/SSH/LightsailDefaultKey-eu-west-2.pem','ubuntu@13.42.147.134'];S1=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','root@8.211.120.109']
def need(v):
 if not v:raise RuntimeError('backup precondition refused')
def digest(p):
 h=hashlib.sha256()
 with P(p).open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def privatefile(p):
 s=p.lstat();need(stat.S_ISREG(s.st_mode) and not p.is_symlink() and s.st_uid==os.getuid() and stat.S_IMODE(s.st_mode)==0o600 and s.st_nlink==1)
def privatewrite(path,value):
 raw=value if isinstance(value,bytes) else (json.dumps(value,sort_keys=True,indent=2)+'\n').encode();fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'wb') as f:f.write(raw);f.flush();os.fsync(f.fileno())
def ssh_remote(action,stream=None):
 need(action in ('proof','metadata','dump','content-digest'));remote='sudo -n python3 -B - '+action
 module=SCHEMA.read_bytes();remote_code=REMOTE.read_bytes();need(hashlib.sha256(module).hexdigest()==SCHEMA_SHA and hashlib.sha256(remote_code).hexdigest()==REMOTE_SHA)
 encoded=base64.b64encode(module).decode('ascii');prefix=('import base64,sys,types\nm=types.ModuleType("schema_fingerprint")\nexec(compile(base64.b64decode('+repr(encoded)+'),"<pinned-schema-fingerprint>","exec"),m.__dict__)\nsys.modules["schema_fingerprint"]=m\n').encode()
 with subprocess.Popen(S2+[remote],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL) as p:
  timer=threading.Timer(1800,p.kill);timer.daemon=True;timer.start()
  try:
   p.stdin.write(prefix+remote_code);p.stdin.close()
   if stream is None:result=p.stdout.read();need(len(result)<64*1024*1024)
   else:
    while True:
     b=p.stdout.read(1048576)
     if not b:break
     stream.write(b)
    result=None
   need(p.wait(timeout=1800)==0);return result
  finally:timer.cancel()
def capture_config(node,stream):
 need(node in (1,2));argv=(S1 if node==1 else S2)+[('sudo -n ' if node==2 else '')+'python3 -B - --node '+str(node)]
 with subprocess.Popen(argv,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL) as child:
  timer=threading.Timer(1800,child.kill);timer.daemon=True;timer.start()
  try:
   child.stdin.write(CAPTURE.read_bytes());child.stdin.close()
   while True:
    block=child.stdout.read(1048576)
    if not block:break
    stream.write(block)
   need(child.wait(timeout=1800)==0)
  finally:timer.cancel()
def s1_proof():
 script='''import subprocess,json\nout={}\nfor n in ("codex-lab-tomcat.service","codex-lab-nginx.service","codex-lab-entry-checker.service","codex-lab-mysql.service"):\n r=subprocess.run(["systemctl","show",n,"--property=ActiveState,MainPID,ControlPID,UnitFileState,ControlGroup"],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,check=True);v=dict(x.split("=",1) for x in r.stdout.splitlines() if "=" in x);g=v.get("ControlGroup","");v["CgroupEmpty"]=not g or all(not q.read_text().strip() for q in __import__("pathlib").Path("/sys/fs/cgroup"+g).rglob("cgroup.procs"));out[n]=v\nprint(json.dumps(out))'''
 p=subprocess.run(S1+['python3 -B -'],input=script.encode(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True,timeout=30);x=json.loads(p.stdout)
 for n,v in x.items():
  need(v.get('ActiveState') in ('inactive','failed') and v.get('MainPID')=='0' and v.get('ControlPID')=='0' and v.get('CgroupEmpty') is True)
  if n=='codex-lab-mysql.service':need(v.get('UnitFileState')=='masked')
 return x
def seal(source,target):
 privatefile(source);need(not target.exists());out=[]
 for mode,key in [('encrypt',KEYS[0]),('verify',KEYS[0]),('verify',KEYS[1])]:
  args=[str(NODE),str(ENV),mode,str(key)]+([str(source),str(target)] if mode=='encrypt' else [str(target)])
  r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=1800,check=True);out.append(json.loads(r.stdout))
 need(all(x.get('decryptVerified') is True for x in out[1:]));need(all(x['plaintextSha256']==digest(source) for x in out[1:]));return {'name':target.name,'encryptedSha256':digest(target),'encryptedBytes':target.stat().st_size,'plaintextSha256':digest(source),'plaintextBytes':source.stat().st_size,'bothRecoveryKeysAuthenticated':True}
p=argparse.ArgumentParser();p.add_argument('--execute',action='store_true');p.add_argument('--run',type=uuid.UUID);p.add_argument('--writers-stopped',action='store_true');p.add_argument('--server1-control-socket');p.add_argument('--node-binary');p.add_argument('--key-custody-file',type=P);a=p.parse_args()
if not a.execute:
 print(json.dumps({'mode':'LOCAL_PLAN_ONLY','networkCalls':0,'filesChanged':False,'backup':'Server 2 logical SQL and accounts plus independently verified encrypted configuration from both hosts','requires':'both app writers stopped, server1 mysql masked, stable GTID and same schema inventory; unique --run and --writers-stopped','restoreTest':'not included'}));raise SystemExit
try:
 need(a.run is not None and a.writers_stopped and digest(ENV)==ENV_SHA and digest(REMOTE)==REMOTE_SHA and digest(CAPTURE)==CAPTURE_SHA and digest(VERIFY)==VERIFY_SHA and digest(SCHEMA)==SCHEMA_SHA);NODE=P(a.node_binary) if a.node_binary else NODE;need(NODE.is_file() and os.access(NODE,os.X_OK));import archive_verify
 custody_path=a.key_custody_file or (P.home()/'Library/Application Support/ServerRecovery/Private/recovery-key-custody.json')
 privatefile(custody_path);need(custody_path.stat().st_size<=65536);custody=json.loads(custody_path.read_bytes());KEYS=[P(custody['primaryPath']),P(custody['secondaryPath'])];need(all(k.is_absolute() for k in KEYS))
 if a.server1_control_socket:S1[1:1]=['-S',a.server1_control_socket]
 need(BACKUPS.parent.is_dir() and PRIVATE.parent.parent.is_dir());PRIVATE.parent.mkdir(mode=0o700,exist_ok=True);PRIVATE.mkdir(mode=0o700,exist_ok=True)
 for p in (PRIVATE.parent,PRIVATE):
  s=p.lstat();need(stat.S_ISDIR(s.st_mode) and not p.is_symlink() and s.st_uid==os.getuid() and stat.S_IMODE(s.st_mode)==0o700 and 'onedrive' not in str(p.resolve()).lower())
 BACKUPS.mkdir(mode=0o700,exist_ok=True);need(BACKUPS.is_dir() and not BACKUPS.is_symlink());need(len(set(k.resolve(strict=True) for k in KEYS))==2)
 for k in KEYS:privatefile(k);need(k.stat().st_size==32 and 'onedrive' not in str(k.resolve()).lower())
 run=str(a.run);lock=PRIVATE/'single-database-backup-exclusive.lock';lock.mkdir(mode=0o700);folder=PRIVATE/('single-database-backup-'+run);folder.mkdir(mode=0o700);privatewrite(lock/'owner.json',{'runId':run,'unknownMustNotReplay':True});privatewrite(folder/'intent.json',{'runId':run,'status':'INTENT','unknownMustNotReplay':True,'expectedMysqlUuid':'5b042440-ddee-11f1-8a71-06ffe4b75559'})
 start=datetime.datetime.now(datetime.timezone.utc);dest=BACKUPS/('backup-'+run);dest.mkdir(mode=0o700)
 pre1=s1_proof();pre=json.loads(ssh_remote('proof'));need(pre['inventory']['baseTableCount']>0);privatewrite(folder/'before.json',{'server1':pre1,'server2':pre})
 content_before=json.loads(ssh_remote('content-digest'));need(content_before['identity']==pre['identity'] and content_before['inventory']==pre['inventory']);metadata=ssh_remote('metadata');privatewrite(folder/'accounts-and-grants.json',metadata);m=json.loads(metadata);need(m['identity']==pre['identity'] and m['inventory']==pre['inventory'])
 dump=folder/'All_Databases.sql.gz';fd=os.open(dump,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'wb') as f:
  with gzip.GzipFile(fileobj=f,mode='wb',mtime=0) as zipped:ssh_remote('dump',zipped)
  f.flush();os.fsync(f.fileno())
 # Fully decompress to authenticate local stream/trailer before encryption.
 with gzip.open(dump,'rb') as f:
  while f.read(1048576):pass
 configuration=[]
 for node in (1,2):
  archive=folder/('Server_'+str(node)+'_Configuration.tar.gz');fd=os.open(archive,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
  with os.fdopen(fd,'wb') as f:capture_config(node,f);f.flush();os.fsync(f.fileno())
  verified=archive_verify.verify(archive,node);need(verified['allArchivedHashesVerified'])
  if 'atlas' in pre['inventory']['schemas']:
   with tarfile.open(archive,'r:gz') as contents:need({'var/lib/codex-validation/app-config/atlas.properties','etc/atlas/entry.conf','etc/letsencrypt/renewal/atlas.zhongjiehuang.com.conf'}.issubset(contents.getnames()))
  configuration.append((node,archive,verified))
 content_after=json.loads(ssh_remote('content-digest'));after=json.loads(ssh_remote('proof'));post1=s1_proof();need(after==pre and post1==pre1 and content_after==content_before);privatewrite(folder/'after.json',{'server1':post1,'server2':after})
 items=[seal(dump,dest/'All_Databases.sql.gz.GCM'),seal(folder/'accounts-and-grants.json',dest/'Database_Accounts_and_Grants.json.GCM')]
 for node,archive,verified in configuration:
  item=seal(archive,dest/('Server_'+str(node)+'_Configuration.tar.gz.GCM'));item['configurationArchiveVerification']=verified;items.append(item)
 result={'format':'single-server-database-backup-v1','runId':run,'createdAtUtc':start.isoformat(),'sourceServer':2,'mysqlUuid':pre['identity']['uuid'],'mysqlPort':332,'sourceMysqlVersion':pre['identity']['version'],'schemas':pre['inventory']['schemas'],'baseTableCount':pre['inventory']['baseTableCount'],'viewCount':pre['inventory']['viewCount'],'tableStorage':'InnoDB','gtidStableDuringQuiescentBackup':True,'applicationsStoppedOnBothHosts':True,'files':items,'sqlAndStarFileBlobContentIncluded':True,'databaseConfigurationIsReferenceNotAutomaticFreshHostInstall':True,'accountsGrantsEncrypted':True,'configurationFromBothServers':True,'atlasConfigurationRequiredAndCaptured':('atlas' in pre['inventory']['schemas']),'configurationCapturePerFileStableNotAtomicAcrossHosts':True,'sourceContentDigestAlgorithm':content_before['algorithm'],'sourceContentSha256':content_before['sha256'],'sourceSchemaDigestAlgorithm':content_before['schemaAlgorithm'],'sourceSchemaSha256':content_before['schemaSha256'],'sourceRawSchemaSha256':content_before['rawSchemaSha256'],'sourceColumnMetadataAlgorithm':content_before['columnAlgorithm'],'sourceColumnMetadataSha256':content_before['columnSha256'],'sourceColumnCount':content_before['columnCount'],'sourceContentDigestBeforeAndAfterDumpMatch':True,'restorePerformedForThisBackup':False,'caveat':'Prior independent source restore tests do not prove restore of this backup.'}
 privatewrite(dest/'MANIFEST.json',result);privatewrite(folder/'completion.json',result)
 for file in (dump,folder/'accounts-and-grants.json',*(v[1] for v in configuration)):privatefile(file);file.unlink()
 (lock/'owner.json').unlink();lock.rmdir();print(json.dumps({'status':'COMPLETE','destination':str(dest),'manifestSha256':digest(dest/'MANIFEST.json'),'restorePerformedForThisBackup':False}))
except Exception as e:
 print(json.dumps({'status':'INCOMPLETE_DO_NOT_REPLAY','errorClass':type(e).__name__,'message':'Preserve private run and exclusive lock; inspect state before deciding further action.'}),file=sys.stderr);raise SystemExit(1)
