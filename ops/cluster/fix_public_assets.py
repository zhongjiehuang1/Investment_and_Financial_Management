#!/usr/bin/env python3
"""Expose only intended static webapp files to the local Nginx worker."""
import argparse
import http.client
import os
from pathlib import Path
import stat
import time

READINESS_APPS = ('Investment_and_Financial_Management', 'starhub', 'ucost')
APPS = READINESS_APPS + ('ROOT',)
PRIVATE_DIRS = {'WEB-INF', 'META-INF'}
PRIVATE_SUFFIXES = {'.class', '.java', '.properties', '.sql', '.log', '.jar', '.war'}


def expose(root):
    root = Path(root).resolve(strict=True)
    bases = []
    for name in APPS:
        base = root / name
        if base.is_symlink() or not base.is_dir() or base.resolve().parent != root:
            raise ValueError('Unexpected web application directory')
        bases.append(base)
    for base in bases:
        for current, children, names in os.walk(base, followlinks=False):
            parent = Path(current)
            children[:] = [name for name in children if not name.startswith('.')
                           and name.upper() not in PRIVATE_DIRS
                           and not (parent / name).is_symlink()]
            os.chmod(parent, stat.S_IMODE(parent.stat().st_mode) | 0o005)
            for name in names:
                item = parent / name
                if name.startswith('.') or item.is_symlink() or item.suffix.lower() in PRIVATE_SUFFIXES:
                    continue
                if item.is_file():
                    os.chmod(item, stat.S_IMODE(item.stat().st_mode) | 0o004)


def wait_for_tomcat(seconds):
    pending = set(READINESS_APPS)
    deadline = time.monotonic() + seconds
    while pending and time.monotonic() < deadline:
        for name in list(pending):
            connection = http.client.HTTPConnection('127.0.0.1', 8080, timeout=2)
            try:
                connection.request('GET', '/' + name + '/api/server')
                if connection.getresponse().status == 200:
                    pending.remove(name)
            except (OSError, http.client.HTTPException):
                pass
            finally:
                connection.close()
        if pending:
            time.sleep(1)
    if pending:
        raise TimeoutError('Tomcat did not finish deploying all application contexts')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--webapps', default='/opt/tomcat/webapps')
    parser.add_argument('--wait-ready', action='store_true')
    parser.add_argument('--wait-seconds', type=int, default=120)
    args = parser.parse_args()
    if args.wait_seconds < 1 or args.wait_seconds > 300:
        raise SystemExit('Invalid startup wait')
    if args.wait_ready:
        wait_for_tomcat(args.wait_seconds)
    expose(args.webapps)
    print('Public static assets are readable; private webapp paths are unchanged.')


if __name__ == '__main__':
    main()

