# Atomic replicated finance transactions

Development state: 11 November 2026. This document describes the new implementation;
it is not production acceptance. Keep formal writes closed until the real two-host
controller, schema migration, HTTP flows, partitions, crashes, rejoin and restore
checks have passed. The two physical MySQL members remain equally eligible writers.

## Why the old recovery path is excluded

The old `FinanceCluster.reconcile` checks Narayana liveness in its own JVM. Another
JVM can therefore appear orphaned before its durable decision is recorded. A local
process lock and `SELECT FOR UPDATE` on a different physical group member cannot
make that recovery decision safe. A replicated deployment now requires
`transaction.backend=atomic`. JDBC-backed legacy XA startup/recovery is rejected;
file-backed legacy XA is confined to H2 memory fixtures or explicitly named
loopback `finance_validation_*` / `finance_atomic_validation_*` rehearsal schemas.
The single-database and JNDI routes enforce that same restriction against both
the configured URL and the actual connection metadata/catalog. Missing or stale
cluster configuration cannot fall back to the canonical managed schemas. Static
pages can load while database access remains closed.

Atomic mode never creates `XaTransactions`, starts its recovery scanner, writes XA
branches, or calls orphan abort/reconcile. An interrupted local transaction is
rolled back/recovered by InnoDB. A committed operation and its result are one
atomic database outcome, discoverable by the original operation ID after any JVM
is lost.

## One physical transaction, two logical schemas

Both private JDBC URLs point at the same explicit `127.0.0.1:PORT` MySQL instance,
with different schema names and one dedicated cross-schema DML identity. Logical
schema 1 remains the canonical user/shared-data schema. Financial rows can still
be placed in logical schema 1 or 2 according to ingress; these are logical names,
not physical primary/replica roles. Each physical MySQL member replicates BOTH.

`AtomicSchemaSession` gives existing DAOs two distinct schema-scoped connection
views over one physical connection. Each statement binds its catalog when
prepared and executed. DAO commit/close cannot finish the physical transaction;
DAO rollback makes the whole operation rollback-only. Statements, result sets and
metadata do not expose the physical connection through `getConnection`,
`getStatement` or `unwrap`. Implicit-commit DDL/SET/USE/CALL and multiple SQL
statements are refused. Retained objects fail after the scope ends. The connection
cannot auto-reconnect into a different transaction, and uses catalog semantics,
server prepared statements and `READ COMMITTED`. The transaction URL removes
conflicting timezone aliases, forces `connectionTimeZone=UTC`,
`forceConnectionTimeZoneToSession=true` and `preserveInstants=true`. Runtime checks
the SQL session timezone and CURRENT_TIMESTAMP/UTC_TIMESTAMP agreement; the OS
may keep its existing timezone. Stored historical values are not rewritten.

Every operation, including consistent reads, operation-result/absence queries
and startup ID repair, increments the same canonical `CLUSTER_ATOMIC_GUARD`
version row in its transaction. A genuine version change enters the MySQL group
write set. Two physical members executing overlapping work must certify that
common row; they cannot both silently commit based only on their local row locks.
Certification/deadlock failure is returned without acknowledgment; clients keep
the original operation ID. There is no automatic retry of arbitrary business work
or external effects. Throughput is deliberately limited by this common guard.

Mutation identity, owner, request HMAC and result payload are committed together
with business changes. A rollback leaves no new durable operation receipt: status
is absent and the same ID and original payload can be retried while its data snapshot remains current. Existing legacy ABORTED/COMMITTED rows
remain readable. A same-ID different-owner or different-payload attempt returns
409. Status preserves a historical COMMITTED state even when its old response
payload is missing, returning `resultUnavailable=true`. Re-execution still fails
closed; it never invents an old payload or repeats the business write. Preserve
the canonical historical idempotency secret so old request HMACs remain comparable.

Every atomic mutation must supply its client-generated `X-IAFM-Operation-Id`,
including registration if that endpoint is ever separately authorized. The server
does not generate an undiscoverable replacement ID for a client mutation. Public
registration remains disabled by the existing servlet policy in this release.

## Mandatory business snapshot compare-and-swap (protocol 2)

