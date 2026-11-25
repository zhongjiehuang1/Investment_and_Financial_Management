"""Canonical ordered data digest for a sandboxed MySQL restore.

Importing this module has no effects. The caller must independently prove that
socket and datadir are owned by the isolated instance and no production files
are visible. It is not a standalone restore launcher or an isolation proof.
"""
import hashlib, os, pathlib, re, stat, subprocess, threading


def data_digest(defaults_file, schemas, timeout=1800):
    names=sorted(schemas)
    if not names or len(set(names)) != len(names) or any(not re.fullmatch(r'[A-Za-z0-9_]+',v) or v in {'mysql','sys','information_schema','performance_schema'} for v in names):
        raise ValueError('invalid isolated schema list')
    args=['/usr/bin/mysqldump','--defaults-file='+str(defaults_file),'--no-login-paths','--no-create-info','--skip-triggers','--skip-routines','--skip-events','--skip-extended-insert','--complete-insert','--hex-blob','--order-by-primary','--skip-comments','--skip-add-locks','--skip-disable-keys','--skip-set-charset','--skip-dump-date','--set-gtid-purged=OFF','--no-tablespaces','--column-statistics=0','--single-transaction','--skip-lock-tables','--tz-utc','--max-allowed-packet=64M','--databases']+names
    proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    timer=threading.Timer(timeout,proc.kill);timer.daemon=True;timer.start();h=hashlib.sha256()
    try:
        while True:
            block=proc.stdout.read(1048576)
            if not block:break
            h.update(block)
        if proc.wait(timeout=timeout)!=0:raise RuntimeError('isolated data fingerprint failed')
        return h.hexdigest()
    finally:timer.cancel()


def schema_digest(defaults_file, schemas, timeout=1800, output_path=None):
    names=sorted(schemas)
    if not names or len(set(names)) != len(names) or any(not re.fullmatch(r'[A-Za-z0-9_]+',v) or v in {'mysql','sys','information_schema','performance_schema'} for v in names):
        raise ValueError('invalid isolated schema list')
    args=['/usr/bin/mysqldump','--defaults-file='+str(defaults_file),'--no-login-paths','--no-data','--skip-triggers','--skip-routines','--skip-events','--skip-comments','--skip-add-locks','--skip-disable-keys','--skip-set-charset','--skip-dump-date','--set-gtid-purged=OFF','--no-tablespaces','--column-statistics=0','--single-transaction','--skip-lock-tables','--tz-utc','--max-allowed-packet=64M','--databases']+names
    output=None
    if output_path is not None:
        target=pathlib.Path(output_path)
        if not target.is_absolute() or target.parent.is_symlink():raise ValueError('private output path required')
        st=target.parent.lstat()
        if not stat.S_ISDIR(st.st_mode) or st.st_uid!=os.getuid() or stat.S_IMODE(st.st_mode)!=0o700:raise ValueError('private output directory required')
        fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        output=os.fdopen(fd,'wb')
    try:proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    except BaseException:
        if output is not None:output.close()
        raise
    timer=threading.Timer(timeout,proc.kill);timer.daemon=True;timer.start();h=hashlib.sha256()
    try:
        while True:
            block=proc.stdout.read(1048576)
            if not block:break
            h.update(block)
            if output is not None:output.write(block)
        if proc.wait(timeout=timeout)!=0:raise RuntimeError('isolated schema fingerprint failed')
        if output is not None:output.flush();os.fsync(output.fileno())
        return h.hexdigest()
    finally:
        timer.cancel()
        try:
            if proc.poll() is None:
                try:proc.kill()
                except ProcessLookupError:pass
            proc.wait()
        finally:
            proc.stdout.close()
            if output is not None:output.close()
