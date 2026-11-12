# Physical membership and database safety

This file describes development work, not an enabled production topology. The
historical logical-shard descriptions below do not describe an accepted current
replicated topology. The user reported two independent full data copies with matching
checksums; matching copies are not live replication. Recheck the actual hosts and
retain all user data. This task permits only the two existing physical servers.
Do not use the staged scripts to promote a peer or overwrite either production data directory.

Physical server identity is separate from the two historical logical schema IDs.
Application identity and physical capacity reservations no longer assume server
IDs 1 and 2. An additional server must still be mapped to logical ingress shard
1 or 2 until the application data model itself changes. A coordinated maintenance
window, compatible schemas, durable decision stores, distinct MySQL identity,
private peer connectivity and tested restore are prerequisites for any change.

The experimental MySQL Group Replication observer supports a configured list of
2–9 members. The upper bound is a MySQL Group Replication limitation, not an
application numbering convention. Every member configuration must have the same
group ID and identical complete UUID-to-physical-ID mapping. On the active writer
the observer checks the current group view, configured majority, member identity,
fresh peer observations, matching membership digest, disk reserve, and the three
application recovery gates. A minority is never authorized by a timeout: a
root-owned, expiring proof must name every excluded member and the exact group
view after an operator independently fences them. That operator remains responsible
for confirming cloud-level fencing before forcing a view.

Example structure only; replace every placeholder, provision private endpoints,
and validate the complete configuration before considering an isolated test:

```json
{
  "groupId": "GROUP-UUID",
  "localMemberUuid": "MEMBER-A-UUID",
  "memberUuids": ["MEMBER-A-UUID", "MEMBER-B-UUID", "MEMBER-C-UUID"],
  "memberNodeIds": {"MEMBER-A-UUID": 1, "MEMBER-B-UUID": 2, "MEMBER-C-UUID": 10}
}
```

`peerStatusUrls` also lists every nonlocal physical member, each through an
authenticated, restricted private connection. The staged MySQL generator requires
a stable explicit SQL/group port mapping for all non-default configurations.
MySQL/Tomcat/reserve permissions, replica recovery credentials, TLS, health
observers, DNS and certificate renewal require separate, verified provisioning.
`storage.physicalNodeIds` must exactly match the observer's configured physical
identities. The Star_Hub physical-reservation migration and its new mode are not
enabled in current production. Its two logical data schemas remain independent
from the number of physical replicas.

Changing membership is not a rolling configuration edit. Stop all writers and
external ingress, drain and inspect XA, preserve a recent verified backup, verify
that no prepared or uncertain transaction is left, and coordinate one membership
change across every affected node. A joining replica must be fully seeded and
have sufficient protected storage before entering the writable view. Complete and
test a safe rejoin/cutover controller before considering production use. Mere DNS
changes, a health timeout, or a stale configuration file do not grant authority.

To scale beyond one MySQL replication group's hard limit while retaining full data
copies, equal eligibility and safe writes, a different data-plane architecture is
required and has not been implemented or validated. For example, switching to
Galera-based systems is not a drop-in solution: those systems do not support the
external XA transactions currently used by finance and file storage. Plain MySQL
asynchronous replication can lag, and semi-synchronous replication can fall back
to asynchronous mode on timeout; neither is automatically an equivalent guarantee.
Any replacement must first redesign those transactions, preserve operation IDs and
recovery decisions, and pass isolated partition, crash, rejoin, restore and
membership-change tests. No finite computer system provides literal unlimited
capacity; avoid imposing an arbitrary application-level server count while
documenting and enforcing the actual data-plane limits.

Reference: <https://dev.mysql.com/doc/refman/8.0/en/group-replication-limitations.html>,
<https://docs.percona.com/percona-xtradb-cluster/9.7/limitation.html>,
<https://dev.mysql.com/doc/mysql-replication-excerpt/8.0/en/replication-options-source.html>.
