#!/usr/bin/env python3
"""Small, restricted remote half of the single-database maintenance procedure."""
import datetime,json,os,pathlib,re,stat,subprocess,sys,time,urllib.request
P=pathlib.Path
S1MYSQL='codex-lab-mysql.service'; MYSQL=S1MYSQL; MON='codex-single-database-monitor.service'
TC='codex-lab-tomcat.service'; CHECK='codex-lab-entry-checker.service'; ENTRY='codex-lab-nginx.service'
UUID='5b042440-ddee-11f1-8a71-06ffe4b75559'
def require(x):
 if not x:raise RuntimeError('maintenance guard refused')
def command(a,timeout=60):
 p=subprocess.run(a,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=timeout);require(p.returncode==0);return p.stdout.decode()
def active(unit,timeout=60):return command(['systemctl','show',unit,'--property=ActiveState','--value'],timeout=timeout).strip()=='active'
def masked(unit):return command(['systemctl','show',unit,'--property=UnitFileState','--value']).strip()=='masked'
def stopped(unit):
 v=dict(x.split('=',1) for x in command(['systemctl','show',unit,'--property=ActiveState,MainPID,ControlPID,ControlGroup']).splitlines() if '=' in x)
 if v.get('ActiveState')!='inactive' or v.get('MainPID')!='0' or v.get('ControlPID')!='0':return False
 group=v.get('ControlGroup','')
 return not group or all(not x.read_text().strip() for x in (P('/sys/fs/cgroup')/group.lstrip('/')).rglob('cgroup.procs'))
def mysql_identity(wait=0):
 # A Type=simple unit may be active before the private socket accepts SQL.
 # Only retry documented connection failures during this bounded cold start;
 # a successfully queried unexpected identity is always an immediate refusal.
 require(wait in (0,90));deadline=time.monotonic()+wait
 while True:
  remaining=deadline-time.monotonic() if wait else 15
  require(remaining>0)
  try:
   p=subprocess.run(['/usr/bin/mysql','--defaults-extra-file=/var/lib/codex-validation/private/client.cnf','--no-login-paths','--batch','--raw','--skip-column-names'],input=b'SELECT @@server_uuid,@@port;',stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=min(15,remaining))
  except subprocess.TimeoutExpired:
   # An ambiguous stalled query is not an established connection refusal.
   raise RuntimeError('database identity unavailable')
  else:
   if p.returncode==0:
    require(p.stdout.decode().strip()==UUID+'\t332');return
   connection_error=re.search(rb'(?m)^ERROR (?:2002|2003|2013|2055)\b',p.stderr or b'')
   if not wait or not connection_error:raise RuntimeError('database identity unavailable')
   remaining=deadline-time.monotonic()
   if remaining<=0 or not active(MYSQL,timeout=min(5,remaining)) or time.monotonic()>=deadline:raise RuntimeError('database identity unavailable')
  remaining=deadline-time.monotonic()
  if remaining<=0:raise RuntimeError('database identity unavailable')
  time.sleep(min(1,remaining))
def private_read(path):
 fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
 with os.fdopen(fd,'r') as f:
  s=os.fstat(f.fileno());require(stat.S_ISREG(s.st_mode) and s.st_uid==0 and stat.S_IMODE(s.st_mode)==0o600 and s.st_nlink==1 and s.st_size<=65536);return f.read()
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
def monitor_ready():
 config=json.loads(private_read('/var/lib/codex-validation/single-database/monitor.json'));require(config.get('listenPort')==942 and config.get('mysqlUuid')==UUID)
 token_path=P(config['tokenFile']);require(token_path==P('/var/lib/codex-validation/single-database/monitor.token'));token=private_read(token_path).strip();require(len(token)>=32)
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect());request=urllib.request.Request('http://127.0.0.1:942/health',headers={'Authorization':'Bearer '+token});end=time.monotonic()+90
 while True:
  try:
   with opener.open(request,timeout=3) as r:require(r.status==204)
   return
  except Exception:
   if time.monotonic()>=end:raise RuntimeError('database monitor not ready')
   time.sleep(2)
def healthy(node,wait):
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 end=time.monotonic()+wait
 while True:
  try:
   for app in ('iafm','starhub','ucost','portfolio'):
    with opener.open('http://127.0.0.1:'+str(942+node)+'/local-admit/'+app,timeout=3) as v:require(v.status==204)
   return
  except Exception:
   if time.monotonic()>=end:raise RuntimeError('local health not ready')
   time.sleep(2)
