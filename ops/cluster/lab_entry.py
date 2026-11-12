#!/usr/bin/env python3
"""Equal-node Nginx entry with active, freshness-bounded private admission.

Only render is offline. Serve can test/reload its one private Nginx configuration;
it never starts/stops MySQL/Tomcat, changes fencing, or retries a user request.
"""
import argparse
import concurrent.futures
from dataclasses import dataclass
import fcntl
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shlex
import socket
import stat
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

APPS = ("iafm", "starhub", "ucost", "portfolio")
CONTEXT = {"iafm": "Investment_and_Financial_Management", "starhub": "starhub", "ucost": "ucost", "portfolio": ""}
RECOVERY = {"iafm": "/internal/recovery-ready", "starhub": "/api/internal/recovery-ready", "ucost": "/internal/recovery-ready"}
PROOF_HEADERS = ("MySQL-UUID", "View-ID", "Epoch", "Mode", "Membership", "Permit", "Controller-Boot-ID")
AUDIT_FILENAME = "durability-audit.jsonl"


class Closed(Exception):
    pass


def require(value, message):
    if not value:
        raise Closed(message)


def safe_path(value):
    require(isinstance(value, str) and value.startswith("/") and re.fullmatch(r"/[A-Za-z0-9_./-]+", value)
            and ".." not in Path(value).parts, "Explicit safe absolute path required")
    return value


def endpoint(value):
    uri = urllib.parse.urlsplit(value)
    require(uri.scheme == "http" and uri.hostname == "127.0.0.1" and uri.port and 1 <= uri.port <= 65535
            and not uri.username and not uri.password and uri.path in ("", "/") and not uri.query and not uri.fragment,
            "Explicit loopback service endpoint required")
    return value.rstrip("/")


def validate(config):
    require(config.get("nodeId") in (1, 2) and type(config["nodeId"]) is int, "Physical node ID required")
    require(config.get("listenAddress", "127.0.0.1") in ("127.0.0.1", "0.0.0.0"), "Explicit entry bind required")
    require(config.get("listenPort") in (18443, 18444, 443), "Known staged or formal TLS port required")
    require(config.get("listenAddress", "127.0.0.1") == "127.0.0.1" or config["listenPort"] == 443,
            "Staged entries must stay loopback-only")
    if "privateAcceptanceBackendRouting" in config:
        require(type(config["privateAcceptanceBackendRouting"]) is bool,
                "Private acceptance backend routing must be an explicit boolean")
        require(config.get("listenAddress", "127.0.0.1") == "127.0.0.1"
                and config["listenPort"] in (18443, 18444),
                "Private acceptance backend routing is forbidden on formal entries")
    if "privateWriteApplications" in config:
        applications = config["privateWriteApplications"]
        require(config.get("listenAddress", "127.0.0.1") == "127.0.0.1"
                and config["listenPort"] in (18443, 18444),
                "Private write scope is restricted to loopback acceptance entries")
        require(type(applications) is list
                and all(type(app) is str and app in APPS for app in applications),
                "Private write scope must list known applications")
        require(len(applications) == len(set(applications)), "Private write scope cannot repeat an application")
    require(config.get("admissionPort") == 942 + config["nodeId"], "Node-specific privileged admission port required")
    safe_path(config["runtimeDirectory"])
    require(config.get("nginxBinary", "/usr/sbin/nginx") in ("/usr/sbin/nginx", "/usr/local/nginx/sbin/nginx"), "Known Nginx executable required")
    require(config.get("nginxUnit", "codex-lab-nginx.service") in ("codex-lab-nginx.service", "nginx.service"), "Reviewed foreground Nginx unit required")
    require(re.fullmatch(r"[a-z_][a-z0-9_-]*", config.get("nginxUser", "www-data")), "Nginx account required")
    require(set(config["nodes"]) == {"1", "2"} and set(config["applications"]) == set(APPS), "Exactly two nodes and four projects required")
    for node_id, node in config["nodes"].items():
        require(endpoint(node["tomcat"]) == "http://127.0.0.1:" + str(880 + int(node_id))
                and endpoint(node["controller"]) == "http://127.0.0.1:" + str(940 + int(node_id)),
                "Pinned node-specific privileged service endpoints required")
        for key in ("appToken", "readyToken"):
            require(isinstance(node.get(key), str) and re.fullmatch(r"[A-Za-z0-9_-]{32,}", node[key]), "Private probe credential required")
    for app, values in config["applications"].items():
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]+", values["hostname"]), "Explicit DNS hostname required")
        safe_path(values["certificate"]); safe_path(values["privateKey"])
        if app != "portfolio":
            require(re.fullmatch(r"[A-Za-z0-9_-]{32,}", values.get("recoveryToken", "")), "Private application probe credential required")
        if app in ("iafm", "starhub"):
            require(re.fullmatch(r"[A-Za-z0-9_-]{32,}", values.get("ingressToken", "")), "Private ingress credential required")
        if app == "portfolio":
            require(re.fullmatch(r"[0-9a-f]{64}", values.get("indexSha256", "")), "Verified portfolio index digest required")
    interval = config.get("intervalSeconds", 3)
    timeout = config.get("timeoutSeconds", 3)
    maximum = config.get("maxAgeSeconds", 15)
    require(type(interval) in (int, float) and 1 <= interval <= 30 and type(timeout) in (int, float) and 1 <= timeout <= 10
            and type(maximum) in (int, float) and 2 * timeout + interval <= maximum <= 90, "Bounded polling/freshness timing required")
    return config


def load_config(path):
    path = Path(path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o600,
            "Owner-private regular configuration required")
    return validate(json.loads(path.read_text()))


