# Two-server data replication: migration work in progress

This document is a design and rehearsal record. It does not describe the
current production deployment and does not authorize enabling replication or
promoting a surviving server. The legacy source uses the two-shard coordinator described in
`CLUSTER_OPERATIONS.md`; historical deployment evidence is not current acceptance.
See `CURRENT_VALIDATION_2026-11-11.txt`.

## Data model and rehearsal

Finance identities include the original logical shard and generation; two rows
on different shards may share an integer ID. Star_Hub also routes file bodies by
logical shard. Do not merge tables or silently reassign these identities.

A possible layout retains the original authoritative databases and puts the
peer's three databases alongside them with `_peer` suffixes. Each physical
server must then contain both sets and synchronously replicate changes. In an
isolated, network-disabled MySQL 8.0.46, stopped-writer backups from both servers
were restored together under six distinct schema names. All 71 restored table
checksums matched their respective original snapshots. This was a rehearsal;
it did not change production or prove a complete application recovery.
The same six-schema restore was later run into an isolated two-host replication
group: all 71 table checksums matched the captured originals on each member.
An initial run with the default replication cache exhausted the scratch member's
512 MiB memory limit. A second run passed with a 128 MiB message cache and one
replication worker. This is evidence for the captured dataset, not a capacity
guarantee for concurrent production load. A separate isolated cross-host test
subsequently uploaded a 12 MiB random file, read it back, and compared its
SHA-256 digest on both members using a 700 MiB per-member process limit; the
second member's recorded peak was 664,862,720 bytes. The previous 512 MiB
test exhausted its process limit, and a 15-second XA socket timeout proved too
short on the workstation-relayed test network. These observations do not prove
that simultaneous live Tomcat and database peak loads fit without pressure.

The finance coordinator has an optional JDBC-backed transaction decision
store, disabled by default. In a disposable two-member MySQL group on one
physical host, a test killed the finance JVM after one XA branch committed,
then killed its MySQL primary. After the failed test member was fenced and the
survivor was explicitly reconfigured, a fresh JVM using a different local
identity directory recovered the replicated decision, finished the second
branch and replayed the operation without a second update. Both test members
matched after rejoining.

The finance crash/recovery scenario was subsequently rehearsed with one
disposable MySQL member on each physical server and encrypted temporary
loopback forwards. After the first participant committed, the test terminated
only the scratch primary, disconnected its test transports, confirmed the
survivor did not acknowledge a write before fencing, and waited for the group
to report the peer unreachable. The survivor was then forced to a one-member
view, recovered the transaction decision and second branch, and replayed the
operation once. The original member rejoined with matching balances. A reverse
scratch-member failure and rejoin also replicated a new test write. These tests
do not establish whole-server power-loss, cloud fencing, public DNS switching,
or production-traffic behavior. A separate cross-host rehearsal also recovered
an interrupted Star_Hub upload on the fenced survivor and verified its file
bytes; its capacity and node-health probes were simulated, so it does not
establish real filesystem-boundary enforcement or complete application failover.

A later isolated two-host rehearsal used the actual reserve observers and
local Tomcat processes with authenticated test observers connected to separate
scratch MySQL members. A synthetic Star_Hub file upload and idempotent retry
passed while both physical capacity checks were active. Disconnecting the
scratch replication transport blocked an attempted database write; after the
other scratch MySQL process was independently stopped, the survivor refused
authority without a valid view-bound test attestation, accepted a synthetic
upload with that attestation, and replicated both logical shards after the
scratch peer rejoined. The production databases and public routing were not
changed by this test. This exercises real disk reserve observations, not an
actual cloud-instance fence, production data cutover, DNS failover, or
production traffic under load.

The reverse scratch-node loss was also tested after adding MySQL identity
checks: Finance recovered an interrupted two-branch operation exactly once,
Star_Hub saved and read synthetic files using the authenticated observer, and
UCost preserved its synthetic operation and accepted a survivor-side update.
Both scratch database members matched after rejoining. The UCost test used the
real authority and database identity checks, while its full application-health
gate was disabled for the isolated harness; public ingress and cloud-instance
fencing were not tested.

In a subsequent application-level rehearsal, all four packaged applications
were started in a localhost-only Tomcat against both restored scratch members.
The three stateful applications became healthy with the real authenticated
observer; an isolated partition made them unavailable for new writes while the
portfolio remained accessible. After the other scratch MySQL process was
stopped and the survivor was explicitly fenced, all applications recovered;
a synthetic UCost account and idempotent save remained readable, and matched
after the scratch peer rejoined. The app container ran on the workstation,
and the test did not switch production routing or stop a cloud instance.

