"""Server-side backup stage: invoked only as root over SSH; no credentials in argv."""
import json,os,pathlib,re,subprocess,sys,hashlib,stat
P=pathlib.Path
UUID='5b042440-ddee-11f1-8a71-06ffe4b75559'
CORE={'IAFM','IAFM_peer','starhub_accounts','starhub_files_peer','starhub_decisions','ucost_app'}
SYSTEM={'mysql','sys','information_schema','performance_schema'}
def need(x):
 if not x:raise RuntimeError('backup precondition refused')
def cmd(args):
 r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True,timeout=60);return r.stdout.decode('utf8')
def mysql(query):return cmd(['/usr/bin/mysql','--defaults-file=/var/lib/codex-validation/private/client.cnf','--no-login-paths','--batch','--raw','--skip-column-names','--execute',query])
def unit(name):
 text=cmd(['systemctl','show',name,'--property=ActiveState,MainPID,ControlPID,UnitFileState,ControlGroup']);return dict(s.split('=',1) for s in text.splitlines() if '=' in s)
def stopped():
 for n in ('codex-lab-tomcat.service','codex-lab-nginx.service','codex-lab-entry-checker.service'):
  u=unit(n);need(u.get('ActiveState') in ('inactive','failed') and u.get('MainPID')=='0' and u.get('ControlPID')=='0')
  group=u.get('ControlGroup','')
  if group:
   root=P('/sys/fs/cgroup'+group);need(not root.exists() or all(not q.read_text().strip() for q in root.rglob('cgroup.procs')))
def identity():
 x=mysql("SELECT CONCAT_WS('|',@@server_uuid,@@port,@@GLOBAL.read_only,@@GLOBAL.super_read_only,@@GLOBAL.innodb_flush_log_at_trx_commit,@@GLOBAL.sync_binlog,@@GLOBAL.log_bin,@@GLOBAL.gtid_executed,@@version)").strip().split('|');need(len(x)==9 and x[:7]==[UUID,'332','0','0','1','1','1'] and re.search(r'(?<![0-9])26\.10\.0(?![0-9])',x[8]));need(unit('codex-lab-mysql.service').get('ActiveState')=='active');return {'uuid':x[0],'port':int(x[1]),'gtid':x[7],'version':x[8]}
def schemas():
 names=sorted(s for s in mysql('SHOW DATABASES').splitlines() if s not in SYSTEM);need(CORE.issubset(names) and all(re.fullmatch(r'[A-Za-z0-9_]+',s) for s in names))
 raw=mysql("SELECT CONCAT(TABLE_SCHEMA,'|',TABLE_NAME,'|',COALESCE(ENGINE,''),'|',TABLE_TYPE) FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema') ORDER BY TABLE_SCHEMA,TABLE_NAME")
 rows=[s.split('|') for s in raw.splitlines()];need(all(len(v)==4 and v[0] in names and v[3] in ('BASE TABLE','VIEW') and (v[3]=='VIEW' or v[2]=='InnoDB') for v in rows));return {'schemas':names,'objects':rows,'baseTableCount':sum(v[3]=='BASE TABLE' for v in rows),'viewCount':sum(v[3]=='VIEW' for v in rows)}
def metadata():
 account_literals=mysql("SELECT CONCAT(QUOTE(User),'@',QUOTE(Host)) FROM mysql.user ORDER BY User,Host").splitlines();accounts=[]
 for ident in account_literals:
  need('\n' not in ident and '\r' not in ident and ident.count('@')==1)
  accounts.append({'identitySql':ident,'createUser':mysql('SHOW CREATE USER '+ident),'grants':mysql('SHOW GRANTS FOR '+ident)})
 return {'identity':identity(),'inventory':schemas(),'accountsAndGrants':accounts}
