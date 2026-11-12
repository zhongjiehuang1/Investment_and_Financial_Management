#!/usr/bin/env python3
"""Read-only source audit. Produces an anonymised summary and an owner-private pairing plan.

No network requests, source edits, or database writes occur. Never infer ownership from
date/amount alone. PDF account headers establish ownership; counterpart references or
the owner's name on both sides establish transfer intent. Ambiguous pairs stay for review.
Requires pypdf only for statement verification; all money comparisons use Decimal.
"""
import argparse
import collections
import csv
import dataclasses
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from decimal import Decimal


def module(path):
    spec = importlib.util.spec_from_file_location("audit_" + path.parent.name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = result
    spec.loader.exec_module(result)
    return result


def compact(text):
    return re.sub(r"[^a-z0-9]", "", text.lower())


def load_sources(root):
    from pypdf import PdfReader
    rows, profiles, identities, source_files = [], {}, {}, []
    for directory in ("1", "5", "6", "7"):
        folder = root / directory
        importer = module(next(folder.glob("import_*.py")))
        transactions = []
        texts = {}
        owner_names, account_numbers = set(), set()
        if directory == "1":
            for source in sorted(folder.glob("*.xls")):
                source_files.append(source)
                for transaction in importer.extract_transactions(importer.read_xls_rows(source), importer.DEFAULT_PLATFORM):
                    item = dataclasses.asdict(transaction)
                    item["source_id"] = source.name + ":" + str(transaction.source_row)
                    transactions.append(item)
        else:
            transactions = [dataclasses.asdict(row) for row in importer.load_embedded_transactions()]
            for source in sorted(folder.glob("*.pdf")):
                source_files.append(source)
                text = "\n".join(page.extract_text() for page in PdfReader(source).pages)
                texts[source.name] = text
                headers = re.findall(r"Mr\s+([A-Za-z]+)\s+([A-Za-z]+)\s+\d{2}-\d{2}-\d{2}\s+(\d{8})", text, re.I)
                for first, last, number in headers:
                    owner_names.add((first.lower(), last.lower()))
                    account_numbers.add(number)
                account_numbers.update(re.findall(r"Account\s*Number\s*(\d{8})", text, re.I))
        identities[directory] = {"names": owner_names, "numbers": account_numbers, "header_text": "\n".join(texts.values())}
        currency = "CNY" if directory == "1" else "GBP"
        local = []
        for transaction in transactions:
            item = {key: transaction[key] for key in ("date", "type", "name", "amount", "source_id")}
            item.update(account_id="A" + directory, source=directory, platform=getattr(importer, "DEFAULT_PLATFORM"), currency=currency)
            amount = Decimal(item["amount"])
            if amount < 0 or amount != amount.quantize(Decimal("0.01")):
                raise ValueError("Invalid source amount")
            item["amount"] = format(amount, ".2f")
            item["id"] = hashlib.sha256((directory + ":" + item["source_id"]).encode()).hexdigest()[:16]
            if directory != "1":
                statement = transaction.get("statement_file")
                if directory == "7":
                    matching = [name for name in texts if re.search(r" - " + str(transaction["statement"]) + r"\.pdf$", name)]
                    statement = matching[0] if len(matching) == 1 else None
                text = texts.get(statement, "")
                item["source_pdf_verified"] = compact(item["name"]) in compact(text) and item["amount"] in text.replace(",", "")
            else:
                item["source_pdf_verified"] = True  # Parsed directly from XLS, no cached intermediary.
            local.append(item)
        keys = collections.Counter((r["date"], r["type"], r["name"], r["amount"]) for r in local)
        csv_matches = None
        for source in folder.glob("*.csv"):
            with source.open(encoding="utf-8-sig") as handle:
                exported = list(csv.DictReader(handle))
            csv_matches = collections.Counter((r["date"], r["type"], r["name"], format(Decimal(r["amount"]), ".2f")) for r in exported) == keys
            if not csv_matches:
                raise ValueError("CSV and checksum-verified importer disagree")
        profiles[directory] = dict(account_id="A" + directory, currency=currency, rows=len(local), earliest=min(r["date"] for r in local), latest=max(r["date"] for r in local),
                income=sum(r["type"] == "income" for r in local), expense=sum(r["type"] == "expense" for r in local), zero_amount=sum(Decimal(r["amount"]) == 0 for r in local),
                repeated_composite_key_rows=sum(v for v in keys.values() if v > 1), repeated_composite_keys=sum(v > 1 for v in keys.values()),
                unique_source_ids=len({r["source_id"] for r in local}), statement_files=len(texts) if texts else len(list(folder.glob("*.xls"))), csv_matches_embedded=csv_matches,
                pdf_header_account_identities=len(account_numbers), checksum_verified=directory != "1", source_text_verified_rows=sum(r["source_pdf_verified"] for r in local))
        rows.extend(local)
    known_names = set().union(*(item["names"] for item in identities.values()))
    for identity in identities.values():
        header_text = compact(identity.pop("header_text"))
        identity["owner_confirmed"] = bool(identity["names"]) or any("mr" + first[0] + last in header_text for first, last in known_names)
        identity["names"] = known_names
    return rows, profiles, identities, source_files


def ownership_evidence(outgoing, incoming, identities):
    if any(re.search(r"\bfees?\b|\bcharges?\b|commission|手续费", row["name"], re.I) for row in (outgoing, incoming)):
        return None
    a, b = compact(outgoing["name"]), compact(incoming["name"])
    left, right = identities[outgoing["source"]], identities[incoming["source"]]
    if not left["owner_confirmed"] or not right["owner_confirmed"] or not outgoing["source_pdf_verified"] or not incoming["source_pdf_verified"]:
        return None
    if any(number in a for number in right["numbers"]) and any(number in b for number in left["numbers"]):
        return "reciprocal_account_references"
    def self_name(text):
        return any(first + last in text or last + first in text or last + first[0] + "transfer" in text for first, last in left["names"])
    if self_name(a) and self_name(b) and any(word in a + b for word in ("transfer", "tfr", "remittence")):
        return "reciprocal_owner_name_transfer_references"
    return None


def pair_rows(rows, identities):
    incoming = collections.defaultdict(list)
    for row in rows:
        if row["type"] == "income" and Decimal(row["amount"]) > 0:
            incoming[row["date"], row["currency"], row["amount"]].append(row)
    matches, candidates, adjacency = [], [], collections.defaultdict(list)
    for outgoing in rows:
        if outgoing["type"] != "expense" or Decimal(outgoing["amount"]) <= 0:
            continue
        for other in incoming[outgoing["date"], outgoing["currency"], outgoing["amount"]]:
            if outgoing["account_id"] == other["account_id"]:
                continue
            evidence = ownership_evidence(outgoing, other, identities)
            pair = dict(outgoing=outgoing, incoming=other, evidence=evidence)
            candidates.append(pair)
            if evidence:
                adjacency[outgoing["id"]].append(pair)
                adjacency[other["id"]].append(pair)
    for candidate in candidates:
        if candidate["evidence"] and len(adjacency[candidate["outgoing"]["id"]]) == 1 and len(adjacency[candidate["incoming"]["id"]]) == 1:
            matches.append(candidate)
    matched = {r["id"] for pair in matches for r in (pair["outgoing"], pair["incoming"])}
    review = [dict(outgoing_id=c["outgoing"]["id"], incoming_id=c["incoming"]["id"], reason="ambiguous" if c["evidence"] else "ownership_or_transfer_intent_unproven") for c in candidates if not (c["outgoing"]["id"] in matched and c["incoming"]["id"] in matched)]
    return matches, review


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--live", type=Path, help="Optional owner-private JSONL read-only export for occurrence-level reconciliation")
    args = parser.parse_args()
    rows, profiles, identities, files = load_sources(args.source)
    matches, review = pair_rows(rows, identities)
    paired_ids = {row["id"] for pair in matches for row in (pair["outgoing"], pair["incoming"])}
    unmatched_intent = [row for row in rows if row["id"] not in paired_ids and re.search(r"\btransfer\b|\btfr\b|转账|汇款", row["name"], re.I)]
    summary = dict(schema_version=1, grain="one source bank statement transaction", sources=profiles, total_rows=len(rows),
        auto_verified_pairs=len(matches), matched_evidence=dict(collections.Counter(m["evidence"] for m in matches)), review_candidates=len(review),
        unpaired_transfer_description_rows=len(unmatched_intent),
        paired_account_counts=dict(collections.Counter(m["outgoing"]["account_id"] + ":" + m["incoming"]["account_id"] for m in matches)),
        rules=["Different owned accounts, same booking day and currency, exactly equal positive amounts, reciprocal ownership evidence, unique pairing on both sides.",
               "Equal date/amount alone never suffices; fees remain external expense; cross-currency historical candidates require explicit documentary link and are not auto-paired.",
               "Repeated composite keys are retained, not assumed duplicate. Existing live data must match source exactly before reclassification; account balances are never replayed."],
        impact="Verified internal pairs reduce reported income and expense by the same per-currency sum, leaving net cash flow and all account balances unchanged.",
        source_manifest=[dict(path=str(p.relative_to(args.source)), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in files])
    if args.live:
        live = [json.loads(line) for line in args.live.read_text().splitlines()]
        def key(row):
            return (row["date"], row["type"], row["platform"], row["name"], format(Decimal(row["amount"]), ".2f"), row["currency"])
        original, stored = collections.Counter(map(key, rows)), collections.Counter(map(key, live))
        summary["live_reconciliation"] = dict(live_rows=len(live), exact_source_occurrences_present=sum((original & stored).values()),
            missing_source_occurrences=sum((original - stored).values()), additional_live_occurrences_retained=sum((stored - original).values()),
            pairs_with_unique_live_legs=sum(all(stored[key(pair[side])] == 1 for side in ("outgoing", "incoming")) for pair in matches),
            earliest=min(row["date"] for row in live), latest=max(row["date"] for row in live),
            additional_live_by_account={profile["account_id"]:sum(row["platform"] == next(r["platform"] for r in rows if r["account_id"] == profile["account_id"]) for row in live) - profile["rows"] for profile in profiles.values()})
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "source-audit-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    plan = args.output / "private-transfer-plan.json"
    plan.write_text(json.dumps(dict(schema_version=1, pairs=matches, review=review, unpaired_transfer_intent=unmatched_intent), ensure_ascii=False, indent=2))
    os.chmod(plan, 0o600)
    print(json.dumps({key: value for key, value in summary.items() if key != "source_manifest"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