The optional backend requires `objectStore.backend=jdbc` and private
`objectStore.jdbc.url`, `.user` and `.password` entries. The loopback endpoint
must be the authorized writer of a properly configured replicated database;
the application does not establish or fence replication itself. An operator
must provision `ops/cluster/finance-jdbc-decision-store.sql` in that database.
Runtime verifies its InnoDB table and primary key, and never creates or drops it.
Switching from the file backend is refused while unexpected files remain in the
original transaction-store directory. That conservative check is not a
substitute for draining, recovering and verifying the old store before migration.
Preserve the existing `identity` marker and configure global fencing before
attempting any failover.

`ops/cluster/prepare_peer_restore.py` prepares the peer dump for isolated
rehearsal by changing database declarations only. It refuses an existing output
file, unexpected database names, cross-schema foreign keys, several stored-object
forms, GTID changes and database drops. It is not a general-purpose SQL parser.
Before any real migration, separately inspect schema objects and external
references, compare data and schema definitions, and take a new coordinated
backup with stopped writers. Never import rehearsal SQL into production as an
unreviewed shortcut.

## Requirements before production

- Prove both the business data and every distributed transaction decision are
  durably available after either physical host is lost. Current Narayana
  decision stores are local files, and a local process lock does not fence a
  coordinator on another host. Database replication alone does not solve this.
- Provision the health observer's two narrow Performance Schema `SELECT`
  privileges on both members, and reconcile its private credentials when MySQL
  account tables are replicated. The existing independent installations use
  different passwords; blindly copying one account database would break the
  other node's observer.
- Configure replication so a successful healthy two-node write is durably
  present on both nodes; confirm it never silently falls back to asynchronous
  mode. Check cross-node reads, application transaction gates, identity reuse,
  Star_Hub's file bytes, capacity reservations, sessions and retries.
- Test membership changes during an in-flight commit. `AFTER` applies to the
  members in the active group; a planned or graceful departure can leave a
  one-member view. The application now checks the authorized view before
  acknowledging a completed operation, but that check is not itself a database
  fencing primitive. Verify every writer, background task and maintenance path,
  and demonstrate the transition under load before asserting a two-copy
  acknowledgement guarantee. An unacknowledged commit remains an unknown
  outcome and must be resolved by operation identity rather than assumed lost.
- Finance startup repairs ID metadata and its background scanner can complete
  XA branches. When the decision store uses JDBC, both now require the local
  authenticated `/authority` check, which verifies the replication view
  without depending on application recovery. Configure the private
  `IAFM_CLUSTER_AUTHORITY_URL=http://127.0.0.1:9411/authority` and existing
  shared health token on either active coordinator. Missing, passive, stale,
  unauthorized, or incorrectly routed authority fails closed. This check is
  not proof of a cloud fence and does not replace database-level fencing.
  The observer supplies its authorized MySQL server UUID; Finance requires
  the two distinct logical schemas and JDBC decision store to use one private
  loopback MySQL instance and verifies that each connection reports that UUID.
  With replicated mode enabled, startup verifies and repairs ID metadata in
  both logical shards before opening requests. Star_Hub also verifies that its
  account, file and decision-store connections target the authorized server;
  UCost verifies each database connection in its explicit replicated mode.
  The connection checks also require session binary logging to be on and
  session consistency to be `AFTER` or `BEFORE_AND_AFTER`. Do not grant
  application accounts `SESSION_VARIABLES_ADMIN`, which can permit disabling
  binary logging; verify the least-privilege setup before deployment.
  These application checks do not establish the safety of a production
  migration, database topology or cloud fencing by themselves.
- Two members cannot form a majority after an unplanned peer loss. Freeze shared
  writes, verify the former peer is actually fenced out using an independent
  method, and only then apply a documented manual recovery procedure. Network
  timeout alone is not fencing. Keep the old node out of service until a
  controlled resynchronization has completed.
- A surviving single node cannot provide two physical copies of new writes.
  Make this degraded condition visible, preserve backups independently, and
  keep enforcing the physical disk reserve and per-account quota.
- Test both directions of whole-node failure, network partition, in-flight XA
  decisions, uncertain client replies, concurrent writes, restarts, and peer
  rejoin. Verify public routing and certificate renewal independently.

The present MySQL servers have GTID disabled and both have `server_id=1`.
Starting Group Replication or copying one live schema over another without a
controlled migration would be unsafe. Do not make these changes until the
application, fencing, recovery and rollback paths are ready together.
