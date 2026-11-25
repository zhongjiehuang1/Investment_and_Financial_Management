"""Synthetic statement layout coverage for repeatable period evidence."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from prepare_hsbc_saver_report import build_report, reconcile_statement


class SaverReportTest(unittest.TestCase):
    first = """Online Bonus Saver
    Test Owner 00-00-00 00000000 1
    Closing Balance £125.00
    01 Mar 26 BALANCE BROUGHT FORWARD 100.00
    05 Mar 26 TFR Some Account
    INTERNET TRANSFER 20.00 120.00
    01 Apr 26 CR GROSS INTEREST
    TO 31MAR2026 2.00
    CR ADDED GROSS INTEREST 3.00 125.00
    01 Apr 26 BALANCE CARRIED FORWARD 125.00
    """
    second = """Online Bonus Saver
    Test Owner 00-00-00 00000000 2
    Closing Balance £115.00
    01 Apr 26 BALANCE BROUGHT FORWARD 125.00
    10 Apr 26 TFR Some Account
    INTERNET TRANSFER 10.00 115.00
    01 May 26 BALANCE CARRIED FORWARD 115.00
    """

    def build(self, second):
        return build_report([([second], "second"), ([self.first], "first")], "owner", "saver",
                            "Savings", "account", None)

    def test_interest_stays_in_account_performance_and_transfers_get_preflow_values(self):
        report = self.build(self.second)
        self.assertEqual("100.00", report["openingValues"][0]["amount"])
        self.assertEqual("115.00", report["closingValues"][0]["amount"])
        self.assertEqual(["20.00", "-10.00"], [row["amount"] for row in report["externalFlows"]])
        self.assertEqual(["100.00", "125.00"],
                         [row["values"][0]["amount"] for row in report["preFlowValuations"]])
        self.assertNotIn("00000000", str(report))

    def test_mixed_accounts_and_unreconciled_interest_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "different accounts"):
            self.build(self.second.replace("00000000", "00000001"))
        with self.assertRaisesRegex(ValueError, "Interest does not reconcile"):
            reconcile_statement([self.first.replace("3.00 125.00", "1.00 125.00")])


if __name__ == "__main__":
    unittest.main()
