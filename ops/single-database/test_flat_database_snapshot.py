import contextlib, hashlib, io, json, os, pathlib, subprocess, tempfile, unittest, uuid
from unittest.mock import patch
import flat_database_snapshot as flat

class FlatSnapshotTest(unittest.TestCase):
 def test_inventory_and_summary_are_bounded_to_the_snapshot(self):
  m={'schemas':['one','two'],'baseTableCount':2,'viewCount':1,'mysqlUuid':'test','createdAtUtc':'2026-11-14T21:00:00+00:00'}
  b={'server2':{'applicationsStopped':True,'identity':{'uuid':'test'},'inventory':{'schemas':['one','two'],'baseTableCount':2,'viewCount':1,'objects':[['one','secret_name','InnoDB','BASE TABLE'],['one','v','', 'VIEW'],['two','x','InnoDB','BASE TABLE']]}}}
  counts=flat.validate_inventory(m,b);text=flat.summary(m,counts)
  self.assertIn('| `one` | 1 | 1 |',text);self.assertNotIn('secret_name',text);self.assertIn('mysql',text)
  b['server2']['inventory']['baseTableCount']=3
  with self.assertRaises(ValueError):flat.validate_inventory(m,b)

 def test_encrypted_components_are_verified_and_copied_without_server_configuration(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=pathlib.Path(tmp);private=root/'private';work=private/'Work';backups=root/'backups';stage=work/'Flat_Database_Snapshots'
   for p in (private,work,backups):p.mkdir(mode=0o700)
   key=os.urandom(32);paths=[private/'key-a',private/'key-b']
   for p in paths:p.write_bytes(key);p.chmod(0o600)
   custody=private/'recovery-key-custody.json';custody.write_text(json.dumps({'primaryPath':str(paths[0]),'secondaryPath':str(paths[1])}));custody.chmod(0o600)
   run=str(uuid.uuid4());backup=backups/('backup-'+run);backup.mkdir(mode=0o700)
   items=[]
   for index,name in enumerate(flat.NAMES):
    plain=root/('plain-'+str(index));plain.write_bytes(('test component '+str(index)).encode());plain.chmod(0o600)
    cipher=backup/name
    subprocess.run([str(flat.NODE),str(flat.ENV),'encrypt',str(paths[0]),str(plain),str(cipher)],capture_output=True,check=True)
    cipher.chmod(0o600);items.append({'name':name,'encryptedBytes':cipher.stat().st_size,'encryptedSha256':flat.sha(cipher),'plaintextBytes':plain.stat().st_size,'plaintextSha256':flat.sha(plain)})
   m={'format':'single-server-database-backup-v1','runId':run,'applicationsStoppedOnBothHosts':True,'sourceContentDigestBeforeAndAfterDumpMatch':True,'schemas':['example'],'baseTableCount':1,'viewCount':0,'mysqlUuid':'test','createdAtUtc':'2026-11-14T21:00:00+00:00','files':items,'configurationFromBothServers':True}
   manifest=backup/'MANIFEST.json';manifest.write_text(json.dumps(m));manifest.chmod(0o600)
   receipt=work/'Single_Database_Backup'/('single-database-backup-'+run);receipt.mkdir(parents=True,mode=0o700)
   before={'server2':{'applicationsStopped':True,'identity':{'uuid':'test'},'inventory':{'schemas':['example'],'baseTableCount':1,'viewCount':0,'objects':[['example','data','InnoDB','BASE TABLE']]}}}
   (receipt/'before.json').write_text(json.dumps(before));(receipt/'before.json').chmod(0o600)
   with patch.multiple(flat,PRIVATE=private,WORK=work,BACKUPS=backups,STAGING=stage),contextlib.redirect_stdout(io.StringIO()):
    flat.prepare(backup,flat.sha(manifest))
   out=stage/('flat-'+run);self.assertEqual({p.name for p in out.iterdir()},{*flat.NAMES,'MANIFEST.json','DATABASE_SUMMARY.md'})
   derived=json.loads((out/'MANIFEST.json').read_text());self.assertFalse(derived['configurationFromBothServers']);self.assertEqual(derived['sourceBackupManifestSha256'],flat.sha(manifest))
   self.assertEqual([x['name'] for x in derived['files']],list(flat.NAMES))

if __name__=='__main__':unittest.main()
