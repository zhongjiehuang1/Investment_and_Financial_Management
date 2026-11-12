# Equal-node HTTP entry and active health admission

Implementation checkpoint: 11 November 2026. The candidate script is staged on
both installed hosts with its entry services stopped. Thirteen local tests and
real Nginx parsing for closed, node-one, node-two, both-node and mixed topologies
passed on both hosts. This does not establish functional TLS/browser forwarding
or fault timing. Those actual tests remain required before formal writes open.

## Routing and health dependency

Each host has a separate Nginx entry and a root-owned checker. Both entries can
select either equal application node. New sessions use equal round robin;
JSESSIONID.n1/n2 (Finance/Star) and UCOST_SESSION.n1/n2 (UCost) select their sticky
node, with the healthy peer as backup. A removed primary leaves its peer as an
ordinary live server. No healthy upstream yields a closed dummy upstream.
There is no permanent writer role or DNS-based health decision in this design.

On both hosts, pinned privileged SSH forwards expose both Tomcats at loopback
881/882 and controllers at 941/942. The checker uses direct loopback only,
ignores inherited HTTP proxy environment variables and refuses redirects.
For each node it captures complete authenticated authority evidence, queries the
private controller /ready, actual app /api/server identity and each runtime
recovery endpoint, then requires exactly the same authority evidence again.
The evidence contains MySQL UUID, view, epoch, mode, membership, permit and
controller boot ID. Missing, malformed or duplicate proof headers are refused;
a controller reboot between samples invalidates the observation. Configuration
pins each physical node's Tomcat and controller ports rather than accepting
arbitrary loopback listeners.
The controller verifies real MySQL membership/durability/fencing; runtime recovery
must use actual application JDBC credentials. Cached application booleans alone
are insufficient. The controller's /ready must probe direct recovery endpoints,
never this entry or a public /cluster-health endpoint. Recovery probes use
independent /authority, never /ready, and do not update the certification guard.

Two consecutive successful observations with the same authority fingerprint add
a database application node. A failed observation removes it immediately. A new
authority fingerprint needs two successes again. These are admission intervals,
not measured takeover times. Actual timing remains an acceptance result.

Each proxied request additionally uses Nginx auth_request against its LOCAL
checker at 943/944. This listener has no control operations and is never exposed
by a public location. Admission expires after the configured monotonic age;
checker death, stale evidence, backward clock movement or publication failure
closes requests. The Nginx upstream update is config-tested and atomically
published before additions are admitted. A failed config test cannot replace
live configuration. Reload checks the exact private master PID/config path.

Portfolio is read-only. Its actual Tomcat index must first match a verified
release SHA256. Subsequent HTTP HEAD metadata probes reuse that content check
until metadata changes or one hour passes, reducing continuous cross-cloud body
transfer. A fresh content check then runs. This is availability/release checking,
not a substitute for the independent complete deployment manifest comparison.
Portfolio's /api/server has `scope=entry`; it identifies this entry, not the
Tomcat chosen for another request. Database apps proxy their actual server ID.

## Request replay and session limits

Every non-GET/HEAD request has proxy_next_upstream off, including all login,
logout, file, key-management and account actions. WebSocket forwarding also has
replay off. Only GET/HEAD can try the peer before any response reaches the client.
There is no `non_idempotent` retry flag. Existing client UUID/payload/owner
protocols resolve uncertain outcomes and explicitly retry the same intent.
Nginx must not invent IDs, inspect/store passwords, or replay external AI calls.
Application 401/409/429/503/507 response bodies remain intact, including operation
state; transport/admission failures return an honest entry-unavailable result.

The entry preserves original Host (including an explicit public test port), uses
that Host port (default TLS443) rather than the private listener port for
X-Forwarded-Port, overwrites X-Real-IP and
X-Forwarded-For with the actual client address, and sets trusted scheme/port
headers. Tomcat's RemoteIpValve must trust only the loopback proxy. Root public
paths are mapped once to the exact prepared context; a same-context prefix is
stripped to prevent context/context URLs. Cookie paths and same-host context
redirects are rewritten. Internal operational paths, WEB-INF/META-INF and source,
config, archive and hidden-file paths are blocked. Tester, provider and UCost
authentication rate limits are retained. Star streams are not response-buffered.

Star's bounded JSON requests also use `proxy_request_buffering off` with HTTP/1.1,
so Nginx can stream them to Tomcat without first spooling a full request to disk.
The existing 6 MiB, 1 MiB and 16 KiB request bounds and global per-entry category
concurrency limits remain in force. Tomcat reads bounded bodies into memory and
does not use inbound multipart temporary files. Actual Content-Length/chunked,
interrupted-body, two-entry convergence, heap and linked/unlinked temporary-file
measurements are still required. Configuration alone is not proof of zero disk
spooling. Read-interval timeouts are not an absolute deadline against a client
that keeps making slow progress. A closed overall readiness gate can still
return ENTRY_UNAVAILABLE; it must not be misreported as a proven business 507.

