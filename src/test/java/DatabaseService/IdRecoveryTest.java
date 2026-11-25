package DatabaseService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Runs against H2 normally, or isolated generated databases in a local real MySQL instance. */
public class IdRecoveryTest extends DatabaseInit {
    private final Map<String,String> previous = new HashMap<>();
    private String mysqlBase, database;

    @Before public void setup() throws Exception {
        mysqlBase = System.getProperty("iafm.test.mysql.base");
        String url;
        if (mysqlBase == null) {
            url = "jdbc:h2:mem:id_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
            property("iafm.db.user", "sa");
        } else {
            if (!mysqlBase.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("ID tests accept only a disposable local MySQL server.");
            }
            database = "iafm_test_id_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = DriverManager.getConnection(mysqlBase, "root", ""); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE " + database);
            }
            url = mysqlBase + database;
            property("iafm.db.user", "root");
        }
        property("iafm.db.url", url); property("iafm.db.password", ""); property("iafm.db.jndi", "");
        new CreateAllTables().createAllTables(); new SetIds().setAllIds();
    }

    private void property(String key, String value) { previous.put(key, System.getProperty(key)); System.setProperty(key, value); }

    @After public void cleanup() throws Exception {
        try {
            if (database != null) try (Connection connection = DriverManager.getConnection(mysqlBase, "root", ""); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE " + database);
            } else if (previous.containsKey("iafm.db.url")) try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP ALL OBJECTS");
            }
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
            SetIds.clearReferenceCache();
        }
    }

    private static void sql(String sql) throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql); connection.commit();
        }
    }

    private static long scalar(String sql) throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next()); return row.getLong(1);
        }
    }

    private static void user(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO USERS (ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES (?,'Test','Only',?,'synthetic')")) {
            statement.setInt(1,id); statement.setString(2,"id"+id+"@example.invalid"); statement.executeUpdate();
        }
    }

    private static void user(int id) throws Exception {
        try (Connection connection = openConnection()) { user(connection,id); connection.commit(); }
    }

    private static int allocateAndInsert() throws Exception {
        try (Connection connection = openConnection()) {
            int id = nextId(connection,"USERS"); user(connection,id); connection.commit(); return id;
        }
    }

    @Test public void emptyTableStartsAtOneAndEmptyPoolUsesLiveMaximum() throws Exception {
        assertEquals(1, allocateAndInsert());
        user(10);
        assertEquals(11, allocateAndInsert());
    }

    @Test public void repairCanTargetTheOtherLogicalShardWithoutTouchingThePrimary() throws Exception {
        Assume.assumeTrue(mysqlBase==null);
        String primary=System.getProperty("iafm.db.url");
        String peer="jdbc:h2:mem:id_peer_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
        user(7);sql("INSERT INTO ID_RECORDER_USERS(TABLE_ID) VALUES(7)");
        try {
            System.setProperty("iafm.db.url",peer);
            new CreateAllTables().createAllTables();
        } finally {System.setProperty("iafm.db.url",primary);}
        try(Connection connection=DriverManager.getConnection(peer,"sa", "")) {
            connection.setAutoCommit(false);
            user(connection,7);
            try(Statement statement=connection.createStatement()){statement.executeUpdate("INSERT INTO ID_RECORDER_USERS(TABLE_ID) VALUES(7)");}
            connection.commit();
            new SetIds().setAllIds(connection);
            try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM ID_RECORDER_USERS WHERE TABLE_ID=7")) {
                assertTrue(rows.next());assertEquals(0,rows.getInt(1));
            }
        }
        assertEquals("Repairing the peer must not alter the primary",1,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS WHERE TABLE_ID=7"));
    }

    @Test public void runtimeSchemaValidationRejectsIncompleteGenerationMigration() throws Exception {
        sql(mysqlBase == null ? "DROP INDEX UQ_GEN_USERS" : "DROP INDEX UQ_GEN_USERS ON USERS");
        try (Connection connection = openConnection()) {
            assertThrows(SQLException.class, () -> CreateAllTables.verifyIdSchema(connection));
        }
        sql("CREATE UNIQUE INDEX UQ_GEN_USERS ON USERS(GENERATION)");
        sql("ALTER TABLE USERS ALTER COLUMN GENERATION DROP DEFAULT");
        try (Connection connection = openConnection()) {
            assertThrows(SQLException.class, () -> CreateAllTables.verifyIdSchema(connection));
        }
        // The maintenance entry point repairs an interrupted migration without rotating valid identities.
        sql("INSERT INTO USERS(ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD,GENERATION) VALUES (1,'Test','Only','test@example.invalid','synthetic','11111111-1111-1111-1111-111111111111')");
        String generation;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT GENERATION FROM USERS WHERE ID=1")) {
            row.next(); generation = row.getString(1);
        }
        new CreateAllTables().createAllTables();
        try (Connection connection = openConnection()) { CreateAllTables.verifyIdSchema(connection); }
        try (Connection connection = openConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT GENERATION FROM USERS WHERE ID=1")) {
            row.next(); assertEquals(generation, row.getString(1));
        }
    }

    @Test public void minimumRecycledIdIsPreferredAndOccupiedPoolEntryIsRejected() throws Exception {
        user(2); user(10);
        sql("INSERT INTO ID_RECORDER_USERS(TABLE_ID) VALUES (8),(3),(2)");
        assertEquals(3,allocateAndInsert()); assertEquals(8,allocateAndInsert()); assertEquals(11,allocateAndInsert());
        assertEquals(0,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS"));
    }

    @Test public void failedInsertRestoresClaimAndItsHighWater() throws Exception {
        sql("INSERT INTO ID_RECORDER_USERS(TABLE_ID) VALUES (7)");
        try (Connection connection = openConnection()) {
            assertEquals(7,nextId(connection,"USERS"));
            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO USERS(ID,FORENAME) VALUES (7,'Incomplete')"));
            }
            connection.rollback();
        }
        assertEquals(1,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS WHERE TABLE_ID=7"));
        assertEquals(0,scalar("SELECT HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME='USERS'"));
        assertEquals(7,allocateAndInsert());
    }

    @Test public void deleteAndRecycleRollbackTogetherAndNewGenerationIsUnique() throws Exception {
        user(7); String original;
        try (Connection connection=openConnection(); Statement statement=connection.createStatement(); ResultSet row=statement.executeQuery("SELECT GENERATION FROM USERS WHERE ID=7")) { row.next(); original=row.getString(1); }
        try (Connection connection = openConnection(); Statement statement=connection.createStatement()) {
            lockIdTables(connection,"USERS"); statement.executeUpdate("DELETE FROM USERS WHERE ID=7"); recycleId(connection,"USERS",7); connection.rollback();
        }
        assertEquals(1,scalar("SELECT COUNT(*) FROM USERS WHERE ID=7")); assertEquals(0,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS"));
        try (Connection connection = openConnection(); Statement statement=connection.createStatement()) {
            lockIdTables(connection,"USERS"); statement.executeUpdate("DELETE FROM USERS WHERE ID=7"); recycleId(connection,"USERS",7); recycleId(connection,"USERS",7); connection.commit();
        }
        assertEquals(7,allocateAndInsert());
        try (Connection connection=openConnection(); Statement statement=connection.createStatement(); ResultSet row=statement.executeQuery("SELECT GENERATION FROM USERS WHERE ID=7")) { row.next(); assertNotEquals(original,row.getString(1)); }
    }

    @Test public void startupRepairsInternalAndReliableTailGapsIdempotently() throws Exception {
        for (int id : new int[]{1,2,3,4,5,6,8,9,10}) user(id);
        sql("INSERT INTO ID_RECORDER_USERS(TABLE_ID) VALUES (-2147483648),(-12),(0),(4)");
        new SetIds().setAllIds(); new SetIds().setAllIds();
        assertEquals(3,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS"));
        assertEquals(12,scalar("SELECT HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME='USERS'"));
        assertEquals(7,allocateAndInsert()); assertEquals(11,allocateAndInsert()); assertEquals(12,allocateAndInsert());
    }

    @Test public void startupDoesNotInventTailHistoryWhenNoEvidenceSurvives() throws Exception {
        user(1); user(2); new SetIds().setAllIds();
        assertEquals(0,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS"));
        assertEquals(3,allocateAndInsert());
    }

    @Test public void hugeGapsAreRepresentedCompactlyAndMaxIntStillAllowsReuse() throws Exception {
        user(Integer.MAX_VALUE); new SetIds().setAllIds();
        assertEquals(1,scalar("SELECT COUNT(*) FROM ID_RECOVERY_RANGES WHERE TABLE_NAME='USERS'"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS"));
        assertEquals(1,allocateAndInsert()); assertEquals(2,allocateAndInsert());
        new SetIds().setAllIds(); assertEquals(3,allocateAndInsert());
        sql("DELETE FROM ID_RECOVERY_RANGES WHERE TABLE_NAME='USERS'");
        try (Connection connection=openConnection()) { assertThrows(SQLException.class,()->nextId(connection,"USERS")); connection.rollback(); }
    }

    @Test public void manySmallGapsHaveABoundedPointBudgetAcrossRepeatedStartup() throws Exception {
        try (Connection connection = openConnection()) {
            for (int i = 1; i <= 80; i++) user(connection, i * 65);
            connection.commit();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            new SetIds().setAllIds();
            assertEquals(4096, scalar("SELECT COUNT(*) FROM ID_RECORDER_USERS WHERE TABLE_ID>0"));
            assertEquals(16, scalar("SELECT COUNT(*) FROM ID_RECOVERY_RANGES WHERE TABLE_NAME='USERS'"));
            assertEquals(1024, scalar("SELECT SUM(LAST_ID-FIRST_ID+1) FROM ID_RECOVERY_RANGES WHERE TABLE_NAME='USERS'"));
        }
        assertEquals(1, allocateAndInsert());
    }

    @Test public void survivingLedgerReferencesAreNeverReassigned() throws Exception {
        user(1);
        sql("INSERT INTO LEDGER_JOURNALS(ID,USER_ID,KIND,DATE,BOOK_CURRENCY,ORIGIN,EVIDENCE) VALUES ('test',1,'income','2026-01-01','GBP','test','synthetic')");
        sql("INSERT INTO LEDGER_ENTRIES(JOURNAL_ID,LINE_NUMBER,RECORD_ID,ACCOUNT_KIND,ACCOUNT_NAME,NATIVE_CURRENCY,NATIVE_AMOUNT,DEBIT_MINOR,CREDIT_MINOR) VALUES ('test',1,7,'asset','Test','GBP','1.00',100,0)");
        sql("INSERT INTO INCOME_AND_EXPENDITURE_DATA(ID,USER_ID,TYPE,PLATFORM,NAME,AMOUNT,DATE,CURRENCY) VALUES (10,1,'income','Test','Synthetic','1.00','2026-01-01','GBP')");
        sql("INSERT INTO ID_RECORDER_INCOME_AND_EXPENDITURE_DATA(TABLE_ID) VALUES (7)");
        new SetIds().setAllIds();
        assertEquals(0,scalar("SELECT COUNT(*) FROM ID_RECORDER_INCOME_AND_EXPENDITURE_DATA WHERE TABLE_ID=7"));
        try (Connection connection=openConnection()) { assertThrows(SQLException.class,()->recycleId(connection,"INCOME_AND_EXPENDITURE_DATA",7)); connection.rollback(); }
    }

    @Test public void independentConnectionsAndStartupNeverAllocateTheSameId() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(5); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Set<Integer>>> writers=new ArrayList<>();
            for(int worker=0;worker<4;worker++) writers.add(executor.submit(()-> {
                start.await(); Set<Integer> ids=new HashSet<>(); for(int i=0;i<20;i++) ids.add(allocateAndInsert()); return ids;
            }));
            Future<?> checker=executor.submit(()-> { start.await(); for(int i=0;i<6;i++) new SetIds().setAllIds(); return null; });
            start.countDown(); Set<Integer> ids=new HashSet<>();
            for(Future<Set<Integer>> writer:writers) for(int id:writer.get(60,TimeUnit.SECONDS)) assertTrue(ids.add(id));
            checker.get(60,TimeUnit.SECONDS); assertEquals(80,ids.size()); assertEquals(80,scalar("SELECT COUNT(*) FROM USERS"));
        } finally { executor.shutdownNow(); }
    }

    @Test(timeout = 90000) public void separateApplicationProcessesAllocateAndRecycleWithoutCollisions() throws Exception {
        org.junit.Assume.assumeNotNull(mysqlBase);
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<Process> workers = new ArrayList<>();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) workers.add(new ProcessBuilder(javaExecutable, "-cp", classpath,
                    ProcessWorker.class.getName(), System.getProperty("iafm.db.url")).redirectErrorStream(true).start());
            List<java.io.BufferedReader> outputs = new ArrayList<>();
            List<Future<String>> ready = new ArrayList<>();
            for (Process worker : workers) {
                java.io.BufferedReader output = worker.inputReader(); outputs.add(output);
                ready.add(readers.submit(output::readLine));
            }
            for (Future<String> signal : ready) assertEquals("READY", signal.get(20, TimeUnit.SECONDS));
            for (Process worker : workers) { worker.getOutputStream().write(1); worker.getOutputStream().flush(); }
            for (int i = 0; i < workers.size(); i++) {
                Process worker = workers.get(i);
                assertTrue("Worker did not finish", worker.waitFor(50, TimeUnit.SECONDS));
                String output = outputs.get(i).lines().collect(java.util.stream.Collectors.joining("\n"));
                assertEquals(output, 0, worker.exitValue());
            }
            assertEquals(50, scalar("SELECT COUNT(*) FROM USERS"));
            assertEquals(50, scalar("SELECT COUNT(DISTINCT GENERATION) FROM USERS"));
            assertEquals(0, scalar("SELECT COUNT(*) FROM USERS U JOIN ID_RECORDER_USERS R ON R.TABLE_ID=U.ID"));
        } finally {
            for (Process worker : workers) worker.destroyForcibly();
            readers.shutdownNow();
        }
    }

    /** A distinct JVM has no allocator memory or locks in common with its peer. */
    public static class ProcessWorker {
        public static void main(String[] args) throws Exception {
            if (args.length != 1 || !args[0].matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/iafm_test_id_[a-z0-9]+")) {
                throw new IllegalArgumentException("Only the isolated local fixture is accepted.");
            }
            System.setProperty("iafm.db.url", args[0]); System.setProperty("iafm.db.user", "root");
            System.setProperty("iafm.db.password", ""); System.setProperty("iafm.db.jndi", "");
            System.out.println("READY"); System.out.flush();
            if (System.in.read() < 0) throw new IllegalStateException("Start signal was not received.");
            for (int i = 0; i < 25; i++) {
                int id = allocateAndInsert();
                if (i % 5 == 0) {
                    try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM USERS WHERE ID=?")) {
                        lockIdTables(connection, "USERS"); statement.setInt(1, id);
                        if (statement.executeUpdate() != 1) throw new SQLException("Expected one owned fixture row.");
                        recycleId(connection, "USERS", id); connection.commit();
                    }
                    allocateAndInsert();
                }
            }
        }
    }

}
