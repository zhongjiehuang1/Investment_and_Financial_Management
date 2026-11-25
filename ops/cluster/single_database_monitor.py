#!/usr/bin/env python3
"""Read-only single-production-MySQL readiness and capacity monitor.
No replication, write permits, fencing, SQL mutations or automatic takeover.
"""
import argparse,hashlib,hmac,json,os,socket,stat,subprocess,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
FLOOR=4_000_000_000
SQL="SET SESSION time_zone='+00:00'; SELECT JSON_ARRAY(@@server_uuid,@@server_id,@@port,@@global.read_only,@@global.super_read_only,@@session.sql_log_bin,@@global.log_bin,@@global.innodb_flush_log_at_trx_commit,@@global.sync_binlog,@@global.innodb_doublewrite,(SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_STATE='ONLINE'),@@global.gtid_mode,@@global.enforce_gtid_consistency,@@global.require_secure_transport,@@datadir);"
_artifact_cache={}
def artifact_hash(path):
 p=Path(path);s=p.lstat()
 if not stat.S_ISREG(s.st_mode) or p.is_symlink() or s.st_uid!=0 or s.st_mode&0o022:raise ValueError('Trusted artifact required')
 identity=(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
 previous=_artifact_cache.get(str(p))
 if previous and previous[0]==identity:return previous[1]
 h=hashlib.sha256()
 with p.open('rb')as f:
  for block in iter(lambda:f.read(1048576),b''):h.update(block)
 after=p.lstat()
 if identity!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns):raise ValueError('Artifact changed')
 digest=h.hexdigest();_artifact_cache[str(p)]=(identity,digest);return digest
def private(path):
 p=Path(path);s=p.lstat()
 if p.is_symlink() or not stat.S_ISREG(s.st_mode) or s.st_uid!=os.geteuid() or stat.S_IMODE(s.st_mode)!=0o600:raise ValueError('Protected file required')
 return p

def load(path):
 p=private(path);c=json.loads(p.read_text())
 if set(c)!={'nodeId','mysqlUuid','mysqlPort','mysqlClient','clientOptions','tokenFile','listenPort','reserveSocket','capacityValidationFile','capacityValidationSha256','dataPath','capacityArtifacts'} or c['nodeId']!=2 or c['mysqlPort']!=332 or c['listenPort']!=942:raise ValueError('Pinned single database configuration required')
 if str(uuid.UUID(c['mysqlUuid']))!=c['mysqlUuid']:raise ValueError('Expected MySQL identity required')
 private(c['clientOptions']);private(c['tokenFile'])
 binary=Path(c['mysqlClient']);s=binary.stat()
 if not binary.is_absolute() or not stat.S_ISREG(s.st_mode) or s.st_uid!=0 or s.st_mode&0o022:raise ValueError('Trusted MySQL client required')
 return c

def database_ready(values,c):
 return (isinstance(values,list) and len(values)==15 and values[0]==c['mysqlUuid'] and values[1:3]==[2,c['mysqlPort']]
  and values[3:9]==[0,0,1,1,1,1] and str(values[9]).upper() in ('1','ON','TRUE') and values[10]==0
  and values[11:13]==['ON','ON'] and values[13]==1 and Path(values[14]).resolve()==Path(c['dataPath']).resolve())

def capacity_validated(c):
 try:
  path=c.get('capacityValidationFile');expected=c.get('capacityValidationSha256')
  if not path or not expected:return False
  source=private(path);raw=source.read_bytes()
  if hashlib.sha256(raw).hexdigest()!=expected:return False
  proof=json.loads(raw)
  return (proof.get('status')=='PASS' and proof.get('mysqlUuid')==c['mysqlUuid'] and proof.get('topology')=='single-database'
          and proof.get('physicalBytesPerPayloadByte')==6 and proof.get('fixedOverheadBytes')==30_044_160
          and proof.get('ordinaryExt4FloorVerified') is True and proof.get('actualFileLifecycleMeasured') is True
          and proof.get('validationScope')=='bounded-file-lifecycle-and-ext4-floor'
          and proof.get('fullQuotaCapacityValidated') is False and proof.get('allocationEstimateIsWorstCaseGuarantee') is False
          and proof.get('dataFilesystemDevice')==os.stat(c['dataPath']).st_dev
          and proof.get('artifacts')==c['capacityArtifacts']
          and bool(c['capacityArtifacts'])
          and all(artifact_hash(path)==digest for path,digest in c['capacityArtifacts'].items()))
 except (OSError,ValueError,TypeError):return False