Tribes DeltaManager session copying needs its separately prepared authenticated,
encrypted private 411/412 channels and sync ACK tests. Session delivery ACK is
not a database commit or two-copy durability proof. In-process Java locks do not
serialize the same session across two JVMs. Database ownership/generation and
atomic-operation checks remain authoritative. Concurrent login/logout/password
change, tester verification, CSRF rotation, currency edits and guest tab flushing
must be tested through both entries. Sticky routing reduces overlap; it does not
make those cross-JVM races disappear.

An intact entry can forward a failed local MySQL/application to the peer. If the
whole entry host or its public IP path disappears, neither other Nginx can take
over TCP connections to that different cloud IP. Two A records do not establish
transparent full-host failover. Client reconnection/address selection needs its
own actual test and limitation statement. No additional server or paid load
balancer is created by this implementation.

## Read-only mutation/health coverage review

| Project/path | Current durable handling | Entry/client implications |
| --- | --- | --- |
| Finance business asset/ledger/inflation/currency/user deletion/logoff servlets | ApiSupport -> atomic transaction; required operation header, owner/generation/payload HMAC and committed result | Keep same UUID after unknown, query operationStatus. Replayed session-only consequences still need HTTP tests. |
| Finance login/loginStatus | Immutable SQL attempts/shared budgets; negative results commit/confirm, current credential/generation revalidated on success replay | No blind replacement on bare 401/409; no session from status. Public register remains forbidden. |
| Finance recovery | Fresh runtime JDBC marker/gate/auth/business reads plus stable authority; no guard write and no /ready recursion | New WAR must replace the prior cached-boolean implementation. |
| Star account changePassword/addKey/selectKey/deleteKey | AtomicHttp request receipt bound to user/generation/path/payload | Same UUID status/replay; no Nginx POST retry. Session edits are deferred until commit confirmation. |
| Star workspace reserve/cancel/upload/trash/restore/delete/saveChat/deleteChat | Atomic file/request protocol, SQL guard and durable reservations/content | Verify reserve/content/cancel arbitration and files on both nodes; preserve pending operations. |
| Star login/loginStatus | Shared SQL attempt protocol and current credential validation | Preserve unknown pending ID; status alone grants no session. |
| Star logout/tester form | Session changes; not a durable database exactly-once operation | Never transparently replay. Cross-node invalidation/rotation needs real session tests. |
| Star guest-session | Old JVM-local Tabs/static locks/budget were unsafe across JVMs; Star agent is replacing them with atomic SQL/vault-encrypted state | Re-review final new protocol and test stale versions, chunks, tab IDs and shared budgets; do not accept the historical implementation. |
| Star chat/run/batch/check/realtime | External provider effects currently have no local durable SENT/UNKNOWN receipt covering all calls | No transparent retry. A timeout does not prove no charge/job occurred. Provider idempotency/status support must be verified per API before retry. |
| Star recovery | Star agent reports fresh actual runtime DB/identity/schema probe with stable independent authority | Require its final WAR/010 schema; cached ready flag is insufficient. |
| UCost data/register/login/password | Atomic SQL operation receipts, revision/session-generation validation; account status available | Retry original UUID after unknown; negative auth outcomes and credential replay need full HTTP tests. |
| UCost logout/cookie-disable/CSRF session creation | Session-only changes; no durable operation receipt | No automatic POST repeat; test session invalidation and CSRF through peer forwarding. |
| UCost recovery | Fresh runtime DB/schema/permissions plus independent permit checks, owned by root | Entry only admits its actual recovery success and matching physical identity. |
| Portfolio | Built static website; no server mutation API found | Validate release manifest, real root/context assets and HTTP GET/HEAD fallback. |

The external-call gap requires a client UUID plus HMAC-bound owner/tab/payload,
a durably confirmed pre-call claim, explicit SENT/COMPLETED/UNKNOWN states and
stored/retrievable results. A locally committed SENT record followed by a crash
cannot show whether the provider acted. Without a verified provider idempotency
key and result-query API it must remain unknown; only an explicit new user intent
may start another charged call. Streams and WebSockets need the same boundary
statement. This protocol has been proposed to root/Star agent and is not claimed
implemented by this entry.

## Private configuration contract

