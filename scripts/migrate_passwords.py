#!/usr/bin/env python3
"""Upgrade legacy plaintext passwords without changing passwords. Dry-run by default.

Run during maintenance. Requires PyMySQL and an exclusive 0600 backup destination for --apply.
Compatible with Model.Tools.Passwords p1 encoding (PBKDF2-HMAC-SHA256, 600000 rounds).
"""
import argparse
import base64
import getpass
import hashlib
import json
import os
import re
import secrets
import pymysql

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--database', required=True)
parser.add_argument('--db-user', default='root')
parser.add_argument('--socket', default='/var/run/mysqld/mysqld.sock')
parser.add_argument('--apply', action='store_true')
parser.add_argument('--backup')
args = parser.parse_args()
if args.apply and not args.backup:
    parser.error('--apply requires --backup')
password = getpass.getpass('Database password: ')
connection = pymysql.connect(unix_socket=args.socket, user=args.db_user, password=password, database=args.database, autocommit=False)
del password
try:
    with connection.cursor() as cursor:
        cursor.execute('SELECT ID,PASSWORD FROM USERS ORDER BY ID FOR UPDATE')
        rows = cursor.fetchall()
        legacy = [(identifier, stored) for identifier, stored in rows if not re.fullmatch(r'p1\$[A-Za-z0-9_-]{43}', stored)]
        if args.apply:
            descriptor = os.open(args.backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as handle:
                json.dump({'legacy_passwords': legacy}, handle)
            for identifier, stored in legacy:
                salt = secrets.token_bytes(16)
                derived = hashlib.pbkdf2_hmac('sha256', stored.encode('utf-8'), salt, 600000, 16)
                encoded = 'p1$' + base64.urlsafe_b64encode(salt + derived).decode().rstrip('=')
                cursor.execute('UPDATE USERS SET PASSWORD=%s WHERE ID=%s AND BINARY PASSWORD=BINARY %s', (encoded, identifier, stored))
                assert cursor.rowcount == 1, 'Password changed concurrently; rolling back'
            connection.commit()
        else:
            connection.rollback()
        print(json.dumps({'users': len(rows), 'legacy_passwords': len(legacy), 'mode': 'apply' if args.apply else 'dry-run', 'passwords_unchanged': True}))
except Exception:
    connection.rollback()
    raise
finally:
    connection.close()
