"""Verified, stopped-Tomcat WAR pre-expansion. Default action has no side effects."""
import argparse,grp,hashlib,json,os,pathlib,re,stat,subprocess,sys,uuid,xml.etree.ElementTree as ET,zipfile
P=pathlib.Path
NAMES={'ROOT.war','Investment_and_Financial_Management.war','starhub.war','ucost.war','atlas.war'}
BASE=P('/var/lib/codex-validation/tomcat-atomic');WEB=BASE/'webapps';SERVER=BASE/'conf/server.xml';BACK=P('/var/lib/codex-validation/preexpanded-war-preimages')
MAX_ENTRY=256*1024*1024;MAX_TOTAL=2*1024*1024*1024;MAX_ENTRIES=20000
class Refusal(Exception):pass
def need(ok):
 if not ok:raise Refusal('safety precondition refused')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def guard_bytes(size):
 fs=os.statvfs(WEB);block=fs.f_frsize;need(fs.f_bfree*block>=4_000_000_000+((size+block-1)//block)*block)
def fsyncdir(p):
 fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
def privatewrite(p,data,mode=0o600):
 fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode)
 with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
 fsyncdir(p.parent)
def regular(p,uid=0):
 s=p.lstat();need(stat.S_ISREG(s.st_mode) and not p.is_symlink() and s.st_uid==uid and s.st_nlink==1 and not (stat.S_IMODE(s.st_mode)&0o022));return s
def safedir(p,uid=0):
 s=p.lstat();need(stat.S_ISDIR(s.st_mode) and not p.is_symlink() and s.st_uid==uid and not (stat.S_IMODE(s.st_mode)&0o022));return s
def stopped():
 r=subprocess.run(['systemctl','show','codex-lab-tomcat.service','--property=ActiveState,MainPID,ControlPID,ControlGroup'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,check=True,timeout=10);v=dict(s.split('=',1) for s in r.stdout.splitlines() if '=' in s);need(v.get('ActiveState') in ('inactive','failed') and v.get('MainPID')=='0' and v.get('ControlPID')=='0')
 if v.get('ControlGroup'):
  cg=P('/sys/fs/cgroup'+v['ControlGroup']);need(not cg.exists() or all(not f.read_text().strip() for f in cg.rglob('cgroup.procs')))
def dirset(p,gid):os.chown(p,0,gid);os.chmod(p,0o750)
def fileset(p,gid):os.chown(p,0,gid);os.chmod(p,0o640)
def parse_war(p):
 items={};dirs=set();count=0;total=0
 with zipfile.ZipFile(p) as z:
  for entry in z.infolist():
   n=entry.filename;need(entry.orig_filename==n and n and not n.startswith('/') and '\\' not in n and '\x00' not in n and not any(ord(c)<32 for c in n));need(not re.search(r'(^|/)\.{1,2}(/|$)',n) and '//' not in n)
   path=n[:-1] if n.endswith('/') else n;parts=path.split('/');need(all(parts) and path.casefold()!='meta-inf/war-tracker');isdir=entry.is_dir();
   if isdir:dirs.add(path)
   typ=(entry.external_attr>>16)&0o170000;need(typ in ((0,stat.S_IFDIR) if isdir else (0,stat.S_IFREG)))
   need(path not in items);items[path]=(isdir,entry);count+=1;need(count<=MAX_ENTRIES and entry.file_size<=MAX_ENTRY and (entry.file_size<=1024*1024 or entry.file_size<=max(1,entry.compress_size)*1000));total+=entry.file_size;need(total<=MAX_TOTAL)
   for i in range(1,len(parts)):dirs.add('/'.join(parts[:i]))
  for parent in dirs:need(parent not in items or items[parent][0])
  casefold={};
  for k in set(items)|dirs:
   lower=k.casefold();need(lower not in casefold or casefold[lower]==k);casefold[lower]=k
  need(any(not isdir and name=='WEB-INF/web.xml' for name,(isdir,_) in items.items()) or any(not isdir and name=='index.html' for name,(isdir,_) in items.items()))
  if 'META-INF/context.xml' in items:
   item=items['META-INF/context.xml'];need(not item[0] and item[1].file_size<=1048576);context=ET.fromstring(z.read(item[1]));need(context.tag=='Context' and context.get('docBase') is None and context.get('unpackWAR','true')=='true' and context.get('antiResourceLocking','false')=='false')
  files={}
  for name,(isdir,e) in items.items():
   if not isdir:
    h=hashlib.sha256();length=0
    with z.open(e) as source:
     for chunk in iter(lambda:source.read(1048576),b''):h.update(chunk);length+=len(chunk);need(length<=e.file_size)
    need(length==e.file_size);files[name]={'sha256':h.hexdigest(),'size':length}
  return files,dirs,total

def verify_tree(tree,files,dirs,war,gid):
 s=safedir(tree);need(s.st_gid==gid and stat.S_IMODE(s.st_mode)==0o750);actual={};actualdirs=set()
 for root,ds,fs in os.walk(tree,followlinks=False):
  for name in ds:
   p=P(root)/name;s=safedir(p);need(s.st_gid==gid and stat.S_IMODE(s.st_mode)==0o750);actualdirs.add(str(p.relative_to(tree)))
  for name in fs:
   p=P(root)/name;s=regular(p);need(s.st_gid==gid and stat.S_IMODE(s.st_mode)==0o640);rel=str(p.relative_to(tree));
   if rel=='META-INF/war-tracker':need(s.st_size==0 and s.st_mtime_ns//1000000==war.stat().st_mtime_ns//1000000)
   else:actual[rel]={'sha256':sha(p),'size':s.st_size}
 need(actual==files and dirs.issubset(actualdirs) and all(x in dirs or x=='META-INF' or any(y.startswith(x+'/') for y in files) for x in actualdirs));need((tree/'META-INF/war-tracker').is_file());return True

def xml_change(raw):
 xml=ET.fromstring(raw);hosts=xml.findall('.//Host');need(len(hosts)==1 and hosts[0].get('name')=='localhost' and hosts[0].get('appBase')=='webapps' and not hosts[0].findall('./Context'));need(hosts[0].get('unpackWARs','true') in ('true','false') and hosts[0].get('copyXML','false')=='false' and hosts[0].get('deployOnStartup','true')=='true')
 masked=re.sub(rb'<!--.*?-->',lambda m:b' '*(m.end()-m.start()),raw,flags=re.S);tags=list(re.finditer(rb'<Host\b(?:[^"\'>]|"[^"]*"|\'[^\']*\')*>',masked,re.S));need(len(tags)==1);start,end=tags[0].span();tag=raw[start:end];hits=list(re.finditer(rb'\bunpackWARs\s*=\s*(["\'])(true|false)\1',tag));need(len(hits)<=1)
 if not hits:need(hosts[0].get('unpackWARs') is None);return raw
 hit=hits[0];need(hit.group(2).decode()==hosts[0].get('unpackWARs'));return raw[:start+hit.start(2)]+b'true'+raw[start+hit.end(2):]
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--execute',action='store_true');ap.add_argument('--run',type=uuid.UUID);ap.add_argument('--manifest');ap.add_argument('--manifest-sha256');a=ap.parse_args()
 if not a.execute:
  print(json.dumps({'mode':'LOCAL_PLAN_ONLY','remoteOrFilesystemChanges':False,'requires':'Tomcat stopped; five reviewed WAR hashes and private manifest; exact tree verification','keepsWarsAndOldExpandedTrees':True}));return
 need(os.geteuid()==0 and a.run is not None and a.manifest and re.fullmatch(r'[0-9a-f]{64}',a.manifest_sha256 or ''));stopped();run=str(a.run);gid=grp.getgrnam('tomcat').gr_gid
 for p in (BASE.parent,BASE,WEB,SERVER.parent):safedir(p)
 mf=P(a.manifest);ms=regular(mf);need(stat.S_IMODE(ms.st_mode)==0o600 and sha(mf)==a.manifest_sha256);manifest=json.loads(mf.read_bytes());need(set(manifest)=={'wars'} and set(manifest['wars'])==NAMES and all(re.fullmatch(r'[0-9a-f]{64}',h) for h in manifest['wars'].values()))
 server_stat=regular(SERVER);raw=SERVER.read_bytes();updated=xml_change(raw);context=BASE/'conf/Catalina/localhost'
 for default in (BASE/'conf/context.xml',context/'context.xml.default'):
  if default.exists() or default.is_symlink():
   regular(default);root=ET.fromstring(default.read_bytes());need(root.tag=='Context' and root.get('docBase') is None and root.get('unpackWAR','true')=='true' and root.get('antiResourceLocking','false')=='false')
 if context.exists():
  safedir(context)
  for name in NAMES:need(not (context/(name[:-4]+'.xml')).exists() and not (context/(name[:-4]+'.xml')).is_symlink())
 expected={};total=0
 for name in sorted(NAMES):
  war=WEB/name;regular(war);need(sha(war)==manifest['wars'][name]);f,d,size=parse_war(war);expected[name]=(f,d);total+=size
 fs=os.statvfs(WEB);block=fs.f_frsize;allocation=sum(sum(((x['size']+block-1)//block)*block for x in f.values())+(len(d)+2)*block for f,d in expected.values());need(fs.f_bfree*block>=4_000_000_000+allocation)
 BACK.mkdir(mode=0o700,parents=True,exist_ok=True);safedir(BACK);pre=BACK/run;stage=BASE/('.preexpand-stage-'+run);need(not pre.exists() and not stage.exists());pre.mkdir(mode=0o700);stage.mkdir(mode=0o700)
 privatewrite(pre/'server.xml.before',raw);privatewrite(pre/'intent.json',json.dumps({'runId':run,'manifestSha256':a.manifest_sha256,'warHashes':manifest['wars'],'serverXmlSha256':hashlib.sha256(raw).hexdigest(),'status':'INTENT','unknownMustNotReplay':True}).encode())
 moves=[]
 for name in sorted(NAMES):
  war=WEB/name;files,dirs=expected[name];target=WEB/name[:-4]
  if target.exists() or target.is_symlink():
   try:verify_tree(target,files,dirs,war,gid);moves.append({'war':name,'action':'verified-reused'});continue
   except (OSError,Refusal):safedir(target);need(not (pre/name[:-4]).exists());os.rename(target,pre/name[:-4]);fsyncdir(WEB);fsyncdir(pre);moves.append({'war':name,'action':'prior-tree-preserved'})
  dest=stage/name[:-4];dest.mkdir(mode=0o750);dirset(dest,gid)
  with zipfile.ZipFile(war) as z:
   for entry in z.infolist():
    if entry.is_dir():
     directory=dest.joinpath(*entry.filename.rstrip('/').split('/'));directory.mkdir(parents=True,exist_ok=True,mode=0o750)
     for p in [directory,*directory.parents]:
      if p==stage:break
      dirset(p,gid)
     continue
    path=dest.joinpath(*entry.filename.split('/'));path.parent.mkdir(parents=True,exist_ok=True,mode=0o750)
    for p in [path.parent,*path.parent.parents]:
     if p==stage:break
     dirset(p,gid)
    h=hashlib.sha256();length=0;fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o640)
    with os.fdopen(fd,'wb') as out,z.open(entry) as inp:
     for b in iter(lambda:inp.read(1048576),b''):guard_bytes(len(b));out.write(b);h.update(b);length+=len(b)
     out.flush();os.fsync(out.fileno())
    fileset(path,gid);need(files[entry.filename]=={'sha256':h.hexdigest(),'size':length})
  met=dest/'META-INF';met.mkdir(mode=0o750,exist_ok=True);dirset(met,gid);tracker=met/'war-tracker';privatewrite(tracker,b'',0o640);fileset(tracker,gid);os.utime(tracker,ns=(war.stat().st_mtime_ns,war.stat().st_mtime_ns));fd=os.open(tracker,os.O_RDONLY|os.O_NOFOLLOW);os.fsync(fd);os.close(fd);need(sha(war)==manifest['wars'][name]);verify_tree(dest,files,dirs,war,gid);fsyncdir(met);fsyncdir(dest);os.rename(dest,target);fsyncdir(WEB);moves.append({'war':name,'action':'expanded-and-verified'})
 need(not any(stage.iterdir()));stage.rmdir();fsyncdir(BASE);need(SERVER.read_bytes()==raw and SERVER.stat().st_ino==server_stat.st_ino)
 if updated!=raw:
  temp=SERVER.with_name('.server.xml-preexpand-'+run);privatewrite(temp,updated,stat.S_IMODE(server_stat.st_mode));os.chown(temp,server_stat.st_uid,server_stat.st_gid)
  for key in os.listxattr(SERVER,follow_symlinks=False):os.setxattr(temp,key,os.getxattr(SERVER,key,follow_symlinks=False),follow_symlinks=False)
  fd=os.open(temp,os.O_RDONLY|os.O_NOFOLLOW);os.fsync(fd);os.close(fd)
  need(SERVER.read_bytes()==raw);os.replace(temp,SERVER);fsyncdir(SERVER.parent)
 for name in sorted(NAMES):need(sha(WEB/name)==manifest['wars'][name] and verify_tree(WEB/name[:-4],*expected[name],WEB/name,gid))
 guard_bytes(0);newstat=regular(SERVER);need(newstat.st_uid==server_stat.st_uid and newstat.st_gid==server_stat.st_gid and stat.S_IMODE(newstat.st_mode)==stat.S_IMODE(server_stat.st_mode));need(xml_change(SERVER.read_bytes())==SERVER.read_bytes());stopped();receipt={'status':'VERIFIED_TOMCAT_STOPPED','runId':run,'manifestSha256':a.manifest_sha256,'serverXmlSha256':sha(SERVER),'warHashes':manifest['wars'],'actions':moves,'tomcatStarted':False,'runtimeFunctionVerified':False}
 privatewrite(pre/'completion.json',json.dumps(receipt,sort_keys=True).encode());print(json.dumps(receipt))
if __name__=='__main__':
 try:main()
 except Exception as e:print(json.dumps({'status':'INCOMPLETE_DO_NOT_REPLAY','errorClass':type(e).__name__}));raise SystemExit(1)
