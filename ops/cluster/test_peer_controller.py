import concurrent.futures
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import socketserver
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from types import SimpleNamespace
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("peer_controller", Path(__file__).with_name("peer_controller.py"))
pc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pc)

GROUP = "10000000-0000-4000-8000-000000000000"
ONE = "10000000-0000-4000-8000-000000000001"
TWO = "10000000-0000-4000-8000-000000000002"
GTIDS = GROUP + ":1"
PEER_KEY = "isolated-test-peer-key-" + "p" * 40
APP_KEY = "isolated-test-app-key-" + "a" * 40


def config(node):
    return {"nodeId": node, "peerNodeId": 3 - node, "clusterId": "isolated-two-peer-tests",
            "groupId": GROUP, "memberNodeIds": {ONE: 1, TWO: 2}, "listenPort": 940 + node,
            "peerPort": 943 - node, "automaticTakeover": True, "groupFragmentBytes": 1048576, "auditSchemas": ["isolated_business"],
            "tls": {"caFile": "/isolated/ca.pem", "certFile": "/isolated/cert.pem", "keyFile": "/isolated/key.pem"}}


class MemoryStore:
    def __init__(self, old=None):
        self.value = copy.deepcopy(old or {})
        self.history = []
        self.fail = False

    def read(self):
        return copy.deepcopy(self.value)

    def write(self, value):
        if self.fail:
            raise OSError("synthetic fsync failure")
        self.value = copy.deepcopy(value)
        self.history.append(copy.deepcopy(value))


class FakeMySQL:
    """A data-plane model, never confused with the real MySQL acceptance test."""
    def __init__(self, node):
        self.node = node
        self.running = False
        self.masked = False
        self.readonly = 1
        self.members = []
        self.view = None
        self.process = {"pid": 100 + node, "startTicks": "1"}
        self.executed = GTIDS
        self.waited = []
        self.fences = 0
        self.promotions = 0
        self.has_xa = False
        self.has_writers = False
        self.fail_fence = False
        self.fail_wait = False
        self.fingerprint_value = "b" * 64
        self.peer = None
        self.defaults = {"communicationFragmentBytes":1048576,"serverId":node,"autoIncrementStep":7,"autoIncrementOffset":node,"groupAutoIncrementStep":7,"groupId": GROUP, "singlePrimary": 0, "consistency": "BEFORE_AND_AFTER",
                         "enforceEverywhere":1,"eventScheduler":"OFF","enforceGtidConsistency":"ON","persistedGlobalsLoad":0,
                         "groupAutoStart":0,"bootstrapGroup":0,"autoRejoinTries":0,"unreachableMajorityTimeout":0,
                         "exitStateAction":"READ_ONLY","preserveCommitOrder":1,"binlogFormat":"ROW","offlineMode":0,
                         "mysqlXState":"DISABLED","runtimeGuardIdentityVerified":True,
                         "communicationStack": "MYSQL", "secureTransport": 1, "tlsVersion": "TLSv1.3",
                         "groupSSLMode": "VERIFY_IDENTITY", "recoverySSL": 1, "recoveryVerify": 1,
                         "recoveryTLSVersion": "TLSv1.3", "recoveryGetPublicKey": 0,
                         "serverCA": "/isolated/ca.pem", "recoveryCA": "/isolated/ca.pem",
                         "serverCert": "/isolated/cert.pem", "recoveryCert": "/isolated/cert.pem",
                         "serverKey": "/isolated/key.pem", "recoveryKey": "/isolated/key.pem", "flush": 1, "syncBinlog": 1, "doublewrite": "ON", "gtidMode": "ON",
                         "logBin": 1, "logReplicaUpdates": 1}

    def snapshot(self):
        if not self.running:
            raise pc.Closed("synthetic SQL down")
        return {**self.defaults, "mysqlUuid": ONE if self.node == 1 else TWO,
                "readOnly": self.readonly, "ordinaryReadOnly": self.readonly, "members": copy.deepcopy(self.members),
                "viewId": self.view, "gtidExecuted": self.executed,
                "processIdentity": copy.deepcopy(self.process)}

    def is_fenced(self):
        return self.masked and not self.running

    def fence(self):
        self.fences += 1
        if self.fail_fence:
            raise pc.Closed("synthetic unkillable database")
        self.running = False
        self.masked = True

    def start_readonly(self):
        self.masked = False
        self.running = True
        self.readonly = 1
        self.members = []
        self.view = None
        self.process["startTicks"] = str(int(self.process["startTicks"]) + 1)

    def quiesce(self):
        self.readonly = 1
        if self.has_writers:
            raise pc.Closed("synthetic old writer did not drain")

    def writes_drained(self):
        return self.readonly == 1 and not self.has_writers

    def enable_writes(self):
        self.readonly = 0

    def prepared_xa(self):
        return self.has_xa

    def promote_single(self):
        if self.has_xa:
            raise pc.Closed("synthetic unresolved legacy XA")
        self.promotions += 1
        self.readonly = 0
        self.members = [{"id": ONE if self.node == 1 else TWO, "state": "ONLINE", "role": "PRIMARY"}]
        self.view = "single-view-" + str(self.node)

    def bootstrap(self):
        self.promote_single()

    def join(self):
        self.executed = self.peer.executed
        for database in (self, self.peer):
            database.running = True
            database.readonly = 0
            database.members = [{"id": ONE, "state": "ONLINE", "role": "PRIMARY"},
                                {"id": TWO, "state": "ONLINE", "role": "PRIMARY"}]
            database.view = "dual-view-" + str(self.process["startTicks"])

    def fingerprint(self):
        return self.fingerprint_value

    def gtid_subset(self, left, right):
        return left == right or left == GTIDS and right == GROUP + ":1-3"

    def wait_gtids(self, value):
        if not pc.valid_gtids(value):
            raise pc.Closed("invalid GTID")
        self.waited.append(value)
        return not self.fail_wait


