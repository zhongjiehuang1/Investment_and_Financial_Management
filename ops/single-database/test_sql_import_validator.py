import unittest,gzip,tempfile,hashlib
from pathlib import Path
import sql_import_validator as mod
from sql_import_validator import Validator,Refused,validate_gzip
SCHEMAS={"ucost_app","starhub_accounts"}
class ImportBoundary(unittest.TestCase):
 def setUp(self):mod.SCHEMAS=set(SCHEMAS)
 def validator(self):
  v=Validator();v.text('USE `ucost_app`; CREATE TABLE `owned` (`id` bigint PRIMARY KEY, `payload` longtext) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;');return v
 def test_safe_literals_not_instructions(self):
  self.validator().text("INSERT INTO `owned` VALUES (1,'; DROP DATABASE mysql; \\\\!echo denied'),(2,0x00FF),(3,NULL);")
 def test_client_commands_denied(self):
  for sql in ['SOURCE /etc/passwd;','DELIMITER $$;','\\! id;','\\. /tmp/input;','connect mysql;','charset utf8mb4;']:
   with self.subTest(kind=sql.split()[0]),self.assertRaises(Refused):self.validator().text(sql)
 def test_global_replication_accounts_plugins_denied(self):
  for sql in ["SET @@GLOBAL.GTID_PURGED='x';","/*!80000 SET @@GLOBAL.GTID_PURGED='x' */;",'CHANGE REPLICATION SOURCE TO SOURCE_HOST=\'x\';','START GROUP_REPLICATION;','CREATE USER x;','GRANT ALL ON *.* TO x;','INSTALL PLUGIN x SONAME \'evil.so\';','CREATE FUNCTION x RETURNS STRING SONAME \'evil.so\';','USE mysql;','DROP TABLE mysql.user;']:
   with self.subTest(index=sql.split()[0]),self.assertRaises(Refused):self.validator().text(sql)
 def test_external_expressions_and_storage_denied(self):
  for sql in ["INSERT INTO owned VALUES (1,LOAD_FILE('/etc/passwd'));","INSERT INTO owned SELECT 1,2;","CREATE TABLE evil (id int) ENGINE=FEDERATED CONNECTION='mysql://x';","CREATE TABLE evil (id int) ENGINE=InnoDB DATA DIRECTORY='/tmp';","CREATE TABLE evil (id int DEFAULT (SLEEP(1))) ENGINE=InnoDB;","ALTER TABLE owned ADD id2 int;","CREATE TRIGGER evil BEFORE INSERT ON owned FOR EACH ROW SET @x=1;","CREATE TABLE evil (id int) ENGINE=MyISAM;"]:
   with self.subTest(index=sql.split()[0]),self.assertRaises(Refused):self.validator().text(sql)
 def test_comments_and_version_comments_differ(self):
  self.validator().text("/* SET @@GLOBAL.GTID_PURGED='not executed' */;")
  with self.assertRaises(Refused):self.validator().text("/*!80000 SET @@GLOBAL.GTID_PURGED='executed' */;")
 def test_mode_and_variable_injection_denied(self):
  for sql in ["SET SQL_MODE='NO_BACKSLASH_ESCAPES';","SET @evil=1;","SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO'; SELECT 1;","SET @@GLOBAL.SQL_LOG_BIN=0;"]:
   with self.assertRaises(Refused):self.validator().text(sql)
 def test_exact_schema_set_and_gzip_hash(self):
  text=''.join('CREATE DATABASE IF NOT EXISTS `'+s+'` DEFAULT CHARACTER SET utf8mb4; USE `'+s+'`;'for s in sorted(SCHEMAS))+'\n';raw=text.encode()
  with tempfile.TemporaryDirectory()as d:
   p=Path(d)/'test.gz';p.write_bytes(gzip.compress(raw));self.assertEqual(validate_gzip(p,len(raw),hashlib.sha256(raw).hexdigest(),sorted(SCHEMAS))['schemaCount'],2)
   with self.assertRaises(Refused):validate_gzip(p,len(raw),'0'*64,sorted(SCHEMAS))
  with self.assertRaises(Refused):self.validator().done()
if __name__=='__main__':unittest.main()