@dataclass
class Reply:
    status: int
    headers: dict
    body: bytes

    def object(self):
        require("application/json" in self.headers.get("content-type", ""), "Actual JSON response required")
        result = json.loads(self.body)
        require(isinstance(result, dict), "JSON object required")
        return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class Http:
    def __init__(self, timeout):
        self.timeout = timeout

    def get(self, url, token=None, method="GET", limit=1_048_576):
        parsed=urllib.parse.urlsplit(url)
        require(parsed.scheme=="http" and parsed.hostname=="127.0.0.1" and parsed.port and not parsed.username
                and not parsed.password and not parsed.fragment, "Probe cannot leave pinned loopback transport")
        headers = {"Cache-Control": "no-store", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(url, headers=headers, method=method)
        # Ignore inherited HTTP proxy variables: no probe credential may leave
        # the configured SSH/loopback channel, even after a redirect.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)
        try:
            response = opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as failed:
            response = failed
        with response:
            body = b"" if method == "HEAD" else response.read(limit + 1)
            require(len(body) <= limit, "Probe response exceeded its bound")
            headers = {}
            for key, value in response.headers.raw_items():
                key = key.lower()
                if key.startswith("x-four-project-"):
                    require(key not in headers, "Ambiguous controller proof headers")
                headers[key] = value
            return Reply(response.status, headers, body)


def proof(reply):
    require(reply.status == 204, "Current database authority is unavailable")
    values = tuple(reply.headers.get("x-four-project-" + key.lower(), "") for key in PROOF_HEADERS)
    mysql_uuid, view, epoch, mode, membership, permit, boot_id = values
    try:
        identities_valid = str(uuid.UUID(mysql_uuid)) == mysql_uuid and str(uuid.UUID(boot_id)) == boot_id
    except (ValueError, TypeError, AttributeError):
        identities_valid = False
    require(identities_valid and view and re.fullmatch(r"[1-9][0-9]*", epoch)
            and mode in ("dual", "single") and re.fullmatch(r"[0-9a-f]{64}", membership)
            and re.fullmatch(r"[0-9a-f]{64}", permit),
            "Incomplete current database authority evidence")
    return values


STATUS_MAX_AGE_MILLIS = 12_000
STATUS_CLOCK_TOLERANCE_MILLIS = 2_000
CAPACITY_FLOOR_BYTES = 4_000_000_000


@dataclass
class StatusObservation:
    reply: Reply
    started_millis: int
    ended_millis: int
    received_monotonic: float


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "Ambiguous controller status fields")
        value[key] = item
    return value


def capacity_status(observation, node_id, authority, *, local, now_millis=None, now_monotonic=None):
    """Bind this poll's real capacity sample to its unchanged current grant.

    A peer Tomcat is not a database replica participant. Its real capacity and
    database authority remain mandatory in dual mode even when ingress needs
    to forward away from that peer's failed Tomcat.
    """
    reply = observation.reply
    require(reply.status == 200 and "application/json" in reply.headers.get("content-type", ""),
            "Actual controller capacity status required")
    value = json.loads(reply.body, object_pairs_hook=_unique_object)
    require(type(value) is dict, "Controller status object required")
    now_millis = int(time.time() * 1000) if now_millis is None else now_millis
    now_monotonic = time.monotonic() if now_monotonic is None else now_monotonic
    observed = value.get("observedAtMillis")
    require(type(observed) is int and observed > 0
            and observation.started_millis - STATUS_CLOCK_TOLERANCE_MILLIS <= observed
            <= observation.ended_millis + STATUS_CLOCK_TOLERANCE_MILLIS
            and observed <= now_millis + STATUS_CLOCK_TOLERANCE_MILLIS
            and now_millis - observed <= STATUS_MAX_AGE_MILLIS
            and 0 <= now_monotonic - observation.received_monotonic <= STATUS_MAX_AGE_MILLIS / 1000,
            "Fresh status from this poll required")
    mysql_uuid, view, epoch, mode, membership, _permit, boot_id = authority
    member, boot, observed_epoch = value.get("memberUuid"), value.get("controllerBootId"), value.get("authorityEpoch")
    try:
        identities_valid = (str(uuid.UUID(member)) == member and str(uuid.UUID(boot)) == boot)
    except (ValueError, TypeError, AttributeError):
        identities_valid = False
    require(type(value.get("nodeId")) is int and value["nodeId"] == node_id
            and identities_valid and type(observed_epoch) is str and re.fullmatch(r"[1-9][0-9]*", observed_epoch)
            and value.get("viewId") == view and value.get("membershipDigest") == membership
            and value.get("replicationMode") == mode and value.get("mysqlReady") is True
            and type(value.get("tomcatReady")) is bool, "Status does not identify this current data-plane grant")
    if local:
        require(member == mysql_uuid and boot == boot_id and observed_epoch == epoch and value["tomcatReady"] is True,
                "Local application or controller incarnation changed")
    else:
        require(mode == "dual" and member != mysql_uuid and boot != boot_id,
                "Distinct current peer controller/database required")
    configured, online = value.get("configuredNodeIds"), value.get("onlineNodeIds")
    require(type(configured) is list and type(online) is list
            and all(type(node) is int for node in configured + online)
            and len(configured) == 2 and set(configured) == {1, 2}
            and len(online) == len(set(online)), "Exact configured/online node identities required")
    if mode == "dual":
        require(len(online) == 2 and set(online) == {1, 2} and value.get("singleNodeAuthorized") is False,
                "Current dual membership required")
    else:
        require(local and online == [node_id] and value.get("singleNodeAuthorized") is True,
                "Fresh locally authorized fenced single-node membership required")
    factor, fixed = value.get("physicalBytesPerPayloadByte"), value.get("fixedOverheadBytes")
    require(value.get("reserveGuaranteed") is True and value.get("capacityModelValidated") is True
            and type(value.get("floorBytes")) is int and value["floorBytes"] == CAPACITY_FLOOR_BYTES
            and value.get("boundaryPolicy") == "ext4-root-plus-kernel-v1"
            and type(factor) is int and 1 <= factor <= 1_000_000
            and type(fixed) is int and 0 <= fixed <= 2**63 - 1,
            "Validated protected capacity boundary required")
    volumes = value.get("volumes")
    require(type(volumes) is list and volumes, "Actual protected volumes required")
    identifiers = set()
    for volume in volumes:
        require(type(volume) is dict and type(volume.get("id")) is str and volume["id"].strip()
                and volume["id"] not in identifiers and type(volume.get("availableBytes")) is int
                and CAPACITY_FLOOR_BYTES <= volume["availableBytes"] <= 2**63 - 1,
                "Every distinct protected volume must retain the 4 GB boundary")
        identifiers.add(volume["id"])
    return value