The certification guard protects overlapping transactions. It does not by itself
protect a form read before another user committed. Every atomic data read now
returns `dataRevision=MIGRATION_UUID:NUMBER`; the canonical guard stores a separate
`DATA_REVISION` which advances only for business mutations. All business mutations
must supply the exact original read snapshot as `expectedDataRevision`. Missing
is 428, malformed is 400, and a stale snapshot is 409. No receipt or business row
commits after rejection. Only `/logOff` and `/currencySwitch` are session/preference
operations exempt from the business snapshot requirement. Asset, transaction,
transfer, inflation, refresh-write and account deletion paths are included.

The browser binds each row and open form to its original read, not a mutable
"latest token". A fresh response cannot relabel an old row. Platform selections
must come from the same snapshot as the form. After a successful change the
browser invalidates financial and inflation caches. A stale form is closed and
fresh data is fetched; no request is automatically resubmitted with a newer
snapshot. Another user changing unrelated data can conservatively cause a
conflict. This is a deliberate global revision rather than a false promise of
row-level merge semantics.

Receipt lookup for an existing UUID and owner/payload occurs before snapshot
validation, preserving historical replay. The historical request HMAC and its
private key are unchanged. A confirmed cached result retains its original data
revision; result queries do not bless old financial data with a current token.
New operations bind the expected snapshot in their payload HMAC. A transaction
which lost certification and is absent may still fail stale-snapshot validation
on retry: reload, review, and submit a new explicit intent. Do not change a
pending payload or invent a new UUID while its outcome remains unknown.

`ops/cluster/migrate_finance_revision.py` provides separate lab-only manual
`prepare-private`, `preflight`, `canonical`, and `switch` phases. It is not an
all-purpose cleanup/startup script and runs no service stop/start. Keep entry,
Tomcat and workers stopped, retain off-server recovery evidence, and confirm both
normal GR writers. Preflight compares the real application-status contract
(mysqlReady, dual mode, both online IDs and matching UUID/view/epoch/membership)
with a complete stable authority permit; it does not confuse the private RPC
snapshot fields with HTTP status or depend on /ready. Actual SQL verifies the
reviewed 512-KiB group fragments, allocator IDs/step, durability and verified TLS.
Each metadata operation rechecks the same permit, and completion requires
/confirm acknowledgment that all executed GTIDs durably applied on both members. One node performs the logged GR metadata migration; both nodes
verify apply before their private config switch. The old writer accounts are
locked, new TLS-required accounts receive the same exact table DML privileges,
and each canonical marker gets one shared new migration UUID and protocol 2.
Business, identity-allocation and historical receipt projections are compared
before/after. Config switching preserves the historical HMAC line verbatim.
Old processes must be stopped and credentials fenced: merely changing a marker
cannot revoke an already running old transaction. New runtime verifies the marker
and required columns on every transaction and fails closed on protocol 1.

## Deleted-account outcome after JVM/session loss

A new authorized `/deleteUser` requires its existing authenticated cookie and
commits the endpoint, a domain-separated HMAC digest and a seven-day UTC expiry
in the same receipt transaction as deletion. The HMAC binds the original base
session ID, operation UUID and immutable owner generation, using the existing
private idempotency key. Only the digest is stored, never the session cookie or
capability plaintext. Known `.n1`/`.n2` routes do not change the base capability.

`POST /operationStatus` may resolve that one COMMITTED deletion using the original
HttpOnly cookie plus original UUID even when both Tomcat JVMs restarted and no
HttpSession survives. It checks endpoint, expiry, digest and absence of the old
user generation on both logical schemas; only `accountDeleted=true` and operation
state are returned, with current durability confirmation. It never restores an
identity or returns a saved financial payload. A new sign-in rotates the session
ID and forgets any in-session deletion proof. Wrong cookie, operation, owner,
endpoint or expired capability cannot obtain the narrow result. Ordinary API
identity and generation checks remain mandatory. A server-session proof is also
retained for legacy receipts when available; it does not provide restart survival.

Deleting/clearing the original cookie or waiting past seven days removes this
self-service recovery path; use an authorized administrative receipt lookup.
Cookie loss, full public-IP loss and provider side effects must not be presented
as transparently solved by in-memory session replication.

