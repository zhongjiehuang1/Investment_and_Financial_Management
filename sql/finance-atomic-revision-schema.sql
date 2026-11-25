-- Protocol-1 -> protocol-2 maintenance ONLY. Formal entry and all old JVMs must
-- be stopped; lock/revoke the old writer credentials and verify zero sessions.
-- Execute on ONE normal Group Replication writer and wait for durable peer apply.
-- This changes metadata only, never user/business rows or historic receipts.
ALTER TABLE CLUSTER_ATOMIC_GUARD
 ADD COLUMN DATA_REVISION BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT CLUSTER_ATOMIC_DATA_REVISION_NONNEGATIVE CHECK (DATA_REVISION>=0);
ALTER TABLE CLUSTER_OPERATIONS ADD COLUMN ENDPOINT VARCHAR(160) NULL,
 ADD COLUMN DELETE_RESULT_HASH CHAR(64) NULL,
 ADD COLUMN DELETE_RESULT_EXPIRES DATETIME(6) NULL;
-- After this DDL, provision a new least-privilege writer identity and prepare
-- matching private configs on both nodes. Preserve historical idempotency keys.
-- Then use a prepared/bound maintenance UPDATE, with a NEW shared Finance UUID:
-- UPDATE CLUSTER_ATOMIC_CONFIG SET PROTOCOL_VERSION=2,MIGRATION_ID=?
-- WHERE ID=1 AND PROTOCOL_VERSION=1 AND MIGRATION_ID=?;
-- New runtime refuses protocol-1 on EVERY transaction, and old runtime startup
-- refuses protocol-2. Already-running old writers must also be stopped and
-- credential-fenced; a marker change alone cannot revoke their cached authority.