class Prober:
    def __init__(self, config, http=None):
        self.config = config
        self.http = http or Http(config.get("timeoutSeconds", 3))
        self.portfolio_cache = {}
        self.cache_lock = threading.Lock()

    def portfolio(self, node, base):
        head = self.http.get(base + "/index.html", method="HEAD")
        if head.status != 200:
            return False
        signature = tuple(head.headers.get(k, "") for k in ("etag", "last-modified", "content-length"))
        now = time.monotonic()
        with self.cache_lock:
            old = self.portfolio_cache.get(node)
            cached = old and any(signature) and old[0] == signature and now - old[1] < 3600
        if not cached:
            page = self.http.get(base + "/index.html")
            if page.status != 200 or hashlib.sha256(page.body).hexdigest() != self.config["applications"]["portfolio"]["indexSha256"]:
                return False
            with self.cache_lock:
                self.portfolio_cache[node] = (signature, now)
        return True

    def status(self, url, token):
        started = int(time.time() * 1000)
        reply = self.http.get(url, token, limit=65_536)
        return StatusObservation(reply, started, int(time.time() * 1000), time.monotonic())

    def node(self, node_id):
        node = self.config["nodes"][str(node_id)]
        base = endpoint(node["tomcat"])
        result = {app: False for app in APPS}
        fingerprint = ""
        try:
            result["portfolio"] = self.portfolio(node_id, base)
        except Exception:
            pass
        try:
            before = proof(self.http.get(node["controller"] + "/authority", node["appToken"]))
            checks = {"controller": (node["controller"] + "/status", node["appToken"])}
            if before[3] == "dual":
                peer = self.config["nodes"][str(3 - node_id)]
                checks["peer-controller"] = (peer["controller"] + "/status", peer["appToken"])
            for app in APPS[:-1]:
                context = base + "/" + CONTEXT[app]
                checks[app + "-recovery"] = (context + RECOVERY[app], self.config["applications"][app]["recoveryToken"])
                checks[app + "-identity"] = (context + "/api/server", None)
            replies = {}
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                futures = {key: pool.submit(self.status, url, token) if key.endswith("controller")
                           else pool.submit(self.http.get, url, token) for key, (url, token) in checks.items()}
                for key, future in futures.items():
                    try:
                        replies[key] = future.result()
                    except Exception:
                        replies[key] = Reply(503, {}, b"")
            after = proof(self.http.get(node["controller"] + "/authority", node["appToken"]))
            require(before == after, "Authority changed during actual recovery/capacity checks")
            now_millis, now_monotonic = int(time.time() * 1000), time.monotonic()
            capacity_status(replies["controller"], node_id, before, local=True,
                            now_millis=now_millis, now_monotonic=now_monotonic)
            if before[3] == "dual":
                capacity_status(replies["peer-controller"], 3 - node_id, before, local=False,
                                now_millis=now_millis, now_monotonic=now_monotonic)
            # Preserve /ready's full local recovery requirement, while executing
            # each real application recovery once rather than twice per poll.
            require(all(replies[app + "-recovery"].status == 204 for app in APPS[:-1]),
                    "All three actual local recovery checks required")
            for app in APPS[:-1]:
                try:
                    identity = replies[app + "-identity"].object()
                    result[app] = replies[app + "-recovery"].status == 204 and type(identity.get("serverId")) is int and identity["serverId"] == node_id
                except Exception:
                    result[app] = False
            fingerprint = hashlib.sha256(json.dumps(before).encode()).hexdigest()
        except Exception:
            pass
        return result, fingerprint

    def all(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = {node: pool.submit(self.node, node) for node in (1, 2)}
            return {node: future.result() for node, future in futures.items()}


class Admission:
    def __init__(self, max_age=15, clock=time.monotonic):
        self.max_age = max_age
        self.clock = clock
        self.lock = threading.Lock()
        self.observed = None
        self.healthy = {app: [] for app in APPS}
        self.streak = {}

    def observe(self, observations):
        with self.lock:
            healthy = {app: [] for app in APPS}
            for node in (1, 2):
                states, fingerprint = observations[node]
                for app in APPS:
                    key = (app, node)
                    epoch = "static" if app == "portfolio" else fingerprint
                    old_epoch, count = self.streak.get(key, (None, 0))
                    count = (count + 1 if epoch == old_epoch else 1) if states.get(app) and epoch else 0
                    self.streak[key] = (epoch, count)
                    if count >= 2:
                        healthy[app].append(node)
            self.observed = self.clock()
            return healthy

    def publish(self, healthy):
        with self.lock:
            self.healthy = {app: list(healthy[app]) for app in APPS}

    def admitted(self, app):
        with self.lock:
            return (app in APPS and self.observed is not None and 0 <= self.clock() - self.observed <= self.max_age
                    and bool(self.healthy[app]))

    def snapshot(self):
        with self.lock:
            age = None if self.observed is None else self.clock() - self.observed
            fresh = age is not None and 0 <= age <= self.max_age
            return {"fresh": fresh, "observedAgeMillis": None if age is None else int(age * 1000),
                    "healthyNodeIds": {app: list(nodes) if fresh else [] for app, nodes in self.healthy.items()}}


def upstream(app, preference, healthy, nodes):
    name = f"four_{app}_{preference}"
    selected = [n for n in (1, 2) if n in healthy]
    lines = []
    for node in selected:
        host = urllib.parse.urlsplit(nodes[str(node)]["tomcat"]).netloc
        backup = preference in ("n1", "n2") and int(preference[1:]) in selected and node != int(preference[1:])
        lines.append(f"        server {host}{' backup' if backup else ''} max_fails=1 fail_timeout=3s;")
    if not lines:
        lines = ["        server 127.0.0.1:9 down;"]
    return "    upstream " + name + " {\n" + "\n".join(lines) + "\n    }\n"


def render(config, healthy):
    validate(config)
    directory = safe_path(config["runtimeDirectory"])
    port = config["admissionPort"]
    text = f'''# Generated private entry configuration. No controller credentials are embedded.
user {config.get('nginxUser', 'www-data')};
worker_processes auto;
pid {directory}/private/nginx.pid;
error_log /dev/null crit;
events {{ worker_connections 2048; }}
http {{
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    # This records the observed response mode, never proof of client receipt.
    # A started SSE/unknown provider operation has no final response proof.
    map "$upstream_http_x_starhub_operation_state:$upstream_http_x_four_project_durability" $four_audit_durability {{
        default unconfirmed;
        ~\\A:single\\z single;
        ~\\A:dual\\z dual;
        ~\\A(?:COMPLETED|COMPLETED_NO_RESULT|ABSENT):single\\z single;
        ~\\A(?:COMPLETED|COMPLETED_NO_RESULT|ABSENT):dual\\z dual;
    }}
    map $server_name $four_audit_application {{
        default unknown;
'''
    for app in APPS:
        text += f'        {config["applications"][app]["hostname"]} {app};\n'
    text += f'''    }}
    log_format four_response_durability escape=json '{{"time":"$time_iso8601","application":"$four_audit_application","host":"$server_name","status":"$status","responseDurability":"$four_audit_durability"}}';
    # Static path: the root master opens it; workers inherit that descriptor.
    access_log {directory}/private/{AUDIT_FILENAME} four_response_durability;
    log_subrequest off;
    server_tokens off;
    client_body_temp_path {directory}/client-body;
    proxy_temp_path {directory}/proxy-temp;
    client_body_timeout 30s;
    map $http_upgrade $four_upgrade {{ default upgrade; '' close; }}
    # The external TLS port is part of Host, not the private --connect-to
    # listener port. Production-origin tests can terminate at local 18443.
    map $http_host $four_forwarded_port {{
        default 443;
        ~^[^:]+:(?<four_requested_port>[0-9]+)$ $four_requested_port;
    }}
    limit_req_zone $binary_remote_addr zone=four_starhub_access:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=four_starhub_api:10m rate=30r/m;
    limit_req_zone $binary_remote_addr zone=four_ucost_auth:10m rate=10r/m;
    limit_conn_zone $binary_remote_addr zone=four_starhub_realtime:1m;
    map "$request_method:$uri" $four_files_body {{
        default "";
        ~^POST:/(?:starhub/)?api/workspace$ "$server_name:files";
    }}
    map "$request_method:$uri" $four_guest_body {{
        default "";
        ~^POST:/(?:starhub/)?api/guest-session$ "$server_name:guest";
    }}
    map "$request_method:$uri" $four_provider_body {{
        default "";
        ~^POST:/(?:starhub/)?api/(?:chat|run|batch|check|provider-operation|provider-media)$ "$server_name:provider";
    }}
    map "$request_method:$uri" $four_account_body {{
        default "";
        ~^POST:/(?:starhub/)?(?:api/account|_access)$ "$server_name:account";
    }}
    map "$request_method:$uri" $four_starhub_post_denied {{
        default 0;
        ~^POST:/(?:starhub/)?(?:_access|api/(?:workspace|guest-session|chat|run|batch|check|provider-operation|provider-media|account))$ 0;
        ~^POST: 1;
    }}
    limit_conn_zone $four_files_body zone=four_files_body:1m;
    limit_conn_zone $four_guest_body zone=four_guest_body:1m;
    limit_conn_zone $four_provider_body zone=four_provider_body:1m;
    limit_conn_zone $four_account_body zone=four_account_body:1m;
'''
    for app in APPS:
        for preference in ("any", "n1", "n2"):
            text += upstream(app, preference, healthy[app], config["nodes"])
        cookie = "$cookie_UCOST_SESSION" if app == "ucost" else "$cookie_JSESSIONID"
        private_route = app == "starhub" and config.get("privateAcceptanceBackendRouting", False)
        cookie_target = f"$four_{app}_cookie_upstream" if private_route else f"$four_{app}_upstream"
        text += f'''    map {cookie} {cookie_target} {{
        default four_{app}_any;
        ~[.]n1$ four_{app}_n1;
        ~[.]n2$ four_{app}_n2;
    }}
'''
        if private_route:
            # Affinity tests must preserve the complete replicated session ID.
            # Select only the existing health-filtered groups; this neither
            # fabricates a session nor admits an unhealthy application.
            text += '''    map $http_x_private_acceptance_backend $four_starhub_upstream {
        default $four_starhub_cookie_upstream;
        ~\\An1\\z four_starhub_n1;
        ~\\An2\\z four_starhub_n2;
    }
'''
    # Unknown SNI/Host never falls through into the first project's shell.
    first=config["applications"]["iafm"]
    text+=f'''    server {{
        listen {config.get('listenAddress','127.0.0.1')}:{config['listenPort']} ssl default_server;
        server_name _;
        ssl_certificate {first['certificate']};
        ssl_certificate_key {first['privateKey']};
        ssl_protocols TLSv1.2 TLSv1.3;
        return 444;
    }}
'''
    for app in APPS:
        context=CONTEXT[app]
        if context:
            text+=f'''    map $request_uri $four_{app}_uri {{
        default $request_uri;
        ~^/{context}(?<four_{app}_suffix>/.*)$ $four_{app}_suffix;
    }}
'''
    for app in APPS:
        values = config["applications"][app]
        context = "/" + CONTEXT[app] if CONTEXT[app] else ""
        ingress = ""
        if app == "iafm":
            ingress = f'proxy_set_header X-IAFM-Ingress-Node "{config["nodeId"]}";\n            proxy_set_header X-IAFM-Ingress-Token "{values["ingressToken"]}";'
        if app == "starhub":
            ingress = f'proxy_set_header X-Starhub-Ingress-Node "{config["nodeId"]}";\n            proxy_set_header X-Starhub-Ingress-Token "{values["ingressToken"]}";'
        public_uri=f"$four_{app}_uri" if context else "$request_uri"
        absolute_redirect=""
        if context:
            hostname=values['hostname'].replace('.', '[.]')
            absolute_redirect=f"proxy_redirect ~^https?://(?:127[.]0[.]0[.]1(?::[0-9]+)?|{hostname}(?::[0-9]+)?){context}/(.*)$ /$1;"
        # Logical ingress is preserved when this entry selects the peer. Both
        # MySQL members contain both logical schemas; neither is a fixed writer.
        streaming = "proxy_buffering off; gzip off;" if app == "starhub" else ""
        common = f'''auth_request /_entry_admit;
            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-Forwarded-Host $host;
            proxy_set_header X-Forwarded-Port $four_forwarded_port;
            {ingress}
            proxy_set_header Connection "";
            proxy_connect_timeout 2s;
            proxy_read_timeout 360s;
            proxy_send_timeout 60s;
            proxy_intercept_errors off;
            proxy_request_buffering {"off" if app == "starhub" else "on"};
            {streaming}
            proxy_cookie_path {context + '/' if context else '/'} /;
            proxy_cookie_path {context or '/'} /;
            proxy_redirect {context + '/' if context else '/'} /;
            {absolute_redirect}
            proxy_pass http://$four_{app}_upstream{context}{public_uri};'''
        body_limits = '''limit_conn four_files_body 1;
        limit_conn four_guest_body 1;
        limit_conn four_provider_body 2;
        limit_conn four_account_body 1;
        limit_conn_status 429;
        client_body_buffer_size 32k;
        if ($uri ~ ";") { return 400; }
        if ($request_method !~ ^(GET|HEAD|POST)$) { return 405; }
        if ($four_starhub_post_denied) { return 404; }''' if app == "starhub" else ""
        # A private capacity-validation window can authorize only its reviewed
        # application. Reject POST before location routing/auth/body handling;
        # ordinary GET/HEAD and all existing per-app policies remain intact.
        # A private internal status avoids rewriting these responses through
        # the existing generic 503 error page. It is never returned publicly.
        phase_closed = ""
        if "privateWriteApplications" in config and app not in config["privateWriteApplications"]:
            phase_closed = '''error_page 419 =503 @entry_phase_closed;
        if ($request_method = POST) { return 419; }
        location @entry_phase_closed {
            default_type application/json;
            add_header Cache-Control "no-store" always;
            return 503 '{"code":"ENTRY_PHASE_CLOSED","error":"Service temporarily unavailable."}';
        }'''
        text += f'''    server {{
        listen {config.get('listenAddress', '127.0.0.1')}:{config['listenPort']} ssl;
        server_name {values['hostname']};
        ssl_certificate {values['certificate']};
        ssl_certificate_key {values['privateKey']};
        ssl_protocols TLSv1.2 TLSv1.3;
        client_max_body_size {'6m' if app == 'starhub' else '2100k' if app == 'ucost' else '2m'};
        {body_limits}
        {phase_closed}
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;
        add_header Referrer-Policy no-referrer always;
        add_header Strict-Transport-Security "max-age=31536000" always;
        error_page 403 500 502 503 504 =503 @entry_unavailable;
        location = /_entry_admit {{
            internal;
            proxy_pass http://127.0.0.1:{port}/admit/{app};
            proxy_pass_request_body off;
            proxy_pass_request_headers off;
            proxy_set_header Content-Length "";
            proxy_connect_timeout 1s;
            proxy_read_timeout 1s;
        }}
        location ~* "(?:^|/)(?:internal|WEB-INF|META-INF)(?:/|;|$)" {{ return 404; }}
        location ~* "[.](?:class|java|properties|sql|log|jar|war)$" {{ return 404; }}
        location ~ /[.] {{ return 404; }}
        location / {{
            recursive_error_pages on;
            # Defining418 here suppresses all inherited error_page directives.
            # Keep admission denial a503 while retaining the safe-read route.
            error_page 403 500 502 503 504 =503 @entry_unavailable;
            error_page 418 = @safe_read;
            if ($request_method ~ ^(GET|HEAD)$) {{ return 418; }}
            proxy_next_upstream off;
            {common}
        }}
        location @safe_read {{
            proxy_pass_request_body off;
            proxy_set_header Content-Length "";
            proxy_next_upstream error timeout invalid_header http_502 http_503 http_504;
            proxy_next_upstream_tries 2;
            proxy_next_upstream_timeout 6s;
            {common}
        }}
'''
        if app == "ucost":
            text += f'''        location ~ ^/(?:ucost/)?api/(login|register|password)$ {{
            limit_req zone=four_ucost_auth burst=10 nodelay;
            limit_req_status 429;
            proxy_next_upstream off;
            {common}
        }}
'''
        if app == "starhub":
            text += f'''        location ~ ^/(?:starhub/)?_access$ {{
            client_max_body_size 16k;
            limit_req zone=four_starhub_access burst=5 nodelay;
            limit_req_status 429;
            proxy_next_upstream off;
            {common}
        }}
        location ~ ^/(?:starhub/)?api/(chat|run|batch|check)$ {{
            limit_req zone=four_starhub_api burst=5 nodelay;
            limit_req_status 429;
            proxy_next_upstream off;
            {common}
        }}
        location = /realtime {{
            # Do not replay a WebSocket or an external-provider conversation.
            limit_conn four_starhub_realtime 2;
            auth_request /_entry_admit;
            proxy_pass http://$four_{app}_upstream/starhub/realtime;
            proxy_http_version 1.1;
            proxy_pass_request_body off;
            proxy_set_header Content-Length "";
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection $four_upgrade;
            proxy_next_upstream off;
            proxy_read_timeout 1800s;
            proxy_buffering off;
        }}
'''
            # Reuse the same bounded proxy policy for both root and explicit
            # same-context URLs. Larger requests fail before body buffering.
            for route, maximum in (("account", "16k"), ("guest-session", "1m"), ("provider-operation", "16k"), ("provider-media", "16k")):
                text += f'''        location ~ ^/(?:starhub/)?api/{route}$ {{
            client_max_body_size {maximum};
            proxy_next_upstream off;
            {common}
        }}
'''
        if app == "portfolio":
            text += f'''        location = /api/server {{
            default_type application/json;
            add_header Cache-Control no-store always;
            return 200 '{{"serverId":{config['nodeId']},"scope":"entry"}}';
        }}
'''
        text += '''        location @entry_unavailable {
            recursive_error_pages off;
            default_type application/json;
            add_header Cache-Control no-store always;
            return 503 '{"status":"failure","code":"ENTRY_UNAVAILABLE","message":"服务器故障，请等待恢复"}';
        }
    }
'''
    return text + "}\n"


def write_private(path, content):
    path = Path(path)
    require(not path.is_symlink(), "Private state cannot be a symlink")
    temporary = path.with_name(path.name + ".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(content); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def _private_audit_file(path, owner):
    """Validate/sync an append-only use of the current file, without rotation.

    The owner parameter is fixed to root by prepare_audit_log; local filesystem
    tests can exercise the same checks under their own unprivileged UID.
    """
    path = Path(path)
    parent = path.parent.lstat()
    require(stat.S_ISDIR(parent.st_mode) and parent.st_uid == owner
            and stat.S_IMODE(parent.st_mode) == 0o700,
            "Root-private durability log directory required")
    descriptor = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_CLOEXEC | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == owner and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600,
                "Root-owned single-link private durability log required")
        os.fsync(descriptor)
        current = path.lstat()
        require((current.st_dev, current.st_ino) == (info.st_dev, info.st_ino),
                "Durability log changed during observation")
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        os.close(descriptor)


def prepare_audit_log(config):
    require(os.geteuid() == 0, "Durability log preparation requires root")
    _private_audit_file(Path(config["runtimeDirectory"]) / "private" / AUDIT_FILENAME, 0)


def _private_nginx_pid(path, owner=0, group=0):
    """An empty nginx-t artifact is not a master; never repair/delete a PID."""
    path = Path(path)
    parent = path.parent.lstat()
    require(stat.S_ISDIR(parent.st_mode) and parent.st_uid == owner
            and stat.S_IMODE(parent.st_mode) == 0o700, "Root-private PID parent required")
    try:
        before = path.lstat()
    except FileNotFoundError:
        return {"kind": "absent", "pid": None, "file": None}
    require(stat.S_ISREG(before.st_mode) and before.st_uid == owner and before.st_gid == group
            and before.st_nlink == 1 and stat.S_IMODE(before.st_mode) in (0o600, 0o644)
            and before.st_size <= 32, "Unknown or unsafe private Nginx PID file")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        info = os.fstat(descriptor)
        require((info.st_dev, info.st_ino) == (before.st_dev, before.st_ino), "Nginx PID changed while opening")
        raw = os.read(descriptor, 33)
    finally:
        os.close(descriptor)
    current = path.lstat()
    require((current.st_dev, current.st_ino, current.st_size) == (info.st_dev, info.st_ino, len(raw)),
            "Nginx PID changed during observation")
    identity = (info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode), info.st_nlink)
    if raw == b"":
        return {"kind": "empty", "pid": None, "file": identity}
    require(re.fullmatch(rb"[1-9][0-9]{0,9}\n?", raw) is not None, "Invalid private Nginx PID")
    return {"kind": "numeric", "pid": int(raw), "file": identity}


