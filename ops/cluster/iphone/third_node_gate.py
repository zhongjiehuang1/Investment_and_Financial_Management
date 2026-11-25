#!/usr/bin/env python3
"""Fail-closed admission for iPhone compute contexts.

Requires a separately provisioned, pinned root-owned monitor SSH forward and
each admitted context's independently authenticated recovery endpoint.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import socket
import stat
import sys
import threading
import time
import uuid
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from lab_entry import APPS, CAPACITY_FLOOR_BYTES, Closed, CONTEXT, RECOVERY, Reply, _unique_object, require

ROOT=Path('/etc/iphone-node3')
CONFIG=ROOT/'admission.json'
APP_CONFIG=Path('/home/server/.local/iphone-deploy-20261121/tomcat-db-base/private')
KNOWN=ROOT/'server2_known_hosts'
KEY=ROOT/'monitor_ed25519'
HOST='13.42.147.134'
FINGERPRINT='ulVNFf+vuQGCn2adL2qzWJ12+XQq2UL4OuwZAzp08iU'
SSH_ARGS=['/usr/bin/ssh','-F','/dev/null','-N','-T','-o','BatchMode=yes','-o','IdentitiesOnly=yes',
          '-o','ExitOnForwardFailure=yes','-o','StrictHostKeyChecking=yes',
          '-o','UserKnownHostsFile='+str(KNOWN),'-o','GlobalKnownHostsFile=/dev/null',
          '-o','ConnectTimeout=20','-o','ServerAliveInterval=30','-o','ServerAliveCountMax=3',
          '-o','KexAlgorithms=curve25519-sha256','-i',str(KEY),
          '-L','127.0.0.1:942:127.0.0.1:1942','codex-peer@'+HOST]

def root_file(path,private=False):
    info=path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid==0 and not (stat.S_IMODE(info.st_mode)&0o022),
            'Root-owned non-writable regular file required')
    if private:require(stat.S_IMODE(info.st_mode)==0o600,'Root-private file required')

def root_parents(path):
    for parent in path.parents:
        info=parent.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid==0 and not (stat.S_IMODE(info.st_mode)&0o022),
                'Root-owned non-writable directory required')

def validate_config(value):
    require(type(value) is dict and set(value)=={'format','mysqlUuid','monitorToken','recoveryTokens'},'Exact node-3 config required')
    require(value['format']=='iphone-compute-admission-v2','Explicit node-3 opt-in required')
    require(str(uuid.UUID(value['mysqlUuid']))==value['mysqlUuid'],'Canonical database UUID required')
    token=lambda s:isinstance(s,str) and re.fullmatch(r'[A-Za-z0-9_-]{32,}',s)
    require(token(value['monitorToken']),'Private monitor token required')
    recovery=value['recoveryTokens']
    require(type(recovery) is dict and recovery and set(recovery)<=set(APPS[:-1]) and all(token(v) for v in recovery.values()),'Private per-application recovery tokens required')
    require(len({value['monitorToken'], *recovery.values()})==len(recovery)+1,'Use distinct monitor and recovery credentials')
    return value

def load_config():
    root_parents(CONFIG);root_file(CONFIG,True)
    fd=os.open(CONFIG,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        info=os.fstat(fd);require(stat.S_ISREG(info.st_mode) and info.st_uid==0 and stat.S_IMODE(info.st_mode)==0o600 and info.st_size<=65536,'Private config changed')
        with os.fdopen(fd,'rb') as f:
            fd=-1;return validate_config(json.load(f,object_pairs_hook=_unique_object))
    finally:
        if fd>=0:os.close(fd)


def read_private_text(path, owner):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as entry:
        info = os.fstat(entry.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == owner
                and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 65536,
                'Explicit private admission input required')
        return entry.read(65537).decode('ascii')


def initialize_admission():
    root_parents(CONFIG)
    app_owner = pwd.getpwnam('server').pw_uid
    monitor = read_private_text(ROOT / 'monitor.token', 0).strip()
    recovery = {}
    for app in APPS[:-1]:
        path = APP_CONFIG / f'{app}-recovery.token'
        if app == 'iafm' or path.exists() or path.is_symlink():
            recovery[app] = read_private_text(path, app_owner).strip()
    source = read_private_text(APP_CONFIG / 'iafm-cluster.properties', app_owner)
    identities = re.findall(r'(?m)^atomic\.mysqlUuid=([a-f0-9-]+)$', source)
    require(len(identities) == 1, 'One configured MySQL UUID required')
    config = validate_config({'format': 'iphone-compute-admission-v2', 'mysqlUuid': identities[0],
                              'monitorToken': monitor, 'recoveryTokens': recovery})
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    with os.fdopen(os.open(CONFIG, flags, 0o600), 'w') as destination:
        json.dump(config, destination, sort_keys=True)
        destination.write('\n')
        destination.flush()
        os.fsync(destination.fileno())
    print('Private admission configuration initialized.')

def known_key(path=KNOWN,expected=FINGERPRINT):
    root_parents(path);root_file(path)
    lines=path.read_text().splitlines();require(len(lines)==1,'Exactly one pinned server host key required')
    parts=lines[0].split();require(len(parts)>=3 and parts[0]==HOST and parts[1]=='ssh-ed25519','Pinned host key entry required')
    try: digest=base64.b64encode(hashlib.sha256(base64.b64decode(parts[2],validate=True)).digest()).decode().rstrip('=')
    except Exception as failure:raise Closed('Invalid pinned host key') from failure
    require(digest==expected,'Pinned SSH host fingerprint mismatch')

def listener_inodes(proc=Path('/proc'),port=942):
    found=[]
    for table in ('tcp','tcp6'):
        path=proc/'net'/table
        if not path.exists():continue
        for line in path.read_text().splitlines()[1:]:
            fields=line.split()
            if len(fields)<10 or fields[3]!='0A':continue
            address,number=fields[1].split(':')
            if int(number,16)!=port:continue
            require(table=='tcp' and address=='0100007F','Monitor must bind only IPv4 loopback')
            found.append(fields[9])
    require(len(found)==1,'Exactly one monitor listener required')
    return found[0]

_monitor_scan_lock = threading.Lock()


def monitor_owner(proc, inode):
    socket_link = f'socket:[{inode}]'
    owner = None
    with os.scandir(proc) as processes:
        for process in processes:
            if not (process.name.isascii() and process.name.isdecimal()):
                continue
            try:
                with os.scandir(os.path.join(process.path, 'fd')) as descriptors:
                    for descriptor in descriptors:
                        # Linux exposes each numeric descriptor as a link. Read it directly.
                        if not (descriptor.name.isascii() and descriptor.name.isdecimal()):
                            continue
                        try:
                            destination = os.readlink(descriptor.path)
                        except FileNotFoundError:
                            continue  # A descriptor may close during iteration.
                        if destination == socket_link:
                            require(owner is None, 'Single monitor SSH process required')
                            owner = Path(process.path), Path(descriptor.path)
                            break  # Additional descriptors in this PID share its ownership.
            except FileNotFoundError:
                continue  # A process may exit before its descriptor directory opens.
    require(owner is not None, 'Single monitor SSH process required')
    return owner


def monitor_start(process):
    stat_line = (process / 'stat').read_text()
    require(') ' in stat_line, 'Monitor process incarnation unavailable')
    fields = stat_line.rsplit(') ', 1)[1].split()
    require(len(fields) > 19 and fields[19].isdigit(), 'Monitor process start time required')
    return fields[19]


def verify_monitor(proc=Path('/proc')):
    # Concurrent scans contend for the emulated CPU. Each call still reads fresh state.
    with _monitor_scan_lock:
        known_key()
        root_file(KEY, True)
        root_file(Path('/usr/bin/ssh'))
        minimum = proc / 'sys/net/ipv4/ip_unprivileged_port_start'
        require(int(minimum.read_text().strip()) >= 946, 'Privileged listener boundary required')
        inode = listener_inodes(proc)
        process, descriptor = monitor_owner(proc, inode)
        started = monitor_start(process)
        status = (process / 'status').read_text()
        require(re.search(r'(?m)^Uid:\s+0\s+0\s+0\s+0\s*$', status) is not None,
                'Root-owned monitor process required')
        require(os.readlink(process / 'exe') == '/usr/bin/ssh', 'Pinned SSH executable required')
        args = (process / 'cmdline').read_bytes().rstrip(b'\0').split(b'\0')
        require(args == [arg.encode() for arg in SSH_ARGS], 'Exact pinned monitor SSH command required')
        require(os.readlink(descriptor) == f'socket:[{inode}]'
                and monitor_start(process) == started and listener_inodes(proc) == inode,
                'Monitor socket or process changed during verification')
        return process.name, inode, started


class ScopedAdmission:
    """One recorded completion time and two-proof streak for each app."""
    def __init__(self, apps, clock=time.monotonic):
        self.apps = frozenset(apps)
        require(self.apps and self.apps <= set(APPS[:-1]), 'Known compute applications required')
        self.clock = clock
        self.lock = threading.Lock()
        self.generation = 0
        self.incarnation = None
        self.samples = {}

    def ticket(self):
        with self.lock:
            return self.generation

    def bind(self, ticket, incarnation):
        with self.lock:
            if ticket != self.generation:
                return None
            if self.incarnation != incarnation:
                self.generation += 1
                self.incarnation = incarnation
                self.samples.clear()
            return self.generation

    def monitor_failed(self, ticket):
        with self.lock:
            if ticket == self.generation:
                self.generation += 1
                self.incarnation = None
                self.samples.clear()

    def app_failed(self, app, ticket):
        with self.lock:
            if ticket == self.generation:
                self.samples.pop(app, None)

    def complete(self, app, ticket, fingerprint, completed_at):
        with self.lock:
            if app not in self.apps or ticket != self.generation:
                return 'discarded'
            if not fingerprint or not 0 <= self.clock() - completed_at <= 15:
                self.samples.pop(app, None)
                return 'stale'
            previous = self.samples.get(app)
            if previous is not None and completed_at <= previous[0]:
                return 'discarded'
            streak = min(2, previous[2] + 1) if previous is not None and fingerprint == previous[1] else 1
            self.samples[app] = (completed_at, fingerprint, streak)
            return 'admitted' if streak == 2 else 'waiting-second-proof'

    def locally_admitted(self, app, node_id):
        with self.lock:
            sample = self.samples.get(app)
            return (type(node_id) is int and node_id == 3 and sample is not None
                    and sample[2] == 2 and 0 <= self.clock() - sample[0] <= 15)


_PROBE_PATHS = {
    942: frozenset(('/status',)),
    18082: frozenset(path for app, recovery in RECOVERY.items()
                     for path in (f'/{CONTEXT[app]}/api/server', f'/{CONTEXT[app]}{recovery}')),
}


class LoopbackHttp:
    """Plain HTTP for the fixed local Tomcat and pinned SSH forward only."""
    def __init__(self, port, timeout):
        require(port in _PROBE_PATHS, 'Fixed loopback probe port required')
        self.port = port
        self.timeout = timeout

    def get(self, path, token=None, *, limit):
        require(path in _PROBE_PATHS[self.port], 'Fixed loopback probe path required')
        require(type(limit) is int and 0 < limit <= 65536, 'Bounded probe response required')
        headers = {'Cache-Control': 'no-store', 'Accept': 'application/json', 'Connection': 'close'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        connection = HTTPConnection('127.0.0.1', self.port, timeout=self.timeout)
        try:
            connection.request('GET', path, headers=headers)
            with connection.getresponse() as response:
                body = response.read(limit + 1)
                require(len(body) <= limit, 'Probe response exceeded its bound')
                response_headers = {}
                for key, value in response.headers.raw_items():
                    key = key.lower()
                    if key.startswith('x-four-project-'):
                        require(key not in response_headers, 'Ambiguous controller proof headers')
                    response_headers[key] = value
                return Reply(response.status, response_headers, body)
        finally:
            connection.close()


class ComputeProber:
    def __init__(self, config, clock=time.monotonic):
        self.config = config
        self.clock = clock
        self.monitor = LoopbackHttp(942, 3)
        self.identity = LoopbackHttp(18082, 12)
        self.recovery = LoopbackHttp(18082, 90)
        self.application_slot = threading.Lock()

    @staticmethod
    def object(reply):
        require(reply.status == 200 and 'application/json' in reply.headers.get('content-type', ''),
                'Successful JSON response required')
        value = json.loads(reply.body, object_pairs_hook=_unique_object)
        require(type(value) is dict, 'JSON object required')
        return value

    def application_completed(self, app, token):
        # The two emulated CPUs make simultaneous JDBC health checks stall each other.
        # Monitor validation and per-app proof expiration remain independent.
        with self.application_slot:
            context = '/' + CONTEXT[app]

            def is_node3():
                identity = self.object(self.identity.get(context + '/api/server', limit=8192))
                return type(identity.get('serverId')) is int and identity['serverId'] == 3

            if not is_node3():
                return None
            started = self.clock()
            result = self.recovery.get(context + RECOVERY[app], token, limit=8192)
            finished = self.clock()
            if result.status != 204 or result.body or not 0 <= finished - started <= 90 or not is_node3():
                return None
            return finished

    def production_proof(self):
        # The production monitor is sampled after application SQL finishes, so
        # its fresh capacity proof covers the time the gate is published.
        reply = self.monitor.get('/status', self.config['monitorToken'], limit=65536)
        value = self.object(reply)
        observed = value.get('observedAtMillis')
        require(value.get('topology') == 'single-database' and value.get('mode') == 'single'
                and type(value.get('nodeId')) is int and value['nodeId'] == 2
                and value.get('mysqlUuid') == self.config['mysqlUuid']
                and value.get('mysqlReady') is True and value.get('ready') is True
                and value.get('reserveGuaranteed') is True
                and type(value.get('floorBytes')) is int and value['floorBytes'] == CAPACITY_FLOOR_BYTES
                and type(observed) is int and 0 <= int(time.time() * 1000) - observed <= 5000,
                'Fresh production database and capacity proof required')
        return self.config['mysqlUuid'] + ':single-database'

def handler(admission):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            # A client sending one byte before every idle timeout must still
            # release its slot within the absolute connection deadline.
            self._deadline=threading.Timer(getattr(self.server,'_read_timeout',2),self._expire)
            self._deadline.daemon=True
            self._deadline.start()
        def _expire(self):
            try:self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:pass
        def finish(self):
            self._deadline.cancel()
            super().finish()
        def handle(self):
            try:super().handle()
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError,TimeoutError):pass
        def do_GET(self):
            app=self.path.removeprefix('/local-admit/') if self.path.startswith('/local-admit/') else ''
            allowed=admission.locally_admitted(app,3)
            self.send_response(204 if allowed else 403)
            self.send_header('Cache-Control','no-store');self.send_header('Content-Length','0');self.end_headers()
        def log_message(self,*args):pass
    return Handler

class BoundedHTTPServer(ThreadingHTTPServer):
    """Bound root-owned listener resources; rejected connections fail closed."""
    daemon_threads=True
    request_queue_size=8

    def __init__(self,address,handler_class,max_connections=8,read_timeout=2):
        require(type(max_connections) is int and 1 <= max_connections <= 8,'Bounded connection count required')
        require(type(read_timeout) in (int,float) and 0 < read_timeout <= 2,'Bounded socket timeout required')
        self._slots=threading.BoundedSemaphore(max_connections)
        self._read_timeout=read_timeout
        super().__init__(address,handler_class)

    def get_request(self):
        request,address=super().get_request()
        request.settimeout(self._read_timeout)
        return request,address

    def process_request(self,request,client_address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request,client_address)
        except BaseException:
            self._slots.release()
            raise

    def process_request_thread(self,request,client_address):
        try:
            super().process_request_thread(request,client_address)
        finally:
            self._slots.release()

def poll_app_once(admission, prober, app, token, verifier=verify_monitor, timings=None):
    def timed(name, operation, *args):
        if timings is None:
            return operation(*args)
        started = time.monotonic()
        try:
            return operation(*args)
        finally:
            timings[name] = int((time.monotonic() - started) * 1000)

    ticket = admission.ticket()
    try:
        before = timed('monitorBeforeMillis', verifier)
    except Exception:
        admission.monitor_failed(ticket)
        return 'monitor-unavailable'
    ticket = admission.bind(ticket, before)
    if ticket is None:
        return 'discarded'
    try:
        completed_at = timed('applicationMillis', prober.application_completed, app, token)
    except Exception:
        completed_at = None
    if completed_at is None:
        admission.app_failed(app, ticket)
        return 'application-unavailable'
    try:
        proof = timed('productionProofMillis', prober.production_proof)
        require(isinstance(proof, str) and proof, 'Current production proof required')
        require(timed('monitorAfterMillis', verifier) == before, 'Monitor SSH incarnation changed while probing')
        bound = hashlib.sha256((proof + '\0' + repr(before)).encode()).hexdigest()
    except Exception:
        admission.monitor_failed(ticket)
        return 'monitor-unavailable'
    return admission.complete(app, ticket, bound, completed_at)


def run_app(admission, prober, app, token, stop, delay):
    if stop.wait(delay):
        return
    last = None
    while not stop.is_set():
        started = time.monotonic()
        timings = {}
        result = poll_app_once(admission, prober, app, token, timings=timings)
        if result != last:
            print(json.dumps({'app': app, 'status': result,
                              'elapsedMillis': int((time.monotonic() - started) * 1000),
                              **timings}), flush=True)
            last = result
        stop.wait(2)


def main():
    require(os.geteuid()==0,'Root required for privileged loopback gate')
    if sys.argv[1:] == ['--init-config']:
        initialize_admission()
        return
    if sys.argv[1:] == ['--check-monitor']:
        before = verify_monitor()
        ComputeProber(load_config()).production_proof()
        require(verify_monitor() == before, 'Monitor SSH incarnation changed while probing')
        print('Actual production database monitor passed over the pinned SSH tunnel.')
        return
    require(sys.argv[1:] == [], 'Unknown compute admission action')
    config=load_config();verify_monitor()
    admission = ScopedAdmission(config['recoveryTokens'])
    prober = ComputeProber(config)
    stop = threading.Event()
    server = BoundedHTTPServer(('127.0.0.1', 945), handler(admission))
    try:
        for index, (app, token) in enumerate(config['recoveryTokens'].items()):
            threading.Thread(target=run_app, args=(admission, prober, app, token, stop, index * 3),
                             daemon=True, name='proof-' + app).start()
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()

if __name__=='__main__':main()
