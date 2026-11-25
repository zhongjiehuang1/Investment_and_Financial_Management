package DatabaseService.Model.Controller;

import DatabaseService.ShardScope;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Native Connector/J check using SELECT constants only. The two existing
 * catalogs supply namespace context; no business table, DDL or DML is used.
 * Credentials are read from environment and never printed.
 */
public final class SinglePhysicalShardedPageValidation {
    static final int ROWS_PER_SCHEMA = ShardedPage.FETCH_WINDOW * 2 + 9;
    static final String COLUMNS = "id, generation, label, amount";
    static final String ORDER = "CAST(amount AS DECIMAL(20,4)) ASC, label DESC, id ASC";
    static final String REVERSE_ORDER = "CAST(amount AS DECIMAL(20,4)) DESC, label ASC, id DESC";
    private record Expected(int id, int node, String label, BigDecimal amount) { }

    private SinglePhysicalShardedPageValidation() { }

    static String label(int id) {
        if (id % 11 == 0) return null;
        return new String[]{"A", "a", "z", "Å", "中文", "quoted'label"}[id % 6];
    }

    static BigDecimal amount(int id) { return id % 13 == 0 ? null : BigDecimal.valueOf(id % 7); }

    static String constants() {
        StringJoiner rows = new StringJoiner(" UNION ALL ");
        for (int id = 1; id <= ROWS_PER_SCHEMA; id++) {
            String text = label(id), money = amount(id) == null ? "NULL" : amount(id).toPlainString();
            rows.add("SELECT " + id + " AS id, '" + new UUID(0, id) + "' AS generation, "
                    + (text == null ? "NULL" : "'" + text.replace("'", "''") + "'") + " AS label, " + money + " AS amount");
        }
        return " FROM (" + rows + ") SYNTHETIC_PAGING_ROWS WHERE ? = ?";
    }

    static JSONObject decode(ResultSet rows, int columns) throws SQLException {
        if (columns != 4) throw new SQLException("Unexpected synthetic projection");
        String label = rows.getString("label"), amount = rows.getString("amount");
        return new JSONObject().put("id", rows.getInt("id")).put("generation", rows.getString("generation"))
                .put("label", label == null ? JSONObject.NULL : label).put("amount", amount == null ? JSONObject.NULL : amount);
    }

    /** Use the production two-schema adapter, not a second JDBC connection. */
    static final class Scope implements AutoCloseable {
        private final AutoCloseable session;
        private final ShardScope scope;
        final Connection canonical;
        Scope(Connection physical, String first, String second) throws Exception {
            Class<?> type = Class.forName("DatabaseService.AtomicSchemaSession");
            Constructor<?> constructor = type.getDeclaredConstructor(Connection.class, String.class, String.class);
            constructor.setAccessible(true);
            session = (AutoCloseable) constructor.newInstance(physical, first, second);
            Method method = type.getDeclaredMethod("connections"); method.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Integer, Connection> views = (Map<Integer, Connection>) method.invoke(session);
            scope = ShardScope.begin(views, 1); canonical = scope.connection(1);
        }
        @Override public void close() throws Exception { try { scope.close(); } finally { session.close(); } }
    }

    private static int compareNullable(Comparable<Object> left, Object right) {
        return left == null ? (right == null ? 0 : -1) : right == null ? 1 : left.compareTo(right);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Expected left, Expected right, boolean reverse) {
        int value = compareNullable((Comparable) left.amount, right.amount);
        if (value != 0) return reverse ? -value : value;
        byte[] a = left.label == null ? null : left.label.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.label == null ? null : right.label.getBytes(StandardCharsets.UTF_8);
        value = a == null ? (b == null ? 0 : -1) : b == null ? 1 : Arrays.compareUnsigned(a, b);
        if (value != 0) return reverse ? value : -value;
        value = Integer.compare(left.id, right.id);
        if (value != 0) return reverse ? -value : value;
        return Integer.compare(left.node, right.node);
    }

    static JSONObject assertPage(Connection connection, int page, int pageSize, boolean reverse) throws SQLException {
        JSONObject result = ShardedPage.read(connection, COLUMNS, constants(), List.of(1, 1),
                reverse ? REVERSE_ORDER : ORDER, page, pageSize, SinglePhysicalShardedPageValidation::decode);
        List<Expected> expected = new ArrayList<>();
        for (int id = 1; id <= ROWS_PER_SCHEMA; id++) for (int node : List.of(1, 2))
            expected.add(new Expected(id, node, label(id), amount(id)));
        expected.sort((a, b) -> compare(a, b, reverse));
        long offset = (long) (page - 1) * pageSize;
        int begin = (int) Math.min(expected.size(), offset), end = Math.min(expected.size(), begin + pageSize);
        JSONArray items = result.getJSONArray("items");
        require(result.getLong("total") == expected.size() && items.length() == end - begin);
        require(result.getBoolean("hasNext") == (offset + items.length() < expected.size()));
        for (int index = 0; index < items.length(); index++) {
            Expected row = expected.get(begin + index); JSONObject actual = items.getJSONObject(index);
            require(actual.getInt("id") == row.id && actual.getInt("databaseId") == row.node);
            require(actual.getString("recordKey").equals(row.node + ":" + row.id + ":" + new UUID(0, row.id)));
            require(actual.isNull("label") == (row.label == null));
            if (row.label != null) require(actual.getString("label").equals(row.label));
            require(actual.isNull("amount") == (row.amount == null));
            if (row.amount != null) require(new BigDecimal(actual.getString("amount")).compareTo(row.amount) == 0);
        }
        return result;
    }

