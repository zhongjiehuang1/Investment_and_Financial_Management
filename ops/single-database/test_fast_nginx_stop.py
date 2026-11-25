#!/usr/bin/env python3
"""Mock the systemd command boundary; never touches systemctl or a server."""
import importlib.util,pathlib
p=pathlib.Path(__file__).with_name('fast_nginx_stop.py');spec=importlib.util.spec_from_file_location('fast_nginx_stop',p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
base={'LoadState':'loaded','UnitFileState':'enabled','Restart':'no','ActiveState':'active','SubState':'running','ControlPID':'0','MainPID':'123','Job':'','ControlGroup':'/system.slice/'+m.UNIT,'InvocationID':'a'*32}
dead={**base,'ActiveState':'inactive','SubState':'dead','MainPID':'0','ControlGroup':''}
pending={**base,'Job':'9 /org/freedesktop/systemd1/job/9'}
stopping={**base,'ActiveState':'deactivating','SubState':'stop-sigterm','Job':'9 /org/freedesktop/systemd1/job/9'}
def run(states,*,empty=True,fail=()):
 calls=[];ticks=[0.0];queue=list(states)
 def cmd(*args):
  calls.append(args)
  if args[0] in fail:raise RuntimeError('simulated ambiguous failure')
  if args[0]=='show':
   s=queue.pop(0) if len(queue)>1 else queue[0];return '\n'.join(f'{k}={v}' for k,v in s.items())
  return ''
 def pause(n):ticks[0]+=n
 try:r=m.stop(cmd,lambda:empty,lambda:ticks[0],pause);return r,calls,None
 except Exception as e:return None,calls,e
r,c,e=run([base,dead]);assert not e and r['signalSent']==False and [a[0] for a in c].count('kill')==0
r,c,e=run([base,pending,{**stopping,'ControlPID':'456'},stopping,{**stopping,'MainPID':'0'},dead]);assert not e and r['signalSent']==True and [a[0] for a in c].count('kill')==1
assert ('kill','--kill-who=main','--signal=SIGTERM',m.UNIT) in c
for states,opts in [([base,{**stopping,'InvocationID':'b'*32}],{}),([base,{**stopping,'MainPID':'456'}],{}),([base,{**dead,'Job':'1 /job'}],{}),([base,dead],{'empty':False}),([base,stopping],{'fail':('kill',)}),([base,stopping],{'fail':('stop',)}),([base,stopping],{})]:
 r,c,e=run(states,**opts);assert e and not r;assert [a[0] for a in c].count('stop')<=1 and [a[0] for a in c].count('kill')<=1
r,c,e=run([{**base,'Restart':'always'}]);assert e and not any(a[0] in ('stop','kill') for a in c)
r,c,e=run([{**base,'Job':'9 /job'}]);assert e and not any(a[0] in ('stop','kill') for a in c)
print('fast stop mocked: immediate graceful, pending+TERM, changed identity/PID, jobs/cgroup, unknown failures and bounded timeout: PASS')
