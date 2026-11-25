#!/usr/bin/env python3
"""Validate captured public chart text and rebuild the normalized CPI dataset."""
import csv
import datetime as dt
import hashlib
import json
import re
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ACCESSED_AT = "2026-10-17"
MONTHS = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split()
SOURCES = {
    "USD": ("united-states", "United States", "U.S. Bureau of Labor Statistics"),
    "GBP": ("united-kingdom", "United Kingdom", "Office for National Statistics"),
    "CNY": ("china", "China", "National Bureau of Statistics of China"),
    "EUR": ("euro-area", "Euro Area", "Eurostat"),
    "JPY": ("japan", "Japan", "Ministry of Internal Affairs and Communications"),
    "HKD": ("hong-kong", "Hong Kong", "Census and Statistics Department, Hong Kong"),
    "AUD": ("australia", "Australia", "Australian Bureau of Statistics"),
    "CAD": ("canada", "Canada", "Statistics Canada"),
    "CHF": ("switzerland", "Switzerland", "Swiss Federal Statistical Office"),
    "SGD": ("singapore", "Singapore", "Statistics Singapore"),
}
FIELDS = ["currency", "date", "rate", "source", "source_url", "frequency", "measure", "accessed_at"]


def month_index(date):
    return int(date[:4]) * 12 + int(date[5:7]) - 1


def month_date(index):
    return f"{index // 12:04d}-{index % 12 + 1:02d}-15"


def parse_tooltip(value):
    found = re.fullmatch(r"([A-Z][a-z]{2}) ([0-9]{4})\s+(-?[0-9]+(?:\.[0-9]+)?)%", value.strip())
    if found is None or found[1] not in MONTHS:
        raise ValueError(f"Malformed displayed tooltip: {value!r}")
    date = f"{found[2]}-{MONTHS.index(found[1]) + 1:02d}-15"
    dt.date.fromisoformat(date)
    rate = Decimal(found[3])
    if not rate.is_finite() or rate <= -100 or rate >= 100:
        raise ValueError(f"Unexpected inflation rate: {rate}")
    return date, format(rate, ".2f")


def validate_recent(code, captured, monthly):
    """Compare independently captured 1Y labels to precise 10Y tooltips."""
    if captured.get("points"):
        axes = captured["axis"]
        year_positions = [i for i, item in enumerate(axes) if re.fullmatch(r"[0-9]{4}", item["label"])]
        assert len(year_positions) == 1, (code, "ambiguous year axis")
        anchor = year_positions[0]
        year = int(axes[anchor]["label"])
        seen = set()
        for point in captured["points"]:
            nearest = min(range(len(axes)), key=lambda i: abs(axes[i]["x"] - point["x"]))
            assert abs(axes[nearest]["x"] - point["x"]) < 2, (code, "ambiguous point alignment")
            assert nearest not in seen, (code, "two data points at the same month")
            seen.add(nearest)
            date = month_date(year * 12 + nearest - anchor)
            assert Decimal(monthly[date]) == Decimal(point["value"]), (code, date, "1Y/10Y mismatch")
    if captured.get("tooltipRecords"):
        for tooltip in captured["tooltipRecords"]:
            date, rate = parse_tooltip(tooltip)
            assert monthly[date] == rate, (code, date, "1Y/10Y tooltip mismatch")


def write_csv(name, rows, fields=FIELDS):
    with (ROOT / name).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main():
    raw_path = ROOT / "raw" / "visible-chart-evidence.json"
    evidence = json.loads(raw_path.read_text(encoding="utf-8"))
    assert set(evidence) == set(SOURCES), "The ten requested currencies are required"
    all_rows, changes, coverage = [], [], []
    for currency, (slug, country, publisher) in SOURCES.items():
        captured = evidence[currency]
        parsed = [parse_tooltip(item) for item in captured["history"]]
        assert parsed == sorted(parsed), (currency, "nonchronological capture")
        monthly = dict(parsed)
        assert len(monthly) == len(parsed), (currency, "duplicate reference month")
        assert all(date <= ACCESSED_AT for date in monthly), (currency, "future observation")
        validate_recent(currency, captured, monthly)
        expected = set(range(month_index(parsed[0][0]), month_index(parsed[-1][0]) + 1))
        missing = sorted(month_date(i) for i in expected - {month_index(date) for date in monthly})
        assert missing == (["2025-10-15"] if currency == "USD" else []), (currency, "unexpected gap", missing)
        url = f"https://tradingeconomics.com/{slug}/inflation-cpi"
        rows = [dict(zip(FIELDS, [currency, date, rate, "Trading Economics", url, "monthly", "headline_hicp_yoy" if currency == "EUR" else "headline_cpi_yoy", ACCESSED_AT])) for date, rate in parsed]
        compact, previous = [], None
        for row in rows:
            same_run = previous is not None and row["rate"] == previous["rate"] and month_index(row["date"]) == month_index(previous["date"]) + 1
            if same_run:
                compact[-1]["observed_through"] = row["date"]
            else:
                compact.append({**row, "observed_through": row["date"]})
            previous = row
        all_rows.extend(rows)
        changes.extend(compact)
        coverage.append({
            "currency": currency, "country": country, "source_url": url,
            "upstream_publisher_as_named_by_te": publisher,
            "frequency": "monthly", "measure": rows[0]["measure"],
            "reference_month_start": rows[0]["date"], "reference_month_end": rows[-1]["date"],
            "raw_observations": len(rows), "change_points": len(compact),
            "missing_reference_months": missing,
            "access_method": "Anonymous public 10Y chart; exact visible point tooltip text",
            "full_history_complete": False,
            "limitation": "MAX history requires a Trading Economics subscription; no protected history was accessed.",
        })
    write_csv("inflation_monthly_raw.csv", all_rows)
    write_csv("inflation_change_points.csv", changes, FIELDS + ["observed_through"])
    result = {
        "accessed_at": ACCESSED_AT,
        "date_semantics": "Reference month represented on its 15th day; not the actual release date",
        "rate_semantics": "Headline year-on-year inflation in percentage points, not MoM, index level, forecast or core inflation",
        "deduplication": "Only consecutive calendar months with exactly equal observed rates are collapsed; keep the earliest month. Missing months break a run.",
        "raw_evidence_sha256": hashlib.sha256(raw_path.read_bytes()).hexdigest(),
        "total_raw_observations": len(all_rows), "total_change_points": len(changes),
        "currencies": coverage,
    }
    (ROOT / "coverage.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"raw": len(all_rows), "change_points": len(changes), "coverage": [{k: r[k] for k in ("currency", "reference_month_start", "reference_month_end", "raw_observations", "change_points", "missing_reference_months")} for r in coverage]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
