-- Apply only with all legacy writers stopped, old XA recovered and backup verified.
-- Run in the CANONICAL logical schema; runtime never creates or repairs this marker.
-- Do not reset VERSION during deployment or replica rejoin.
CREATE TABLE IF NOT EXISTS CLUSTER_ATOMIC_GUARD (
 ID TINYINT PRIMARY KEY,
 VERSION BIGINT NOT NULL,
 DATA_REVISION BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT CLUSTER_ATOMIC_GUARD_SINGLETON CHECK (ID=1),
 CONSTRAINT CLUSTER_ATOMIC_GUARD_NONNEGATIVE CHECK (VERSION>=0),
 CONSTRAINT CLUSTER_ATOMIC_DATA_REVISION_NONNEGATIVE CHECK (DATA_REVISION>=0)
) ENGINE=InnoDB;
INSERT IGNORE INTO CLUSTER_ATOMIC_GUARD(ID,VERSION,DATA_REVISION) VALUES(1,0,0);
CREATE TABLE IF NOT EXISTS CLUSTER_ATOMIC_CONFIG (
 ID TINYINT PRIMARY KEY,
 PROTOCOL_VERSION INT NOT NULL,
 MIGRATION_ID CHAR(36) NOT NULL,
 PRIMARY_SCHEMA VARCHAR(64) NOT NULL,
 PEER_SCHEMA VARCHAR(64) NOT NULL,
 CONSTRAINT CLUSTER_ATOMIC_CONFIG_SINGLETON CHECK (ID=1)
) ENGINE=InnoDB;
-- Insert exactly one marker using prepared, bound maintenance parameters:
-- INSERT INTO CLUSTER_ATOMIC_CONFIG(ID,PROTOCOL_VERSION,MIGRATION_ID,PRIMARY_SCHEMA,PEER_SCHEMA)
-- VALUES(1,2,?,?,?);
-- All nodes use the SAME migration ID and logical schema names.
-- The application identity requires SELECT only on CONFIG; SELECT/UPDATE on GUARD.
-- This migration is incomplete until old writer credentials are revoked/locked,
-- all table engines/FK restrictions and exact copied data have been verified,
-- and real multi-primary controller/failure/restore acceptance has passed.
