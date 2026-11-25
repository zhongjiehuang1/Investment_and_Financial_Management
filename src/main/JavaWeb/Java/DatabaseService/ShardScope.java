package DatabaseService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One already-enlisted distributed transaction. The external coordinator owns all
 * physical connections, the durable operation gate, and the final XA decision.
 * DAO commits/closes are deferred; a DAO rollback marks the whole scope rollback-only.
 */
public final class ShardScope implements AutoCloseable {
    private static final ThreadLocal<ShardScope> CURRENT = new ThreadLocal<>();
    private final Map<Integer, Connection> physical;
    private final Map<Integer, Connection> managed;
    private final int ingressNode;
    private final Model.Tools.ExchangeRateForVariousCurrencies.RatePin ratePin;
    private boolean rollbackOnly;
    private boolean closed;

    private ShardScope(Map<Integer, Connection> enlisted, int ingressNode) {
        if (!enlisted.keySet().equals(java.util.Set.of(1, 2)) || !enlisted.containsKey(ingressNode)
                || enlisted.get(1) == null || enlisted.get(2) == null || enlisted.get(1) == enlisted.get(2))
            throw new IllegalArgumentException("A scope requires two distinct, already-enlisted node connections.");
        this.ingressNode = ingressNode;
        physical = Map.copyOf(enlisted);
        Map<Integer, Connection> wrappers = new LinkedHashMap<>();
        for (int node : List.of(1, 2)) wrappers.put(node, wrap(enlisted.get(node)));
        managed = Collections.unmodifiableMap(wrappers);
        ratePin = Model.Tools.ExchangeRateForVariousCurrencies.pinForTransaction();
    }

    public static ShardScope begin(Map<Integer, Connection> enlisted, int ingressNode) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested shard scope.");
        ShardScope scope = new ShardScope(enlisted, ingressNode);
        CURRENT.set(scope);
        return scope;
    }

    public static boolean isActive() { return CURRENT.get() != null; }
    public static ShardScope current() {
        ShardScope scope = CURRENT.get();
        if (scope == null || scope.closed) throw new IllegalStateException("A distributed transaction scope is required.");
        return scope;
    }
    public int ingressNode() { return ingressNode; }
    public boolean isRollbackOnly() { return rollbackOnly; }
    public void markRollbackOnly() { rollbackOnly = true; }
    public Connection connection(int node) {
        Connection connection = managed.get(node);
        if (closed || connection == null) throw new IllegalStateException("Unavailable transaction participant.");
        return connection;
    }
    public Map<Integer, Connection> connections() { return managed; }
    /** Registration creates only a foreign-key identity shadow, never a second business account. */
    public void ensureIdentityShadow(int userId) throws SQLException {
        String generation, forename, surname, email;
        try (java.sql.PreparedStatement statement = connection(1).prepareStatement(
                "SELECT GENERATION, FORENAME, SURNAME, EMAIL_ADDRESS FROM USERS WHERE ID=? FOR UPDATE")) {
            statement.setInt(1, userId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Canonical user identity is missing.");
                generation = result.getString(1); forename = result.getString(2);
                surname = result.getString(3); email = result.getString(4);
            }
        }
        try (java.sql.PreparedStatement statement = connection(2).prepareStatement("SELECT GENERATION FROM USERS WHERE ID=? FOR UPDATE")) {
            statement.setInt(1, userId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!generation.equalsIgnoreCase(result.getString(1))) throw new SQLException("Identity shadow conflicts with canonical user.");
                    return;
                }
            }
        }
        try (java.sql.PreparedStatement statement = connection(2).prepareStatement(
                "INSERT INTO USERS(ID,GENERATION,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES(?,?,?,?,?,?)")) {
            statement.setInt(1, userId); statement.setString(2, generation); statement.setString(3, forename);
            statement.setString(4, surname); statement.setString(5, email); statement.setString(6, "!identity-shadow!");
            if (statement.executeUpdate() != 1) throw new SQLException("Identity shadow was not inserted.");
        }
    }
    public static List<Connection> connections(Connection singleNodeFallback) {
        return isActive() ? List.copyOf(current().managed.values()) : List.of(singleNodeFallback);
    }
    public static int nodeOf(Connection connection) {
        if (!isActive()) return 1;
        ShardScope scope = current();
        for (int node : List.of(1, 2))
            if (connection == scope.managed.get(node) || connection == scope.physical.get(node)) return node;
        throw new IllegalArgumentException("Connection does not belong to this transaction.");
    }
    public static boolean sameNode(Connection left, Connection right) {
        return isActive() ? nodeOf(left) == nodeOf(right) : left == right;
    }
    static Connection open(boolean canonical) throws SQLException {
        ShardScope scope = current();
        RequestIdentity.validateUser(scope.connection(1));
        return canonical ? scope.connection(1) : RequestIdentity.route(scope.connection(scope.ingressNode));
    }
    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(ShardScope.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
            String name = method.getName();
            if (name.equals("equals")) return proxy == arguments[0];
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("toString")) return "Transaction-scoped database connection";
            if (closed) throw new SQLException("Transaction scope is closed.");
            if (name.equals("commit") || name.equals("close")) return null;
            if (name.equals("rollback")) { rollbackOnly = true; return null; }
            if (name.equals("getAutoCommit")) return false;
            if (name.equals("setAutoCommit")) {
                if (Boolean.TRUE.equals(arguments[0])) throw new SQLException("Distributed transactions cannot enable auto-commit.");
                return null;
            }
            if (name.equals("setReadOnly") || name.equals("setTransactionIsolation")) return null;
            if (name.equals("abort") || name.equals("unwrap") || name.equals("setSavepoint") || name.equals("releaseSavepoint"))
                throw new SQLException("Transaction control belongs to the external coordinator.");
            if (name.equals("isWrapperFor")) return false;
            try { return method.invoke(connection, arguments); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Shard scope is not current on this thread.");
        CURRENT.remove();
        closed = true;
        ratePin.close();
    }
}
