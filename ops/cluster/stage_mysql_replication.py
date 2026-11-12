#!/usr/bin/env python3
"""Create a private MySQL peer configuration; never activate or restart it."""
import argparse
import os
import re
from pathlib import Path
import uuid


def render(node, group, members=(1, 2), port_map=None, tls_directory="/etc/four-project/tls", fragment_bytes=1048576):
    members = tuple(members)
    if type(fragment_bytes) is not int or fragment_bytes not in (524288,1048576):
        raise ValueError("A reviewed 512-KiB or 1-MiB GR fragment bound is required")
    if (len(members) != 2 or set(members) != {1,2} or node not in members
            or any(type(n) is not int for n in members)):
        raise ValueError("Exactly the two approved physical IDs 1 and 2 are required")
    if str(uuid.UUID(group)) != group.lower():
        raise ValueError("Expected a canonical group UUID")
    if port_map is None and set(members)=={1,2}:
        port_map={1:(331,331),2:(332,332)}
    if not isinstance(port_map,dict) or set(port_map)!=set(members):
        raise ValueError("Provide a stable SQL and group port mapping for each member")
    ports=[]
    for pair in port_map.values():
        if not isinstance(pair,(tuple,list)) or len(pair)!=2 or any(not isinstance(p,int) or isinstance(p,bool) or not 1<=p<=1023 for p in pair):
            raise ValueError("Invalid mapped port")
        if pair[0] != pair[1]:
            raise ValueError("Authenticated MYSQL stack must use its SQL listener as group address")
        ports.append(pair[0])
    if len(set(ports))!=len(ports):raise ValueError("Mapped SQL ports must be unique")
    if not isinstance(tls_directory,str) or not re.fullmatch(r"/[A-Za-z0-9_./-]+",tls_directory):
        raise ValueError("Supply an absolute private TLS directory")
    tls_directory=tls_directory.rstrip("/")
    sql_port, group_port = port_map[node]
    seeds = ",".join(f"127.0.0.1:{port_map[n][1]}" for n in sorted(members))
    return f"""# Generated offline; retain the old configuration and stopped-writer backup.
[mysqld]
server_id={node}
auto_increment_increment=7
auto_increment_offset={node}
port={sql_port}
bind_address=127.0.0.1
report_host=127.0.0.1
report_port={sql_port}
mysqlx=OFF
require_secure_transport=ON
tls_version=TLSv1.3
ssl_ca={tls_directory}/ca.pem
ssl_cert={tls_directory}/server-cert.pem
ssl_key={tls_directory}/server-key.pem
skip_name_resolve=ON
performance_schema=ON
super_read_only=ON
innodb_flush_log_at_trx_commit=1
innodb_doublewrite=ON
sync_binlog=1
log_bin=/var/lib/mysql/four-project-bin
max_binlog_size=64M
binlog_expire_logs_seconds=172800
binlog_format=ROW
gtid_mode=ON
enforce_gtid_consistency=ON
log_replica_updates=ON
skip_replica_start=ON
replica_parallel_workers=1
replica_pending_jobs_size_max=33554432
plugin_load_add=group_replication.so
loose-group_replication_group_name={group.lower()}
loose-group_replication_start_on_boot=OFF
loose-group_replication_bootstrap_group=OFF
loose-group_replication_local_address=127.0.0.1:{group_port}
loose-group_replication_group_seeds={seeds}
loose-group_replication_ip_allowlist=127.0.0.1
loose-group_replication_single_primary_mode=OFF
loose-group_replication_auto_increment_increment=7
loose-group_replication_enforce_update_everywhere_checks=ON
loose-group_replication_consistency=BEFORE_AND_AFTER
loose-group_replication_communication_stack=MYSQL
loose-group_replication_ssl_mode=VERIFY_IDENTITY
loose-group_replication_recovery_use_ssl=ON
loose-group_replication_recovery_ssl_verify_server_cert=ON
loose-group_replication_recovery_tls_version=TLSv1.3
loose-group_replication_recovery_ssl_ca={tls_directory}/ca.pem
loose-group_replication_recovery_ssl_cert={tls_directory}/server-cert.pem
loose-group_replication_recovery_ssl_key={tls_directory}/server-key.pem
loose-group_replication_communication_max_message_size={fragment_bytes}
loose-group_replication_message_cache_size=134217728
loose-group_replication_recovery_get_public_key=OFF
loose-group_replication_unreachable_majority_timeout=0
"""


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node-id',type=int,required=True)
    parser.add_argument('--members',default='1,2',help='Comma-separated approved physical IDs (MySQL supports 2–9 members per group)')
    parser.add_argument('--port-map',help='Stable ID:SQL_PORT:SQL_PORT entries; MYSQL group address uses its privileged SQL listener')
    parser.add_argument('--group-id',required=True)
    parser.add_argument('--tls-directory',default='/etc/four-project/tls')
    parser.add_argument('--fragment-bytes',type=int,choices=(524288,1048576),default=1048576)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    try:
        members=tuple(int(value) for value in args.members.split(','))
        port_map=None
        if args.port_map:
            entries=[tuple(int(value) for value in item.split(':')) for item in args.port_map.split(',')]
            if any(len(entry)!=3 for entry in entries) or len({entry[0] for entry in entries})!=len(entries):raise ValueError('Invalid port map')
            port_map={entry[0]:(entry[1],entry[2]) for entry in entries}
        content=render(args.node_id,args.group_id,members,port_map,args.tls_directory,args.fragment_bytes)
    except ValueError as exc:
        parser.error(str(exc))
    fd=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as target:
        target.write(content);target.flush();os.fsync(target.fileno())
    print('MySQL peer configuration staged; no server was changed.')


if __name__=='__main__': main()
