import io,unittest
from schema_fingerprint import schema_hashes,validate_schema_binding
HEAD=b"/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;\n"
TAIL=b'/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;\n'
def schema(column,extra=b''):
 return HEAD+b'CREATE TABLE `t` (\n'+column+b'\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n'+extra+TAIL
def fp(x):return schema_hashes(io.BytesIO(x))
class SchemaFingerprintTests(unittest.TestCase):
 def test_narrow_target_equivalence_and_raw_retained(self):
  for t in (b'char(36)',b'varchar(160)',b'tinytext',b'text',b'mediumtext',b'longtext'):
   a=schema(b'  `value` '+t+b' COLLATE utf8mb4_unicode_ci DEFAULT NULL,');b=schema(b'  `value` '+t+b' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,');x,y=fp(a),fp(b);self.assertNotEqual(x['rawSha256'],y['rawSha256']);self.assertEqual(x['schemaSha256'],y['schemaSha256']);self.assertEqual(x['normalizedColumns'],1);self.assertEqual(y['normalizedColumns'],0)
 def test_other_schema_changes_still_fail(self):
  base=schema(b'  `value` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,')
  variants=[base.replace(b'varchar(20)',b'varchar(21)'),base.replace(b'DEFAULT NULL',b'NOT NULL'),base.replace(b'DEFAULT NULL',b"DEFAULT 'x'"),base.replace(b'utf8mb4_unicode_ci',b'utf8mb4_bin'),base.replace(b'DEFAULT CHARSET=utf8mb4',b'DEFAULT CHARSET=latin1'),base.replace(b'ENGINE=InnoDB',b'ENGINE=MyISAM'),base.replace(b'DEFAULT NULL',b'DEFAULT NULL AUTO_INCREMENT'),base.replace(b'ENGINE=InnoDB',b'KEY `idx` (`value`)\n) ENGINE=InnoDB'),base.replace(b'ENGINE=InnoDB',b'CONSTRAINT `fk` FOREIGN KEY (`value`) REFERENCES `p` (`id`)\n) ENGINE=InnoDB')]
  for variant in variants:self.assertNotEqual(fp(base)['schemaSha256'],fp(variant)['schemaSha256'])
 def test_comment_literals_and_unrelated_collations_unchanged(self):
  cases=[HEAD+b"/*\nCREATE TABLE `t` (\n  `x` varchar(2) COLLATE utf8mb4_unicode_ci,\n)\n*/\n"+TAIL,schema(b"  `x` varchar(30) DEFAULT ' COLLATE utf8mb4_unicode_ci',"),schema(b'  `x` varchar(30) CHARACTER SET latin1 COLLATE latin1_bin,'),schema(b'  `x` varchar(30) COLLATE utf8mb4_bin,'),schema(b'  `x` varchar(30) COMMENT \' COLLATE utf8mb4_unicode_ci\',')]
  for item in cases:
   v=fp(item);self.assertEqual(v['normalizedColumns'],0);self.assertEqual(v['rawSha256'],v['schemaSha256'])
 def test_unsupported_mode_and_truncated_table_fail_closed(self):
  with self.assertRaises(ValueError):fp(b'CREATE TABLE `x` (\n  `a` varchar(5) COLLATE utf8mb4_unicode_ci\n);\n')
  with self.assertRaises(ValueError):fp(HEAD+b'CREATE TABLE `x` (\n  `a` varchar(5) COLLATE utf8mb4_unicode_ci\n')
  with self.assertRaises(ValueError):fp(HEAD+b"SET SQL_MODE='NO_BACKSLASH_ESCAPES';\n"+schema(b'  `a` char(2) COLLATE utf8mb4_unicode_ci,')[len(HEAD):])
  with self.assertRaises(ValueError):fp(HEAD+b'/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;\n'+schema(b'  `a` char(2) COLLATE utf8mb4_unicode_ci,')[len(HEAD):])
 def test_v1_requires_original_raw_and_v2_requires_all_column_proofs(self):
  v1={'sourceSchemaDigestAlgorithm':'mysql-schema-dump-sha256-v1','sourceSchemaSha256':'a'*64};self.assertEqual(validate_schema_binding(v1)['schemaAlgorithm'],'mysql-schema-dump-sha256-v1')
  v2={'sourceSchemaDigestAlgorithm':'mysql-schema-dump-charset-semantic-v2','sourceSchemaSha256':'b'*64,'sourceRawSchemaSha256':'a'*64,'sourceColumnMetadataAlgorithm':'mysql-information-schema-columns-sha256-v1','sourceColumnMetadataSha256':'c'*64,'sourceColumnCount':1,'sourceMysqlVersion':'26.10.0'};self.assertEqual(validate_schema_binding(v2)['expectedColumnFingerprint'],'c'*64)
  for key in ('sourceRawSchemaSha256','sourceColumnMetadataAlgorithm','sourceColumnMetadataSha256','sourceColumnCount'):
   missing=dict(v2);del missing[key]
   with self.assertRaises(ValueError):validate_schema_binding(missing)
if __name__=='__main__':unittest.main()
