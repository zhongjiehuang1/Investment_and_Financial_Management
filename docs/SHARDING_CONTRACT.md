# Finance data placement and transaction contract

Node numbers are public logical identifiers. Connection addresses, credentials, coordinator identifiers and regions belong only in private deployment configuration.

## Placement and identity

- Canonical user identity and password verification live on node 1. Node 2 holds only a `USERS` foreign-key shadow with the same numeric user ID and immutable generation. The shadow password is disabled; it cannot authenticate a user. `BASIC_VARIABLES_AND_DATA` and shared inflation/reference series remain canonical on node 1 and are never copied into identity shadows.
- New financial accounts and new external transaction records are inserted on the ingress node. An internal transfer creates its two transaction rows, journal and journal entries together on the ingress node. An account balance is updated on the node owning that account, including when its transaction record is on the other node.
- A row identity is `(databaseId, table, id, generation)`, owned by canonical `(userDatabaseId=1, userId, userGeneration)`. Generation never changes during an edit and changes when a recycled numeric ID is assigned again. The API returns `databaseId` and `recordKey=node:id:generation`. Existing requests carrying `id` and `generation` are routed only if exactly one matching owned generation exists across both nodes.
- Account names are checked for uniqueness across both nodes under the canonical owner lock. Renames update transaction and journal references on both nodes. Deleting an account processes every related journal/record and reverses surviving transfer accounts, distinguishing equal local IDs on different nodes.
- User deletion removes all business children and both identity rows in one global transaction. Child IDs return only to their owning node's allocator. The user ID is recycled only by node 1, after the distributed deletion succeeds. A secondary shadow must never allocate or independently recycle a user ID.

## Coordinator interface

`ShardScope.begin(Map<Integer, Connection> enlisted, int ingressNode)` requires exactly two distinct connections already enlisted in the external XA transaction. The scope exposes `connection(node)`, `connections()`, `isRollbackOnly()` and `markRollbackOnly()`. DAO calls to `commit()` and `close()` do not finish the physical transaction. `rollback()` marks the entire scope rollback-only. Scope close removes its thread-local binding and releases its exchange-rate snapshot without ending the database transaction. The coordinator must throw/abort if `isRollbackOnly()` is true, and must be the only owner of XA prepare, commit, rollback and connection closure.

Before business access, the scope locks the canonical user row first and its matching identity shadow second. Read scopes hold the same locks until their final outcome. The coordinator's durable global gate excludes all reads and mutations during an unresolved operation; this is necessary because two-phase commit does not by itself prevent another reader from observing one branch before the other branch commits. A failed participant, missing shadow, conflicting generation, unresolved decision or missing receipt keeps admission closed. No caller may silently fall back to one node. Cluster-enabled access without a scope is rejected; the legacy background inflation cache is disabled in this mode.

Mutation operation IDs, durable request hashes/results, branch receipts, coordinator recovery and the gate are implemented by `FinanceCluster` and `XaTransactions`. Business DAOs do not invent their own recovery decision. Authentication and registration must also run through the outer coordinator; registration inserts the canonical user and invokes `ShardScope.ensureIdentityShadow(userId)` inside that same transaction.

## Reads

Account, platform and transaction pages merge both nodes before applying the global page offset/limit. Two streaming cursors use identical binary UTF-8 text keys, numeric amount keys and a node-qualified final tie break. Memory usage is bounded by the page and current cursor rows. Counts come from both nodes under the same operation/owner gate. Transaction account generations may be on another node; paired transfer journals remain colocated with their rows. Asset, earnings, cashflow and risk summaries sum integer minor units across nodes by currency before a single currency conversion/rounding step.

Each `ShardScope` also pins one immutable exchange-rate snapshot until close. Provider GBP/CNY cross-rates derive from that same captured USD matrix, avoiding a mixed refresh generation from the legacy pair cache. An explicit manual GBP/CNY override is pinned together with the USD snapshot used for other currencies. Source metadata is pinned as well; a background rate refresh becomes visible only to a later scope.

## Existing identity migration

Stop stateful writers on both nodes, preserve database backups, apply the normal schema migration to both databases, and inspect the destination for conflicting independent users. From an unpacked checked WAR, run:

```sh
java -cp 'WEB-INF/classes:WEB-INF/lib/*' DatabaseService.IdentityShadowMigration /etc/iafm/cluster.properties --writers-stopped
```

The private file supplies `node1.url/user/password` and `node2.url/user/password`. This maintenance tool reads canonical identities under locks and atomically writes only the secondary identity metadata. It copies no financial rows, basic variables, journals, or password hashes. Existing unmatched IDs/generations cause an abort rather than a merge. It reports counts only. Verify the resulting IDs/generations and zero business-data copying before allowing coordinated requests. Retain the original canonical user allocation state; do not reseed or renumber users.

## Validation

`ShardedFinanceTest` exercises business routing against two H2 databases by default. `IAFM_SHARD_TEST_CONFIG` can point to a mode-600 properties file with `node1.url`, `node2.url`, `user`, and `password` for two disposable MySQL validation schemas; only loopback URLs whose database names begin `finance_shard_validation_` are accepted, and those schemas' test tables are cleared. The separate coordinator tests must cover durable XA recovery, an interrupted second-phase commit, missing participants and idempotent replay. Passing data-routing tests alone does not establish crash recovery or infrastructure availability.

`ExchangeRateSnapshotTest` refreshes provider and manual rates at request boundaries to verify one report/transfer cannot mix rate generations. `src/test/js/finance-browser-smoke.cjs` runs the actual frontend against synthetic local endpoints with Playwright and Chromium. It checks colliding local IDs across nodes, correct edit/delete identities, metadata-only pending storage, a lost commit response followed by recovery without a second write, and a narrow mobile viewport. Run it with Node and an installed Playwright module; `CHROME_PATH` optionally selects an installed Chromium executable. It never connects to production databases or uses real accounts.