    private static void require(boolean value) throws SQLException {
        if (!value) throw new SQLException("Synthetic pagination assertion failed");
    }

    public static void main(String[] arguments) {
        String stage = "configuration";
        try {
            String address = System.getenv("IAFM_PAGING_JDBC_URL");
            URI uri = URI.create(Objects.requireNonNull(address).substring("jdbc:".length()));
            require(address.startsWith("jdbc:mysql://") && "mysql".equals(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0
                    && uri.getRawUserInfo() == null && uri.getFragment() == null);
            String first = System.getenv("IAFM_PAGING_SCHEMA_1"), second = System.getenv("IAFM_PAGING_SCHEMA_2");
            require(first != null && second != null && first.matches("[A-Za-z0-9_]+") && second.matches("[A-Za-z0-9_]+") && !first.equalsIgnoreCase(second));
            Properties properties = new Properties();
            properties.setProperty("user", Objects.requireNonNull(System.getenv("IAFM_PAGING_JDBC_USER")));
            properties.setProperty("password", Objects.requireNonNull(System.getenv("IAFM_PAGING_JDBC_PASSWORD")));
            properties.setProperty("allowMultiQueries", "false"); properties.setProperty("autoReconnect", "false");
            properties.setProperty("useServerPrepStmts", "true"); properties.setProperty("databaseTerm", "CATALOG");
            properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "15000");
            stage = "connect";
            // Preserve the complete protected URL, including TLS mode, trust
            // material, timeout settings and the default catalog. It is never
            // printed; the two explicit schema views select their own catalogs.
            JSONObject success;
            try (Connection physical = DriverManager.getConnection(address, properties)) {
                String driver = physical.getMetaData().getDriverVersion(), server = physical.getMetaData().getDatabaseProductVersion();
                require(driver.contains("26.10.0"));
                stage = "tls";
                com.mysql.cj.jdbc.JdbcConnection mysql = physical.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);
                require("VERIFY_IDENTITY".equals(String.valueOf(mysql.getPropertySet().getEnumProperty(com.mysql.cj.conf.PropertyKey.sslMode).getValue()))
                        && mysql.getSession().isSSLEstablished());
                physical.setReadOnly(true); physical.setAutoCommit(false);
                try {
                    stage = "read-only";
                    try (Statement statement = physical.createStatement(); ResultSet rows = statement.executeQuery("SELECT @@session.transaction_read_only")) {
                        require(rows.next() && rows.getInt(1) == 1);
                    }
                    stage = "synthetic-pages";
                    try (Scope scope = new Scope(physical, first, second)) {
                        for (boolean reverse : new boolean[]{false, true})
                            for (int page : new int[]{1, 2, 3, 4}) assertPage(scope.canonical, page, 100, reverse);
                        assertPage(scope.canonical, 1, 5, false);
                    }
                    success = new JSONObject().put("status", "PASS").put("driverVersion", driver).put("serverVersion", server)
                            .put("scenarios", 9).put("syntheticRowsPerSchema", ROWS_PER_SCHEMA).put("fetchWindow", ShardedPage.FETCH_WINDOW)
                            .put("physicalConnections", 1).put("readOnlyVerified", true).put("businessTablesAccessed", false)
                            .put("ddlOrDmlExecuted", false).put("tlsVerified", true).put("sslMode", "VERIFY_IDENTITY");
                } finally { physical.rollback(); }
            }
            System.out.println(success.put("rollbackCompleted", true).put("connectionClosed", true));
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            JSONObject safe = new JSONObject().put("status", "FAIL").put("stage", stage).put("exceptionClass", cause.getClass().getSimpleName());
            if (cause instanceof SQLException sql) safe.put("sqlState", sql.getSQLState() == null ? "" : sql.getSQLState()).put("vendorCode", sql.getErrorCode());
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            safe.put("classification", message.contains("streaming result set") || message.contains("a physical result is still active") ? "ACTIVE_STREAM" : "UNCLASSIFIED");
            System.err.println(safe); System.exit(1);
        }
    }
}
