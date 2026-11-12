# Two-node peer controller contract

This is a two-node, equal-writer controller for the **new atomic application
backend**, not permission to run the legacy cross-JVM XA recovery path. Both
healthy MySQL Group Replication members must be `PRIMARY`, have
`group_replication_single_primary_mode=OFF`,
`group_replication_consistency=BEFORE_AND_AFTER`, `sync_binlog=1`,
`innodb_flush_log_at_trx_commit=1` and InnoDB doublewrite enabled. The final
transport is the authenticated `MYSQL` stack on the protected SQL listener,
`require_secure_transport=ON`, TLSv1.3 only, and `VERIFY_IDENTITY`. Distributed
recovery and ordinary MYSQL-stack group traffic use the pinned private CA and
node certificates, server identity verification and a dedicated `REQUIRE X509`
recovery account. Unauthenticated XCOM was only an initial isolated experiment;
it is refused by the final controller policy. Every normal
acknowledged write is checked for durable execution on both nodes. No timeout
creates write authority or proves a fence.

## Private application protocol

`GET /authority`, authenticated by a **separate application token**, returns 204
only for a current admitted view. Headers:

- `X-Four-Project-MySQL-UUID`
- `X-Four-Project-View-ID`
- `X-Four-Project-Epoch` (local durable grant revision)
- `X-Four-Project-Mode`: `dual` or `single`
- `X-Four-Project-Membership`: SHA-256 of the common membership policy
- `X-Four-Project-Controller-Boot-ID`
- `X-Four-Project-Permit` and `X-Four-Project-Authority-Stamp`: the same opaque,
  stable root-key HMAC bound to this grant, view, identities and mode.

Request challenges are deliberately **not** part of the permit. A healthy
unchanged grant has a stable permit, including during a long transaction.
Applications capture it before work, check the exact permit before commit, and
after commit call `POST /confirm` with JSON `{permit,gtidSet,operationId}`.
`gtidSet` must be captured from the same local MySQL after committing. In dual
mode the controller waits for this GTID set locally and on its peer, checks
durability settings, then verifies the same grant again. Success is 204 with
`X-Four-Project-Durability: dual`. Single mode returns `single`, explicitly
degraded. A dual permit cannot be confirmed by a later single grant. Failure or
an uncertain response is an unknown outcome resolved using the operation ID;
the client must not invent a new ID and repeat a possibly committed write.

`GET /ready` additionally requires all configured application recovery checks,
real protected capacity and a validated measured cost model. `/authority` does
not depend on application recovery, avoiding an initialization cycle. `/status`
supplies the view/membership and physical capacity fields used by Star_Hub.

## Live SQL policy and startup guard

The controller reads the following settings from actual MySQL on every authority
observation, confirmation and watchdog check. Missing fields close authority.

| Safety policy | Required live evidence |
| --- | --- |
| Coordinated equal writers | Multi-primary, update-everywhere checks enabled, `BEFORE_AND_AFTER`, disjoint audited allocators |
| Durable history | GTID mode and GTID consistency enabled, ROW logging, replica updates and commit-order preservation enabled, flush/sync values 1, doublewrite enabled |
| Explicit recovery | Persisted-global loading, GR boot/bootstrap start and autorejoin disabled; majority timeout 0; configured `READ_ONLY` exit action |
| No autonomous application writes | Event scheduler exactly OFF, offline mode OFF, MySQL X plugin DISABLED or absent |
| Protected local endpoints | Actual loopback bind/report addresses, privileged SQL/report port, MYSQL group listener and the two group seed addresses match the SHA-bound guard unit |
| Correct protected storage | Actual data, socket and temporary paths match the same guard unit and the protected data directory |
| Authenticated private transport | The existing TLS 1.3, VERIFY_IDENTITY, X.509 recovery and exact configured certificate-path checks still apply |

The startup guard forces read-only settings; normal DUAL/SINGLE authority instead
requires both ordinary and super read-only values OFF. RECONCILING requires a
completed super-read-only freeze. A controlled bootstrap may temporarily set its
bootstrap flag while JOINING, where application authority remains closed.
The configured expulsion timeout is also checked against the bound unit; it is
never treated as proof that another host has been isolated.

Buffer/cache/PFS sizes, connection/replica worker counts and redo capacity are
resource-profile settings. Changing them requires capacity/resource validation;
their values do not establish fencing or grant authority. This controller does
not claim that its SQL attestation replaces reserve, cgroup, swap or real
application checks. After a source or policy change, rerun the required live
matrix on that installed version before claiming final acceptance.

## Root-only peer and actuation protocol