## Durable sign-in attempts across JVMs

Atomic sign-in uses `X-IAFM-Operation-Id` and canonical SQL tables from
`sql/finance-atomic-login-schema.sql`. Apply this separate migration before
startup. Only HMAC(IP), HMAC(normalized email), HMAC(email/password payload), UUID,
HTTP result and an optional immutable user generation are persisted. No raw IP,
email, password or password-derived verifier is written to these tables.

A shared budget allows 20 new attempts per client in five minutes; status checks
have a separate 20-per-minute shared budget. SQL database time sets the windows.
Every decision, including failed authentication (401), budget denial (429), and
status absence, is committed under the same certification guard and confirmed
through the controller before it is returned. Expected authentication failure is
a result, not an exception that would roll back its counter. Counter tables have
a bounded active-client count. Immutable attempt receipts are not automatically
expired: an uncertain retry must remain resolvable after either JVM restarts.

Same UUID and payload replays a failed terminal result without counting again.
A prior success never grants a new session by itself: replay verifies the CURRENT
password and original user ID/generation. A changed/recycled identity or rotated
credential fails; a confirmed status response grants no session. Email/payload
mismatch returns an unresolved conflict, never a replacement execution.

`POST /loginStatus` accepts only operation UUID and email, checks the email HMAC,
and returns terminal/absent status and the original HTTP code, with no user,
credential or session information. Origin validation and the shared status
budget remain active. This endpoint is usable before a session exists, while
public registration remains forbidden by the servlet security constraint.

The browser stores only the pending UUID and email in localStorage, using a
cross-tab Web Lock; it never persists the password or a derived verifier. After
an unknown result it queries status with durability evidence before reusing the
ID or replacing a confirmed old intent. Bare 401/409/429, missing confirmation,
storage failure and unknown status cannot silently erase the pending operation.
A known failed attempt is shown once before a later explicit submission creates
a new ID. Sessions are created only after successful commit/confirmation.

Runtime permissions for canonical LOGIN_ATTEMPTS are SELECT/INSERT only; both
LOGIN_LIMITS and LOGIN_STATUS_LIMITS need SELECT/INSERT/UPDATE/DELETE. The logical
peer needs no login receipt/budget tables. Preserve the historical canonical
idempotency secret, which also keys these HMAC domains. Existing legacy local
throttling is retained only inside the isolated legacy path.

## Private permit and durability confirmation

All private Java control/health HTTP calls explicitly use Proxy.NO_PROXY, and
Python maintenance uses ProxyHandler({}) plus NoRedirect. Exact configured
loopback authority URLs remain mandatory; environment/system proxy settings
cannot receive the bearer token. The production Proof has seven mandatory fields,
including Controller-Boot-ID, with no six-field compatibility fallback. Confirmation
requires204, matching durability and all seven captured proof fields. Missing,
changed, duplicated or swapped confirmation headers leave the result unknown;
they never manufacture acknowledgment or abort an already committed operation.


Atomic mode does not accept the old UUID-only authority interface. It requires:

1. Authenticated `GET /authority` -> 204 with MySQL UUID, view ID, epoch, mode,
   membership digest and opaque permit headers.
2. The entire permit snapshot must remain identical at the pre-commit check.
   A permit is stable for the same physical boot/incarnation, epoch and view;
   it changes when any of those authority conditions changes.
3. The session uses `BEFORE_AND_AFTER`. Its local instance UUID, binary logging,
   GTID mode, `sync_binlog=1`, `innodb_flush_log_at_trx_commit=1` and doublewrite
   are verified. A successful commit alone is not yet an acknowledged operation.
4. On the same connection, read the committed global GTID set, then authenticated
   `POST /confirm` with `{permit,gtidSet,operationId}`. The controller must prove
   durable application on the authorized member(s), with the SAME epoch/view and
   boot identities, before returning 204 and the matching durability mode.
5. A timeout, changed permit, lost connection, failed confirmation or commit
   exception is an unknown result. Query/replay the original ID; never write an
   ABORTED receipt merely because confirmation failed.

