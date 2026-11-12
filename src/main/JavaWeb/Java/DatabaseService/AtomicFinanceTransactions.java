package DatabaseService;

import Controller.ApiSupport;
import org.json.JSONObject;
import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Replicated mode has no application-level prepared branches to recover. Both
 * logical schemas, operation identity and result share one local InnoDB commit.
 * Updating the common guard row makes every concurrent request conflict during
 * multi-primary certification, including absence/result queries and ID repair.
 * A losing transaction is retried with its original operation identity; it can
 * never recover, abort or unlock a transaction executing in another JVM.
 */
final class AtomicFinanceTransactions {
    record Proof(String mysqlUuid, String viewId, String epoch, String mode, String membership, String controllerBootId, String permit) { }
    interface Authority {
        Proof capture() throws SQLException;
        void verify(Proof proof) throws SQLException;
        String confirm(Connection connection, Proof proof, String operation) throws SQLException;
    }
    @FunctionalInterface interface Connections { Connection open(Proof proof) throws SQLException; }
    @FunctionalInterface private interface Transaction { JSONObject run(Map<Integer, Connection> connections) throws Exception; }
    private final Properties config;
    private final String canonicalSchema, peerSchema, migration;
    private final Authority authority;
    private final Connections connections;
    private final AtomicLoginAttempts logins;

    static boolean enabled(Properties properties) {
        String mode = properties.getProperty("transaction.backend", "xa");
        if (!Set.of("xa", "atomic").contains(mode)) throw new IllegalArgumentException("Unknown finance transaction backend");
        return mode.equals("atomic");
    }

    AtomicFinanceTransactions(Properties properties) {
        this(properties, schemas(properties), new AtomicReplicationPermit(properties), null);
    }

    AtomicFinanceTransactions(Properties properties, Authority authority) {
        this(properties, schemas(properties), authority, null);
    }

    /** Test injection is package-private; production always enforces MySQL identity and session consistency. */
    AtomicFinanceTransactions(Properties properties, String[] schemas, Authority authority, Connections supplied) {
        this(properties,schemas,authority,supplied,new AtomicLoginAttempts());
    }

    AtomicFinanceTransactions(Properties properties, String[] schemas, Authority authority, Connections supplied,AtomicLoginAttempts logins) {
        config = new Properties(); config.putAll(properties);
        canonicalSchema = schemas[0]; peerSchema = schemas[1];
        migration = required(config, "atomic.migrationId").toLowerCase(Locale.ROOT);
        if (!RequestIdentity.validGeneration(migration)) throw new IllegalArgumentException("Explicit atomic migration identity is required");
        this.authority = Objects.requireNonNull(authority);
        this.logins = Objects.requireNonNull(logins);
        connections = supplied != null ? supplied : proof -> {
            Connection database = DriverManager.getConnection(transactionUrl(required(config, "node1.url")),
                    required(config, "node1.user"), Objects.requireNonNull(config.getProperty("node1.password")));
            try {
                try (Statement query = database.createStatement()) { query.execute("SET SESSION group_replication_consistency='BEFORE_AND_AFTER'"); }
                ReplicationDatabaseIdentity.verify(database, proof.mysqlUuid());
                verifyDurability(database);
                return database;
            } catch (SQLException | RuntimeException failure) {
                try { database.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        };
    }

    static void verifyDurability(Connection database) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery(
                "SELECT @@GLOBAL.innodb_flush_log_at_trx_commit,@@GLOBAL.sync_binlog,@@GLOBAL.innodb_doublewrite,@@GLOBAL.gtid_mode,@@SESSION.time_zone,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),CURRENT_TIMESTAMP())")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != 1
                    || !"ON".equalsIgnoreCase(rows.getString(3)) || !"ON".equalsIgnoreCase(rows.getString(4))
                    || !Set.of("+00:00", "UTC").contains(rows.getString(5)) || rows.getLong(6) != 0)
                throw new SQLException("Atomic replicated durability or UTC session settings are not enabled");
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Incomplete atomic database configuration: " + key);
        return value;
    }

