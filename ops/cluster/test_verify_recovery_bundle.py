import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import verify_recovery_bundle as verify


class RecoveryBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        proof={node:{'result':'match','network_disabled':True,'tables_compared':1,'mysql_accounts':1} for node in ('node1','node2')}
        (self.root/'cold-restore-verification.json').write_text(json.dumps(proof))
        for node in (1,2):
            for suffix in ('databases.sql.gz','table-checksums.tsv','host-inventory.txt'):
                (self.root/f'node{node}-{suffix}').write_bytes(b'test')
            self.archive(f'node{node}-mysql-cold.tar.gz',['mysql/data'])
            self.archive(f'node{node}-recovery-files.tar.gz',verify.RECOVERY_PATHS)
        self.write_manifest()

    def tearDown(self):self.temp.cleanup()

    def archive(self,name,entries):
        with tarfile.open(self.root/name,'w:gz') as archive:
            for entry in sorted(entries):
                info=tarfile.TarInfo(entry);info.size=1;archive.addfile(info,io.BytesIO(b'x'))

    def write_manifest(self):
        entries={}
        for path in self.root.iterdir():
            if path.name=='manifest.json':continue
            data=path.read_bytes();entries[path.name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        (self.root/'manifest.json').write_text(json.dumps(entries))

    def test_valid_pair_and_tampering(self):
        self.assertEqual({'files':11,'tables':2},verify.verify(self.root))
        (self.root/'node1-host-inventory.txt').write_bytes(b'changed')
        with self.assertRaises(ValueError):verify.verify(self.root)

    def test_rejects_unsafe_archive_and_unverified_restore(self):
        self.archive('node1-mysql-cold.tar.gz',['mysql/data','mysql/../../outside']);self.write_manifest()
        with self.assertRaises(ValueError):verify.verify(self.root)
        self.archive('node1-mysql-cold.tar.gz',['mysql/data'])
        (self.root/'cold-restore-verification.json').write_text('{}');self.write_manifest()
        with self.assertRaises(ValueError):verify.verify(self.root)


if __name__=='__main__':unittest.main()
