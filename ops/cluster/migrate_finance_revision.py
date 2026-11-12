#!/usr/bin/env python3
"""Explicit lab-only protocol-2 maintenance phases; never starts/stops services.

Generate one shared owner-private credential file off-server, then copy it through
an authorized secure channel to both hosts. Run preflight on both. Only root's
separate GO authorizes canonical GR DDL/account changes and config switching.
"""
import argparse
import grp
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import time
import urllib.request
import urllib.parse
import uuid

CANONICAL={'IAFM':'IAFM_peer','finance_atomic_validation_a':'finance_atomic_validation_b'}
ALL_SCHEMAS=set(CANONICAL)|set(CANONICAL.values())
DEFAULTS=Path('/var/lib/codex-validation/private/client.cnf')
AUTHORITY=Path('/var/lib/codex-validation/app-config/application-authority.json')

class Closed(Exception):pass

def require(value,message):
    if not value:raise Closed(message)

def private(path):
    path=Path(path);info=path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid==os.geteuid() and stat.S_IMODE(info.st_mode)==0o600,'Owner-private regular file required')
    return path

def ident(value):
    require(isinstance(value,str) and re.fullmatch('[A-Za-z0-9_]+',value),'Unexpected SQL identifier')
    return '`'+value+'`'

def literal(value):return "'"+str(value).replace("'","''")+"'"

def credentials(path):
    value=json.loads(private(path).read_text())
    require(value.get('username')=='iafm_atomic_v2' and re.fullmatch('[A-Za-z0-9_-]{48,}',value.get('password','')),'Expected new dedicated identity required')
    require(str(uuid.UUID(value['migrationId']))==value['migrationId'],'Canonical new migration UUID required')
    return value

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def private_opener():
    # Do not inherit HTTP(S)_PROXY/ALL_PROXY for a private bearer-token call.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())

def authority_dual():
    info=AUTHORITY.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid==0 and stat.S_IMODE(info.st_mode) in (0o600,0o640),'Protected local authority configuration required')
    cfg=json.loads(AUTHORITY.read_text());url=urllib.parse.urlsplit(cfg.get('authorityUrl',''))
    require(url.scheme=='http' and url.hostname=='127.0.0.1' and url.port in (941,942) and url.path=='/authority'
            and not url.username and not url.password and not url.query and not url.fragment,'Exact private loopback authority required')
    require(isinstance(cfg.get('token'),str) and len(cfg['token'])>=32,'Private application authority token required')
    request=urllib.request.Request(cfg['authorityUrl'],headers={'Authorization':'Bearer '+cfg['token']})
    try:
        opener=private_opener()
        with opener.open(request,timeout=15) as reply:
            require(reply.status==204,'Controller must grant an actual dual authority')
            proof=tuple(reply.headers.get('X-Four-Project-'+key,'') for key in ('MySQL-UUID','View-ID','Epoch','Mode','Membership','Controller-Boot-ID','Permit'))
        request=urllib.request.Request(cfg['authorityUrl'][:-len('/authority')]+'/status',headers={'Authorization':'Bearer '+cfg['token']})
        with opener.open(request,timeout=15) as reply:
            require(reply.status==200,'Private controller status unavailable')
            raw=reply.read(65537);require(len(raw)<=65536,'Bounded private status required');status=json.loads(raw)
    except Exception:raise Closed('Stable DUAL controller authority is unavailable') from None
    require(str(uuid.UUID(proof[0]))==proof[0] and 1<=len(proof[1])<=256 and re.fullmatch('[0-9]+',proof[2]) and proof[3]=='dual'
            and re.fullmatch('[0-9a-f]{64}',proof[4]) and str(uuid.UUID(proof[5]))==proof[5] and 32<=len(proof[6])<=4096,'Complete current dual permit required')
    # HTTP /status deliberately exposes the application health contract, not
    # snapshot()'s root-HMAC-RPC structure. No peer secret or controller instance
    # is read/constructed here. The full permit enforces D2's internal audit.
    observed=status.get('observedAtMillis')
    require(status.get('mysqlReady') is True and status.get('replicationMode')=='dual' and status.get('singleNodeAuthorized') is False
            and status.get('configuredNodeIds')==[1,2] and status.get('onlineNodeIds')==[1,2]
            and all(type(x) is int for key in ('configuredNodeIds','onlineNodeIds') for x in status.get(key,[]))
            and type(status.get('nodeId')) is int and status['nodeId']==url.port-940
            and type(observed) is int and 0<=int(time.time()*1000)-observed<=5000
            and str(status.get('authorityEpoch'))==proof[2] and status.get('membershipDigest')==proof[4]
            and status.get('memberUuid')==proof[0] and status.get('viewId')==proof[1] and status.get('controllerBootId')==proof[5],
            'Current application-status dual gate must match the full permit')
    return proof

