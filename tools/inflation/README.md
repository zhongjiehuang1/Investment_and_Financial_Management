# Monthly inflation maintenance

This tool merges the documented Trading Economics observations into the existing inflation table. The full source manifest and public-access limitations are in `data/SOURCE_NOTES.md`. Values are monthly headline **year-on-year percentages**, with reference months normalized to their 15th day. EUR uses headline HICP. Values are exact decimals with at most two decimal places; zero and negative inflation are valid.

The importer preserves existing observations outside the supplied source months. A source observation replaces a conflicting existing value for that same currency and month, and the dry-run report lists both values. If old rows disagree with one another for a month, the importer resolves them only when the supplied verified source explicitly covers that month and separately reports every old value. Conflicting old months outside source coverage stop the operation. Missing months are not invented or carried forward. Only adjacent known months with identical values are combined; `OBSERVED_THROUGH` preserves the final month of each combined interval. Existing rows without that field count only as observations of their own month.

Use Python 3.10+ with PyMySQL installed. Supply `IAFM_DB_URL` (for example `jdbc:mysql://localhost:3306/IAFM`) and `IAFM_DB_USER` through the maintenance environment, or specify `--database`, `--db-user`, and `--socket` explicitly. The password is requested securely when neither `IAFM_MIGRATION_PASSWORD` nor `IAFM_DB_PASSWORD` is set. `--prompt-password` always requests it interactively and ignores both password environment variables. Do not put credentials into the URL, command line, source code, or output files. The application runtime identity can retain DML-only privileges; migration uses a separate maintenance identity.

Run each command separately from this project directory. The default is read-only:

```sh
python3 tools/inflation/import_inflation.py
```

For the server maintenance identity and its separately installed PyMySQL library, the equivalent interactive dry run is:

```sh
env PYTHONPATH=/root/iafm-maintenance-libs python3 tools/inflation/import_inflation.py --db-user root --database IAFM --socket /var/run/mysqld/mysqld.sock --prompt-password
```

Replace the database name with the existing configured database if different. The password prompt is not echoed and contains no shell argument secret.

Review source coverage, new months, conflicting old/source values, and final row counts. Apply during the deployment maintenance window, after stopping the old application, so old code cannot concurrently modify inflation rows without the new lock:

```sh
python3 tools/inflation/import_inflation.py --apply --maintenance --backup-dir /root/iafm-inflation-backups
```

Before applying, run the project's `DatabaseService.CreateAllTables` maintenance entry point against a backed-up database and allow its `SetIds` consistency check to finish. The importer requires the existing `OBSERVED_THROUGH` and immutable `GENERATION` columns, unique currency/date index, `INFLATION_SERIES_LOCK`, `ID_ALLOCATION_STATE`, and `ID_RECOVERY_RANGES`. It does not execute schema changes or migrate obsolete negative counters. `--maintenance` confirms that incompatible old application instances and importers are stopped.

`--apply` locks the inflation series first, then its allocator, matching the Java application. It writes and verifies an exclusive mode-0600 backup of the locked inflation rows (including generations), explicit recycle pool, allocation high-water state, and recovery ranges before any mutation. The importer updates only changed intervals and explicitly deletes/recycles superseded starts. Existing currency/start identities retain their ID and generation. New starts receive a fresh UUID generation and the smallest safe ID from the explicit pool or recovery ranges; only an empty pool permits current `MAX(ID)+1`. Newly deleted IDs may be reused within that same transaction. It never replaces the entire table. Repeating an identical import leaves observations and identity metadata unchanged.

Data changes, ID claims, recycling, and high-water updates commit together after exact monthly read-back and active/reusable-ID checks. Backup, insertion, deletion, or verification failure rolls back the whole transaction. Declared references to removed inflation intervals stop the import instead of relying on foreign-key cascading deletes.

Keep the private backup outside the web application and source archive. Restoration is a separate maintenance operation: stop all writers, acquire the same locks, restore the saved rows with their original IDs and generations together with that table's recorder, allocation state, and recovery ranges in one transaction, and validate the restored monthly data and ID invariants before resuming writes. Never restore only the observation rows while leaving the later recycle state in place. Retain the normal database backup for schema and full-database recovery; do not commit private backup files.

New administrator CRUD operations use the same series lock and preserve these interval rules. Adding an already covered month with the same rate is idempotent. A different rate returns 409 and requires editing/deleting the existing interval first. Editing a multi-month interval changes its entire rate while keeping its dates and currency; moving those fields returns 400. Deleting a row deletes its full displayed interval. Adjacent identical known intervals merge automatically while gaps remain gaps.

Reproduce the source CSVs from captured public chart tooltips:

```sh
python3 tools/inflation/data/build_dataset.py
```

Run importer logic tests:

```sh
python3 -m unittest discover -s src/test/python -p test_inflation_import.py
```

The optional real-MySQL importer tests use only the disposable `iafm_inflation_import_test` schema on localhost with a passwordless local test root account. Prepare that schema with `DatabaseService.CreateAllTables`, then set `IAFM_INFLATION_TEST_MYSQL_PORT` to the isolated test server's port when running the same test command. Without that explicit variable, the MySQL cases are skipped. Do not point the test runtime at a production MySQL instance.
