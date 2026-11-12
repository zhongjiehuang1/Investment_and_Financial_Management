#!/usr/bin/env python3
"""Explicit root-only installation. Input token/config staging directory is private."""
import argparse
import grp
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import stat
import subprocess
from reserve_observer import BOUNDARY_POLICY, ext4_fields, kernel_reserve_path, mounted_reserve_identity, required_root_blocks


def run(*args, input=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True)
    if result.returncode:
        error_path = Path('/root/four-project-maintenance/cluster-install-error.log')
        error_path.parent.mkdir(parents=True, exist_ok=True)
        write(error_path, Path(args[0]).name + ' failed with exit code ' + str(result.returncode) + '\n')
        raise RuntimeError('Cluster installation command failed; private log retained')
    return result.stdout.strip()


def write(path, text, mode=0o600, owner=None, group=None):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.new')
    with temporary.open('w') as stream:
        stream.write(text);stream.flush();os.fsync(stream.fileno())
    temporary.chmod(mode)
    if owner is not None or group is not None:
        os.chown(temporary, -1 if owner is None else pwd.getpwnam(owner).pw_uid, -1 if group is None else grp.getgrnam(group).gr_gid)
    temporary.replace(path)
    directory_fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(directory_fd)
    finally:os.close(directory_fd)


def account(name):
    try: pwd.getpwnam(name)
    except KeyError: run('/usr/sbin/useradd', '--system', '--user-group', '--no-create-home', '--shell', '/usr/sbin/nologin', name)


