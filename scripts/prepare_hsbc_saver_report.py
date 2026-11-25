#!/usr/bin/env python3
"""Prepare an offline period-report input from contiguous HSBC Online Bonus Saver PDFs."""

import argparse
from datetime import datetime
from decimal import Decimal
import hashlib
import re
from pathlib import Path

from reconcile_period_performance import identity, load, write_private

AMOUNT = re.compile(r"(?<![.\d])\d[\d,]*\.\d{2}(?!\d)")
DATED = re.compile(r"^(\d{1,2} [A-Z][a-z]{2} \d{2})\s+(.*)$")


def amounts(line):
    return [Decimal(token.replace(",", "")) for token in AMOUNT.findall(line)]


def date(text):
    return datetime.strptime(text, "%d %b %y").date().isoformat()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def reconcile_statement(pages):
    text = "\n".join(pages)
    require(re.search(r"Online Bonus Saver", text, re.I), "Unexpected statement product.")
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    accounts = {m.groups() for line in lines if (m := re.search(r"(\d{2}-\d{2}-\d{2})\s+(\d{8})\s+\d+\s*$", line))}
    require(len(accounts) == 1, "A unique statement account could not be verified.")
    starts = [i for i, line in enumerate(lines) if "BALANCE BROUGHT FORWARD" in line]
    ends = [i for i, line in enumerate(lines) if "BALANCE CARRIED FORWARD" in line]
    require(len(starts) == len(ends) == 1 and starts[0] < ends[0], "Unsupported statement layout.")
    first, last = starts[0], ends[0]
    opening = amounts(lines[first])[-1]
    closing = amounts(lines[last])[-1]
    start = date(DATED.fullmatch(lines[first])[1])
    end = date(DATED.fullmatch(lines[last])[1])
    closing_summary = [line for line in lines if re.match(r"^Closing Balance\b", line)]
    require(len(closing_summary) == 1 and amounts(closing_summary[0]) == [closing],
            "Closing balance differs from the summary.")
    current, day = opening, start
    entries = []
    index = first + 1
    while index < last:
        match = DATED.fullmatch(lines[index])
        require(match is not None, "Unexpected posting.")
        previous = day
        day, description = date(match[1]), match[2]
        require(start < day <= end and previous <= day, "Invalid posting date.")
        index += 1
        require(index < last, "Missing posting details.")
        detail = lines[index]
        values = amounts(detail)
        index += 1
        if description.startswith("TFR ") and detail.startswith("INTERNET TRANSFER"):
            require(len(values) == 2 and abs(values[1] - current) == values[0] and values[0] > 0,
                    "Transfer does not reconcile.")
            change, updated, kind = values[1] - current, values[1], "external"
        elif re.fullmatch(r"CR\s+GROSS INTEREST", description) and re.match(r"^TO \d{2}[A-Z]{3}\d{4}", detail):
            if len(values) == 1:
                require(index < last and re.match(r"^CR\s+ADDED GROSS INTEREST", lines[index]),
                        "Bonus interest details are missing.")
                bonus, updated = amounts(lines[index])
                change = values[0] + bonus
                index += 1
            else:
                require(len(values) == 2, "Unexpected interest fields.")
                change, updated = values
            require(current + change == updated, "Interest does not reconcile.")
            kind = "interest"
        else:
            raise ValueError("A posting needs manual reconciliation.")
        entries.append((day, kind, change, current))
        current = updated
    require(current == closing, "Running balance does not match the closing balance.")
    return start, end, opening, closing, entries, accounts.pop()


def build_report(statements, generation, scope_id, scope, account, cpi):
    require(bool(statements), "At least one statement is required.")
    require(bool(re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", scope_id)), "Invalid scope ID.")
    ordered = sorted((reconcile_statement(pages), digest) for pages, digest in statements)
    for (left, _), (right, _) in zip(ordered, ordered[1:]):
        require(left[1] == right[0] and left[3] == right[2], "Statements have a gap, overlap, or balance mismatch.")
    require(len({statement[5] for statement, _ in ordered}) == 1, "Statements belong to different accounts.")
    first, last = ordered[0][0], ordered[-1][0]
    start, end = first[0], last[1]

    def valuation(value):
        return [{"account": account, "currency": "GBP", "amount": f"{value:.2f}"}]

    by_day = {}
    for statement, _ in ordered:
        for day, kind, amount, before in statement[4]:
            by_day.setdefault(day, []).append((kind, amount, before))
    external = []
    snapshots = []
    for day, entries in sorted(by_day.items()):
        flows = [(i, amount, balance) for i, (kind, amount, balance) in enumerate(entries) if kind == "external"]
        if not flows:
            continue
        # The report uses end-of-day flows, so the day's interest must already be in the pre-flow value.
        require(all(entry[0] != "interest" for entry in entries[flows[0][0] + 1:]),
                "Interest after a same-day transfer needs a reviewed valuation.")
        for _, amount, _ in flows:
            external.append({"reference": f"statement-transfer-{len(external) + 1}",
                             "account": account, "currency": "GBP", "date": day, "amount": f"{amount:.2f}"})
        if day < end:
            snapshots.append({"date": day, "values": valuation(flows[0][2])})
    report = {"version": 1, "userGeneration": generation, "reportingCurrency": "GBP", "scopeId": scope_id,
              "scope": scope, "reconciliationReference": "HSBC Online Bonus Saver running balance",
              "startDate": start, "endDate": end, "openingValues": valuation(first[2]),
              "closingValues": valuation(last[3]), "externalFlows": external, "preFlowValuations": snapshots,
              "fxRates": [], "statementSha256": [digest for _, digest in ordered]}
    if cpi is not None:
        report["cpi"] = cpi
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("statements", nargs="+", type=Path)
    parser.add_argument("--user-generation", required=True)
    parser.add_argument("--account-generation", required=True)
    parser.add_argument("--scope-id", default="hsbc-online-bonus-saver")
    parser.add_argument("--scope", default="HSBC Online Bonus Saver")
    parser.add_argument("--cpi", type=Path, help="Optional official CPI observations in period input format.")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        import pymupdf
    except ImportError:
        parser.exit(2, "Install PyMuPDF in the local Python environment.\n")
    try:
        inputs = [*args.statements, *([args.cpi] if args.cpi else [])]
        require(args.output.resolve() not in {path.resolve() for path in inputs}, "Output must be separate from input.")
        documents = []
        for path in args.statements:
            content = path.read_bytes()
            with pymupdf.open(stream=content, filetype="pdf") as pdf:
                documents.append(([page.get_text(sort=True) for page in pdf], hashlib.sha256(content).hexdigest()))
        report = build_report(documents, identity(args.user_generation), args.scope_id, args.scope,
                              identity(args.account_generation), load(args.cpi.read_bytes()) if args.cpi else None)
        write_private(args.output, report)
        print(f"Prepared {len(documents)} contiguous statement(s) with {len(report['externalFlows'])} external flow(s).")
    except (OSError, ValueError, TypeError, IndexError) as failure:
        parser.exit(2, f"Could not prepare the report. {failure}\n")


if __name__ == "__main__":
    main()