def confirm_dual(db,permit):
    require(authority_dual()==permit,'Maintenance authority changed before peer durability confirmation')
    executed=db.scalar('SELECT @@GLOBAL.gtid_executed')
    cfg=json.loads(AUTHORITY.read_text())
    body=json.dumps({'permit':permit[6],'gtidSet':executed,'operationId':str(uuid.uuid4())}).encode()
    request=urllib.request.Request(cfg['authorityUrl'][:-len('/authority')]+'/confirm',data=body,
                                  headers={'Authorization':'Bearer '+cfg['token'],'Content-Type':'application/json'},method='POST')
    try:
        with private_opener().open(request,timeout=30) as reply:
            proof=tuple(reply.headers.get('X-Four-Project-'+key,'') for key in ('MySQL-UUID','View-ID','Epoch','Mode','Membership','Controller-Boot-ID','Permit'))
            require(reply.status==204 and proof==permit and reply.headers.get('X-Four-Project-Durability')=='dual','Metadata dual durability could not be confirmed')
    except Exception:raise Closed('Metadata dual durability is unknown; inspect retained maintenance evidence, never blindly rerun') from None
    require(authority_dual()==permit,'Authority changed after metadata confirmation')
    return executed

class Mysql:
    def query(self,sql):
        # Credential-bearing SQL is stdin only; suppress all raw client output on
        # failure. Normal GR binary logging stays ON; no destructive reseeding.
        result=subprocess.run(['mysql','--defaults-file='+str(private(DEFAULTS)),'--batch','--raw','--skip-column-names','--skip-reconnect'],
                              input="SET SESSION sql_log_bin=1; SET SESSION time_zone='+00:00'; SET SESSION sql_mode=CONCAT_WS(',',@@SESSION.sql_mode,'NO_BACKSLASH_ESCAPES');\n"+sql+';\n',capture_output=True,text=True,timeout=90)
        require(result.returncode==0,'SQL phase failed; error codes '+','.join(re.findall(r'ERROR ([0-9]+)',result.stderr)))
        return [line.split('\t') for line in result.stdout.splitlines()]
    def scalar(self,sql):
        rows=self.query(sql);require(len(rows)==1 and len(rows[0])==1,'Scalar maintenance result required');return rows[0][0]

def inventory(db,projection=None):
    result={}
    for schema in sorted(ALL_SCHEMAS):
        result[schema]={}
        for (table,) in db.query('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='+literal(schema)+" AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"):
            if table in ('CLUSTER_ATOMIC_GUARD','CLUSTER_ATOMIC_CONFIG'):continue
            target=ident(schema)+'.'+ident(table)
            result[schema][table]={'rows':int(db.scalar('SELECT COUNT(*) FROM '+target))}
            if table=='CLUSTER_OPERATIONS' and schema in CANONICAL:
                columns=(projection or {}).get(schema,{}).get(table,{}).get('columns')
                if columns is None:columns=[r[0] for r in db.query('SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='+literal(schema)+" AND TABLE_NAME='CLUSTER_OPERATIONS' ORDER BY ORDINAL_POSITION")]
                fields=','.join("IFNULL(HEX(CAST("+ident(c)+" AS BINARY)),'-')" for c in columns)
                hashes=db.query("SELECT SHA2(CONCAT_WS(':',"+fields+"),256) FROM "+target+' ORDER BY ID')
                result[schema][table].update(columns=columns,rowProjectionSha256=hashlib.sha256('\n'.join(r[0] for r in hashes).encode()).hexdigest())
            else:result[schema][table]['checksum']=db.query('CHECKSUM TABLE '+target+' EXTENDED')[0][1]
    return result