Retries and status queries also take a new permit, update the guard, commit and
confirm before returning a result. Single-member confirmation adds
`durability=single`, `replicationMode=single`, `degraded=true` and matching response
headers. The public health strip visibly reports single-copy operation. No
two-copy claim is made during a fenced single-member period.

The controller must verify durability settings on BOTH physical members, not just
GTID presence. A GTID list alone is not proof that disk flushing or fencing is
correct. Database authentication, old-writer credential revocation and root-owned
restart-safe fencing remain separate deployment requirements.

## Replicated HTTP session compatibility

The WAR is distributable. `Model.User` uses a serializable `SessionIdentity`
proxy containing only ID/generation, display identity, currency preference and
String[] preferences. Financial derived objects and row caches never enter the
wire representation. Deserialization constructs a fresh User so all required
helper objects are initialized, and rejects invalid generations/preferences.
This is session transport compatibility, not a new authentication decision:
normal MySQL generation/ownership validation still runs on each data request.

`SessionReplicationTouch` explicitly sets the current User attribute after the
request, allowing Tomcat DeltaManager to see in-place currency changes. It calls
only getSession(false), takes the session lock, reads the current attribute rather
than a retained old User, and never recreates an invalidated/absent session. A
concurrent newer login wins; logout/revocation is not undone. Deployment must
configure authenticated private two-node Tomcat replication and test actual
login, preference, logout/revocation and entry failover. Serialization unit tests
do not prove session replication or durable storage. Any restrictive Tomcat
session value allowlist must permit `Model.User$SessionIdentity`, `Model.User`,
String and String[].

## Independent runtime recovery health

The private recovery endpoint checks actual runtime JDBC access on each call,
not only the cached initialized flag. It captures an independent /authority,
opens the checked UTC/consistent connection, reads the exact migration marker,
serialization guard and drained legacy gates, verifies login-table SELECT and
key business columns in both logical schemas, then requires the same authority.
It rolls back its read-only work without updating the common certification
row, committing a user operation, or calling /ready. This avoids health
recursion and multi-primary health probes conflicting as writes. Actual DML
privileges and all application functions still need separate acceptance tests.

## Maintenance migration prerequisites

Stop all old JVMs/importers and recover every old XA branch/decision using verified
copies first. Preserve canonical business rows, operation receipts, identity/ID
evidence and off-server backups. Revoke or lock old writer credentials without
deleting the accounts; a stale legacy JVM must not be able to write with a new
group authority. Create a new dedicated runtime identity with no DDL, FILE,
SESSION_VARIABLES_ADMIN, global-variable or replication-management permission.

Preserve the existing complete `IAFM` business data as logical schema 1. Do not
copy it in full into logical schema 2: that would duplicate financial records.
Create logical schema 2 with equivalent restrictive schema definitions, empty
financial/shared tables and only these seeds:

- `USERS`: ID, GENERATION, FORENAME, SURNAME, EMAIL_ADDRESS, with
  PASSWORD=`!identity-shadow!`; no password hash or API credential copying.
- `INFLATION_SERIES_LOCK`: singleton ID 1.
- `ID_ALLOCATION_STATE`: six supported table-name rows, initial high-water 0.
- `CLUSTER_GATE`: ID 1 with NULL operation; other legacy cluster tables empty.

All other ID recorder/recovery-range rows start empty on the peer. Atomic startup
repairs ID metadata from those actual shadows while holding the common guard.
Keep ALL original canonical high-water and recycle evidence. The existing
`IdentityShadowMigration` explains shadow fields, but its old two-connection
maintenance procedure is not an online migration or replacement for this plan.

Rebuild and verify foreign keys explicitly; `CREATE TABLE ... LIKE` does not copy
FK definitions. Remove cascading actions only after isolated deletion/rollback
tests and row/key comparisons. Do not disable foreign-key checks. Runtime checks
both logical schemas for transactional engines, complete ID/generation structure
and prohibited cascading actions.

Apply `sql/finance-atomic-schema.sql` and `sql/finance-atomic-login-schema.sql`
to the canonical schema using a maintenance
identity. Insert exactly one `CLUSTER_ATOMIC_CONFIG` marker with protocol version
2, a new shared migration UUID, and the exact primary/peer schema names. Preserve
the guard version; never reset it during deploy or rejoin. Runtime refuses missing
or mismatched markers, a non-empty old file store, a held legacy gate, nonterminal
legacy operations or branches, incomplete schemas and unsafe durability settings.

