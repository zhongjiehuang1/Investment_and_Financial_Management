#!/usr/bin/env python3
"""Two equal GR writers, fail-closed authority, and authenticated durable fencing.

See PEER_CONTROLLER.md. This module never reads a password into a command argument
or logs a request/response body. Root policy and data-plane tests remain required.
"""
import argparse
import fcntl
import hashlib
import hmac
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import socket
import stat
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid


class Closed(RuntimeError):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def canonical_create_table(value, allocator_validated=False):
    # MySQL retains whether a text column's charset was explicitly declared.
    # A mysqldump restore can therefore display CHARACTER SET utf8mb4 before
    # COLLATE utf8mb4_* where the original displays only COLLATE. That prefix
    # adds no semantics: the named collation already determines its charset.
    # Match only the beginning of a CHAR/VARCHAR/TEXT column declaration, never
    # default strings, enums, identifiers, a different collation or table flags.
    value = re.sub(r"(?m)^(\s+`[^`]+`\s+(?:(?:var)?char\([0-9]+\)|(?:tiny|medium|long)?text)\s+)"
                   r"CHARACTER SET ([A-Za-z0-9_]+)( COLLATE \2_[A-Za-z0-9_]+\b)",
                   lambda m: m[1] + m[3].lstrip(), value)
    if allocator_validated:
        # Only the final actual table-options line, after an authenticated
        # metadata/PK/next>MAX allocator check. Column AUTO_INCREMENT declarations
        # and every DEFAULT/COMMENT string remain untouched.
        lines = value.splitlines(keepends=True)
        if lines and re.search(r"(?m)^[A-Za-z0-9_]+\tCREATE TABLE `[A-Za-z0-9_]+` \($", value):
            lines[-1] = re.sub(r"^(\) ENGINE=InnoDB) AUTO_INCREMENT=[1-9][0-9]*(?= DEFAULT CHARSET=)",
                               r"\1", lines[-1])
            value = "".join(lines)
    return value


def private_stream_digest(command, timeout=75):
    """Hash a bounded private subprocess stream without retaining/logging data."""
    child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + timeout
    result = hashlib.sha256()
    try:
        with selectors.DefaultSelector() as ready:
            ready.register(child.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not ready.select(remaining):
                    raise Closed("Private full-content audit timed out")
                chunk = os.read(child.stdout.fileno(), 65536)
                if not chunk:
                    break
                result.update(chunk)
        if child.wait(timeout=max(0.01, deadline-time.monotonic())) != 0:
            raise Closed("Private full-content audit failed")
        return result.hexdigest()
    finally:
        if child.poll() is None:
            child.kill()
        child.wait()
        child.stdout.close()


def token():
    return secrets.token_hex(32)


def private_file(path, uid=None):
    """Open the exact non-symlink private regular file, without a race to reopen."""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != (os.geteuid() if uid is None else uid)
                or info.st_mode & 0o077 or info.st_size > 1_048_576):
            raise Closed("Private root configuration permissions are invalid")
        return stream.read(1_048_577)


def valid_gtids(value):
    # Tagged GTIDs are deliberately refused until their syntax/semantics have
    # separate acceptance. The configured group uses untagged UUID intervals.
    if not isinstance(value, str) or len(value) > 65536 or not value:
        return False
    value = re.sub(r"[ \t\r\n]", "", value)
    uuid_pattern = r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    interval = r"[1-9][0-9]*(?:-[1-9][0-9]*)?"
    return re.fullmatch(uuid_pattern + r"(?::" + interval + r")+(?:," + uuid_pattern + r"(?::" + interval + r")+)*", value) is not None


