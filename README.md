# Investment and Financial Management

Java 21 / Jakarta Servlet 6.1 / Tomcat 11 personal finance application. The original Controller / Model / DatabaseService layout and String-based monetary interfaces are retained. Money calculations use exact minor units; exchange rates are handled separately at high precision.

## Build and test

Use Java 21 and Maven:

```sh
mvn clean verify
python3 -m unittest discover -s src/test/python
```

The build creates `target/Investment_and_Financial_Management.war` and writes Java test results to `target/surefire-reports/`. The Java suite defaults to isolated H2 fixtures. Use a disposable loopback MySQL instance and the test-only connection properties in `IdRecoveryTest`, `CascadeDeletionTest` and `RequestIdentityIntegrationTest` to exercise MySQL Connector/J 26.10.0. The optional Python inflation test uses `IAFM_INFLATION_TEST_MYSQL_PORT` with its own disposable schema.

## Runtime configuration

Set these outside the repository and deployment archive:

- `IAFM_DB_URL`, `IAFM_DB_USER`, `IAFM_DB_PASSWORD`, or `IAFM_DB_JNDI` for a configured DataSource.
- `IAFM_SCHEMA_AUTO_INIT=false` in production; apply schema changes with a maintenance identity before startup. The runtime identity needs SELECT, INSERT, UPDATE and DELETE on this application's schema only.
- `IAFM_ADMIN_USER_IDS` as comma-separated `id:generation` bindings. `generation` is the immutable UUID in `USERS.GENERATION`.
- Optional read-only `IAFM_API_KEYS` as semicolon-separated `id:generation:sha256hex` bindings. Generate a key with `scripts/export_user_data.py`; never publish raw keys.
- Exchange rates default to the official free ECB daily reference feed, even if an old key is still present. `IAFM_EXCHANGE_RATE_PROVIDER=exchangerate-api` explicitly selects the legacy keyed provider, with `IAFM_EXCHANGE_RATE_API_KEY` or the protected `iafm/exchangeRateApiKey` context entry. Never place a provider key in source or logs. Preserve the versioned reference cache and its per-provider attempt budget across deployments. ECB is checked hourly; failed ECB fetches have a 15-minute minimum gate (the current hourly timer normally retries on its next tick). Keyed-provider attempts remain capped at once per 24 hours, including failures. Display the actual source/as-of date; reference values are not live trading prices.

