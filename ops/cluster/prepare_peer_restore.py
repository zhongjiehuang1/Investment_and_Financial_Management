#!/usr/bin/env python3
"""Prepare a peer's stopped-writer mysqldump for an isolated restore rehearsal.

The output retains the two logical shards under distinct database names.  This
is an offline transformation: it never connects to, drops, or updates MySQL.
Do not import its output into production without a separately verified migration.
"""
import argparse
import gzip
import os
import re
from pathlib import Path

PEER_DATABASES = {
    b"IAFM": b"IAFM_peer",
    b"starhub_accounts": b"starhub_accounts_peer",
    b"ucost_app": b"ucost_app_peer",
}
CREATE_DATABASE = re.compile(rb"^CREATE DATABASE\s+(?:/\*!\d+ IF NOT EXISTS\*/\s+)?`([^`]+)`")
USE_DATABASE = re.compile(rb"^USE\s+`([^`]+)`\s*;")
UNSUPPORTED_OBJECT = re.compile(
    rb"^\s*(?:/\*!\d+\s+)?CREATE\s+(?:(?:/\*!\d+\s+)?DEFINER\s*=|"
    rb"(?:OR\s+REPLACE\s+)?(?:ALGORITHM\s*=\s*\w+\s+)?(?:VIEW|TRIGGER|EVENT|PROCEDURE|FUNCTION)\b)",
    re.IGNORECASE,
)
CROSS_SCHEMA_REFERENCE = re.compile(rb"\bREFERENCES\s+`[^`]+`\s*\.\s*`", re.IGNORECASE)


def transform(source, destination):
    """Rewrite only mysqldump schema declarations, preserving every row byte."""
    declarations = {name: [0, 0] for name in PEER_DATABASES}
    for line in source:
        if UNSUPPORTED_OBJECT.search(line) or CROSS_SCHEMA_REFERENCE.search(line):
            raise ValueError("Unsupported stored object or cross-schema foreign key; inspect manually")
        if re.match(rb"^\s*SET\s+@@(?:GLOBAL\.)?GTID_PURGED", line, re.IGNORECASE):
            raise ValueError("Dump must be produced with --set-gtid-purged=OFF")
        if re.match(rb"^\s*DROP\s+(?:DATABASE|SCHEMA)\b", line, re.IGNORECASE):
            raise ValueError("Dump must not contain DROP DATABASE or DROP SCHEMA")
        create = CREATE_DATABASE.match(line)
        use = USE_DATABASE.match(line)
        if line.startswith(b"CREATE DATABASE") and not create:
            raise ValueError("Unexpected CREATE DATABASE syntax")
        if re.match(rb"^\s*USE\s+", line, re.IGNORECASE) and not use:
            raise ValueError("Unexpected USE syntax")
        match = create or use
        if match:
            name = match.group(1)
            if name not in PEER_DATABASES:
                raise ValueError("Unexpected database declaration")
            declarations[name][0 if create else 1] += 1
            start, end = match.span(1)
            line = line[:start] + PEER_DATABASES[name] + line[end:]
        destination.write(line)
    if any(counts != [1, 1] for counts in declarations.values()):
        raise ValueError("Each expected database must be declared and selected exactly once")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Stopped-writer mysqldump .sql.gz")
    parser.add_argument("destination", type=Path, help="New .sql.gz for an isolated rehearsal")
    args = parser.parse_args()
    # Exclusive creation prevents accidental replacement of a backup or source.
    fd = os.open(args.destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb") as out, gzip.open(args.source, "rb") as src:
            transform(src, out)
    except BaseException:
        args.destination.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    main()