class Pair:
    def __init__(self, http=False):
        self.clock = 1.0
        self.stores = {i: MemoryStore() for i in (1, 2)}
        self.mysql = {i: FakeMySQL(i) for i in (1, 2)}
        self.controllers = {i: pc.Controller(config(i), self.mysql[i], self.stores[i], PEER_KEY, APP_KEY,
                                              boot_id="boot-" + str(i), monotonic=lambda: self.clock, host_policy=lambda: True) for i in (1, 2)}
        self.links = {(1, 2): True, (2, 1): True}
        self.servers = []
        for node in (1, 2):
            self.mysql[node].peer = self.mysql[3 - node]
            self.controllers[node].transport = lambda path, value, n=node: self.deliver(n, path, value)

    def deliver(self, source, path, value):
        if not self.links[(source, 3 - source)]:
            raise pc.Closed("synthetic partition")
        return self.controllers[3 - source].peer_request(path, copy.deepcopy(value))

    def dual(self):
        a, b = self.controllers[1], self.controllers[2]
        a.prepare()
        b.prepare()
        a.bootstrap()
        b.join()
        a.arm_dual()
        b.arm_dual()
        self.mysql[1].waited.clear(); self.mysql[2].waited.clear()
        return a, b

    def restart(self, node, old=None, reboot=False):
        self.stores[node] = MemoryStore(old if old is not None else self.stores[node].read())
        controller = pc.Controller(config(node), self.mysql[node], self.stores[node], PEER_KEY, APP_KEY,
                                   boot_id="reboot-" + str(node) if reboot else "boot-" + str(node), monotonic=lambda: self.clock, host_policy=lambda: True)
        self.controllers[node] = controller
        controller.transport = lambda path, value, n=node: self.deliver(n, path, value)
        return controller


