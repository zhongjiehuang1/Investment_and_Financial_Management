package DatabaseService;

import Model.User;
import DatabaseService.Model.Controller.ReadData;
import DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset;
import DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.AddIncomeOrExpense;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Runs against H2 by default, or disposable schemas on an explicitly selected local MySQL test server. */
public class RequestIdentityIntegrationTest extends DatabaseInit {
    private final Map<String, String> previous = new HashMap<>();
    private String mysqlServer, database;
    private boolean databaseCreated;
    private User user;

    @Before public void createDatabase() throws Exception {
        mysqlServer = System.getProperty("iafm.identity.mysql.server");
        database = "finance_validation_identity_" + UUID.randomUUID().toString().replace("-", "");
        if (mysqlServer != null) {
            if (!mysqlServer.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))
                throw new IllegalArgumentException("Identity integration tests require an isolated loopback MySQL server URL.");
            try (Connection connection = DriverManager.getConnection(mysqlServer, "root", ""); var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE " + database);
                databaseCreated = true;
            }
            configure("iafm.db.url", mysqlServer + database); configure("iafm.db.user", "root");
        } else {
            configure("iafm.db.url", "jdbc:h2:mem:" + database + ";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            configure("iafm.db.user", "sa");
        }
        configure("iafm.db.password", ""); configure("iafm.db.jndi", "");
        new CreateAllTables().createAllTables();
        execute("INSERT INTO USERS (ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES (1,'Test','User','one@example.invalid','synthetic')");
        user = new User(); user.setId(1); user.setGeneration(value("USERS", "GENERATION", 1));
        cash(1, "Account", "10.00");
    }

