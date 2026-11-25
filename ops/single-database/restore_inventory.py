"""Read-only host preflight for single-database offline restore; no output secrets."""
import hashlib,json,os,pathlib,re,stat,subprocess,sys,time
P=pathlib.Path
class Refusal(Exception):pass
def need(v):
 if not v:raise Refusal('offline inventory refused')
def digest(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def unit(name):
 r=subprocess.run(['systemctl','show',name,'--property=LoadState,ActiveState,MainPID,ControlPID,UnitFileState,ControlGroup'],capture_output=True,text=True,check=True,timeout=10)
 v=dict(x.split('=',1) for x in r.stdout.splitlines() if '=' in x)
 g=v.get('ControlGroup','');p=P('/sys/fs/cgroup'+g) if g else None
 empty=not p or not p.exists() or all(not x.read_text().strip() for x in p.rglob('cgroup.procs'))
 return masked_unit(v,empty)
def masked_unit(v,empty):
 need(v.get('LoadState')=='masked' and v.get('UnitFileState') in ('masked','masked-runtime') and v.get('ActiveState') in ('inactive','failed') and v.get('MainPID')=='0' and v.get('ControlPID')=='0' and empty is True)
 return {'inactive':True,'emptyCgroup':True,'loadState':v['LoadState'],'unitFileState':v['UnitFileState']}
def main():
 need(os.geteuid()==0 and len(sys.argv)==2 and sys.argv[1] in ('1','2'));node=int(sys.argv[1]);units={}
 for n in ('codex-lab-tomcat.service','codex-lab-nginx.service','codex-lab-entry-checker.service','codex-lab-mysql.service'):units[n]=unit(n)
 if node==1:need(units['codex-lab-mysql.service']['unitFileState']=='masked')
 data={'nodeId':node,'observedAtUnix':time.time(),'units':units}
 if node==2:
  # A stopped unit alone is not sufficient if another production mysqld is running.
  for proc in P('/proc').iterdir():
   if proc.name.isdigit():
    try:exe=os.readlink(proc/'exe')
    except (OSError,PermissionError):continue
    need(not exe.endswith('/mysqld') and '/mysqld (deleted)' not in exe)
  config=P('/var/lib/codex-validation/single-database/monitor.json');need(config.is_file() and not config.is_symlink() and config.stat().st_uid==0)
  datadir=P(json.loads(config.read_text())['dataPath']);need(datadir==P('/var/lib/codex-validation/data') and datadir.is_dir() and not datadir.is_symlink() and datadir.resolve()==datadir);a=datadir/'auto.cnf';need(a.is_file() and not a.is_symlink() and a.stat().st_uid in (0,datadir.stat().st_uid))
  m=re.search(r'(?m)^server-uuid\s*=\s*([0-9a-f-]{36})\s*$',a.read_text());need(m and m.group(1)=='5b042440-ddee-11f1-8a71-06ffe4b75559')
  default=P('/var/lib/mysql');need(default.is_dir() and not default.is_symlink());st=default.stat();space=os.statvfs('/');mem={k:int(v.split()[0])*1024 for k,v in (x.split(':',1) for x in P('/proc/meminfo').read_text().splitlines())};v=subprocess.run(['/usr/sbin/mysqld','--no-defaults','--version'],capture_output=True,text=True,check=True,timeout=10).stdout;need(re.search(r'(?<![0-9])26\.10\.0(?![0-9])',v) is not None)
  data.update(productionUuid=m.group(1),productionDataIdentity={'dev':datadir.stat().st_dev,'ino':datadir.stat().st_ino},hostDefaultMysqlIdentity={'dev':st.st_dev,'ino':st.st_ino,'isDirectory':True,'isSymlink':False},hostMountNamespace=os.readlink('/proc/self/ns/mnt'),physicalFreeBytes=space.f_bfree*space.f_frsize,memAvailableBytes=mem['MemAvailable'],swapFreeBytes=mem['SwapFree'],mysqldVersion=v,binaries={'/usr/sbin/mysqld':{'sha256':digest('/usr/sbin/mysqld')},'/usr/bin/mysqldump':{'sha256':digest('/usr/bin/mysqldump')}})
 print(json.dumps(data,sort_keys=True))
if __name__=='__main__':
 try:main()
 except Exception as e:print(json.dumps({'status':'REFUSED','errorClass':type(e).__name__}),file=sys.stderr);raise SystemExit(1)
