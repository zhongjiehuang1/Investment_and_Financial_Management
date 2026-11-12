-- Explicit maintenance-only setup for a new, independently replicated decision store.
-- Do not replace or drop an existing table; preserve it through failover and restore.
CREATE TABLE IF NOT EXISTS iafm_JBossTSTxTable (
  StateType INTEGER NOT NULL,
  Hidden INTEGER NOT NULL,
  TypeName VARCHAR(255) NOT NULL,
  UidString VARCHAR(255) NOT NULL,
  ObjectState BLOB,
  PRIMARY KEY (UidString, TypeName, StateType)
) ENGINE=InnoDB;
-- Bind this explicitly to the existing coordinator UUID only after all writers
-- are stopped, prepared XA transactions are resolved, and the file store is empty.
CREATE TABLE IF NOT EXISTS iafm_decision_store_identity (
  ID INTEGER NOT NULL PRIMARY KEY,
  STORE_UUID CHAR(36) NOT NULL
) ENGINE=InnoDB;