def reserved_blocks(block_size, kernel_reserved_clusters=None, cluster_size=None):
    return required_root_blocks(block_size,kernel_reserved_clusters,cluster_size)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--node-id',type=int,choices=[1,2],required=True);parser.add_argument('--stage',required=True);parser.add_argument('--mysql-defaults-file',required=True);args=parser.parse_args()
    if os.geteuid()!=0: raise SystemExit('Root is required')
    admin = Path(args.mysql_defaults_file)
    info = admin.lstat()
    if not admin.is_absolute() or not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise SystemExit('Supply an absolute root-owned private MySQL option file (mode 0600)')
    stage=Path(args.stage);token=(stage/'cluster-shared.token').read_text().strip()
    if len(token)<32: raise SystemExit('Private token is missing or too short')
    account('cluster-agent');account('cluster-reserve')
    config_dir=Path('/etc/four-project-cluster');config_dir.mkdir(exist_ok=True);config_dir.chmod(0o750);os.chown(config_dir,0,grp.getgrnam('cluster-agent').gr_gid)
    write(config_dir/'token',token+'\n',0o640,'root','cluster-agent')
    password=secrets.token_hex(32)
    sql=(f"CREATE USER IF NOT EXISTS 'cluster_health'@'localhost' IDENTIFIED BY '{password}'; "
         f"ALTER USER 'cluster_health'@'localhost' IDENTIFIED BY '{password}'; "
         "GRANT SELECT ON performance_schema.replication_group_members TO 'cluster_health'@'localhost'; "
         "GRANT SELECT ON performance_schema.replication_group_member_stats TO 'cluster_health'@'localhost';")
    run('/usr/bin/mysql','--defaults-extra-file='+str(admin),input=sql)
    write(config_dir/'mysql.cnf','[client]\nuser=cluster_health\npassword='+password+'\nsocket=/var/run/mysqld/mysqld.sock\n',0o600,'cluster-agent','cluster-agent')
    device=run('/usr/bin/findmnt','-n','-o','SOURCE','/');filesystem=run('/usr/bin/findmnt','-n','-o','FSTYPE','/')
    if filesystem!='ext4' or not device.startswith('/dev/'): raise RuntimeError('Supported ext4 volume required')
    reserved=4_000_000_000
    metadata=run('/usr/sbin/tune2fs','-l',device)
    before=Path('/root/four-project-maintenance/ext4-reserve-before.txt')
    if not before.exists(): write(before,metadata+'\n')
    fields=ext4_fields(metadata);block_size=fields['Block size'];cluster_size=fields['Cluster size']
    kernel_clusters=int(kernel_reserve_path(device).read_text().strip())
    reserve_user=pwd.getpwnam('cluster-reserve');reserve_group=grp.getgrnam('cluster-reserve')
    if mounted_reserve_identity(device) != (reserve_user.pw_uid,reserve_group.gr_gid):
        raise RuntimeError('Mounted ext4 reserve identity differs; arrange a reviewed remount or reboot before installation. No reservation setting was changed.')
    run('/usr/sbin/tune2fs','-r',str(reserved_blocks(block_size,kernel_clusters,cluster_size)),'-u',str(reserve_user.pw_uid),'-g',str(reserve_group.gr_gid),device)
    roots=['cron.service','rsyslog.service','systemd-journald.service','nginx.service','snapd.service','apt-daily.service','apt-daily-upgrade.service','unattended-upgrades.service','logrotate.service','certbot.service','systemd-journal-flush.service']
    roots=[unit for unit in roots if run('/usr/bin/systemctl','show',unit,'--property=LoadState','--value')!='not-found']
    for unit in roots+['mysql.service','tomcat.service']:
        write('/etc/systemd/system/'+unit+'.d/40-four-project-reserve.conf','[Service]\nCapabilityBoundingSet=~CAP_SYS_RESOURCE\n',0o644)
    for unit in ['mysql.service','tomcat.service']:
        write('/etc/systemd/system/'+unit+'.d/41-four-project-reserve-policy.conf','[Unit]\nRequires=four-project-reserve-policy.service\nAfter=four-project-reserve-policy.service\n',0o644)
    write('/etc/systemd/journald.conf.d/40-four-project-bounds.conf','[Journal]\nSystemMaxUse=128M\nRuntimeMaxUse=64M\nSystemMaxFileSize=16M\nRuntimeMaxFileSize=16M\nSystemKeepFree=4000000000\nMaxRetentionSec=7day\nRateLimitIntervalSec=30s\nRateLimitBurst=1000\n',0o644)
    observer={'boundaryPolicy':BOUNDARY_POLICY,'socket':'/run/four-project-reserve/status.sock','reservedBytes':reserved,'reservedUid':reserve_user.pw_uid,'reservedGid':reserve_group.gr_gid,
              'volumes':[{'id':'root','path':'/var/lib/mysql','device':device,'kernelReservedClusters':kernel_clusters}],'applicationUnits':['mysql.service','tomcat.service'],'managedRootWriterUnits':roots}
    write(config_dir/'reserve.json',json.dumps(observer,indent=2)+'\n',0o640,'root','cluster-agent')
    config={'nodeId':args.node_id,'tokenFile':str(config_dir/'token'),'mysqlDefaultsFile':str(config_dir/'mysql.cnf'),'reserveSocket':observer['socket'],
            'tomcatChecks':[{'url':'http://127.0.0.1:8080/','expectedStatus':200}],
            'physicalBytesPerPayloadByte':4,'fixedOverheadBytes':64*1024**2,'capacityModelValidated':False}
    if args.node_id==1:
        config['peerStatusUrls']=[{'nodeId':2,'url':'http://127.0.0.1:9412/status'}]
        config['recoveryChecks']=['http://127.0.0.1:8080/Investment_and_Financial_Management/internal/recovery-ready','http://127.0.0.1:8080/starhub/api/internal/recovery-ready','http://127.0.0.1:8080/ucost/internal/recovery-ready']
    else: config['readyDelegate']='http://127.0.0.1:18081/ucost/api/cluster-health'
    write(config_dir/'agent.json',json.dumps(config,indent=2)+'\n',0o640,'root','cluster-agent')
    for source in ('node_agent.py','reserve_observer.py','reserve_policy.py','attest_manual_fence.py','fix_public_assets.py'):
        write('/usr/local/lib/four-project-cluster/'+source,(stage/source).read_text(),0o755)
    write('/etc/systemd/system/tomcat.service.d/four-project-public-assets.conf','''[Service]
ExecStartPost=/usr/bin/python3 /usr/local/lib/four-project-cluster/fix_public_assets.py --wait-ready
''',0o644)
    write('/etc/systemd/system/four-project-reserve.service',f'''[Unit]
Description=Read-only ext4 reserve boundary observer
After=local-fs.target four-project-reserve-policy.service
Requires=four-project-reserve-policy.service
[Service]
Type=simple
User=root
Group=cluster-agent
ExecStart=/usr/bin/python3 /usr/local/lib/four-project-cluster/reserve_observer.py /etc/four-project-cluster/reserve.json
RuntimeDirectory=four-project-reserve
RuntimeDirectoryMode=0750
UMask=0007
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
CapabilityBoundingSet=
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
DevicePolicy=closed
DeviceAllow={device} r
RestrictAddressFamilies=AF_UNIX
MemoryMax=128M
[Install]
WantedBy=multi-user.target
''',0o644)
    write('/etc/systemd/system/four-project-reserve-policy.service',f'''[Unit]
Description=Verify one aggregate ext4 root-plus-kernel reserve
After=local-fs.target
Before=mysql.service tomcat.service four-project-reserve.service
[Service]
Type=oneshot
User=root
Group=root
ExecStart=/usr/bin/python3 /usr/local/lib/four-project-cluster/reserve_policy.py --config /etc/four-project-cluster/reserve.json --apply
RemainAfterExit=true
NoNewPrivileges=true
CapabilityBoundingSet=
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths={kernel_reserve_path(device)}
RestrictAddressFamilies=AF_UNIX
[Install]
WantedBy=multi-user.target
''',0o644)
    write('/etc/systemd/system/four-project-agent.service','''[Unit]
Description=Private cluster health and capacity observer
After=network.target mysql.service tomcat.service four-project-reserve.service
Requires=four-project-reserve.service
[Service]
Type=simple
User=cluster-agent
Group=cluster-agent
ExecStart=/usr/bin/python3 /usr/local/lib/four-project-cluster/node_agent.py /etc/four-project-cluster/agent.json
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
CapabilityBoundingSet=
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
RestrictAddressFamilies=AF_INET AF_UNIX
MemoryMax=256M
TasksMax=96
[Install]
WantedBy=multi-user.target
''',0o644)
    run('/usr/bin/systemctl','daemon-reload')
    run('/usr/bin/systemd-analyze','verify','/etc/systemd/system/four-project-agent.service','/etc/systemd/system/four-project-reserve.service','/etc/systemd/system/four-project-reserve-policy.service')
    run('/usr/bin/systemctl','enable','--now','four-project-reserve-policy.service','four-project-reserve.service','four-project-agent.service')
    run('/usr/bin/systemctl','restart','four-project-reserve.service','four-project-agent.service')
    write('/root/four-project-maintenance/cluster-install-state.json',json.dumps({'nodeId':args.node_id,'device':device,'reservedBytes':reserved,'managedRootWriterUnits':roots,'note':'Existing root-writer services must restart to enforce new capability bounds; no app restart or Nginx activation performed.'},indent=2)+'\n')
    print('Private observers installed; ext4 reserve configured; existing applications and Nginx routes unchanged.')


if __name__=='__main__': main()
