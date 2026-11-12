"""Exercise isolated importer client classes against fake responses; no service or records are read."""
import ast
import json
from pathlib import Path
from types import SimpleNamespace
import unittest


PROJECT = Path(__file__).resolve().parents[3]
SCRIPTS = [
    PROJECT / "src/main/Python/1/import_income_expense.py",
    PROJECT / "src/main/Python/5/import_hsbc_online_bonus_saver.py",
    PROJECT / "src/main/Python/6/import_hsbc_all_statements.py",
    PROJECT / "src/main/Python/7/import_lloyds_49_statements.py",
]


def isolated_client(script):
    tree = ast.parse(script.read_text(encoding="utf-8"))
    client_node = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "FinanceHttpClient")
    namespace = {
        "Any": object, "Transaction": object, "json": json,
        "ImportErrorWithMessage": ValueError,
        "HttpRequestError": type("FakeHttpRequestError", (RuntimeError,), {}),
        "DEFAULT_ENDPOINT": "/addIncomeOrExpense", "FALLBACK_ENDPOINT": "/AddIncomeOrExpense",
        "EXPECTED_USER_ID": 2,
    }
    exec(compile(ast.Module(body=[client_node], type_ignores=[]), str(script), "exec"), namespace)
    # The real constructor prepares network/cookie handlers. It is intentionally never called.
    client = object.__new__(namespace["FinanceHttpClient"])
    client.resolved_add_endpoint = None
    return client


class FakePages:
    def __init__(self, count):
        self.count = count
        self.calls = []

    def request(self, path, *, json_data=None, form=None):
        assert path == "/getIncomeAndExpenditureData"
        assert json_data["pageSize"] == 100
        page = json_data["page"]
        self.calls.append(page)
        start = (page - 1) * 100
        items = [{"id": value + 1} for value in range(start, min(start + 100, self.count))]
        body = {
            "status": "success", "items": items, "size": len(items),
            "page": page, "pageSize": 100, "total": self.count,
            "hasNext": start + len(items) < self.count,
        }
        return 200, json.dumps(body).encode(), {}


class ImporterPaginationTests(unittest.TestCase):
    def test_every_importer_counts_second_and_third_pages(self):
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                client = isolated_client(script)
                fake = FakePages(205)
                client._request = fake.request
                self.assertEqual(client.count_transactions(), 205)
                self.assertEqual(fake.calls, [1, 2, 3])
                fake.calls.clear()
                ids = [item["id"] for item in client.iter_transactions()]
                self.assertEqual(ids, list(range(1, 206)))
                self.assertEqual(fake.calls, [1, 2, 3])

    def test_zero_and_exact_page_size_do_not_request_extra_pages(self):
        for script in SCRIPTS:
            for count in (0, 100, 200):
                with self.subTest(script=script.name, count=count):
                    client = isolated_client(script)
                    fake = FakePages(count)
                    client._request = fake.request
                    self.assertEqual(client.count_transactions(), count)
                    self.assertEqual(fake.calls, list(range(1, max(1, (count + 99) // 100) + 1)))

    def test_invalid_pagination_stops_instead_of_miscounting(self):
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                client = isolated_client(script)
                for body in (
                    {"status": "success", "items": [], "total": 205, "page": 1, "hasNext": True},
                    {"status": "success", "items": [{"id": 1}], "total": 205, "hasNext": False},
                    {"status": "failure", "message": "Fake read failure"},
                ):
                    client._request = lambda *args, **kwargs: (200, json.dumps(body).encode(), {})
                    with self.assertRaises(ValueError):
                        client.count_transactions()

    def test_add_requires_an_explicit_json_success_before_returning(self):
        transaction = SimpleNamespace(form_data=lambda: {"amount": "1.00", "platform": "Cash"})
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                client = isolated_client(script)
                client.user_generation = "00000000-0000-0000-0000-000000000001"
                client._account_generations = {"Cash": "00000000-0000-0000-0000-000000000002"}
                for status, body in (
                    (200, b'{"status":"failure","message":"Fake write failure"}'),
                    (200, b'{}'), (200, b'<html>Sign in</html>'), (204, b''),
                ):
                    client._request = lambda *args, **kwargs: (status, body, {})
                    with self.assertRaises(ValueError):
                        client.add_transaction("/addIncomeOrExpense", transaction)
                client._request = lambda *args, **kwargs: (200, b'{"status":"success"}', {})
                client.add_transaction("/addIncomeOrExpense", transaction)

    def test_owner_and_account_generation_are_pinned_for_the_import(self):
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                client = isolated_client(script)
                calls = []
                generation = "00000000-0000-0000-0000-000000000002"
                def request(path, **kwargs):
                    calls.append(path)
                    if path == "/getUserProfile":
                        body = {"id": 2, "userGeneration": generation}
                    elif path == "/getPlatforms":
                        body = {"items": [{"name": "Cash", "generation": generation}], "hasNext": False}
                    else:
                        raise AssertionError(path)
                    return 200, json.dumps({"status": "success", **body}).encode(), {}
                client._request = request
                transaction = SimpleNamespace(form_data=lambda: {"amount": "1.00", "platform": "Cash"})
                self.assertEqual(client._identity_form(transaction)["platformGeneration"], generation)
                # A later replacement must not be silently accepted by re-reading its identity.
                client._request = lambda *a, **kw: self.fail("Identity must remain pinned")
                self.assertEqual(client._identity_form(transaction)["platformGeneration"], generation)
                self.assertEqual(calls, ["/getUserProfile", "/getPlatforms"])

    def test_wrong_owner_stops_before_any_write(self):
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                client = isolated_client(script)
                client._request = lambda *a, **kw: (200, json.dumps({"status": "success", "id": 4,
                    "userGeneration": "00000000-0000-0000-0000-000000000001"}).encode(), {})
                with self.assertRaises(ValueError):
                    client._identity_form(SimpleNamespace(form_data=lambda: {"platform": "Cash"}))


if __name__ == "__main__":
    unittest.main()
