package DatabaseService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

/**
 * Two logical schemas, one physical InnoDB transaction. No proxy returned to a
 * DAO exposes the physical connection or owns its commit, rollback or lifetime.
 * Statements bind their schema both when prepared and when executed: preparing
 * a statement and then using the other schema must not redirect its execution.
 */
final class AtomicSchemaSession implements AutoCloseable {
    private final Connection physical;
    private final Map<Integer, Connection> views;
    private final Set<Statement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean rollbackOnly;

    AtomicSchemaSession(Connection physical, String canonical, String peer) throws SQLException {
        if (canonical == null || peer == null || !canonical.matches("[A-Za-z0-9_]+")
                || !peer.matches("[A-Za-z0-9_]+") || canonical.equalsIgnoreCase(peer))
            throw new SQLException("Distinct explicit logical schemas are required");
        if (physical.getAutoCommit()) throw new SQLException("Outer atomic transaction is required");
        this.physical = physical;
        views = Map.of(1, connection(canonical), 2, connection(peer));
    }

    Map<Integer, Connection> connections() { return views; }
    boolean rollbackOnly() { return rollbackOnly; }

    private void requireActive() throws SQLException {
        if (!active || Thread.currentThread() != owner)
            throw new SQLException("Atomic transaction scope is closed or belongs to another thread");
    }

    private void select(String catalog) throws SQLException {
        requireActive();
        if (!catalog.equals(physical.getCatalog())) physical.setCatalog(catalog);
    }

    private static Object call(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static Object identity(Object proxy, String name, Object[] arguments, String label) {
        return switch (name) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> label;
            default -> null;
        };
    }

    /** DDL, comments and external/session effects do not enter borrowed JDBC. */
    private static void safeSql(String sql) throws SQLException {
        AtomicSql.verify(sql);
    }

    private Connection connection(String catalog) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
            String name = method.getName();
            if (Set.of("equals", "hashCode", "toString").contains(name)) return identity(proxy, name, arguments, "Atomic schema connection");
            if (name.equals("isClosed")) return !active || physical.isClosed();
            requireActive();
            switch (name) {
                case "commit", "close" -> { return null; }
                case "rollback" -> {
                    if (arguments != null && arguments.length != 0) throw new SQLException("Savepoints belong to the atomic owner");
                    rollbackOnly = true; return null;
                }
                case "getAutoCommit" -> { return false; }
                case "setAutoCommit" -> {
                    if (Boolean.TRUE.equals(arguments[0])) throw new SQLException("Atomic work cannot enable auto-commit");
                    return null;
                }
                case "setReadOnly", "setTransactionIsolation" -> { return null; }
                case "getCatalog" -> { return catalog; }
                case "getSchema" -> { return null; } // MySQL databaseTerm=CATALOG
                case "isWrapperFor" -> { return false; }
                case "setCatalog", "setSchema", "unwrap", "abort", "setSavepoint", "releaseSavepoint", "prepareCall", "setClientInfo", "setTypeMap", "setNetworkTimeout" ->
                    throw new SQLException("Physical connection control belongs to the atomic transaction owner");
                case "prepareStatement" -> {
                    safeSql((String) arguments[0]); select(catalog);
                    Statement statement = (Statement) call(physical, method, arguments);
                    return statement(statement, (Connection) proxy, catalog, true);
                }
                case "createStatement" -> {
                    select(catalog);
                    return statement((Statement) call(physical, method, arguments), (Connection) proxy, catalog, false);
                }
                case "getMetaData" -> {
                    select(catalog); DatabaseMetaData metadata = physical.getMetaData();
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (view, operation, values) -> {
                        String key = operation.getName();
                        if (Set.of("equals", "hashCode", "toString").contains(key)) return identity(view, key, values, "Atomic schema metadata");
                        requireActive();
                        if (key.equals("getConnection")) return proxy;
                        if (key.equals("unwrap")) throw new SQLException("Physical metadata cannot be unwrapped");
                        if (key.equals("isWrapperFor")) return false;
                        select(catalog);
                        Object result = call(metadata, operation, values);
                        return result instanceof ResultSet rows ? rows(rows, null) : result;
                    });
                }
                case "nativeSQL" -> { safeSql((String) arguments[0]); return call(physical, method, arguments); }
                case "getWarnings", "clearWarnings", "isValid", "isReadOnly", "getTransactionIsolation", "getHoldability", "getNetworkTimeout", "getClientInfo", "getTypeMap" -> {
                    return call(physical, method, arguments);
                }
                default -> throw new SQLFeatureNotSupportedException("Unsupported atomic schema connection operation: " + name);
            }
        });
    }

    private Object statement(Statement statement, Connection connection, String catalog, boolean prepared) {
        statements.add(statement);
        Class<?> type = prepared ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            String name = method.getName();
            if (Set.of("equals", "hashCode", "toString").contains(name)) return identity(proxy, name, arguments, "Atomic schema statement");
            if (name.equals("isClosed")) return !active || statement.isClosed();
            requireActive();
            if (name.equals("getConnection")) return connection;
            if (name.equals("unwrap")) throw new SQLException("Physical statement cannot be unwrapped");
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("close")) { statements.remove(statement); statement.close(); return null; }
            if (name.startsWith("execute") || name.equals("addBatch")) {
                if (arguments != null && arguments.length > 0 && arguments[0] instanceof String sql) {
                    // JDBC forbids SQL strings on PreparedStatement; do not let
                    // its inherited Statement overload evade its prepared SQL.
                    if (prepared) throw new SQLException("Prepared SQL cannot be replaced");
                    safeSql(sql);
                }
                select(catalog);
            }
            Object result = call(statement, method, arguments);
            return result instanceof ResultSet rows ? rows(rows, (Statement) proxy) : result;
        });
    }

    private ResultSet rows(ResultSet result, Statement statement) {
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
            String name = method.getName();
            if (Set.of("equals", "hashCode", "toString").contains(name)) return identity(proxy, name, arguments, "Atomic schema result");
            if (name.equals("isClosed")) return !active || result.isClosed();
            requireActive();
            if (name.equals("getStatement")) return statement;
            if (name.equals("unwrap")) throw new SQLException("Physical result cannot be unwrapped");
            if (name.equals("isWrapperFor")) return false;
            return call(result, method, arguments);
        });
    }

    @Override public void close() throws SQLException {
        requireActive();
        SQLException failure = null;
        for (Statement statement : statements) try { statement.close(); }
        catch (SQLException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        statements.clear(); active = false;
        if (failure != null) throw failure;
    }
}
