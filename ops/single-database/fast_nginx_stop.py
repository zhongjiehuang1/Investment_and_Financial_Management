#!/usr/bin/env python3
"""One audited, bounded stop of the known Nginx unit; never replay on failure."""
import json,os,pathlib,re,subprocess,sys,time
UNIT='codex-lab-nginx.service';CGROUP=pathlib.Path('/sys/fs/cgroup/system.slice')/UNIT
PROPS='LoadState,ActiveState,SubState,MainPID,ControlPID,ControlGroup,Job,InvocationID,UnitFileState,Restart'
def require(ok):
 if not ok:raise RuntimeError('bounded nginx stop guard refused')
def command(*args):
 result=subprocess.run(['systemctl',*args],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=8)
 require(result.returncode==0)
 return result.stdout.decode()
def state(cmd):
 raw=cmd('show',UNIT,'--property='+PROPS)
 return dict(x.split('=',1) for x in raw.splitlines() if '=' in x)
def empty_cgroup():
 if not CGROUP.exists():return True
 return all(not p.read_text().strip() for p in CGROUP.rglob('cgroup.procs'))
def stop(cmd=command,is_empty=empty_cgroup,clock=time.monotonic,pause=time.sleep):
 before=state(cmd);invocation=before.get('InvocationID','');pid=before.get('MainPID','')
 require(before.get('LoadState')=='loaded' and before.get('UnitFileState')=='enabled' and before.get('Restart')=='no')
 require(before.get('ActiveState')=='active' and before.get('SubState')=='running' and before.get('ControlPID')=='0' and before.get('Job')=='')
 require(re.fullmatch('[0-9a-f]{32}',invocation) is not None and pid.isdecimal() and int(pid)>0)
 require(before.get('ControlGroup')=='/system.slice/'+UNIT)
 cmd('stop','--no-block',UNIT)
 deadline=clock()+12;signalled=False
 while clock()<deadline:
  current=state(cmd)
  require(current.get('LoadState')=='loaded' and current.get('UnitFileState')=='enabled' and current.get('InvocationID')==invocation and current.get('Restart')=='no')
  if current.get('ActiveState')=='inactive':
   require(current.get('SubState')=='dead' and current.get('MainPID')=='0' and current.get('ControlPID')=='0' and current.get('Job')=='' and is_empty())
   return {'status':'PASS','unit':UNIT,'signalSent':signalled,'invocationId':invocation}
  if current.get('ActiveState')=='active' and current.get('Job') and current.get('MainPID')==pid and current.get('ControlPID')=='0' and current.get('ControlGroup')=='/system.slice/'+UNIT:
   pause(.2);continue
  if current.get('ActiveState')=='deactivating' and current.get('MainPID')=='0' and current.get('Job') and current.get('ControlGroup') in ('','/system.slice/'+UNIT):
   pause(.2);continue
  require(current.get('ActiveState')=='deactivating' and current.get('MainPID')==pid and current.get('ControlGroup')=='/system.slice/'+UNIT and current.get('Job'))
  control=current.get('ControlPID','');require(control.isdecimal())
  if control!='0':pause(.2);continue
  if not signalled:
   cmd('kill','--kill-who=main','--signal=SIGTERM',UNIT);signalled=True
  pause(.2)
 raise RuntimeError('bounded nginx stop timed out; outcome unknown')
if __name__=='__main__':
 require(os.geteuid()==0 and len(sys.argv)==1)
 print(json.dumps(stop(),sort_keys=True))
