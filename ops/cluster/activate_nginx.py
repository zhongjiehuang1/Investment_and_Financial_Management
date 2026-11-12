#!/usr/bin/env python3
"""Explicit maintenance-window activation; generates no credentials."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess


def run(*args):
    subprocess.run(args, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--maintenance-window', action='store_true', required=True)
    parser.add_argument('--stage', default='/root/four-project-maintenance/nginx-stage')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('Root required')
    stage = Path(args.stage)
    run('nginx', '-t', '-c', str(stage / 'nginx-test.conf'))
    for source in (stage / 'sites').iterdir():
        if not source.is_file():
            continue
        target = Path('/etc/nginx/sites-available') / source.name
        shutil.copyfile(source, target)
        target.chmod(0o600)
        enabled = Path('/etc/nginx/sites-enabled') / source.name
        if enabled.is_symlink() or enabled.exists():
            enabled.unlink()
        enabled.symlink_to(target)
    run('nginx', '-t')
    run('systemctl', 'daemon-reload')
    # Restart existing long-lived root writers to apply their capability bounds.
    state = json.loads(Path('/root/four-project-maintenance/cluster-install-state.json').read_text())
    for unit in state['managedRootWriterUnits']:
        if unit == 'nginx.service':
            continue
        if subprocess.run(['systemctl', 'is-active', '--quiet', unit]).returncode == 0:
            run('systemctl', 'restart', unit)
    run('systemctl', 'restart', 'nginx.service')
    Path('/etc/four-project-cluster/nginx-active').touch(mode=0o600)
    print('Staged Nginx sites activated; managed active root writers restarted with reserve bounds.')


if __name__ == '__main__':
    main()