def _nginx_unit_observation(unit):
    require(unit in ("codex-lab-nginx.service", "nginx.service"), "Unknown Nginx service")
    fields = "Id,LoadState,ActiveState,SubState,MainPID,ControlPID,InvocationID,ControlGroup,User,Group,Type,ExecStart,FragmentPath,DropInPaths"
    result = subprocess.run(["/usr/bin/systemctl", "show", unit, "--property=" + fields],
                            capture_output=True, text=True, check=False, timeout=5)
    require(result.returncode == 0 and len(result.stdout) < 65536, "Actual local Nginx service metadata unavailable")
    values = {}
    for line in result.stdout.splitlines():
        require("=" in line, "Incomplete Nginx service metadata")
        key, value = line.split("=", 1)
        require(key not in values, "Duplicate Nginx service metadata")
        values[key] = value
    return values


def _entry_nginx_master(config, proc_root=Path("/proc"), owner=0, unit_query=None,
                        cgroup_root=Path("/sys/fs/cgroup")):
    """Identity authority is trusted local systemd plus visible proc metadata.

    The restricted checker cannot read the stronger-capability master's exe.
    No proc/exe inode claim is made: root-owned executable/launch arguments,
    actual MainPID/InvocationID/cgroup and root proc UID/start ticks are required.
    """
    proc_root = Path(proc_root)
    require((proc_root / "self/status").is_file(), "Strict proc view required for Nginx identity")
    binary = Path(config.get("nginxBinary", "/usr/sbin/nginx")).resolve(strict=True)
    executable = binary.stat()
    require(stat.S_ISREG(executable.st_mode) and executable.st_uid == owner
            and not executable.st_mode & 0o022, "Trusted actual Nginx executable required")
    expected_inode = (executable.st_dev, executable.st_ino)
    expected_config = str(Path(config["runtimeDirectory"]) / "private/nginx.conf")
    expected_argv = str(binary) + " -c " + expected_config + " -g daemon off;"
    unit = config.get("nginxUnit", "codex-lab-nginx.service")
    require(unit in ("codex-lab-nginx.service", "nginx.service"), "Unknown Nginx service")
    query = _nginx_unit_observation if unit_query is None else unit_query
    observed = query(unit)
    require(observed.get("Id") == unit and observed.get("LoadState") == "loaded"
            and observed.get("ControlPID") == "0", "Unknown or transitioning Nginx unit")
    fragment = Path(observed.get("FragmentPath", ""))
    require(fragment.is_absolute() and not fragment.is_symlink(), "Known Nginx unit file required")
    unit_file = fragment.stat()
    require(stat.S_ISREG(unit_file.st_mode) and unit_file.st_uid == owner and unit_file.st_nlink == 1
            and not unit_file.st_mode & 0o022 and not observed.get("DropInPaths"), "Unreviewed Nginx unit file or drop-in")
    active = observed.get("ActiveState") == "active"
    if active:
        require(observed.get("SubState") == "running" and observed.get("Type") == "simple"
                and observed.get("User") == "root" and observed.get("Group") in ("", "root")
                and re.fullmatch(r"[0-9a-f]{32}", observed.get("InvocationID", ""))
                and re.fullmatch(r"[1-9][0-9]{0,9}", observed.get("MainPID", "")), "Actual root Nginx service incarnation required")
        prefix = "{ path=" + str(binary) + " ; argv[]=" + expected_argv + " ; ignore_errors=no ; "
        command = observed.get("ExecStart", "")
        require(command.startswith(prefix) and command.endswith(" }") and command.count("{ path=") == 1
                and re.findall(r" ; pid=([0-9]+) ;", command) in (["0"], [observed["MainPID"]]),
                "Nginx unit must directly launch the exact private foreground configuration")
        group = "/system.slice/" + unit
        require(observed.get("ControlGroup") == group, "Nginx unit cgroup differs")
    else:
        require(observed.get("ActiveState") in ("inactive", "failed") and observed.get("MainPID") == "0",
                "Nginx unit is not proved stopped")
        group = None
    found = []
    for process in proc_root.glob("[0-9]*"):
        try:
            # comm/status are world-readable process metadata. Reading every
            # other UID's exe requires ptrace privileges this checker does not
            # have and must not gain merely to publish its private entry.
            if (process / "comm").read_text().strip() not in (binary.name, "nginx"):
                continue
            status = dict(line.split(":", 1) for line in (process / "status").read_text().splitlines() if ":" in line)
            if [int(value) for value in status["Uid"].split()] != [owner] * 4:
                continue
            before = (process / "stat").read_text().rsplit(")", 1)[1].split()[19]
            raw = (process / "cmdline").read_bytes()
            require(len(raw) <= 65536, "Unbounded Nginx process identity")
            arguments = [part.decode("utf-8") for part in raw.split(b"\0") if part]
            if not arguments:
                continue
            title = "nginx: master process "
            is_master = arguments[0].startswith(title)
            tokens = shlex.split(arguments[0][len(title):]) if is_master else arguments
            configs = []
            for index, token in enumerate(tokens):
                if token == "-c":
                    require(index + 1 < len(tokens), "Incomplete Nginx configuration identity")
                    configs.append(tokens[index + 1])
                elif token.startswith("-c") and len(token) > 2:
                    configs.append(token[2:])
            if expected_config not in configs:
                continue
            require(configs == [expected_config] and is_master and len(arguments) == 1
                    and arguments[0] == title + expected_argv,
                    "Entry Nginx master arguments are not the exact reviewed launch")
            require(active and int(process.name) == int(observed["MainPID"]),
                    "Unexpected root master uses this private Nginx configuration")
            require((process / "cgroup").read_text().splitlines() == ["0::" + group]
                    and str(int(process.name)) in (Path(cgroup_root) / group.lstrip("/") / "cgroup.procs").read_text().splitlines(),
                    "Nginx MainPID is not a member of its actual unit cgroup")
            after = (process / "stat").read_text().rsplit(")", 1)[1].split()[19]
            require(before.isdigit() and before == after, "Nginx process incarnation changed")
            found.append({"pid": int(process.name), "startTicks": int(before),
                          "configuredBinaryInode": expected_inode, "cmdlineSha256": hashlib.sha256(raw).hexdigest(),
                          "unit": unit, "invocationId": observed["InvocationID"], "cgroup": group,
                          "identityAuthority": "local-systemd-plus-visible-proc", "procExeInodeVerified": False})
        except FileNotFoundError:
            if process.exists():
                # Only an already-dead candidate is presumed absent; unreadable
                # live root nginx identity always closes publication.
                status = dict(line.split(":", 1) for line in (process / "status").read_text().splitlines() if ":" in line)
                require(status.get("State", "").strip().startswith("Z") and (process / "cmdline").read_bytes() == b"",
                        "An uninspectable live process prevents Nginx identity proof")
        except (PermissionError, UnicodeError, KeyError, IndexError, ValueError):
            raise Closed("Actual Nginx process identity is unavailable") from None
    require(len(found) <= 1, "Multiple masters use this private Nginx configuration")
    require(bool(found) == active and query(unit) == observed, "Nginx service or process identity changed during observation")
    return found[0] if found else None


