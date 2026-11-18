#!/usr/bin/env python3
"""Publish a verified encrypted database-only snapshot without deleting history.

The default is a no-write plan. An interrupted execution retains its intent and
staged files; inspect them before any subsequent publication.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import uuid
from zoneinfo import ZoneInfo

HOME = Path.home()
ROOT = HOME / 'Library/CloudStorage/OneDrive-Personal/Zhongjie_Huang_s_Database'
HISTORY = ROOT / 'Historical_Archives/Database_Backups'
WORK = HOME / 'Library/Application Support/ServerRecovery/Private/Work'
STAGES = WORK / 'Flat_Database_Snapshots'
JOURNAL = WORK / 'Flat_Database_Publications'
FILES = {'All_Databases.sql.gz.GCM', 'Database_Accounts_and_Grants.json.GCM',
         'MANIFEST.json', 'DATABASE_SUMMARY.md', 'RESTORE_VERIFICATION.json'}
PATTERN = re.compile(r'Databases_Backup_\d{4}-\d{2}-\d{2}\Z')


def require(ok, msg):
    if not ok:
        raise ValueError(msg)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def ordinary(path):
    st = path.lstat()
    require(stat.S_ISREG(st.st_mode) and not path.is_symlink() and st.st_nlink == 1,
            'Expected a regular unlinked snapshot file')


def load(path):
    ordinary(path)
    return json.loads(path.read_text())


def verified_snapshot(snapshot, expected):
    require(re.fullmatch(r'[0-9a-f]{64}', expected) is not None, 'Invalid expected manifest hash')
    require(snapshot.parent == STAGES and snapshot.name.startswith('flat-') and not snapshot.is_symlink(),
            'Snapshot must be an owned flat staging directory')
    run = str(uuid.UUID(snapshot.name.removeprefix('flat-')))
    require(snapshot.name == 'flat-' + run and snapshot.is_dir(), 'Invalid staged snapshot run')
    require({p.name for p in snapshot.iterdir()} == FILES, 'Unexpected snapshot members')
    for name in FILES:
        ordinary(snapshot / name)
    require(digest(snapshot / 'MANIFEST.json') == expected, 'Flat manifest hash differs')
    manifest = load(snapshot / 'MANIFEST.json')
    require(manifest.get('flatDatabaseOnly') is True and manifest.get('sourceServer') == 2
            and manifest.get('runId') == run and manifest.get('gtidStableDuringQuiescentBackup') is True,
            'Snapshot manifest is not the reviewed database backup')
    entries = manifest.get('files')
    require(isinstance(entries, list) and {x.get('name') for x in entries} ==
            {'All_Databases.sql.gz.GCM', 'Database_Accounts_and_Grants.json.GCM'}, 'Unexpected encrypted components')
    for item in entries:
        path = snapshot / item['name']
        require(item.get('bothRecoveryKeysAuthenticated') is True and path.stat().st_size == item.get('encryptedBytes')
                and digest(path) == item.get('encryptedSha256'), 'Encrypted component verification failed')
    proof = load(snapshot / 'RESTORE_VERIFICATION.json')
    restore = str(uuid.UUID(proof.get('runId', '')))
    original = WORK / 'Single_Database_Restore' / ('restore-' + restore) / 'completion.json'
    require(proof == load(original) and proof.get('status') == 'PASS'
            and proof.get('sourceManifestSha256') == expected
            and proof.get('dataSha256') == manifest.get('sourceContentSha256')
            and all(proof.get(k) is True for k in ('isolatedMysqlExited', 'ownedCleanupPerformed', 'productionDatadirIdentityUnchanged')),
            'Independent isolated restore receipt does not match')
    source = HISTORY / ('backup-' + run) / 'MANIFEST.json'
    ordinary(source)
    require(digest(source) == manifest.get('sourceBackupManifestSha256'), 'Source backup manifest changed')
    created = datetime.datetime.fromisoformat(manifest['createdAtUtc'])
    require(created.tzinfo is not None, 'Backup creation time must have timezone')
    return manifest, created.astimezone(ZoneInfo('Europe/London')).date().isoformat()


def exclusive_json(path, obj):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump(obj, out, sort_keys=True, indent=2)
        out.write('\n')
        out.flush()
        os.fsync(out.fileno())


def copy_exact(source, target):
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with source.open('rb') as src, os.fdopen(fd, 'wb') as dst:
        shutil.copyfileobj(src, dst, 1024 * 1024)
        dst.flush()
        os.fsync(dst.fileno())
    require(digest(source) == digest(target), 'Staged copy changed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    snapshot = args.snapshot.absolute()
    manifest, day = verified_snapshot(snapshot, args.manifest_sha256)
    require(ROOT.is_dir() and HISTORY.is_dir() and not ROOT.is_symlink() and not HISTORY.is_symlink(), 'OneDrive backup root unavailable')
    existing = sorted(p for p in ROOT.iterdir() if PATTERN.fullmatch(p.name))
    require(len(existing) <= 1 and all(p.is_dir() and not p.is_symlink() for p in existing),
            'Multiple or invalid current backup directories require manual review')
    target = ROOT / ('Databases_Backup_' + day)
    require(not target.exists() or target in existing, 'Dated target already exists')
    if not args.execute:
        print(json.dumps({'status': 'PLAN_ONLY', 'target': str(target), 'existing': str(existing[0]) if existing else None,
                          'databaseRun': manifest['runId'], 'members': sorted(FILES)}, sort_keys=True))
        return
    JOURNAL.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = JOURNAL / 'exclusive.lock'
    lock.mkdir(mode=0o700)
    op = str(uuid.uuid4())
    record = JOURNAL / ('publication-' + op)
    record.mkdir(mode=0o700)
    staging = ROOT / ('.Databases_Backup_staging_' + op)
    previous = existing[0] if existing else None
    archive = HISTORY / ('History_' + previous.name + '_' + op) if previous else None
    try:
        exclusive_json(record / 'intent.json', {'runId': op, 'snapshot': str(snapshot), 'snapshotManifestSha256': args.manifest_sha256,
                        'target': str(target), 'staging': str(staging), 'previous': str(previous) if previous else None,
                        'archive': str(archive) if archive else None, 'unknownMustNotReplay': True})
        require(not staging.exists() and (archive is None or not archive.exists()), 'Publication destination already exists')
        staging.mkdir(mode=0o700)
        for name in sorted(FILES):
            copy_exact(snapshot / name, staging / name)
        require({p.name for p in staging.iterdir()} == FILES and all(not p.is_dir() for p in staging.iterdir()), 'Staged publication is not flat')
        exclusive_json(record / 'stage-complete.json', {'files': {n: digest(staging / n) for n in sorted(FILES)}})
        if previous:
            require(previous.is_dir() and not previous.is_symlink() and not archive.exists(), 'Previous backup changed')
            os.rename(previous, archive)
            exclusive_json(record / 'history-complete.json', {'previous': str(previous), 'archive': str(archive)})
        require(not target.exists(), 'Target appeared during publication')
        os.rename(staging, target)
        require({p.name for p in target.iterdir()} == FILES and all(not p.is_dir() for p in target.iterdir()), 'Published directory is not flat')
        require(all(digest(target / n) == digest(snapshot / n) for n in FILES), 'Published files changed')
        exclusive_json(record / 'completion.json', {'status': 'COMPLETE', 'target': str(target), 'archive': str(archive) if archive else None,
                        'snapshotManifestSha256': args.manifest_sha256, 'runId': op})
        lock.rmdir()
        print(json.dumps({'status': 'COMPLETE', 'target': str(target), 'archive': str(archive) if archive else None,
                          'publicationRun': op}, sort_keys=True))
    except BaseException:
        # The exact intent, lock and any staged/archive paths are evidence.
        # Never overwrite a possibly changed target or replay an unknown move.
        raise


if __name__ == '__main__':
    main()
