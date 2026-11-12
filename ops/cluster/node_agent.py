#!/usr/bin/env python3
"""Loopback-only, authenticated health/capacity observer. No write or failover API."""
import concurrent.futures
import hashlib
import hmac
import http.server
import json
import os
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(url, token, expected=200, timeout=5):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token, "Cache-Control": "no-store"})
    with urllib.request.build_opener(NoRedirect).open(req, timeout=timeout) as response:
        if response.status != expected:
            raise ValueError("Unexpected probe result")
        raw = response.read(65537)
        if len(raw) > 65536:
            raise ValueError("Probe response too large")
        return json.loads(raw) if raw and response.headers.get_content_type() == "application/json" else None


def snapshot_fresh(status, expected_node, now_ms, freshness_ms=5000):
    observed = status.get("observedAtMillis")
    return (status.get("nodeId") == expected_node and isinstance(observed, int)
            and 0 <= now_ms - observed <= freshness_ms
            and status.get("mysqlReady") is True and status.get("tomcatReady") is True)


def write_headroom(status):
    """Require the exact safety floor and a separately validated payload-cost model."""
    volumes = status.get("volumes")
    if (status.get("reserveGuaranteed") is not True or status.get("capacityModelValidated") is not True
            or not isinstance(volumes, list) or not volumes):
        return False
    for volume in volumes:
        if not isinstance(volume, dict):
            return False
        available = volume.get("availableBytes")
        if not isinstance(available, int) or isinstance(available, bool) or available < 4_000_000_000:
            return False
    return True


def replication_policy(state, config, attestation, now_ms):
    """Admit a configured quorum, or a specifically fenced minority view.

    A minority requires root-owned evidence for *every* excluded member.
    Changing the configured membership requires a coordinated stop of writers;
    two different membership configurations must never run concurrently.
    """
    try:
        expected = set(config["memberUuids"])
        local = state["localId"]
        if (not 2 <= len(expected) <= 9 or len(config["memberUuids"]) != len(expected)
                or any(not isinstance(member, str) or not member for member in expected) or local not in expected
                or local != config["localMemberUuid"]
                or state["groupId"] != config["groupId"]
                or state["consistency"] != "BEFORE_AND_AFTER"
                or state["readOnly"] != 0 or not isinstance(state["viewId"], str)
                or not state["viewId"]):
            return None
        members = state["members"]
        if not isinstance(members, list) or any(not isinstance(m, dict) for m in members):
            return None
        ids = [m.get("id") for m in members]
        if len(set(ids)) != len(ids) or any(m.get("state") != "ONLINE" for m in members):
            return None
        local_members = [m for m in members if m.get("id") == local]
        if (len(local_members) != 1 or local_members[0].get("role") != "PRIMARY"
                or config.get("writerMode", "multi-primary") != "multi-primary"
                or any(m.get("role") != "PRIMARY" for m in members)):
            return None
        online = set(ids)
        if not online.issubset(expected):
            return None
        if len(online) >= len(expected) // 2 + 1:
            return "dual" if len(expected) == 2 else "quorum"
        if not isinstance(attestation, dict):
            return None
        excluded = expected - online
        # Accept the old single-peer proof for existing two-member installations.
        fenced = attestation.get("fencedIds")
        if fenced is None and len(expected) == 2:
            fenced = [attestation.get("fencedId")]
        issued, expires = attestation.get("issuedAtMillis"), attestation.get("expiresAtMillis")
        if (attestation.get("kind") != "operator-confirmed-cloud-fence"
                or attestation.get("groupId") != state["groupId"]
                or attestation.get("viewId") != state["viewId"]
                or attestation.get("survivorId") != local
                or not isinstance(fenced, list) or len(set(fenced)) != len(fenced)
                or set(fenced) != excluded
                or not isinstance(issued, int) or isinstance(issued, bool)
                or not isinstance(expires, int) or isinstance(expires, bool)
                or not issued <= now_ms < expires or expires - issued > 3_600_000):
            return None
        return "single" if len(online) == 1 else "fenced"
    except (KeyError, TypeError, ValueError, StopIteration):
        return None