def _pid_matches_master(pid, master):
    if master is None:
        require(pid["kind"] in ("absent", "empty"), "Stale numeric Nginx PID is not a stopped proof")
    else:
        require(pid["kind"] == "numeric" and pid["pid"] == master["pid"],
                "Private Nginx PID does not identify its actual live master")


class Publisher:
    def __init__(self, config, command=subprocess.run, audit=prepare_audit_log, master=None):
        self.config, self.command = config, command
        self.audit = audit
        self.master = (lambda: _entry_nginx_master(config)) if master is None else master
        self.directory = Path(config["runtimeDirectory"]) / "private"
        self.current = self.directory / "nginx.conf"
        self.previous = None

    def publish(self, healthy):
        # Also check the deduplicated path: Nginx USR1 can change log ownership.
        # Failed sync/ownership checks close health admission; they never repair
        # or truncate an unexplained log, and are not per-response fsync proof.
        self.audit(self.config)
        contents = render(self.config, healthy)
        if contents == self.previous:
            return
        candidate = self.directory / "nginx.candidate.conf"
        write_private(candidate, contents)
        binary = self.config.get("nginxBinary", "/usr/sbin/nginx")
        pid_file = self.directory / "nginx.pid"
        before_master = self.master()
        before_pid = _private_nginx_pid(pid_file, os.geteuid(), os.getegid())
        _pid_matches_master(before_pid, before_master)
        test = self.command([binary, "-t", "-c", str(candidate)], capture_output=True, check=False, timeout=15)
        require(test.returncode == 0, "Generated Nginx configuration was rejected; admission stays closed")
        after_master = self.master()
        after_pid = _private_nginx_pid(pid_file, os.geteuid(), os.getegid())
        require(after_master == before_master, "Nginx master changed during configuration validation")
        _pid_matches_master(after_pid, after_master)
        if before_pid["kind"] != "absent":
            require(after_pid["file"] == before_pid["file"], "Private Nginx PID file changed during validation")
        old = self.current.read_text() if self.current.exists() else None
        write_private(self.current, contents)
        try:
            if before_master is not None:
                require(self.master() == before_master, "Nginx master incarnation changed before reload")
                require(_private_nginx_pid(pid_file, os.geteuid(), os.getegid()) == after_pid,
                        "Private Nginx PID changed before reload")
                reloaded = self.command([binary, "-s", "reload", "-c", str(self.current)], capture_output=True, check=False, timeout=15)
                require(reloaded.returncode == 0, "Private entry reload was rejected")
                require(self.master() == before_master, "Nginx master incarnation changed during reload")
                require(_private_nginx_pid(pid_file, os.geteuid(), os.getegid()) == after_pid,
                        "Private Nginx PID changed during reload")
            self.previous = contents
        except Exception:
            if old is not None:
                write_private(self.current, old)
            raise