def services(units,op):
 for unit in units:
  command(['systemctl',op,unit],timeout=180 if op=='start' else 90)
  require(active(unit) if op=='start' else stopped(unit))
def logs():
 root=P('/var/lib/codex-validation/tomcat-atomic/logs');require(root.is_dir() and not root.is_symlink());fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 removed=0;threshold=time.time()-14*86400
 try:
  for name in os.listdir(fd):
   if not re.fullmatch(r'(?:catalina|localhost)\.\d{4}-\d{2}-\d{2}\.log',name):continue
   try:day=datetime.date.fromisoformat(name.split('.')[1])
   except ValueError:continue
   s=os.stat(name,dir_fd=fd,follow_symlinks=False)
   if not stat.S_ISREG(s.st_mode) or s.st_nlink!=1 or s.st_mtime>=threshold or day>=datetime.datetime.now(datetime.timezone.utc).date()-datetime.timedelta(days=14):continue
   # With the project Tomcat stopped, retain any file held by a live process.
   held=False
   for proc in P('/proc').iterdir():
    if not proc.name.isdigit():continue
    try:
     for f in (proc/'fd').iterdir():
      try:
       t=f.stat()
       if (t.st_dev,t.st_ino)==(s.st_dev,s.st_ino):held=True;break
      except (FileNotFoundError,ProcessLookupError):continue
      except PermissionError:held=True;break
    except (FileNotFoundError,ProcessLookupError):continue
    except PermissionError:held=True
    if held:break
   if held:continue
   current=os.stat(name,dir_fd=fd,follow_symlinks=False);require((current.st_dev,current.st_ino,current.st_size,current.st_mtime_ns)==(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns));os.unlink(name,dir_fd=fd);removed+=1
 finally:os.close(fd)
 return removed
def main():
 require(os.geteuid()==0 and len(sys.argv)==3 and sys.argv[1] in ('1','2'));node=int(sys.argv[1]);phase=sys.argv[2]
 require(phase in ('preflight','stop-app','assert-stopped','stop-db','clean','start-db','start-app','verify'))
 if phase=='preflight':
  require(all(active(x) for x in (TC,CHECK,ENTRY,'codex-lab-tunnel.service','codex-lab-reserve.service','codex-lab-reserve-policy.service')))
  if node==1:require(masked(S1MYSQL) and stopped(S1MYSQL))
  else:require(active(MYSQL) and active(MON));mysql_identity()
  healthy(node,5)
 elif phase=='stop-app':services((ENTRY,CHECK,TC),'stop')
 elif phase=='assert-stopped':require(all(stopped(x) for x in (TC,CHECK,ENTRY)))
 elif phase=='stop-db':
  require(node==2 and all(stopped(x) for x in (TC,CHECK,ENTRY)))
  p=subprocess.run(['/usr/bin/mysql','--defaults-extra-file=/var/lib/codex-validation/private/client.cnf','--no-login-paths','--batch','--raw','--skip-column-names'],input=b'XA RECOVER;',stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15);require(p.returncode==0 and not p.stdout.strip())
  services((MON,MYSQL),'stop')
 elif phase=='clean':
  require(all(stopped(x) for x in (TC,CHECK,ENTRY)));require((node==1 and masked(S1MYSQL) and stopped(S1MYSQL)) or (node==2 and stopped(MYSQL) and stopped(MON)))
  command(['apt-get','clean'],timeout=120);command(['journalctl','--rotate'],timeout=60);command(['journalctl','--vacuum-time=14d'],timeout=120);removed=logs()
 elif phase=='start-db':require(node==2);services((MYSQL,),'start');mysql_identity(wait=90);services((MON,),'start');monitor_ready()
 elif phase=='start-app':
  if node==1:require(masked(S1MYSQL) and stopped(S1MYSQL))
  services((TC,CHECK,ENTRY),'start');healthy(node,90)
 else:
  require(all(active(x) for x in (TC,CHECK,ENTRY)))
  if node==1:require(masked(S1MYSQL) and stopped(S1MYSQL))
  else:require(active(MYSQL) and active(MON));mysql_identity()
  healthy(node,20)
 print(json.dumps({'node':node,'phase':phase,'status':'PASS',**({'oldProjectLogFilesRemoved':removed} if phase=='clean' else {})}))
if __name__=='__main__':
 try:main()
 except BaseException as e:print(json.dumps({'status':'REFUSED_OR_UNKNOWN','errorClass':type(e).__name__}));raise SystemExit(1)
