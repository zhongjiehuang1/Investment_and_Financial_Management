"""Synthetic records only: ownership, ambiguity and money representation boundaries."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


audit = load("reconciliation_test", ROOT / "src/main/Python/reconcile_internal_transfers.py")
migration = load("migration_test", ROOT / "scripts/migrate_ledger.py")


class ReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.identities = {
            "a": {"owner_confirmed": True, "names": {("sample", "owner")}, "numbers": {"11111111"}},
            "b": {"owner_confirmed": True, "names": {("sample", "owner")}, "numbers": {"22222222"}},
        }

    def row(self, identifier, source, kind, name, **overrides):
        return dict(id=identifier, source=source, account_id=source, date="2025-01-15", currency="GBP", amount="10.00", type=kind, name=name, source_pdf_verified=True, **overrides)

    def test_reciprocal_account_reference_pairs(self):
        rows = [self.row("out", "a", "expense", "TFR 22222222"), self.row("in", "b", "income", "TFR 11111111")]
        pairs, review = audit.pair_rows(rows, self.identities)
        self.assertEqual(1, len(pairs))
        self.assertEqual("reciprocal_account_references", pairs[0]["evidence"])
        self.assertEqual([], review)

    def test_equal_date_and_amount_never_prove_transfer(self):
        rows = [self.row("out", "a", "expense", "Groceries"), self.row("in", "b", "income", "Salary")]
        pairs, review = audit.pair_rows(rows, self.identities)
        self.assertEqual([], pairs)
        self.assertEqual(1, len(review))

    def test_two_candidates_are_never_arbitrarily_paired(self):
        rows = [self.row("out", "a", "expense", "TFR 22222222"), self.row("in1", "b", "income", "TFR 11111111"), self.row("in2", "b", "income", "TFR 11111111")]
        pairs, review = audit.pair_rows(rows, self.identities)
        self.assertEqual([], pairs)
        self.assertEqual(2, len(review))

    def test_fees_stay_external_and_missing_source_evidence_blocks_pairing(self):
        rows = [self.row("out", "a", "expense", "TFR 22222222 fee"), self.row("in", "b", "income", "TFR 11111111")]
        self.assertEqual([], audit.pair_rows(rows, self.identities)[0])
        rows[0]["name"] = "TFR 22222222"
        rows[0]["source_pdf_verified"] = False
        self.assertEqual([], audit.pair_rows(rows, self.identities)[0])

    def test_unproven_ownership_and_cross_currency_are_not_auto_paired(self):
        rows = [self.row("out", "a", "expense", "TFR 22222222"), self.row("in", "b", "income", "TFR 11111111")]
        self.identities["b"]["owner_confirmed"] = False
        self.assertEqual([], audit.pair_rows(rows, self.identities)[0])
        self.identities["b"]["owner_confirmed"] = True
        rows[1]["currency"] = "USD"
        self.assertEqual([], audit.pair_rows(rows, self.identities)[0])

    def test_same_account_is_never_a_pair(self):
        rows = [self.row("out", "a", "expense", "TFR 11111111"), self.row("in", "a", "income", "TFR 11111111")]
        self.assertEqual([], audit.pair_rows(rows, self.identities)[0])

    def test_database_money_canonicalization_has_no_float_rounding(self):
        self.assertEqual("10.00", migration.money("10"))
        self.assertEqual("10.10", migration.money("10.1"))
        self.assertEqual("999999999999.99", migration.money("999999999999.99"))
        for value in ("1.001", "NaN", "Infinity", "-1"):
            with self.assertRaises(ValueError):
                migration.money(value)


if __name__ == "__main__":
    unittest.main()
