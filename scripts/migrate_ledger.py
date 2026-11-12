#!/usr/bin/env python3
"""Idempotent, owner-scoped ledger migration. Dry-run by default; never changes account balances.

Requires PyMySQL. Password is prompted (or IAFM_MIGRATION_PASSWORD environment variable).
The pairing plan must come from reconcile_internal_transfers.py and remain private.
Before --apply, also take the normal database backup. This script writes an additional
0600 JSON rollback snapshot before modifying any user transaction or journal.
"""
import argparse
import collections
import datetime
from decimal import Decimal
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import uuid


def money(value):
    parsed = Decimal(str(value))
    if not parsed.is_finite() or parsed < 0 or parsed != parsed.quantize(Decimal("0.01")):
        raise ValueError("Invalid amount in stored record")
    return format(parsed, ".2f")


def identity(row):
    return (str(row["DATE"]), row["PLATFORM"], row["NAME"], money(row["AMOUNT"]), row["CURRENCY"])


def source_identity(row):
    return (row["date"], row["platform"], row["name"], money(row["amount"]), row["currency"])


def column_metadata(cursor, table, column):
    cursor.execute("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,COLLATION_NAME,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,COLUMN_COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s AND COLUMN_NAME=%s", (table, column))
    return cursor.fetchone()


def character_definition(metadata):
    """Metadata supplies identifiers, not values: validate before interpolating DDL."""
    if not metadata or metadata["DATA_TYPE"].lower() != "varchar" or metadata["CHARACTER_MAXIMUM_LENGTH"] != 36:
        raise ValueError("Unexpected journal identifier column definition")
    charset, collation = metadata["CHARACTER_SET_NAME"], metadata["COLLATION_NAME"]
    if any(not isinstance(value, str) or re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", value) is None for value in (charset, collation)):
        raise ValueError("Invalid character set or collation identifier")
    return "VARCHAR(36) CHARACTER SET " + charset + " COLLATE " + collation


def align_transaction_journal_column(cursor, reference):
    definition = character_definition(reference)
    existing = column_metadata(cursor, "INCOME_AND_EXPENDITURE_DATA", "JOURNAL_ID")
    if existing is None:
        cursor.execute("ALTER TABLE INCOME_AND_EXPENDITURE_DATA ADD COLUMN JOURNAL_ID " + definition + " NULL DEFAULT NULL")
        return
    character_definition(existing)
    if (existing["CHARACTER_SET_NAME"], existing["COLLATION_NAME"]) == (reference["CHARACTER_SET_NAME"], reference["COLLATION_NAME"]):
        return
    # Only this migration's nullable identifier may be changed. Never rewrite old
    # transaction descriptions, table defaults, account data, or an unfamiliar schema.
    if existing["IS_NULLABLE"] != "YES" or existing["COLUMN_DEFAULT"] is not None or existing["EXTRA"] or existing["COLUMN_COMMENT"]:
        raise ValueError("Unexpected transaction journal column attributes; maintenance review required")
    cursor.execute("ALTER TABLE INCOME_AND_EXPENDITURE_DATA MODIFY COLUMN JOURNAL_ID " + definition + " NULL DEFAULT NULL")


def ensure_schema(cursor):
    cursor.execute("CREATE TABLE IF NOT EXISTS LEDGER_JOURNALS (ID VARCHAR(36) PRIMARY KEY, USER_ID INT NOT NULL, KIND VARCHAR(20) NOT NULL, DATE DATE NOT NULL, BOOK_CURRENCY VARCHAR(50) NOT NULL, ORIGIN VARCHAR(30) NOT NULL, EVIDENCE VARCHAR(255) NOT NULL, FOREIGN KEY(USER_ID) REFERENCES USERS(ID)) ENGINE=InnoDB")
    # Existing transaction tables can retain an older collation than the database
    # default. The authoritative join key is the actual journal PK, not either default.
    reference = column_metadata(cursor, "LEDGER_JOURNALS", "ID")
    definition = character_definition(reference)
    cursor.execute("CREATE TABLE IF NOT EXISTS LEDGER_ENTRIES (JOURNAL_ID " + definition + " NOT NULL, LINE_NUMBER INT NOT NULL, RECORD_ID INT, ACCOUNT_KIND VARCHAR(30) NOT NULL, ACCOUNT_NAME VARCHAR(150) NOT NULL, NATIVE_CURRENCY VARCHAR(50) NOT NULL, NATIVE_AMOUNT VARCHAR(15) NOT NULL, DEBIT_MINOR BIGINT NOT NULL, CREDIT_MINOR BIGINT NOT NULL, BALANCE_TYPE VARCHAR(50), PRIMARY KEY(JOURNAL_ID, LINE_NUMBER), FOREIGN KEY(JOURNAL_ID) REFERENCES LEDGER_JOURNALS(ID)) ENGINE=InnoDB")
    align_transaction_journal_column(cursor, reference)
    cursor.execute("SHOW INDEX FROM INCOME_AND_EXPENDITURE_DATA WHERE Key_name='IDX_IAFM_TRANSACTION_JOURNAL'")
    if not cursor.fetchone():
        cursor.execute("CREATE INDEX IDX_IAFM_TRANSACTION_JOURNAL ON INCOME_AND_EXPENDITURE_DATA (JOURNAL_ID, USER_ID)")


def journal(cursor, identifier, user, kind, date, currency, evidence):
    cursor.execute("INSERT INTO LEDGER_JOURNALS (ID,USER_ID,KIND,DATE,BOOK_CURRENCY,ORIGIN,EVIDENCE) VALUES (%s,%s,%s,%s,%s,'source_reconciliation',%s)",
                   (identifier, user, kind, date, currency, evidence))


def entry(cursor, identifier, line, row, debit, credit, kind="asset", balance_type=None):
    cursor.execute("INSERT INTO LEDGER_ENTRIES (JOURNAL_ID,LINE_NUMBER,RECORD_ID,ACCOUNT_KIND,ACCOUNT_NAME,NATIVE_CURRENCY,NATIVE_AMOUNT,DEBIT_MINOR,CREDIT_MINOR,BALANCE_TYPE) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                   (identifier, line, row["ID"] if kind == "asset" else None, kind, row["PLATFORM"] if kind == "asset" else kind,
                    row["CURRENCY"], money(row["AMOUNT"]), debit, credit, balance_type))