The peer secret is root-owned 0600 and is different from the application token.
The application token cannot call peer/admin/fence endpoints. TCP listeners bind
only to 127.0.0.1 and cross-host traffic uses the reviewed SSH transports. Bodies
and authorization headers are never logged. HMAC requests and responses name
the cluster, sender, receiver, boot IDs, controller incarnations and challenges.
The responder issues a short-lived monotonic one-use challenge; a fresh client
nonce binds the response. Old/repeated actions fail after consumption or expiry,
and old incarnations fail after restart. A snapshot/fence acknowledgment also
performs a new callback echo, so a one-direction administrative connection alone
does not authorize either node.

Local root commands and the database startup guard also use signed one-use
challenge exchanges, not a bearer containing the root secret. They verify signed
responses before trusting them. Occupying the TCP listener while the controller
is stopped cannot make a fake server steal the root key or authorize startup.

On **every** controller start, before serving, it persists `FENCING` with a new
random incarnation and fsyncs the state and directory. It stops and persistently
masks its configured MySQL unit, checks the unit inactive/masked and checks that
no process is using the protected data directory. Only then does it fsync
`FENCED`. A persistent systemd `ExecCondition` contacts the *live current-boot*
controller; an old file alone never permits a database start. MySQL defaults to
super-read-only, no Group Replication autostart and no automatic restart. A
controller failure closes application authority. A returning controller fences
again rather than trusting an old grant. Any state-write or actuation failure
closes authority and cannot produce a fence acknowledgment.

When local SQL fails, the local watchdog performs that durable fence. A survivor
that was admitted in the dual view obtains a fresh bilateral peer fence proof,
checks its own database process identity and durability, and only then changes
the GR view. The proof must name exactly the excluded peer and current controller
sessions. The survivor admits single-copy service only after the actual local
one-member view is observed. Both nodes down/fenced cannot promote each other.
Loss of peer communication closes normal authority; it never triggers an unsafe
timeout promotion. Single authority also requires a current peer fence response.

Initial bootstrap is an explicit root operation while both applications are
closed: prepare both databases read-only, inspect comparable GTIDs and equal
complete business-data fingerprints, bootstrap the most-current temporary seed,

join the other, then admit both
PRIMARY members. The fingerprint covers every configured business table's
structure, row count and a deterministic complete-data SHA-256 obtained from a
primary-key-ordered hex-BLOB dump stream. No dump bytes are retained or logged.
The versioned audit also hashes database default character set/collation and
encryption options, SQL/JSON-duality view definitions, stored function/procedure
and JavaScript/WebAssembly library definitions, event definitions, and trigger execution/ordering/security/SQL-mode/charset
fields. Trigger creation timestamps are local administrative metadata and are
excluded; executable definitions and ordering remain exact. A missing private
definition or unexpected identifier closes admission. The actual two-node
inventory had no views, triggers, routines or events in the twelve business
schemas when checked; those empty inventories are still audited.
The 32-bit MySQL table checksums are additional diagnostics, never the sole
content proof. A narrowly scoped CHAR/VARCHAR/TEXT column canonicalization
removes an explicit character-set name only when the immediately following
named collation already implies that same character set; defaults and other
DDL remain exact. The temporary seed is not a permanent writer role. A fenced
returning member is started read-only and may only join the survivor's existing
group. Its GTID set must be a subset of the survivor's; errant data is refused.
When membership changes both old permits close. Both controllers verify the
same two-member view before admitting dual operation again.

Rejoining does not itself admit application writes. Before `START
GROUP_REPLICATION`, the returning controller fsyncs **RECONCILING**. This also
keeps application authority closed before the native group join. Protocol 3 has
a distinct common policy digest: an older SRO-only controller cannot participate
in its admission. Upgrade both controllers under verified local fences; do not
mix controller versions and treat an older audit as protocol-3 evidence.