    @After public void cleanup() throws Exception {
        try {
            if (mysqlServer != null && databaseCreated) {
                try (Connection connection = DriverManager.getConnection(mysqlServer, "root", ""); var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP DATABASE " + database);
                }
            } else if (mysqlServer == null && database != null) execute("DROP ALL OBJECTS");
        } finally {
            for (var entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private void configure(String key, String value) { previous.put(key, System.getProperty(key)); System.setProperty(key, value); }
    private static void execute(String sql) throws Exception {
        try (Connection connection = openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql); connection.commit();
        }
    }
    private static void cash(int id, String name, String amount) throws Exception {
        execute("INSERT INTO ALL_NON_INVESTMENT_ASSETS (ID,USER_ID,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES ("
                + id + ",1,'" + name + "','" + amount + "','DEMAND_DEPOSIT','VERY_LOW_AND_LOW_RISK_TYPE','GBP','2026-01-01')");
    }
    private static String value(String table, String field, int id) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("SELECT " + field + " FROM " + table + " WHERE ID=?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }
    private RequestIdentity scope(boolean mutation, List<RequestIdentity.Record> records, List<RequestIdentity.Account> accounts) {
        return RequestIdentity.begin(user, mutation, records, accounts);
    }
    private static void stale(boolean user, Throwing action) throws Exception {
        try { action.run(); fail("Expected immutable identity rejection."); }
        catch (RequestIdentity.StaleIdentity expected) { assertEquals(user, expected.isUser()); }
    }
    private interface Throwing { void run() throws Exception; }

    @Test public void readsExposeGenerationsAndValidEditsRetainThem() throws Exception {
        String generation = value("ALL_NON_INVESTMENT_ASSETS", "GENERATION", 1);
        try (RequestIdentity ignored = scope(false, List.of(), List.of())) {
            assertEquals(generation, ReadData.assetsPage(1, "nonInvestment", 1, 100, "", "all").getJSONArray("items").getJSONObject(0).getString("generation"));
            assertEquals(generation, ReadData.platformsPage(1, 1, 100, "").getJSONArray("items").getJSONObject(0).getString("generation"));
        }
        try (RequestIdentity ignored = scope(true, List.of(new RequestIdentity.Record("ALL_NON_INVESTMENT_ASSETS", 1, generation)), List.of())) {
            assertTrue(EditAsset.editNonInvestmentAsset(1, 1, "Account", "DEMAND_DEPOSIT", "VERY_LOW_AND_LOW_RISK_TYPE", "20.00", "GBP"));
        }
        assertEquals(generation, value("ALL_NON_INVESTMENT_ASSETS", "GENERATION", 1));
        assertEquals("20.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
    }

    @Test public void deletedAndReusedRecordRejectsOldEditAndDelete() throws Exception {
        String old = value("ALL_NON_INVESTMENT_ASSETS", "GENERATION", 1);
        assertTrue(DeleteAsset.deleteNonInvestmentAsset(1, 1));
        cash(1, "Replacement", "40.00");
        assertNotEquals(old, value("ALL_NON_INVESTMENT_ASSETS", "GENERATION", 1));
        try (RequestIdentity ignored = scope(true, List.of(new RequestIdentity.Record("ALL_NON_INVESTMENT_ASSETS", 1, old)), List.of())) {
            stale(false, () -> EditAsset.editNonInvestmentAsset(1, 1, "Old", "DEMAND_DEPOSIT", "VERY_LOW_AND_LOW_RISK_TYPE", "0.00", "GBP"));
            stale(false, () -> DeleteAsset.deleteNonInvestmentAsset(1, 1));
        }
        assertEquals("Replacement", value("ALL_NON_INVESTMENT_ASSETS", "NAME", 1));
        assertEquals("40.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
    }

    @Test public void reusedAccountNameCannotReceiveAnOldIncomeRequest() throws Exception {
        String old = value("ALL_NON_INVESTMENT_ASSETS", "GENERATION", 1);
        assertTrue(DeleteAsset.deleteNonInvestmentAsset(1, 1)); cash(1, "Account", "50.00");
        try (RequestIdentity ignored = scope(true, List.of(), List.of(new RequestIdentity.Account("Account", old)))) {
            stale(false, () -> AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Account", "Stale", "1.00", "2026-01-02"));
        }
        assertEquals("50.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
    }

    @Test public void recycledUserCannotUseAnOldSessionForReadsOrWrites() throws Exception {
        assertTrue(DatabaseService.Model.User.deleteUser(1, user.getGeneration()));
        assertTrue(DatabaseService.Model.Controller.RegisterAndLogin.Register.userRegister("replacement@example.invalid", "password", "User", "Replacement"));
        assertNotEquals(user.getGeneration(), value("USERS", "GENERATION", 1));
        cash(1, "Private", "90.00");
        try (RequestIdentity ignored = scope(false, List.of(), List.of())) {
            stale(true, () -> ReadData.assetsPage(1, "nonInvestment", 1, 100, "", "all"));
        }
        try (RequestIdentity ignored = scope(true, List.of(), List.of())) {
            stale(true, () -> AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Private", "Stale", "1.00", "2026-01-02"));
        }
        assertEquals("90.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
    }

    @Test public void readConnectionPinsUserAgainstConcurrentDeletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> deletion;
        CountDownLatch started = new CountDownLatch(1);
        try {
            try (RequestIdentity ignored = scope(false, List.of(), List.of()); Connection connection = openConnection()) {
                deletion = executor.submit(() -> { started.countDown(); return DatabaseService.Model.User.deleteUser(1, user.getGeneration()); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                try { deletion.get(200, TimeUnit.MILLISECONDS); fail("Deletion must wait for the authenticated read transaction."); }
                catch (TimeoutException expected) { }
                connection.commit();
            }
            assertTrue(deletion.get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test public void sharedInflationReuseRejectsStaleGeneration() throws Exception {
        assertTrue(DatabaseService.Model.Controller.InflationRatesData.AddInflationRate.addInflationRate(2.0, "GBP", "2026-01-15"));
        String old = value("ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES", "GENERATION", 1);
        assertTrue(DatabaseService.Model.Controller.InflationRatesData.DeleteInflationRate.deleteInflationRate(1));
        assertTrue(DatabaseService.Model.Controller.InflationRatesData.AddInflationRate.addInflationRate(3.0, "GBP", "2026-02-15"));
        try (RequestIdentity ignored = scope(true, List.of(new RequestIdentity.Record("ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES", 1, old)), List.of())) {
            stale(false, () -> DatabaseService.Model.Controller.InflationRatesData.EditInflationRate.editInflationRate(1, 4.0, "GBP", "2026-02-15"));
        }
        assertNotEquals(old, value("ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES", "GENERATION", 1));
    }
    @Test public void loginDoesNotLockUnrelatedUsersWhileScanningEmail() throws Exception {
        execute("INSERT INTO USERS (ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES (2,'Other','User','two@example.invalid','second-password')");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = openConnection()) {
            lockUser(connection, 1);
            Future<Boolean> login = executor.submit(() -> DatabaseService.Model.Controller.RegisterAndLogin.Login.userLogin(
                    new User(), "TWO@example.invalid", "second-password"));
            assertTrue(login.get(10, TimeUnit.SECONDS));
            connection.rollback();
        } finally { executor.shutdownNow(); }
    }

    @Test public void loginRejectsUserRecyclingDuringPasswordVerification() throws Exception {
        pausedLogin(() -> {
            assertTrue(DatabaseService.Model.User.deleteUser(1, user.getGeneration()));
            execute("INSERT INTO USERS (ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES (1,'Replacement','User','one@example.invalid','synthetic')");
        });
        assertEquals("synthetic", value("USERS", "PASSWORD", 1));
        assertNotEquals(user.getGeneration(), value("USERS", "GENERATION", 1));
    }

    @Test public void loginCannotOverwriteAConcurrentPasswordChange() throws Exception {
        pausedLogin(() -> execute("UPDATE USERS SET PASSWORD='changed-password' WHERE ID=1"));
        assertEquals("changed-password", value("USERS", "PASSWORD", 1));
    }

    private void pausedLogin(Throwing concurrentChange) throws Exception {
        BlockingKdf.delegate = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getProvider();
        BlockingKdf.entered = new CountDownLatch(1); BlockingKdf.resume = new CountDownLatch(1);
        BlockingKdf.first = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.security.Provider observer = new java.security.Provider("IAFM-Identity-Kdf-Observer", "1.0", "Test-only login race observer") { };
        observer.put("SecretKeyFactory.PBKDF2WithHmacSHA256", BlockingKdf.class.getName());
        java.security.Security.insertProviderAt(observer, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        User loggedIn = new User();
        try {
            Future<Boolean> login = executor.submit(() -> {
                BlockingKdf.loginThread = Thread.currentThread();
                return DatabaseService.Model.Controller.RegisterAndLogin.Login.userLogin(loggedIn, "one@example.invalid", "synthetic");
            });
            assertTrue(BlockingKdf.entered.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> { concurrentChange.run(); return null; });
            mutation.get(10, TimeUnit.SECONDS);
            BlockingKdf.resume.countDown();
            assertFalse(login.get(10, TimeUnit.SECONDS));
            assertEquals(0, loggedIn.getId());
        } finally {
            BlockingKdf.resume.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS);
            java.security.Security.removeProvider(observer.getName()); BlockingKdf.loginThread = null;
        }
    }

    /** Pauses a real KDF after the credential snapshot, making lifecycle races deterministic. */
    public static final class BlockingKdf extends javax.crypto.SecretKeyFactorySpi {
        static java.security.Provider delegate;
        static volatile Thread loginThread;
        static CountDownLatch entered, resume;
        static java.util.concurrent.atomic.AtomicBoolean first;
        @Override protected javax.crypto.SecretKey engineGenerateSecret(java.security.spec.KeySpec input)
                throws java.security.spec.InvalidKeySpecException {
            if (Thread.currentThread() == loginThread && first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!resume.await(15, TimeUnit.SECONDS)) throw new java.security.spec.InvalidKeySpecException("Login race test timed out.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new java.security.spec.InvalidKeySpecException(interrupted);
                }
            }
            try { return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", delegate).generateSecret(input); }
            catch (java.security.NoSuchAlgorithmException failure) { throw new java.security.spec.InvalidKeySpecException(failure); }
        }
        @Override protected java.security.spec.KeySpec engineGetKeySpec(javax.crypto.SecretKey key, Class<?> type)
                throws java.security.spec.InvalidKeySpecException { throw new java.security.spec.InvalidKeySpecException("Not used"); }
        @Override protected javax.crypto.SecretKey engineTranslateKey(javax.crypto.SecretKey key)
                throws java.security.InvalidKeyException { throw new java.security.InvalidKeyException("Not used"); }
    }

}
