import hashlib,io,os,stat,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import isolated_data_digest as digest

class Process:
 def __init__(self,body):self.stdout=io.BytesIO(body);self.exited=False;self.killed=False;self.waited=False
 def wait(self,timeout=None):self.exited=True;self.waited=True;return 0
 def poll(self):return 0 if self.exited else None
 def kill(self):self.killed=True;self.exited=True

class SchemaDumpTests(unittest.TestCase):
 def test_optional_private_dump_is_same_unnormalized_stream(self):
  data=b'CREATE TABLE t (id int);\nAUTO_INCREMENT=42\n'
  with tempfile.TemporaryDirectory() as name:
   os.chmod(name,0o700);out=Path(name)/'schema.private.sql'
   with patch.object(digest.subprocess,'Popen',return_value=Process(data)):
    result=digest.schema_digest('/fake/client.cnf',['IAFM'],output_path=out)
   self.assertEqual(out.read_bytes(),data);self.assertEqual(result,hashlib.sha256(data).hexdigest());self.assertEqual(stat.S_IMODE(out.stat().st_mode),0o600)
   with patch.object(digest.subprocess,'Popen',return_value=Process(b'changed')):
    with self.assertRaises(FileExistsError):digest.schema_digest('/fake/client.cnf',['IAFM'],output_path=out)
   self.assertEqual(out.read_bytes(),data)
 def test_symlink_or_nonprivate_parent_rejected(self):
  with tempfile.TemporaryDirectory() as name:
   real=Path(name)/'real';real.mkdir(mode=0o700);link=Path(name)/'link';link.symlink_to(real,target_is_directory=True)
   with self.assertRaises(ValueError):digest.schema_digest('/fake/client.cnf',['IAFM'],output_path=link/'x')
   os.chmod(real,0o755)
   with self.assertRaises(ValueError):digest.schema_digest('/fake/client.cnf',['IAFM'],output_path=real/'x')
 def test_stream_failure_kills_and_waits_for_owned_dump(self):
  class Broken(io.BytesIO):
   def read(self,*args):raise OSError('synthetic stream failure')
  child=Process(b'');child.stdout=Broken()
  with tempfile.TemporaryDirectory() as name:
   os.chmod(name,0o700)
   with patch.object(digest.subprocess,'Popen',return_value=child):
    with self.assertRaises(OSError):digest.schema_digest('/fake/client.cnf',['IAFM'],output_path=Path(name)/'schema.private.sql')
  self.assertTrue(child.killed);self.assertTrue(child.waited);self.assertTrue(child.stdout.closed)
if __name__=='__main__':unittest.main()