def read_fence_attestation(path):
    """Only a root-owned, non-writable-by-others regular file can attest fencing."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_size > 4096:
                return None
            value = json.loads(stream.read(4097))
            return value if isinstance(value, dict) else None
    except (OSError, ValueError, TypeError):
        return None


class Agent:
    def __init__(self, config):
        self.config = config
        self.token = Path(config["tokenFile"]).read_text().strip()
        if (len(self.token) < 32 or not isinstance(config["nodeId"], int)
                or isinstance(config["nodeId"], bool) or config["nodeId"] < 1):
            raise ValueError("Invalid private agent identity configuration")

    def member_mapping(self):
        replication = self.config["replication"]
        mapping = replication.get("memberNodeIds")
        members = replication["memberUuids"]
        if (not isinstance(mapping, dict) or not isinstance(members, list)
                or len(set(members)) != len(members) or set(mapping) != set(members)):
            return None
        values = list(mapping.values())
        if (any(not isinstance(n, int) or isinstance(n, bool) or n < 1 for n in values)
                or len(set(values)) != len(values)
                or mapping.get(replication["localMemberUuid"]) != self.config["nodeId"]):
            return None
        return mapping

    def membership_digest(self):
        mapping = self.member_mapping()
        if mapping is None:
            return None
        data = {"groupId": self.config["replication"]["groupId"], "memberNodeIds": mapping}
        return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

    def online_node_ids(self, state):
        """Map a validated writable view to configured physical identities."""
        mapping = self.member_mapping()
        if mapping is None:
            return None
        return sorted(mapping[m["id"]] for m in state["members"])

    def peer_membership_matches(self, state, online):
        """Check config agreement without depending on Tomcat recovery readiness."""
        mapping = self.member_mapping()
        digest = self.membership_digest()
        if mapping is None or not digest:
            return False
        peers = self.config["peerStatusUrls"]
        by_node = {item["nodeId"]: item["url"] for item in peers}
        if len(by_node) != len(peers) or set(by_node) != set(mapping.values()) - {self.config["nodeId"]}:
            return False
        members_by_node = {node: member for member, node in mapping.items()}
        now = int(time.time() * 1000)
        for node in online:
            if node == self.config["nodeId"]:
                continue
            status = request(by_node[node], self.token)
            observed = status.get("observedAtMillis")
            if (status.get("nodeId") != node or status.get("memberUuid") != members_by_node[node]
                    or status.get("membershipDigest") != digest or status.get("mysqlReady") is not True
                    or not isinstance(observed, int) or isinstance(observed, bool) or not 0 <= now - observed <= 5000):
                return False
        latest = self.group_state()
        proof = read_fence_attestation(self.config["fenceAttestationFile"])
        return (latest.get("viewId") == state.get("viewId") and self.online_node_ids(latest) == online
                and replication_policy(latest, self.config["replication"], proof, int(time.time() * 1000)) is not None)

    def replication_view(self):
        if self.config.get("readyMode") != "replicated":
            return None, None, None
        try:
            state = self.group_state()
            proof = read_fence_attestation(self.config["fenceAttestationFile"])
            mode = replication_policy(state, self.config["replication"], proof, int(time.time() * 1000))
            nodes = self.online_node_ids(state) if mode else None
            # Legacy two-member configurations have no mapping. Three or more
            # members must have a complete UUID-to-physical-node mapping.
            if mode and nodes is None and (len(self.config["replication"]["memberUuids"]) > 2
                                           or "memberNodeIds" in self.config["replication"]):
                return None, None, None
            return mode, state, nodes
        except Exception:
            return None, None, None

    def mysql_ready(self):
        try:
            result = subprocess.run(["/usr/bin/mysql", "--defaults-extra-file=" + self.config["mysqlDefaultsFile"],
                                     "--batch", "--skip-column-names", "--connect-timeout=2", "-e",
                                     "SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@innodb_doublewrite"],
                                    capture_output=True, text=True, timeout=3, check=False)
            return result.returncode == 0 and result.stdout.strip().split("\t") == ["1", "1", "ON"]
        except (OSError, subprocess.SubprocessError):
            return False

    def group_state(self):
        sql = """SELECT JSON_OBJECT('localId',@@server_uuid,'groupId',@@global.group_replication_group_name,
                'consistency',@@global.group_replication_consistency,'readOnly',@@global.super_read_only,
                'viewId',(SELECT VIEW_ID FROM performance_schema.replication_group_member_stats WHERE MEMBER_ID=@@server_uuid LIMIT 1),
                'members',(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',MEMBER_ID,'state',MEMBER_STATE,'role',MEMBER_ROLE)) FROM performance_schema.replication_group_members))"""
        result = subprocess.run(["/usr/bin/mysql", "--defaults-extra-file=" + self.config["mysqlDefaultsFile"],
                                 "--batch", "--raw", "--skip-column-names", "--connect-timeout=2", "-e", sql],
                                capture_output=True, text=True, timeout=4, check=True)
        return json.loads(result.stdout.strip())

    def replication_mode(self):
        return self.replication_view()[0]

    def authority_identity(self):
        # This check deliberately does not depend on Tomcat or recovery. It
        # lets startup repair and recovery verify the local writer without
        # recursively calling /ready, which depends on their completion.
        if self.config.get("readyMode") != "replicated" or self.config.get("readyDelegate"):
            return None
        try:
            if not self.mysql_ready():
                return None
            state = self.group_state()
            proof = read_fence_attestation(self.config["fenceAttestationFile"])
            mode = replication_policy(state, self.config["replication"], proof, int(time.time() * 1000))
            if mode not in ("dual", "single", "quorum", "fenced"):
                return None
            if len(self.config["replication"]["memberUuids"]) > 2:
                online = self.online_node_ids(state)
                if online is None or not self.peer_membership_matches(state, online):
                    return None
            elif "memberNodeIds" in self.config["replication"] and self.online_node_ids(state) is None:
                return None
            identity = state["localId"]
            return str(uuid.UUID(identity)) if str(uuid.UUID(identity)) == identity.lower() else None
        except Exception:
            return None

    def tomcat_ready(self):
        try:
            for check in self.config["tomcatChecks"]:
                result = request(check["url"], self.token, check.get("expectedStatus", 200), timeout=2)
                if "serverId" in check and (not isinstance(result, dict) or result.get("serverId") != check["serverId"]):
                    return False
            return True
        except Exception:
            return False

    def reserve(self):
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.settimeout(3)
                connection.connect(self.config["reserveSocket"])
                connection.sendall(b"status\n")
                data = b""
                while not data.endswith(b"\n"):
                    part = connection.recv(65536)
                    if not part or len(data) + len(part) > 65536:
                        raise ValueError("Invalid reserve response")
                    data += part
                result = json.loads(data)
                if not isinstance(result.get("volumes"), list):
                    raise ValueError("Invalid volume status")
                return result
        except Exception:
            return {"guaranteed": False, "volumes": []}

    def status(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            mysql = pool.submit(self.mysql_ready)
            tomcat = pool.submit(self.tomcat_ready)
            reserve = pool.submit(self.reserve)
            reserve_status = reserve.result()
            mysql_ready = mysql.result()
            tomcat_ready = tomcat.result()
            replication_mode = self.replication_mode()
            result = {"nodeId": self.config["nodeId"], "observedAtMillis": int(time.time() * 1000),
                    "mysqlReady": mysql_ready, "tomcatReady": tomcat_ready,
                    "replicationMode": replication_mode,
                    "singleNodeAuthorized": replication_mode == "single",
                    "reserveGuaranteed": reserve_status.get("guaranteed") is True,
                    "volumes": reserve_status["volumes"],
                    "physicalBytesPerPayloadByte": self.config.get("physicalBytesPerPayloadByte", 4),
                    "fixedOverheadBytes": self.config.get("fixedOverheadBytes", 67108864),
                    "capacityModelValidated": self.config.get("capacityModelValidated") is True}
            if self.config.get("readyMode") == "replicated" and len(self.config["replication"]["memberUuids"]) > 2:
                mode, state, nodes = self.replication_view()
                result["replicationMode"] = mode
                result["singleNodeAuthorized"] = mode == "single"
                result["onlineNodeIds"] = nodes
                result["viewId"] = state.get("viewId") if state else None
                result["membershipDigest"] = self.membership_digest()
                result["memberUuid"] = self.config["replication"]["localMemberUuid"]
                mapping = self.member_mapping()
                result["configuredNodeIds"] = sorted(mapping.values()) if mapping is not None else None
            return result

    def ready(self):
        try:
            if self.config.get("capacityModelValidated") is not True:
                return False
            delegate = self.config.get("readyDelegate")
            if delegate and self.config.get("readyMode") == "replicated":
                return False  # Each replicated writer must verify its own membership and fence.
            if delegate:
                result = request(delegate, self.token, expected=200)
                return isinstance(result, dict) and result.get("status") == "ready"
            peers = self.config["peerStatusUrls"]
            recoveries = self.config["recoveryChecks"]
            if len(recoveries) != 3:
                return False
            replicated = self.config.get("readyMode") == "replicated"
            if replicated and len(self.config["replication"]["memberUuids"]) > 2:
                mode, _state, online = self.replication_view()
                if mode not in ("quorum", "single", "fenced") or online is None:
                    return False
                peer_by_id = {p["nodeId"]: p["url"] for p in peers}
                configured_ids = set(self.config["replication"]["memberNodeIds"].values())
                if len(peer_by_id) != len(peers) or set(peer_by_id) != configured_ids - {self.config["nodeId"]}:
                    return False
                needed = [(n, peer_by_id[n]) for n in online if n != self.config["nodeId"]]
            else:
                if len(peers) != 1:
                    return False
                mode = self.replication_mode() if replicated else "dual"
                if mode not in ("dual", "single"):
                    return False
                needed = [(peers[0]["nodeId"], peers[0]["url"])] if mode == "dual" else []
            if mode is None:
                return False
            with concurrent.futures.ThreadPoolExecutor(max_workers=max(5, len(needed)+4)) as pool:
                local = pool.submit(self.status)
                peer_tasks = [(node, pool.submit(request, url, self.token)) for node, url in needed]
                recovery = [pool.submit(request, url, self.token, 204, 5) for url in recoveries]
                statuses = [(local.result(), self.config["nodeId"])]
                statuses.extend((task.result(), node) for node, task in peer_tasks)
                for result in recovery:
                    result.result()
                now = int(time.time() * 1000)
                if replicated and len(self.config["replication"]["memberUuids"]) > 2:
                    digest = self.membership_digest()
                    by_id = {node: member for member, node in self.member_mapping().items()}
                    if not digest or any(status.get("membershipDigest") != digest
                                         or status.get("memberUuid") != by_id.get(node)
                                         for status, node in statuses):
                        return False
                return (not (replicated and len(self.config["replication"]["memberUuids"]) > 2)
                        or (statuses[0][0].get("onlineNodeIds") == online
                            and statuses[0][0].get("viewId") == _state.get("viewId"))) and (all(snapshot_fresh(status, node, now) for status, node in statuses)
                        and (self.config.get("readyMode") != "replicated"
                             or all(write_headroom(status) for status, _node in statuses)))
        except Exception:
            return False


def serve(config_path):
    agent = Agent(json.loads(Path(config_path).read_text()))

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            authorized = self.headers.get("Authorization", "")
            if not hmac.compare_digest(authorized, "Bearer " + agent.token):
                self.send_response(403); self.send_header("Cache-Control", "no-store"); self.end_headers(); return
            if self.path == "/status":
                body = json.dumps(agent.status(), separators=(",", ":")).encode()
                self.send_response(200); self.send_header("Content-Type", "application/json")
            elif self.path == "/authority":
                identity = agent.authority_identity()
                body = b""; self.send_response(204 if identity is not None else 503)
                if identity is not None: self.send_header("X-Four-Project-MySQL-UUID", identity)
            elif self.path == "/ready":
                body = b""; self.send_response(204 if agent.ready() else 503)
            else:
                body = b""; self.send_response(404)
            self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body))); self.end_headers()
            if body: self.wfile.write(body)

        def log_message(self, *_args):
            pass  # Never log bearer tokens, request content or private response details.

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 9411), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Supply private node-agent configuration path")
    serve(sys.argv[1])
