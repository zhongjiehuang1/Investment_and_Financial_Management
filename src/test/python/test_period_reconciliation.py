"""Synthetic ledger exports with immutable identities and reciprocal transfer legs."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from reconcile_period_performance import reconcile
from export_user_data import Client


def generation(number):
    return f"00000000-0000-0000-0000-{number:012x}"


class PeriodReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.digest = "a" * 64
        self.a, self.b = generation(10), generation(11)
        self.template = dict(userGeneration=generation(1), startDate="2026-01-01", endDate="2026-02-01",
                             openingValues=[dict(account=self.a), dict(account=self.b)],
                             closingValues=[dict(account=self.a), dict(account=self.b)], externalFlows=[])
        self.export = dict(profile=dict(generation=generation(1)), transactions=[])
        self.review = dict(ledgerExportSha256=self.digest, ledgerCoverageConfirmed=True, classifications={})

    def transaction(self, number, account, kind, **fields):
        return dict(id=number, databaseId=1, generation=generation(number), platformGeneration=account,
                    type=kind, date="2026-01-15", amount="10.00", currency="GBP", **fields)

    def add_pair(self):
        out = self.transaction(2, self.a, "transfer_out", journalId=generation(20), pairedTransfer=True,
                               counterpartId=3, counterpartDatabaseId=1, counterpartGeneration=generation(3),
                               counterpartAmount="10.00", counterpartCurrency="GBP")
        incoming = self.transaction(3, self.b, "transfer_in", journalId=generation(20), pairedTransfer=True,
                                    counterpartId=2, counterpartDatabaseId=1, counterpartGeneration=generation(2),
                                    counterpartAmount="10.00", counterpartCurrency="GBP")
        self.export["transactions"] += [out, incoming]
        return out, incoming

    def reconcile(self):
        return reconcile(self.template, self.export, self.review, self.digest)

    def test_internal_pair_is_removed_and_cross_boundary_leg_is_retained(self):
        self.add_pair()
        result, summary = self.reconcile()
        self.assertEqual([], result["externalFlows"])
        self.assertEqual(2, summary["internalTransferLegs"])
        for key in ("openingValues", "closingValues"):
            self.template[key].pop()
        result, _ = self.reconcile()
        self.assertEqual([dict(reference=generation(2), account=self.a, date="2026-01-15",
                               currency="GBP", amount="-10.00")], result["externalFlows"])

    def test_cross_currency_pair_preserves_native_amount(self):
        out, incoming = self.add_pair()
        incoming.update(currency="USD", amount="12.10")
        out.update(counterpartCurrency="USD", counterpartAmount="12.10")
        for key in ("openingValues", "closingValues"):
            self.template[key].pop(0)
        result, _ = self.reconcile()
        self.assertEqual("USD", result["externalFlows"][0]["currency"])
        self.assertEqual("12.10", result["externalFlows"][0]["amount"])

    def test_unverified_transfers_and_unclassified_rows_block_publication(self):
        out, _ = self.add_pair()
        out["counterpartGeneration"] = generation(51)
        self.export["transactions"].append(self.transaction(4, self.b, "expense"))
        result, summary = self.reconcile()
        self.assertIsNone(result)
        self.assertEqual(["unverified_transfer", "unverified_transfer", "needs_economic_classification"],
                         [row["reason"] for row in summary["reviewRequired"]])

    def test_reviewed_dividends_and_explicit_external_flows_are_distinct(self):
        self.export["transactions"] = [self.transaction(4, self.b, "income"),
                                       self.transaction(5, self.a, "income")]
        self.review["classifications"] = {generation(4): "performance", generation(5): "external"}
        result, _ = self.reconcile()
        self.assertEqual(self.digest, result["ledgerExportSha256"])
        self.assertEqual([generation(5)], [flow["reference"] for flow in result["externalFlows"]])
        self.review["ledgerExportSha256"] = "b" * 64
        with self.assertRaises(ValueError):
            self.reconcile()

    def test_unknown_account_and_owner_mismatch_fail_closed(self):
        self.export["transactions"] = [self.transaction(5, "", "expense")]
        result, summary = self.reconcile()
        self.assertIsNone(result)
        self.assertEqual("missing_account_identity", summary["reviewRequired"][0]["reason"])
        self.export["profile"]["generation"] = generation(90)
        with self.assertRaises(ValueError):
            self.reconcile()

    def test_export_rejects_snapshot_drift_and_preserves_sharded_ids(self):
        snapshot = dict(dataRevision="one", userGeneration=generation(1))
        client = object.__new__(Client)
        response = dict(snapshot, total=2, items=[dict(id=1, databaseId=1), dict(id=1, databaseId=2)],
                        hasNext=False, page=1, pageSize=100)
        client.get = lambda *args, **kwargs: response
        self.assertEqual(2, len(list(client.rows("transactions", snapshot=snapshot))))
        response["dataRevision"] = "two"
        with self.assertRaises(RuntimeError):
            list(client.rows("transactions", snapshot=snapshot))


if __name__ == "__main__":
    unittest.main()