def valid_operation(value):
    try:
        return isinstance(value, str) and len(value) == 36 and str(uuid.UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def privileged_port_boundary():
    """A dead private service must not leave a port claimable by ordinary UIDs."""
    try:
        return int(Path("/proc/sys/net/ipv4/ip_unprivileged_port_start").read_text().strip()) >= 1024
    except (OSError, ValueError):
        return False


class StateStore:
    """Exclusive process ownership, atomic replacement and fsync of both levels."""
    def __init__(self, directory, uid=None):
        self.directory = Path(directory)
        self.uid = os.geteuid() if uid is None else uid
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != self.uid or info.st_mode & 0o077:
            raise Closed("State directory permissions are invalid")
        self.lock_fd = os.open(self.directory / "controller.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        lock_info = os.fstat(self.lock_fd)
        if not stat.S_ISREG(lock_info.st_mode) or lock_info.st_uid != self.uid or lock_info.st_mode & 0o077:
            os.close(self.lock_fd)
            self.lock_fd = None
            raise Closed("State lock ownership is invalid")
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except Exception:
            os.close(self.lock_fd)
            self.lock_fd = None
            raise
        self.path = self.directory / "state.json"

    def read(self):
        try:
            value = json.loads(private_file(self.path, self.uid))
            if not isinstance(value, dict):
                raise ValueError("Invalid state")
            return value
        except FileNotFoundError:
            return {}

    def write(self, value):
        temporary = self.directory / (".state-" + token())
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(canonical(value) + b"\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            directory_fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass

    def close(self):
        if self.lock_fd is not None:
            os.close(self.lock_fd)
            self.lock_fd = None


class MySQLActuator:
    """Root-only local process/service fence and narrow fixed SQL operations."""
    def __init__(self, config):
        self.config = config
        self.unit = config["mysqlUnit"]
        self.data = str(Path(config["mysqlDataDir"]).resolve())
        self.defaults = config["mysqlDefaultsFile"]
        self.address = config["groupAddress"]
        if (not re.fullmatch(r"[A-Za-z0-9_.@-]+\.service", self.unit)
                or not re.fullmatch(r"127\.0\.0\.1:[0-9]{1,3}", self.address)
                or not 1 <= int(self.address.rsplit(":", 1)[1]) < 1024):
            raise Closed("Invalid local actuator identity")
        if config.get("production") is not True:
            if not self.unit.startswith("codex-") or not self.data.startswith("/var/lib/codex-validation/"):
                raise Closed("Scratch actuator must be isolated")
        if not Path(self.data).is_dir():
            raise Closed("Protected data directory is missing")
        private_file(self.defaults, 0)

    def guard_installed(self):
        try:
            path = Path(self.config["guardDropin"])
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                return False
            raw = path.read_bytes()
            return (hashlib.sha256(raw).hexdigest() == self.config["guardDropinSha256"]
                    and all(value.encode() in raw for value in ("ExecCondition=+", " guard --config ",
                        "Restart=no", "RestartForceExitStatus=", "--persisted-globals-load=OFF",
                        "--super-read-only=ON", "--read-only=ON", "--event-scheduler=OFF",
                        "--group-replication-start-on-boot=OFF", "--group-replication-bootstrap-group=OFF",
                        "--group-replication-communication-stack=MYSQL", "--require-secure-transport=ON",
                        "--tls-version=TLSv1.3", "--group-replication-recovery-use-ssl=ON",
                        "--group-replication-recovery-ssl-verify-server-cert=ON",
                        "--group-replication-ssl-mode=VERIFY_IDENTITY", "--auto-increment-increment=7",
                        "--group-replication-auto-increment-increment=7",
                        "--group-replication-communication-max-message-size=" + str(self.config["groupFragmentBytes"]),
                        f"--server-id={self.config['nodeId']}",
                        f"--auto-increment-offset={self.config['nodeId']}")))
        except (OSError, KeyError):
            return False

    @staticmethod
    def command(args, timeout=15):
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            # Never include command output, SQL text, defaults contents or request
            # bodies in an exception that could become a system journal entry.
            raise Closed("Local actuator command failed")
        return result.stdout.strip()

    def sql(self, sql, timeout=8):
        return self._sql(sql, timeout)

    def _sql(self, sql, timeout):
        result = subprocess.run(["/usr/bin/mysql", "--defaults-file=" + self.defaults,
                                 "--no-login-paths", "--batch", "--raw", "--skip-column-names", "--connect-timeout=2"],
                                input="SET SESSION group_replication_consistency='EVENTUAL';\n" + sql,
                                capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise Closed("Private MySQL operation failed")
        return result.stdout.strip()

    def processes(self):
        result = []
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                args = (entry / "cmdline").read_bytes().split(b"\0")
                text = [arg.decode("utf-8", "strict") for arg in args if arg]
                if not text or Path(text[0]).name not in ("mysqld", "mysqld-debug"):
                    continue
                datadir = None
                for index, arg in enumerate(text):
                    if arg.startswith("--datadir="):
                        datadir = arg.split("=", 1)[1]
                    elif arg == "--datadir" and index + 1 < len(text):
                        datadir = text[index + 1]
                # The production package uses its defaults file rather than an
                # explicit --datadir; count every mysqld in that mode. Scratch
                # tests require an explicit datadir and cannot ignore aliases.
                if self.config.get("production") is True or (datadir is not None and str(Path(datadir).resolve()) == self.data):
                    fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
                    result.append({"pid": int(entry.name), "startTicks": fields[19]})
            except (OSError, UnicodeError, IndexError):
                continue
        return sorted(result, key=lambda p: p["pid"])

    def process_identity(self):
        values = self.processes()
        if len(values) != 1:
            raise Closed("Expected exactly one protected MySQL process")
        return values[0]

    def is_fenced(self):
        active = subprocess.run(["systemctl", "is-active", "--quiet", self.unit], capture_output=True).returncode == 0
        enabled = subprocess.run(["systemctl", "is-enabled", self.unit], capture_output=True, text=True).stdout.strip()
        return not active and enabled == "masked" and not self.processes() and self.guard_installed()

    def fence(self):
        self.command(["systemctl", "stop", self.unit], timeout=60)
        self.command(["systemctl", "mask", self.unit])
        if not self.is_fenced():
            raise Closed("Database could not be proven fenced")

    def start_readonly(self):
        self.command(["systemctl", "unmask", self.unit])
        self.command(["systemctl", "start", self.unit], timeout=60)
        # Type=simple systemd reports "started" before MySQL has opened its
        # authenticated socket. Waiting here grants no write authority: the
        # durable phase is JOINING and the forced startup remains read-only.
        deadline = time.monotonic() + 30
        while True:
            try:
                snapshot = self.snapshot()
                break
            except (Closed, OSError, subprocess.TimeoutExpired):
                if time.monotonic() >= deadline:
                    raise Closed("Quarantined database did not become inspectable") from None
                time.sleep(0.25)
        if snapshot["readOnly"] != 1 or snapshot["members"]:
            raise Closed("Returning database did not start quarantined")

    def snapshot(self):
        if not self.guard_installed():
            raise Closed("Persistent startup guard is missing or changed")
        value = self.sql("""SELECT JSON_OBJECT(
          'mysqlUuid',@@server_uuid,'groupId',@@global.group_replication_group_name,
          'singlePrimary',@@global.group_replication_single_primary_mode,
          'enforceEverywhere',@@global.group_replication_enforce_update_everywhere_checks,
          'eventScheduler',@@global.event_scheduler,'enforceGtidConsistency',@@global.enforce_gtid_consistency,
          'persistedGlobalsLoad',@@global.persisted_globals_load,'groupAutoStart',@@global.group_replication_start_on_boot,
          'bootstrapGroup',@@global.group_replication_bootstrap_group,'autoRejoinTries',@@global.group_replication_autorejoin_tries,
          'unreachableMajorityTimeout',@@global.group_replication_unreachable_majority_timeout,
          'memberExpelTimeout',@@global.group_replication_member_expel_timeout,
          'exitStateAction',@@global.group_replication_exit_state_action,
          'preserveCommitOrder',@@global.replica_preserve_commit_order,'binlogFormat',@@global.binlog_format,
          'offlineMode',@@global.offline_mode,'bindAddress',@@global.bind_address,'sqlPort',@@global.port,
          'reportHost',@@global.report_host,'reportPort',@@global.report_port,
          'groupLocalAddress',@@global.group_replication_local_address,'groupSeeds',@@global.group_replication_group_seeds,
          'mysqlDataDir',@@global.datadir,'mysqlSocket',@@global.socket,'mysqlTmpDir',@@global.tmpdir,
          'mysqlXState',(SELECT COALESCE(MAX(PLUGIN_STATUS),'ABSENT') FROM information_schema.PLUGINS WHERE PLUGIN_NAME='mysqlx'),
          'serverId',@@server_id,'autoIncrementStep',@@global.auto_increment_increment,
          'autoIncrementOffset',@@global.auto_increment_offset,
          'groupAutoIncrementStep',@@global.group_replication_auto_increment_increment,
          'communicationFragmentBytes',@@global.group_replication_communication_max_message_size,
          'communicationStack',@@global.group_replication_communication_stack,
          'secureTransport',@@global.require_secure_transport,'tlsVersion',@@global.tls_version,
          'groupSSLMode',@@global.group_replication_ssl_mode,
          'recoverySSL',@@global.group_replication_recovery_use_ssl,
          'recoveryVerify',@@global.group_replication_recovery_ssl_verify_server_cert,
          'recoveryTLSVersion',@@global.group_replication_recovery_tls_version,
          'recoveryGetPublicKey',@@global.group_replication_recovery_get_public_key,
          'serverCA',@@global.ssl_ca,'serverCert',@@global.ssl_cert,'serverKey',@@global.ssl_key,
          'recoveryCA',@@global.group_replication_recovery_ssl_ca,
          'recoveryCert',@@global.group_replication_recovery_ssl_cert,
          'recoveryKey',@@global.group_replication_recovery_ssl_key,
          'consistency',@@global.group_replication_consistency,
          'readOnly',@@global.super_read_only,'ordinaryReadOnly',@@global.read_only,'flush',@@innodb_flush_log_at_trx_commit,
          'syncBinlog',@@sync_binlog,'doublewrite',@@innodb_doublewrite,
          'gtidMode',@@gtid_mode,'logBin',@@log_bin,'logReplicaUpdates',@@log_replica_updates,
          'gtidExecuted',@@global.gtid_executed,
          'viewId',(SELECT VIEW_ID FROM performance_schema.replication_group_member_stats
                    WHERE MEMBER_ID=@@server_uuid LIMIT 1),
          'members',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',MEMBER_ID,'state',MEMBER_STATE,'role',MEMBER_ROLE))
                    FROM performance_schema.replication_group_members),JSON_ARRAY()))""")
        result = json.loads(value)
        # OFFLINE local rows mean no joined group; normalize to an empty view.
        if all(row.get("state") == "OFFLINE" for row in result.get("members", [])):
            result["members"] = []
        result["processIdentity"] = self.process_identity()
        result["runtimeGuardIdentityVerified"] = self.runtime_identity_matches(result)
        return result

    def runtime_identity_matches(self, snapshot):
        """Compare live endpoint/path settings with the SHA-bound actual unit.

        Each authenticated peer attests its own local guard; local/peer ports can
        differ without inventing a common fixed port or a permanent primary.
        Read-only startup flags are handled by admission phase, not compared to
        the normal writable values. Memory/cache sizes are capacity parameters.
        """
        try:
            if not self.guard_installed():
                return False
            starts = [line.partition("=")[2] for line in Path(self.config["guardDropin"]).read_text().splitlines()
                      if line.startswith("ExecStart=") and line.partition("=")[2]]
            if len(starts) != 1:
                return False
            flags = {}
            for arg in shlex.split(starts[0])[1:]:
                if arg.startswith("--") and "=" in arg:
                    name, value = arg[2:].split("=", 1)
                    flags[name.removeprefix("loose-").replace("_", "-")] = value
            for field, flag in (("sqlPort", "port"), ("reportPort", "report-port"),
                                ("memberExpelTimeout", "group-replication-member-expel-timeout")):
                if not flags[flag].isdigit() or snapshot[field] != int(flags[flag]):
                    return False
            if not 1 <= snapshot["sqlPort"] < 1024 or snapshot["reportPort"] != snapshot["sqlPort"]:
                return False
            for field, flag in (("bindAddress", "bind-address"), ("reportHost", "report-host")):
                if snapshot[field] != flags[flag] or snapshot[field] != "127.0.0.1":
                    return False
            if (snapshot["groupLocalAddress"] != self.config["groupAddress"]
                    or snapshot["groupLocalAddress"] != flags["group-replication-local-address"]
                    or snapshot["groupLocalAddress"] != "127.0.0.1:" + str(snapshot["sqlPort"])):
                return False
            seeds = snapshot["groupSeeds"].split(",")
            if (len(seeds) != 2 or len(set(seeds)) != 2 or snapshot["groupLocalAddress"] not in seeds
                    or sorted(seeds) != sorted(flags["group-replication-group-seeds"].split(","))
                    or any(not re.fullmatch(r"127\.0\.0\.1:[0-9]{1,3}", seed)
                           or not 1 <= int(seed.rsplit(":", 1)[1]) < 1024 for seed in seeds)):
                return False
            for field, flag in (("mysqlDataDir", "datadir"), ("mysqlSocket", "socket"), ("mysqlTmpDir", "tmpdir")):
                if (not Path(snapshot[field]).is_absolute() or not Path(flags[flag]).is_absolute()
                        or Path(snapshot[field]).resolve() != Path(flags[flag]).resolve()):
                    return False
            return str(Path(snapshot["mysqlDataDir"]).resolve()) == self.data
        except (KeyError, TypeError, ValueError, OSError):
            return False

    def quiesce(self):
        # SRO blocks new writes, but MySQL 26.10 releases its temporary commit
        # barrier while idle pre-existing RW transactions may remain. Explicitly
        # drain them before any audit. Never infer drainage from elapsed time.
        self.sql("SET GLOBAL super_read_only=ON", timeout=60)
        deadline = time.monotonic() + 60
        while not self.writes_drained():
            if time.monotonic() >= deadline:
                raise Closed("Pre-existing write transactions did not drain")
            time.sleep(0.1)

    def write_barrier_status(self):
        value = json.loads(self.sql("""SELECT JSON_OBJECT(
          'superReadOnly',@@global.super_read_only,'ordinaryReadOnly',@@global.read_only,
          'performanceSchema',@@global.performance_schema,
          'instrumentationConsumers',(SELECT COUNT(*) FROM performance_schema.setup_consumers
            WHERE NAME IN ('global_instrumentation','thread_instrumentation') AND ENABLED='YES'),
          'metadataInstrument',(SELECT COUNT(*) FROM performance_schema.setup_instruments
            WHERE NAME='wait/lock/metadata/sql/mdl' AND ENABLED='YES'),
          'metadataLost',(SELECT VARIABLE_VALUE FROM performance_schema.global_status
            WHERE VARIABLE_NAME='Performance_schema_metadata_lock_lost'),
          'threadsLost',(SELECT VARIABLE_VALUE FROM performance_schema.global_status
            WHERE VARIABLE_NAME='Performance_schema_thread_instances_lost'),
          'uninstrumentedClients',(SELECT COUNT(*) FROM performance_schema.threads
            WHERE TYPE='FOREGROUND' AND PROCESSLIST_ID IS NOT NULL AND INSTRUMENTED<>'YES'),
          'observerKnown',(SELECT COUNT(*) FROM performance_schema.threads
            WHERE PROCESSLIST_ID=CONNECTION_ID() AND INSTRUMENTED='YES'),
          'modifiedTransactions',(SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE TRX_ROWS_MODIFIED>0),
          'writeIntentLocks',(SELECT COUNT(*) FROM performance_schema.metadata_locks
            WHERE LOCK_TYPE IN ('INTENTION_EXCLUSIVE','SHARED_WRITE','SHARED_WRITE_LOW_PRIO',
              'SHARED_UPGRADABLE','SHARED_NO_WRITE','SHARED_NO_READ_WRITE','EXCLUSIVE')
              AND (OWNER_THREAD_ID IS NULL OR OWNER_THREAD_ID NOT IN
                (SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=CONNECTION_ID()))),
          'unknownLockTypes',(SELECT COUNT(*) FROM performance_schema.metadata_locks
            WHERE LOCK_TYPE NOT IN ('INTENTION_EXCLUSIVE','SHARED','SHARED_HIGH_PRIO','SHARED_READ','SHARED_WRITE',
              'SHARED_WRITE_LOW_PRIO','SHARED_UPGRADABLE','SHARED_READ_ONLY','SHARED_NO_WRITE','SHARED_NO_READ_WRITE','EXCLUSIVE')))
          """))
        numbers = {}
        for name in ('superReadOnly','ordinaryReadOnly','performanceSchema','instrumentationConsumers',
                     'metadataInstrument','metadataLost','threadsLost','uninstrumentedClients','observerKnown',
                     'modifiedTransactions','writeIntentLocks','unknownLockTypes'):
            raw = value.get(name)
            if isinstance(raw, bool) or not isinstance(raw, (int, str)) or not str(raw).isdigit():
                raise Closed("Write-drain observation is incomplete")
            numbers[name] = int(raw)
        if (numbers['superReadOnly'] != 1 or numbers['ordinaryReadOnly'] != 1
                or numbers['performanceSchema'] != 1 or numbers['instrumentationConsumers'] != 2
                or numbers['metadataInstrument'] != 1 or numbers['metadataLost'] != 0
                or numbers['threadsLost'] != 0 or numbers['uninstrumentedClients'] != 0
                or numbers['observerKnown'] != 1 or numbers['unknownLockTypes'] != 0):
            raise Closed("Write-drain observability or read-only protection is unavailable")
        return numbers

    def writes_drained(self):
        value = self.write_barrier_status()
        return value['modifiedTransactions'] == 0 and value['writeIntentLocks'] == 0

    def enable_writes(self):
        self.sql("SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF")

    def prepared_xa(self):
        return bool(self.sql("XA RECOVER"))

    def wait_gtids(self, value):
        if not valid_gtids(value):
            raise Closed("Invalid GTID confirmation set")
        value = re.sub(r"[ \t\r\n]", "", value)
        return self.sql("SELECT WAIT_FOR_EXECUTED_GTID_SET('" + value + "',5)", timeout=7) == "0"

    def gtid_subset(self, left, right):
        if not left:
            return True
        if not valid_gtids(left) or (right and not valid_gtids(right)):
            return False
        left, right = (re.sub(r"[ \t\r\n]", "", x) for x in (left, right))
        return self.sql("SELECT GTID_SUBSET('" + left + "','" + right + "')") == "1"

    def promote_single(self):
        if self.prepared_xa():
            raise Closed("Legacy prepared XA prevents single-node takeover")
        state = self.snapshot()
        if (state.get("groupId") == self.config["groupId"] and len(state.get("members", [])) == 1
                and state["members"][0] == {"id": state["mysqlUuid"], "state": "ONLINE", "role": "PRIMARY"}):
            self.enable_writes()
            return
        self.sql("SET GLOBAL group_replication_force_members='" + self.address + "'")
        self.sql("SET GLOBAL group_replication_force_members=''")
        self.enable_writes()

    def bootstrap(self):
        if self.prepared_xa():
            raise Closed("Prepared XA prevents group bootstrap")
        try:
            self.sql("SET GLOBAL group_replication_bootstrap_group=ON; START GROUP_REPLICATION", timeout=60)
        finally:
            self.sql("SET GLOBAL group_replication_bootstrap_group=OFF")

    def join(self):
        if self.prepared_xa():
            raise Closed("Legacy prepared XA prevents replica rejoin")
        self.sql("START GROUP_REPLICATION", timeout=60)

    def allocator_validated(self, schema, table):
        columns = self.sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema
                           + "' AND TABLE_NAME='" + table + "' AND EXTRA LIKE '%auto_increment%' ORDER BY ORDINAL_POSITION").splitlines()
        if not columns:
            return False
        if len(columns) != 1 or not re.fullmatch(r"[A-Za-z0-9_]+", columns[0]):
            raise Closed("Only an audited single-column automatic primary key can normalize its allocator")
        primary = self.sql("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='" + schema
                           + "' AND TABLE_NAME='" + table + "' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION").splitlines()
        if primary != columns:
            raise Closed("Automatic allocation must be the one-column primary key")
        current = self.snapshot()
        if (current["serverId"] != self.config["nodeId"] or current["autoIncrementStep"] != 7
                or current["groupAutoIncrementStep"] != 7 or current["autoIncrementOffset"] != self.config["nodeId"]):
            raise Closed("Equal-writer allocation settings are not disjoint")
        maximum = int(self.sql("SELECT COALESCE(MAX(`" + columns[0] + "`),0) FROM `" + schema + "`.`" + table + "`"))
        upcoming = self.sql("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema
                            + "' AND TABLE_NAME='" + table + "'")
        if not upcoming.isdigit() or int(upcoming) <= maximum:
            raise Closed("Automatic allocator is not above all committed primary keys")
        return True

    def schema_definitions(self, schema):
        """Private semantic schema audit, including objects outside base tables.

        Trigger creation timestamps are local administrative metadata; all
        execution/ordering/security/SQL-mode/charset fields are retained instead.
        Definitions never become diagnostics or public controller responses.
        """
        if not re.fullmatch(r"[A-Za-z0-9_]+", schema):
            raise Closed("Unexpected schema identifier")
        database = self.sql("SHOW CREATE DATABASE `" + schema + "`")
        if "CREATE DATABASE " not in database:
            raise Closed("Database defaults could not be completely audited")
        result = [["database", schema, database]]

        def identifier(value):
            if not re.fullmatch(r"[A-Za-z0-9_]+", value):
                raise Closed("Unexpected schema object identifier")
            return "`" + schema + "`.`" + value + "`"

        def definition(kind, name):
            value = self.sql("SHOW CREATE " + kind + " " + identifier(name))
            if "\tCREATE " not in value:
                raise Closed("Complete private object definition is unavailable")
            return [kind.lower(), name, value]

        views = self.sql("SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA='"
                         + schema + "' UNION SELECT TABLE_NAME FROM information_schema.JSON_DUALITY_VIEWS "
                         "WHERE TABLE_SCHEMA='" + schema + "' ORDER BY TABLE_NAME").splitlines()
        result.extend(definition("VIEW", name) for name in views)
        routines = self.sql("SELECT CONCAT(ROUTINE_TYPE,CHAR(9),ROUTINE_NAME) FROM information_schema.ROUTINES "
                            "WHERE ROUTINE_SCHEMA='" + schema + "' ORDER BY ROUTINE_TYPE,ROUTINE_NAME").splitlines()
        for routine in routines:
            fields = routine.split("\t")
            if len(fields) != 2 or fields[0] not in ("FUNCTION", "PROCEDURE"):
                raise Closed("Unexpected stored routine inventory")
            result.append(definition(fields[0], fields[1]))
        triggers = self.sql("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='"
                            + schema + "' ORDER BY EVENT_OBJECT_TABLE,ACTION_TIMING,EVENT_MANIPULATION,ACTION_ORDER,TRIGGER_NAME").splitlines()
        for name in triggers:
            identifier(name)
            value = json.loads(self.sql("SELECT JSON_OBJECT('name',TRIGGER_NAME,'event',EVENT_MANIPULATION,"
                "'schema',EVENT_OBJECT_SCHEMA,'table',EVENT_OBJECT_TABLE,'order',ACTION_ORDER,"
                "'condition',ACTION_CONDITION,'statement',ACTION_STATEMENT,'orientation',ACTION_ORIENTATION,"
                "'timing',ACTION_TIMING,'oldTable',ACTION_REFERENCE_OLD_TABLE,'newTable',ACTION_REFERENCE_NEW_TABLE,"
                "'oldRow',ACTION_REFERENCE_OLD_ROW,'newRow',ACTION_REFERENCE_NEW_ROW,'sqlMode',SQL_MODE,"
                "'definer',DEFINER,'characterSetClient',CHARACTER_SET_CLIENT,'collationConnection',COLLATION_CONNECTION,"
                "'databaseCollation',DATABASE_COLLATION) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='"
                + schema + "' AND TRIGGER_NAME='" + name + "'"))
            if (not isinstance(value, dict) or value.get("name") != name or value.get("schema") != schema
                    or not isinstance(value.get("statement"), str) or not value["statement"]):
                raise Closed("Complete private trigger definition is unavailable")
            result.append(["trigger", name, value])
        events = self.sql("SELECT EVENT_NAME FROM information_schema.EVENTS WHERE EVENT_SCHEMA='"
                         + schema + "' ORDER BY EVENT_NAME").splitlines()
        result.extend(definition("EVENT", name) for name in events)
        libraries = self.sql("SELECT LIBRARY_NAME FROM information_schema.LIBRARIES WHERE LIBRARY_SCHEMA='"
                            + schema + "' ORDER BY LIBRARY_NAME").splitlines()
        # SHOW CREATE retains full JavaScript or encoded WebAssembly bodies;
        # LIBRARY_DEFINITION alone is empty for WASM and is not a sufficient audit.
        result.extend(definition("LIBRARY", name) for name in libraries)
        return result

    def fingerprint(self):
        schemas = self.config.get("auditSchemas", [])
        if not schemas or any(not re.fullmatch(r"[A-Za-z0-9_]+", s) or s in ("mysql", "sys", "performance_schema", "information_schema") for s in schemas):
            raise Closed("Explicit business-schema audit policy is required")
        rows, definitions = [], []
        for schema in sorted(schemas):
            definitions.extend(self.schema_definitions(schema))
            tables = self.sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").splitlines()
            if not tables:
                raise Closed("Required business schema has no tables")
            for table in tables:
                if not re.fullmatch(r"[A-Za-z0-9_]+", table):
                    raise Closed("Unexpected table identifier")
                name = "`" + schema + "`.`" + table + "`"
                # One private client per table avoids hundreds of sequential
                # authentications during the initial read-only seed audit.
                audit = self.sql("SELECT COUNT(*) FROM " + name + "; CHECKSUM TABLE " + name
                                 + " EXTENDED; SHOW CREATE TABLE " + name)
                rows.append([schema, table, canonical_create_table(audit, self.allocator_validated(schema, table))])
        # CHECKSUM TABLE alone has only a 32-bit checksum. A collision must not
        # make two distinct seeds look equal. Hash a deterministic, primary-key
        # ordered, hex-BLOB data dump as a stream; no business bytes are retained
        # in a file, diagnostic, exception, journal or command argument.
        data = private_stream_digest(["/usr/bin/mysqldump", "--defaults-file=" + self.defaults,
            "--no-login-paths", "--no-create-info", "--skip-triggers", "--skip-routines",
            "--skip-events", "--skip-extended-insert", "--complete-insert", "--hex-blob",
            "--order-by-primary", "--skip-comments", "--skip-add-locks", "--skip-disable-keys",
            "--skip-set-charset", "--skip-dump-date", "--set-gtid-purged=OFF",
            "--no-tablespaces", "--column-statistics=0", "--single-transaction", "--skip-lock-tables",
            "--tz-utc", "--max-allowed-packet=64M", "--databases", *sorted(schemas)])
        return digest({"format": "complete-business-schema-and-content-v2", "definitions": definitions,
                       "tables": rows, "dataSha256": data})


def validate_config(config):
    local, peer = config.get("nodeId"), config.get("peerNodeId")
    if (not isinstance(local, int) or not isinstance(peer, int) or {local, peer} != {1, 2}
            or isinstance(local, bool) or isinstance(peer, bool)
            or not re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", config.get("clusterId", ""))):
        raise Closed("Exactly two configured physical identities are required")
    group = config.get("groupId", "")
    if str(uuid.UUID(group)) != group:
        raise Closed("Canonical group UUID is required")
    members = config.get("memberNodeIds", {})
    if (not isinstance(members, dict) or set(members.values()) != {1, 2}
            or len(members) != 2 or any(not isinstance(v, int) or isinstance(v, bool) for v in members.values())
            or any(str(uuid.UUID(key)) != key for key in members)):
        raise Closed("Canonical physical MySQL membership is required")
    if type(config.get("groupFragmentBytes")) is not int or config["groupFragmentBytes"] not in (524288,1048576):
        raise Closed("An explicit reviewed cross-cloud message-fragment bound is required")
    tls = config.get("tls", {})
    if (not isinstance(tls, dict) or set(tls) != {"caFile", "certFile", "keyFile"}
            or any(not isinstance(p, str) or not Path(p).is_absolute() or "\n" in p or "\r" in p for p in tls.values())):
        raise Closed("Explicit private MySQL TLS material paths are required")
    schemas = config.get("auditSchemas")
    if (not isinstance(schemas, list) or not schemas
            or any(not isinstance(s, str) or not re.fullmatch(r"[A-Za-z0-9_]+", s)
                   or s in ("mysql", "sys", "information_schema", "performance_schema") for s in schemas)
            or len(set(schemas)) != len(schemas)):
        raise Closed("One explicit common business-schema audit policy is required")
    for key in ("listenPort", "peerPort"):
        value = config.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value < 1024:
            raise Closed("A protected privileged loopback port is required")
    return {"clusterId": config["clusterId"], "groupId": group, "memberNodeIds": members, "auditSchemas": sorted(schemas),
            "protocol": "atomic-multiprimary-3-v1", "consistency": "BEFORE_AND_AFTER", "communicationStack": "MYSQL", "transport": "TLSv1.3+VERIFY_IDENTITY+X509", "groupFragmentBytes": config["groupFragmentBytes"]}


def durable_mysql(snapshot, config):
    try:
        ids = set(config["memberNodeIds"])
        return (snapshot["groupId"] == config["groupId"] and snapshot["mysqlUuid"] in ids
                and config["memberNodeIds"][snapshot["mysqlUuid"]] == config["nodeId"]
                and snapshot["singlePrimary"] == 0 and snapshot["consistency"] == "BEFORE_AND_AFTER"
                and snapshot["enforceEverywhere"] == 1 and snapshot["eventScheduler"] == "OFF"
                and snapshot["enforceGtidConsistency"] == "ON" and snapshot["persistedGlobalsLoad"] == 0
                and snapshot["groupAutoStart"] == 0 and snapshot["bootstrapGroup"] == 0
                and snapshot["autoRejoinTries"] == 0 and snapshot["unreachableMajorityTimeout"] == 0
                and snapshot["exitStateAction"] == "READ_ONLY" and snapshot["preserveCommitOrder"] == 1
                and snapshot["binlogFormat"] == "ROW" and snapshot["offlineMode"] == 0
                and snapshot["mysqlXState"] in ("DISABLED", "ABSENT")
                and snapshot["runtimeGuardIdentityVerified"] is True
                and snapshot["serverId"] == config["nodeId"]
                and snapshot["autoIncrementStep"] == 7 and snapshot["groupAutoIncrementStep"] == 7
                and snapshot["autoIncrementOffset"] == config["nodeId"]
                and snapshot["communicationFragmentBytes"] == config["groupFragmentBytes"]
                and snapshot["communicationStack"] == "MYSQL"
                and snapshot["secureTransport"] == 1 and snapshot["tlsVersion"] == "TLSv1.3"
                and snapshot["groupSSLMode"] == "VERIFY_IDENTITY"
                and snapshot["recoverySSL"] == 1 and snapshot["recoveryVerify"] == 1
                and snapshot["recoveryGetPublicKey"] == 0 and snapshot["recoveryTLSVersion"] == "TLSv1.3"
                and all(snapshot["server" + suffix] == snapshot["recovery" + suffix] == config["tls"][name]
                        for suffix, name in (("CA", "caFile"), ("Cert", "certFile"), ("Key", "keyFile")))
                and snapshot["flush"] == 1 and snapshot["syncBinlog"] == 1
                and snapshot["doublewrite"] == "ON" and snapshot["gtidMode"] == "ON"
                and snapshot["logBin"] == 1 and snapshot["logReplicaUpdates"] == 1)
    except (KeyError, TypeError, ValueError):
        return False


def member_view(snapshot, config, count):
    try:
        ids = set(config["memberNodeIds"])
        members = snapshot["members"]
        actual = [m["id"] for m in members]
        return (durable_mysql(snapshot, config)
                and len(actual) == count and len(set(actual)) == count and set(actual).issubset(ids)
                and snapshot["mysqlUuid"] in actual
                and all(m["state"] == "ONLINE" and m["role"] == "PRIMARY" for m in members)
                and isinstance(snapshot["viewId"], str) and bool(snapshot["viewId"]))
    except (KeyError, TypeError, ValueError):
        return False


def writable_view(snapshot, config, count):
    return (member_view(snapshot, config, count) and snapshot["readOnly"] == 0
            and snapshot.get("ordinaryReadOnly") == 0)


class Controller:
    def __init__(self, config, actuator, store, peer_secret, app_token,
                 boot_id=None, monotonic=time.monotonic, transport=None, host_policy=privileged_port_boundary):
        self.policy = validate_config(config)
        self.config, self.actuator, self.store = config, actuator, store
        if len(peer_secret) < 32 or len(app_token) < 32 or hmac.compare_digest(peer_secret, app_token):
            raise Closed("Independent strong peer and application credentials are required")
        self.peer_secret, self.app_token = peer_secret, app_token
        self.membership = digest(self.policy)
        self.boot_id = boot_id or Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.incarnation = token()
        self.monotonic, self.transport = monotonic, transport
        self.host_policy = host_policy
        self.lock = threading.RLock()
        self.audit_lock = threading.Lock()
        self.challenges = {}
        self.broken = False
        try:
            previous = store.read()
            revision = previous.get("revision", 0)
            if not isinstance(revision, int) or isinstance(revision, bool) or not 0 <= revision < 2**63 - 100:
                revision = 0
        except (ValueError, OSError, Closed):
            previous, revision = {}, 0
        self.state = {"version": 1, "clusterId": config["clusterId"], "nodeId": config["nodeId"],
                      "membership": self.membership, "bootId": self.boot_id, "incarnation": self.incarnation,
                      "revision": revision, "phase": "FENCING", "grant": None}
        # A restored file never restores its old grant. Even initialization I/O
        # failure attempts a local hard fence and never yields a proof.
        self.fence("controller-start")

    def _save(self, phase, grant=None):
        self.state = {**self.state, "revision": self.state["revision"] + 1,
                      "phase": phase, "grant": grant}
        try:
            self.store.write(self.state)
        except Exception:
            self.broken = True
            try:
                self.actuator.fence()
            except Exception:
                pass
            raise Closed("Durable controller state could not be written") from None

    def fence(self, reason="local-request"):
        with self.lock:
            write_error = None
            if self.state["phase"] != "FENCED" or not self.actuator.is_fenced():
                try:
                    self._save("FENCING")
                except Closed as failure:
                    write_error = failure
                try:
                    self.actuator.fence()
                except Exception:
                    raise Closed("Local isolation remains incomplete") from None
                if write_error:
                    raise write_error
                if not self.actuator.is_fenced():
                    raise Closed("Local isolation remains incomplete")
                self._save("FENCED")
            return self.snapshot()

    def session(self):
        return {"nodeId": self.config["nodeId"], "bootId": self.boot_id,
                "incarnation": self.incarnation}

    def mysql(self):
        try:
            return self.actuator.snapshot()
        except Exception:
            return None

    def snapshot(self):
        with self.lock:
            phase = self.state["phase"]
            mysql = self.mysql() if phase != "FENCED" else None
            # Proof is from the live actuator, never merely a saved flag.
            fenced = not self.broken and phase == "FENCED" and self.actuator.is_fenced()
            return {**self.session(), "revision": self.state["revision"], "phase": phase,
                    "membership": self.membership, "fenced": fenced,
                    "privilegedPortsProtected": self.host_policy(),
                    "grant": self.state.get("grant"), "mysql": mysql,
                    "fingerprint": None, "admissionProtocol": 3}

    def sign(self, value):
        return hmac.new(self.peer_secret.encode(), canonical(value), hashlib.sha256).hexdigest()

    def _envelope(self, body, request_nonce, recipient=None):
        value = {"version": 1, "clusterId": self.config["clusterId"], "membership": self.membership,
                 "sender": self.session(), "receiver": self.config["peerNodeId"],
                 "requestNonce": request_nonce, "recipient": recipient, "body": body}
        return {**value, "signature": self.sign(value)}

    def _verify(self, envelope, response=False, request_nonce=None):
        if not isinstance(envelope, dict):
            raise Closed("Invalid private protocol envelope")
        signature = envelope.get("signature", "")
        value = {k: v for k, v in envelope.items() if k != "signature"}
        if not isinstance(signature, str) or not hmac.compare_digest(signature, self.sign(value)):
            raise Closed("Private protocol authentication failed")
        sender = value.get("sender", {})
        if (value.get("version") != 1 or value.get("clusterId") != self.config["clusterId"]
                or value.get("membership") != self.membership
                or value.get("receiver") != self.config["nodeId"]
                or sender.get("nodeId") != self.config["peerNodeId"]
                or not isinstance(sender.get("bootId"), str) or not sender["bootId"]
                or not re.fullmatch(r"[0-9a-f]{64}", sender.get("incarnation", ""))
                or not re.fullmatch(r"[0-9a-f]{64}", value.get("requestNonce", ""))):
            raise Closed("Private protocol identity mismatch")
        if response and (value.get("requestNonce") != request_nonce or value.get("recipient") != self.session()):
            raise Closed("Private response is stale")
        return value

    def _http(self, path, value):
        if self.transport:
            return self.transport(path, value)
        request = urllib.request.Request("http://127.0.0.1:" + str(self.config["peerPort"]) + path,
                                         data=canonical(value), headers={"Content-Type": "application/json"}, method="POST")
        # urllib redirect handling would allow a private credential envelope to
        # leave its pinned loopback endpoint, so no redirect is followed.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_args, **_kwargs):
                return None
        action = value.get("body", {}).get("action", "")
        timeout = 120 if action in ("fingerprint", "audit-fingerprint", "audit-begin") else 8
        with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect).open(request, timeout=timeout) as response:
            if response.status != 200:
                raise Closed("Private peer is unavailable")
            raw = response.read(131073)
            if len(raw) > 131072:
                raise Closed("Private peer response is too large")
            return json.loads(raw)

    def rpc(self, action, payload=None):
        nonce = token()
        hello = self._envelope({"action": "hello"}, nonce)
        reply = self._verify(self._http("/peer/hello", hello), True, nonce)
        recipient, challenge = reply["sender"], reply["body"].get("challenge")
        if not isinstance(challenge, str) or not re.fullmatch(r"[0-9a-f]{64}", challenge):
            raise Closed("Invalid peer challenge")
        nonce = token()
        envelope = self._envelope({"action": action, "challenge": challenge, "payload": payload or {}}, nonce, recipient)
        response = self._verify(self._http("/peer/action", envelope), True, nonce)
        if response["sender"] != recipient:
            raise Closed("Peer incarnation changed during request")
        return response["body"]

    def peer_request(self, path, envelope):
        value = self._verify(envelope)
        body, sender = value.get("body", {}), value["sender"]
        if path == "/peer/hello" and body.get("action") == "hello":
            with self.lock:
                now = self.monotonic()
                self.challenges = {k: v for k, v in self.challenges.items() if v[1] > now}
                if len(self.challenges) >= 4096:
                    raise Closed("Too many pending peer challenges")
                challenge = token()
                self.challenges[challenge] = (sender, now + 15)
            result = {"challenge": challenge}
        elif path == "/peer/action":
            with self.lock:
                challenge = self.challenges.pop(body.get("challenge"), None)
                if (value.get("recipient") != self.session() or challenge is None
                        or challenge[0] != sender or self.monotonic() >= challenge[1]):
                    raise Closed("Private action is stale or repeated")
            action, payload = body.get("action"), body.get("payload", {})
            if action == "echo":
                result = {"session": self.session(), "admissionProtocol": 3}
            elif action in ("snapshot", "fence", "wait", "fingerprint", "capacity", "audit-begin", "audit-wait", "audit-fingerprint", "audit-arm", "audit-enable"):
                # A reverse callback is required; no stale or one-way connection
                # can alone be used as bilateral proof. Echo does not recurse.
                echo = self.rpc("echo")
                if echo.get("session") != sender or echo.get("admissionProtocol") != 3:
                    raise Closed("Bilateral peer session is unavailable")
                if action.startswith("audit-"):
                    methods = {"audit-begin": self.audit_begin, "audit-wait": self.audit_wait,
                               "audit-fingerprint": self.audit_fingerprint, "audit-arm": self.audit_arm,
                               "audit-enable": self.audit_enable}
                    result = methods[action](payload, sender)
                elif action == "fence":
                    with self.lock:
                        if (self.state["phase"] != "FENCED" or not self.actuator.is_fenced()
                                or payload.get("expectedRevision") != self.state["revision"]
                                or payload.get("expectedIncarnation") != self.incarnation
                                or payload.get("expectedBootId") != self.boot_id):
                            raise Closed("Peer has not already established the requested current fence")
                        result = self.fence("authenticated-peer-request")
                elif action == "wait":
                    current = self.authority(require_peer=False)
                    # The requesting node has a different local permit. Bind the
                    # peer's exact current view/epoch supplied in its prior snapshot.
                    if current["viewId"] != payload.get("viewId") or self.state["revision"] != payload.get("peerRevision"):
                        raise Closed("Peer view changed during confirmation")
                    if not self.actuator.wait_gtids(payload.get("gtidSet")):
                        raise Closed("Peer durable execution did not complete")
                    after = self.authority(require_peer=False)
                    if current != after:
                        raise Closed("Peer grant changed during confirmation")
                    result = {"executed": True, "viewId": after["viewId"], "revision": self.state["revision"]}
                elif action == "capacity":
                    result = self.capacity()
                elif action == "fingerprint":
                    if self.state["phase"] != "JOINING":
                        raise Closed("Fingerprint requires read-only quarantine")
                    before = self.mysql()
                    if before is None or before["readOnly"] != 1 or before["members"]:
                        raise Closed("Fingerprint requires a stopped group and read-only database")
                    if self.actuator.prepared_xa():
                        raise Closed("Prepared XA prevents an initial seed fingerprint")
                    result = {"fingerprint": self.actuator.fingerprint(), "mysql": self.mysql(), **self.session()}
                else:
                    result = self.snapshot()
            else:
                raise Closed("Unsupported private action")
        else:
            raise Closed("Unsupported private endpoint")
        response = {"version": 1, "clusterId": self.config["clusterId"], "membership": self.membership,
                    "sender": self.session(), "receiver": sender["nodeId"], "requestNonce": value["requestNonce"],
                    "recipient": sender, "body": result}
        return {**response, "signature": self.sign(response)}

    def admin_request(self, envelope):
        """Never transmit the peer secret as a bearer to a replaceable TCP port.

        A local unprivileged process cannot steal root credentials by occupying
        the listener during controller downtime and receiving a guard request.
        Root commands use signed one-use exchanges and verify signed responses.
        """
        if not isinstance(envelope, dict):
            raise Closed("Invalid root request")
        signature = envelope.get("signature", "")
        value = {k: v for k, v in envelope.items() if k != "signature"}
        if (not isinstance(signature, str) or not hmac.compare_digest(signature, self.sign(value))
                or value.get("kind") != "local-root-admin"
                or value.get("clusterId") != self.config["clusterId"]
                or value.get("membership") != self.membership
                or value.get("nodeId") != self.config["nodeId"] or value.get("bootId") != self.boot_id
                or not re.fullmatch(r"[0-9a-f]{64}", value.get("nonce", ""))):
            raise Closed("Root request authentication failed")
        owner = {"admin": True, "bootId": self.boot_id}
        if value.get("action") == "hello":
            with self.lock:
                now = self.monotonic()
                self.challenges = {k: v for k, v in self.challenges.items() if v[1] > now}
                if len(self.challenges) >= 4096:
                    raise Closed("Too many pending root challenges")
                challenge = token()
                self.challenges[challenge] = (owner, now + 15)
            result = {"challenge": challenge}
        else:
            with self.lock:
                challenge = self.challenges.pop(value.get("challenge"), None)
                if (value.get("incarnation") != self.incarnation or challenge is None
                        or challenge[0] != owner or self.monotonic() >= challenge[1]):
                    raise Closed("Root action is stale or repeated")
            action = value.get("action")
            methods = {"fence": self.fence, "prepare": self.prepare, "bootstrap": self.bootstrap, "bootstrap-latest": lambda: self.bootstrap(allow_latest=True),
                       "join": self.join, "arm-dual": self.arm_dual, "takeover": self.takeover,
                       "reload-observations": self.reload_observations, "reconcile-seed": self.reconcile_seed}
            if action == "start-allowed":
                if not self.guard(value.get("bootId")):
                    raise Closed("Database startup is not authorized")
                result = {"allowed": True}
            elif action in methods:
                state = methods[action]()
                result = {"nodeId": state["nodeId"], "phase": state["phase"], "revision": state["revision"]}
            else:
                raise Closed("Unsupported root transition")
        reply = {"kind": "local-root-admin-response", "clusterId": self.config["clusterId"],
                 "membership": self.membership, "nodeId": self.config["nodeId"], "bootId": self.boot_id,
                 "incarnation": self.incarnation, "requestNonce": value["nonce"], "body": result}
        return {**reply, "signature": self.sign(reply)}

    def reload_observations(self):
        """Health/capacity evidence may change without restarting/fencing SQL.

        All identity, database, endpoint, root-token and actuator policy changes
        require a new controller incarnation and its mandatory startup fence.
        """
        new = json.loads(private_file(self.config["_configPath"], 0))
        allowed = {"capacity", "tomcatChecks", "recoveryChecks"}
        old_policy = {k: v for k, v in self.config.items() if k not in allowed | {"_configPath"}}
        new_policy = {k: v for k, v in new.items() if k not in allowed | {"_configPath"}}
        if old_policy != new_policy or validate_config(new) != self.policy:
            raise Closed("Identity or actuator policy cannot be live-reloaded")
        with self.lock:
            self.config = {**self.config, **{k: new.get(k, {} if k == "capacity" else []) for k in allowed}}
        return self.snapshot()

    def _same_mysql_process(self, snapshot):
        grant = self.state.get("grant") or {}
        return snapshot is not None and snapshot.get("processIdentity") == grant.get("processIdentity")

    def _peer(self):
        peer = self.rpc("snapshot")
        if (peer.get("nodeId") != self.config["peerNodeId"] or peer.get("membership") != self.membership
                or peer.get("admissionProtocol") != 3
                or peer.get("privilegedPortsProtected") is not True
                or not isinstance(peer.get("revision"), int) or peer.get("revision", 0) < 1):
            raise Closed("Peer identity is not admitted")
        return peer

    def authority(self, require_peer=True):
        # Never hold the local state lock across a peer callback: both nodes may
        # ask for authority concurrently, and each peer request is independently
        # authenticated and can be serviced by a separate HTTP worker.
        with self.lock:
            if self.broken or not self.host_policy() or self.state["phase"] not in ("DUAL", "SINGLE"):
                raise Closed("No current local write authority")
            state = dict(self.state)
        snapshot = self.mysql()
        mode = "dual" if state["phase"] == "DUAL" else "single"
        count = 2 if mode == "dual" else 1
        if not writable_view(snapshot, self.config, count) or not self._same_mysql_process(snapshot):
            raise Closed("Current local data-plane view is not writable")
        grant = state["grant"]
        if snapshot["viewId"] != grant["viewId"]:
            raise Closed("Data-plane view changed")
        peer = self._peer() if require_peer else None
        if require_peer:
            if mode == "dual":
                remote = peer.get("mysql")
                remote_config = {**self.config, "nodeId": self.config["peerNodeId"]}
                if (peer.get("phase") != "DUAL" or not writable_view(remote, remote_config, 2)
                        or remote["viewId"] != snapshot["viewId"] or peer.get("grant", {}).get("viewId") != snapshot["viewId"]
                        or peer["bootId"] != grant["peerBootId"] or peer["incarnation"] != grant["peerIncarnation"]
                        or remote["processIdentity"] != peer.get("grant", {}).get("processIdentity")
                        or not grant.get("auditFingerprint")
                        or any(peer.get("grant", {}).get(k) != grant.get(k) for k in ("auditId", "auditFingerprint"))):
                    raise Closed("Peer does not admit this dual view")
            else:
                if (peer.get("phase") != "FENCED" or peer.get("fenced") is not True
                        or peer["bootId"] != grant["peerBootId"] or peer["incarnation"] != grant["peerIncarnation"]
                        or peer["revision"] != grant["peerRevision"]):
                    raise Closed("Current peer fence is not proven")
        result = {"mysqlUuid": snapshot["mysqlUuid"], "viewId": snapshot["viewId"],
                  "epoch": str(state["revision"]), "mode": mode, "membership": self.membership,
                  "bootId": self.boot_id, "incarnation": self.incarnation,
                  "peerBootId": grant["peerBootId"], "peerIncarnation": grant["peerIncarnation"],
                  "peerRevision": peer["revision"] if peer else grant.get("peerRevision")}
        # In dual mode peer phase revisions change only on new admissions, and
        # are not needed to stabilize the local permit during mutual arm order.
        stable = {k: v for k, v in result.items() if k != "peerRevision" or mode == "single"}
        result["permit"] = self.sign({"kind": "application-grant", **stable})
        with self.lock:
            if self.state != state:
                raise Closed("Controller grant changed during observation")
        return result

    def confirm(self, body):
        if (not isinstance(body, dict) or not valid_gtids(body.get("gtidSet"))
                or not isinstance(body.get("permit"), str)
                or not valid_operation(body.get("operationId"))):
            raise Closed("Invalid operation confirmation")
        before = self.authority()
        if not hmac.compare_digest(before["permit"], body["permit"]):
            raise Closed("Operation belongs to an old authority grant")
        if not self.actuator.wait_gtids(body["gtidSet"]):
            raise Closed("Local durable execution did not complete")
        if before["mode"] == "dual":
            remote = self.rpc("wait", {"gtidSet": body["gtidSet"], "viewId": before["viewId"],
                                       "peerRevision": before["peerRevision"]})
            if remote.get("executed") is not True or remote.get("viewId") != before["viewId"]:
                raise Closed("Peer execution is not confirmed")
        after = self.authority()
        if before["permit"] != after["permit"] or before["mode"] != after["mode"]:
            raise Closed("Operation grant changed before acknowledgment")
        return after

    def prepare(self):
        with self.lock:
            if self.broken or self.state["phase"] != "FENCED" or not self.actuator.is_fenced():
                raise Closed("Read-only preparation requires a verified local fence")
        peer = self._peer()
        if peer.get("phase") not in ("FENCED", "JOINING", "DUAL", "SINGLE", "RECONCILING"):
            raise Closed("Peer is not in a controlled state")
        with self.lock:
            self._save("JOINING", {"startupPermit": True, "peerBootId": peer["bootId"], "peerIncarnation": peer["incarnation"]})
        try:
            self.actuator.start_readonly()
        except Exception:
            self.fence("read-only-start-failed")
            raise Closed("Read-only preparation failed") from None
        return self.snapshot()

    def bootstrap(self, allow_latest=False):
        with self.lock:
            if self.broken or self.state["phase"] != "JOINING":
                raise Closed("Bootstrap requires quarantined preparation")
        local = self.mysql()
        if local is None or local["readOnly"] != 1 or local["members"]:
            raise Closed("Seed is not read-only outside a group")
        peer = self._peer()
        if peer.get("phase") != "JOINING":
            raise Closed("Both nodes must prepare for initial bootstrap")
        remote = self.rpc("fingerprint")
        remote_sql = remote.get("mysql") or {}
        local_contains_peer = self.actuator.gtid_subset(remote_sql.get("gtidExecuted", ""), local["gtidExecuted"])
        peer_contains_local = self.actuator.gtid_subset(local["gtidExecuted"], remote_sql.get("gtidExecuted", ""))
        local_fingerprint = self.actuator.fingerprint()
        fingerprints_match = local_fingerprint == remote.get("fingerprint")
        if (remote_sql.get("readOnly") != 1 or remote_sql.get("members") or not local_contains_peer
                or (not fingerprints_match and not (allow_latest and not peer_contains_local))):
            raise Closed("Bootstrap requires equal quarantined data or an explicitly reviewed strict GTID-superset recovery seed")
        # A shutdown's final GR view transaction can make one GTID set a strict
        # superset even though all business rows match. That most-current member
        # is the temporary seed; equal GTID sets use a deterministic tie-break.
        if peer_contains_local and self.config["nodeId"] != min(self.config["nodeId"], self.config["peerNodeId"]):
            raise Closed("The temporary equal-data bootstrap seed is the lower node ID")
        self.actuator.bootstrap()
        if allow_latest:
            # This explicit recovery never creates SINGLE authority; clients
            # remain quarantined until peer catchup and the full common audit.
            self.actuator.quiesce()
        return self.snapshot()

    def join(self):
        with self.lock:
            if self.broken or self.state["phase"] != "JOINING":
                raise Closed("Rejoin requires read-only quarantine")
        local = self.mysql()
        peer = self._peer()
        remote = peer.get("mysql")
        if (local is None or local["readOnly"] != 1 or local["members"] or remote is None
                or not member_view(remote, {**self.config, "nodeId": self.config["peerNodeId"]}, 1)
                or peer.get("phase") not in ("SINGLE", "JOINING", "RECONCILING")
                or not self.actuator.gtid_subset(local["gtidExecuted"], remote["gtidExecuted"])):
            raise Closed("Returning node is not a safe subset of the existing seed/survivor")
        # This durable barrier precedes START GR, including with an old peer:
        # old peers refuse RECONCILING and cannot admit the new two-member view.
        with self.lock:
            if self.state["phase"] != "JOINING":
                raise Closed("Join was superseded")
            self._save("RECONCILING", {"processIdentity": local["processIdentity"],
                       "peerBootId": peer["bootId"], "peerIncarnation": peer["incarnation"]})
        self.actuator.join()
        return self.snapshot()

    def _audit_context(self, payload, sender, phase="RECONCILING"):
        with self.lock:
            state = dict(self.state)
        grant = state.get("grant") or {}
        if (self.broken or state["phase"] != phase or grant.get("auditId") != payload.get("auditId")
                or sender.get("bootId") != grant.get("peerBootId")
                or sender.get("incarnation") != grant.get("peerIncarnation")):
            raise Closed("Admission audit is obsolete")
        local = self.mysql()
        if (not member_view(local, self.config, 2) or local["viewId"] != grant.get("viewId")
                or local["processIdentity"] != grant.get("processIdentity")):
            raise Closed("Admission data-plane view changed")
        return state, local

    def _same_gtids(self, left, right):
        return (isinstance(left,str) and isinstance(right,str)
                and (not left or valid_gtids(left)) and (not right or valid_gtids(right))
                and self.actuator.gtid_subset(left, right) and self.actuator.gtid_subset(right, left))

    def audit_begin(self, payload, sender):
        if not re.fullmatch(r"[0-9a-f]{64}", payload.get("auditId", "")):
            raise Closed("Invalid admission audit identity")
        # The temporary audit coordinator is selected from current controller
        # incarnations, never a permanent writer or preferred server.
        if (sender["incarnation"], sender["nodeId"]) >= (self.incarnation, self.config["nodeId"]):
            raise Closed("Another current controller coordinates this admission")
        local = self.mysql()
        with self.lock:
            if (self.state["phase"] not in ("JOINING", "SINGLE", "DUAL", "RECONCILING")
                    or self.state["revision"] != payload.get("expectedRevision")
                    or not member_view(local, self.config, 2) or local["viewId"] != payload.get("viewId")
                    or local["processIdentity"] != payload.get("processIdentity")):
                raise Closed("Admission begin was superseded")
            self._save("RECONCILING", {"auditId": payload["auditId"], "viewId": local["viewId"],
                       "processIdentity": local["processIdentity"], "peerBootId": sender["bootId"],
                       "peerIncarnation": sender["incarnation"]})
        self.actuator.quiesce()
        _, current = self._audit_context(payload, sender)
        if current["readOnly"] != 1:
            raise Closed("Admission freeze did not complete")
        return self.snapshot()

    def audit_wait(self, payload, sender):
        _, local = self._audit_context(payload, sender)
        if local["readOnly"] != 1 or (payload.get("gtidSet") and not self.actuator.wait_gtids(payload["gtidSet"])):
            raise Closed("Frozen peer did not apply the required GTIDs")
        return self.snapshot()

    def audit_fingerprint(self, payload, sender):
        state, before = self._audit_context(payload, sender)
        if before["readOnly"] != 1 or self.actuator.prepared_xa() or not self.actuator.writes_drained():
            raise Closed("Frozen admission has unresolved legacy transactions")
        fingerprint = self.actuator.fingerprint()
        _, after = self._audit_context(payload, sender)
        if (after["readOnly"] != 1 or not self._same_gtids(before["gtidExecuted"], after["gtidExecuted"])
                or self.actuator.prepared_xa() or not self.actuator.writes_drained()):
            raise Closed("Frozen content changed during the full audit")
        with self.lock:
            if self.state != state:
                raise Closed("Admission changed during the full audit")
            self._save("RECONCILING", {**state["grant"], "auditFingerprint": fingerprint,
                       "auditedGtidSet": after["gtidExecuted"]})
        return {**self.snapshot(), "fingerprint": fingerprint}

    def audit_arm(self, payload, sender):
        state, local = self._audit_context(payload, sender)
        grant = state["grant"]
        if (state["revision"] != payload.get("expectedRevision") or local["readOnly"] != 1
                or not grant.get("auditFingerprint") or grant["auditFingerprint"] != payload.get("fingerprint")
                or not self._same_gtids(local["gtidExecuted"], payload.get("gtidSet", ""))
                or not self._same_gtids(local["gtidExecuted"], grant["auditedGtidSet"])
                or self.actuator.prepared_xa() or not self.actuator.writes_drained()):
            raise Closed("Full admission proof is not current")
        with self.lock:
            if self.state != state:
                raise Closed("Admission arm was superseded")
            self._save("DUAL", grant)
        return self.snapshot()

    def audit_enable(self, payload, sender):
        state, local = self._audit_context(payload, sender, "DUAL")
        peer = self._peer()
        other = peer.get("mysql")
        grant = state["grant"]
        if (peer.get("phase") != "DUAL" or not member_view(other, {**self.config,"nodeId":self.config["peerNodeId"]}, 2)
                or other["viewId"] != local["viewId"] or peer["bootId"] != grant["peerBootId"]
                or peer["incarnation"] != grant["peerIncarnation"]
                or any(peer.get("grant", {}).get(k) != grant.get(k) for k in ("auditId", "auditFingerprint"))
                or not self._same_gtids(local["gtidExecuted"], grant["auditedGtidSet"])
                or not self._same_gtids(other["gtidExecuted"], grant["auditedGtidSet"])
                or self.actuator.prepared_xa() or not self.actuator.writes_drained()):
            raise Closed("Both durable admission grants are not current")
        with self.lock:
            if self.state != state:
                raise Closed("Admission enable was superseded")
            self.actuator.enable_writes()
        return self.snapshot()

    def arm_dual(self):
        if not self.audit_lock.acquire(False):
            raise Closed("Another local admission is in progress")
        try:
            local, peer = self.mysql(), self._peer()
            remote = peer.get("mysql")
            with self.lock:
                state = dict(self.state)
            if (state["phase"] not in ("JOINING", "DUAL", "SINGLE", "RECONCILING")
                    or not member_view(local,self.config,2)
                    or not member_view(remote,{**self.config,"nodeId":self.config["peerNodeId"]},2)
                    or local["viewId"] != remote["viewId"]
                    or peer.get("phase") not in ("JOINING","DUAL","SINGLE","RECONCILING")):
                raise Closed("Both members are not in one controlled data-plane view")
            grant=state.get("grant") or {}
            if (state["phase"]=="DUAL" and peer.get("phase")=="DUAL" and grant.get("auditFingerprint")
                    and local["readOnly"]==0 and remote["readOnly"]==0
                    and grant.get("viewId")==local["viewId"] and grant.get("processIdentity")==local["processIdentity"]
                    and grant.get("peerBootId")==peer["bootId"] and grant.get("peerIncarnation")==peer["incarnation"]
                    and all(peer.get("grant",{}).get(k)==grant.get(k) for k in ("auditId","auditFingerprint"))):
                return self.snapshot()
            if peer.get("admissionProtocol") != 3:
                raise Closed("Peer does not support the current admission protocol")
            if (self.incarnation,self.config["nodeId"]) > (peer["incarnation"],peer["nodeId"]):
                return self.snapshot()  # The other current incarnation coordinates.
            audit=token()
            context={"auditId":audit,"viewId":local["viewId"],"processIdentity":local["processIdentity"],
                     "peerBootId":peer["bootId"],"peerIncarnation":peer["incarnation"]}
            with self.lock:
                if self.state != state:
                    raise Closed("Admission was superseded before freeze")
                self._save("RECONCILING",context)
            self.actuator.quiesce()
            begin=self.rpc("audit-begin",{"auditId":audit,"viewId":local["viewId"],"expectedRevision":peer["revision"],
                           "processIdentity":remote["processIdentity"]})
            other_session={k:peer[k] for k in ("nodeId","bootId","incarnation")}
            current=self.mysql()
            remote=begin["mysql"]
            if remote["gtidExecuted"] and not self.actuator.wait_gtids(remote["gtidExecuted"]):
                raise Closed("Local admission catchup failed")
            self.rpc("audit-wait",{"auditId":audit,"gtidSet":current["gtidExecuted"]})
            remote_proof=self.rpc("audit-fingerprint",{"auditId":audit})
            own_proof=self.audit_fingerprint({"auditId":audit},other_session)
            if (own_proof["fingerprint"] != remote_proof.get("fingerprint")
                    or not self._same_gtids(own_proof["mysql"]["gtidExecuted"],remote_proof["mysql"]["gtidExecuted"])):
                raise Closed("Complete reconciled data or GTIDs differ")
            proof={"auditId":audit,"fingerprint":own_proof["fingerprint"],"gtidSet":own_proof["mysql"]["gtidExecuted"]}
            self.rpc("audit-arm",{**proof,"expectedRevision":remote_proof["revision"]})
            self.audit_arm({**proof,"expectedRevision":own_proof["revision"]},other_session)
            self.rpc("audit-enable",{"auditId":audit})
            self.audit_enable({"auditId":audit},other_session)
            return self.snapshot()
        finally:
            # A failed/expired audit never restores the old grant or disables SRO.
            self.audit_lock.release()

    def reconcile_seed(self):
        with self.lock:
            if self.state["phase"] != "RECONCILING":
                raise Closed("Only a quarantined reconciliation may recover its existing view")
        # Use exactly the normal bilateral persistent-fence proof, but never
        # issue SINGLE authority. This is only a temporary, closed group seed.
        return self.takeover(reconcile_only=True)

    def takeover(self, reconcile_only=False):
        with self.lock:
            if self.broken or self.state["phase"] not in (("RECONCILING",) if reconcile_only else ("DUAL", "SINGLE")):
                raise Closed("Only an existing admitted survivor or closed reconciliation may recover a view")
            current = dict(self.state)
        local = self.mysql()
        if not durable_mysql(local, self.config) or not self._same_mysql_process(local):
            raise Closed("Survivor database process changed")
        if self.actuator.prepared_xa():
            raise Closed("Legacy prepared XA prevents takeover")
        observed = self._peer()
        if observed.get("phase") != "FENCED" or observed.get("fenced") is not True:
            raise Closed("Peer has not established its local failure fence")
        peer = self.rpc("fence", {"expectedRevision": observed["revision"],
                                   "expectedBootId": observed["bootId"],
                                   "expectedIncarnation": observed["incarnation"]})
        if (peer.get("phase") != "FENCED" or peer.get("fenced") is not True
                or any(peer.get(k) != observed.get(k) for k in ("bootId", "incarnation", "revision"))):
            raise Closed("Peer has not proven persistent isolation")
        old_grant = current.get("grant") or {}
        if (current["phase"] == "SINGLE" and writable_view(local, self.config, 1)
                and local["viewId"] == old_grant.get("viewId")
                and all(peer.get(k) == old_grant.get("peer" + k[0].upper() + k[1:]) for k in ("bootId", "incarnation", "revision"))):
            return self.snapshot()
        with self.lock:
            if self.state != current:
                raise Closed("Survivor grant changed before isolation")
            self._save("PROMOTING")
            promoting = dict(self.state)
        try:
            # The peer fence is already proven. Waiting/retrying here is only
            # for MySQL's view transition; elapsed time never supplies a fence.
            deadline = self.monotonic() + 30
            while not writable_view(local, self.config, 1):
                try:
                    self.actuator.promote_single()
                except Exception:
                    pass
                local = self.mysql()
                if writable_view(local, self.config, 1):
                    break
                if self.monotonic() >= deadline:
                    raise Closed("Survivor has not reached its one-member view")
                time.sleep(0.25)
            latest = self._peer()
            if (latest.get("phase") != "FENCED" or latest.get("fenced") is not True
                    or any(latest.get(k) != peer.get(k) for k in ("bootId", "incarnation", "revision"))):
                raise Closed("Peer fence changed during takeover")
            grant = {"viewId": local["viewId"], "processIdentity": local["processIdentity"],
                     "peerBootId": peer["bootId"], "peerIncarnation": peer["incarnation"], "peerRevision": peer["revision"]}
            with self.lock:
                if self.state != promoting:
                    raise Closed("Another transition superseded takeover")
                self._save("RECONCILING" if reconcile_only else "SINGLE", grant)
            if reconcile_only:
                self.actuator.quiesce()
        except Exception:
            self.fence("takeover-failed")
            raise Closed("Takeover did not establish safe authority") from None
        return self.snapshot()

    def guard(self, boot_id):
        with self.lock:
            return (not self.broken and boot_id == self.boot_id and self.state["phase"] == "JOINING"
                    and self.state.get("grant", {}).get("startupPermit") is True)

    def tick(self):
        if not self.host_policy():
            self.fence("privileged-port-policy-lost")
            return
        with self.lock:
            phase = self.state["phase"]
            observed_state = dict(self.state)
        if phase == "FENCING":
            with self.lock:
                if self.state == observed_state:
                    self.fence("retry-isolation")
        elif phase == "FENCED":
            with self.lock:
                if self.state == observed_state and not self.actuator.is_fenced():
                    self.fence("persistent-isolation-changed")
        elif phase in ("DUAL", "SINGLE"):
            local = self.mysql()
            if not durable_mysql(local, self.config) or not self._same_mysql_process(local):
                with self.lock:
                    if self.state == observed_state:
                        self.fence("database-failed-or-restarted")
                return
            try:
                peer = self._peer()
            except Exception:
                return  # No communication is never an isolation proof.
            with self.lock:
                if self.state != observed_state:
                    return
            if peer.get("phase") == "FENCED" and peer.get("fenced") is True:
                if self.config.get("automaticTakeover") is True:
                    # Repeated ticks do not mint a new permit for an unchanged
                    # existing single grant.
                    grant = self.state.get("grant") or {}
                    same = phase == "SINGLE" and all(peer.get(k) == grant.get("peer" + k[0].upper() + k[1:]) for k in ("bootId", "incarnation", "revision"))
                    if not same:
                        self.takeover()
            elif peer.get("phase") in ("JOINING", "DUAL", "SINGLE", "RECONCILING"):
                try:
                    self.arm_dual()
                except Closed:
                    pass
        elif phase in ("JOINING", "RECONCILING"):
            if phase == "RECONCILING":
                local = self.mysql()
                if not durable_mysql(local,self.config) or not self._same_mysql_process(local):
                    with self.lock:
                        if self.state == observed_state:
                            self.fence("reconciling-database-failed-or-restarted")
                    return
            try:
                self.arm_dual()
            except Closed:
                pass

    def capacity(self):
        config = self.config.get("capacity", {})
        result = {"reserveGuaranteed": False, "capacityModelValidated": config.get("validated") is True,
                  "boundaryPolicy": "ext4-root-plus-kernel-v1", "floorBytes": 4_000_000_000,
                  "globalReserveEnforcement": False,
                  "protectionScope": "ordinary data allocation; kernel metadata and privileged writers can cross",
                  "physicalBytesPerPayloadByte": config.get("physicalBytesPerPayloadByte", 4),
                  "fixedOverheadBytes": config.get("fixedOverheadBytes", 67108864), "volumes": []}
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.settimeout(2)
                connection.connect(config["reserveSocket"])
                connection.sendall(b"status\n")
                data = b""
                while not data.endswith(b"\n"):
                    part = connection.recv(65536)
                    if not part or len(data) + len(part) > 65536:
                        raise Closed("Invalid private reserve observation")
                    data += part
            value = json.loads(data)
            volumes = value.get("volumes")
            if (value.get("boundaryPolicy") != "ext4-root-plus-kernel-v1" or value.get("floorBytes") != 4_000_000_000
                    or not isinstance(volumes, list) or not volumes or any(not isinstance(v, dict)
                    or not isinstance(v.get("availableBytes"), int) or isinstance(v.get("availableBytes"), bool)
                    or v["availableBytes"] < 0 for v in volumes)):
                raise Closed("Invalid real volume observation")
            for volume in volumes:
                integer_fields = ("blockSize", "clusterSize", "rootReservedBytes", "kernelReservedBytes", "kernelReservedClusters",
                                  "aggregateReservedBytes", "physicalFreeBytes", "ordinaryAvailableBytes", "reservedUid", "reservedGid",
                                  "mountedReservedUid", "mountedReservedGid")
                if any(type(volume.get(field)) is not int or volume[field] < 0 for field in integer_fields):
                    raise Closed("Incomplete aggregate reserve observation")
                block = volume["blockSize"]
                if (volume["reservedUid"] <= 0 or volume["reservedGid"] <= 0
                        or volume["mountedReservedUid"] != volume["reservedUid"]
                        or volume["mountedReservedGid"] != volume["reservedGid"]
                        or block <= 0 or block & (block - 1) or volume["clusterSize"] < block
                        or volume["clusterSize"] % block or volume["kernelReservedClusters"] <= 0
                        or volume["kernelReservedBytes"] != volume["kernelReservedClusters"] * volume["clusterSize"]
                        or volume["aggregateReservedBytes"] != volume["rootReservedBytes"] + volume["kernelReservedBytes"]
                        or volume["aggregateReservedBytes"] != ((4_000_000_000 + block - 1) // block) * block
                        or volume["physicalFreeBytes"] - volume["ordinaryAvailableBytes"] != volume["aggregateReservedBytes"]
                        or volume["availableBytes"] != min(volume["physicalFreeBytes"], volume["ordinaryAvailableBytes"] + 4_000_000_000)):
                    raise Closed("Inconsistent aggregate reserve observation")
            result["reserveGuaranteed"] = value.get("guaranteed") is True
            result["volumes"] = volumes
        except Exception:
            pass
        return result

    def application_probe(self, check):
        url = check.get("url", "")
        if not re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}/[^\s]*", url):
            return False
        request = urllib.request.Request(url, headers={"Authorization": "Bearer " + self.app_token,
                                                       "Cache-Control": "no-store"})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_args, **_kwargs):
                return None
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect).open(request, timeout=4) as response:
                return response.status == check.get("expectedStatus", 204)
        except Exception:
            return False

    def status(self):
        try:
            grant = self.authority()
        except Exception:
            grant = None
        snapshot = self.mysql()
        capacity = self.capacity()
        checks = self.config.get("tomcatChecks", [])
        return {"nodeId": self.config["nodeId"], "observedAtMillis": int(time.time() * 1000),
                "mysqlReady": grant is not None, "tomcatReady": bool(checks) and all(self.application_probe(x) for x in checks),
                "replicationMode": grant["mode"] if grant else None,
                "singleNodeAuthorized": grant is not None and grant["mode"] == "single",
                "onlineNodeIds": sorted(self.config["memberNodeIds"][m["id"]] for m in snapshot["members"]
                                        if m["id"] in self.config["memberNodeIds"] and m["state"] == "ONLINE") if snapshot else [],
                "configuredNodeIds": [1, 2], "memberUuid": snapshot["mysqlUuid"] if snapshot else None,
                "viewId": grant["viewId"] if grant else None, "membershipDigest": self.membership,
                "controllerBootId": self.boot_id, "authorityEpoch": grant["epoch"] if grant else None,
                **capacity}

    def ready(self):
        try:
            before = self.authority()
            status = self.status()
            if (not status["tomcatReady"] or not status["reserveGuaranteed"]
                    or not status["capacityModelValidated"]
                    or any(v["availableBytes"] < 4_000_000_000 for v in status["volumes"])):
                return False
            if before["mode"] == "dual":
                peer_capacity = self.rpc("capacity")
                if (peer_capacity.get("reserveGuaranteed") is not True or peer_capacity.get("capacityModelValidated") is not True
                        or not peer_capacity.get("volumes")
                        or any(v.get("availableBytes", -1) < 4_000_000_000 for v in peer_capacity["volumes"])):
                    return False
            recoveries = self.config.get("recoveryChecks", [])
            if len(recoveries) != 3 or not all(self.application_probe({"url": x}) for x in recoveries):
                return False
            return before["permit"] == self.authority()["permit"]
        except Exception:
            return False


def authority_headers(grant):
    return {"X-Four-Project-MySQL-UUID": grant["mysqlUuid"], "X-Four-Project-View-ID": grant["viewId"],
            "X-Four-Project-Epoch": grant["epoch"], "X-Four-Project-Mode": grant["mode"],
            "X-Four-Project-Membership": grant["membership"],
            "X-Four-Project-Controller-Boot-ID": grant["bootId"],
            "X-Four-Project-Permit": grant["permit"], "X-Four-Project-Authority-Stamp": grant["permit"]}


def server_for(controller):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def authorized(self):
            expected = "Bearer " + controller.app_token
            return hmac.compare_digest(self.headers.get("Authorization", ""), expected)

        def reply(self, status, body=None, headers=None):
            raw = b"" if body is None else canonical(body)
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(raw)))
            if body is not None:
                self.send_header("Content-Type", "application/json")
            for name, value in (headers or {}).items():
                self.send_header(name, str(value))
            self.end_headers()
            if raw:
                self.wfile.write(raw)

        def read_json(self):
            length = self.headers.get("Content-Length", "")
            if not length.isdigit() or not 1 <= int(length) <= 131072 or self.headers.get("Transfer-Encoding"):
                raise Closed("Invalid bounded private request")
            return json.loads(self.rfile.read(int(length)))

        def do_GET(self):
            if not self.authorized():
                self.reply(403)
                return
            try:
                if self.path == "/authority":
                    self.reply(204, headers=authority_headers(controller.authority()))
                elif self.path == "/status":
                    self.reply(200, controller.status())
                elif self.path == "/ready":
                    self.reply(204 if controller.ready() else 503)
                else:
                    self.reply(404)
            except Exception:
                self.reply(503)

        def do_POST(self):
            try:
                if self.path in ("/peer/hello", "/peer/action"):
                    self.reply(200, controller.peer_request(self.path, self.read_json()))
                elif self.path == "/confirm":
                    if not self.authorized():
                        self.reply(403)
                        return
                    grant = controller.confirm(self.read_json())
                    self.reply(204, headers={**authority_headers(grant), "X-Four-Project-Durability": grant["mode"]})
                elif self.path == "/admin":
                    body = self.read_json()
                    if not isinstance(body, dict) or body.get("kind") != "local-root-admin":
                        self.reply(403)
                        return
                    self.reply(200, controller.admin_request(body))
                else:
                    self.reply(404)
            except Exception:
                # Close on malformed/stale/unavailable requests. Exact reasons
                # stay in test assertions; no credential-bearing body is logged.
                self.reply(503)

        def log_message(self, *_args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", controller.config["listenPort"]), Handler)
    server.daemon_threads = True
    return server


def read_config(path):
    if os.geteuid() != 0:
        raise Closed("Root is required for the installed controller")
    config = json.loads(private_file(path, 0))
    validate_config(config)
    config["_configPath"] = str(Path(path).resolve())
    return config


def admin_request(config, action):
    key = private_file(config["peerSecretFile"], 0).decode().strip()
    membership = digest(validate_config(config))
    boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *_args, **_kwargs):
            return None
    def exchange(action, **fields):
        value = {"kind": "local-root-admin", "clusterId": config["clusterId"], "membership": membership,
                 "nodeId": config["nodeId"], "bootId": boot, "nonce": token(), "action": action, **fields}
        value["signature"] = hmac.new(key.encode(), canonical(value), hashlib.sha256).hexdigest()
        request = urllib.request.Request("http://127.0.0.1:" + str(config["listenPort"]) + "/admin",
                                         data=canonical(value), method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect).open(request, timeout=300 if action in ("bootstrap", "bootstrap-latest", "arm-dual") else 90) as response:
            if response.status != 200:
                raise Closed("Administrative transition did not complete")
            raw = response.read(8193)
            if len(raw) > 8192:
                raise Closed("Invalid root response size")
            reply = json.loads(raw)
        signature = reply.pop("signature", "")
        if (not isinstance(signature, str) or not hmac.compare_digest(signature, hmac.new(key.encode(), canonical(reply), hashlib.sha256).hexdigest())
                or reply.get("kind") != "local-root-admin-response" or reply.get("clusterId") != config["clusterId"]
                or reply.get("membership") != membership or reply.get("nodeId") != config["nodeId"]
                or reply.get("bootId") != boot or reply.get("requestNonce") != value["nonce"]):
            raise Closed("Root response authentication failed")
        return reply
    hello = exchange("hello")
    result = exchange(action, incarnation=hello["incarnation"], challenge=hello["body"]["challenge"])
    return result["body"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["serve", "guard", "port-guard", "fence-local", "fence", "prepare", "bootstrap", "bootstrap-latest", "join", "arm-dual", "takeover", "reconcile-seed", "reload-observations"])
    parser.add_argument("--config")
    args = parser.parse_args()
    if args.action == "port-guard":
        if not privileged_port_boundary():
            raise Closed("Privileged private ports are not protected by the host")
        return
    if not args.config:
        raise Closed("Supply a private controller configuration")
    config = read_config(args.config)
    if args.action == "guard":
        admin_request(config, "start-allowed")
        return
    if args.action != "serve" and args.action != "fence-local":
        result = admin_request(config, args.action)
        print("node=" + str(result["nodeId"]) + " phase=" + result["phase"] + " revision=" + str(result["revision"]))
        return
    store = StateStore(config["stateDir"], 0)
    controller = Controller(config, MySQLActuator(config), store,
                            private_file(config["peerSecretFile"], 0).decode().strip(),
                            private_file(config["appTokenFile"], 0).decode().strip())
    if args.action == "fence-local":
        store.close()
        return
    if not privileged_port_boundary():
        raise Closed("Privileged private ports are not protected by the host")
    server = server_for(controller)
    def watchdog():
        while True:
            try:
                controller.tick()
            except Exception:
                # A failed transition never exposes an admitted grant. The next
                # pass can retry isolation, not turn a timeout into promotion.
                pass
            time.sleep(1)
    threading.Thread(target=watchdog, name="peer-database-watchdog", daemon=True).start()
    try:
        server.serve_forever()
    finally:
        server.server_close()
        store.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Peer controller operation failed closed; inspect private state and service status") from None
