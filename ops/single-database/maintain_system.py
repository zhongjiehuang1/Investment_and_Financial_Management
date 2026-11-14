#!/usr/bin/env python3
"""Two-node maintenance: default offline plan. No failover or database cleanup."""
import argparse,datetime,hashlib,json,os,pathlib,re,signal,stat,subprocess,sys,time,uuid
P=pathlib.Path;D=P(__file__).resolve().parent;REMOTE=D/'maintenance_remote.py'
HOME=P.home();ROOT=HOME/'Documents/Server_Recovery_Work/Single_Database_Maintenance'
S1=['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','root@8.211.120.109']
S2=['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-i',str(HOME/'Documents/SSH/LightsailDefaultKey-eu-west-2.pem'),'ubuntu@13.42.147.134']
def interrupted(*_):raise InterruptedError('maintenance interrupted')
def owner_private(d):
 s=d.lstat();return stat.S_ISDIR(s.st_mode) and not d.is_symlink() and s.st_uid==os.getuid() and stat.S_IMODE(s.st_mode)==0o700
def emit(file,event):
 raw=(json.dumps(event,sort_keys=True)+'\n').encode();file.write(raw);file.flush();os.fsync(file.fileno())
def call(node,phase,code,socket=None):
 assert node in (1,2) and phase in ('preflight','stop-app','assert-stopped','stop-db','clean','start-db','start-app','verify')
 args=list(S1 if node==1 else S2)
 if node==1 and socket:args[1:1]=['-S',socket]
 remote=('' if node==1 else 'sudo -n ')+'python3 -B - '+str(node)+' '+phase
 started=time.monotonic();p=subprocess.run(args+[remote],input=code,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=900)
 try:v=json.loads(p.stdout)
 except (ValueError,UnicodeError):v={}
 if p.returncode or v.get('status')!='PASS' or v.get('phase')!=phase or v.get('node')!=node:raise RuntimeError('remote phase refused or unknown')
 return {'node':node,'phase':phase,'status':'PASS','durationMs':round((time.monotonic()-started)*1000),**({'oldProjectLogFilesRemoved':v['oldProjectLogFilesRemoved']} if 'oldProjectLogFilesRemoved' in v else {})}
def main():
 a=argparse.ArgumentParser(description=__doc__);a.add_argument('--execute',action='store_true');a.add_argument('--server1-control-socket');a.add_argument('--expected-worker-sha256');args=a.parse_args()
 if not args.execute:
  print(json.dumps({'mode':'OFFLINE_PLAN_ONLY','networkCalls':0,'filesChanged':False,'phases':['preflight both hosts','stop app entries and Tomcats','stop Server 2 monitor and MySQL','clean apt cache and old journal / eligible project logs','start Server 2 MySQL and monitor','start both app stacks and verify local readiness'],'doesNotDelete':['databases','user files','keys','old datadirs','test schemas'],'limitation':'No independent recovery if the Mac process is killed or network remains unavailable; inspect and restore manually.'}));return
 if not args.expected_worker_sha256 or not re.fullmatch('[0-9a-f]{64}',args.expected_worker_sha256):raise ValueError('reviewed worker hash required')
 code=REMOTE.read_bytes()
 if hashlib.sha256(code).hexdigest()!=args.expected_worker_sha256:raise ValueError('worker hash changed')
 if args.server1_control_socket and (not P(args.server1_control_socket).is_absolute() or not P(args.server1_control_socket).exists()):raise ValueError('control socket not found')
 ROOT.mkdir(mode=0o700,parents=True,exist_ok=True)
 if not owner_private(ROOT):raise ValueError('owner-private work directory required')
 lock=ROOT/'maintenance-exclusive.lock';lock.mkdir(mode=0o700);run=str(uuid.uuid4());folder=ROOT/('run-'+run);folder.mkdir(mode=0o700)
 fd=os.open(folder/'events.jsonl',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);started=False;failed=False;restored=False;recovery_attempted=False
 signal.signal(signal.SIGTERM,interrupted)
 with os.fdopen(fd,'wb') as events:
  emit(events,{'runId':run,'state':'INTENT','workerSha256':args.expected_worker_sha256,'unknownMustNotReplay':True})
  try:
   for node in (1,2):emit(events,call(node,'preflight',code,args.server1_control_socket))
   # Write intent before dispatch so a lost response remains explicitly unknown.
   started=True
   for node,phase in ((1,'stop-app'),(2,'stop-app'),(1,'assert-stopped'),(2,'assert-stopped'),(2,'stop-db'),(1,'clean'),(2,'clean')):
    emit(events,{'node':node,'phase':phase,'status':'DISPATCHING_OR_UNKNOWN'});emit(events,call(node,phase,code,args.server1_control_socket))
  except BaseException as error:
   failed=True;emit(events,{'phase':'operation','status':'FAILED_OR_UNKNOWN','errorClass':type(error).__name__})
  finally:
   if started and not failed:
    # A lost remote response may leave a stop job running. Never start a
    # competing operation after any unknown or failed phase.
    recovery_ok=True;recovery_attempted=True
    for node,phase in ((2,'start-db'),(1,'start-app'),(2,'start-app'),(1,'verify'),(2,'verify')):
     try:emit(events,{'node':node,'phase':phase,'status':'RECOVERY_DISPATCHING_OR_UNKNOWN'});emit(events,call(node,phase,code,args.server1_control_socket))
     except BaseException as error:
      recovery_ok=False;emit(events,{'node':node,'phase':phase,'status':'FAILED_OR_UNKNOWN','errorClass':type(error).__name__});break
    restored=recovery_ok
   else:restored=not started
   status='PASS' if not failed and restored else 'INCOMPLETE_RECONCILE_MANUALLY'
   emit(events,{'runId':run,'status':status,'restoreAttempted':recovery_attempted,'localReadinessVerified':bool(started and restored),'unknownMustNotReplay':status!='PASS'})
 if status=='PASS':lock.rmdir()
 print(json.dumps({'status':status,'runId':run,'receipt':str(folder/'events.jsonl'),'lockRetained':status!='PASS'}))
 if status!='PASS':raise SystemExit(1)
if __name__=='__main__':main()
