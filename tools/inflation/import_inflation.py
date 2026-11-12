#!/usr/bin/env python3
"""Merge sourced monthly CPI observations; read-only unless --apply --maintenance is explicit.

Run CreateAllTables and SetIds under deployment maintenance before using --apply.
Keep incompatible old writers stopped. Data, generation, recycle-pool and allocator
changes commit together; this importer never performs schema migrations.
"""
import argparse
import csv
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import uuid
from decimal import Decimal, InvalidOperation
from urllib.parse import urlparse

TABLE = "ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES"
RECORDER = "ID_RECORDER_" + TABLE
MAX_ID = 2147483647
SOURCES = dict(zip("USD GBP CNY EUR JPY HKD AUD CAD CHF SGD".split(), "united-states united-kingdom china euro-area japan hong-kong australia canada switzerland singapore".split()))


def month(value):
    if isinstance(value, dt.datetime):
        value = value.date()
    if not isinstance(value, dt.date):
        value = dt.date.fromisoformat(str(value))
    return value.replace(day=15)


def next_month(value):
    return dt.date(value.year + (value.month == 12), value.month % 12 + 1, 15)


def exact_rate(value):
    try:
        parsed = Decimal(str(value))
        if not parsed.is_finite() or abs(parsed) > Decimal("999.99"):
            raise ValueError("Rate is outside DECIMAL(5,2) capacity")
        rounded = parsed.quantize(Decimal("0.01"))
        if parsed != rounded:
            raise ValueError("Rate has more than two decimal places")
        return rounded
    except InvalidOperation as error:
        raise ValueError("Invalid decimal inflation rate") from error


def load_csv(path):
    observations = {}
    with Path(path).open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            currency = row["currency"]
            expected_source = f"https://tradingeconomics.com/{SOURCES.get(currency, '')}/inflation-cpi"
            expected_measure = "headline_hicp_yoy" if currency == "EUR" else "headline_cpi_yoy"
            if currency not in SOURCES or row.get("source") != "Trading Economics" or row.get("source_url") != expected_source:
                raise ValueError("Only the documented Trading Economics currency sources are accepted")
            if row.get("frequency") != "monthly" or row.get("measure") != expected_measure:
                raise ValueError("Expected monthly headline year-on-year inflation")
            key = (currency, month(row["date"]))
            if key[1] > dt.date.today():
                raise ValueError("Future observations and forecasts are not accepted")
            value = exact_rate(row["rate"])
            if key in observations:
                raise ValueError(f"Duplicate source month: {currency} {key[1]}")
            observations[key] = value
    if not observations:
        raise ValueError("The source CSV is empty")
    return observations


def expand_existing(rows, source=None, conflicts=None):
    monthly = {}
    observed_rates = {}
    for row in rows:
        currency = row["VARIOUS_CURRENCIES"]
        if currency not in SOURCES:
            raise ValueError(f"Unrecognized existing currency {currency}; no rows were changed")
        start = month(row["DATE"])
        through = month(row.get("OBSERVED_THROUGH") or row["DATE"])
        if through < start or (through.year - start.year) > 200:
            raise ValueError("Invalid existing inflation interval")
        value = exact_rate(row["AIR_IN_VARIOUS_CURRENCIES"])
        cursor = start
        while cursor <= through:
            key = (currency, cursor)
            observed_rates.setdefault(key, set()).add(value)
            if len(observed_rates[key]) > 1:
                if source is None or key not in source:
                    raise ValueError(f"Conflicting existing intervals at {currency} {cursor}; no verified source covers this month")
                monthly[key] = source[key]
            else:
                monthly[key] = value
            cursor = next_month(cursor)
    if conflicts is not None:
        conflicts.extend({"currency": key[0], "date": str(key[1]), "old_rates": [str(value) for value in sorted(values)],
                          "source_rate": str(source[key])} for key, values in sorted(observed_rates.items()) if len(values) > 1)
    return monthly