def preflight(db):
    require(os.geteuid()==0,'Root maintenance identity required')
    permit=authority_dual()
    require(db.query('SELECT @@datadir,@@GLOBAL.general_log,@@GLOBAL.slow_query_log,@@GLOBAL.gtid_mode,@@SESSION.sql_log_bin,@@GLOBAL.require_secure_transport')==
            [['/var/lib/codex-validation/data/','0','0','ON','1','1']],'Exact scratch datadir, private logging, TLS and normal GTID replication required')
    members=db.query('SELECT MEMBER_STATE,MEMBER_ROLE FROM performance_schema.replication_group_members')
    require(len(members)==2 and all(row==['ONLINE','PRIMARY'] for row in members),'Both equal normal GR members must be ONLINE PRIMARY')
    require(db.scalar('SELECT @@GLOBAL.group_replication_communication_max_message_size')=='524288','Reviewed 512-KiB cross-cloud fragment policy required')
    require(db.scalar('SELECT @@server_uuid')==permit[0],'Authority must describe this exact local MySQL instance')
    node=urllib.parse.urlsplit(json.loads(AUTHORITY.read_text())['authorityUrl']).port-940
    require(db.query('SELECT @@GLOBAL.server_id,@@GLOBAL.auto_increment_offset,@@GLOBAL.auto_increment_increment,@@GLOBAL.group_replication_auto_increment_increment,'
                     '@@GLOBAL.group_replication_consistency,@@GLOBAL.innodb_flush_log_at_trx_commit,@@GLOBAL.sync_binlog,@@GLOBAL.innodb_doublewrite,'
                     '@@GLOBAL.log_bin,@@GLOBAL.log_replica_updates,@@GLOBAL.group_replication_communication_stack,@@GLOBAL.tls_version,'
                     '@@GLOBAL.group_replication_ssl_mode,@@GLOBAL.group_replication_recovery_use_ssl,@@GLOBAL.group_replication_recovery_ssl_verify_server_cert,'
                     '@@GLOBAL.group_replication_recovery_tls_version,@@GLOBAL.group_replication_recovery_get_public_key,'
                     '@@GLOBAL.ssl_ca=@@GLOBAL.group_replication_recovery_ssl_ca,@@GLOBAL.ssl_cert=@@GLOBAL.group_replication_recovery_ssl_cert,'
                     '@@GLOBAL.ssl_key=@@GLOBAL.group_replication_recovery_ssl_key,@@GLOBAL.read_only,@@GLOBAL.super_read_only,'
                     '@@GLOBAL.group_replication_single_primary_mode,@@GLOBAL.group_replication_enforce_update_everywhere_checks')==
            [[str(node),str(node),'7','7','BEFORE_AND_AFTER','1','1','ON','1','1','MYSQL','TLSv1.3','VERIFY_IDENTITY','1','1','TLSv1.3','0','1','1','1','0','0','0','1']],
            'Actual allocator, durable equal-writer and verified TLS policy required')
    for unit in ['mysql','tomcat','nginx','codex-lab-tomcat','codex-lab-nginx','codex-lab-entry-nginx','four-project-entry-nginx']:
        require(subprocess.run(['systemctl','is-active','--quiet',unit]).returncode!=0,'All old/formal application and entry units must be stopped')
    active=subprocess.check_output(['systemctl','list-units','--type=service','--state=active','--no-legend','--plain'],text=True)
    require(not re.search(r'codex-atomic-iafm-',active),'All Finance workers must be stopped')
    require(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER IN ('iafm_atomic','iafm_atomic_v2')")=='0','Old/new Finance writer sessions must be absent')
    require(not db.query('XA RECOVER'),'Legacy prepared work must be reconciled first')
    result=inventory(db)
    require(authority_dual()==permit,'Controller authority changed during maintenance preflight')
    return result

def rewrite_properties(original,value):
    replacements={'node1.user':value['username'],'node2.user':value['username'],'node1.password':value['password'],'node2.password':value['password'],'atomic.migrationId':value['migrationId']}
    counts={key:0 for key in replacements};result=[]
    for line in original.splitlines(keepends=True):
        key=line.split('=',1)[0]
        if key in replacements:
            counts[key]+=1;line=key+'='+replacements[key]+'\n'
        result.append(line)
    require(all(count==1 for count in counts.values()),'Each private runtime identity/marker key must appear exactly once')
    return ''.join(result)

def atomic_private(path,text,mode=0o600,group=None):
    path=Path(path);require(not path.is_symlink(),'Refuse a private symlink')
    temporary=path.with_name(path.name+'.revision-new');fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,mode)
    try:
        with os.fdopen(fd,'w') as output:output.write(text);output.flush();os.fsync(output.fileno())
        if group is not None:os.chown(temporary,0,group)
        os.replace(temporary,path)
        directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(directory)
        finally:os.close(directory)
    finally:temporary.unlink(missing_ok=True)

