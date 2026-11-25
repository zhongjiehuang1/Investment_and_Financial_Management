# IAFM identifier reuse: current scope and audit

## Decision

The existing implementation covers six business tables: `USERS`, `ALL_INVESTMENT_ASSETS`, `ALL_NON_INVESTMENT_ASSETS`, `ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES`, `BASIC_VARIABLES_AND_DATA`, and `INCOME_AND_EXPENDITURE_DATA`. The source audit found **no additional independent numeric business-table ID that can safely be added to this allocator**. No additional allocator, schema migration, identifier renumbering or account-deletion API is proposed by this audit.

## How the existing scope is protected

IAFM uses one production MySQL instance with two logical schemas, `IAFM` and `IAFM_peer`; record identity is evaluated against the configured logical schema and includes the table, numeric ID and immutable generation. A reused integer identifies a new record only with a new generation. The account, record, ownership and generation checks reject stale requests; a numeric ID alone is not authority. The shared atomic transaction guard, allocator locking and data-revision checks serialize and protect affected mutations. The affected row, its dependent work, allocator state and operation result must commit together; rollback must leave no partial reuse. An unknown response is resolved using the original operation UUID, never by inventing a new operation ID or assuming rollback.

Within the reviewed implementation, reconciliation uses recorder/high-water/gap-range state; candidate reuse checks the existing row, visible declared exported foreign keys in the current schema, and the known ledger-record reference for income/expenditure records. The allocator does not prove the absence of every unmodelled reference: cross-schema links not visible to its DB account, data embedded in JSON, exported reports, external clients and historical snapshots require their own review. In particular, do not expand the allowlist or alter relationships until an administrator has inventoried the complete live metadata and application/external references, updated guards as needed, and tested on an isolated copy. Do not reset a sequence, edit recorder tables manually, renumber rows, or discard receipts to force smaller IDs.

## Why the remaining IDs are not new candidates

- `LEDGER_JOURNALS.ID` is a journal UUID. `LEDGER_ENTRIES` uses `(JOURNAL_ID, LINE_NUMBER)`; a line number is local to its journal and can already appear in a different journal. It is part of ledger history and must not be globally recycled or reordered.
- `CLUSTER_ATOMIC_GUARD.ID` and `CLUSTER_ATOMIC_CONFIG.ID` are singleton protocol markers. Revisions and guard versions track transaction state rather than free identifiers; they must not be reset for reuse.
- `ID_RECORDER_*`, `ID_ALLOCATION_STATE` and `ID_RECOVERY_RANGES` are the existing allocator's bookkeeping, not independent business entities to renumber. `LOGIN_LIMITS` and `LOGIN_STATUS_LIMITS` use client-related keys for security limits, not a disposable numeric sequence.
- Login attempt, operation, result and receipt identifiers identify authentication or transaction history and unknown-result reconciliation. Their UUIDs and generations must not be recycled. Old transaction/coordinator decision identifiers are likewise recovery identities if present; do not remove them merely to reduce numbers.

## Evidence and boundaries

The current server inventory reported by the operator covers six application schemas and 97 base tables, and the six allocator state rows/pool tables were observed. Trigger status was not independently established by this source audit or by the isolated restore. This is contextual inventory, not proof that arbitrary external or non-FK references are absent. This document is a read-only code audit; it did not alter business data, deploy a new mechanism or perform new failure tests.

Source index, relative to `Investment_and_Financial_Management`: `docs/SHARDING_CONTRACT.md`; `src/main/JavaWeb/Java/DatabaseService/SetIds.java`, `DatabaseInit.java`, `CreateAllTables.java`, `RequestIdentity.java`, `AtomicFinanceTransactions.java`; `src/main/JavaWeb/Java/DatabaseService/Model/Controller/IncomeAndExpenditureData/Ledger.java`; `sql/finance-atomic-schema.sql`, `sql/finance-atomic-login-schema.sql`; and `ops/cluster/finance-schema.sql`. Historical XA code is not evidence that the current single-database deployment uses XA.