if os.geteuid()!=0:raise SystemExit(1)
try:
 action=sys.argv[1];stopped();identity();fs=os.statvfs('/');need(fs.f_bfree*fs.f_frsize>=4_000_000_000)
 if action=='proof':print(json.dumps({'identity':identity(),'inventory':schemas(),'applicationsStopped':True}))
 elif action=='metadata':print(json.dumps(metadata(),sort_keys=True))
 elif action=='content-digest':
  from schema_fingerprint import schema_hashes, schema_column_digests
  before={'identity':identity(),'inventory':schemas()};names=before['inventory']['schemas']
  missing_pk=mysql("SELECT CONCAT(t.TABLE_SCHEMA,'|',t.TABLE_NAME) FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema') AND t.TABLE_TYPE='BASE TABLE' AND NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS k WHERE k.TABLE_SCHEMA=t.TABLE_SCHEMA AND k.TABLE_NAME=t.TABLE_NAME AND k.CONSTRAINT_TYPE='PRIMARY KEY')").splitlines();need(not missing_pk)
  args=['/usr/bin/mysqldump','--defaults-file=/var/lib/codex-validation/private/client.cnf','--no-login-paths','--no-create-info','--skip-triggers','--skip-routines','--skip-events','--skip-extended-insert','--complete-insert','--hex-blob','--order-by-primary','--skip-comments','--skip-add-locks','--skip-disable-keys','--skip-set-charset','--skip-dump-date','--set-gtid-purged=OFF','--no-tablespaces','--column-statistics=0','--single-transaction','--skip-lock-tables','--tz-utc','--max-allowed-packet=64M','--databases']+names
  ddlargs=['/usr/bin/mysqldump','--defaults-file=/var/lib/codex-validation/private/client.cnf','--no-login-paths','--no-data','--skip-triggers','--skip-routines','--skip-events','--skip-comments','--skip-add-locks','--skip-disable-keys','--skip-set-charset','--skip-dump-date','--set-gtid-purged=OFF','--no-tablespaces','--column-statistics=0','--single-transaction','--skip-lock-tables','--tz-utc','--max-allowed-packet=64M','--databases']+names
  import threading
  def streamhash(argv):
   h=hashlib.sha256();proc=subprocess.Popen(argv,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL);timer=threading.Timer(1800,proc.kill);timer.daemon=True;timer.start()
   try:
    while True:
     block=proc.stdout.read(1048576)
     if not block:break
     h.update(block)
    need(proc.wait(timeout=1800)==0);return h.hexdigest()
   finally:timer.cancel()
  datasha=streamhash(args)
  proc=subprocess.Popen(ddlargs,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL);timer=threading.Timer(1800,proc.kill);timer.daemon=True;timer.start()
  try:
   ddl=schema_hashes(proc.stdout);need(proc.wait(timeout=1800)==0)
  finally:
   timer.cancel()
   if proc.poll() is None:proc.kill()
   proc.wait();proc.stdout.close()
  columns=schema_column_digests('/usr/bin/mysql','/var/lib/codex-validation/private/client.cnf',names,allowed_schemas=set(names),expected_objects=[(x[0],x[1],x[3]) for x in before['inventory']['objects']])
  after={'identity':identity(),'inventory':schemas()};stopped();need(before==after)
  print(json.dumps({'algorithm':'mysql-ordered-dump-data-sha256-v1','sha256':datasha,'schemaAlgorithm':'mysql-schema-dump-charset-semantic-v2','schemaSha256':ddl['schemaSha256'],'rawSchemaSha256':ddl['rawSha256'],'columnAlgorithm':columns['algorithm'],'columnSha256':columns['sha256'],'columnCount':columns['columnCount'],'identity':before['identity'],'inventory':before['inventory']}))
 elif action=='dump':
  inventory=schemas();os.execv('/usr/bin/mysqldump',['mysqldump','--defaults-file=/var/lib/codex-validation/private/client.cnf','--no-login-paths','--single-transaction','--quick','--hex-blob','--no-tablespaces','--routines','--events','--triggers','--set-gtid-purged=OFF','--databases']+inventory['schemas'])
 else:raise RuntimeError('invalid action')
except Exception as e:
 print('Backup stage refused: '+type(e).__name__,file=sys.stderr);raise SystemExit(1)
