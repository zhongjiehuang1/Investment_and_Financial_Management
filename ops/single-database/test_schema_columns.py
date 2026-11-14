import copy,hashlib,importlib.util,json,os,pathlib,sys,tempfile,unittest
from unittest.mock import Mock
HERE=pathlib.Path(__file__).resolve().parent;spec=importlib.util.spec_from_file_location('schema_columns',HERE/'schema_fingerprint.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def column(schema='IAFM',table='USERS',name='ID',ordinal=1):
 return {'record':'column','schema':schema,'table':table,'column':name,'ordinal':ordinal,'default':None,'nullable':'NO','dataType':'int','charLength':None,'octetLength':None,'numericPrecision':10,'numericScale':0,'datetimePrecision':None,'charset':None,'collation':None,'columnType':'int unsigned','columnKey':'PRI','extra':'auto_increment','comment':'','generationExpression':'','srsId':None,'engineAttribute':None,'secondaryEngineAttribute':None}
def encode(rows):return b'\n'.join(json.dumps(r,ensure_ascii=False,separators=(',',':')).encode() for r in rows)+b'\n'
def initial():return [{'record':'setting','gipk':1},{'record':'schema','schema':'IAFM'},{'record':'table','schema':'IAFM','table':'USERS','tableType':'BASE TABLE'},column()]
class Test(unittest.TestCase):
 def test_independent_inventory_and_column_coverage_are_required(self):
  rows=initial();m.digest_rows(encode(rows),['IAFM'],{'IAFM'},[('IAFM','USERS','BASE TABLE')])
  with self.assertRaises(m.Refused):m.digest_rows(encode(rows),['IAFM'],{'IAFM'},[('IAFM','OTHER','BASE TABLE')])
  with self.assertRaises(m.Refused):m.digest_rows(encode(rows[:-1]),['IAFM'],{'IAFM'})
 def test_output_digest_stable_and_exposes_no_attributes(self):
  one=m.digest_rows(encode(initial()),['IAFM'],{'IAFM'});self.assertEqual(set(one),{'algorithm','sha256','columnCount'});self.assertEqual(one['algorithm'],m.ALGORITHM);self.assertEqual(one['columnCount'],1);self.assertEqual(one,m.digest_rows(encode(initial()),['IAFM'],{'IAFM'}));self.assertEqual(len(one['sha256']),64)
 def test_every_definition_attribute_changes_digest(self):
  base=initial();orig=m.digest_rows(encode(base),['IAFM'],{'IAFM'})['sha256'];changes={'default':'0','nullable':'YES','dataType':'bigint','charLength':4,'octetLength':4,'numericPrecision':11,'numericScale':1,'datetimePrecision':2,'charset':'utf8mb4','collation':'utf8mb4_bin','columnType':'bigint','columnKey':'MUL','extra':'','comment':'private metadata not output','generationExpression':'(`a` + 1)','srsId':4326,'engineAttribute':{'x':1},'secondaryEngineAttribute':{'y':2}}
  for k,v in changes.items():
   rows=copy.deepcopy(base);rows[-1][k]=v;self.assertNotEqual(orig,m.digest_rows(encode(rows),['IAFM'],{'IAFM'})['sha256'],k)
 def test_json_canonical_nested_objects(self):
  x=initial();y=initial();x[-1]['engineAttribute']={'b':1,'a':2};y[-1]['engineAttribute']={'a':2,'b':1};self.assertEqual(m.digest_rows(encode(x),['IAFM'],{'IAFM'}),m.digest_rows(encode(y),['IAFM'],{'IAFM'}))
 def test_schemas_whitelist_and_identifier_escape(self):
  for chosen,allowed in [(['IAFM','IAFM'],{'IAFM'}),(['IAFM'],{'ucost_app'}),(['mysql'],{'mysql'}),(["a' OR 1=1"],{"a' OR 1=1"}),([] ,{'IAFM'})]:
   with self.assertRaises(m.Refused):m.build_query(chosen,allowed)
  sql=m.build_query(['ucost_app','IAFM'],{'ucost_app','IAFM'});self.assertNotIn('GROUP_CONCAT',sql);self.assertIn('information_schema.COLUMNS_EXTENSIONS',sql);self.assertIn('show_gipk_in_create_table_and_information_schema=ON',sql);self.assertNotIn('SELECT *',sql)
 def test_missing_schema_columns_order_duplicates_and_gipk_fail_closed(self):
  samples=[[],[{'record':'setting','gipk':0},{'record':'schema','schema':'IAFM'},{'record':'table','schema':'IAFM','table':'USERS','tableType':'BASE TABLE'},column()],[{'record':'setting','gipk':1},column()],initial()+[column()],initial()+[column(name='X',ordinal=3)],[{'record':'setting','gipk':1},{'record':'schema','schema':'IAFM'},column(name='X',ordinal=2),column()]]
  for rows in samples:
   with self.assertRaises(m.Refused):m.digest_rows(encode(rows),['IAFM'],{'IAFM'})
  incomplete=initial();del incomplete[-1]['srsId']
  with self.assertRaises(m.Refused):m.digest_rows(encode(incomplete),['IAFM'],{'IAFM'})
 def test_mysql_invocation_private_defaults_and_no_data_emission(self):
  with tempfile.TemporaryDirectory() as directory:
   folder=pathlib.Path(directory).resolve();binary=folder/'mysql';binary.write_bytes(b'fixture');binary.chmod(0o700);config=folder/'config';config.write_text('[client]\n');config.chmod(0o600);runner=Mock(return_value=Mock(returncode=0,stdout=encode(initial())))
   out=m.schema_column_digests(binary,config,['IAFM'],allowed_schemas={'IAFM'},runner=runner);self.assertEqual(1,out['columnCount']);args,kwargs=runner.call_args;self.assertEqual(str(binary),args[0][0]);self.assertEqual('--defaults-file='+str(config),args[0][1]);self.assertEqual(kwargs['stderr'],m.subprocess.DEVNULL);self.assertIn(b'information_schema.COLUMNS',kwargs['input']);self.assertNotIn('default',out)
   config.chmod(0o644)
   with self.assertRaises(m.Refused):m.schema_column_digests(binary,config,['IAFM'],allowed_schemas={'IAFM'},runner=runner)
 def test_failure_and_partial_metadata_never_accepted(self):
  with tempfile.TemporaryDirectory() as directory:
   folder=pathlib.Path(directory).resolve();binary=folder/'mysql';binary.write_bytes(b'x');binary.chmod(0o700);config=folder/'config';config.write_bytes(b'x');config.chmod(0o600)
   with self.assertRaises(m.Refused):m.schema_column_digests(binary,config,['IAFM'],allowed_schemas={'IAFM'},runner=Mock(return_value=Mock(returncode=1,stdout=b'')))
   with self.assertRaises(m.Refused):m.schema_column_digests(binary,config,['IAFM'],allowed_schemas={'IAFM'},runner=Mock(return_value=Mock(returncode=0,stdout=b'not-json')))
if __name__=='__main__':unittest.main()
