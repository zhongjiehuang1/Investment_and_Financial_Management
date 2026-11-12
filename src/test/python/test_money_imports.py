"""Test only isolated amount functions; never import or run the statement importers."""
import ast
from pathlib import Path
import re
import unittest


class MoneyImporterTests(unittest.TestCase):
    def test_pure_money_functions_and_script_syntax(self):
        project = Path(__file__).resolve().parents[3]
        scripts = [
            project / "src/main/Python/1/import_income_expense.py",
            project / "src/main/Python/5/import_hsbc_online_bonus_saver.py",
            project / "src/main/Python/6/import_hsbc_all_statements.py",
            project / "src/main/Python/7/import_lloyds_49_statements.py",
        ]
        for script in scripts:
            with self.subTest(script=script.name):
                source = script.read_text(encoding="utf-8")
                compile(source, str(script), "exec")  # Syntax only: no data is decoded or executed.
                tree = ast.parse(source)
                functions = ast.Module(body=[
                    node for node in tree.body
                    if isinstance(node, ast.FunctionDef) and node.name in {"money_minor", "money_text", "decimal_text"}
                ], type_ignores=[])
                namespace = {"re": re, "Any": object, "ImportErrorWithMessage": ValueError}
                exec(compile(functions, str(script), "exec"), namespace)
                parse = namespace["money_minor"]
                format_minor = namespace["money_text"]
                self.assertEqual(parse("0.10") + parse("0.20"), 30)
                self.assertEqual(format_minor(parse("-0.01")), "-0.01")
                self.assertEqual(format_minor(parse("0001.2")), "1.20")
                self.assertEqual(format_minor(sum(parse("0.01") for _ in range(10000))), "100.00")
                self.assertEqual(format_minor(parse("90071992547409.91") + 1), "90071992547409.92")
                for invalid in ("", "NaN", "Infinity", "1e3", "1.001", "1,000.00", "1.000", None):
                    with self.assertRaises(ValueError):
                        parse(invalid)
                if "decimal_text" in namespace:
                    self.assertEqual(namespace["decimal_text"]("-123.4"), "123.40")


if __name__ == "__main__":
    unittest.main()
