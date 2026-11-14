import importlib.util,io,pathlib,stat,tempfile,unittest,zipfile
p=pathlib.Path(__file__).with_name('preexpand_wars.py');spec=importlib.util.spec_from_file_location('preexpand',p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Validate(unittest.TestCase):
 def archive(self,entries):
  f=tempfile.NamedTemporaryFile(suffix='.war');
  with zipfile.ZipFile(f.name,'w') as z:
   for n,b in entries:z.writestr(n,b)
  self.addCleanup(f.close);return pathlib.Path(f.name)
 def test_files_dirs_and_hashes(self):
  z=self.archive([('WEB-INF/',''),('WEB-INF/web.xml','ok'),('WEB-INF/empty/',''),('index.html','yes')]);files,dirs,total=m.parse_war(z);self.assertEqual(files['WEB-INF/web.xml']['size'],2);self.assertIn('WEB-INF/empty',dirs)
 def test_refuse_slip_collisions_and_tracker(self):
  for entries in [[('WEB-INF/web.xml','a'),('../outside','b')],[('WEB-INF/web.xml','a'),('WEB-INF/web.xml','b')],[('index.html','a'),('META-INF/war-tracker','')],[('index.html','a'),('X','a'),('x','b')],[('index.html','a'),('A','a'),('A/b','b')]]:
   with self.subTest(entries=entries):
    with self.assertRaises(m.Refusal):m.parse_war(self.archive(entries))
 def test_refuse_zip_symlink(self):
  f=tempfile.NamedTemporaryFile(suffix='.war');self.addCleanup(f.close)
  with zipfile.ZipFile(f.name,'w') as z:
   z.writestr('index.html','x');i=zipfile.ZipInfo('link');i.create_system=3;i.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(i,'/etc/passwd')
  with self.assertRaises(m.Refusal):m.parse_war(pathlib.Path(f.name))
 def test_only_host_unpack_changed(self):
  old=b'<Server><Service><Engine><!-- <Host name="fake"/> --><Host name="localhost" appBase="webapps" unpackWARs="false"><Valve password="secret"/></Host></Engine></Service></Server>';new=m.xml_change(old);self.assertEqual(new,old.replace(b'unpackWARs="false"',b'unpackWARs="true"'));self.assertEqual(m.xml_change(new),new)
 def test_xml_refuses_unexpected_hosts(self):
  with self.assertRaises(m.Refusal):m.xml_change(b'<Server><Host name="bad" appBase="webapps" /></Server>')
if __name__=='__main__':unittest.main()
