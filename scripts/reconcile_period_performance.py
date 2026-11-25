#!/usr/bin/env python3
"""Prepare private period-performance inputs from an owner-reviewed ledger export."""

import argparse
from collections import Counter, defaultdict
from datetime import date
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile


def identity(value):
    if not isinstance(value, str) or not re.fullmatch(
            r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", value):
        raise ValueError("Expected a record generation.")
    return value.lower()


def amount(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,18}(?:\.[0-9]{1,2})?", value):
        raise ValueError("Expected a nonnegative two-decimal ledger amount.")
    return Decimal(value)


def unique_object(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError("Duplicate JSON key.")
        result[key] = value
    return result


def load(raw):
    return json.loads(raw, object_pairs_hook=unique_object)


def pair_verified(legs):
    if len(legs) != 2 or any(leg.get("pairedTransfer") is not True for leg in legs):
        return False
    first, second = legs
    if {first["type"], second["type"]} != {"transfer_in", "transfer_out"}:
        return False
    if first["date"] != second["date"] or first["platformGeneration"] == second["platformGeneration"]:
        return False
    if first["currency"] == second["currency"] and first["amount"] != second["amount"]:
        return False
    return all(
        leg.get("counterpartGeneration", "").lower() == other["generation"]
        and leg.get("counterpartId") == other["id"]
        and leg.get("counterpartDatabaseId") == other["databaseId"]
        and leg.get("counterpartCurrency") == other["currency"]
        and leg.get("counterpartAmount") == format(other["amount"], ".2f")
        and other["amount"] > 0
        for leg, other in ((first, second), (second, first))
    )


def reconcile(template, export, review, digest):
    if identity(template["userGeneration"]) != identity(export["profile"]["generation"]):
        raise ValueError("The ledger export and report owner differ.")
    if review.get("ledgerExportSha256") != digest or review.get("ledgerCoverageConfirmed") is not True:
        raise ValueError("The review must confirm coverage against the exact ledger export.")
    if template.get("externalFlows"):
        raise ValueError("Start with an empty externalFlows list in the valuation template.")

    start, end = (date.fromisoformat(template[key]) for key in ("startDate", "endDate"))
    scope = {identity(row["account"]) for row in template["openingValues"]}
    closing = {identity(row["account"]) for row in template["closingValues"]}
    if not start < end or not scope or scope != closing:
        raise ValueError("The period or account scope is invalid.")

    decisions = {identity(key): value for key, value in review["classifications"].items()}
    if len(decisions) != len(review["classifications"]) or any(
            value not in ("external", "performance") for value in decisions.values()):
        raise ValueError("Use external or performance classifications.")

    transactions = {}
    journals = defaultdict(list)
    for entry in export["transactions"]:
        generation = identity(entry["generation"])
        if generation in transactions:
            raise ValueError("Duplicate ledger transaction generation.")
        if any(type(entry.get(key)) is not int or entry[key] <= 0 for key in ("id", "databaseId")):
            raise ValueError("Invalid exported ledger record identity.")
        row = dict(entry, generation=generation, amount=amount(entry["amount"]))
        row["platformGeneration"] = identity(entry["platformGeneration"]) if entry.get("platformGeneration") else None
        transactions[generation] = row
        if row.get("journalId"):
            journals[row["journalId"]].append(row)

    issues = []
    counts = Counter()
    used = set()
    flows = []
    for generation, row in transactions.items():
        if not start < date.fromisoformat(row["date"]) <= end:
            continue
        account = row["platformGeneration"]
        if account is None:
            issues.append({"generation": generation, "reason": "missing_account_identity"})
            continue
        if account not in scope:
            continue
        kind = row["type"]
        if kind in ("transfer_in", "transfer_out"):
            legs = journals.get(row.get("journalId"), [])
            if any(leg["platformGeneration"] is None for leg in legs) or not pair_verified(legs):
                issues.append({"generation": generation, "reason": "unverified_transfer"})
                continue
            if all(leg["platformGeneration"] in scope for leg in legs):
                counts["internalTransferLegs"] += 1
                continue
        elif kind in ("income", "expense"):
            if row["amount"] == 0 and generation not in decisions:
                continue
            decision = decisions.get(generation)
            if decision is None:
                issues.append({"generation": generation, "reason": "needs_economic_classification"})
                continue
            used.add(generation)
            if decision == "performance":
                counts["performanceRows"] += 1
                continue
        else:
            issues.append({"generation": generation, "reason": "unknown_transaction_type"})
            continue
        positive = kind in ("income", "transfer_in")
        flows.append({"reference": generation, "date": row["date"], "account": account,
                      "currency": row["currency"], "amount": format(row["amount"] if positive else -row["amount"], ".2f")})
    for generation in sorted(decisions.keys() - used):
        issues.append({"generation": generation, "reason": "unused_classification"})

    summary = {"ledgerExportSha256": digest, "reviewRequired": issues, "externalFlows": len(flows), **counts}
    if issues:
        return None, summary
    # Match canonical UUIDs used in ledger rows without changing display or valuation metadata.
    result = dict(template, externalFlows=sorted(flows, key=lambda row: (row["date"], row["reference"])),
                  ledgerExportSha256=digest)
    for key in ("openingValues", "closingValues"):
        result[key] = [dict(row, account=identity(row["account"])) for row in template[key]]
    if "preFlowValuations" in template:
        result["preFlowValuations"] = [
            dict(snapshot, values=[dict(row, account=identity(row["account"])) for row in snapshot["values"]])
            for snapshot in template["preFlowValuations"]
        ]
    return result, summary


def write_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("template", "ledger-export", "review", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--review-output", type=Path, help="Private details for transactions needing review.")
    args = parser.parse_args()
    paths = [args.template, args.ledger_export, args.review, args.output]
    if args.review_output is not None:
        paths.append(args.review_output)
    if len({path.resolve() for path in paths}) != len(paths):
        parser.error("Inputs and outputs must use different files.")
    try:
        raw = args.ledger_export.read_bytes()
        result, summary = reconcile(load(args.template.read_bytes()), load(raw), load(args.review.read_bytes()),
                                    hashlib.sha256(raw).hexdigest())
        if args.review_output is not None:
            write_private(args.review_output, summary)
        if result is not None:
            write_private(args.output, result)
        print(json.dumps({**summary, "reviewRequired": len(summary["reviewRequired"]),
                          "outputWritten": result is not None}))
        return 0 if result is not None else 2
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        parser.exit(2, "Could not reconcile the period. Check input format, ownership and review coverage.\n")


if __name__ == "__main__":
    raise SystemExit(main())
