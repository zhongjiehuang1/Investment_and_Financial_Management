import csv
import datetime as dt
import importlib.util
import json
import os
import stat
import uuid
from unittest.mock import patch
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("inflation_import", PROJECT / "tools/inflation/import_inflation.py")
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)


def row(identifier, currency, date, rate, through=None):
    return {"ID": identifier, "VARIOUS_CURRENCIES": currency, "DATE": dt.date.fromisoformat(date),
            "AIR_IN_VARIOUS_CURRENCIES": Decimal(rate),
            "OBSERVED_THROUGH": dt.date.fromisoformat(through) if through else None,
            "GENERATION": str(uuid.uuid4())}


class FakeCursor:
    def __init__(self):
        self.calls = []
        self.rowcount = 1

    def execute(self, query, args=None):
        self.calls.append((query, args))

    def executemany(self, query, args):
        self.calls.append((query, args))


class InflationImportTest(unittest.TestCase):
    def test_exact_rates_and_month_dates(self):
        self.assertEqual(subject.exact_rate("-0.20"), Decimal("-0.20"))
        self.assertEqual(subject.exact_rate("0"), Decimal("0.00"))
        self.assertEqual(subject.month("2020-02-29"), dt.date(2020, 2, 15))
        for invalid in ("1.005", "NaN", "Infinity", "1000", "-1000"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                subject.exact_rate(invalid)

    def test_only_adjacent_known_months_collapse(self):
        months = {("GBP", dt.date(2020, value, 15)): Decimal("2.00") for value in (1, 2, 4)}
        months[("CNY", dt.date(2020, 2, 15))] = Decimal("2.00")
        spans = subject.compact(months)
        gbp = [span for span in spans if span["VARIOUS_CURRENCIES"] == "GBP"]
        self.assertEqual(len(gbp), 2)
        self.assertEqual(gbp[0]["DATE"], dt.date(2020, 1, 15))
        self.assertEqual(gbp[0]["OBSERVED_THROUGH"], dt.date(2020, 2, 15))
        self.assertEqual(subject.expand_existing(spans), months)

    def test_legacy_rows_do_not_imply_missing_months(self):
        original = [row(1, "CNY", "2016-08-15", "1.30"), row(2, "CNY", "2016-10-15", "2.10", "2016-11-15")]
        expanded = subject.expand_existing(original)
        self.assertEqual(len(expanded), 3)
        self.assertNotIn(("CNY", dt.date(2016, 9, 15)), expanded)

    def test_source_conflicts_are_reported_and_older_rows_preserved(self):
        original = [row(1, "CNY", "2016-08-15", "1.30"), row(2, "CNY", "2016-10-15", "2.10")]
        source = {("CNY", dt.date(2016, 10, 15)): Decimal("2.00"), ("CNY", dt.date(2016, 11, 15)): Decimal("2.00")}
        spans, report = subject.plan(original, source)
        self.assertEqual(len(spans), 2)
        self.assertEqual(report["new_months"], 1)
        self.assertEqual(report["conflicts"], [{"currency": "CNY", "date": "2016-10-15", "old_rate": "2.10", "source_rate": "2.00"}])
        self.assertEqual(report["coverage"][0]["retained_existing_outside_source"], 1)
        self.assertEqual(subject.expand_existing(spans)[("CNY", dt.date(2016, 8, 15))], Decimal("1.30"))
        repeated, repeated_report = subject.plan(spans, source)
        self.assertEqual(repeated, spans)
        self.assertEqual(repeated_report["conflicts"], [])
        self.assertEqual(repeated_report["new_months"], 0)

    def test_apply_is_noop_when_spans_already_match(self):
        original = [row(10, "GBP", "2020-01-15", "2.00", "2020-02-15")]
        spans = subject.compact(subject.expand_existing(original))
        cursor = FakeCursor()
        self.assertFalse(subject.apply(cursor, original, spans, []))
        self.assertEqual(cursor.calls, [])

    def test_apply_preserves_start_identity_and_only_inserts_actual_new_span(self):
        original = [row(10, "GBP", "2020-01-15", "2.00")]
        spans, _ = subject.plan(original, {("GBP", dt.date(2020, 2, 15)): Decimal("2.00"), ("USD", dt.date(2020, 1, 15)): Decimal("1.00")})
        cursor = FakeCursor()
        with patch.object(subject, "claim_id", return_value=3) as allocator:
            self.assertTrue(subject.apply(cursor, original, spans, []))
        allocator.assert_called_once_with(cursor, [])
        self.assertEqual(len(cursor.calls), 2)
        update, insert = cursor.calls
        self.assertIn("UPDATE " + subject.TABLE, update[0])
        self.assertEqual(update[1][-2:], (10, original[0]["GENERATION"]))
        self.assertEqual(update[1][3], dt.date(2020, 2, 15))
        self.assertIn("INSERT INTO " + subject.TABLE, insert[0])
        self.assertEqual(insert[1][0], 3)
        self.assertNotEqual(insert[1][-1], original[0]["GENERATION"])
        uuid.UUID(insert[1][-1])

    def test_verified_source_resolves_actual_legacy_duplicate_and_preserves_smallest_id(self):
        original = [row(102, "CNY", "2023-12-15", "-0.30"), row(122, "CNY", "2023-12-15", "0.10")]
        source = {("CNY", dt.date(2023, 12, 15)): Decimal("-0.30")}
        spans, report = subject.plan(original, source)
        self.assertEqual(report["existing_conflicts_resolved_by_source"], [{"currency": "CNY", "date": "2023-12-15", "old_rates": ["-0.30", "0.10"], "source_rate": "-0.30"}])
        self.assertEqual(subject.expand_existing(spans), source)
        cursor = FakeCursor()
        with patch.object(subject, "recycle_id") as recycler:
            self.assertTrue(subject.apply(cursor, original, spans, []))
        recycler.assert_called_once_with(cursor, 122, [])
        self.assertEqual(cursor.calls[0], (f"DELETE FROM {subject.TABLE} WHERE ID=%s AND GENERATION=%s", (122, original[1]["GENERATION"])))
        self.assertTrue(cursor.calls[1][0].startswith("UPDATE " + subject.TABLE))
        self.assertEqual(cursor.calls[1][1][-2:], (102, original[0]["GENERATION"]))

    def test_uncovered_existing_conflict_is_never_silently_resolved(self):
        original = [row(102, "CNY", "2023-12-01", "-0.30"), row(122, "CNY", "2023-12-15", "0.10")]
        with self.assertRaisesRegex(ValueError, "no verified source covers this month"):
            subject.plan(original, {("CNY", dt.date(2024, 1, 15)): Decimal("-0.80")})
        with self.assertRaisesRegex(ValueError, "no verified source covers this month"):
            subject.expand_existing(original)

    def test_csv_provenance_and_duplicate_months_validated(self):
        valid = {"currency": "GBP", "date": "2020-01-15", "rate": "1.80", "source": "Trading Economics",
                 "source_url": "https://tradingeconomics.com/united-kingdom/inflation-cpi", "frequency": "monthly", "measure": "headline_cpi_yoy"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.csv"
            for values in ([valid, dict(valid)], [{**valid, "measure": "month_on_month"}], [{**valid, "source_url": "https://example.com/"}], [{**valid, "date": "2099-01-15"}]):
                with path.open("w", newline="") as handle:
                    writer = csv.DictWriter(handle, fieldnames=valid)
                    writer.writeheader()
                    writer.writerows(values)
                with self.assertRaises(ValueError):
                    subject.load_csv(path)

    def test_bundled_public_data_has_expected_coverage_and_gap(self):
        source = subject.load_csv(PROJECT / "tools/inflation/data/inflation_monthly_raw.csv")
        self.assertEqual(len(source), 1096)
        self.assertEqual(len(subject.compact(source)), 932)
        self.assertEqual({currency for currency, date in source}, set(subject.SOURCES))
        self.assertNotIn(("USD", dt.date(2025, 10, 15)), source)
        aud = sorted(date for currency, date in source if currency == "AUD")
        self.assertEqual((len(aud), aud[0]), (17, dt.date(2025, 4, 15)))


@unittest.skipUnless(os.environ.get("IAFM_INFLATION_TEST_MYSQL_PORT"), "Explicit disposable localhost MySQL test port is required")
class InflationImportMySQLTest(unittest.TestCase):
    """Run after CreateAllTables against the explicitly named empty disposable test database."""
    @classmethod
    def setUpClass(cls):
        import pymysql
        port = int(os.environ["IAFM_INFLATION_TEST_MYSQL_PORT"])
        if not 1024 <= port <= 65535:
            raise ValueError("Use an unprivileged disposable localhost MySQL port")
        cls.connection = pymysql.connect(host="127.0.0.1", port=port, database="iafm_inflation_import_test", user="root", password="",
                                         autocommit=False, cursorclass=pymysql.cursors.DictCursor)
        with cls.connection.cursor() as cursor:
            columns, unique = subject.schema(cursor)
            if not unique or not {"GENERATION", "OBSERVED_THROUGH"}.issubset(columns):
                raise ValueError("Run the project's CreateAllTables maintenance entry point on this disposable database first")
        cls.connection.rollback()

    @classmethod
    def tearDownClass(cls):
        cls.connection.close()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.backups = Path(self.directory.name)
        with self.connection.cursor() as cursor:
            cursor.execute(f"DELETE FROM {subject.TABLE}")
            cursor.execute(f"DELETE FROM {subject.RECORDER}")
            cursor.execute("DELETE FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=%s", (subject.TABLE,))
            cursor.execute("UPDATE ID_ALLOCATION_STATE SET HIGH_WATER=0 WHERE TABLE_NAME=%s", (subject.TABLE,))
        self.connection.commit()

    def tearDown(self):
        self.connection.rollback()
        self.directory.cleanup()

    def insert(self, identifier, currency, date, rate, through=None):
        existing = row(identifier, currency, date, rate, through or date)
        with self.connection.cursor() as cursor:
            cursor.execute(f"INSERT INTO {subject.TABLE}(ID,AIR_IN_VARIOUS_CURRENCIES,VARIOUS_CURRENCIES,DATE,OBSERVED_THROUGH,GENERATION) VALUES (%s,%s,%s,%s,%s,%s)",
                           (identifier, existing["AIR_IN_VARIOUS_CURRENCIES"], currency, existing["DATE"], existing["OBSERVED_THROUGH"], existing["GENERATION"]))
            cursor.execute("UPDATE ID_ALLOCATION_STATE SET HIGH_WATER=GREATEST(HIGH_WATER,%s) WHERE TABLE_NAME=%s", (identifier, subject.TABLE))
        self.connection.commit()
        return existing

    def execute(self, sql, parameters=None):
        with self.connection.cursor() as cursor:
            cursor.execute(sql, parameters)
        self.connection.commit()

    def snapshot(self):
        with self.connection.cursor() as cursor:
            columns, _ = subject.schema(cursor)
            rows = subject.read_rows(cursor, columns)
            cursor.execute(f"SELECT TABLE_ID FROM {subject.RECORDER} ORDER BY TABLE_ID")
            recorder = list(cursor.fetchall())
            cursor.execute("SELECT TABLE_NAME,HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME=%s", (subject.TABLE,))
            state = cursor.fetchone()
            cursor.execute("SELECT TABLE_NAME,FIRST_ID,LAST_ID FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=%s ORDER BY FIRST_ID", (subject.TABLE,))
            ranges = list(cursor.fetchall())
        self.connection.rollback()
        return {"rows": rows, "id_recorder": recorder, "id_allocation_state": state, "id_recovery_ranges": ranges}

    def run_import(self, source, apply=True):
        return subject.import_observations(self.connection, source, "synthetic-source-hash", apply, self.backups, apply)

    def test_apply_reuses_smallest_id_and_preserves_generation_on_update_and_noop(self):
        original = self.insert(10, "GBP", "2020-01-15", "2.00")
        self.execute(f"INSERT INTO {subject.RECORDER}(TABLE_ID) VALUES (3),(8)")
        source = {("GBP", dt.date(2020, 2, 15)): Decimal("2.00"), ("USD", dt.date(2020, 1, 15)): Decimal("1.00")}
        before = self.snapshot()
        report, result = self.run_import(source)
        self.assertEqual(result["status"], "committed")
        after = self.snapshot()
        gbp, usd = after["rows"]
        self.assertEqual((gbp["ID"], gbp["GENERATION"]), (10, original["GENERATION"]))
        self.assertEqual(gbp["OBSERVED_THROUGH"], dt.date(2020, 2, 15))
        self.assertEqual(usd["ID"], 3)
        self.assertNotEqual(usd["GENERATION"], original["GENERATION"])
        self.assertEqual(after["id_recorder"], [{"TABLE_ID": 8}])
        self.assertEqual(self.run_import(source)[1]["status"], "unchanged")
        self.assertEqual(self.snapshot(), after)
        saved = json.loads(Path(result["backup"]).read_text())
        for key in before:
            self.assertEqual(saved[key], json.loads(json.dumps(before[key], default=str)))
        self.assertEqual(stat.S_IMODE(Path(result["backup"]).stat().st_mode), 0o600)
        self.assertIn("GENERATION", saved["columns"])

    def test_compaction_recycles_removed_start_before_new_insert_with_fresh_generation(self):
        first = self.insert(1, "GBP", "2020-01-15", "2.00")
        removed = self.insert(2, "GBP", "2020-02-15", "3.00")
        source = {("GBP", dt.date(2020, 2, 15)): Decimal("2.00"), ("USD", dt.date(2020, 1, 15)): Decimal("1.00")}
        self.run_import(source)
        gbp, usd = self.snapshot()["rows"]
        self.assertEqual((gbp["ID"], gbp["GENERATION"]), (1, first["GENERATION"]))
        self.assertEqual(usd["ID"], 2)
        self.assertNotEqual(usd["GENERATION"], removed["GENERATION"])

    def test_range_and_explicit_pool_remain_usable_at_integer_maximum(self):
        self.insert(subject.MAX_ID, "CAD", "2020-01-15", "1.50")
        self.execute(f"INSERT INTO {subject.RECORDER}(TABLE_ID) VALUES (7)")
        self.execute("INSERT INTO ID_RECOVERY_RANGES(TABLE_NAME,FIRST_ID,LAST_ID) VALUES (%s,3,4)", (subject.TABLE,))
        source = {(currency, dt.date(2020, 1, 15)): Decimal("2.00") for currency in ("GBP", "JPY", "USD")}
        self.run_import(source)
        snapshot = self.snapshot()
        self.assertEqual({row["VARIOUS_CURRENCIES"]: row["ID"] for row in snapshot["rows"]}, {"CAD": subject.MAX_ID, "GBP": 3, "JPY": 4, "USD": 7})
        self.assertEqual(snapshot["id_recorder"], [])
        self.assertEqual(snapshot["id_recovery_ranges"], [])
        self.assertEqual(snapshot["id_allocation_state"]["HIGH_WATER"], subject.MAX_ID)

    def test_failed_insert_rolls_back_compaction_generation_recycling_and_allocation(self):
        import pymysql
        self.insert(1, "GBP", "2020-01-15", "2.00")
        self.insert(2, "GBP", "2020-02-15", "3.00")
        self.execute("INSERT INTO ID_RECOVERY_RANGES(TABLE_NAME,FIRST_ID,LAST_ID) VALUES (%s,8,9)", (subject.TABLE,))
        before = self.snapshot()
        self.execute(f"ALTER TABLE {subject.TABLE} ADD CONSTRAINT SYNTHETIC_BLOCK_USD CHECK(VARIOUS_CURRENCIES<>'USD')")
        try:
            with self.assertRaises(pymysql.MySQLError):
                self.run_import({("GBP", dt.date(2020, 2, 15)): Decimal("2.00"), ("USD", dt.date(2020, 1, 15)): Decimal("1.00")})
            self.assertEqual(self.snapshot(), before)
            saved = json.loads(next(self.backups.glob("*.json")).read_text())
            for key in before:
                self.assertEqual(saved[key], json.loads(json.dumps(before[key], default=str)))
        finally:
            self.execute(f"ALTER TABLE {subject.TABLE} DROP CHECK SYNTHETIC_BLOCK_USD")

    def test_dry_run_and_missing_maintenance_confirmation_never_write(self):
        self.insert(10, "GBP", "2020-01-15", "2.00")
        before = self.snapshot()
        source = {("USD", dt.date(2020, 1, 15)): Decimal("1.00")}
        self.assertEqual(self.run_import(source, apply=False)[1]["status"], "dry-run")
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(list(self.backups.iterdir()), [])
        with self.assertRaisesRegex(ValueError, "maintenance mode"):
            subject.import_observations(self.connection, source, "synthetic", True, self.backups, False)
        self.assertEqual(self.snapshot(), before)

    def test_occupied_recycle_marker_is_never_assigned_and_max_fallback_uses_current_table(self):
        original = self.insert(1, "GBP", "2020-01-15", "2.00")
        self.execute(f"INSERT INTO {subject.RECORDER}(TABLE_ID) VALUES (1),(5)")
        self.execute("UPDATE ID_ALLOCATION_STATE SET HIGH_WATER=100 WHERE TABLE_NAME=%s", (subject.TABLE,))
        self.run_import({("USD", dt.date(2020, 1, 15)): Decimal("1.00")})
        self.assertEqual(self.snapshot()["rows"][0]["GENERATION"], original["GENERATION"])
        self.run_import({("JPY", dt.date(2020, 1, 15)): Decimal("1.00")})
        snapshot = self.snapshot()
        self.assertEqual({row["VARIOUS_CURRENCIES"]: row["ID"] for row in snapshot["rows"]}, {"GBP": 1, "JPY": 6, "USD": 5})
        self.assertEqual(snapshot["id_allocation_state"]["HIGH_WATER"], 100)



if __name__ == "__main__":
    unittest.main()