def canonical(db,value,old_migration,journal):
    require(str(uuid.UUID(old_migration))==old_migration and value['migrationId']!=old_migration,'Explicit original and distinct new migration UUIDs required')
    before=preflight(db)
    permit=authority_dual()
    def mutate(sql):
        require(authority_dual()==permit,'Maintenance authority changed before a metadata operation')
        return db.query(sql)
    require(not journal.exists(),'Existing migration evidence requires explicit investigation; do not blindly rerun')
    atomic_private(journal,json.dumps({'phase':'preflight','before':before,'newMigrationId':value['migrationId']})+'\n')
    for schema,peer in CANONICAL.items():
        require(db.query('SELECT PROTOCOL_VERSION,MIGRATION_ID,PRIMARY_SCHEMA,PEER_SCHEMA FROM '+ident(schema)+'.CLUSTER_ATOMIC_CONFIG WHERE ID=1')==
                [['1',old_migration,schema,peer]],'Original protocol-1 marker mismatch')
        require(db.scalar("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA="+literal(schema)+" AND TABLE_NAME='CLUSTER_ATOMIC_GUARD' AND COLUMN_NAME='DATA_REVISION'")=='0','Revision column already exists; inspect incomplete maintenance')
    for host in ['localhost','127.0.0.1']:
        require(db.scalar("SELECT COUNT(*) FROM mysql.user WHERE User='iafm_atomic_v2' AND Host="+literal(host))=='0','New account already exists; investigate partial maintenance')
    old_grantees=','.join(literal("'iafm_atomic'@'"+host+"'") for host in ['localhost','127.0.0.1'])
    grants=db.query('SELECT TABLE_SCHEMA,TABLE_NAME,PRIVILEGE_TYPE,GRANTEE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE IN ('+old_grantees+')')
    require(grants and all(s in ALL_SCHEMAS and p in ['SELECT','INSERT','UPDATE','DELETE'] for s,t,p,g in grants),'Original least-privilege grant shape required')
    for host in ['localhost','127.0.0.1']:
        require(db.scalar("SELECT ssl_type FROM mysql.user WHERE User='iafm_atomic' AND Host="+literal(host)) in ('','ANY'),'A stronger pre-existing client-certificate requirement needs explicit preservation')
        mutate('ALTER USER '+literal('iafm_atomic')+'@'+literal(host)+' ACCOUNT LOCK')
        mutate('CREATE USER '+literal(value['username'])+'@'+literal(host)+' IDENTIFIED BY '+literal(value['password'])+' REQUIRE SSL')
    grouped={}
    for schema,table,priv,grantee in grants:
        host=grantee.rsplit('@',1)[1];require(host in ("'localhost'","'127.0.0.1'"),'Unexpected grant host')
        grouped.setdefault((schema,table,host),set()).add(priv)
    for (schema,table,host),privileges in grouped.items():
        mutate('GRANT '+','.join(sorted(privileges))+' ON '+ident(schema)+'.'+ident(table)+' TO '+literal(value['username'])+'@'+host)
    for schema in CANONICAL:
        mutate('ALTER TABLE '+ident(schema)+'.CLUSTER_ATOMIC_GUARD ADD COLUMN DATA_REVISION BIGINT NOT NULL DEFAULT 0, ADD CONSTRAINT CLUSTER_ATOMIC_DATA_REVISION_NONNEGATIVE CHECK(DATA_REVISION>=0)')
        mutate('ALTER TABLE '+ident(schema)+'.CLUSTER_OPERATIONS ADD COLUMN ENDPOINT VARCHAR(160) NULL, ADD COLUMN DELETE_RESULT_HASH CHAR(64) NULL, ADD COLUMN DELETE_RESULT_EXPIRES DATETIME(6) NULL')
        mutate('UPDATE '+ident(schema)+'.CLUSTER_ATOMIC_CONFIG SET PROTOCOL_VERSION=2,MIGRATION_ID='+literal(value['migrationId'])+' WHERE ID=1 AND PROTOCOL_VERSION=1 AND MIGRATION_ID='+literal(old_migration))
        require(db.scalar('SELECT DATA_REVISION FROM '+ident(schema)+'.CLUSTER_ATOMIC_GUARD WHERE ID=1')=='0','Initial revision must be zero')
    require(inventory(db,before)==before,'Business/ID/receipt rows changed during metadata migration')
    executed=confirm_dual(db,permit)
    atomic_private(journal,json.dumps({'phase':'canonical-complete','before':before,'newMigrationId':value['migrationId'],'normalGrGtidSet':executed,'dualDurabilityConfirmed':True})+'\n')