def compact(monthly):
    spans = []
    for (currency, date), value in sorted(monthly.items()):
        previous = spans[-1] if spans else None
        if previous and previous["VARIOUS_CURRENCIES"] == currency and previous["AIR_IN_VARIOUS_CURRENCIES"] == value and next_month(previous["OBSERVED_THROUGH"]) == date:
            previous["OBSERVED_THROUGH"] = date
        else:
            spans.append({"VARIOUS_CURRENCIES": currency, "DATE": date, "OBSERVED_THROUGH": date, "AIR_IN_VARIOUS_CURRENCIES": value})
    return spans


def plan(rows, source):
    existing_conflicts = []
    existing = expand_existing(rows, source, existing_conflicts)
    conflicts = [{"currency": key[0], "date": str(key[1]), "old_rate": str(existing[key]), "source_rate": str(value)} for key, value in sorted(source.items()) if key in existing and existing[key] != value]
    combined = {**existing, **source}
    spans = compact(combined)
    coverage = []
    for currency in SOURCES:
        supplied = sorted(key[1] for key in source if key[0] == currency)
        merged = sorted(key[1] for key in combined if key[0] == currency)
        if merged:
            coverage.append({"currency": currency, "source_from": str(supplied[0]) if supplied else None,
                             "source_through": str(supplied[-1]) if supplied else None, "source_months": len(supplied),
                             "retained_existing_outside_source": sum(1 for key in existing if key[0] == currency and key not in source),
                             "final_from": str(merged[0]), "final_through": str(merged[-1]),
                             "final_spans": sum(s["VARIOUS_CURRENCIES"] == currency for s in spans)})
    report = {"existing_rows": len(rows), "existing_known_months": len(existing), "source_observations": len(source),
              "new_months": sum(key not in existing for key in source), "conflicts": conflicts,
              "existing_conflicts_resolved_by_source": existing_conflicts,
              "final_known_months": len(combined), "final_rows": len(spans),
              "collapsed_known_months": len(combined) - len(spans), "coverage": coverage}
    return spans, report


def schema(cursor):
    cursor.execute(f"SHOW COLUMNS FROM {TABLE}")
    columns = [row["Field"] for row in cursor.fetchall()]
    cursor.execute(f"SHOW INDEX FROM {TABLE}")
    indexes = cursor.fetchall()
    return columns, any(row["Key_name"].upper() == "UQ_INFLATION_CURRENCY_DATE" for row in indexes)


def read_rows(cursor, columns, locking=False):
    selected = "ID,AIR_IN_VARIOUS_CURRENCIES,VARIOUS_CURRENCIES,DATE"
    if "OBSERVED_THROUGH" in columns:
        selected += ",OBSERVED_THROUGH"
    if "GENERATION" in columns:
        selected += ",GENERATION"
    cursor.execute(f"SELECT {selected} FROM {TABLE} ORDER BY VARIOUS_CURRENCIES,DATE,ID" + (" FOR UPDATE" if locking else ""))
    return list(cursor.fetchall())


