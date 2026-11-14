import json,os,subprocess,sys,tempfile,unittest,gzip,hashlib
from pathlib import Path
D=Path(__file__).resolve().parent
sys.path.insert(0,str(D))
import sql_import_validator as sql
import restore_inventory as inventory
class RestoreLocal(unittest.TestCase):
 def test_default_plan_has_no_io(self):
  r=subprocess.run([sys.executable,'-B',str(D/'single_database_restore.py')],capture_output=True,text=True,check=True)
  v=json.loads(r.stdout);self.assertEqual(v['mode'],'LOCAL_PLAN_ONLY');self.assertEqual(v['remoteOperations'],0);self.assertFalse(v['fileChanges'])
 def test_missing_pins_fail_before_remote(self):
  r=subprocess.run([sys.executable,'-B',str(D/'single_database_restore.py'),'--execute'],capture_output=True,text=True)
  self.assertNotEqual(r.returncode,0);self.assertIn('REVIEWED_PACKAGE_REQUIRED',r.stderr)
 def test_stopped_unit_must_have_effective_permanent_or_runtime_mask(self):
  good={'LoadState':'masked','UnitFileState':'masked-runtime','ActiveState':'inactive','MainPID':'0','ControlPID':'0'}
  self.assertTrue(inventory.masked_unit(good,True)['emptyCgroup'])
  good['UnitFileState']='masked';self.assertEqual(inventory.masked_unit(good,True)['unitFileState'],'masked')
  for changed in ({'LoadState':'loaded'},{'UnitFileState':'enabled'},{'ActiveState':'active'},{'MainPID':'3'},{'ControlPID':'3'}):
   with self.assertRaises(inventory.Refusal):inventory.masked_unit({**good,**changed},True)
  with self.assertRaises(inventory.Refusal):inventory.masked_unit(good,False)
 def test_dynamic_schema_refuses_mysql_and_cross_schema(self):
  raw=b'CREATE DATABASE IF NOT EXISTS `one` DEFAULT CHARACTER SET utf8mb4; USE `one`; CREATE TABLE `t` (`id` int PRIMARY KEY) ENGINE=InnoDB;\n';h=hashlib.sha256(raw).hexdigest()
  with tempfile.TemporaryDirectory() as d:
   f=Path(d)/'x.gz';f.write_bytes(gzip.compress(raw))
   self.assertEqual(sql.validate_gzip(f,len(raw),h,['one'])['tableCount'],1)
   for schemas in (['mysql'],['one','one'],['../one']):
    with self.assertRaises(sql.Refused):sql.validate_gzip(f,len(raw),h,schemas)
   with self.assertRaises(sql.Refused):sql.validate_gzip(f,len(raw),h,['two'])
 def test_source_and_restore_ordered_dump_options_match(self):
  import ast
  def options(path,marker):
   tree=ast.parse(path.read_text())
   for node in ast.walk(tree):
    if isinstance(node,ast.List):
     text=[n.value for n in node.elts if isinstance(n,ast.Constant) and isinstance(n.value,str)]
     if '/usr/bin/mysqldump' in text and marker in text:return [x for x in text if x.startswith('--') and x!='--defaults-file=/var/lib/codex-validation/private/client.cnf']
   self.fail('canonical mysqldump options not found')
  for marker in ('--order-by-primary','--no-data'):
   self.assertEqual(options(D/'remote_backup.py',marker),options(D/'isolated_data_digest.py',marker))
 def test_import_validator_refuses_active_trigger(self):
  raw=b'CREATE DATABASE IF NOT EXISTS `one`; USE `one`; CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW SET @x=1;\n';h=hashlib.sha256(raw).hexdigest()
  with tempfile.TemporaryDirectory() as d:
   f=Path(d)/'x.gz';f.write_bytes(gzip.compress(raw))
   with self.assertRaises(sql.Refused):sql.validate_gzip(f,len(raw),h,['one'])
if __name__=='__main__':unittest.main()
