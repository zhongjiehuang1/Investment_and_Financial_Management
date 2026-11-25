import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import uuid

MODULE = Path(__file__).with_name('publish_flat_database_snapshot.py')
spec = importlib.util.spec_from_file_location('flat_publisher', MODULE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        p.ROOT = base / 'cloud'
        p.HISTORY = p.ROOT / 'Historical_Archives' / 'Database_Backups'
        p.WORK = base / 'private'
        p.STAGES = p.WORK / 'Flat_Database_Snapshots'
        p.JOURNAL = p.WORK / 'Flat_Database_Publications'
        p.HISTORY.mkdir(parents=True)
        p.STAGES.mkdir(parents=True)
        self.run = str(uuid.uuid4())
        self.restore = str(uuid.uuid4())
        self.stage = p.STAGES / ('flat-' + self.run)
        self.stage.mkdir()
        source = p.HISTORY / ('backup-' + self.run)
        source.mkdir()
        (source / 'MANIFEST.json').write_text('{"verified":true}')
        parts = []
        for name in ('All_Databases.sql.gz.GCM', 'Database_Accounts_and_Grants.json.GCM'):
            path = self.stage / name
            path.write_bytes(('encrypted-'+name).encode())
            parts.append({'name':name, 'encryptedBytes':path.stat().st_size, 'encryptedSha256':p.digest(path), 'bothRecoveryKeysAuthenticated':True})
        (self.stage / 'DATABASE_SUMMARY.md').write_text('summary')
        manifest = {'flatDatabaseOnly':True,'sourceServer':2,'runId':self.run,'gtidStableDuringQuiescentBackup':True,
                    'files':parts,'sourceBackupManifestSha256':p.digest(source / 'MANIFEST.json'),
                    'sourceContentSha256':'a'*64,'createdAtUtc':'2026-11-14T22:00:00+00:00'}
        (self.stage / 'MANIFEST.json').write_text(json.dumps(manifest))
        self.hash = p.digest(self.stage / 'MANIFEST.json')
        proof = {'status':'PASS','runId':self.restore,'sourceManifestSha256':self.hash,'dataSha256':'a'*64,
                 'isolatedMysqlExited':True,'ownedCleanupPerformed':True,'productionDatadirIdentityUnchanged':True}
        canonical = p.WORK / 'Single_Database_Restore' / ('restore-'+self.restore)
        canonical.mkdir(parents=True)
        (canonical / 'completion.json').write_text(json.dumps(proof))
        (self.stage / 'RESTORE_VERIFICATION.json').write_text(json.dumps(proof))
        self.old = p.ROOT / 'Databases_Backup_2026-11-13'
        self.old.mkdir()
        (self.old / 'old-encrypted-snapshot').write_bytes(b'old-backup')

    def tearDown(self):
        self.temp.cleanup()

    def call(self, execute=False):
        argv=['publish','--snapshot',str(self.stage),'--manifest-sha256',self.hash]
        if execute: argv.append('--execute')
        with mock.patch.object(sys,'argv',argv), contextlib.redirect_stdout(io.StringIO()) as output:
            p.main()
        return json.loads(output.getvalue())

    def test_plan_then_publish_preserves_old_and_produces_only_flat_files(self):
        self.assertEqual('PLAN_ONLY',self.call()['status'])
        self.assertTrue((self.old/'old-encrypted-snapshot').is_file())
        result=self.call(True)
        self.assertEqual('COMPLETE',result['status'])
        current=Path(result['target'])
        self.assertEqual('Databases_Backup_2026-11-14',current.name)
        self.assertEqual(p.FILES,{x.name for x in current.iterdir()})
        self.assertTrue(all(x.is_file() for x in current.iterdir()))
        self.assertEqual(b'old-backup',(Path(result['archive'])/'old-encrypted-snapshot').read_bytes())
        self.assertFalse(self.old.exists())
        self.assertFalse((p.JOURNAL/'exclusive.lock').exists())

    def test_modified_ciphertext_refuses_without_moving_old_backup(self):
        (self.stage/'All_Databases.sql.gz.GCM').write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.call(True)
        self.assertEqual(b'old-backup',(self.old/'old-encrypted-snapshot').read_bytes())
        self.assertFalse(p.JOURNAL.exists())

    def test_failed_final_rename_keeps_history_stage_and_lock(self):
        real_rename=p.os.rename
        def interrupted(src,dst):
            if Path(dst).name == 'Databases_Backup_2026-11-14': raise OSError('simulated failure')
            return real_rename(src,dst)
        with mock.patch.object(p.os,'rename',side_effect=interrupted), self.assertRaises(OSError): self.call(True)
        self.assertTrue((p.JOURNAL/'exclusive.lock').is_dir())
        self.assertEqual(1,len(list(p.HISTORY.glob('History_Databases_Backup_2026-11-13_*'))))
        self.assertEqual(1,len(list(p.ROOT.glob('.Databases_Backup_staging_*'))))

if __name__ == '__main__': unittest.main()
