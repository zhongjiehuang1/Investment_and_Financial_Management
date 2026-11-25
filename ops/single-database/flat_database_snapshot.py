#!/usr/bin/env python3
"""Prepare a flat, encrypted database snapshot; no network or server changes."""
import argparse, collections, datetime, hashlib, json, os, pathlib, re, shutil, stat, subprocess, uuid
from zoneinfo import ZoneInfo

P = pathlib.Path
HOME_DIR = P.home()
PRIVATE = HOME_DIR / 'Library/Application Support/ServerRecovery/Private'
WORK = PRIVATE / 'Work'
STAGING = WORK / 'Flat_Database_Snapshots'
BACKUPS = HOME_DIR / 'Library/CloudStorage/OneDrive-Personal/Zhongjie_Huang_s_Database/Historical_Archives/Database_Backups'
NODE = HOME_DIR / '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node'
ENV = P(__file__).resolve().parent / 'recovery_envelope.cjs'
ENV_SHA = '5e22f75a7dab7b366ad34a8bab9e52ea19e27da49c9f05952b0e1aae0c315f7d'
NAMES = ('All_Databases.sql.gz.GCM', 'Database_Accounts_and_Grants.json.GCM')

def require(value, message):
    if not value:
        raise ValueError(message)

def sha(path):
    h = hashlib.sha256()
    with P(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def private_file(path):
    path = P(path); s = path.lstat()
    require(stat.S_ISREG(s.st_mode) and not path.is_symlink() and s.st_uid == os.getuid()
            and stat.S_IMODE(s.st_mode) == 0o600 and s.st_nlink == 1, 'Private regular file required')

def private_directory(path):
    path = P(path); s = path.lstat()
    require(stat.S_ISDIR(s.st_mode) and not path.is_symlink() and s.st_uid == os.getuid()
            and stat.S_IMODE(s.st_mode) == 0o700 and 'onedrive' not in str(path.resolve()).lower(),
            'Private local staging directory required')

def write_new(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as f:
        f.write(data); f.flush(); os.fsync(f.fileno())

def copy_new(source, target):
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with P(source).open('rb') as source_file, os.fdopen(fd, 'wb') as target_file:
        shutil.copyfileobj(source_file, target_file, 1024 * 1024); target_file.flush(); os.fsync(target_file.fileno())

def validate_inventory(manifest, before):
    server = before['server2']; inventory = server['inventory']; schemas = manifest['schemas']
    require(server.get('applicationsStopped') is True and server['identity']['uuid'] == manifest['mysqlUuid']
            and inventory['schemas'] == schemas and inventory['baseTableCount'] == manifest['baseTableCount']
            and inventory['viewCount'] == manifest['viewCount'], 'Backup inventory does not match manifest')
    require(schemas and len(schemas) == len(set(schemas)) and all(re.fullmatch(r'[A-Za-z0-9_]+', s) for s in schemas),
            'Invalid schema inventory')
    counts = {name: collections.Counter() for name in schemas}
    for row in inventory['objects']:
        require(isinstance(row, list) and len(row) == 4 and row[0] in counts and row[3] in ('BASE TABLE', 'VIEW'),
                'Invalid object inventory')
        counts[row[0]][row[3]] += 1
    require(sum(x['BASE TABLE'] for x in counts.values()) == manifest['baseTableCount']
            and sum(x['VIEW'] for x in counts.values()) == manifest['viewCount'], 'Object counts differ')
    return counts

def summary(manifest, counts):
    captured = datetime.datetime.fromisoformat(manifest['createdAtUtc']).astimezone(ZoneInfo('Europe/London'))
    lines = ['# Database backup summary', '',
             'Captured: ' + captured.strftime('%Y-%m-%d %H:%M:%S %Z'), '',
             'This is an encrypted point-in-time backup of all non-system databases on Server 2. '
             'The SQL file contains their schemas and data; the separate encrypted file contains account and grant references.', '',
             '| Database | Tables | Views |', '| --- | ---: | ---: |']
    lines += [f'| `{name}` | {counts[name]["BASE TABLE"]} | {counts[name]["VIEW"]} |' for name in manifest['schemas']]
    lines += ['', 'The `information_schema`, `performance_schema` and `sys` system schemas are not exported as data. '
              'The `mysql` system schema is not dumped wholesale; account and grant information is kept in the separate encrypted file. '
              'The original full backup also contains separately stored server-configuration archives.', '',
              'Keep recovery keys outside OneDrive. A separate isolated-restore receipt must match this folder’s `MANIFEST.json`.', '']
    return '\n'.join(lines)

def prepare(backup, expected_sha):
    backup = P(backup)
    require(backup.is_absolute() and backup.parent == BACKUPS and backup.is_dir() and not backup.is_symlink(),
            'Use an existing reviewed recovery backup directory')
    manifest_path = backup / 'MANIFEST.json'; private_file(manifest_path)
    require(re.fullmatch(r'[0-9a-f]{64}', expected_sha or '') and sha(manifest_path) == expected_sha, 'Manifest hash mismatch')
    manifest = json.loads(manifest_path.read_bytes()); run = str(uuid.UUID(manifest['runId']))
    require(backup.name == 'backup-' + run and manifest.get('format') == 'single-server-database-backup-v1'
            and manifest.get('applicationsStoppedOnBothHosts') is True and manifest.get('sourceContentDigestBeforeAndAfterDumpMatch') is True,
            'Backup lacks required consistency evidence')
    receipt = WORK / 'Single_Database_Backup' / ('single-database-backup-' + run) / 'before.json';private_file(receipt)
    counts = validate_inventory(manifest, json.loads(receipt.read_bytes()))
    custody = PRIVATE / 'recovery-key-custody.json';private_file(custody);keys = json.loads(custody.read_bytes())
    keypaths = [P(keys['primaryPath']), P(keys['secondaryPath'])]
    require(len({p.resolve(strict=True) for p in keypaths}) == 2 and sha(ENV) == ENV_SHA and NODE.is_file(), 'Recovery tools mismatch')
    for key in keypaths:
        private_file(key);require(key.is_absolute() and key.stat().st_size == 32 and 'onedrive' not in str(key.resolve()).lower(), 'Invalid recovery key')
    matching = [x for x in manifest['files'] if x.get('name') in NAMES]
    items = {x['name']: x for x in matching}
    require(len(matching) == len(NAMES) and set(items) == set(NAMES), 'Required encrypted components missing or duplicated')
    for name in NAMES:
        path = backup / name;private_file(path);item = items[name]
        require(path.stat().st_size == item['encryptedBytes'] and sha(path) == item['encryptedSha256'], 'Ciphertext mismatch')
        for key in keypaths:
            checked = subprocess.run([str(NODE), str(ENV), 'verify', str(key), str(path)], capture_output=True, text=True, timeout=1800, check=True)
            result = json.loads(checked.stdout)
            require(result.get('decryptVerified') is True and result['plaintextSha256'] == item['plaintextSha256']
                    and result['plaintextBytes'] == item['plaintextBytes'], 'Encrypted component authentication failed')
    private_directory(WORK);STAGING.mkdir(mode=0o700, exist_ok=True);private_directory(STAGING)
    dest = STAGING / ('flat-' + run);dest.mkdir(mode=0o700)
    for name in NAMES:copy_new(backup / name, dest / name)
    flat = dict(manifest);flat['files'] = [items[name] for name in NAMES];flat['configurationFromBothServers'] = False
    flat['flatDatabaseOnly'] = True;flat['sourceBackupManifestSha256'] = expected_sha
    flat['sourceBackupDirectory'] = backup.name
    flat['flatScope'] = 'Non-system database SQL and encrypted account/grant reference; server configurations remain in the source backup.'
    write_new(dest / 'MANIFEST.json', (json.dumps(flat, indent=2, sort_keys=True) + '\n').encode())
    write_new(dest / 'DATABASE_SUMMARY.md', summary(manifest, counts).encode())
    print(json.dumps({'status':'PREPARED_NOT_RESTORE_TESTED','destination':str(dest),'manifestSha256':sha(dest/'MANIFEST.json'),
                      'sourceManifestSha256':expected_sha,'schemaCount':len(counts)}))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--execute',action='store_true');p.add_argument('--backup');p.add_argument('--manifest-sha256');a=p.parse_args()
    if not a.execute:
        print(json.dumps({'mode':'LOCAL_PLAN_ONLY','networkCalls':0,'filesChanged':False,'scope':'Prepare encrypted database-only files from an already completed and verified backup; an isolated restore and safe publication are separate.'}));return
    require(a.backup and a.manifest_sha256,'Explicit backup and manifest hash required');prepare(a.backup,a.manifest_sha256)

if __name__ == '__main__':
    try:main()
    except Exception as exc:
        print(json.dumps({'status':'REFUSED_OR_INCOMPLETE','errorClass':type(exc).__name__,'message':str(exc),'automaticRetry':False}),file=__import__('sys').stderr)
        raise SystemExit(1)