def switch(db,value,node,journal):
    before=preflight(db)
    permit=authority_dual()
    require(db.scalar("SELECT COUNT(*) FROM mysql.user WHERE User='iafm_atomic' AND account_locked<>'Y'")=='0','Old writer credentials must already be locked')
    require(db.scalar("SELECT COUNT(*) FROM mysql.user WHERE User='iafm_atomic_v2'")=='2','Both replicated local-host accounts must exist')
    require(db.scalar("SELECT COUNT(*) FROM mysql.user WHERE User='iafm_atomic_v2' AND ssl_type<>'ANY'")=='0','New runtime TCP sessions must require TLS')
    new_grantees=','.join(literal("'iafm_atomic_v2'@'"+host+"'") for host in ['localhost','127.0.0.1'])
    require(db.scalar('SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES WHERE GRANTEE IN ('+new_grantees+") AND PRIVILEGE_TYPE<>'USAGE'")=='0','Unexpected global static privileges')
    for table,column in [('global_grants','USER'),('db','User'),('default_roles','USER'),('role_edges','TO_USER')]:
        require(db.scalar('SELECT COUNT(*) FROM mysql.'+ident(table)+' WHERE '+ident(column)+"='iafm_atomic_v2'")=='0','Unexpected global dynamic, role or database-wide privileges')
    for schema,peer in CANONICAL.items():
        require(db.query('SELECT PROTOCOL_VERSION,MIGRATION_ID,PRIMARY_SCHEMA,PEER_SCHEMA FROM '+ident(schema)+'.CLUSTER_ATOMIC_CONFIG WHERE ID=1')==
                [['2',value['migrationId'],schema,peer]],'Replicated protocol-2 marker mismatch')
    group=grp.getgrnam('tomcat').gr_gid;paths=[]
    for name,mode in [('iafm-cluster.properties',0o640),('iafm-worker-node'+str(node)+'.properties',0o600)]:
        path=Path('/var/lib/codex-validation/app-config')/name;info=path.lstat()
        require(stat.S_ISREG(info.st_mode) and info.st_uid==0 and stat.S_IMODE(info.st_mode)==mode,'Protected existing runtime file required')
        original=path.read_text();updated=rewrite_properties(original,value)
        # Historical idempotency lines are copied byte-for-byte; never rotate them.
        require([s for s in original.splitlines() if s.startswith('idempotency.secret=')]==[s for s in updated.splitlines() if s.startswith('idempotency.secret=')],'Historical HMAC key changed')
        require(authority_dual()==permit,'Authority changed before protected config switch')
        atomic_private(path,updated,mode,group if mode==0o640 else None);paths.append(str(path))
    require(inventory(db)==before,'Rows changed during config switch')
    confirm_dual(db,permit)
    atomic_private(journal,json.dumps({'phase':'node-config-switched','nodeId':node,'newMigrationId':value['migrationId'],'paths':paths,'rowsPreserved':True,'dualDurabilityConfirmed':True})+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['plan','prepare-private','preflight','canonical','switch']);p.add_argument('--private-file');p.add_argument('--node-id',type=int,choices=[1,2]);p.add_argument('--old-migration-id');p.add_argument('--journal');a=p.parse_args()
    if a.phase=='plan':print('Stop/verify both app entries and JVMs. Preflight both peers. Canonical phase ONCE on node1 normal GR. Wait/compare GTIDs and all business checksums. Switch protected configs on each node. Verify new runtime read-only and all protocol-2/CAS tests before reopening. No phase starts services.');return
    require(a.private_file,'Explicit private file required')
    if a.phase=='prepare-private':
        path=Path(a.private_file);require(path.is_absolute() and not path.exists(),'New absolute private output required');atomic_private(path,json.dumps({'username':'iafm_atomic_v2','password':secrets.token_urlsafe(48),'migrationId':str(uuid.uuid4())})+'\n');print('Shared private maintenance material prepared; no SQL executed.');return
    value=credentials(a.private_file);db=Mysql()
    if a.phase=='preflight':before=preflight(db);print(json.dumps({'preflight':True,'schemas':sorted(before),'rowsPreserved':True,'normalGrDual':True}));return
    require(a.journal and Path(a.journal).is_absolute(),'Explicit owner-private maintenance evidence path required')
    if a.phase=='canonical':require(a.node_id==1,'Canonical migration runs only on the selected maintenance node1');canonical(db,value,a.old_migration_id,Path(a.journal))
    else:require(a.node_id in (1,2),'Physical node ID required');switch(db,value,a.node_id,Path(a.journal))
    print(json.dumps({'phase':a.phase,'completed':True,'servicesStarted':False,'credentialValuesLogged':False}))

if __name__=='__main__':
    try:main()
    except Exception as failure:raise SystemExit('Finance protocol-2 maintenance refused: '+(str(failure) if isinstance(failure,Closed) else type(failure).__name__))