class PersistentStoreTest(unittest.TestCase):
    def test_fsync_atomic_replace_permissions_and_exclusive_controller(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "state"
            store = pc.StateStore(directory)
            try:
                store.write({"revision": 1, "phase": "FENCED"})
                self.assertEqual({"phase": "FENCED", "revision": 1}, store.read())
                self.assertEqual(0o600, store.path.stat().st_mode & 0o777)
                with self.assertRaises(BlockingIOError):
                    pc.StateStore(directory)
                self.assertEqual(["controller.lock", "state.json"], sorted(p.name for p in directory.iterdir()))
            finally:
                store.close()

    def test_nonprivate_directory_and_state_symlink_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "state"
            directory.mkdir(mode=0o755)
            with self.assertRaises(pc.Closed):
                pc.StateStore(directory)
            directory.chmod(0o700)
            store = pc.StateStore(directory)
            try:
                (directory / "state.json").symlink_to(Path(temporary) / "outside")
                with self.assertRaises(OSError):
                    store.read()
            finally:
                store.close()


class PeerProtocolTest(unittest.TestCase):
    def test_guard_client_rejects_fake_local_listener_without_sending_root_secret(self):
        cfg = {**config(1), "peerSecretFile": "/private/test-peer.key"}
        seen = []
        class Response:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                pass
            def read(self, _limit):
                return pc.canonical({"kind": "local-root-admin-response", "signature": "0" * 64})
        def opened(request, **_kwargs):
            seen.append(request)
            return Response()
        with patch.object(pc, "private_file", return_value=PEER_KEY.encode()), patch.object(pc.Path, "read_text", return_value="boot-1"), patch.object(pc.urllib.request, "build_opener", return_value=SimpleNamespace(open=opened)), self.assertRaises(pc.Closed):
            pc.admin_request(cfg, "start-allowed")
        self.assertEqual(1, len(seen))
        self.assertNotIn(PEER_KEY.encode(), seen[0].data)
        self.assertNotIn("Authorization", seen[0].headers)

    def test_duplicate_delayed_and_old_incarnation_actions_are_rejected(self):
        pair = Pair()
        a, b = pair.controllers[1], pair.controllers[2]
        nonce = pc.token()
        hello = a._envelope({"action": "hello"}, nonce)
        response = a._verify(b.peer_request("/peer/hello", hello), True, nonce)
        envelope = a._envelope({"action": "echo", "challenge": response["body"]["challenge"], "payload": {}}, pc.token(), b.session())
        b.peer_request("/peer/action", envelope)
        with self.assertRaises(pc.Closed):
            b.peer_request("/peer/action", envelope)
        response = a._verify(b.peer_request("/peer/hello", hello), True, nonce)
        delayed = a._envelope({"action": "echo", "challenge": response["body"]["challenge"], "payload": {}}, pc.token(), b.session())
        pair.clock += 16
        with self.assertRaises(pc.Closed):
            b.peer_request("/peer/action", delayed)
        response = a._verify(b.peer_request("/peer/hello", hello), True, nonce)
        obsolete = a._envelope({"action": "echo", "challenge": response["body"]["challenge"], "payload": {}}, pc.token(), b.session())
        replacement = pair.restart(2)
        with self.assertRaises(pc.Closed):
            replacement.peer_request("/peer/action", obsolete)

    def test_signatures_configuration_and_response_nonce_bindings(self):
        pair = Pair()
        a, b = pair.controllers[1], pair.controllers[2]
        envelope = a._envelope({"action": "hello"}, pc.token())
        corrupt = copy.deepcopy(envelope)
        corrupt["sender"]["nodeId"] = 2
        with self.assertRaises(pc.Closed):
            b.peer_request("/peer/hello", corrupt)
        response = b.peer_request("/peer/hello", envelope)
        with self.assertRaises(pc.Closed):
            a._verify(response, True, pc.token())
        b.membership = "f" * 64
        with self.assertRaises(pc.Closed):
            b.peer_request("/peer/hello", envelope)

    def test_root_guard_actions_use_one_use_signed_requests_not_secret_bearers(self):
        pair = Pair()
        a = pair.controllers[1]
        def request(action, **fields):
            value = {"kind": "local-root-admin", "clusterId": a.config["clusterId"],
                     "membership": a.membership, "nodeId": 1, "bootId": a.boot_id,
                     "nonce": pc.token(), "action": action, **fields}
            return {**value, "signature": a.sign(value)}
        hello = a.admin_request(request("hello"))
        body = request("start-allowed", incarnation=a.incarnation, challenge=hello["body"]["challenge"])
        self.assertNotIn(PEER_KEY.encode(), pc.canonical(body))
        with self.assertRaises(pc.Closed):
            a.admin_request(body)  # Fenced does not permit startup.
        with self.assertRaises(pc.Closed):
            a.admin_request(body)  # Consumed even when the action was refused.
        a.prepare()
        hello = a.admin_request(request("hello"))
        valid = request("start-allowed", incarnation=a.incarnation, challenge=hello["body"]["challenge"])
        self.assertTrue(a.admin_request(valid)["body"]["allowed"])
        with self.assertRaises(pc.Closed):
            a.admin_request(valid)

    def test_one_way_and_two_way_partitions_close_both_authorities(self):
        pair = Pair()
        a, b = pair.dual()
        permit = a.authority()["permit"]
        for links in ({(1, 2): False, (2, 1): True}, {(1, 2): True, (2, 1): False}, {(1, 2): False, (2, 1): False}):
            pair.links = links
            for controller in (a, b):
                with self.assertRaises(pc.Closed):
                    controller.authority()
                controller.tick()
                self.assertEqual("DUAL", controller.state["phase"])
            self.assertEqual(1, pair.mysql[1].promotions)  # Only initial bootstrap.
        pair.links = {(1, 2): True, (2, 1): True}
        self.assertEqual(permit, a.authority()["permit"])


class AuthorityTest(unittest.TestCase):
    def test_unsafe_host_privileged_port_policy_closes_both_nodes(self):
        pair=Pair();a,b=pair.dual()
        a.host_policy=lambda:False
        for node in (a,b):
            with self.assertRaises(pc.Closed):node.authority()
        a.tick()
        self.assertTrue(pair.mysql[1].is_fenced())
        b.tick()
        self.assertEqual("DUAL",b.state["phase"])
        a.host_policy=lambda:True
        b.tick()
        self.assertEqual("single",b.authority()["mode"])

    def test_only_observation_policy_can_reload_without_changing_authority(self):
        pair = Pair()
        a, _ = pair.dual()
        a.config["_configPath"] = "/private/test-controller.json"
        before = a.authority()["permit"]
        candidate = {k: v for k, v in a.config.items() if k != "_configPath"}
        candidate["capacity"] = {"validated": False}
        with patch.object(pc, "private_file", return_value=pc.canonical(candidate)):
            a.reload_observations()
        self.assertEqual(before, a.authority()["permit"])
        candidate["listenPort"] += 1
        with patch.object(pc, "private_file", return_value=pc.canonical(candidate)), self.assertRaises(pc.Closed):
            a.reload_observations()

    def test_equal_primary_members_both_admitted_and_permits_are_stable(self):
        pair = Pair()
        a, b = pair.dual()
        first = a.authority()
        for _ in range(5):
            self.assertEqual(first, a.authority())
            self.assertEqual("dual", b.authority()["mode"])
        self.assertNotEqual(a.authority()["permit"], b.authority()["permit"])
        self.assertEqual(["PRIMARY", "PRIMARY"], [m["role"] for m in pair.mysql[2].members])

    def test_global_gr_durability_identity_or_process_change_closes(self):
        for change in ({"syncBinlog": 0}, {"flush": 2}, {"singlePrimary": 1}, {"consistency": "AFTER"}, {"doublewrite": "OFF"}, {"gtidMode": "OFF"}, {"logReplicaUpdates": 0}):
            pair = Pair()
            a, _ = pair.dual()
            pair.mysql[1].defaults.update(change)
            with self.subTest(change=change), self.assertRaises(pc.Closed):
                a.authority()
        pair = Pair()
        a, _ = pair.dual()
        pair.mysql[1].process["startTicks"] = "999"
        with self.assertRaises(pc.Closed):
            a.authority()
        a.tick()
        self.assertEqual("FENCED", a.state["phase"])

    def test_dual_confirmation_waits_both_and_lost_peer_or_grant_never_succeeds(self):
        pair = Pair()
        a, b = pair.dual()
        body = {"permit": a.authority()["permit"], "gtidSet": GTIDS, "operationId": str(uuid.uuid4())}
        self.assertEqual("dual", a.confirm(body)["mode"])
        self.assertEqual([GTIDS], pair.mysql[1].waited)
        self.assertEqual([GTIDS], pair.mysql[2].waited)
        pair.mysql[2].fail_wait = True
        with self.assertRaises(pc.Closed):
            a.confirm(body)
        pair.mysql[2].fail_wait = False
        original = pair.mysql[1].wait_gtids
        def transition(value):
            answer = original(value)
            b.fence()
            a.takeover()
            return answer
        pair.mysql[1].wait_gtids = transition
        with self.assertRaises(pc.Closed):
            a.confirm(body)

    def test_sql_injection_invalid_gtid_or_operation_identity_refused_before_wait(self):
        pair = Pair()
        a, _ = pair.dual()
        body = {"permit": a.authority()["permit"], "gtidSet": GTIDS, "operationId": str(uuid.uuid4())}
        for value in ("", "x' OR 1", "1:1", GROUP + ":0", GROUP + ":1;DROP DATABASE mysql"):
            with self.subTest(value=value), self.assertRaises(pc.Closed):
                a.confirm({**body, "gtidSet": value})
        self.assertEqual([], pair.mysql[1].waited)

    def test_only_real_fence_allows_takeover_and_new_writes_are_single_copy(self):
        pair = Pair()
        a, b = pair.dual()
        dual = a.authority()["permit"]
        pair.mysql[2].running = False
        b.tick()
        self.assertTrue(pair.mysql[2].is_fenced())
        a.tick()
        single = a.authority()
        self.assertEqual("single", single["mode"])
        self.assertNotEqual(dual, single["permit"])
        body = {"permit": single["permit"], "gtidSet": GTIDS, "operationId": str(uuid.uuid4())}
        self.assertEqual("single", a.confirm(body)["mode"])
        with self.assertRaises(pc.Closed):
            a.confirm({**body, "permit": dual})
        before = copy.deepcopy(a.state)
        a.tick()
        self.assertEqual(before, a.state)

    def test_failed_fence_or_failed_state_fsync_never_produces_isolation_proof(self):
        pair = Pair()
        a, b = pair.dual()
        pair.mysql[2].fail_fence = True
        with self.assertRaises(pc.Closed):
            a.takeover()
        self.assertNotEqual("SINGLE", a.state["phase"])
        self.assertFalse(b.snapshot()["fenced"])
        pair.mysql[2].fail_fence = False
        pair.stores[2].fail = True
        with self.assertRaises(pc.Closed):
            b.fence()
        self.assertTrue(pair.mysql[2].is_fenced())
        self.assertFalse(b.snapshot()["fenced"])
        with self.assertRaises(pc.Closed):
            b.authority()

    def test_controller_restart_host_reboot_and_old_disk_state_all_start_fenced(self):
        for reboot in (False, True):
            pair = Pair()
            a, b = pair.dual()
            old_state = pair.stores[2].read()
            old_incarnation = b.incarnation
            new = pair.restart(2, old_state, reboot)
            self.assertNotEqual(old_incarnation, new.incarnation)
            self.assertEqual(["FENCING", "FENCED"], [s["phase"] for s in pair.stores[2].history])
            self.assertTrue(pair.mysql[2].is_fenced())
            self.assertFalse(new.guard(new.boot_id))
            with self.assertRaises(pc.Closed):
                new.authority()
            with self.assertRaises(pc.Closed):
                a.authority()
            a.tick()
            self.assertEqual("single", a.authority()["mode"])

    def test_both_simultaneously_attempting_takeover_cannot_both_become_writers(self):
        pair = Pair()
        a, b = pair.dual()
        def run(controller):
            try:
                controller.takeover()
            except pc.Closed:
                pass
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(run, (a, b)))
        writable = []
        for controller in (a, b):
            try:
                writable.append(controller.authority()["mode"])
            except pc.Closed:
                pass
        self.assertLessEqual(sum(mode == "single" for mode in writable), 1)
        self.assertEqual(["dual", "dual"], writable)

    def test_controlled_readonly_rejoin_new_view_and_two_equal_writers(self):
        pair = Pair()
        a, b = pair.dual()
        pair.mysql[2].running = False
        b.tick()
        a.tick()
        previous = a.authority()["permit"]
        pair.mysql[1].executed = GROUP + ":1-3"
        b.prepare()
        self.assertEqual(1, pair.mysql[2].readonly)
        self.assertTrue(b.guard(b.boot_id))
        self.assertFalse(b.guard("previous-boot"))
        with self.assertRaises(pc.Closed):
            a.authority()
        b.join()
        a.arm_dual()
        b.arm_dual()
        self.assertEqual("dual", a.authority()["mode"])
        self.assertEqual("dual", b.authority()["mode"])
        self.assertNotEqual(previous, a.authority()["permit"])
        self.assertEqual(pair.mysql[1].executed, pair.mysql[2].executed)

    def test_errant_gtid_and_mismatched_bootstrap_data_are_refused(self):
        pair = Pair()
        a, b = pair.controllers[1], pair.controllers[2]
        a.prepare()
        b.prepare()
        pair.mysql[2].fingerprint_value = "c" * 64
        with self.assertRaises(pc.Closed):
            a.bootstrap()
        pair.mysql[2].fingerprint_value = pair.mysql[1].fingerprint_value
        a.bootstrap()
        pair.mysql[2].executed = TWO + ":90"
        with self.assertRaises(pc.Closed):
            b.join()

    def test_more_current_second_node_can_be_temporary_seed_then_both_write(self):
        pair = Pair()
        a, b = pair.controllers[1], pair.controllers[2]
        a.prepare()
        b.prepare()
        pair.mysql[2].executed = GROUP + ":1-3"
        with self.assertRaises(pc.Closed):
            a.bootstrap()
        b.bootstrap()
        a.join()
        a.arm_dual()
        b.arm_dual()
        self.assertEqual("dual", a.authority()["mode"])
        self.assertEqual("dual", b.authority()["mode"])


