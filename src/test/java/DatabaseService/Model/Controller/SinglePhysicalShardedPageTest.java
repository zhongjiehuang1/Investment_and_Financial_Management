package DatabaseService.Model.Controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real local JDBC plus a protocol guard rejecting overlapping physical results. */
public class SinglePhysicalShardedPageTest {
    private Connection database;
    private ProtocolGuard guard;
    private SinglePhysicalShardedPageValidation.Scope scope;

    @Before public void openOnePhysicalConnection() throws Exception {
        database = DriverManager.getConnection("jdbc:h2:mem:paged_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        try (Statement statement = database.createStatement()) {
            statement.execute("CREATE SCHEMA PAGE_FIRST"); statement.execute("CREATE SCHEMA PAGE_SECOND");
        }
        database.setAutoCommit(false);
        guard = new ProtocolGuard(database);
        scope = new SinglePhysicalShardedPageValidation.Scope(guard.connection(), "PAGE_FIRST", "PAGE_SECOND");
    }

    @After public void closeOnePhysicalConnection() throws Exception {
        try { if (scope != null) scope.close(); }
        finally { if (database != null) { database.rollback(); database.close(); } }
    }

    @Test public void mergesTwoNonemptySchemasAcrossFixedWindowsAndDeepPages() throws Exception {
        for (int page : new int[]{1, 2, 3, 4}) {
            SinglePhysicalShardedPageValidation.assertPage(scope.canonical, page, 100, false);
            assertClosed();
        }
        assertTrue("The fixture must actually cross multiple windows", guard.windowQueries > 4);
        assertEquals(ShardedPage.FETCH_WINDOW, guard.maximumResultRows);
        assertEquals(1, guard.maximumLiveResults);
        assertFalse(database.getAutoCommit());
    }

    @Test public void preservesNullOrderingBinaryTextDescendingAndSourceTieOwnership() throws Exception {
        for (boolean reverse : new boolean[]{false, true}) {
            SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 1, 5, reverse);
            SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 2, 100, reverse);
            SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 3, 100, reverse);
            assertClosed();
        }
    }

    @Test public void closesResultAndStatementWhenSecondSchemaDecoderFails() throws Exception {
        AtomicInteger decoded = new AtomicInteger();
        SQLException failure = assertThrows(SQLException.class, () -> ShardedPage.read(scope.canonical,
                SinglePhysicalShardedPageValidation.COLUMNS, SinglePhysicalShardedPageValidation.constants(), List.of(1, 1),
                SinglePhysicalShardedPageValidation.ORDER, 1, 100, (rows, columns) -> {
                    if (decoded.incrementAndGet() == ShardedPage.FETCH_WINDOW + 1) throw new SQLException("Synthetic decoder failure", "TEST1");
                    return SinglePhysicalShardedPageValidation.decode(rows, columns);
                }));
        assertEquals("TEST1", failure.getSQLState());
        assertClosed();
        SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 1, 5, false);
        assertClosed();
    }

    @Test public void closesStatementWhenLaterWindowQueryFailsAndLeavesConnectionUsable() throws Exception {
        guard.failWindow = 3;
        SQLException failure = assertThrows(SQLException.class,
                () -> SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 2, 100, false));
        assertEquals("TEST2", failure.getSQLState());
        assertClosed(); guard.failWindow = -1;
        SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 1, 5, false);
        assertClosed();
    }

    @Test public void closesResourcesOnRuntimeDecoderFailureAndRejectsInvalidPagination() throws Exception {
        assertThrows(IllegalStateException.class, () -> ShardedPage.read(scope.canonical,
                SinglePhysicalShardedPageValidation.COLUMNS, SinglePhysicalShardedPageValidation.constants(), List.of(1, 1),
                SinglePhysicalShardedPageValidation.ORDER, 1, 5, (rows, columns) -> { throw new IllegalStateException("Synthetic decode"); }));
        assertClosed();
        assertThrows(IllegalArgumentException.class, () -> SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 0, 5, false));
        assertThrows(IllegalArgumentException.class, () -> SinglePhysicalShardedPageValidation.assertPage(scope.canonical, 1, 101, false));
        assertClosed();
    }

    private void assertClosed() {
        assertEquals(0, guard.liveResults); assertEquals(0, guard.liveStatements);
        assertEquals(0, guard.overlappingCommands);
    }

    private static Object call(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class ProtocolGuard {
        final Connection physical;
        int liveStatements, liveResults, maximumLiveResults, maximumResultRows, overlappingCommands, windowQueries;
        int failWindow = -1;
        ProtocolGuard(Connection physical) { this.physical = physical; }
        void idle() throws SQLException {
            if (liveResults != 0) { overlappingCommands++; throw new SQLException("A physical result is still active", "ACTIVE"); }
        }
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("setCatalog")) { idle(); physical.setSchema((String) arguments[0]); return null; }
                if (name.equals("getCatalog")) return physical.getSchema();
                if (name.equals("prepareStatement")) {
                    idle(); PreparedStatement statement = (PreparedStatement) call(physical, method, arguments);
                    return statement(statement, ((String) arguments[0]).contains(" LIMIT ? OFFSET ?"));
                }
                return call(physical, method, arguments);
            });
        }
        PreparedStatement statement(PreparedStatement physicalStatement, boolean window) {
            liveStatements++;
            boolean[] closed = {false};
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("close")) {
                    if (!closed[0]) { closed[0] = true; try { physicalStatement.close(); } finally { liveStatements--; } }
                    return null;
                }
                if (name.equals("executeQuery")) {
                    idle();
                    if (window && ++windowQueries == failWindow) throw new SQLException("Synthetic window query failure", "TEST2");
                    return result((ResultSet) call(physicalStatement, method, arguments));
                }
                return call(physicalStatement, method, arguments);
            });
        }
        ResultSet result(ResultSet physicalResult) {
            liveResults++; maximumLiveResults = Math.max(maximumLiveResults, liveResults);
            boolean[] closed = {false}; int[] rows = {0};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("close")) {
                    if (!closed[0]) { closed[0] = true; try { physicalResult.close(); } finally { liveResults--; } }
                    return null;
                }
                Object value = call(physicalResult, method, arguments);
                if (name.equals("next") && Boolean.TRUE.equals(value)) maximumResultRows = Math.max(maximumResultRows, ++rows[0]);
                return value;
            });
        }
    }
}
