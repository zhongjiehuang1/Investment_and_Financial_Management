# Finance logical schemas and identity contract

IAFM runs on both application servers against one production MySQL instance on Server 2. The `IAFM` and `IAFM_peer` schemas are logical participants inside that single instance; their logical identifiers are not physical-server identities or independent replicas. Server 1 uses a restricted, host-key-verified SSH tunnel and JDBC TLS identity verification. Each public entry serves its local Tomcat. If the shared database is unavailable, database-backed work on both hosts stops. Existing Tomcat session replication does not provide database replication or automatic request takeover.

## Identity and placement

The canonical user identity is in `IAFM.USERS`; the corresponding identity shadow in `IAFM_peer.USERS` has the same numeric ID and immutable generation. A user identity includes its numeric ID and generation, and record identity includes its logical database/schema, table, numeric ID and generation. Logical schema identifiers continue to distinguish records that can have the same number in different schemas. They must not be interpreted as proof of a physical server or live copy.

The application checks the authenticated user's generation, ownership and the relevant row generations before a mutation. Edits and deletes use the generations captured when the records were read; a stale request must not act on a different row that later receives the same integer. Requests involving an account or transfer also carry the appropriate account generations. Missing, stale or ambiguous identity is rejected rather than guessed. Configuration for administrator and read-only API access also binds a user ID to its generation.

## Transactions, revisions and operation results

The current atomic backend uses one physical InnoDB connection with logical views of both schemas. A shared guard serializes the protected transaction; application changes, identity checks, transaction state and operation result are committed together. Business mutation checks include the current data-revision token so stale pages cannot silently overwrite newer work. Cross-schema transfers and their ledger effects are handled within that transaction. A failed transaction rolls back its changes; a successful commit provides durability on the single production database, not confirmation from two database replicas.

A mutation carries its original UUID operation ID and request identity. Repeating the same accepted operation with the same content may return its recorded result; reusing an ID with different content is rejected. If a response is lost or a commit outcome is uncertain, retain the original ID and query the authenticated operation result before making any decision. A timeout or HTTP 503 alone does not prove rollback. Preserve operation results and transaction state with the database backup; do not clear them merely to make a retry succeed.

## Recyclable numeric IDs

The existing allocator explicitly covers these six business tables:

- `ALL_INVESTMENT_ASSETS`
- `ALL_NON_INVESTMENT_ASSETS`
- `ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES`
- `BASIC_VARIABLES_AND_DATA`
- `INCOME_AND_EXPENDITURE_DATA`
- `USERS`

Allocation and recycling occur within the business transaction, under the relevant allocator locks. Startup reconciles the recorder, high-water mark and compact recovered-gap ranges; failure to establish consistency keeps writes unavailable. The allocator examines the lowest recorded or recovered candidate first, rejects candidates already present or referenced, and otherwise falls back to the next ID above the current maximum. This is not a claim that it independently searches every possible unrecorded gap on every allocation.

Before reusing a candidate, the implementation checks the current table and declared exported foreign-key references in the current schema. It also checks the explicitly known nullable ledger record reference for `INCOME_AND_EXPENDITURE_DATA`. When schema relationships change, update and verify these checks before enabling writes; they cannot discover arbitrary undocumented references. IDs are returned to the pool only after the affected row and its dependent work have been removed in the same transaction. A replacement row has a new immutable generation, so reuse does not give an old request authority over the replacement.

This rule applies only to the listed tables and reviewed paths. Journal UUIDs, operation UUIDs and audit/receipt identities are not recyclable numeric IDs. Do not reset sequences, renumber existing rows or delete operation history to obtain smaller IDs. The ledger uses journal UUIDs and journal-line identities; deleting an account must handle its dependent records and balanced transfer effects rather than silently discarding inconsistent evidence.

The implementation boundaries are in `DatabaseService/DatabaseInit.java`, `DatabaseService/SetIds.java`, `DatabaseService/RequestIdentity.java` and `DatabaseService/AtomicFinanceTransactions.java`. Schema changes and destructive experiments require a separate reviewed maintenance plan, a verified recovery copy and isolated test data. Current English operations guidance is in local OneDrive `Linux_Server_*/README.md`.
