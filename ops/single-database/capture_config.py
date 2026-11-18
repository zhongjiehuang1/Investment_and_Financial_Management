"""Read-only selected current configuration capture; writes encrypted-by-caller tar to stdout."""
import argparse,datetime,grp,hashlib,io,json,os,pathlib,pwd,stat,subprocess,sys,tarfile
P=pathlib.Path;B=P('/var/lib/codex-validation');
class Refusal(Exception):pass
def need(x):
 if not x:raise Refusal('configuration capture refused')
def allowed(p):
 s=str(p);return any(s==r or s.startswith(r+'/') for r in ['/var/lib/codex-validation/app-config','/var/lib/codex-validation/tls','/var/lib/codex-validation/entry-tls','/var/lib/codex-validation/tomcat-atomic/conf','/var/lib/codex-validation/peer','/var/lib/codex-validation/peer-home','/var/lib/codex-validation/private','/var/lib/codex-validation/single-database','/var/lib/codex-validation/system-safety','/etc/mysql','/etc/ssh','/etc/letsencrypt','/etc/sysctl.d','/etc/ufw','/usr/local/lib/systemd/system','/etc/systemd/system','/usr/local/lib/four-project','/usr/share/ca-certificates','/etc/ssl','/root/.ssh','/home/ubuntu/.ssh','/home/codex-peer/.ssh','/var/lib/four-project-entry-node1/private','/var/lib/four-project-entry-node2/private']) or s in ('/var/lib/codex-validation/entry-formal.json','/etc/atlas/entry.conf','/etc/nginx/mime.types','/etc/alternatives/my.cnf','/dev/null','/etc/apt/sources.list','/etc/apt/sources.list.d/mysql-official.list','/etc/apt/keyrings/mysql-official.gpg','/usr/share/keyrings/ubuntu-archive-keyring.gpg','/etc/apparmor.d/usr.sbin.mysqld','/etc/apparmor.d/local/usr.sbin.mysqld','/etc/fstab','/etc/sysctl.conf','/etc/nftables.conf','/etc/passwd','/etc/group','/etc/os-release','/usr/lib/os-release','/usr/lib/systemd/system/certbot.service','/usr/lib/systemd/system/certbot.timer','/usr/lib/systemd/system/ufw.service','/usr/lib/systemd/system/nftables.service')
def checkpath(p):
 need(p.is_absolute() and '..' not in p.parts and allowed(p));return p
chosen={};visiting=set()
def add(p,follow=True):
 p=checkpath(P(p));need(str(p) not in visiting)
 if str(p) in chosen:return
 visiting.add(str(p));need(p.exists() or p.is_symlink());s=p.lstat();need(stat.S_ISREG(s.st_mode) or stat.S_ISDIR(s.st_mode) or stat.S_ISLNK(s.st_mode));need(s.st_uid==0 or str(p).startswith(('/root/.ssh/','/home/ubuntu/.ssh/','/home/codex-peer/.ssh/','/var/lib/codex-validation/peer-home/')) or (p==B/'peer-home' and s.st_uid==pwd.getpwnam('codex-peer').pw_uid))
 chosen[str(p)]=p
 if stat.S_ISLNK(s.st_mode) and follow:
  raw=os.readlink(p);target=P(raw) if raw.startswith('/') else p.parent/raw;target=P(os.path.normpath(str(target)));checkpath(target)
  if str(target)!='/dev/null':add(target,True)
 visiting.remove(str(p))
def tree(root):
 p=checkpath(P(root));need(p.exists() and not p.is_symlink() and p.is_dir());add(p)
 for d,dirs,files in os.walk(p,followlinks=False):
  for name in dirs+files:
   q=P(d)/name;add(q)
   if q.is_symlink() and name in dirs:dirs.remove(name)
def optional(p):
 p=P(p)
 if p.exists() or p.is_symlink():add(p)