Admission protocol 3 temporarily selects an audit coordinator from the two fresh
controller incarnations. Both controllers persist RECONCILING, then enable
`super_read_only` to block new writes. **SRO alone does not prove that idle,
previously modified transactions have ended.** Actual MySQL 26.10 tests observed
such a transaction still RUNNING with modified rows after SRO became 1; the
[exact 26.10 implementation](https://github.com/mysql/mysql-server/blob/mysql-26.10.0/sql/sys_vars.cc)
releases its temporary commit barrier while old RW transactions may remain.
The controller therefore explicitly waits for all modified InnoDB transactions
and all write-intent metadata locks to drain. It includes unknown/replication
writers and every metadata-lock lifecycle state, including low-priority DML.
Only the positively identified fresh, read-only observation connection's own
metadata locks are excluded. Incomplete/disabled instrumentation, lost records,
unknown lock types or a deadline cannot establish drainage and keep admission
closed. The controller neither guesses abort from age nor kills an unknown
transaction. Replication threads may still catch up while clients cannot start
new writes. Both controllers wait for the union of
committed GTIDs, require bidirectional GTID inclusion and no prepared legacy XA,
and independently hash every configured business table's schema and complete
primary-key-ordered data. The GTID set, view, process identities and freeze are
checked again after hashing. The drained-writer condition is checked before and
after hashing, again before arming, and immediately before enabling writes.

Only matching fresh proofs can fsync the two new DUAL grants. Both databases
remain frozen at that point. Separate authenticated audit-enable exchanges
recheck the exact audit identity and both durable grants before unfreezing either
server. If one side alone unfreezes, authority remains closed because it also
requires the peer's current writable view and matching audit proof. Failure,
restart, stale or repeated messages never restore the old grant. Audit failure
keeps the relevant authority closed and keeps the databases read-only.

The equal-writer allocator uses server IDs and offsets 1 and 2 with increment 7,
including `group_replication_auto_increment_increment=7`. These are checked in
the actual MySQL snapshot. A table's local AUTO_INCREMENT high-water counter can
differ even when all committed rows agree. Only a single-column automatic
primary key with a checked next counter strictly above its committed maximum is
eligible for canonicalization. Only the numeric AUTO_INCREMENT option on the
actual final table-options line is omitted from comparison; column definitions,
defaults, comments and every other table option remain exact. Invalid or
colliding offsets close admission. The previous laboratory server IDs 101/102
produced offsets exceeding increment 7 and were corrected without changing UUIDs,
GTIDs or user data.

During a rolling upgrade, a quarantined existing member may lose quorum when the
other controller fences its SQL. The signed root-only `reconcile-seed` action
requires a fresh bilateral proof that the peer is currently persistently fenced.
It recovers only that existing member's group view, keeps RECONCILING and freezes
client writes again. It issues no SINGLE application grant. The peer can then
prepare/join this closed existing seed and both must pass the full audit. This
is not a replacement for strict initial equal-data bootstrap or a way to discard
errant GTIDs.

A final GR view transaction during planned shutdown can make one GTID set a
strict superset while all business rows are equal. That member can be the
temporary seed regardless of its node number; identical GTID sets use the lower
node number only as a bootstrap tie-break. Normal `bootstrap` requires equal
business fingerprints. An explicitly invoked root `bootstrap-latest` may instead
start the strict GTID-superset node as a read-only, closed seed while its stale
peer catches up. It never issues SINGLE application authority. Equal GTID sets
with unequal business data, or divergent GTID sets, are refused even by this
recovery action. Both nodes must still pass the common complete audit after
catchup. The controller never erases GTID history or overwrites a stale dataset
by guessing.

The root CLI is `python3 peer_controller.py ACTION --config PRIVATE_JSON`.
`serve` always performs startup fencing before listening; `guard` is for the
systemd startup condition; `prepare`, `bootstrap`, `join`, `arm-dual`, `takeover`
and `fence` are explicit signed local transitions. `reconcile-seed` is the
closed existing-group recovery described above. `reload-observations` may
reload only `capacity`, `tomcatChecks` and `recoveryChecks` from that same private
file. Changing any database, identity, peer, root credential, audit policy or
actuator setting requires a new fenced controller incarnation.

## Trust boundary and acceptance

This fences the configured MySQL process/service under the existing trusted root
administrative domain. It does not claim to defeat a malicious root user who
removes the guard, launches an uncontrolled server or restores an old OS image
that predates the guard. Restoring an old **controller state file**, controller
restart and host reboot are handled by the live boot/incarnation barrier and
startup fencing. Root credentials, unit/config permissions and the guard are part
of the trusted installation. An unverified administrative `mask` alone is not a
fence proof.

Production stays closed until the exact installed actuator, both MySQLs, durable
GTID confirmation, cross-JVM application paths, delayed/duplicate messages,
partitions, controller failures, both failure directions and controlled rejoin
pass on isolated databases first. Model tests alone do not accept the live
deployment. Scratch mode must use only dedicated `codex-*` units and a separate
`/var/lib/codex-validation` tree.

Official current references:
[MySQL 26.10 GTID waits](https://dev.mysql.com/doc/refman/26.10/en/gtid-functions.html),
[Group Replication modes](https://dev.mysql.com/doc/refman/26.10/en/group-replication-deploying-in-multi-primary-or-single-primary-mode.html),
[durable binary logging](https://dev.mysql.com/doc/refman/26.10/en/binary-log.html),
[authenticated MYSQL communication](https://dev.mysql.com/doc/refman/26.10/en/group-replication-connection-security.html),
[Group Replication TLS](https://dev.mysql.com/doc/refman/26.10/en/group-replication-secure-socket-layer-support-ssl.html),
[message fragmentation](https://dev.mysql.com/doc/refman/26.10/en/group-replication-performance-message-fragmentation.html),
[read-only completion semantics](https://dev.mysql.com/doc/refman/26.10/en/server-system-variables.html#sysvar_read_only).
The trigger field selection follows the
[MySQL 26.10 trigger inventory](https://dev.mysql.com/doc/refman/26.10/en/information-schema-triggers-table.html);
private routine definitions must be available through
[SHOW CREATE PROCEDURE/FUNCTION](https://dev.mysql.com/doc/refman/26.10/en/show-create-procedure.html).
[SHOW CREATE VIEW](https://dev.mysql.com/doc/refman/26.10/en/show-create-view.html)
retains JSON-duality annotations, and
[SHOW CREATE LIBRARY](https://dev.mysql.com/doc/refman/26.10/en/show-create-library.html)
retains WebAssembly bodies that the library inventory alone does not expose.

The current cross-cloud laboratory candidate explicitly uses 524,288-byte GR
message fragments; the previous tested candidate used 1,048,576 bytes. The exact configured bound is checked against live MySQL and included
in the shared policy digest. Both the default 10-MiB setting and the intermediate 1-MiB candidate stalled
an unacknowledged 64-MiB synthetic transaction and repeatedly lost quorum. A
12-MiB synthetic write succeeded with 1-MiB fragments. None of the timeouts
were treated as a rollback or a fence. The acknowledged UCost revision 3 was
verified on both members before and after controlled isolation/crash recovery.
The 512-KiB candidate with an actual Connector/J `ServerPreparedStatement` also
lost stable communication during the same 64-MiB logical operation. The client
reported an UNKNOWN socket timeout. Later metadata and crash recovery found its
eight rows and next committed GTID on both members; the original operation was
then verified on each member using its owner/generation, exact content SHA and
dual confirmation. It was neither reinserted nor discarded. The available
observations bound commitment to approximately 78–136 seconds after dispatch;
they do not identify an exact commit instant or a successful original reply.
Thus a single large GR transaction is not accepted as reliable file-transfer
behavior, despite the eventual retained result. Smaller durable staging with a
small atomic publication transaction needs separate implementation and testing.
Temporarily removing MemoryHigh
throttling and increasing the message cache to 256 MiB did not establish natural
completion; neither was accepted as a sufficient fix. The bounded 30-second SQL stop timeout triggers a real stop/kill
actuator; isolation still requires an inactive masked service and no protected
process, never the passage of that time alone.

The deployed isolated lab currently uses `codex-lab-peer-controller.service`,
`codex-lab-mysql.service`, and private configuration
`/var/lib/codex-validation/controller/controller.json`. Root commands are run
locally on the chosen host, with `sudo -n` on the Ubuntu host. From a fully
fenced start, execute separately and inspect each result:

1. Start `codex-lab-peer-controller.service` on node 1.
2. Start `codex-lab-peer-controller.service` on node 2.
3. Run `python3 /usr/local/lib/codex-lab-peer/peer_controller.py prepare --config /var/lib/codex-validation/controller/controller.json` on node 1.
4. Run the same `prepare` command on node 2.
5. Run `bootstrap` with that exact script/config on the most-current verified
   seed; equal GTIDs use node 1. An unequal or divergent seed is refused.
6. Run `join` with that script/config on the other node.
7. Inspect both real GR views for two ONLINE PRIMARY members. The watchdog
   performs the frozen full audit before admitting dual; `arm-dual` can request
   that audit. A successful pending command alone is not DUAL admission: inspect
   both durable grants, matching audit proof and final super/read_only values.
8. Verify stable application authority and acknowledge an actual committed GTID
   with `/confirm` on both nodes. An empty initial GTID set is not a committed
   business operation and is deliberately refused.
9. Keep application readiness closed until real capacity, recovery and all
   failure acceptance tests pass. Controller authority alone is insufficient.

Every controller restart fences SQL and invalidates old grants. Never restart
one casually while applications are serving, remove the guard or change the
service mask to bypass recovery. Recovery from a single surviving group uses
`prepare` then `join`, not a new bootstrap. The final user-facing manual must
also include application stop/start, entrance forwarding, backup and cleanup
steps matching the final installed paths; this is the narrower controller
contract only.
