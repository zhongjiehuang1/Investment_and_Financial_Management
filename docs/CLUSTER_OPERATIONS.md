# Two-node operations

> Historical design/validation record. It is not the current deployment status or authorization to enable writes. See CURRENT_VALIDATION_2026-11-11.txt and the current server operations guide.

The deployment prioritizes consistency. Each application has one active coordinator on node 1. Node 2 serves its local frontend and forwards stateful requests through the authenticated private tunnel. There is no automatic promotion of a second writer. Finance serializes its reads and mutations through one durable gate; this intentionally trades throughput for a simple, auditable recovery boundary.

Finance creates new business rows on the authenticated ingress node and updates each account balance on its owning node. Both databases participate in XA. Read pages and whole financial reports run inside the same gate and use one pinned exchange-rate snapshot. See [SHARDING_CONTRACT.md](SHARDING_CONTRACT.md) for identity, pagination, deletion and ID recycling.

## Private configuration

Set `IAFM_CLUSTER_ENABLED=true`, `IAFM_SERVER_ID=1` or `2`, `IAFM_SCHEMA_AUTO_INIT=false`, and `IAFM_COORDINATOR_MODE=active` only on node 1 (`passive` on node 2). `IAFM_CLUSTER_CONFIG` points to a private properties file containing:

```properties
node1.url=jdbc:mysql://127.0.0.1:3306/IAFM?serverTimezone=UTC
node1.user=PRIVATE_RUNTIME_USER
node1.password=PRIVATE_RUNTIME_PASSWORD
node2.url=jdbc:mysql://127.0.0.1:13306/IAFM?serverTimezone=UTC
node2.user=PRIVATE_RUNTIME_USER
node2.password=PRIVATE_RUNTIME_PASSWORD
objectStore.absolutePath=/var/lib/iafm/transactions
nodeIdentifier=iafm-primary
ingress.token=PRIVATE_STABLE_INGRESS_TOKEN
idempotency.secret=PRIVATE_STABLE_RANDOM_SECRET_AT_LEAST_32_CHARACTERS
```

The idempotency secret is separate from ordinary credentials and must survive redeployment. It authenticates request digests, including requests containing credentials, without storing fast unsalted password hashes. Rotating it requires an explicit plan for retained operations. The runtime forces bounded JDBC connection/read timeouts and separate resource identity for each logical node; a driver must not collapse two loopback tunnel ports into one XA branch.

Set `IAFM_CLUSTER_HEALTH_REQUIRED=true`, `IAFM_CLUSTER_HEALTH_URL=http://127.0.0.1:9411/ready`, and `IAFM_CLUSTER_HEALTH_TOKEN` from the private agent configuration. The private `/internal/recovery-ready` endpoint checks only local recovery and cannot recursively invoke the external readiness check. Both entrances overwrite the ingress node/token headers, and Tomcat/MySQL are private. Public `/api/server` returns only the local logical server number.

## Migration and activation

1. Rehearse the schema migration against isolated copies and compare original rows/fields. Preserve verified database, WAR and private configuration backups. Stop all stateful writers on both nodes.
2. Apply the normal schema maintenance entry and `ops/cluster/finance-schema.sql` to both databases. Run `DatabaseService.IdentityShadowMigration <private.properties> --writers-stopped` to seed identity metadata only.
3. Run `DatabaseService.ClusterStoreProvision <private.properties> --writers-stopped` once using the checked application classes and dependencies. It binds the primary database's coordinator UUID to the durable directory's `identity` file. The directory must remain outside `webapps`, temporary directories and build output. An existing missing/mismatched marker requires restoration, never silent reinitialization.
4. Retain the same durable directory, node identifier, marker and idempotency secret on every deployment. Install identical WARs on both nodes, apply private configuration, and start the private agents/tunnel and Tomcat. Check recovery before opening stateful traffic.

## Failure and recovery

An unavailable required MySQL, Tomcat, node connection, unknown transaction outcome or unresolved decision closes database access. The frontend displays “服务器故障，请等待恢复”. A request timeout does not imply rollback. Clients retain operation identity metadata and query the committed result before allowing a different mutation. Session changes and logout happen only after a confirmed transaction outcome, including replay. No financial form values or passwords are persisted in the browser's operation metadata.

After an outage, restore the services and the original durable store, let Narayana reconcile decisions and participant receipts, then verify readiness. Never manually delete XA branches, operation rows, receipts, gates or coordinator files to make a health check green. A shutdown must drain requests and recovery work before releasing its process lock. Backups of runtime state must include the database and durable transaction store from a compatible stopped/checkpointed state.

Portfolio static content is served independently on both nodes. The remaining frontends can also remain readable during a database/application outage. Two DNS address records alone do not provide a guaranteed client failover time; an external health-aware entry point is needed for that guarantee. Durable database and coordinator media must survive the outage. Permanent disk loss or arbitrary administrator writes require recovery procedures beyond transaction rollback.

## Validation tools

`FinanceClusterTest` covers two-resource commit, durable replay, mismatched payload rejection, whole-operation rollback and gate release. Its optional `finance.test.config` accepts only disposable loopback MySQL validation schemas. `FinanceClusterCrashHarness` runs in separate JVMs: it halts after the first participant commits, then verifies recovery of both physical databases and exactly-once replay. It is never part of the deployed request flow. Sharding, identity reuse, concurrency, global aggregation and exchange-rate snapshot tests complement these recovery checks.