def addunit(name,required=True):
 r=subprocess.run(['systemctl','show',name,'--property=FragmentPath,DropInPaths,LoadState'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=15,check=True);v=dict(x.split('=',1) for x in r.stdout.splitlines() if '=' in x);fragment=v.get('FragmentPath','')
 if fragment == '/run/systemd/system/'+name and v.get('LoadState') == 'masked':
  # Runtime mask is temporary: capture the persistent unit, not /run.
  candidates=[P('/etc/systemd/system')/name,P('/usr/local/lib/systemd/system')/name,P('/usr/lib/systemd/system')/name]
  present=[p for p in candidates if p.exists() or p.is_symlink()]
  need(present);add(present[0])
 elif not fragment or fragment=='/dev/null':
  local=P('/etc/systemd/system')/name
  if local.exists() or local.is_symlink():add(local)
  elif required:raise Refusal('required unit missing')
 else:
  p=P(fragment)
  # Ubuntu's /lib is a symlink to /usr/lib. Record the actual vendor unit;
  # do not recursively copy arbitrary system unit directories.
  if fragment.startswith('/lib/systemd/system/'):p=p.resolve(strict=True)
  add(p)
 for item in v.get('DropInPaths','').split():add(P(item))
def hash_fd(fd):
 h=hashlib.sha256();os.lseek(fd,0,os.SEEK_SET)
 for b in iter(lambda:os.read(fd,1048576),b''):h.update(b)
 os.lseek(fd,0,os.SEEK_SET);return h.hexdigest()
def system_inventory(node):
 result=subprocess.run(['dpkg-query','-W','-f=${binary:Package}\t${Version}\t${db:Status-Abbrev}\n'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=60,check=True)
 packages=[]
 for line in result.stdout.splitlines():
  parts=line.split('\t');need(len(parts)==3)
  if parts[2].startswith('ii'):packages.append({'package':parts[0],'version':parts[1]})
 need(packages);identities=[]
 for name in ('root','ubuntu','mysql','tomcat','codex-peer'):
  try:u=pwd.getpwnam(name)
  except KeyError:continue
  identities.append({'name':name,'uid':u.pw_uid,'gid':u.pw_gid,'home':u.pw_dir,'shell':u.pw_shell})
 groups=[{'name':g.gr_name,'gid':g.gr_gid,'members':g.gr_mem} for g in grp.getgrall()]
 units={}
 for unit in ('certbot.timer','certbot.service','ufw.service','nftables.service'):
  p=subprocess.run(['systemctl','show',unit,'--property=ActiveState,UnitFileState,LoadState'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=15,check=True)
  units[unit]=dict(x.split('=',1) for x in p.stdout.splitlines() if '=' in x)
 return {'format':'current-system-rebuild-inventory-v1','nodeId':node,'installedPackages':packages,'serviceIdentities':identities,'groups':groups,'unitStates':units,'passwordHashesIncluded':False}
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--node',type=int,choices=(1,2),required=True);args=ap.parse_args();need(os.geteuid()==0);node=args.node
 entry=B/'entry-formal.json';add(entry);config=json.loads(entry.read_text());need(config.get('nodeId')==node and config.get('databaseTopology')=='single-database')
 apps=config.get('applications');need(isinstance(apps,dict) and set(apps)=={'iafm','starhub','ucost','portfolio'})
 for item in apps.values():
  for key in ('certificate','privateKey'):add(P(item[key]))
  host=item.get('hostname','');need(isinstance(host,str) and bool(host) and all(c.isalnum() or c in '.-' for c in host));optional(P('/etc/letsencrypt/renewal')/(host+'.conf'))
 optional('/etc/letsencrypt/cli.ini')
 for d in ('pre','post','deploy'):
  hook=P('/etc/letsencrypt/renewal-hooks')/d
  if hook.exists():tree(hook)
 required=['iafm-cluster.properties',f'iafm-node{node}-environment.json','starhub.properties','starhub-security.json','ucost.properties','mysql-ca.pem','mysql-trust.p12','tomcat-atomic.env','security/tester-password']
 for name in required:add(B/'app-config'/name)
 for name in ('security/dbip-country-lite.mmdb','atlas.properties'):optional(B/'app-config'/name)
 if P('/etc/atlas/entry.conf').exists():
  add(P('/etc/atlas/entry.conf'))
  for name in ('fullchain.pem','privkey.pem'):add(P('/etc/letsencrypt/live/atlas.zhongjiehuang.com')/name)
  add(P('/etc/letsencrypt/renewal/atlas.zhongjiehuang.com.conf'))
 for root in (B/'tls',B/'entry-tls',B/'tomcat-atomic/conf'):tree(root)
 for root in (B/'peer',B/'peer-home'):
  need(root.is_dir() and not root.is_symlink());add(root)
  for d,dirs,files in os.walk(root,followlinks=False):
   for name in files:
    if name in ('id_ed25519','id_ed25519.pub','known_hosts','authorized_keys'):add(P(d)/name)
   dirs[:]=[name for name in dirs if not (P(d)/name).is_symlink()]
 for name in ('id_ed25519','known_hosts'):add(B/'peer'/name)
 peer=pwd.getpwnam('codex-peer');peerkey=P(peer.pw_dir)/'.ssh/authorized_keys';add(peerkey)
 login='root' if node==1 else 'ubuntu';home=P(pwd.getpwnam(login).pw_dir);optional(home/'.ssh/authorized_keys')
 for name in ('tomcat','tunnel','entry-checker','nginx','mysql','reserve','reserve-policy'):addunit('codex-lab-'+name+'.service',required=(name!='mysql' or node==2))
 if node==2:addunit('codex-single-database-monitor.service')
 for name in ('lab_entry.py','single_database_monitor.py','reserve_observer.py','reserve_policy.py','private_port_guard.py'):add(P('/usr/local/lib/four-project')/name)
 add(B/'system-safety/reserve.json');add(P('/etc/ssh/sshd_config'));tree('/etc/ssh/sshd_config.d');add(P('/etc/nginx/mime.types'))
 for p in ('/etc/apt/sources.list','/etc/apt/sources.list.d/mysql-official.list','/etc/apt/keyrings/mysql-official.gpg','/usr/share/keyrings/ubuntu-archive-keyring.gpg','/etc/fstab','/etc/sysctl.conf','/etc/passwd','/etc/group','/etc/os-release'):add(P(p))
 for p in ('/etc/apparmor.d/usr.sbin.mysqld','/etc/apparmor.d/local/usr.sbin.mysqld'):
  if node==2:add(P(p))
  else:optional(p)
 for p in ('/etc/nftables.conf',):optional(p)
 for p in ('/etc/sysctl.d','/etc/ufw','/etc/letsencrypt/accounts'):tree(p)
 for unit in ('certbot.timer','certbot.service','ufw.service'):addunit(unit)
 addunit('nftables.service',required=False)
 inventory=system_inventory(node)
 for p in P('/etc/ssh').glob('ssh_host_*_key*'):add(p)
 nginx=P('/var/lib')/('four-project-entry-node'+str(node))/'private/nginx.conf';add(nginx)
 if node==2:
  tree('/etc/mysql');add(B/'private/client.cnf')
  for name in ('monitor.json','monitor.token','capacity-validation.json','health-client.cnf'):add(B/'single-database'/name)
 # Include only directory metadata for ancestors; never recurse over them.
 for p in list(chosen.values()):
  for parent in p.parents:
   if str(parent)=='/':break
   if str(parent) not in chosen:
    s=parent.lstat();need(stat.S_ISDIR(s.st_mode) and not parent.is_symlink());chosen[str(parent)]=parent
 rows=[]
 with tarfile.open(fileobj=sys.stdout.buffer,mode='w|gz',format=tarfile.PAX_FORMAT) as tar:
  for name in sorted(chosen):
   p=chosen[name];s=p.lstat();arc=name.lstrip('/');info=tar.gettarinfo(str(p),arcname=arc);row={'path':arc,'mode':stat.S_IMODE(s.st_mode),'uid':s.st_uid,'gid':s.st_gid}
   if stat.S_ISREG(s.st_mode):
    need(s.st_nlink==1);fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW);before=os.fstat(fd);need(before.st_dev==s.st_dev and before.st_ino==s.st_ino);digest=hash_fd(fd);row.update(type='file',bytes=s.st_size,sha256=digest);info.type=tarfile.REGTYPE;info.linkname=''
    with os.fdopen(fd,'rb') as f:
     tar.addfile(info,f);after=os.fstat(f.fileno());need((after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns)==(before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns) and hash_fd(f.fileno())==digest)
   elif stat.S_ISDIR(s.st_mode):row['type']='directory';tar.addfile(info)
   elif stat.S_ISLNK(s.st_mode):row.update(type='symlink',target=os.readlink(p));tar.addfile(info)
   else:raise Refusal('unsupported type')
   rows.append(row)
  inventory_body=(json.dumps(inventory,sort_keys=True)+'\n').encode();info=tarfile.TarInfo('SYSTEM_REBUILD_INVENTORY.json');info.size=len(inventory_body);info.mode=0o600;info.uid=0;info.gid=0;tar.addfile(info,io.BytesIO(inventory_body));rows.append({'path':info.name,'mode':info.mode,'uid':0,'gid':0,'type':'file','bytes':len(inventory_body),'sha256':hashlib.sha256(inventory_body).hexdigest()})
  body=(json.dumps({'format':'current-single-database-config-capture-v1','nodeId':node,'capturedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files':rows,'includesSecrets':True,'excludesBusinessData':True,'excludesWarAndSources':True},sort_keys=True)+'\n').encode();info=tarfile.TarInfo('CONFIG_CAPTURE_MANIFEST.json');info.size=len(body);info.mode=0o600;info.uid=0;info.gid=0;tar.addfile(info,io.BytesIO(body))
if __name__=='__main__':
 try:main()
 except Exception as e:print('Configuration capture refused: '+type(e).__name__,file=sys.stderr);raise SystemExit(1)