def attach(cursor, user, row, identifier, new_type):
    cursor.execute("UPDATE INCOME_AND_EXPENDITURE_DATA SET JOURNAL_ID=%s,TYPE=%s WHERE ID=%s AND USER_ID=%s AND JOURNAL_ID IS NULL AND TYPE=%s", (identifier, new_type, row["ID"], user, row["TYPE"]))
    if cursor.rowcount != 1:
        raise ValueError("Ledger changed during reconciliation")


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, default=str, sort_keys=True).encode()).hexdigest()


def migrate(connection, plan, user, expected_email, apply, backup):
    with connection.cursor() as cursor:
        if apply:
            ensure_schema(cursor)
            connection.commit()  # DDL is separate; all financial changes below are one transaction.
        connection.begin()
        cursor.execute("SELECT ID,EMAIL_ADDRESS FROM USERS WHERE ID=%s FOR UPDATE", (user,))
        owner = cursor.fetchone()
        if not owner or owner["EMAIL_ADDRESS"].casefold() != expected_email.casefold():
            raise ValueError("User identity did not match")
        cursor.execute("SELECT * FROM INCOME_AND_EXPENDITURE_DATA WHERE USER_ID=%s ORDER BY ID FOR UPDATE", (user,))
        rows = cursor.fetchall()
        lookup = collections.defaultdict(list)
        for row in rows:
            lookup[identity(row)].append(row)
        assets = []
        for table in ("ALL_NON_INVESTMENT_ASSETS", "ALL_INVESTMENT_ASSETS"):
            cursor.execute("SELECT * FROM " + table + " WHERE USER_ID=%s ORDER BY ID", (user,))
            assets.append(cursor.fetchall())
        names = collections.Counter((r.get("NAME", r.get("INVESTMENT_PLATFORM")), r["CURRENCY"]) for group in assets for r in group)
        resolved, used, already = [], set(), 0
        for pair in plan["pairs"]:
            if pair["evidence"] not in ("reciprocal_account_references", "reciprocal_owner_name_transfer_references"):
                raise ValueError("Unsupported evidence")
            source_out, source_in = pair["outgoing"], pair["incoming"]
            if source_out["date"] != source_in["date"] or source_out["currency"] != source_in["currency"] or money(source_out["amount"]) != money(source_in["amount"]) or source_out["platform"] == source_in["platform"]:
                raise ValueError("Historical pair does not balance")
            if any(names[(s["platform"], s["currency"])] != 1 for s in (source_out, source_in)):
                raise ValueError("A paired account is not uniquely owned by this user")
            found = [lookup[source_identity(source)] for source in (source_out, source_in)]
            if any(len(result) != 1 for result in found):
                raise ValueError("A source leg does not uniquely match the live ledger")
            outgoing, incoming = found[0][0], found[1][0]
            if outgoing["ID"] in used or incoming["ID"] in used:
                raise ValueError("A record appears in multiple pairs")
            used.update((outgoing["ID"], incoming["ID"]))
            identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, "iafm-ledger:" + str(user) + ":" + source_out["id"] + ":" + source_in["id"]))
            if outgoing.get("JOURNAL_ID") == incoming.get("JOURNAL_ID") == identifier and outgoing["TYPE"] == "transfer_out" and incoming["TYPE"] == "transfer_in":
                already += 1
                continue
            if outgoing["TYPE"] != "expense" or incoming["TYPE"] != "income" or outgoing.get("JOURNAL_ID") or incoming.get("JOURNAL_ID"):
                raise ValueError("Existing classification differs from the inspected source")
            resolved.append((identifier, outgoing, incoming, pair["evidence"]))
        result = dict(user_id=user, records=len(rows), verified_pairs=len(plan["pairs"]), pairs_to_apply=len(resolved), pairs_already_applied=already,
                      zero_amount_records=sum(Decimal(r["AMOUNT"]) == 0 for r in rows), retained_review_candidates=len(plan.get("review", [])), mode="apply" if apply else "dry-run")
        if not apply:
            connection.rollback()
            return result
        if not backup:
            raise ValueError("--backup is required for --apply")
        cursor.execute("SELECT * FROM LEDGER_JOURNALS WHERE USER_ID=%s ORDER BY ID", (user,))
        journals_before = cursor.fetchall()
        cursor.execute("SELECT E.* FROM LEDGER_ENTRIES E JOIN LEDGER_JOURNALS J ON J.ID=E.JOURNAL_ID WHERE J.USER_ID=%s ORDER BY E.JOURNAL_ID,E.LINE_NUMBER", (user,))
        entries_before = cursor.fetchall()
        snapshot = dict(user_id=user, created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(), rows=rows, journals=journals_before, entries=entries_before, assets=assets)
        descriptor = os.open(backup, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump(snapshot, handle, ensure_ascii=False, indent=2, default=str)
        for identifier, outgoing, incoming, evidence in resolved:
            minor = int(Decimal(outgoing["AMOUNT"]) * 100)
            if minor <= 0:
                raise ValueError("A transfer must be positive")
            journal(cursor, identifier, user, "transfer", outgoing["DATE"], outgoing["CURRENCY"], evidence)
            entry(cursor, identifier, 1, outgoing, 0, minor, balance_type="expense")
            entry(cursor, identifier, 2, incoming, minor, 0, balance_type="income")
            attach(cursor, user, outgoing, identifier, "transfer_out")
            attach(cursor, user, incoming, identifier, "transfer_in")
        external_count = 0
        for row in rows:
            if row["ID"] in used or row.get("JOURNAL_ID") or row["TYPE"] not in ("income", "expense") or Decimal(row["AMOUNT"]) == 0:
                continue
            identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, "iafm-ledger-external:" + str(user) + ":" + str(row["ID"])))
            minor = int(Decimal(row["AMOUNT"]) * 100)
            income = row["TYPE"] == "income"
            journal(cursor, identifier, user, row["TYPE"], row["DATE"], row["CURRENCY"], "Existing history retained; opening balances are not replayed")
            entry(cursor, identifier, 1, row, minor if income else 0, 0 if income else minor, balance_type=row["TYPE"])
            entry(cursor, identifier, 2, row, 0 if income else minor, minor if income else 0, kind="external_income" if income else "external_expense")
            attach(cursor, user, row, identifier, row["TYPE"])
            external_count += 1
        cursor.execute("SELECT J.ID FROM LEDGER_JOURNALS J LEFT JOIN LEDGER_ENTRIES E ON E.JOURNAL_ID=J.ID WHERE J.USER_ID=%s GROUP BY J.ID HAVING COUNT(*)<2 OR SUM(E.DEBIT_MINOR)<>SUM(E.CREDIT_MINOR) OR SUM(E.DEBIT_MINOR)<=0", (user,))
        if cursor.fetchall():
            raise ValueError("Unbalanced journal detected; rolled back")
        after_assets = []
        for table in ("ALL_NON_INVESTMENT_ASSETS", "ALL_INVESTMENT_ASSETS"):
            cursor.execute("SELECT * FROM " + table + " WHERE USER_ID=%s ORDER BY ID", (user,))
            after_assets.append(cursor.fetchall())
        if fingerprint(assets) != fingerprint(after_assets):
            raise ValueError("Balances changed; rolled back")
        cursor.execute("SELECT COUNT(*) AS N FROM INCOME_AND_EXPENDITURE_DATA WHERE USER_ID=%s", (user,))
        if cursor.fetchone()["N"] != len(rows):
            raise ValueError("Transaction count changed; rolled back")
        connection.commit()
        return dict(result, external_journals_created=external_count, balances_unchanged=True, rows_unchanged=True, all_journals_balanced=True)


def main():
    import pymysql
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--db-user", default="root")
    parser.add_argument("--socket", default="/var/run/mysqld/mysqld.sock")
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--expected-email", required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup", type=Path)
    args = parser.parse_args()
    if args.user_id <= 0:
        raise ValueError("Invalid owner ID")
    password = os.environ.get("IAFM_MIGRATION_PASSWORD")
    if password is None:
        password = getpass.getpass("Database password: ")
    plan = json.loads(args.plan.read_text())
    if plan.get("schema_version") != 1:
        raise ValueError("Unsupported plan")
    connection = pymysql.connect(unix_socket=args.socket, user=args.db_user, password=password, database=args.database, charset="utf8mb4", autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    try:
        result = migrate(connection, plan, args.user_id, args.expected_email, args.apply, args.backup)
        print(json.dumps(result, indent=2))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