class HttpContractTest(unittest.TestCase):
    def setUp(self):
        self.pair = Pair()
        self.servers = []
        # Start on ephemeral ports, then wire each peer to the other's selected
        # port. The production config validator still rejects public addresses.
        for node in (1, 2):
            controller = self.pair.controllers[node]
            controller.config["listenPort"] = 0
            server = pc.server_for(controller)
            controller.config["listenPort"] = server.server_address[1]
            self.servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        for node in (1, 2):
            self.pair.controllers[node].config["peerPort"] = self.servers[2 - node].server_address[1]
            self.pair.controllers[node].transport = None
        self.a, self.b = self.pair.dual()

    def tearDown(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()

    def request(self, node, path, key=APP_KEY, body=None):
        url = "http://127.0.0.1:" + str(self.pair.controllers[node].config["listenPort"]) + path
        request = urllib.request.Request(url, data=pc.canonical(body) if body is not None else None,
                                         headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            return urllib.request.urlopen(request, timeout=8)
        except urllib.error.HTTPError as error:
            return error

    def test_http_authority_stable_permit_dual_confirm_and_token_separation(self):
        with self.request(1, "/authority") as response:
            self.assertEqual(204, response.status)
            permit = response.headers["X-Four-Project-Permit"]
            self.assertEqual(permit, response.headers["X-Four-Project-Authority-Stamp"])
            self.assertEqual("dual", response.headers["X-Four-Project-Mode"])
            self.assertEqual(ONE, response.headers["X-Four-Project-MySQL-UUID"])
        with self.request(1, "/authority") as response:
            self.assertEqual(permit, response.headers["X-Four-Project-Permit"])
        with self.request(1, "/confirm", body={"permit": permit, "gtidSet": GTIDS, "operationId": str(uuid.uuid4())}) as response:
            self.assertEqual(204, response.status)
            self.assertEqual("dual", response.headers["X-Four-Project-Durability"])
        with self.request(1, "/admin", body={"action": "fence"}) as response:
            self.assertEqual(403, response.code)
        with self.request(1, "/authority", key=PEER_KEY) as response:
            self.assertEqual(403, response.code)
        self.a.fence()
        with self.request(1, "/authority") as response:
            self.assertEqual(503, response.code)

    def test_concurrent_mutual_authority_callbacks_do_not_deadlock(self):
        def probe(node):
            with self.request(node, "/authority") as response:
                return response.status
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            self.assertEqual([204] * 12, list(pool.map(probe, [1, 2] * 6)))


class PhysicalCapacityContractTest(unittest.TestCase):
    def test_old_root_only_observer_is_rejected_and_dual_ready_needs_both_real_reserves(self):
        pair=Pair();a,b=pair.dual()
        volume={"id":"root","availableBytes":8_096_000_000,"physicalFreeBytes":8_096_002_048,
                "ordinaryAvailableBytes":4_096_000_000,"blockSize":4096,"clusterSize":4096,
                "rootReservedBytes":3_983_224_832,"kernelReservedClusters":4096,
                "kernelReservedBytes":16_777_216,"aggregateReservedBytes":4_000_002_048,
                "reservedUid":999,"reservedGid":998,"mountedReservedUid":999,"mountedReservedGid":998}
        value=[{"guaranteed":True,"boundaryPolicy":"ext4-root-plus-kernel-v1","floorBytes":4_000_000_000,"volumes":[volume]}]
        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                if self.rfile.readline(16)==b"status\n":self.wfile.write(pc.canonical(value[0])+b"\n")
        with tempfile.TemporaryDirectory(prefix="fpc-",dir="/tmp") as temporary:
            path=str(Path(temporary)/"reserve.sock")
            server=socketserver.ThreadingUnixStreamServer(path,Handler)
            threading.Thread(target=server.serve_forever,daemon=True).start()
            try:
                a.config["capacity"]={"validated":True,"reserveSocket":path}
                a.config["tomcatChecks"]=[{"url":"http://127.0.0.1:881/","expectedStatus":200}]
                a.config["recoveryChecks"]=["r1","r2","r3"]
                a.application_probe=lambda _check:True
                self.assertTrue(a.capacity()["reserveGuaranteed"])
                self.assertFalse(a.ready())  # Peer has no verified reserve.
                b.capacity=lambda:a.capacity()
                self.assertTrue(a.ready())
                value[0]={"guaranteed":True,"volumes":[{"id":"root","availableBytes":8_000_000_000}]}
                self.assertFalse(a.capacity()["reserveGuaranteed"])
                value[0]={"guaranteed":True,"boundaryPolicy":"ext4-root-plus-kernel-v1","floorBytes":4_000_000_000,
                          "volumes":[{**volume,"rootReservedBytes":4_000_002_048,"aggregateReservedBytes":4_016_779_264}]}
                self.assertFalse(a.capacity()["reserveGuaranteed"])
                for change in ({"mountedReservedUid":0},{"mountedReservedGid":0}):
                    value[0]={"guaranteed":True,"boundaryPolicy":"ext4-root-plus-kernel-v1","floorBytes":4_000_000_000,
                              "volumes":[{**volume,**change}]}
                    self.assertFalse(a.capacity()["reserveGuaranteed"])
                missing=dict(volume);missing.pop("mountedReservedUid")
                value[0]={"guaranteed":True,"boundaryPolicy":"ext4-root-plus-kernel-v1","floorBytes":4_000_000_000,"volumes":[missing]}
                self.assertFalse(a.capacity()["reserveGuaranteed"])
            finally:
                server.shutdown();server.server_close()


if __name__ == "__main__":
    unittest.main()


class MySQLActuatorStartupTests(unittest.TestCase):
    def test_type_simple_start_waits_for_authenticated_readonly_snapshot(self):
        actuator = object.__new__(pc.MySQLActuator)
        actuator.unit = "isolated-test-mysql.service"
        observations = [pc.Closed("socket not opened"), {"readOnly": 1, "members": []}]
        with patch.object(actuator, "command") as command, patch.object(actuator, "snapshot", side_effect=observations), patch.object(pc.time, "sleep"):
            actuator.start_readonly()
        self.assertEqual(command.call_count, 2)

    def test_start_does_not_admit_unquarantined_database(self):
        actuator = object.__new__(pc.MySQLActuator)
        actuator.unit = "isolated-test-mysql.service"
        with patch.object(actuator, "command"), patch.object(actuator, "snapshot", return_value={"readOnly": 0, "members": []}):
            with self.assertRaises(pc.Closed):
                actuator.start_readonly()


class FingerprintCanonicalTests(unittest.TestCase):
    def test_explicit_same_charset_is_redundant_with_named_collation(self):
        before="  `email` varchar(254) COLLATE utf8mb4_unicode_ci NOT NULL,"
        after="  `email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,"
        self.assertEqual(pc.canonical_create_table(before), pc.canonical_create_table(after))

    def test_collation_semantics_and_default_text_are_not_normalized(self):
        text="  `email` varchar(254) COLLATE utf8mb4_bin DEFAULT ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',"
        self.assertEqual(pc.canonical_create_table(text), text)
        self.assertNotEqual(pc.canonical_create_table(text), pc.canonical_create_table(text.replace("utf8mb4_bin", "utf8mb4_unicode_ci")))
        mismatch="  `email` varchar(254) CHARACTER SET latin1 COLLATE utf8mb4_unicode_ci NOT NULL,"
        self.assertEqual(pc.canonical_create_table(mismatch), mismatch)


class CommunicationStackTests(unittest.TestCase):
    def test_unauthenticated_xcom_stack_is_refused_for_final_policy(self):
        database=FakeMySQL(1)
        database.start_readonly()
        self.assertTrue(pc.durable_mysql(database.snapshot(), config(1)))
        database.defaults["communicationStack"]="XCOM"
        self.assertFalse(pc.durable_mysql(database.snapshot(), config(1)))


class MySQLTLSPolicyTests(unittest.TestCase):
    def test_each_missing_verification_control_closes_authority(self):
        database=FakeMySQL(1)
        database.start_readonly()
        for key, unsafe in (("secureTransport",0),("tlsVersion","TLSv1.2,TLSv1.3"),("groupSSLMode","REQUIRED"),
                            ("recoverySSL",0),("recoveryVerify",0),("recoveryTLSVersion","TLSv1.2"),
                            ("recoveryGetPublicKey",1),("recoveryCA","/wrong/ca.pem"),
                            ("recoveryCert",""),("recoveryKey","")):
            value=database.snapshot(); value[key]=unsafe
            self.assertFalse(pc.durable_mysql(value,config(1)),key)


class CompleteLiveSQLPolicyTests(unittest.TestCase):
    def test_each_missing_or_changed_critical_runtime_policy_closes_authority(self):
        db=FakeMySQL(1);db.start_readonly();baseline=db.snapshot()
        changes={"enforceEverywhere":0,"eventScheduler":"ON","enforceGtidConsistency":"OFF","persistedGlobalsLoad":1,
                 "groupAutoStart":1,"bootstrapGroup":1,"autoRejoinTries":3,"unreachableMajorityTimeout":10,
                 "exitStateAction":"OFFLINE_MODE","preserveCommitOrder":0,"binlogFormat":"STATEMENT","offlineMode":1,
                 "mysqlXState":"ACTIVE","runtimeGuardIdentityVerified":False}
        self.assertTrue(pc.durable_mysql(baseline,config(1)))
        for key,unsafe in changes.items():
            self.assertFalse(pc.durable_mysql({**baseline,key:unsafe},config(1)),key)
            missing=copy.deepcopy(baseline);del missing[key]
            self.assertFalse(pc.durable_mysql(missing,config(1)),key)
        self.assertFalse(pc.durable_mysql({**baseline,"eventScheduler":"DISABLED"},config(1)))

    def test_sha_bound_runtime_identity_paths_and_endpoint_match_are_required(self):
        a=object.__new__(pc.MySQLActuator);a.data="/isolated/data";a.config={"groupAddress":"127.0.0.1:331"}
        unit="[Service]\nExecStart=\nExecStart=/isolated/mysqld --datadir=/isolated/data --socket=/isolated/run/mysql.sock --tmpdir=/isolated/tmp --bind-address=127.0.0.1 --report-host=127.0.0.1 --port=331 --report-port=331 --group-replication-local-address=127.0.0.1:331 --group-replication-group-seeds=127.0.0.1:331,127.0.0.1:332 --loose-group-replication-member-expel-timeout=10\n"
        sample={"mysqlDataDir":"/isolated/data/","mysqlSocket":"/isolated/run/mysql.sock","mysqlTmpDir":"/isolated/tmp",
                "bindAddress":"127.0.0.1","reportHost":"127.0.0.1","sqlPort":331,"reportPort":331,
                "groupLocalAddress":"127.0.0.1:331","groupSeeds":"127.0.0.1:332,127.0.0.1:331","memberExpelTimeout":10}
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"guard.conf";path.write_text(unit);a.config["guardDropin"]=str(path)
            with patch.object(a,"guard_installed",return_value=True):
                self.assertTrue(a.runtime_identity_matches(sample))
                for field,wrong in (("sqlPort",1331),("reportPort",332),("reportHost","0.0.0.0"),("bindAddress","*"),
                                    ("groupLocalAddress","127.0.0.1:332"),("groupSeeds","127.0.0.1:331,127.0.0.1:999"),
                                    ("mysqlDataDir","/other/data"),("mysqlSocket","/tmp/mysql.sock"),("mysqlTmpDir","/tmp"),
                                    ("memberExpelTimeout",11)):
                    self.assertFalse(a.runtime_identity_matches({**sample,field:wrong}),field)
                path.write_text(unit+unit);self.assertFalse(a.runtime_identity_matches(sample))
            with patch.object(a,"guard_installed",return_value=False):self.assertFalse(a.runtime_identity_matches(sample))


class WriteDrainBarrierTests(unittest.TestCase):
    def status(self):
        return {"superReadOnly":1,"ordinaryReadOnly":1,"performanceSchema":1,"instrumentationConsumers":2,
                "metadataInstrument":1,"metadataLost":"0","threadsLost":"0","uninstrumentedClients":0,"observerKnown":1,
                "modifiedTransactions":0,"writeIntentLocks":0,"unknownLockTypes":0}

    def test_sro_alone_does_not_prove_idle_modified_transactions_or_write_locks_drained(self):
        a=object.__new__(pc.MySQLActuator)
        for field in ("modifiedTransactions","writeIntentLocks"):
            value=self.status();value[field]=1
            with patch.object(a,"sql",return_value=json.dumps(value)):
                self.assertFalse(a.writes_drained())
        with patch.object(a,"sql",return_value=json.dumps(self.status())):
            self.assertTrue(a.writes_drained())

    def test_disabled_incomplete_or_overflowed_instrumentation_never_proves_drain(self):
        a=object.__new__(pc.MySQLActuator)
        changes={"superReadOnly":0,"ordinaryReadOnly":0,"performanceSchema":0,"instrumentationConsumers":1,
                 "metadataInstrument":0,"metadataLost":"1","threadsLost":"1","uninstrumentedClients":1,
                 "modifiedTransactions":None,"writeIntentLocks":False,"unknownLockTypes":1,"observerKnown":0}
        for field,bad in changes.items():
            value={**self.status(),field:bad}
            with patch.object(a,"sql",return_value=json.dumps(value)),self.assertRaises(pc.Closed):a.writes_drained()
            value=self.status();del value[field]
            with patch.object(a,"sql",return_value=json.dumps(value)),self.assertRaises(pc.Closed):a.writes_drained()

    def test_quiesce_waits_for_actual_drain_and_timeout_never_succeeds(self):
        a=object.__new__(pc.MySQLActuator)
        with patch.object(a,"sql") as sql,patch.object(a,"writes_drained",side_effect=[False,False,True]),patch.object(pc.time,"sleep") as sleep:
            a.quiesce();self.assertEqual(2,sleep.call_count);sql.assert_called_once_with("SET GLOBAL super_read_only=ON",timeout=60)
        with patch.object(a,"sql"),patch.object(a,"writes_drained",return_value=False),patch.object(pc.time,"monotonic",side_effect=[0,61]),self.assertRaises(pc.Closed):a.quiesce()


class ProtectedDaemonIdentityTests(unittest.TestCase):
    def test_production_process_scan_never_counts_its_private_mysqldump_as_a_daemon(self):
        class Entry:
            def __init__(self,pid,command):self.name=str(pid);self.command=command
            def __truediv__(self,name):
                return SimpleNamespace(read_bytes=lambda:self.command,read_text=lambda:'1 (synthetic) '+' '.join(['S']+['0']*18+['123']))
        a=object.__new__(pc.MySQLActuator);a.config={'production':True};a.data='/protected/data'
        entries=[Entry(10,b'/usr/sbin/mysqld\0'),Entry(11,b'/usr/bin/mysqldump\0'),Entry(12,b'/usr/sbin/mysqld-debug\0')]
        with patch.object(pc.Path,'iterdir',return_value=iter(entries)):
            self.assertEqual([{'pid':10,'startTicks':'123'},{'pid':12,'startTicks':'123'}],a.processes())


class IncomingAdmissionVersionTests(unittest.TestCase):
    def test_every_incoming_audit_or_durable_action_requires_new_reverse_echo_protocol(self):
        pair=Pair();sender,receiver=pair.controllers[1],pair.controllers[2]
        actions=('audit-begin','audit-wait','audit-fingerprint','audit-arm','audit-enable','fence','wait','fingerprint','snapshot','capacity')
        before=copy.deepcopy(receiver.state)
        for action in actions:
            for declared in (None,2):
                nonce=pc.token();hello=sender._envelope({'action':'hello'},nonce)
                reply=sender._verify(receiver.peer_request('/peer/hello',hello),True,nonce)
                body={'action':action,'challenge':reply['body']['challenge'],'payload':{}}
                request=sender._envelope(body,pc.token(),reply['sender'])
                echo={'session':sender.session()}
                if declared is not None:echo['admissionProtocol']=declared
                with patch.object(receiver,'rpc',return_value=echo),self.assertRaises(pc.Closed):
                    receiver.peer_request('/peer/action',request)
                self.assertEqual(before,receiver.state)

    def test_authority_and_confirmation_require_current_peer_protocol(self):
        pair=Pair();a,b=pair.dual();grant=a.authority();original=b.snapshot
        with patch.object(b,'snapshot',side_effect=lambda:{**original(),'admissionProtocol':2}):
            with self.assertRaises(pc.Closed):a.authority()
            with self.assertRaises(pc.Closed):a.confirm({'permit':grant['permit'],'gtidSet':GTIDS,'operationId':str(uuid.uuid4())})


class PrivateContentHashTests(unittest.TestCase):
    def test_full_stream_changes_even_if_table_counts_and_32bit_checksum_do_not(self):
        import hashlib,sys
        for payload in ("synthetic-a", "synthetic-b"):
            self.assertEqual(pc.private_stream_digest([sys.executable,"-c","import sys;sys.stdout.write(sys.argv[1])",payload]),
                             hashlib.sha256(payload.encode()).hexdigest())

    def test_failed_or_hung_private_dump_never_produces_an_accepted_digest(self):
        import sys
        with self.assertRaises(pc.Closed):
            pc.private_stream_digest([sys.executable,"-c","raise SystemExit(1)"])
        with self.assertRaises(pc.Closed):
            pc.private_stream_digest([sys.executable,"-c","import time;time.sleep(5)"], timeout=0.02)


class CompleteSchemaDefinitionTests(unittest.TestCase):
    def setUp(self):
        self.actuator=object.__new__(pc.MySQLActuator)
        self.database="isolated\tCREATE DATABASE `isolated` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"

    def test_empty_object_inventory_still_hashes_database_defaults(self):
        with patch.object(self.actuator,"sql",side_effect=[self.database,"","","","",""]):
            first=self.actuator.schema_definitions("isolated")
        self.assertEqual([["database","isolated",self.database]],first)
        with patch.object(self.actuator,"sql",side_effect=[self.database.replace("utf8mb4_bin","utf8mb4_unicode_ci"),"","","","",""]):
            second=self.actuator.schema_definitions("isolated")
        self.assertNotEqual(pc.digest(first),pc.digest(second))

    def test_views_functions_procedures_triggers_and_events_are_all_included(self):
        trigger={"name":"trg","schema":"isolated","statement":"SET NEW.value=OLD.value+1",
                 "order":1,"definer":"isolated@localhost","sqlMode":"STRICT_TRANS_TABLES"}
        answers=[self.database,"v","v\tCREATE ALGORITHM=UNDEFINED VIEW `v` AS SELECT 1\tutf8mb4\tutf8mb4_bin",
                 "FUNCTION\tf\nPROCEDURE\tp","f\tmode\tCREATE DEFINER=`isolated`@`localhost` FUNCTION `f`() RETURNS INT RETURN 1",
                 "p\tmode\tCREATE DEFINER=`isolated`@`localhost` PROCEDURE `p`() SELECT 1",
                 "trg",json.dumps(trigger),"ev","ev\tmode\tUTC\tCREATE DEFINER=`isolated`@`localhost` EVENT `ev` ON SCHEDULE EVERY 1 DAY DO SELECT 1",
                 "lib","lib\tmode\tCREATE LIBRARY `lib` LANGUAGE WASM AS 0x0061736d"]
        with patch.object(self.actuator,"sql",side_effect=answers) as sql:
            result=self.actuator.schema_definitions("isolated")
        self.assertEqual(["database","view","function","procedure","trigger","event","library"],[row[0] for row in result])
        self.assertIn("JSON_DUALITY_VIEWS",sql.call_args_list[1].args[0])
        trigger_query=sql.call_args_list[-5].args[0]
        for field in ("ACTION_ORDER","ACTION_TIMING","SQL_MODE","DEFINER","ACTION_STATEMENT","COLLATION_CONNECTION"):
            self.assertIn(field,trigger_query)
        self.assertNotIn("CREATED",trigger_query)
        changed=copy.deepcopy(result);changed[-3][2]["order"]=2
        self.assertNotEqual(pc.digest(result),pc.digest(changed))

    def test_unknown_identifier_null_definition_and_partial_inventory_close(self):
        for answers in ([self.database,"bad-name"],
                        [self.database,"v","v\tNULL\tutf8mb4"],
                        [self.database,"","PACKAGE\tp"],
                        [self.database,"","","t",json.dumps({"name":"t","schema":"isolated","statement":None})]):
            with patch.object(self.actuator,"sql",side_effect=answers),self.assertRaises(pc.Closed):
                self.actuator.schema_definitions("isolated")

    def test_base_tables_dump_digest_alone_cannot_hide_schema_object_change(self):
        self.actuator.config={"auditSchemas":["isolated"]};self.actuator.defaults="/isolated/private.cnf"
        hashes=[]
        for definitions in ([["view","v","SELECT 1"]],[["view","v","SELECT 2"]]):
            with patch.object(self.actuator,"schema_definitions",return_value=definitions),patch.object(self.actuator,"sql",side_effect=["t","t ddl"]),patch.object(self.actuator,"allocator_validated",return_value=False),patch.object(pc,"private_stream_digest",return_value="a"*64):
                hashes.append(self.actuator.fingerprint())
        self.assertNotEqual(hashes[0],hashes[1])


class ReconciledAdmissionTests(unittest.TestCase):
    def prepared(self):
        pair=Pair();a,b=pair.controllers[1],pair.controllers[2]
        a.prepare();b.prepare();a.bootstrap();b.join()
        coord=min((a,b),key=lambda c:(c.incarnation,c.config["nodeId"]))
        return pair,a,b,coord

    def test_idle_old_writer_after_sro_stops_before_hashing_and_never_restores_grant(self):
        pair,a,b,coord=self.prepared();database=pair.mysql[coord.config['nodeId']]
        database.has_writers=True
        with patch.object(database,'fingerprint',wraps=database.fingerprint) as fingerprint,self.assertRaises(pc.Closed):
            coord.arm_dual()
        fingerprint.assert_not_called();self.assertEqual('RECONCILING',coord.state['phase'])
        self.assertNotIn('auditFingerprint',coord.state['grant'])
        for node in (a,b):
            with self.assertRaises(pc.Closed):node.authority()
        database.has_writers=False;coord.arm_dual()
        for node in (a,b):self.assertEqual('dual',node.authority()['mode'])

    def test_old_sro_only_admission_protocol_cannot_join_new_drain_audit(self):
        pair,a,b,coord=self.prepared();other=b if coord is a else a;original=other.snapshot
        with patch.object(other,'snapshot',side_effect=lambda:{**original(),'admissionProtocol':2}),self.assertRaises(pc.Closed):
            coord.arm_dual()
        for node in (a,b):
            with self.assertRaises(pc.Closed):node.authority()
        policy=pc.validate_config(config(1));old={**policy,'protocol':'atomic-multiprimary-2-v1'}
        self.assertNotEqual(pc.digest(policy),pc.digest(old))

    def test_late_writer_blocks_fingerprint_arm_and_enable_even_with_matching_audit(self):
        pair=Pair();a,b=pair.dual();database=pair.mysql[1]
        old=copy.deepcopy(a.state['grant']);database.readonly=1;database.has_writers=True
        a._save('RECONCILING',old)
        with patch.object(database,'fingerprint',wraps=database.fingerprint) as fingerprint,self.assertRaises(pc.Closed):
            a.audit_fingerprint({'auditId':old['auditId']},b.session())
        fingerprint.assert_not_called()
        with self.assertRaises(pc.Closed):
            a.audit_arm({'auditId':old['auditId'],'expectedRevision':a.state['revision'],
                         'fingerprint':old['auditFingerprint'],'gtidSet':old['auditedGtidSet']},b.session())
        a._save('DUAL',old)
        with patch.object(database,'enable_writes',wraps=database.enable_writes) as enable,self.assertRaises(pc.Closed):
            a.audit_enable({'auditId':old['auditId']},b.session())
        enable.assert_not_called()
        with self.assertRaises(pc.Closed):a.authority()

    def test_join_persists_reconciling_before_mysql_can_join_old_peer(self):
        pair=Pair();a,b=pair.controllers[1],pair.controllers[2]
        a.prepare();b.prepare();a.bootstrap()
        original=pair.mysql[2].join
        def joining():
            self.assertEqual("RECONCILING",b.state["phase"])
            self.assertEqual("RECONCILING",pair.stores[2].history[-1]["phase"])
            original()
        pair.mysql[2].join=joining;b.join()
        with self.assertRaises(pc.Closed):b.authority()

    def test_complete_data_gtids_and_legacy_xa_are_all_required_before_dual(self):
        for defect in ("data","gtids","xa"):
            pair,a,b,coord=self.prepared()
            if defect=="data":pair.mysql[2].fingerprint_value="f"*64
            if defect=="gtids":pair.mysql[2].executed=GROUP+":1-3"
            if defect=="xa":pair.mysql[2].has_xa=True
            with self.assertRaises(pc.Closed,msg=defect):coord.arm_dual()
            for c in (a,b):
                with self.assertRaises(pc.Closed):c.authority()
                self.assertEqual(1,pair.mysql[c.config["nodeId"]].readonly)

    def test_one_sided_unfreeze_never_issues_authority_and_retry_uses_new_audit(self):
        pair,a,b,coord=self.prepared();node=coord.config["nodeId"]
        original=coord.transport
        def lose_reply(path,value):
            result=original(path,value)
            if value.get("body",{}).get("action")=="audit-enable":raise pc.Closed("lost enable reply")
            return result
        coord.transport=lose_reply
        with self.assertRaises(pc.Closed):coord.arm_dual()
        oldaudit=coord.state["grant"]["auditId"]
        self.assertEqual(1,pair.mysql[node].readonly);self.assertEqual(0,pair.mysql[3-node].readonly)
        for c in (a,b):
            with self.assertRaises(pc.Closed):c.authority()
        coord.transport=original;coord.arm_dual()
        self.assertNotEqual(oldaudit,coord.state["grant"]["auditId"])
        for c in (a,b):self.assertEqual("dual",c.authority()["mode"])
        with self.assertRaises(pc.Closed):coord.rpc("audit-enable",{"auditId":oldaudit})

    def test_reconciling_seed_recovery_has_live_fence_proof_but_no_application_grant(self):
        pair,a,b,coord=self.prepared()
        seed=b;peer=a
        peer.fence();seed.reconcile_seed()
        self.assertEqual("RECONCILING",seed.state["phase"])
        self.assertEqual(1,pair.mysql[seed.config["nodeId"]].readonly)
        with self.assertRaises(pc.Closed):seed.authority()
        peer.prepare();peer.join();a.arm_dual();b.arm_dual()
        self.assertEqual("dual",a.authority()["mode"])
        self.assertEqual("dual",b.authority()["mode"])


class AllocatorSafetyTests(unittest.TestCase):
    def test_only_verified_final_table_counter_is_normalized(self):
        ddl="users\tCREATE TABLE `users` (\n  `id` bigint NOT NULL AUTO_INCREMENT,\n  `note` varchar(100) DEFAULT 'AUTO_INCREMENT=66',\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB AUTO_INCREMENT=66 DEFAULT CHARSET=utf8mb4 COMMENT='AUTO_INCREMENT=66'"
        self.assertEqual(ddl,pc.canonical_create_table(ddl))
        changed=pc.canonical_create_table(ddl,True)
        self.assertEqual(ddl.replace("ENGINE=InnoDB AUTO_INCREMENT=66", "ENGINE=InnoDB"),changed)
        self.assertIn("NOT NULL AUTO_INCREMENT",changed)
        self.assertIn("DEFAULT 'AUTO_INCREMENT=66'",changed)
        self.assertIn("COMMENT='AUTO_INCREMENT=66'",changed)

    def test_invalid_or_colliding_global_offsets_are_never_durable_policy(self):
        db=FakeMySQL(1);db.start_readonly()
        for fields in ({"autoIncrementOffset":101},{"autoIncrementStep":1},{"serverId":101},{"groupAutoIncrementStep":1}):
            self.assertFalse(pc.durable_mysql({**db.snapshot(),**fields},config(1)))

    def test_allocator_metadata_requires_single_pk_and_counter_above_maximum(self):
        a=object.__new__(pc.MySQLActuator);a.config=config(1)
        snap={"serverId":1,"autoIncrementStep":7,"groupAutoIncrementStep":7,"autoIncrementOffset":1}
        with patch.object(a,"snapshot",return_value=snap),patch.object(a,"sql",side_effect=["id","id","15","66"]):
            self.assertTrue(a.allocator_validated("isolated","users"))
        for answers in (["id","id","15","15"],["id","id\nsecond"],["id\nsecond"]):
            with patch.object(a,"snapshot",return_value=snap),patch.object(a,"sql",side_effect=answers),self.assertRaises(pc.Closed):
                a.allocator_validated("isolated","users")


class FragmentAndLatestRecoveryTests(unittest.TestCase):
    def test_fragment_bound_is_explicit_and_actual_mysql_must_match(self):
        pc.validate_config(config(1))
        for bad in (None,True,10485760,0):
            with self.assertRaises(pc.Closed):pc.validate_config({**config(1),"groupFragmentBytes":bad})
        db=FakeMySQL(1);db.start_readonly()
        self.assertFalse(pc.durable_mysql({**db.snapshot(),"communicationFragmentBytes":10485760},config(1)))

    def test_strict_superset_can_seed_closed_catchup_without_discarding_new_data(self):
        pair=Pair();a,b=pair.controllers[1],pair.controllers[2]
        a.prepare();b.prepare();pair.mysql[2].executed=GROUP+":1-3";pair.mysql[2].fingerprint_value="f"*64
        with self.assertRaises(pc.Closed):b.bootstrap()
        b.bootstrap(allow_latest=True)
        self.assertEqual("JOINING",b.state["phase"]);self.assertEqual(1,pair.mysql[2].readonly)
        with self.assertRaises(pc.Closed):b.authority()
        pair.mysql[1].fingerprint_value=pair.mysql[2].fingerprint_value  # Recovery applies all missing data.
        a.join();a.arm_dual();b.arm_dual()
        self.assertEqual("dual",a.authority()["mode"])

    def test_equal_gtids_with_different_data_and_divergent_gtids_still_refuse_latest(self):
        pair=Pair();a,b=pair.controllers[1],pair.controllers[2]
        a.prepare();b.prepare();pair.mysql[2].fingerprint_value="f"*64
        with self.assertRaises(pc.Closed):a.bootstrap(allow_latest=True)
        pair.mysql[1].executed=ONE+":1";pair.mysql[2].executed=TWO+":1"
        for c in (a,b):
            with self.assertRaises(pc.Closed):c.bootstrap(allow_latest=True)


class ActualWritableStateTests(unittest.TestCase):
    def test_super_read_only_off_alone_does_not_mean_ordinary_clients_can_write(self):
        pair=Pair();a,b=pair.dual();value=pair.mysql[1].snapshot()
        self.assertTrue(pc.writable_view(value,config(1),2))
        value["ordinaryReadOnly"]=1
        self.assertFalse(pc.writable_view(value,config(1),2))
        with patch.object(pair.mysql[1],"snapshot",return_value=value),self.assertRaises(pc.Closed):a.authority()
