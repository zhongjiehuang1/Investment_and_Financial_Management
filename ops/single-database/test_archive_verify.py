import hashlib,io,json,pathlib,tarfile,tempfile,unittest
import archive_verify
import capture_config
from unittest import mock

class CaptureTests(unittest.TestCase):
 def make(self,*,content=b'hello',declared=b'hello',node=2,bad_path=None,omit_tester=False,omit_repo=False,omit_apparmor=False):
  t=tempfile.NamedTemporaryFile(suffix='.tar.gz');self.addCleanup(t.close)
  inventory={'format':'current-system-rebuild-inventory-v1','nodeId':node,'installedPackages':[{'package':'example','version':'1'}],'serviceIdentities':[{'name':'root','uid':0}],'groups':[{'name':'root','gid':0}],'unitStates':{'ufw.service':{'ActiveState':'active'}},'passwordHashesIncluded':False}
  files={bad_path or 'etc/example.conf':content,'etc/fstab':b'f','etc/sysctl.conf':b's','etc/passwd':b'p','etc/group':b'g','etc/os-release':b'o','usr/lib/os-release':b'o','SYSTEM_REBUILD_INVENTORY.json':json.dumps(inventory).encode()}
  for name in ['etc/apt/sources.list', 'etc/apt/sources.list.d/mysql-official.list', 'etc/apt/keyrings/mysql-official.gpg', 'usr/share/keyrings/ubuntu-archive-keyring.gpg', 'etc/apparmor.d/usr.sbin.mysqld', 'etc/apparmor.d/local/usr.sbin.mysqld']:files[name]=b'public repository or profile fixture'
  if omit_apparmor:
   files.pop('etc/apparmor.d/usr.sbin.mysqld');files.pop('etc/apparmor.d/local/usr.sbin.mysqld')
  if omit_repo:files.pop('etc/apt/keyrings/mysql-official.gpg')
  if not omit_tester:files['var/lib/codex-validation/app-config/security/tester-password']=b'private fixture only'
  dirs=['etc/sysctl.d','etc/ufw','etc/letsencrypt/accounts'];rows=[]
  with tarfile.open(t.name,'w:gz') as a:
   for name in dirs:
    info=tarfile.TarInfo(name);info.type=tarfile.DIRTYPE;info.mode=0o700;info.uid=info.gid=0;a.addfile(info);rows.append({'path':name,'type':'directory','mode':info.mode,'uid':0,'gid':0})
   for name,data in files.items():
    info=tarfile.TarInfo(name);info.mode=0o600;info.uid=info.gid=0;info.size=len(data);a.addfile(info,io.BytesIO(data));expected=declared if name==(bad_path or 'etc/example.conf') else data;rows.append({'path':name,'type':'file','mode':info.mode,'uid':0,'gid':0,'bytes':len(expected),'sha256':hashlib.sha256(expected).hexdigest()})
   manifest={'format':'current-single-database-config-capture-v1','nodeId':node,'files':rows,'includesSecrets':True,'excludesBusinessData':True,'excludesWarAndSources':True};raw=json.dumps(manifest).encode();m=tarfile.TarInfo('CONFIG_CAPTURE_MANIFEST.json');m.size=len(raw);a.addfile(m,io.BytesIO(raw))
  return pathlib.Path(t.name)
 def test_verifies_entry_hash_without_extracting(self):
  out=archive_verify.verify(self.make(),2);self.assertTrue(out['allArchivedHashesVerified']);self.assertFalse(out['actualRestorePerformed'])
 def test_node1_no_server_apparmor_allowed_but_node2_refuses(self):
  self.assertTrue(archive_verify.verify(self.make(node=1,omit_apparmor=True),1)['allArchivedHashesVerified'])
  with self.assertRaises(ValueError):archive_verify.verify(self.make(node=2,omit_apparmor=True),2)
 def test_refuses_mutation_wrong_node_slip_and_missing_tester(self):
  with self.assertRaises(ValueError):archive_verify.verify(self.make(content=b'changed'),2)
  with self.assertRaises(ValueError):archive_verify.verify(self.make(node=1),2)
  with self.assertRaises(ValueError):archive_verify.verify(self.make(bad_path='../bad'),2)
  with self.assertRaises(ValueError):archive_verify.verify(self.make(omit_tester=True),2)
  with self.assertRaises(ValueError):archive_verify.verify(self.make(omit_repo=True),2)
class UnitCaptureTests(unittest.TestCase):
 def test_runtime_mask_captures_persistent_unit_and_dropins(self):
  unit='codex-lab-tomcat.service'
  persistent='/usr/local/lib/systemd/system/'+unit
  result=mock.Mock(stdout='LoadState=masked\nFragmentPath=/run/systemd/system/'+unit+'\nDropInPaths=/etc/systemd/system/'+unit+'.d/a.conf\n')
  with mock.patch.object(capture_config.subprocess,'run',return_value=result), mock.patch.object(pathlib.Path,'exists',lambda p:str(p)==persistent), mock.patch.object(pathlib.Path,'is_symlink',return_value=False), mock.patch.object(capture_config,'add') as add:
   capture_config.addunit(unit)
  self.assertEqual([str(c.args[0]) for c in add.call_args_list],[persistent,'/etc/systemd/system/'+unit+'.d/a.conf'])
 def test_runtime_mask_without_persistent_unit_refuses(self):
  unit='codex-lab-tomcat.service'
  result=mock.Mock(stdout='LoadState=masked\nFragmentPath=/run/systemd/system/'+unit+'\nDropInPaths=\n')
  with mock.patch.object(capture_config.subprocess,'run',return_value=result), mock.patch.object(pathlib.Path,'exists',return_value=False), mock.patch.object(pathlib.Path,'is_symlink',return_value=False), self.assertRaises(capture_config.Refusal):
   capture_config.addunit(unit)
if __name__=='__main__':unittest.main()
