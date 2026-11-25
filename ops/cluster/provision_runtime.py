#!/usr/bin/env python3
"""Provision scoped runtime users from a private file; never prints credentials."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

if os.geteuid()!=0 or len(sys.argv)!=2: raise SystemExit('Root and a private credential file are required')
settings=json.loads(Path(sys.argv[1]).read_text())
expected={'finance':('IAFM','iafm_cluster'),'starhub':('starhub_accounts','starhub_cluster')}
commands=[]
for name,(database,user) in expected.items():
    entry=settings[name]
    if entry['database']!=database or entry['user']!=user or not re.fullmatch('[0-9a-f]{64}',entry['password']): raise SystemExit('Unexpected scoped runtime identity')
    password=entry['password']
    commands += [f"CREATE USER IF NOT EXISTS '{user}'@'localhost' IDENTIFIED BY '{password}'",
                 f"ALTER USER '{user}'@'localhost' IDENTIFIED BY '{password}'",
                 f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{user}'@'localhost'",
                 f"GRANT XA_RECOVER_ADMIN ON *.* TO '{user}'@'localhost'"]
result=subprocess.run(['/usr/bin/mysql','--defaults-extra-file=/root/four-project-maintenance/admin.cnf'],input=';\n'.join(commands)+';\n',text=True,capture_output=True)
if result.returncode:
    Path('/root/four-project-maintenance/runtime-provision-error.log').write_text(result.stderr)
    raise SystemExit('Runtime provisioning failed; private error retained')
for name,entry in settings.items():
    path=Path('/root/four-project-maintenance')/(name+'-runtime.cnf')
    path.write_text('[client]\nuser='+entry['user']+'\npassword='+entry['password']+'\nhost=127.0.0.1\nprotocol=tcp\n');path.chmod(0o600)
    checked=subprocess.run(['/usr/bin/mysql','--defaults-extra-file='+str(path),'--batch','--skip-column-names','-e','SELECT 1'],capture_output=True,text=True)
    if checked.returncode or checked.stdout.strip()!='1': raise SystemExit('Runtime connection validation failed')
print('Scoped finance and StarHub CRUD + XA recovery accounts provisioned and connected.')