ECB reference data is converted from its EUR base into other bases and pairs; the interface and exported metadata must identify the ECB as the source and explain the conversion and any display rounding. The original data is available free from the ECB. If these data are included in documents sold to others, the [ECB website terms](https://www.ecb.europa.eu/services/using-our-site/disclaimer/html/index.en.html) require informing buyers that the original data is free before payment and whenever they access it.

Existing numeric-only administrator/API bindings deliberately fail closed. During a controlled migration, bind each existing configured user to its current generation after backfill. Do not automatically bind a recycled ID to a new person.

## Current two-host deployment and transactions

Both hosts run the same application and use Server 2's single production MySQL on private port 332. Server 1 connects through a restricted, host-key-verified SSH tunnel and JDBC TLS identity verification. The entry on each host sends requests only to its local application. Retained Tomcat session replication supports existing session behavior but does not provide request or database takeover. If the shared MySQL is unavailable, database operations on both hosts stop; the site must not claim a second live copy or automatic database failover.

`transaction.backend=atomic` keeps the two logical finance schemas, shared transaction guard, identity generations, operation UUID and result in one InnoDB transaction on that one physical database. Concurrent modifications use revision and generation checks. A known rolled-back operation may follow the documented retry policy; for an unknown result retain the original operation ID and query its result before deciding whether to retry. Do not infer rollback from a timeout. Successful writes have single-database durability, not dual-node database confirmation. Each application JVM uses a bounded connection pool of at most four connections and a bounded 64-entry prepared-statement cache; these limits do not guarantee a specific latency under load.

For current operations and rebuild steps see local OneDrive `Linux_Server_2026-11-24/README.md`. The checked-in `ops/single-database` directory contains the reviewed single-database backup, isolated restore and limited maintenance tools; follow their safety prerequisites and current receipts. A successful build or an encrypted backup alone is not proof of recovery.

## Maintenance and migration

Stop incompatible application and import writers before migration. Take a full database backup and a copy of the current WAR and private Tomcat configuration. Restore the database backup into a separate database and verify original table counts, field hashes, associations and journal balance before applying changes to production.

Run the existing `DatabaseService.CreateAllTables` main entry with Java 21, the application classes and dependencies, the Servlet API, and the maintenance database configuration. With an exploded WAR and a Tomcat installation the command is:

```sh
java -cp 'WEB-INF/classes:WEB-INF/lib/*:/opt/tomcat/lib/servlet-api.jar' DatabaseService.CreateAllTables
```

Use the actual Servlet API path on the target host. MySQL DDL commits implicitly: a failed schema migration requires inspecting its state and either completing the idempotent migration or restoring the validated backup while writers remain stopped. Changing `IAFM_DB_URL` selects the isolated rehearsal database. Do not run this entry against production until rehearsal succeeds.

Startup inspects the two logical schemas on the shared database and rejects incompatible cascading foreign-key actions. Ledger schemas use non-cascading foreign keys and explicit child-first deletion. `CREATE TABLE IF NOT EXISTS` does not update existing constraints; rehearse and validate any necessary schema migration before changing production. This metadata check is not a backup or restore test.

The migration adds immutable record generations, explicit ID coordination/high-water metadata, compact recovery ranges for large historical gaps, and a nullable `CLOSED_ON` date on both asset tables. Apply the closing-date migration to both logical schemas before deploying archive-aware application code. Existing recorder tables retain explicit recycled IDs. `SetIds` reconciles every recyclable table on each startup; startup fails if consistency cannot be established. Allocation, insertion, deletion and recycling share the business transaction. Small recoverable gaps are restored to recorder tables; large gaps are represented as ranges without loading or inserting billions of integers.

Account deletion removes related transactions and complete paired transfers, reverses the surviving counterpart's balance, removes ledger children explicitly and recycles released integer IDs. Ambiguous historical transfers and corrupt ownership references require evidence-based repair. `POST /deleteUser` removes the authenticated user's data and invalidates that session.

## HTTP identity contract

Successful login/read responses expose `userGeneration`. Mutation requests send it in `X-IAFM-User-Generation`. List rows expose `generation`; edits/deletes submit the captured value. Income/expense requests include `platformGeneration`; transfers include `fromPlatformGeneration` and `toPlatformGeneration`. Selectors expose account generations. Missing/stale identities fail closed, so old pages cannot change a replacement row that reuses an integer ID. The UI and bank importers preserve the identities captured when data were selected.

Keep HTTPS, same-origin checks, Secure/HttpOnly cookies and the existing ten-minute session timeout. The reverse proxy must overwrite Host and forwarding headers; only loopback forwarding is trusted. Public registration remains controlled by the existing servlet policy. Lists default to 100 rows per page and enforce a server-side limit.

## Private data

The Python statement importers are organised by bank account under `src/main/Python`. Keep credentials, bank statements, private transaction payloads, checkpoints and database backups outside the source package. See the [importer list and private input requirements](src/main/Python/PRIVACY.md) and [inflation data maintenance](tools/inflation/README.md). The [July 2026 brand review](docs/legal-and-brand-review.md) records the project naming changes.

## Period performance

Open Financial Report after signing in. The purchasing-power estimate uses the account balances, transactions and inflation observations already recorded in Finance Workspace. Previously saved period reports are available below it. See [Period performance](docs/period-performance.md) for the calculation basis.

## Identifier reuse

See [identifier reuse policy](docs/identifier-reuse.md) for the current safe numeric-ID scope, transaction and generation requirements, and identifiers that must not be recycled.
