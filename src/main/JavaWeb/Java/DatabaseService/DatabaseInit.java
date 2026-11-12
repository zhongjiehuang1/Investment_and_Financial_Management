package DatabaseService;

import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Shared connection utilities; each operation owns its connection and transaction. */
@WebListener
public class DatabaseInit implements ServletContextListener {
    private static final ThreadLocal<Boolean> INITIALIZING = new ThreadLocal<>();
    private static volatile boolean clusterSchemaReady;
    private volatile boolean stopping;
    private java.util.concurrent.ScheduledExecutorService initializer;
    public static boolean clusterSchemaReady() { return clusterSchemaReady; }
    @Override
    public void contextInitialized(ServletContextEvent event) {
        if(FinanceCluster.enabled()) {
            clusterSchemaReady=false;
            initializer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"finance-schema-readiness");thread.setDaemon(true);return thread;});
            initializer.scheduleWithFixedDelay(()->retryInitialize(event),0,5,java.util.concurrent.TimeUnit.SECONDS);
            return;
        }
        // An omitted/stale cluster environment must never fall back to the
        // canonical production schema. Static pages can still load while all
        // data access fails closed through openConnection's isolation checks.
        if (setting("iafm.db.jndi", "IAFM_DB_JNDI", null) == null
                && !LegacyDatabaseIsolation.allowed(setting("iafm.db.url", "IAFM_DB_URL", "jdbc:mysql://localhost:3306/IAFM"))) {
            event.getServletContext().log("Finance database access is disabled: production requires the atomic cluster configuration.");
            return;
        }
        INITIALIZING.set(Boolean.TRUE);
        try { initialize(event); }
        finally { INITIALIZING.remove(); }
    }
    private synchronized void retryInitialize(ServletContextEvent event) {
        if(stopping||clusterSchemaReady)return;
        INITIALIZING.set(Boolean.TRUE);
        try { initialize(event);if(!stopping)clusterSchemaReady=true; }
        catch(RuntimeException unavailable){event.getServletContext().log("Finance schema readiness is unavailable; initialization will retry. "+initializationDiagnostic(unavailable));}
        finally { INITIALIZING.remove(); }
    }

    /** Only constant class/code/frame metadata; never exception messages or SQL values. */
    static String initializationDiagnostic(Throwable failure){
        StringBuilder result=new StringBuilder();int depth=0;
        for(Throwable cause=failure;cause!=null&&depth++<8;cause=cause.getCause()){
            if(!result.isEmpty())result.append("; ");
            result.append(cause.getClass().getSimpleName());
            if(cause instanceof SQLException sql)result.append(" SQLState=").append(sql.getSQLState()).append(" errno=").append(sql.getErrorCode());
            for(StackTraceElement frame:cause.getStackTrace())if(frame.getClassName().startsWith("DatabaseService.")){
                result.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());break;
            }
        }
        return result.toString();
    }

    private void initialize(ServletContextEvent event) {
        // Schema verification includes ID repair writes. With a replicated
        // decision store it may run only on the locally authorized writer.
        java.util.Properties cluster=FinanceCluster.enabled()?clusterConfiguration():null;
        boolean replicated=cluster!=null&&replicatedDecisionStore(cluster);
        if(cluster!=null&&!replicated)FinanceCluster.requireLegacyIsolation(cluster);
        String authority=replicated?Controller.ClusterAvailability.recoveryAuthorityIdentity():null;
        if (replicated && authority==null) throw new IllegalStateException("Replicated recovery authority is unavailable.");
        new RegisterDriver().registerDriver();
        if (replicated) {
            initializeReplicated(event,cluster,authority);
            return;
        }
        if (Boolean.parseBoolean(setting("iafm.schema.autoInit", "IAFM_SCHEMA_AUTO_INIT", "true"))) {
            new CreateAllTables().createAllTables();
        } else {
            // Production migrations run separately with a maintenance identity. The runtime needs DML only.
            try (Connection connection = openConnection(); java.sql.Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT JOURNAL_ID FROM INCOME_AND_EXPENDITURE_DATA WHERE 1 = 0").close();
                statement.executeQuery("SELECT ID FROM LEDGER_JOURNALS WHERE 1 = 0").close();
                statement.executeQuery("SELECT JOURNAL_ID FROM LEDGER_ENTRIES WHERE 1 = 0").close();
                statement.executeQuery("SELECT OBSERVED_THROUGH FROM ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES WHERE 1 = 0").close();
                try (ResultSet inflationLock = statement.executeQuery("SELECT ID FROM INFLATION_SERIES_LOCK WHERE ID = 1")) {
                    if (!inflationLock.next()) throw new SQLException("Inflation schema migration is required.");
                }
                CreateAllTables.verifyIdSchema(connection);
                java.sql.DatabaseMetaData metadata = connection.getMetaData();
                event.getServletContext().log("IAFM database driver: " + metadata.getDriverName() + " " + metadata.getDriverVersion());
            } catch (SQLException unavailable) {
                throw new IllegalStateException("Database migrations must be applied before startup.", unavailable);
            }
        }
        new SetIds().setAllIds();
    }

    private static java.util.Properties clusterConfiguration() {
        String path=setting("iafm.cluster.config", "IAFM_CLUSTER_CONFIG", "/etc/iafm/cluster.properties");
        java.util.Properties properties=new java.util.Properties();
        try (var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(path))) {
            properties.load(reader);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cluster configuration is unavailable.", failure);
        }
        return properties;
    }

    private static boolean replicatedDecisionStore(java.util.Properties properties) {
        if(AtomicFinanceTransactions.enabled(properties))return true;
        String backend=properties.getProperty("objectStore.backend", "file");
        if ("jdbc".equals(backend)) return true;
        if ("file".equals(backend)) return false;
        throw new IllegalStateException("Unknown decision-store backend.");
    }

    private void initializeReplicated(ServletContextEvent event,java.util.Properties properties,String authority) {
        if (Boolean.parseBoolean(setting("iafm.schema.autoInit", "IAFM_SCHEMA_AUTO_INIT", "true")))
            throw new IllegalStateException("Replicated startup requires separately applied migrations.");
        if(AtomicFinanceTransactions.enabled(properties)) {
            try {
                new AtomicFinanceTransactions(properties).initialize();
                event.getServletContext().log("IAFM atomic schema/ID verification completed with private durability confirmation.");
            } catch(Exception failure) {
                throw new IllegalStateException("Atomic schema verification or ID repair failed.",failure);
            }
            return;
        }
        throw new IllegalStateException("Replicated finance requires transaction.backend=atomic; legacy XA startup is disabled.");
    }

    static void verifyReplicatedSchema(Connection connection) throws SQLException {
        ReplicationSchemaSafety.verify(connection);
        try(java.sql.Statement statement=connection.createStatement()) {
            statement.executeQuery("SELECT JOURNAL_ID FROM INCOME_AND_EXPENDITURE_DATA WHERE 1 = 0").close();
            statement.executeQuery("SELECT ID FROM LEDGER_JOURNALS WHERE 1 = 0").close();
            statement.executeQuery("SELECT JOURNAL_ID FROM LEDGER_ENTRIES WHERE 1 = 0").close();
            statement.executeQuery("SELECT OBSERVED_THROUGH FROM ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES WHERE 1 = 0").close();
            try(ResultSet lock=statement.executeQuery("SELECT ID FROM INFLATION_SERIES_LOCK WHERE ID = 1")) {
                if(!lock.next())throw new SQLException("Inflation schema migration is required.");
            }
            CreateAllTables.verifyIdSchema(connection);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        stopping=true;
        if(initializer!=null)initializer.shutdownNow();
        synchronized(this){ /* Wait until startup schema verification releases its connections. */ }
        FinanceCluster.shutdown();
        new DeregisterDriver().deregisterDriver();
        AbandonedConnectionCleanupThread.checkedShutdown();
    }

    private static String setting(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    protected static Connection openConnection() {
        if (!Boolean.TRUE.equals(INITIALIZING.get())) Controller.ClusterAvailability.requireReady();
        if (!Boolean.TRUE.equals(INITIALIZING.get()) && FinanceCluster.enabled() && !ShardScope.isActive())
            throw new Controller.ClusterAvailability.Unavailable();
        if (ShardScope.isActive()) {
            try { return ShardScope.open(false); }
            catch (SQLException failure) { throw new IllegalStateException("Distributed database access is unavailable.", failure); }
        }
        try {
            String jndi = setting("iafm.db.jndi", "IAFM_DB_JNDI", null);
            Connection connection;
            if (jndi != null) {
                DataSource source = (DataSource) new InitialContext().lookup(jndi);
                connection = source.getConnection();
            } else {
                String url = FinanceCluster.boundedUrl(setting("iafm.db.url", "IAFM_DB_URL", "jdbc:mysql://localhost:3306/IAFM"));
                LegacyDatabaseIsolation.require(url);
                String username = setting("iafm.db.user", "IAFM_DB_USER", null);
                String password = setting("iafm.db.password", "IAFM_DB_PASSWORD", "");
                if (username == null) throw new IllegalStateException("Configure IAFM_DB_USER or IAFM_DB_JNDI.");
                connection = DriverManager.getConnection(url, username, password);
            }
            try {
                // Check the actual endpoint as well, including JNDI pools. No
                // schema, identity, allocator or business statement precedes it.
                LegacyDatabaseIsolation.require(connection);
                connection.setReadOnly(false);
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(RequestIdentity.isReadRequest()
                        ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
                RequestIdentity.validateUser(connection);
                return connection;
            } catch (SQLException | RuntimeException exception) {
                // A failed identity check may already hold row locks; release them before returning a pooled connection.
                try { connection.rollback(); } catch (SQLException failure) { exception.addSuppressed(failure); }
                try { connection.close(); } catch (SQLException failure) { exception.addSuppressed(failure); }
                throw exception;
            }
        } catch (SQLException | NamingException exception) {
            throw new IllegalStateException("Database connection is unavailable.", exception);
        }
    }

    protected static Connection openPrimaryConnection() {
        if (!ShardScope.isActive()) return openConnection();
        try { return ShardScope.open(true); }
        catch (SQLException failure) { throw new IllegalStateException("Distributed identity access is unavailable.", failure); }
    }

    protected static PreparedStatement[] getPreparedStatements(int count) { return new PreparedStatement[count]; }
    protected static ResultSet[] getResultSets(int count) { return new ResultSet[count]; }

    protected static void releaseResources(Connection connection, PreparedStatement[] statements, ResultSet[] results) {
        if (results != null) for (ResultSet result : results) close(result);
        if (statements != null) for (PreparedStatement statement : statements) close(statement);
        close(connection);
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); }
        catch (Exception exception) { System.err.println("A database resource could not be closed."); }
    }

    protected static void rollback(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); }
        catch (SQLException exception) { System.err.println("A database transaction could not be rolled back."); }
    }

    /** Serialize mutations only for the same owner, including across application instances. */
    protected static void lockUser(Connection connection, int userId) throws SQLException {
        if (userId <= 0) throw new IllegalArgumentException("A valid user is required.");
        if (ShardScope.isActive()) {
            String generation = null;
            for (Connection participant : ShardScope.connections(connection)) {
                try (PreparedStatement statement = participant.prepareStatement("SELECT GENERATION FROM USERS WHERE ID = ? FOR UPDATE")) {
                    statement.setInt(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("User identity shadow is missing.");
                        if (generation == null) generation = result.getString(1);
                        else if (!generation.equalsIgnoreCase(result.getString(1))) throw new RequestIdentity.StaleIdentity(true);
                    }
                }
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM USERS WHERE ID = ? FOR UPDATE")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("User does not exist.");
            }
        }
    }

    static final java.util.List<String> ID_TABLES = java.util.List.of(
            "ALL_INVESTMENT_ASSETS", "ALL_NON_INVESTMENT_ASSETS",
            "ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES", "BASIC_VARIABLES_AND_DATA",
            "INCOME_AND_EXPENDITURE_DATA", "USERS");

    static void requireIdTable(String table) {
        if (!ID_TABLES.contains(table)) throw new IllegalArgumentException("Unsupported recyclable ID table.");
    }

    /** Lock owner first, then every required allocator in this canonical order before changing IDs. */
    public static void lockIdTables(Connection connection, String... tables) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("ID allocation requires an explicit business transaction.");
        java.util.SortedSet<String> ordered = new java.util.TreeSet<>(java.util.Arrays.asList(tables));
        for (String table : ordered) {
            requireIdTable(table);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME=? FOR UPDATE")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("ID schema migration is required before writes.");
                }
            }
        }
    }

    /** Claim an ID in the insertion transaction. Insert it before allocating again for the same table. */
    protected static int nextId(Connection connection, String table) throws SQLException {
        lockIdTables(connection, table);
        String recorder = "ID_RECORDER_" + table;
        while (true) {
            Integer point = null, first = null, last = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT MIN(TABLE_ID) FROM " + recorder + " WHERE TABLE_ID>0");
                 ResultSet result = statement.executeQuery()) {
                if (result.next()) { int value = result.getInt(1); if (!result.wasNull()) point = value; }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT FIRST_ID,LAST_ID FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=? ORDER BY FIRST_ID LIMIT 1")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) { first = result.getInt(1); last = result.getInt(2); }
                }
            }
            if (point == null && first == null) break;
            int candidate = point == null ? first : first == null ? point : Math.min(point, first);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + recorder + " WHERE TABLE_ID=?")) {
                statement.setInt(1, candidate); statement.executeUpdate();
            }
            if (first != null && candidate == first) {
                String sql = first.equals(last)
                        ? "DELETE FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=? AND FIRST_ID=?"
                        : "UPDATE ID_RECOVERY_RANGES SET FIRST_ID=? WHERE TABLE_NAME=? AND FIRST_ID=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int parameter = 1;
                    if (!first.equals(last)) statement.setInt(parameter++, candidate + 1);
                    statement.setString(parameter++, table); statement.setInt(parameter, candidate); statement.executeUpdate();
                }
            }
            // Invalid legacy recycle rows are removed, never assigned to a live or referenced record.
            if (!SetIds.isUnavailable(connection, table, candidate)) {
                rememberHighWater(connection, table, candidate);
                return candidate;
            }
        }
        long maximum;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(ID),0) FROM " + table);
             ResultSet result = statement.executeQuery()) { result.next(); maximum = result.getLong(1); }
        if (maximum < 0 || maximum >= Integer.MAX_VALUE) throw new SQLException("The integer ID range is exhausted.");
        int candidate = (int) maximum + 1;
        if (SetIds.isUnavailable(connection, table, candidate)) {
            throw new SQLException("The next ID has surviving references; repair those references before inserting.");
        }
        rememberHighWater(connection, table, candidate);
        return candidate;
    }

    static void rememberHighWater(Connection connection, String table, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ID_ALLOCATION_STATE SET HIGH_WATER=CASE WHEN HIGH_WATER<? THEN ? ELSE HIGH_WATER END WHERE TABLE_NAME=?")) {
            statement.setInt(1, id); statement.setInt(2, id); statement.setString(3, table);
            // MySQL may report zero changed rows when a recycled ID is below the high-water mark.
            statement.executeUpdate();
        }
    }

    /** Recycle only after the actual deletion and all dependent deletions, using the same transaction. */
    public static void recycleId(Connection connection, String table, int id) throws SQLException {
        if (id <= 0) throw new IllegalArgumentException("Only positive IDs can be recycled.");
        lockIdTables(connection, table);
        if (SetIds.isUnavailable(connection, table, id)) throw new SQLException("A live or referenced ID cannot be recycled.");
        rememberHighWater(connection, table, id);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT FIRST_ID FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=? AND FIRST_ID<=? AND LAST_ID>=?")) {
            statement.setString(1, table); statement.setInt(2, id); statement.setInt(3, id);
            try (ResultSet result = statement.executeQuery()) { if (result.next()) return; }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ID_RECORDER_" + table
                + " (TABLE_ID) VALUES (?) ON DUPLICATE KEY UPDATE TABLE_ID=TABLE_ID")) {
            statement.setInt(1, id); statement.executeUpdate();
        }
    }

    protected static java.sql.Date recordDate(String value) {
        try { return java.sql.Date.valueOf(java.time.LocalDate.parse(value)); }
        catch (java.time.DateTimeException | NullPointerException invalid) {
            throw new IllegalArgumentException("Use a valid date in YYYY-MM-DD format.");
        }
    }

    protected static String databaseAmount(String amount) {
        String normalized = Model.Tools.Computing.normalize(amount);
        if (normalized.length() > 15) throw new IllegalArgumentException("Amount exceeds the existing column capacity.");
        return normalized;
    }

    protected static String decimalAmount(String amount) {
        String normalized = Model.Tools.Computing.normalize(amount);
        long minor = Model.Tools.Computing.toMinorUnits(normalized);
        if (minor < -999999999999L || minor > 999999999999L) {
            throw new IllegalArgumentException("Amount exceeds the existing decimal column capacity.");
        }
        return normalized;
    }
}
