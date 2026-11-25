#!/usr/bin/env python3
"""Restricted certificate renewal transport. Paths and domain names are fixed."""
import argparse
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import time

DOMAINS = tuple(name + '.zhongjiehuang.com' for name in ('iafm', 'starhub', 'ucost'))
INCOMING = Path('/var/lib/cluster-certsync/incoming.json')
LIMIT = 512 * 1024


def validated_payload(raw):
    if len(raw) > LIMIT:
        raise ValueError('Certificate bundle exceeds size limit')
    data = json.loads(raw)
    if not isinstance(data, dict) or set(data) != set(DOMAINS):
        raise ValueError('Unexpected certificate domain')
    for entry in data.values():
        if not isinstance(entry, dict) or set(entry) != {'fullchain', 'privkey'}:
            raise ValueError('Unexpected certificate bundle fields')
        if not all(isinstance(value, str) and len(value) < 100_000 for value in entry.values()):
            raise ValueError('Invalid certificate bundle values')
    return data


def receive():
    # This is the SSH forced command, running as the isolated receiver account.
    raw = sys.stdin.buffer.read(LIMIT + 1)
    validated_payload(raw)
    descriptor, name = tempfile.mkstemp(prefix='.incoming-', dir=INCOMING.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o640)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, INCOMING)
    finally:
        if os.path.exists(name): os.unlink(name)


def output(*args, input=None):
    result = subprocess.run(args, input=input, capture_output=True, check=True)
    return result.stdout


def apply():
    if os.geteuid() != 0:
        raise ValueError('Root required for validated certificate installation')
    descriptor = os.open(INCOMING, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        raw = stream.read(LIMIT + 1)
    data = validated_payload(raw)
    with tempfile.TemporaryDirectory(prefix='four-project-cert-') as temporary:
        temporary = Path(temporary)
        for domain, entry in data.items():
            directory = temporary / domain
            directory.mkdir(mode=0o700)
            cert, key = directory / 'fullchain.pem', directory / 'privkey.pem'
            cert.write_text(entry['fullchain']); key.write_text(entry['privkey'])
            cert.chmod(0o600); key.chmod(0o600)
            output('openssl', 'x509', '-in', str(cert), '-noout', '-checkhost', domain)
            output('openssl', 'x509', '-in', str(cert), '-noout', '-checkend', '86400')
            decoded = ssl._ssl._test_decode_cert(str(cert))
            if not any(kind == 'DNS' and value == domain for kind, value in decoded.get('subjectAltName', ())):
                raise ValueError('Certificate exact domain mismatch')
            public_cert = output('openssl', 'x509', '-in', str(cert), '-pubkey', '-noout')
            public_key = output('openssl', 'pkey', '-in', str(key), '-pubout')
            if public_cert != public_key:
                raise ValueError('Certificate key does not match')
            output('openssl', 'verify', '-untrusted', str(cert), str(cert))
        target_root = Path('/etc/nginx/tls/four-project')
        changed = False
        for domain in DOMAINS:
            target = target_root / domain
            target.mkdir(mode=0o700, parents=True, exist_ok=True)
            for name in ('fullchain.pem', 'privkey.pem'):
                contents = (temporary / domain / name).read_bytes()
                installed = target / name
                if installed.exists() and installed.read_bytes() == contents:
                    continue
                changed = True
                pending = target / (name + '.new')
                pending.write_bytes(contents)
                pending.chmod(0o600)
                pending.replace(target / name)
        if changed:
            output('/usr/sbin/nginx', '-t')
            if Path('/etc/four-project-cluster/nginx-active').exists():
                output('/usr/bin/systemctl', 'reload', 'nginx.service')
    Path('/etc/four-project-cluster/cert-applied-at').write_text(str(int(time.time())) + '\n')


def send(config_path):
    config = json.loads(Path(config_path).read_text())
    bundle = {}
    for domain in DOMAINS:
        source = Path('/etc/letsencrypt/live') / domain
        bundle[domain] = {'fullchain': (source / 'fullchain.pem').read_text(), 'privkey': (source / 'privkey.pem').read_text()}
    raw = json.dumps(bundle).encode()
    validated_payload(raw)
    output('/usr/bin/ssh', '-F', '/dev/null', '-i', config['keyFile'],
           '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
           '-o', 'UpdateHostKeys=no',
           '-o', 'UserKnownHostsFile=' + config['knownHostsFile'], '-o', 'ConnectTimeout=10',
           'cluster-certsync@' + config['host'], input=raw)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['receive', 'apply', 'send'])
    parser.add_argument('--config')
    args = parser.parse_args()
    try:
        if args.action == 'send': send(args.config)
        elif args.action == 'receive': receive()
        else: apply()
    except Exception:
        # Never log private keys, payloads or command arguments.
        raise SystemExit('Certificate synchronization failed')
