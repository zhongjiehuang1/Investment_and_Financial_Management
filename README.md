# Investment and Financial Management

Java 21 / Jakarta Servlet 6.1 / Tomcat 11 personal finance application. The original Controller / Model / DatabaseService layout and String-based monetary interfaces are retained. Money calculations use exact minor units; exchange rates are handled separately at high precision.

## Build and test

Use Java 21 and Maven:

```sh
mvn clean verify
python3 -m unittest discover -s src/test/python
```

The deployable is `target/Investment_and_Financial_Management.war`. The build uses MySQL Connector/J 26.10.0, which supports MySQL Server 8.4 and later. Current MySQL 26.10.0 requires a fresh isolated integration run; historical MySQL 8 tests are not release acceptance. Normal tests use isolated H2 fixtures. To exercise actual MySQL, start a disposable loopback MySQL instance and explicitly provide the test-only connection properties documented in `IdRecoveryTest`, `CascadeDeletionTest`, and `RequestIdentityIntegrationTest`. Never point destructive fixture tests at a production schema. `IdRecoveryTest` includes two independent Java process workers when MySQL is selected. The optional Python inflation test uses `IAFM_INFLATION_TEST_MYSQL_PORT` and a disposable loopback schema.

## Runtime configuration

Set these outside the repository and deployment archive:

- `IAFM_DB_URL`, `IAFM_DB_USER`, `IAFM_DB_PASSWORD`, or `IAFM_DB_JNDI` for a configured DataSource.
- `IAFM_SCHEMA_AUTO_INIT=false` in production; apply schema changes with a maintenance identity before startup. The runtime identity needs SELECT, INSERT, UPDATE and DELETE on this application's schema only.
- `IAFM_ADMIN_USER_IDS` as comma-separated `id:generation` bindings. `generation` is the immutable UUID in `USERS.GENERATION`.
- Optional read-only `IAFM_API_KEYS` as semicolon-separated `id:generation:sha256hex` bindings. Generate a key with `scripts/export_user_data.py`; never publish raw keys.
- `IAFM_EXCHANGE_RATE_API_KEY` or the existing `iafm/exchangeRateApiKey` context Environment entry. Preserve the provider's daily cache across deployments.

Existing numeric-only administrator/API bindings deliberately fail closed. During a controlled migration, bind each existing configured user to its current generation after backfill. Do not automatically bind a recycled ID to a new person.

## Two-node deployment

The new replicated implementation uses `transaction.backend=atomic`: both logical schemas, operation identity and result share one local InnoDB transaction, with a common certification guard and strict controller permit/GTID confirmation. It never starts the old cross-JVM XA recovery path. Both physical members can execute the same path. See [atomic transaction protocol and migration](docs/ATOMIC_REPLICATED_TRANSACTIONS.md). Production writes remain closed pending real two-host failure and migration acceptance; local unit tests alone do not authorize deployment. Legacy XA is now confined to isolated rehearsal schemas, and the former JDBC/XA replicated backend is refused.

Current status (2026-11-11): the following describes the legacy single-coordinator design, not a validated active-active deployment. Both production applications must remain closed to writes until the new safety requirements and two-host acceptance tests pass. See `docs/CURRENT_VALIDATION_2026-11-11.txt`.

The historical two-node implementation used one active coordinator, durable XA recovery, immutable generation-qualified identities and operation replay protection. New financial rows remained on the logical ingress shard, while reads merged both databases under its transaction gate. Its old deployment/observer instructions are historical context: [cluster operations](docs/CLUSTER_OPERATIONS.md), [sharding contract](docs/SHARDING_CONTRACT.md), and [private node agent](ops/cluster/NODE_AGENT.md). They do not authorize enabling the old XA design on production replicas. Use the atomic protocol above for the new equal-member implementation.

## Maintenance and migration

Stop incompatible application and import writers before migration. Take a full database backup and a copy of the current WAR and private Tomcat configuration. Restore the database backup into a separate database and verify original table counts, field hashes, associations and journal balance before applying changes to production.

Run the existing `DatabaseService.CreateAllTables` main entry with Java 21, the application classes and dependencies, the Servlet API, and the maintenance database configuration. With an exploded WAR and a Tomcat installation the command is:

```sh
java -cp 'WEB-INF/classes:WEB-INF/lib/*:/opt/tomcat/lib/servlet-api.jar' DatabaseService.CreateAllTables
```

Use the actual Servlet API path on the target host. MySQL DDL commits implicitly: a failed schema migration requires inspecting its state and either completing the idempotent migration or restoring the validated backup while writers remain stopped. Changing `IAFM_DB_URL` selects the isolated rehearsal database. Do not run this entry against production until rehearsal succeeds.

Replicated startup inspects all tables in each logical schema and refuses cascading foreign-key actions. New ledger schemas use non-cascading foreign keys and the existing explicit child-first deletion code. `CREATE TABLE IF NOT EXISTS` does not change existing constraints: inspect, rehearse and validate a separate maintenance migration of every existing cascading constraint before replicated startup. This metadata check does not establish distributed lock, fencing or transaction safety.

The migration adds immutable record generations, explicit ID coordination/high-water metadata, and compact recovery ranges for large historical gaps. Existing recorder tables retain explicit recycled IDs. `SetIds` reconciles every recyclable table on each startup; startup fails if consistency cannot be established. Allocation, insertion, deletion and recycling share the business transaction. Small recoverable gaps are restored to recorder tables; large gaps are represented as ranges without loading or inserting billions of integers.

Account deletion includes its related transactions and complete paired transfers, reverses the surviving counterpart's balance, removes ledger children explicitly and recycles all released integer IDs. Ambiguous historical transfers or corrupt ownership references to require evidence-based repair; they are never silently guessed or discarded. `POST /deleteUser` removes only the authenticated user's data and invalidates that session.

## HTTP identity contract

Successful login/read responses expose `userGeneration`. Mutation requests send it in `X-IAFM-User-Generation`. List rows expose `generation`; edits/deletes submit the captured value. Income/expense requests include `platformGeneration`; transfers include `fromPlatformGeneration` and `toPlatformGeneration`. Selectors expose account generations. Missing/stale identities fail closed, so old pages cannot change a replacement row that reuses an integer ID. The UI and bank importers preserve the identities captured when data were selected.

Keep HTTPS, same-origin checks, Secure/HttpOnly cookies and the existing ten-minute session timeout. The reverse proxy must overwrite Host and forwarding headers; only loopback forwarding is trusted. Public registration remains controlled by the existing servlet policy. Lists default to 100 rows per page and enforce a server-side limit.

## Private data

The source package excludes credentials, bank statements, private transaction payloads, checkpoints, database backups and compiled output. Importers read owner-supplied private input; see `src/main/Python/PRIVACY.md`. Existing migration scripts default to dry-run and require a private backup before writes. Inflation provenance and input boundaries are documented in `tools/inflation/README.md`.
