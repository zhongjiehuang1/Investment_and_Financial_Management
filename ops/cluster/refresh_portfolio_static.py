"""Refresh only the exact Portfolio static allowlist after a reviewed WAR build.

Default is a read-only plan. This never contacts or deploys to a server.
"""
from pathlib import Path
import argparse,ast,hashlib,json,os,re,stat,uuid,zipfile
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def public_paths(war):
 paths={'/'};seen=set()
 with zipfile.ZipFile(war)as z:
  for member in z.infolist():
   name=member.filename
   if member.is_dir():continue
   assert name not in seen and not name.startswith('/')and '\\'not in name and '..'not in Path(name).parts;seen.add(name)
   assert (member.external_attr>>16)&0o170000 in(0,stat.S_IFREG)
   if name.startswith(('WEB-INF/','META-INF/')):continue
   assert re.fullmatch('[A-Za-z0-9_./-]+',name)and not any(x.startswith('.')for x in Path(name).parts)
   assert not name.lower().endswith(('.key','.pem','.properties','.java','.class','.sql','.jar','.war','.log'))
   paths.add('/'+name)
   if name.endswith('/index.html'):paths.add('/'+name[:-10])
 assert '/index.html'in paths
 return tuple(sorted(paths))
def main():
 p=argparse.ArgumentParser();p.add_argument('--war',type=Path,required=True);p.add_argument('--war-sha256',required=True);p.add_argument('--entry-source',type=Path,default=Path(__file__).with_name('lab_entry.py'));p.add_argument('--apply',action='store_true');p.add_argument('--preimage-dir',type=Path);a=p.parse_args()
 assert re.fullmatch('[0-9a-f]{64}',a.war_sha256)and a.war.is_file()and not a.war.is_symlink()and digest(a.war)==a.war_sha256
 f=a.entry_source;s=f.lstat();assert stat.S_ISREG(s.st_mode)and not f.is_symlink()and s.st_uid==os.getuid();raw=f.read_text();tree=ast.parse(raw);items=[n for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='LOCAL_STATIC_PATHS'for t in n.targets)];assert len(items)==1
 node=items[0];values=ast.literal_eval(node.value);assert set(values)=={'iafm','ucost','starhub','portfolio'};paths=public_paths(a.war);values['portfolio']=paths;lines=raw.splitlines(keepends=True);updated=''.join(lines[:node.lineno-1])+('LOCAL_STATIC_PATHS = '+repr(values)+'\n')+''.join(lines[node.end_lineno:]);ast.parse(updated)
 result={'mode':'APPLY'if a.apply else'PLAN','warSha256':a.war_sha256,'portfolioPublicPaths':len(paths),'entrySourceBeforeSha256':hashlib.sha256(raw.encode()).hexdigest(),'entrySourceAfterSha256':hashlib.sha256(updated.encode()).hexdigest(),'networkCalls':0}
 if a.apply:
  assert a.preimage_dir and a.preimage_dir.is_absolute()and a.preimage_dir.is_dir()and not a.preimage_dir.is_symlink()and a.preimage_dir.stat().st_mode&0o777==0o700
  tag=str(uuid.uuid4());before=a.preimage_dir/('lab-entry-before-'+tag+'.py');fd=os.open(before,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
  with os.fdopen(fd,'w')as o:o.write(raw);o.flush();os.fsync(o.fileno())
  assert f.read_text()==raw and f.stat().st_ino==s.st_ino;tmp=f.with_name('.'+f.name+'.static-'+tag);fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,stat.S_IMODE(s.st_mode))
  with os.fdopen(fd,'w')as o:o.write(updated);o.flush();os.fsync(o.fileno())
  os.replace(tmp,f)
 print(json.dumps(result))
if __name__=='__main__':main()