def handler(admission):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/status":
                status = admission.snapshot()
                raw = json.dumps(status, separators=(",", ":")).encode()
                self.send_response(200 if status["fresh"] and any(status["healthyNodeIds"].values()) else 503)
                self.send_header("Content-Type", "application/json")
                self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(raw)))
                self.end_headers(); self.wfile.write(raw); return
            app = self.path.removeprefix("/admit/") if self.path.startswith("/admit/") else ""
            self.send_response(204 if admission.admitted(app) else 403)
            self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", "0"); self.end_headers()

        def log_message(self, *_args):
            pass
    return Handler


def notify_ready():
    address = os.environ.get("NOTIFY_SOCKET")
    if not address:
        return
    require(address.startswith(("/", "@")), "Invalid local systemd notification endpoint")
    if address.startswith("@"):
        address = "\0" + address[1:]
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as notification:
        notification.connect(address); notification.sendall(b"READY=1")


def serve(config):
    require(os.geteuid() == 0, "Entry checker requires its root-owned privileged loopback listener")
    directory = Path(config["runtimeDirectory"])
    directory.mkdir(mode=0o711, parents=True, exist_ok=True)
    require(not directory.is_symlink() and directory.stat().st_uid == 0 and stat.S_IMODE(directory.stat().st_mode) == 0o711,
            "Root-owned traversal-only runtime directory required")
    import pwd
    account = pwd.getpwnam(config.get("nginxUser", "www-data"))
    groups = os.getgrouplist(account.pw_name, account.pw_gid)
    for ancestor in directory.parents:
        info = ancestor.stat()
        executable = stat.S_IXUSR if info.st_uid == account.pw_uid else stat.S_IXGRP if info.st_gid in groups else stat.S_IXOTH
        require(info.st_mode & executable, "A runtime ancestor prevents Nginx worker traversal")
    private = directory / "private"
    private.mkdir(mode=0o700, exist_ok=True)
    require(not private.is_symlink() and private.stat().st_uid == 0 and stat.S_IMODE(private.stat().st_mode) == 0o700,
            "Root-only configuration/PID directory required")
    for name in ("client-body", "proxy-temp"):
        temporary = directory / name
        temporary.mkdir(mode=0o700, exist_ok=True)
        require(not temporary.is_symlink(), "Temporary directory cannot be a symlink")
        os.chown(temporary, account.pw_uid, account.pw_gid); os.chmod(temporary, 0o700)
    lock = (private / "checker.lock").open("a")
    os.chmod(private / "checker.lock", 0o600)
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    admission = Admission(config.get("maxAgeSeconds", 15))
    publisher = Publisher(config)
    publisher.publish({app: [] for app in APPS})
    server = ThreadingHTTPServer(("127.0.0.1", config["admissionPort"]), handler(admission))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    notify_ready()
    prober = Prober(config)
    try:
        while True:
            try:
                desired = admission.observe(prober.all())
                # Removal takes effect in auth_request before a slow/failed
                # Nginx reload; additions wait for a tested publication.
                with admission.lock:
                    admission.healthy = {app: [n for n in admission.healthy[app] if n in desired[app]] for app in APPS}
                publisher.publish(desired)
                admission.publish(desired)
            except Exception:
                admission.publish({app: [] for app in APPS})
            time.sleep(config.get("intervalSeconds", 3))
    finally:
        server.shutdown(); server.server_close(); lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("render", "serve")); parser.add_argument("--config", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    config = load_config(args.config)
    if args.action == "render":
        require(args.output and Path(args.output).is_absolute(), "Explicit absolute private output required")
        prepare_audit_log(config)
        write_private(args.output, render(config, {app: [] for app in APPS}))
        print("Private closed entry configuration rendered; no live service changed.")
    else:
        serve(config)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Entry checker refused unsafe or unavailable configuration; private details were not logged.")
