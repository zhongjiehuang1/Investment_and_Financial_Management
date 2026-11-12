#!/usr/bin/env python3
"""Verify a paired backup's file checksums and recorded isolated DB restore.

This read-only check is not a complete system-restore test. It never extracts
archives, connects to a database, or makes changes on a server.
"""
import argparse
import hashlib
import json
import re
import stat
import tarfile
from pathlib import Path, PurePosixPath

REQUIRED = {'cold-restore-verification.json'} | {
    f'node{node}-{suffix}' for node in (1, 2) for suffix in (
        'databases.sql.gz', 'mysql-cold.tar.gz', 'recovery-files.tar.gz',
        'table-checksums.tsv', 'host-inventory.txt')}
RECOVERY_PATHS = {'etc/passwd','etc/group','etc/shadow','etc/mysql','etc/nginx','etc/ssh',
                  'etc/systemd/system','var/www','usr/local/lib/four-project-cluster'} | {
    f'var/lib/tomcat9/webapps/{app}.war' for app in ('ROOT','Investment_and_Financial_Management','starhub','ucost')}


def verify(bundle):
    root=Path(bundle)
    if root.is_symlink() or not root.is_dir():raise ValueError('Backup directory is invalid')
    manifest_path=root/'manifest.json'
    if manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size>1_000_000:
        raise ValueError('Backup manifest is missing or unsafe')
    manifest=json.loads(manifest_path.read_text())
    if not isinstance(manifest,dict) or not REQUIRED.issubset(manifest):raise ValueError('Required backup files are missing from the manifest')
    for name,meta in manifest.items():
        if not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9_.-]+',name) or name in ('.','..') or not isinstance(meta,dict):
            raise ValueError('Invalid manifest entry')
        path=root/name
        if path.is_symlink() or not path.is_file() or not stat.S_ISREG(path.stat().st_mode):raise ValueError('Missing or unsafe file: '+name)
        if not isinstance(meta.get('bytes'),int) or isinstance(meta.get('bytes'),bool) or path.stat().st_size!=meta['bytes']:
            raise ValueError('File size mismatch: '+name)
        if not isinstance(meta.get('sha256'),str) or not re.fullmatch(r'[a-f0-9]{64}',meta['sha256']):raise ValueError('Invalid SHA-256 entry')
        digest=hashlib.sha256()
        with path.open('rb') as data:
            for chunk in iter(lambda:data.read(1024*1024),b''):digest.update(chunk)
        if digest.hexdigest()!=meta['sha256']:raise ValueError('Checksum mismatch: '+name)
    proof=json.loads((root/'cold-restore-verification.json').read_text())
    if set(proof)!={'node1','node2'}:raise ValueError('Restore verification does not cover both nodes')
    for node in ('node1','node2'):
        result=proof[node]
        if (not isinstance(result,dict) or result.get('result')!='match' or result.get('network_disabled') is not True
                or not isinstance(result.get('tables_compared'),int) or isinstance(result.get('tables_compared'),bool)
                or result['tables_compared']<1 or not isinstance(result.get('mysql_accounts'),int)
                or isinstance(result.get('mysql_accounts'),bool) or result['mysql_accounts']<1):
            raise ValueError('Incomplete isolated database restore evidence: '+node)
        with tarfile.open(root/(node+'-mysql-cold.tar.gz'),'r:gz') as archive:
            count=0
            for member in archive:
                name=PurePosixPath(member.name)
                if name.is_absolute() or '..' in name.parts or not name.parts or name.parts[0]!='mysql' or member.issym() or member.islnk():
                    raise ValueError('Unsafe MySQL archive entry')
                count+=1
            if not count:raise ValueError('Empty MySQL archive')
        with tarfile.open(root/(node+'-recovery-files.tar.gz'),'r:gz') as archive:
            names={member.name.rstrip('/') for member in archive}
            if not RECOVERY_PATHS.issubset(names):raise ValueError('Required system recovery material missing: '+node)
    return {'files':len(manifest),'tables':sum(proof[n]['tables_compared'] for n in ('node1','node2'))}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('backup_directory',type=Path);args=parser.parse_args()
    result=verify(args.backup_directory)
    print(f"Backup integrity verified: {result['files']} files, {result['tables']} restored database tables in recorded evidence.")
    print('This does not prove that an entire server can be rebuilt; a clean-host recovery exercise is still required.')


if __name__=='__main__':main()