def backup(path, rows, recorder, allocation_state, ranges, source_hash, columns):
    path.mkdir(parents=True, exist_ok=True)
    filename = path / ("inflation-before-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8] + ".json")
    data = {"schema_version": 2, "table": TABLE, "columns": columns, "rows": rows,
            "id_recorder": recorder, "id_allocation_state": allocation_state,
            "id_recovery_ranges": ranges, "source_csv_sha256": source_hash}
    serialized = json.dumps(data, indent=2, default=str)
    descriptor = os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(serialized)
        handle.flush()
        os.fsync(handle.fileno())
    if filename.read_text(encoding="utf-8") != serialized:
        raise OSError("Inflation backup verification failed; no rows were changed")
    return filename


def changes(rows, spans):
    """Retain an existing start's identity; compaction removes only superseded starts."""
    existing = {}
    identifiers, generations = set(), set()
    for row in sorted(rows, key=lambda value: (value["DATE"], value["ID"])):
        identifier, generation = row["ID"], str(row.get("GENERATION", ""))
        if identifier <= 0 or identifier > MAX_ID or identifier in identifiers:
            raise ValueError("Invalid existing inflation ID; maintenance repair is required")
        try:
            canonical = str(uuid.UUID(generation))
        except (ValueError, AttributeError) as invalid:
            raise ValueError("Inflation generation migration is required before applying") from invalid
        if canonical.lower() != generation.lower() or canonical in generations:
            raise ValueError("Invalid existing inflation generation")
        identifiers.add(identifier)
        generations.add(canonical)
        existing.setdefault((row["VARIOUS_CURRENCIES"], month(row["DATE"])), row)
    retained, inserted, keep_ids = [], [], set()
    for span in spans:
        old = existing.get((span["VARIOUS_CURRENCIES"], span["DATE"]))
        if old is None:
            inserted.append(dict(span))
        else:
            keep_ids.add(old["ID"])
            if (old["DATE"] != span["DATE"] or old.get("OBSERVED_THROUGH") != span["OBSERVED_THROUGH"]
                    or exact_rate(old["AIR_IN_VARIOUS_CURRENCIES"]) != span["AIR_IN_VARIOUS_CURRENCIES"]):
                retained.append({**span, "ID": old["ID"], "GENERATION": old["GENERATION"]})
    removed = [row for row in rows if row["ID"] not in keep_ids]
    return removed, retained, inserted


def references(cursor):
    cursor.execute("SELECT TABLE_NAME,COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                   "WHERE REFERENCED_TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME=%s AND REFERENCED_COLUMN_NAME='ID'", (TABLE,))
    result = []
    for row in cursor.fetchall():
        table, column = row["TABLE_NAME"], row["COLUMN_NAME"]
        if not all(value.replace("_", "").isalnum() for value in (table, column)):
            raise ValueError("Unsupported inflation reference identifier")
        result.append((table, column))
    return result


def unavailable(cursor, identifier, links):
    cursor.execute(f"SELECT ID FROM {TABLE} WHERE ID=%s", (identifier,))
    if cursor.fetchone():
        return True
    for table, column in links:
        cursor.execute(f"SELECT `{column}` FROM `{table}` WHERE `{column}`=%s LIMIT 1", (identifier,))
        if cursor.fetchone():
            return True
    return False


def remember_high_water(cursor, identifier):
    cursor.execute("UPDATE ID_ALLOCATION_STATE SET HIGH_WATER=GREATEST(HIGH_WATER,%s) WHERE TABLE_NAME=%s", (identifier, TABLE))


def recycle_id(cursor, identifier, links):
    if not 0 < identifier <= MAX_ID or unavailable(cursor, identifier, links):
        raise ValueError("A live or referenced inflation ID cannot be recycled")
    remember_high_water(cursor, identifier)
    cursor.execute("SELECT FIRST_ID FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=%s AND FIRST_ID<=%s AND LAST_ID>=%s", (TABLE, identifier, identifier))
    if not cursor.fetchone():
        cursor.execute(f"INSERT INTO {RECORDER}(TABLE_ID) VALUES (%s) ON DUPLICATE KEY UPDATE TABLE_ID=TABLE_ID", (identifier,))


def claim_id(cursor, links):
    """Caller owns both coordination locks and must insert before claiming another ID."""
    while True:
        cursor.execute(f"SELECT MIN(TABLE_ID) AS ID FROM {RECORDER} WHERE TABLE_ID>0")
        point = cursor.fetchone()["ID"]
        cursor.execute("SELECT FIRST_ID,LAST_ID FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=%s ORDER BY FIRST_ID LIMIT 1", (TABLE,))
        interval = cursor.fetchone()
        if point is None and interval is None:
            break
        first = interval["FIRST_ID"] if interval else None
        identifier = min(value for value in (point, first) if value is not None)
        if not 0 < identifier <= MAX_ID or (interval and not first <= interval["LAST_ID"] <= MAX_ID):
            raise ValueError("Invalid recovered inflation ID interval")
        cursor.execute(f"DELETE FROM {RECORDER} WHERE TABLE_ID=%s", (identifier,))
        if first == identifier:
            if first == interval["LAST_ID"]:
                cursor.execute("DELETE FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=%s AND FIRST_ID=%s", (TABLE, first))
            else:
                cursor.execute("UPDATE ID_RECOVERY_RANGES SET FIRST_ID=%s WHERE TABLE_NAME=%s AND FIRST_ID=%s", (first + 1, TABLE, first))
        if not unavailable(cursor, identifier, links):
            remember_high_water(cursor, identifier)
            return identifier
    cursor.execute(f"SELECT COALESCE(MAX(ID),0) AS MAXIMUM FROM {TABLE}")
    maximum = cursor.fetchone()["MAXIMUM"]
    if maximum < 0 or maximum >= MAX_ID:
        raise ValueError("The integer ID range is exhausted")
    identifier = maximum + 1
    if unavailable(cursor, identifier, links):
        raise ValueError("The next inflation ID has surviving references; repair is required")
    remember_high_water(cursor, identifier)
    return identifier


def apply(cursor, rows, spans, links):
    """Apply only the row differences within the caller's locked transaction."""
    removed, updated, inserted = changes(rows, spans)
    if not removed and not updated and not inserted:
        return False
    for row in removed:
        # Referenced rows must not trigger an implicit cascading deletion in an unfamiliar schema.
        for table, column in links:
            cursor.execute(f"SELECT `{column}` FROM `{table}` WHERE `{column}`=%s LIMIT 1", (row["ID"],))
            if cursor.fetchone():
                raise ValueError("A referenced inflation interval cannot be removed")
        cursor.execute(f"DELETE FROM {TABLE} WHERE ID=%s AND GENERATION=%s", (row["ID"], row["GENERATION"]))
        if cursor.rowcount != 1:
            raise ValueError("Inflation row changed during import")
        recycle_id(cursor, row["ID"], links)
    for row in updated:
        cursor.execute(f"UPDATE {TABLE} SET AIR_IN_VARIOUS_CURRENCIES=%s,VARIOUS_CURRENCIES=%s,DATE=%s,OBSERVED_THROUGH=%s WHERE ID=%s AND GENERATION=%s",
                       (row["AIR_IN_VARIOUS_CURRENCIES"], row["VARIOUS_CURRENCIES"], row["DATE"], row["OBSERVED_THROUGH"], row["ID"], row["GENERATION"]))
        if cursor.rowcount != 1:
            raise ValueError("Inflation row changed during import")
    for row in inserted:
        identifier = claim_id(cursor, links)
        cursor.execute(f"INSERT INTO {TABLE}(ID,AIR_IN_VARIOUS_CURRENCIES,VARIOUS_CURRENCIES,DATE,OBSERVED_THROUGH,GENERATION) VALUES (%s,%s,%s,%s,%s,%s)",
                       (identifier, row["AIR_IN_VARIOUS_CURRENCIES"], row["VARIOUS_CURRENCIES"], row["DATE"], row["OBSERVED_THROUGH"], str(uuid.uuid4())))
        if cursor.rowcount != 1:
            raise ValueError("New inflation row was not inserted")
    return True


def import_observations(connection, source, source_hash, apply_changes=False, backup_dir=None, maintenance=False):
    """Own the complete transaction; a failed backup, mutation or verification rolls everything back."""
    if apply_changes and (not maintenance or backup_dir is None):
        raise ValueError("Apply requires maintenance mode and a private backup directory")
    backup_file = None
    try:
        connection.begin()
        with connection.cursor() as cursor:
            columns, has_unique = schema(cursor)
            if apply_changes:
                if not {"OBSERVED_THROUGH", "GENERATION"}.issubset(columns) or not has_unique:
                    raise ValueError("Run the updated schema migration and SetIds before applying inflation data")
                # Match the Java service: series lock first, then the allocator lock, through commit.
                cursor.execute("SELECT ID FROM INFLATION_SERIES_LOCK WHERE ID=1 FOR UPDATE")
                if not cursor.fetchone():
                    raise ValueError("Inflation lock marker is unavailable")
                cursor.execute("SELECT TABLE_NAME,HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME=%s FOR UPDATE", (TABLE,))
                allocation_state = cursor.fetchone()
                if allocation_state is None or not 0 <= allocation_state["HIGH_WATER"] <= MAX_ID:
                    raise ValueError("Inflation allocation migration is required")
                cursor.execute(f"SELECT TABLE_ID FROM {RECORDER} ORDER BY TABLE_ID")
                recorder = list(cursor.fetchall())
                if any(row["TABLE_ID"] <= 0 for row in recorder):
                    raise ValueError("Run SetIds to migrate legacy counters before applying inflation data")
                cursor.execute("SELECT TABLE_NAME,FIRST_ID,LAST_ID FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=%s ORDER BY FIRST_ID", (TABLE,))
                ranges = list(cursor.fetchall())
            rows = read_rows(cursor, columns, locking=apply_changes)
            spans, report = plan(rows, source)
            report.update({"mode": "apply" if apply_changes else "dry-run", "source_csv_sha256": source_hash,
                           "schema_migration_required": not {"OBSERVED_THROUGH", "GENERATION"}.issubset(columns) or not has_unique})
            if not apply_changes:
                connection.rollback()
                return report, {"status": "dry-run"}
            # Back up the locked state, including generations and all allocator metadata, before any DML.
            changes(rows, spans)
            backup_file = backup(Path(backup_dir), rows, recorder, allocation_state, ranges, source_hash, columns)
            changed = apply(cursor, rows, spans, references(cursor))
            verified = read_rows(cursor, columns)
            if expand_existing(verified) != {**expand_existing(rows, source), **source}:
                raise ValueError("Post-write monthly read-back differs; transaction will be rolled back")
            if len(verified) != len(spans) or len({row["GENERATION"] for row in verified}) != len(verified):
                raise ValueError("Post-write inflation identities differ; transaction will be rolled back")
            cursor.execute(f"SELECT A.ID FROM {TABLE} A JOIN {RECORDER} R ON R.TABLE_ID=A.ID LIMIT 1")
            if cursor.fetchone():
                raise ValueError("An occupied inflation ID is incorrectly marked reusable")
            cursor.execute(f"SELECT A.ID FROM {TABLE} A JOIN ID_RECOVERY_RANGES R ON R.TABLE_NAME=%s AND A.ID BETWEEN R.FIRST_ID AND R.LAST_ID LIMIT 1", (TABLE,))
            if cursor.fetchone():
                raise ValueError("An occupied inflation ID remains in a recovery interval")
            connection.commit()
            return report, {"status": "committed" if changed else "unchanged", "rows": len(verified), "backup": str(backup_file),
                            "conflicts_applied": len(report["conflicts"]), "existing_conflicts_resolved": len(report["existing_conflicts_resolved_by_source"])}
    except Exception:
        connection.rollback()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=Path(__file__).parent / "data" / "inflation_monthly_raw.csv")
    parser.add_argument("--apply", action="store_true", help="Back up and apply inflation changes in one transaction; requires the updated schema")
    parser.add_argument("--maintenance", action="store_true", help="Confirm incompatible old writers are stopped and the updated schema/SetIds have run")
    parser.add_argument("--backup-dir", type=Path, default=Path.cwd() / "inflation-backups")
    parser.add_argument("--db-user", default=os.environ.get("IAFM_DB_USER", "root"))
    parser.add_argument("--database", help="Database name; otherwise use IAFM_DB_URL or IAFM")
    parser.add_argument("--socket", help="Connect using this local MySQL Unix socket")
    parser.add_argument("--prompt-password", action="store_true", help="Prompt securely even if an environment password is set")
    args = parser.parse_args()
    source = load_csv(args.csv)
    source_hash = hashlib.sha256(args.csv.read_bytes()).hexdigest()
    import pymysql
    url = urlparse(os.environ.get("IAFM_DB_URL", "jdbc:mysql://localhost:3306/IAFM").removeprefix("jdbc:"))
    if url.scheme != "mysql" or url.username or url.password or not args.db_user:
        raise ValueError("Provide a MySQL URL without credentials and a maintenance database user")
    password = None if args.prompt_password else os.environ.get("IAFM_MIGRATION_PASSWORD", os.environ.get("IAFM_DB_PASSWORD"))
    if password is None:
        password = getpass.getpass("Database password: ")
    connection = pymysql.connect(host=url.hostname or "localhost", port=url.port or 3306, database=args.database or url.path.lstrip("/") or "IAFM",
                                 unix_socket=args.socket, user=args.db_user, password=password,
                                 charset="utf8mb4", autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    try:
        report, result = import_observations(connection, source, source_hash, args.apply, args.backup_dir, args.maintenance)
        print(json.dumps(report, indent=2))
        print(json.dumps(result, indent=2))
    finally:
        connection.close()


if __name__ == "__main__":
    main()