def observe(c):
 started=int(time.time()*1000)
 result={'nodeId':2,'mysqlUuid':c['mysqlUuid'],'topology':'single-database','mode':'single','mysqlReady':False,'reserveGuaranteed':False,'capacityModelValidated':False,'floorBytes':FLOOR,'globalReserveEnforcement':False,'physicalBytesPerPayloadByte':6,'fixedOverheadBytes':30_044_160,'volumes':[],'databaseFailoverAvailable':False,'capacityValidationScope':'bounded-file-lifecycle-and-ext4-floor','fullQuotaCapacityValidated':False,'allocationEstimateIsWorstCaseGuarantee':False}
 try:
  r=subprocess.run([c['mysqlClient'],'--defaults-extra-file='+c['clientOptions'],'--no-login-paths','--connect-timeout=1','--batch','--raw','--skip-column-names','--execute',SQL],capture_output=True,timeout=3)
  if r.returncode==0:result['mysqlReady']=database_ready(json.loads(r.stdout),c)
 except (OSError,ValueError,subprocess.TimeoutExpired):pass
 try:
  with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)as s:
   s.settimeout(2);s.connect(c['reserveSocket']);s.sendall(b'status\n');raw=s.makefile('rb').readline(131073)
  if len(raw)>131072:raise ValueError('Oversized reserve proof')
  value=json.loads(raw)
  if value.get('guaranteed') is True and value.get('floorBytes')==FLOOR and value.get('boundaryPolicy')=='ext4-root-plus-kernel-v1' and isinstance(value.get('volumes'),list) and value['volumes']:
   volumes=value['volumes']
   reserve_config=json.loads(Path('/var/lib/codex-validation/system-safety/reserve.json').read_text())
   configured=reserve_config['volumes'];data_device=os.stat(c['dataPath']).st_dev
   bound=(len(configured)==len(volumes) and {v['id']for v in configured}=={v['id']for v in volumes}
          and any(os.stat(v['path']).st_dev==data_device for v in configured))
   if bound and all(isinstance(v.get('availableBytes'),int) and v['availableBytes']>=FLOOR for v in volumes):
    result.update(reserveGuaranteed=True,capacityModelValidated=capacity_validated(c),boundaryPolicy=value['boundaryPolicy'],volumes=volumes,protectionScope=value.get('scope','ordinary allocations only'))
 except (OSError,ValueError):pass
 result['observedAtMillis']=started;result['ready']=result['mysqlReady'] and result['reserveGuaranteed'] and result['capacityModelValidated']
 return result

class Cache:
 def __init__(self,c):self.c=c;self.value=None;self.at=0;self.lock=threading.Lock();self.stop=threading.Event()
 def loop(self):
  while not self.stop.is_set():
   value=observe(self.c)
   with self.lock:self.value=value;self.at=time.monotonic()
   self.stop.wait(1)
 def get(self):
  with self.lock:
   if self.value is None or not 0<=time.monotonic()-self.at<=4:return None
   return dict(self.value)

def handler(cache,token):
 class Handler(BaseHTTPRequestHandler):
  protocol_version='HTTP/1.1'
  def setup(self):
   super().setup();self.connection.settimeout(15)
  def log_message(self,*args):pass
  def do_GET(self):
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):self.send_error(403);return
   if self.path not in ('/health','/status','/capacity'):self.send_error(404);return
   value=cache.get()
   if value is None:self.send_error(503);return
   if self.path=='/health':
    self.send_response(204 if value.get('ready') is True else 503);self.send_header('Cache-Control','no-store');self.send_header('Content-Length','0');self.end_headers();return
   raw=json.dumps(value,separators=(',',':')).encode();self.send_response(200 if value.get('ready') is True else 503);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
 return Handler

def main():
 p=argparse.ArgumentParser();p.add_argument('--config',required=True);a=p.parse_args();c=load(a.config);token=private(c['tokenFile']).read_text().strip()
 if len(token)<32:raise ValueError('Private probe token required')
 cache=Cache(c);t=threading.Thread(target=cache.loop,daemon=True);t.start();server=ThreadingHTTPServer(('127.0.0.1',c['listenPort']),handler(cache,token));server.daemon_threads=True
 try:server.serve_forever()
 finally:cache.stop.set();server.server_close();t.join(timeout=5)
if __name__=='__main__':main()
