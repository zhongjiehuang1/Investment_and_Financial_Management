package DatabaseService;

import Controller.ApiSupport;
import com.mysql.cj.jdbc.MysqlXADataSource;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import javax.sql.XADataSource;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Explicit real-MySQL rehearsal; never reads production configuration. */
public final class CurrentMySqlValidationHarness {
    private CurrentMySqlValidationHarness() { }

    public static void main(String[] arguments) {
        String stage = "validate-input";
        List<String> created = new ArrayList<>();
        Path store = null;
        boolean validated = false;
        try {
            require(arguments.length == 1 && "--run-isolated-validation".equals(arguments[0]), "Explicit rehearsal flag required");
            String base = System.getenv("IAFM_VALIDATION_MYSQL_BASE");
            require(base != null && base.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{1,5}/"), "Disposable loopback endpoint required");
            String user = System.getenv("IAFM_VALIDATION_MYSQL_USER");
            String password = System.getenv("IAFM_VALIDATION_MYSQL_PASSWORD");
            require(user != null && !user.isBlank() && password != null, "Explicit disposable database credentials required");
            String scratch = System.getenv("IAFM_VALIDATION_WORK_DIR");
            require(scratch != null && Path.of(scratch).isAbsolute(), "Absolute rehearsal work directory required");
            String identifier = "finance_validation_" + UUID.randomUUID().toString().replace("-", "");
            String[] schemas = {identifier + "_1", identifier + "_2"};
            String[] urls = {base + schemas[0] + "?serverTimezone=UTC", base + schemas[1] + "?serverTimezone=UTC"};
            stage = "verify-version";
            try (Connection admin = DriverManager.getConnection(base, user, password); Statement sql = admin.createStatement()) {
                require("MySQL".equals(admin.getMetaData().getDatabaseProductName()), "MySQL required");
                String version = admin.getMetaData().getDatabaseProductVersion();
                require(version.startsWith("26.10."), "MySQL 26.10 required");
                System.out.println("PASS server-version=" + version.replaceAll("[^0-9A-Za-z._-]", "_"));
                stage = "create-disposable-schemas";
                for (String schema : schemas) {
                    require(schema.matches("finance_validation_[0-9a-f]{32}_[12]"), "Unexpected rehearsal schema name");
                    sql.executeUpdate("CREATE DATABASE `" + schema + "`");
                    created.add(schema);
                }
            }
            stage = "foreign-key-safety";
            try (Connection first = DriverManager.getConnection(urls[0], user, password)) {
                verifyForeignKeySafety(first);
            }
            System.out.println("PASS cascading-foreign-key-rejected restrictive-foreign-key-accepted data-preserved");
            Path work = Path.of(scratch);
            Files.createDirectories(work);
            store = Files.createTempDirectory(work, "mysql26-xa-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Properties configuration = new Properties();
            configuration.setProperty("objectStore.absolutePath", store.toString());
            configuration.setProperty("nodeIdentifier", "mysql26-validation");
            configuration.setProperty("ingress.token", UUID.randomUUID().toString());
            configuration.setProperty("idempotency.secret", UUID.randomUUID().toString() + UUID.randomUUID());
            Map<Integer, XADataSource> sources = new TreeMap<>();
            String ddl = Files.readString(Path.of("ops/cluster/finance-schema.sql")).replaceAll("(?m)^--.*$", "");
            stage = "initialize-disposable-finance-schema";
            for (int node : List.of(1, 2)) {
                String url = urls[node - 1];
                configuration.setProperty("node" + node + ".url", url);
                configuration.setProperty("node" + node + ".user", user);
                configuration.setProperty("node" + node + ".password", password);
                MysqlXADataSource source = new MysqlXADataSource();
                source.setUrl(FinanceCluster.boundedUrl(url)); source.setUser(user); source.setPassword(password);
                sources.put(node, source);
                try (Connection connection = DriverManager.getConnection(url, user, password); Statement sql = connection.createStatement()) {
                    for (String statement : ddl.split(";")) if (!statement.isBlank()) sql.execute(statement);
                    sql.execute("CREATE TABLE TEST_BALANCE(ID INT PRIMARY KEY, AMOUNT INT NOT NULL) ENGINE=InnoDB");
                    sql.execute("INSERT INTO TEST_BALANCE VALUES(1,0)");
                    ReplicationSchemaSafety.verify(connection);
                }
            }
            // The test exercises the legacy coordinator against a disposable engine.
            // It does not simulate replicated writer authority or certify failover.
            System.setProperty("iafm.coordinator.mode", "active");
            System.setProperty("iafm.cluster.health.required", "false");
            System.setProperty("iafm.cluster.health.url", "");
            ClusterStoreProvision.provision(configuration);
            stage = "application-xa-commit-rollback-and-idempotency";
            try (FinanceCluster cluster = new FinanceCluster(configuration, sources)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (!cluster.recoveryReady() && System.nanoTime() < deadline) Thread.sleep(25);
                require(cluster.recoveryReady(), "Coordinator recovery not ready");
                String operation = UUID.randomUUID().toString();
                JSONObject payload = new JSONObject().put("amount", 5);
                FinanceCluster.Work increment = () -> {
                    for (Connection connection : ShardScope.current().connections().values()) {
                        try (Statement sql = connection.createStatement()) { require(sql.executeUpdate("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+5 WHERE ID=1") == 1, "Missing fixture balance"); }
                        connection.commit(); // Deferred by the actual application ShardScope.
                    }
                    return new JSONObject().put("balance", 5);
                };
                JSONObject committed = cluster.run(request(operation, configuration), null, payload, true, increment);
                require("committed".equals(committed.getString("operationStatus")), "Missing committed receipt");
                JSONObject replay = cluster.run(request(operation, configuration), null, payload, true, () -> { throw new AssertionError("Idempotent replay executed business work"); });
                require(FinanceCluster.canonical(committed).equals(FinanceCluster.canonical(replay)), "Replay receipt changed");
                boolean conflict = false;
                try { cluster.run(request(operation, configuration), null, new JSONObject().put("amount", 7), true, increment); }
                catch (ApiSupport.Failure expected) { conflict = expected.httpStatus() == 409; }
                require(conflict, "Changed payload under same operation ID accepted");
                String rollback = UUID.randomUUID().toString();
                boolean aborted = false;
                try {
                    cluster.run(request(rollback, configuration), null, payload, true, () -> {
                        increment.run(); ShardScope.current().markRollbackOnly(); return new JSONObject();
                    });
                } catch (ApiSupport.Failure expected) { aborted = expected.httpStatus() == 409; }
                require(aborted, "Rollback-only transaction accepted");
                require("committed".equals(cluster.status(null, operation).getString("state")), "Committed result lookup failed");
                require("aborted".equals(cluster.status(null, rollback).getString("state")), "Aborted result lookup failed");
                for (String url : urls) try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    require(scalar(connection, "SELECT AMOUNT FROM TEST_BALANCE WHERE ID=1") == 5, "Replay duplicated a write or rollback leaked");
                    require(scalar(connection, "SELECT COUNT(*) FROM CLUSTER_RECEIPTS") == 1, "Unexpected participant receipts");
                }
            }
            System.out.println("PASS application-xa-two-branch-commit whole-operation-rollback idempotent-replay changed-payload-conflict result-lookup");
            stage = "cleanup-disposable-schemas";
            try (Connection admin = DriverManager.getConnection(base, user, password); Statement sql = admin.createStatement()) {
                for (String schema : created) sql.executeUpdate("DROP DATABASE `" + schema + "`");
            }
            try (var files = Files.walk(store)) {
                for (Path item : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
            }
            validated = true;
            System.out.println("PASS cleanup-completed scope=one-MySQL-engine-two-disposable-logical-schemas no-replication-or-failover-claim");
        } catch (Exception | AssertionError failure) {
            String diagnostic = failure instanceof SQLException sql ? " SQLState=" + sql.getSQLState() + " errorCode=" + sql.getErrorCode() : "";
            System.err.println("FAIL stage=" + stage + " type=" + failure.getClass().getSimpleName() + diagnostic);
            for (String schema : created) System.err.println("RETAINED disposable-schema=" + schema);
            if (store != null) System.err.println("RETAINED synthetic-transaction-store=" + store);
            // Do not dump exception messages, URLs, credentials or private properties.
        }
        if (!validated) System.exit(1);
    }

    private static void verifyForeignKeySafety(Connection connection) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE FK_PARENT(ID INT PRIMARY KEY) ENGINE=InnoDB");
            sql.execute("CREATE TABLE FK_CHILD(ID INT PRIMARY KEY, PARENT_ID INT, CONSTRAINT FK_SYNTHETIC_PARENT FOREIGN KEY(PARENT_ID) REFERENCES FK_PARENT(ID) ON DELETE CASCADE) ENGINE=InnoDB");
            sql.execute("INSERT INTO FK_PARENT VALUES(1)"); sql.execute("INSERT INTO FK_CHILD VALUES(1,1)");
            boolean refused = false;
            try { ReplicationSchemaSafety.verify(connection); }
            catch (SQLException expected) { refused = expected.getMessage().contains("cascading foreign keys"); }
            require(refused, "Cascading foreign key was accepted");
            require(scalar(connection, "SELECT COUNT(*) FROM FK_CHILD") == 1, "Safety check changed data");
            connection.setAutoCommit(false);
            sql.executeUpdate("DELETE FROM FK_PARENT WHERE ID=1");
            require(scalar(connection, "SELECT COUNT(*) FROM FK_CHILD") == 0, "Expected synthetic cascade not exercised");
            connection.rollback(); connection.setAutoCommit(true);
            require(scalar(connection, "SELECT COUNT(*) FROM FK_CHILD") == 1, "Database rollback did not preserve child");
            sql.execute("ALTER TABLE FK_CHILD DROP FOREIGN KEY FK_SYNTHETIC_PARENT, ADD CONSTRAINT FK_SYNTHETIC_RESTRICTED FOREIGN KEY(PARENT_ID) REFERENCES FK_PARENT(ID) ON DELETE RESTRICT ON UPDATE RESTRICT");
            ReplicationSchemaSafety.verify(connection);
            boolean restricted = false;
            try { sql.executeUpdate("DELETE FROM FK_PARENT WHERE ID=1"); }
            catch (SQLException expected) { restricted = "23000".equals(expected.getSQLState()); }
            require(restricted, "Restrictive parent deletion was accepted");
            require(scalar(connection, "SELECT COUNT(*) FROM FK_PARENT") == 1 && scalar(connection, "SELECT COUNT(*) FROM FK_CHILD") == 1, "Constraint migration changed fixture rows");
        }
    }

    private static long scalar(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); var rows = sql.executeQuery(query)) {
            require(rows.next(), "Missing fixture result"); return rows.getLong(1);
        }
    }

    private static HttpServletRequest request(String operation, Properties configuration) {
        return (HttpServletRequest) Proxy.newProxyInstance(CurrentMySqlValidationHarness.class.getClassLoader(), new Class[]{HttpServletRequest.class}, (proxy, method, arguments) -> switch (method.getName()) {
            case "getHeader" -> switch ((String) arguments[0]) {
                case "X-IAFM-Operation-Id" -> operation;
                case "X-IAFM-Ingress-Node" -> "2";
                case "X-IAFM-Ingress-Token" -> configuration.getProperty("ingress.token");
                default -> null;
            };
            case "getServletPath" -> "/isolatedValidationMutation";
            default -> null;
        });
    }

    private static void require(boolean condition, String failure) {
        if (!condition) throw new IllegalStateException(failure);
    }
}