    static String transactionUrl(String original) {
        String[] parts = original.split("\\?", 2);
        Set<String> controlled = Set.of("connecttimeout", "sockettimeout", "pinglobaltxtophysicalconnection",
                "databaseterm", "allowmultiqueries", "useserverprepstmts", "cacheprepstmts", "autoreconnect",
                "autoreconnectforpools", "reconnectattxend", "servertimezone", "connectiontimezone", "forceconnectiontimezonetosession", "preserveinstants");
        List<String> parameters = new ArrayList<>();
        if (parts.length == 2) for (String parameter : parts[1].split("&")) {
            String key = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (!controlled.contains(key)) parameters.add(parameter);
        }
        parameters.addAll(List.of("connectTimeout=3000", "socketTimeout=90000", "pinGlobalTxToPhysicalConnection=true",
                "databaseTerm=CATALOG", "allowMultiQueries=false", "useServerPrepStmts=true", "cachePrepStmts=false",
                "autoReconnect=false", "autoReconnectForPools=false", "reconnectAtTxEnd=false",
                "connectionTimeZone=UTC", "forceConnectionTimeZoneToSession=true", "preserveInstants=true"));
        return parts[0] + "?" + String.join("&", parameters);
    }

    private static String[] schemas(Properties properties) {
        String[] result = new String[2]; int port = -1;
        for (int i = 0; i < 2; i++) {
            String url = required(properties, "node" + (i + 1) + ".url");
            if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("Atomic mode requires loopback MySQL");
            URI endpoint = URI.create(url.substring(5)); String path = endpoint.getRawPath();
            if (!"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                    || path == null || !path.matches("/[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("Atomic schemas require explicit loopback endpoints");
            if (i > 0 && endpoint.getPort() != port) throw new IllegalArgumentException("Atomic schemas must share one physical MySQL");
            port = endpoint.getPort(); result[i] = path.substring(1);
        }
        if (result[0].equalsIgnoreCase(result[1])
                || !required(properties, "node1.user").equals(required(properties, "node2.user"))
                || properties.getProperty("node1.password") == null
                || !properties.getProperty("node1.password").equals(properties.getProperty("node2.password")))
            throw new IllegalArgumentException("Atomic mode requires distinct schemas and one dedicated cross-schema DML account");
        return result;
    }

    private void verifyMarker(Connection canonical)throws SQLException{
        try(Statement query=canonical.createStatement();ResultSet rows=query.executeQuery(
                "SELECT PROTOCOL_VERSION,MIGRATION_ID,PRIMARY_SCHEMA,PEER_SCHEMA FROM CLUSTER_ATOMIC_CONFIG WHERE ID=1")){
            if(!rows.next()||rows.getInt(1)!=2||!migration.equals(rows.getString(2))
                    ||!canonicalSchema.equals(rows.getString(3))||!peerSchema.equals(rows.getString(4))||rows.next())
                throw new SQLException("Atomic protocol-2 migration marker does not match this writer");
        }
        try(Statement query=canonical.createStatement()){query.executeQuery("SELECT ENDPOINT,DELETE_RESULT_HASH,DELETE_RESULT_EXPIRES FROM CLUSTER_OPERATIONS WHERE 1=0").close();}
    }

    /** Runtime never creates schema or discards legacy work; maintenance supplies the marker. */
    void initialize() throws Exception {
        Path legacy = Path.of(required(config, "objectStore.absolutePath"));
        if (!legacy.isAbsolute()) throw new IllegalArgumentException("Absolute legacy transaction-store path required");
        JdbcDecisionStore.requireEmptyLegacyStore(legacy);
        transact(UUID.randomUUID().toString(), 1, views -> {
            Connection canonical = views.get(1);
            AtomicLoginAttempts.verifySchema(canonical);
            verifyMarker(canonical);
            for (Connection database : views.values()) {
                DatabaseInit.verifyReplicatedSchema(database);
                try (PreparedStatement query = database.prepareStatement(
                        "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
                    query.setString(1, database.getCatalog());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) if (!"InnoDB".equalsIgnoreCase(rows.getString(1)))
                            throw new SQLException("Atomic mode requires every logical table to use InnoDB");
                    }
                }
                try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT OPERATION_ID FROM CLUSTER_GATE WHERE ID=1")) {
                    if (!rows.next() || rows.getString(1) != null) throw new SQLException("Legacy finance gate has not been drained");
                }
                for (String query : List.of("SELECT COUNT(*) FROM CLUSTER_OPERATIONS WHERE STATE NOT IN ('COMMITTED','ABORTED')",
                        "SELECT COUNT(*) FROM CLUSTER_BRANCHES WHERE TERMINAL IS NULL OR TERMINAL NOT IN ('COMMITTED','ABORTED')")) {
                    try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery(query)) {
                        if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("Legacy distributed transactions require recovery before atomic migration");
                    }
                }
                new SetIds().setAllIds(database);
            }
            return new JSONObject().put("ready", true);
        });
    }

    private JSONObject transact(String operation, int ingress, Transaction action) throws Exception {
        Proof proof = authority.capture();
        try (Connection physical = connections.open(proof)) {
            physical.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            physical.setAutoCommit(false);
            try {
                physical.setCatalog(canonicalSchema);
                verifyMarker(physical);
                // A real value change cannot disappear as a net no-op from the
                // write set. All instances/members update this same primary key.
                try (PreparedStatement guard = physical.prepareStatement(
                        "UPDATE CLUSTER_ATOMIC_GUARD SET VERSION=VERSION+1 WHERE ID=1 AND VERSION>=0 AND VERSION<9223372036854775807")) {
                    if (guard.executeUpdate() != 1) throw new SQLException("Atomic serialization guard is missing or exhausted");
                }
                JSONObject result;
                try (AtomicSchemaSession session = new AtomicSchemaSession(physical, canonicalSchema, peerSchema);
                     ShardScope scope = ShardScope.begin(session.connections(), ingress)) {
                    result = action.run(scope.connections());
                    if (scope.isRollbackOnly() || session.rollbackOnly()) throw new ApiSupport.Failure(409, "The operation was rolled back.");
                }
                authority.verify(proof);
                physical.commit();
                // A commit exception or failed confirmation is an unknown result,
                // never an excuse to record ABORTED or repeat a new operation ID.
                String durability = authority.confirm(physical, proof, operation);
                return annotate(result, durability);
            } catch (Exception | Error failure) {
                try { physical.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static JSONObject annotate(JSONObject result, String durability) throws SQLException {
        if (!durability.equals("dual") && !durability.equals("single")) throw new SQLException("Unknown durability confirmation");
        return new JSONObject(result.toString()).put("durability", durability).put("replicationMode", durability)
                .put("degraded", durability.equals("single"));
    }

    /** Old internal callers have no token and fail closed for a new business write. */
    JSONObject run(String operation,String owner,String hash,String path,int ingress,Model.User user,boolean mutation,FinanceCluster.Work work)throws Exception{
        return run(operation,owner,hash,path,ingress,user,mutation,work,null);
    }
    JSONObject run(String operation, String owner, String hash, String path, int ingress,
                   Model.User user, boolean mutation, FinanceCluster.Work work,String expectedRevision) throws Exception {
        return run(operation,owner,hash,path,ingress,user,mutation,work,expectedRevision,null);
    }
    JSONObject run(String operation, String owner, String hash, String path, int ingress,
                   Model.User user, boolean mutation, FinanceCluster.Work work,String expectedRevision,String deletionCookie) throws Exception {
        return transact(operation, ingress, views -> {
            Connection canonical = views.get(1);
            for(Connection database:views.values())verifyUser(database,user);
            if(user!=null&&RequestIdentity.isActive())RequestIdentity.verifyCurrentUser();
            if (mutation) {
                JSONObject previous = existing(canonical, operation, owner, hash);
                if (previous != null) return previous;
            }
            long revision=FinanceDataRevision.read(canonical);
            boolean business=FinanceDataRevision.businessMutation(mutation,path);
            if(business)FinanceDataRevision.require(expectedRevision,FinanceDataRevision.token(migration,revision),operation);
            String deletionHash=null;Timestamp deletionExpires=null;
            if(mutation&&path.equals("/deleteUser")){
                if(user==null||DeletionResultCapability.base(deletionCookie)==null)throw new ApiSupport.Failure(401,"An existing authenticated session cookie is required to delete this account.");
                deletionHash=DeletionResultCapability.digest(required(config,"idempotency.secret"),deletionCookie,operation,owner);
                deletionExpires=Timestamp.from(databaseNow(canonical).plus(java.time.Duration.ofDays(7)));
            }
            if(mutation)update(canonical,"INSERT INTO CLUSTER_OPERATIONS(ID,OWNER_KEY,REQUEST_HASH,STATE,ENDPOINT,DELETE_RESULT_HASH,DELETE_RESULT_EXPIRES) VALUES(?,?,?,'COMMITTED',?,?,?)",operation,owner,hash,path,deletionHash,deletionExpires);
            JSONObject result = work.run();
            if(business)revision=FinanceDataRevision.advance(canonical,revision);
            result.put("dataRevision",FinanceDataRevision.token(migration,revision));
            if (!result.has("status")) result.put("status", "success");
            if (mutation) result.put("operationId", operation).put("operationStatus", "committed");
            if (user != null) result.put("userGeneration", user.getGeneration());
            if(path.equals("/deleteUser"))result.put("accountDeleted",true);
            if (user != null && path.equals("/deleteUser"))
                update(canonical, "DELETE FROM CLUSTER_RESULTS WHERE OPERATION_ID IN (SELECT ID FROM CLUSTER_OPERATIONS WHERE OWNER_KEY=?)", owner);
            if (mutation) update(canonical, "INSERT INTO CLUSTER_RESULTS(OPERATION_ID,PAYLOAD) VALUES(?,?)", operation, result.toString());
            return result;
        });
    }

    private static void verifyUser(Connection database, Model.User user) throws SQLException {
        if (user == null) return;
        try (PreparedStatement query = database.prepareStatement("SELECT GENERATION FROM USERS WHERE ID=? FOR UPDATE")) {
            query.setInt(1, user.getId());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !user.getGeneration().equalsIgnoreCase(rows.getString(1))) throw new RequestIdentity.StaleIdentity(true);
            }
        }
    }

    private static void update(Connection database, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = database.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
            query.executeUpdate();
        }
    }
    private static java.time.Instant databaseNow(Connection database)throws SQLException{
        try(Statement query=database.createStatement();ResultSet rows=query.executeQuery("SELECT CURRENT_TIMESTAMP")){
            if(!rows.next())throw new SQLException("Database time unavailable");return rows.getTimestamp(1).toInstant();
        }
    }

    private static JSONObject existing(Connection database, String operation, String owner, String hash) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT O.OWNER_KEY,O.REQUEST_HASH,O.STATE,R.PAYLOAD FROM CLUSTER_OPERATIONS O LEFT JOIN CLUSTER_RESULTS R ON R.OPERATION_ID=O.ID WHERE O.ID=?")) {
            query.setString(1, operation);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                if (!owner.equals(rows.getString(1)) || !hash.equals(rows.getString(2)))
                    throw new ApiSupport.Failure(409, "Operation identity was already used for different data.");
                if (rows.getString(3).equals("ABORTED")) throw new ApiSupport.Failure(409, "This operation was rolled back. Refresh before trying again.");
                if (!rows.getString(3).equals("COMMITTED") || rows.getString(4) == null) throw new SQLException("Committed operation result is unavailable");
                return new JSONObject(rows.getString(4));
            }
        }
    }

    JSONObject status(Model.User user, String operation) throws Exception {
        if (!RequestIdentity.validGeneration(operation)) throw new ApiSupport.Failure(400, "Invalid operation identity.");
        // /operationStatus itself is already wrapped by run(). Reuse that outer
        // transaction, and let its commit/confirmation serialize this read.
        if (ShardScope.isActive()) return status(ShardScope.current().connection(1), user, operation);
        return transact(UUID.randomUUID().toString(), 1, views -> status(views.get(1), user, operation).put("dataRevision",FinanceDataRevision.token(migration,FinanceDataRevision.read(views.get(1)))));
    }

    /** Independent recovery probe: actual runtime SQL, no /ready call or guard write. */
    void recoveryProbe() throws Exception {
        Proof proof=authority.capture();
        try(Connection physical=connections.open(proof)){
            physical.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);physical.setAutoCommit(false);
            try(AtomicSchemaSession session=new AtomicSchemaSession(physical,canonicalSchema,peerSchema)){
                Connection canonical=session.connections().get(1);
                verifyMarker(canonical);
                try(Statement query=canonical.createStatement();ResultSet rows=query.executeQuery("SELECT VERSION,DATA_REVISION FROM CLUSTER_ATOMIC_GUARD WHERE ID=1")){
                    if(!rows.next()||rows.getLong(1)<0||rows.getLong(2)<0||rows.wasNull()||rows.next())throw new SQLException("Atomic recovery guard is unavailable");
                }
                AtomicLoginAttempts.verifySchema(canonical);
                for(Connection database:session.connections().values()){
                    try(Statement query=database.createStatement();ResultSet rows=query.executeQuery("SELECT OPERATION_ID FROM CLUSTER_GATE WHERE ID=1")){
                        if(!rows.next()||rows.getString(1)!=null||rows.next())throw new SQLException("Atomic recovery legacy gate is not drained");
                    }
                    for(String sql:new String[]{"SELECT ID,GENERATION,EMAIL_ADDRESS,PASSWORD FROM USERS WHERE 1=0",
                            "SELECT ID,USER_ID,GENERATION FROM BASIC_VARIABLES_AND_DATA WHERE 1=0",
                            "SELECT ID,USER_ID,GENERATION FROM ALL_INVESTMENT_ASSETS WHERE 1=0",
                            "SELECT ID,USER_ID,GENERATION FROM ALL_NON_INVESTMENT_ASSETS WHERE 1=0",
                            "SELECT ID,USER_ID,JOURNAL_ID FROM INCOME_AND_EXPENDITURE_DATA WHERE 1=0",
                            "SELECT ID,USER_ID FROM LEDGER_JOURNALS WHERE 1=0",
                            "SELECT JOURNAL_ID FROM LEDGER_ENTRIES WHERE 1=0"})
                        try(Statement query=database.createStatement()){query.executeQuery(sql).close();}
                }
                authority.verify(proof);
            }finally{physical.rollback();}
        }
    }

    String durabilityMode() throws SQLException { return authority.capture().mode(); }

    FinanceCluster.LoginOutcome login(String operation,String client,String emailHash,String payloadHash,String email,String password)throws Exception{
        AtomicLoginAttempts.Outcome[] outcome=new AtomicLoginAttempts.Outcome[1];
        JSONObject confirmed=transact(operation,1,views->{
            outcome[0]=logins.login(views.get(1),operation,client,emailHash,payloadHash,email,password);
            if(outcome[0].user()!=null)verifyUser(views.get(2),outcome[0].user());
            return outcome[0].response();
        });
        return new FinanceCluster.LoginOutcome(confirmed,outcome[0].user(),confirmed.getInt("httpStatus"));
    }

    JSONObject loginStatus(String operation,String client,String emailHash)throws Exception{
        return transact(UUID.randomUUID().toString(),1,views->logins.status(views.get(1),operation,client,emailHash));
    }

    JSONObject deletedUserStatus(Model.User actor,String operation)throws Exception{
        return transact(UUID.randomUUID().toString(),1,views->{
            int live=0;
            for(Connection database:views.values())try(PreparedStatement query=database.prepareStatement("SELECT GENERATION FROM USERS WHERE ID=?")){
                query.setInt(1,actor.getId());try(ResultSet rows=query.executeQuery()){
                    if(rows.next()&&actor.getGeneration().equalsIgnoreCase(rows.getString(1)))live++;
                }
            }
            if(live==1)throw new SQLException("Deletion identity shadows are inconsistent");
            boolean committed=false,expired=false;
            try(PreparedStatement query=views.get(1).prepareStatement("SELECT STATE,DELETE_RESULT_HASH,DELETE_RESULT_EXPIRES FROM CLUSTER_OPERATIONS WHERE ID=? AND OWNER_KEY=? AND ENDPOINT='/deleteUser'")){
                query.setString(1,operation);query.setString(2,FinanceCluster.owner(actor));
                try(ResultSet rows=query.executeQuery()){
                    if(rows.next()){
                        committed="COMMITTED".equals(rows.getString(1));
                        if(rows.getString(2)!=null){Timestamp expiry=rows.getTimestamp(3);if(expiry==null)throw new SQLException("Deletion result expiry is missing");expired=!expiry.toInstant().isAfter(databaseNow(views.get(1)));}
                    }
                }
            }
            if(live==2){if(committed)throw new SQLException("Committed deletion still has its original identity");return new JSONObject().put("notDeleted",true);}
            if(!committed||expired)throw new ApiSupport.Failure(404,"The deletion result cannot be verified with this session.");
            JSONObject terminal=new JSONObject().put("status","success").put("operationId",operation).put("operationStatus","committed").put("accountDeleted",true);
            return new JSONObject().put("status","success").put("operationId",operation).put("state","committed").put("result",terminal);
        });
    }

    /** Works after both JVMs lose all sessions. The original cookie proves only this result. */
    JSONObject deletedUserCookieStatus(String cookie,String operation)throws Exception{
        if(DeletionResultCapability.base(cookie)==null||!RequestIdentity.validGeneration(operation))return null;
        JSONObject confirmed=transact(UUID.randomUUID().toString(),1,views->{
            String owner=null,stored=null;Timestamp expires=null;
            try(PreparedStatement query=views.get(1).prepareStatement("SELECT OWNER_KEY,DELETE_RESULT_HASH,DELETE_RESULT_EXPIRES FROM CLUSTER_OPERATIONS WHERE ID=? AND ENDPOINT='/deleteUser' AND STATE='COMMITTED'")){
                query.setString(1,operation);try(ResultSet rows=query.executeQuery()){
                    if(rows.next()){owner=rows.getString(1);stored=rows.getString(2);expires=rows.getTimestamp(3);}
                }
            }
            if(owner==null||stored==null||expires==null||!expires.toInstant().isAfter(databaseNow(views.get(1)))
                    ||!DeletionResultCapability.equal(stored,DeletionResultCapability.digest(required(config,"idempotency.secret"),cookie,operation,owner)))
                return new JSONObject().put("unavailable",true);
            String[] parts=owner.split(":",-1);
            if(parts.length!=3||!parts[0].equals("1")||!parts[1].matches("[1-9][0-9]{0,9}")||!RequestIdentity.validGeneration(parts[2]))throw new SQLException("Invalid stored deletion owner");
            int id;try{id=Integer.parseInt(parts[1]);}catch(NumberFormatException invalid){throw new SQLException("Invalid stored deletion owner");}
            for(Connection database:views.values())try(PreparedStatement query=database.prepareStatement("SELECT GENERATION FROM USERS WHERE ID=?")){
                query.setInt(1,id);try(ResultSet rows=query.executeQuery()){
                    if(rows.next()&&parts[2].equalsIgnoreCase(rows.getString(1)))throw new SQLException("Committed deletion still has its original identity");
                }
            }
            JSONObject terminal=new JSONObject().put("status","success").put("operationId",operation).put("operationStatus","committed").put("accountDeleted",true);
            return new JSONObject().put("status","success").put("operationId",operation).put("state","committed").put("result",terminal);
        });
        return confirmed.optBoolean("unavailable",false)?null:confirmed;
    }

    private static JSONObject status(Connection database, Model.User user, String operation) throws SQLException {
        verifyUser(database, user);
        JSONObject response = new JSONObject().put("status", "success").put("operationId", operation).put("state", "absent");
        try (PreparedStatement query = database.prepareStatement("SELECT O.STATE,R.PAYLOAD FROM CLUSTER_OPERATIONS O LEFT JOIN CLUSTER_RESULTS R ON R.OPERATION_ID=O.ID WHERE O.ID=? AND O.OWNER_KEY=?")) {
            query.setString(1, operation); query.setString(2, FinanceCluster.owner(user));
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    response.put("state", switch (rows.getString(1)) { case "COMMITTED" -> "committed"; case "ABORTED" -> "aborted"; default -> "pending"; });
                    if (rows.getString(1).equals("COMMITTED")) {
                        if (rows.getString(2) == null) response.put("resultUnavailable",true);
                        else response.put("result", new JSONObject(rows.getString(2)));
                    }
                }
            }
        }
        return response;
    }
}
