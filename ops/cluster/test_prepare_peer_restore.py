import io
import unittest

from prepare_peer_restore import transform


def dump(extra=b""):
    parts = []
    for name in (b"IAFM", b"starhub_accounts", b"ucost_app"):
        parts.extend([b"CREATE DATABASE /*!32312 IF NOT EXISTS*/ `" + name + b"`;\n",
                      b"USE `" + name + b"`;\n",
                      b"INSERT INTO `t` VALUES ('`IAFM`',1);\n"])
    return b"".join(parts) + extra


class PreparePeerRestoreTest(unittest.TestCase):
    def test_preserves_row_contents_and_isolates_all_three_schemas(self):
        out = io.BytesIO()
        transform(io.BytesIO(dump()), out)
        result = out.getvalue()
        for name in (b"IAFM_peer", b"starhub_accounts_peer", b"ucost_app_peer"):
            self.assertIn(b"USE `" + name + b"`;", result)
        self.assertEqual(result.count(b"INSERT INTO `t` VALUES ('`IAFM`',1);"), 3)

    def test_rejects_unknown_and_incomplete_database_declarations(self):
        for content in (dump(b"USE `production`;\n"), dump().replace(b"USE `IAFM`;\n", b"")):
            with self.subTest(content=content), self.assertRaises(ValueError):
                transform(io.BytesIO(content), io.BytesIO())

    def test_rejects_cross_schema_foreign_key_and_gtid_changes(self):
        for extra in (b"CREATE TABLE t (FOREIGN KEY (id) REFERENCES `other`.`t` (id));\n",
                      b"SET @@GLOBAL.GTID_PURGED='abc';\n", b"DROP DATABASE IAFM;\n",
                      b"CREATE DEFINER=`root`@`localhost` TRIGGER t BEFORE INSERT ON x FOR EACH ROW SET @x=1;\n"):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                transform(io.BytesIO(dump(extra)), io.BytesIO())


if __name__ == "__main__":
    unittest.main()
