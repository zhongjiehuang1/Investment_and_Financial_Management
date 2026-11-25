"""Migration decision/DDL contract tests; production MySQL validates actual SQL execution."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("ledger_schema_migration", ROOT / "scripts/migrate_ledger.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


def metadata(collation="utf8mb4_unicode_ci", **overrides):
    return dict(DATA_TYPE="varchar", CHARACTER_MAXIMUM_LENGTH=36, CHARACTER_SET_NAME="utf8mb4", COLLATION_NAME=collation,
                IS_NULLABLE="YES", COLUMN_DEFAULT=None, EXTRA="", COLUMN_COMMENT="", **overrides)


class RecordingCursor:
    def __init__(self, existing):
        self.existing = existing
        self.calls = []

    def execute(self, sql, parameters=None):
        self.calls.append((sql, parameters))

    def fetchone(self):
        return self.existing

    def ddl(self):
        return [sql for sql, parameters in self.calls if sql.startswith("ALTER TABLE")]


class LedgerSchemaCollationTest(unittest.TestCase):
    def test_old_transaction_default_does_not_determine_new_join_key(self):
        cursor = RecordingCursor(metadata("utf8mb4_0900_ai_ci"))
        migration.align_transaction_journal_column(cursor, metadata())
        self.assertEqual(["ALTER TABLE INCOME_AND_EXPENDITURE_DATA MODIFY COLUMN JOURNAL_ID VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL"], cursor.ddl())
        self.assertEqual(("INCOME_AND_EXPENDITURE_DATA", "JOURNAL_ID"), cursor.calls[0][1])

    def test_matching_column_requires_no_alteration(self):
        cursor = RecordingCursor(metadata())
        migration.align_transaction_journal_column(cursor, metadata())
        self.assertEqual([], cursor.ddl())

    def test_missing_column_is_added_with_explicit_reference_collation(self):
        cursor = RecordingCursor(None)
        migration.align_transaction_journal_column(cursor, metadata("utf8mb4_0900_ai_ci"))
        self.assertEqual(["ALTER TABLE INCOME_AND_EXPENDITURE_DATA ADD COLUMN JOURNAL_ID VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL"], cursor.ddl())

    def test_metadata_cannot_be_used_as_injected_ddl(self):
        for bad in ("utf8mb4; DROP TABLE USERS", "utf8mb4`", "utf8mb4 unicode ci", "", None):
            reference = metadata()
            reference["COLLATION_NAME"] = bad
            cursor = RecordingCursor(None)
            with self.assertRaises(ValueError):
                migration.align_transaction_journal_column(cursor, reference)
            self.assertEqual([], cursor.calls)

    def test_unfamiliar_existing_column_is_not_overwritten(self):
        for field, value in (("DATA_TYPE", "text"), ("CHARACTER_MAXIMUM_LENGTH", 50), ("IS_NULLABLE", "NO"),
                             ("COLUMN_DEFAULT", "custom"), ("EXTRA", "GENERATED"), ("COLUMN_COMMENT", "custom migration")):
            existing = metadata("utf8mb4_0900_ai_ci")
            existing[field] = value
            cursor = RecordingCursor(existing)
            with self.assertRaises(ValueError):
                migration.align_transaction_journal_column(cursor, metadata())
            self.assertEqual([], cursor.ddl())

    def test_reference_character_set_is_dynamic_not_utf8mb4_hardcoded(self):
        reference = metadata("ascii_bin")
        reference["CHARACTER_SET_NAME"] = "ascii"
        cursor = RecordingCursor(None)
        migration.align_transaction_journal_column(cursor, reference)
        self.assertIn("CHARACTER SET ascii COLLATE ascii_bin", cursor.ddl()[0])


if __name__ == "__main__":
    unittest.main()