Private runtime properties: `transaction.backend=atomic`, `atomic.migrationId`,
the existing `node1.*`/`node2.*`, `objectStore.absolutePath` naming the empty old
transaction store, existing `idempotency.secret`/`ingress.token`, and
`atomic.authority.url`/`atomic.authority.token`. The latter can use the established
IAFM authority URL and health-token environment/system settings. No secret value
belongs in the repository, command arguments, ordinary documents or test logs.
Keep `IAFM_CLUSTER_ENABLED=true`, `IAFM_SCHEMA_AUTO_INIT=false` and the required
application health gate enabled.

Minimum runtime cluster-table permissions: CONFIG SELECT; GUARD SELECT/UPDATE;
OPERATIONS SELECT/INSERT; RESULTS SELECT/INSERT/DELETE; old coordinator/gate/
branch/receipt tables SELECT only. Business/ID DML remains necessary in both
logical schemas. Test exact grants before production.

## Real multi-JVM validation worker

`DatabaseService.AtomicFinanceValidationWorker` is compiled into test-classes,
never the deployed WAR. It accepts only `finance_atomic_validation_*` schemas on
loopback MySQL 26.10 and owner-private configuration via
`IAFM_ATOMIC_VALIDATION_CONFIG`. It uses the real permit client and production
connection verification. It creates/drops no databases and prints no secrets.

Prepare synthetic TEST_BALANCE(ID INT PK, AMOUNT INT) with (1,0) in both logical
schemas, existing finance cluster tables and the atomic guard in the canonical
schema. The worker exercises the transaction core; complete startup/migration/ID
checks must additionally be tested on restored application schemas/Tomcat.

For sign-in validation, provision exact synthetic USERS identity
`atomic-validation@finance.invalid` in both fixtures (current encoded password
in canonical, shadow sentinel in peer), the three login tables in canonical, and
owner-private `validation.email` / `validation.password` properties. The worker
never provisions users or prints those values. It verifies an explicit UTC SQL
session against the JVM clock, and checks every newly created operation/login
receipt timestamp is within 60 seconds of the current UTC test window. Use `--login UUID valid` or
`--login UUID invalid` and `--login-status UUID`. Each invocation is a separate
JVM and follows the real SQL/permit/confirmation path. Assert the returned HTTP
code and sessionEligible flag: a terminal 401/429 is a successful validation
command, not a granted session. Controlled credential rotation must be a separate
maintenance action in synthetic fixtures only.

Read a token with `--inspect QUERY_UUID`. Run `--run OPERATION_UUID AMOUNT EXPECTED_DATA_REVISION` (1..1000), or `--status OPERATION_UUID`, using
the built classpath plus the current Connector/J and Servlet API. To pause before
commit or after commit but before confirmation, append `before-commit` or
`before-confirm` after the snapshot token (also accepted by `--login`) and provide a fresh private absolute directory in
`IAFM_ATOMIC_VALIDATION_BARRIER`. The worker creates `entered`, prints READY with
its PID, then waits up to 90 seconds for `release`. A controller can kill that
exact test JVM/MySQL and verify replay from another JVM/member. Use synthetic
schemas only and preserve all failed/uncertain test evidence until reconciled.

Required acceptance includes concurrent distinct operations, concurrent same-ID
replay, stale identity/payload conflict, JVM loss before/after commit, failure of
either MySQL, one-way/bidirectional partitions, delayed/replayed controller
messages, controller/machine restarts, fenced single-copy writes and controlled
resynchronization. The local H2 tests exercise wrapper/atomic semantics only.
They do not certify MySQL multi-primary behavior or fencing.

Official references: [Group Replication limitations](https://dev.mysql.com/doc/refman/26.10/en/group-replication-limitations.html),
[certification/write sets](https://dev.mysql.com/doc/refman/26.10/en/group-replication-summary.html),
[catalog selection](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-jdbc-url-format.html),
[CREATE TABLE LIKE](https://dev.mysql.com/doc/refman/26.10/en/create-table-like.html).
