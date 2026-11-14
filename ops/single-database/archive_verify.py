"""Verify current config tar contents against its internal manifest without extraction."""
import hashlib,json,pathlib,tarfile

def verify(path,node):
 stream=path if hasattr(path,'read') else None;path=None if stream is not None else pathlib.Path(path);rows={};manifest=None;inventory=None;total=0
 with tarfile.open(name=path,mode='r:gz',fileobj=stream) as tar:
  for m in tar:
   p=pathlib.PurePosixPath(m.name)
   if m.name.startswith('/') or '\\' in m.name or any(c in ('.','..') for c in p.parts) or m.name in rows or len(rows)>50000:raise ValueError('unsafe configuration archive')
   if m.name=='CONFIG_CAPTURE_MANIFEST.json':
    if manifest is not None or not m.isfile() or m.size>10_000_000:raise ValueError('unsafe manifest')
    manifest=json.loads(tar.extractfile(m).read());continue
   row={'path':m.name,'mode':m.mode,'uid':m.uid,'gid':m.gid}
   if m.isfile():
    total+=m.size
    if total>1_000_000_000:raise ValueError('configuration archive too large')
    h=hashlib.sha256();n=0
    with tar.extractfile(m) as f:
     if m.name=='SYSTEM_REBUILD_INVENTORY.json':
      if m.size>10_000_000:raise ValueError('inventory too large')
      data=f.read();h.update(data);n=len(data);inventory=json.loads(data)
     else:
      for b in iter(lambda:f.read(1048576),b''):h.update(b);n+=len(b)
    if n!=m.size:raise ValueError('configuration file length mismatch')
    row.update(type='file',bytes=n,sha256=h.hexdigest())
   elif m.isdir():row['type']='directory'
   elif m.issym():row.update(type='symlink',target=m.linkname)
   else:raise ValueError('unsupported archive entry')
   rows[m.name]=row
 if not isinstance(manifest,dict) or manifest.get('format')!='current-single-database-config-capture-v1' or manifest.get('nodeId')!=node or manifest.get('includesSecrets') is not True or manifest.get('excludesBusinessData') is not True or manifest.get('excludesWarAndSources') is not True:raise ValueError('invalid configuration capture manifest')
 expected=manifest.get('files');
 if not isinstance(expected,list) or len(expected)!=len(rows) or any(not isinstance(x,dict) or rows.get(x.get('path'))!=x for x in expected):raise ValueError('configuration archive differs from captured evidence')
 required={'var/lib/codex-validation/app-config/security/tester-password','etc/apt/sources.list','etc/apt/sources.list.d/mysql-official.list','etc/apt/keyrings/mysql-official.gpg','usr/share/keyrings/ubuntu-archive-keyring.gpg','etc/fstab','etc/sysctl.conf','etc/passwd','etc/group','usr/lib/os-release','SYSTEM_REBUILD_INVENTORY.json'}
 if node==2:required.update(('etc/apparmor.d/usr.sbin.mysqld','etc/apparmor.d/local/usr.sbin.mysqld'))
 if any(rows.get(name,{}).get('type')!='file' for name in required) or rows.get('etc/os-release',{}).get('type') not in ('file','symlink'):raise ValueError('required rebuild evidence missing')
 if any(rows.get(name,{}).get('type')!='directory' for name in ('etc/sysctl.d','etc/ufw','etc/letsencrypt/accounts')):raise ValueError('required configuration directory missing')
 if not isinstance(inventory,dict) or inventory.get('format')!='current-system-rebuild-inventory-v1' or inventory.get('nodeId')!=node or inventory.get('passwordHashesIncluded') is not False or not inventory.get('installedPackages') or not inventory.get('serviceIdentities') or not inventory.get('groups') or not inventory.get('unitStates'):raise ValueError('invalid system rebuild inventory')
 return {'nodeId':node,'entries':len(rows),'fileCount':sum(r['type']=='file' for r in rows.values()),'regularFileBytes':total,'allArchivedHashesVerified':True,'actualRestorePerformed':False}