Configuration is a regular owner-private 0600 JSON file, normally
`/etc/four-project-entry/local.json`, inside a root0700 directory. Supply all
values from verified private storage; never paste its contents into logs/docs.
The top level has nodeId (1/2), listenAddress (127.0.0.1 for lab), listenPort
(18443/18444; final 443), admissionPort (943/944), runtimeDirectory,
nginxBinary (/usr/sbin/nginx), nginxUser (www-data), intervalSeconds (default 3),
timeoutSeconds (3), maxAgeSeconds (15), nodes and applications.

nodes has exactly keys `1` and `2`, each with tomcat and controller loopback base
URLs plus explicit private appToken and readyToken. The current controller uses
its private application credential for /ready; if that contract changes supply
the corresponding readyToken explicitly. Neither controller credential is put
in generated Nginx configuration. applications has exactly iafm/starhub/ucost/
portfolio; each has hostname, existing certificate and privateKey file paths.
Database apps have recoveryToken. Finance/Star have their own ingressToken.
Portfolio has the SHA256 indexSha256 from the independently verified release.

## Runtime paths, service preparation and manual commands

Proposed actual installation paths (root must reconcile them with final install):
program `/usr/local/lib/four-project/lab_entry.py`, private configuration
`/etc/four-project-entry/local.json`, runtime `/var/lib/four-project-entry`.
The runtime base is root:root0711; all ancestors must permit worker traversal.
The program checks this, refusing a 0700 parent such as an otherwise private lab
workspace. It creates `private/` root0700, with nginx.conf, candidate config,
nginx.pid and checker.lock root0600. It creates client-body/ and proxy-temp/
www-data:www-data0700. No token is readable by www-data through configuration.
These worker-owned writable paths are necessary for real POST bodies.

Use a separate checker and Nginx master; do not silently replace the existing
system Nginx service. The checker is Type=notify, NotifyAccess=main, User=root,
Restart=on-failure, RestartSec=2. ExecStart is:

    /usr/bin/python3 /usr/local/lib/four-project/lab_entry.py serve --config /etc/four-project-entry/local.json

It notifies readiness after the closed config passes nginx -t and the private
admission listener binds. The Nginx unit uses Requires/After the checker,
Type=forking, User=root and PIDFile
`/var/lib/four-project-entry/private/nginx.pid`. ExecStart is:

    /usr/sbin/nginx -c /var/lib/four-project-entry/private/nginx.conf

ExecReload uses nginx -s reload with the same -c path. A root master needs
CAP_NET_BIND_SERVICE, CAP_CHOWN, CAP_DAC_OVERRIDE, CAP_FOWNER, CAP_SETUID,
CAP_SETGID and CAP_KILL for privileged binding, protected files/temp setup,
worker privilege drop and signaling. The checker needs NET_BIND_SERVICE, CHOWN,
DAC_OVERRIDE and FOWNER for private binding/setup and nginx -t. A bounding set
containing only NET_BIND_SERVICE cannot run a normal root-to-www-data Nginx
master. Verify actual installed Nginx behavior before narrowing further.
NoNewPrivileges=true, ProtectSystem=strict and ReadWritePaths limited to the
runtime are compatible targets, requiring actual unit validation. Do not claim
unverified capability restrictions have passed.

Ordered checks after the protected config/program and units are installed:

1. Inspect the two unit files and the private config ownership/mode (not contents).
2. Run the program's render command to an explicit protected output if reviewing
   the initial closed Nginx configuration is needed. It starts no service.
3. Check `nginx -V` includes the HTTP auth_request module.
4. Start the checker unit; inspect its active status.
5. Run `nginx -t -c /var/lib/four-project-entry/private/nginx.conf`.
6. Inspect runtime, private and worker temporary directory modes individually.
7. Start the separate entry Nginx unit.
8. Query `curl --fail http://127.0.0.1:943/status` (944 on node2). It returns only
   freshness and healthy node IDs; no tokens/user data.
9. Use curl --connect-to with the real production TLS hostname/443 mapped to
   loopback18443/18444 so normal Origin has no extra port. Verify all four root
   pages/assets, application identity and authenticated operations.
10. Test both cookie suffixes, new-session distribution, each failed upstream,
    checker stop/staleness, both-unavailable response, response-loss same-ID
    resolution and no duplicate POST/provider call. Record measured durations.
11. Keep formal writes closed until the complete failure/data/capacity/restore
    acceptance is finished. Formal public443 activation is a separate tested
    configuration change; no staged public high port is allowed.

Official Nginx references: [upstreams and backup servers](https://nginx.org/en/docs/http/ngx_http_upstream_module.html),
[proxy retry/buffering/cookie rules](https://nginx.org/en/docs/http/ngx_http_proxy_module.html),
[internal redirects](https://nginx.org/en/docs/http/ngx_http_core_module.html#error_page),
[auth_request](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html).
