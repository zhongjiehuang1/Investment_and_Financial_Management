package DatabaseService;

import DatabaseService.Model.Controller.ReadData;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.AddIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.DeleteIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.EditIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.TransferBetweenAccounts;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

/** Business routing tests; the separate coordinator suite covers XA recovery and durable decisions. */
public class ShardedFinanceTest {
    private static final String LOW = "VERY_LOW_AND_LOW_RISK_TYPE";
    private final Map<String, String> previous = new HashMap<>();
    private final Map<Integer, String> urls = new HashMap<>();
    private String username = "sa", password = "";
    private Model.User user;

    @Before public void setup() throws Exception {
        configure("iafm.cluster.enabled", "false"); configure("iafm.cluster.health.required", "false");
        configure("iafm.cluster.health.url", ""); configure("iafm.coordinator.mode", "active");
        configure("iafm.db.jndi", ""); configure("iafm.base.currency", "GBP");
        String config = System.getenv("IAFM_SHARD_TEST_CONFIG");
        if (config != null) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(config))) { properties.load(reader); }
            username = properties.getProperty("user"); password = properties.getProperty("password");
            for (int node : List.of(1, 2)) {
                String url = properties.getProperty("node" + node + ".url");
                if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/finance_shard_validation_[a-z0-9_]+(?:\\?.*)?"))
                    throw new IllegalArgumentException("Only explicitly isolated loopback validation schemas are allowed.");
                urls.put(node, url);
            }
        } else for (int node : List.of(1, 2)) urls.put(node,
                "jdbc:h2:mem:shards_" + node + "_" + UUID.randomUUID() + ";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        configure("iafm.db.user", username); configure("iafm.db.password", password);
        String generation = UUID.randomUUID().toString();
        for (int node : List.of(1, 2)) {
            clean(node); configure("iafm.db.url", urls.get(node)); new CreateAllTables().createAllTables();
            execute(node, "INSERT INTO USERS(ID,GENERATION,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES(1,?,'Test','User','one@example.invalid','disabled')", generation);
        }
        execute(1, "INSERT INTO BASIC_VARIABLES_AND_DATA(ID,USER_ID) VALUES(1,1)");
        user = new Model.User(); user.setId(1); user.setGeneration(generation);
    }
    private void configure(String key, String value) {
        if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
    @After public void cleanup() throws Exception {
        try { for (int node : urls.keySet()) clean(node); }
        finally { previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }); }
    }
    private Connection raw(int node) throws SQLException { return DriverManager.getConnection(urls.get(node), username, password); }
    private void clean(int node) throws SQLException {
        try (Connection connection = raw(node); Statement statement = connection.createStatement()) {
            if (connection.getMetaData().getDatabaseProductName().contains("MySQL")) {
                List<String> tables = new ArrayList<>();
                try (ResultSet result = statement.executeQuery("SHOW TABLES")) { while (result.next()) tables.add(result.getString(1)); }
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String table : tables) {
                    if (!table.matches("[A-Za-z0-9_]+")) throw new SQLException("Unexpected validation table.");
                    statement.execute("DROP TABLE `" + table + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            } else statement.execute("DROP ALL OBJECTS");
        }
    }
    private void execute(int node, String sql, Object... values) throws SQLException {
        try (Connection connection = raw(node); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); statement.executeUpdate();
        }
    }
    private String value(int node, String sql, Object... values) throws SQLException {
        try (Connection connection = raw(node); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }
    private long count(int node, String table) throws SQLException { return Long.parseLong(value(node, "SELECT COUNT(*) FROM " + table)); }
    private String generation(int node, String table, int id) throws SQLException { return value(node, "SELECT GENERATION FROM " + table + " WHERE ID=?", id); }
    private void cash(int node, int id, String name, String amount) throws SQLException {
        execute(node, "INSERT INTO ALL_NON_INVESTMENT_ASSETS(ID,USER_ID,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES(?,1,?,?,'DEMAND_DEPOSIT',?,'GBP','2026-01-01')", id, name, amount, LOW);
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> T tx(int ingress, boolean mutation, List<RequestIdentity.Record> records, List<RequestIdentity.Account> accounts, Work<T> work) throws Exception {
        return tx(ingress, mutation, records, accounts, true, work);
    }
    private <T> T tx(int ingress, boolean mutation, List<RequestIdentity.Record> records, List<RequestIdentity.Account> accounts, boolean authenticated, Work<T> work) throws Exception {
        try (Connection first = raw(1); Connection second = raw(2)) {
            first.setAutoCommit(false); second.setAutoCommit(false);
            first.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); second.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (ShardScope scope = ShardScope.begin(Map.of(1, first, 2, second), ingress);
                 RequestIdentity identity = authenticated ? RequestIdentity.begin(user, mutation, records, accounts) : null) {
                T result = work.run();
                if (scope.isRollbackOnly()) { first.rollback(); second.rollback(); }
                else { first.commit(); second.commit(); }
                return result;
            }
        }
    }
    private <T> T read(Work<T> work) throws Exception { return tx(2, false, List.of(), List.of(), work); }
    private boolean income(int ingress, String name, String amount) throws Exception {
        int owner = "A".equals(name) ? 1 : 2;
        return tx(ingress, true, List.of(), List.of(new RequestIdentity.Account(name, generation(owner, "ALL_NON_INVESTMENT_ASSETS", 1))),
                () -> AddIncomeOrExpense.addIncomeOrExpense(1, "income", name, "Receipt", amount, "2026-01-02"));
    }

    @Test public void ingressOwnsRecordsWhileRemoteAccountOwnsItsBalanceAndMergedPagination() throws Exception {
        cash(1, 1, "A", "10.00"); cash(2, 1, "B", "20.00");
        assertTrue(income(2, "A", "1.00")); assertTrue(income(1, "A", "2.00"));
        assertEquals("13.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals("20.00", value(2, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals(1, count(1, "INCOME_AND_EXPENDITURE_DATA")); assertEquals(1, count(2, "INCOME_AND_EXPENDITURE_DATA"));
        assertEquals(1, count(1, "LEDGER_JOURNALS")); assertEquals(1, count(2, "LEDGER_JOURNALS"));
        JSONObject first = read(() -> ReadData.transactionsPage(1, new JSONObject().put("pageSize", 1)));
        JSONObject second = read(() -> ReadData.transactionsPage(1, new JSONObject().put("pageSize", 1).put("page", 2)));
        assertEquals(2, first.getLong("total")); assertTrue(first.getBoolean("hasNext")); assertFalse(second.getBoolean("hasNext"));
        JSONObject a = first.getJSONArray("items").getJSONObject(0), b = second.getJSONArray("items").getJSONObject(0);
        assertEquals(a.getInt("id"), b.getInt("id")); assertNotEquals(a.getInt("databaseId"), b.getInt("databaseId")); assertNotEquals(a.getString("recordKey"), b.getString("recordKey"));
        assertEquals(generation(1, "ALL_NON_INVESTMENT_ASSETS", 1), b.getString("platformGeneration"));
        assertEquals("33.00", read(() -> ReadData.assetSummary(1, "GBP")).getString("netWorth"));
        assertEquals("3.00", read(() -> ReadData.cashflowSummary(1, "GBP", new JSONObject())).getString("income"));
        assertEquals(2, read(() -> ReadData.platformsPage(1, 1, 100, "")).getJSONArray("items").length());
    }

    @Test public void generationRoutesEditAndDeleteAcrossIngressAndRejectsRecycledIds() throws Exception {
        cash(1, 1, "A", "10.00"); assertTrue(income(2, "A", "1.00"));
        String old = generation(2, "INCOME_AND_EXPENDITURE_DATA", 1);
        var identity = List.of(new RequestIdentity.Record("INCOME_AND_EXPENDITURE_DATA", 1, old));
        var account = List.of(new RequestIdentity.Account("A", generation(1, "ALL_NON_INVESTMENT_ASSETS", 1)));
        assertTrue(tx(1, true, identity, account, () -> EditIncomeOrExpense.editIncomeOrExpense(1, 1, "income", "A", "Edited", "3", "1", 2026, "4.00")));
        assertEquals("14.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertTrue(tx(1, true, identity, List.of(), () -> DeleteIncomeOrExpense.deleteIncomeOrExpense(1, 1, null, null, null)));
        assertEquals("10.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertTrue(income(2, "A", "5.00")); assertNotEquals(old, generation(2, "INCOME_AND_EXPENDITURE_DATA", 1));
        try { tx(1, true, identity, List.of(), () -> DeleteIncomeOrExpense.deleteIncomeOrExpense(1, 1, null, null, null)); fail("Stale generation accepted"); }
        catch (RequestIdentity.StaleIdentity expected) { assertFalse(expected.isUser()); }
        assertEquals("15.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
    }

    @Test public void movingTransactionBetweenAccountsWithSameLocalIdUpdatesBothOwners() throws Exception {
        cash(1, 1, "A", "10.00"); cash(2, 1, "B", "20.00"); assertTrue(income(2, "A", "1.00"));
        var record = List.of(new RequestIdentity.Record("INCOME_AND_EXPENDITURE_DATA", 1, generation(2, "INCOME_AND_EXPENDITURE_DATA", 1)));
        var account = List.of(new RequestIdentity.Account("B", generation(2, "ALL_NON_INVESTMENT_ASSETS", 1)));
        assertTrue(tx(1, true, record, account, () -> EditIncomeOrExpense.editIncomeOrExpense(1, 1, "income", "B", "Moved", "3", "1", 2026, "4.00")));
        assertEquals("10.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals("24.00", value(2, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals("B", value(2, "SELECT PLATFORM FROM INCOME_AND_EXPENDITURE_DATA WHERE ID=1"));
    }

    @Test public void renameFindsRemoteHistoryAndAccountCascadeReversesCollidingRemoteTransferAccount() throws Exception {
        cash(1, 1, "A", "100.00"); cash(2, 1, "B", "0.00");
        String first = generation(1, "ALL_NON_INVESTMENT_ASSETS", 1), second = generation(2, "ALL_NON_INVESTMENT_ASSETS", 1);
        assertTrue(tx(2, true, List.of(), List.of(new RequestIdentity.Account("A", first), new RequestIdentity.Account("B", second)),
                () -> TransferBetweenAccounts.transferBetweenAccounts(1, "A", "B", "10.00", "", "2026-01-02", "Transfer")));
        assertEquals("90.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals("10.00", value(2, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        var record = List.of(new RequestIdentity.Record("ALL_NON_INVESTMENT_ASSETS", 1, first));
        assertFalse(tx(2, true, record, List.of(), () -> DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(1, 1, "Renamed", "DEMAND_DEPOSIT", LOW, "90.00", "USD")));
        assertTrue(tx(2, true, record, List.of(), () -> DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(1, 1, "Renamed", "DEMAND_DEPOSIT", LOW, "90.00", "GBP")));
        assertEquals("Renamed", value(2, "SELECT PLATFORM FROM INCOME_AND_EXPENDITURE_DATA WHERE TYPE='transfer_out'"));
        JSONArray paired = read(() -> ReadData.transactionsPage(1, new JSONObject().put("section", "transfers"))).getJSONArray("items");
        assertEquals(2, paired.length()); assertTrue(paired.getJSONObject(0).getBoolean("pairedTransfer"));
        assertEquals(2, paired.getJSONObject(0).getInt("counterpartDatabaseId"));
        assertTrue(tx(2, true, record, List.of(), () -> DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 1)));
        assertEquals(0, count(1, "ALL_NON_INVESTMENT_ASSETS")); assertEquals("0.00", value(2, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals(0, count(2, "INCOME_AND_EXPENDITURE_DATA")); assertEquals(0, count(2, "LEDGER_JOURNALS"));
    }

    @Test public void outerRollbackCancelsLocalDaoCommitsAndShadowConflictFailsClosed() throws Exception {
        cash(1, 1, "A", "10.00");
        tx(2, true, List.of(), List.of(), () -> {
            assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "A", "Temporary", "1.00", "2026-01-02"));
            assertEquals(0, count(2, "INCOME_AND_EXPENDITURE_DATA"));
            ShardScope.current().markRollbackOnly(); return null;
        });
        assertEquals(0, count(2, "INCOME_AND_EXPENDITURE_DATA")); assertEquals("10.00", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        execute(2, "UPDATE USERS SET GENERATION=? WHERE ID=1", UUID.randomUUID().toString());
        try { read(() -> ReadData.assetSummary(1, "GBP")); fail("Conflicting user shadow accepted"); }
        catch (RequestIdentity.StaleIdentity expected) { assertTrue(expected.isUser()); }
    }

    @Test public void registrationCopiesOnlyIdentityAndUserDeletionRecyclesCanonicalUserAfterBothCascades() throws Exception {
        assertTrue(tx(2, true, List.of(), List.of(), false,
                () -> DatabaseService.Model.Controller.RegisterAndLogin.Register.userRegister("second@example.invalid", "a-test-password", "Test", "Second")));
        assertEquals(2, count(1, "USERS")); assertEquals(2, count(2, "USERS"));
        assertEquals(generation(1, "USERS", 2), generation(2, "USERS", 2));
        assertEquals("!identity-shadow!", value(2, "SELECT PASSWORD FROM USERS WHERE ID=2"));
        assertEquals(2, count(1, "BASIC_VARIABLES_AND_DATA")); assertEquals(0, count(2, "BASIC_VARIABLES_AND_DATA"));
        cash(1, 1, "A", "0.00"); cash(2, 1, "B", "0.00"); assertTrue(income(1, "B", "2.00")); assertTrue(income(2, "A", "3.00"));
        assertTrue(tx(2, true, List.of(), List.of(), () -> DatabaseService.Model.User.deleteUser(1, user.getGeneration())));
        for (int node : List.of(1, 2)) {
            assertEquals(1, count(node, "USERS")); assertEquals(0, count(node, "INCOME_AND_EXPENDITURE_DATA"));
            assertEquals(0, count(node, "ALL_NON_INVESTMENT_ASSETS")); assertEquals(0, count(node, "LEDGER_JOURNALS"));
        }
        assertEquals(1, count(1, "ID_RECORDER_USERS")); assertEquals(0, count(2, "ID_RECORDER_USERS"));
    }

    @Test public void globalTextAndAmountOrderingDoesNotLoseRowsAtPageBoundaries() throws Exception {
        cash(1, 1, "Z", "0.00"); cash(2, 1, "a", "0.00"); cash(1, 2, "中文", "0.00"); cash(2, 2, "😀", "0.00");
        Set<String> keys = new HashSet<>(); List<String> names = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            int current = page;
            JSONObject result = read(() -> ReadData.platformsPage(1, current, 1, "")); assertEquals(4, result.getLong("total"));
            JSONObject item = result.getJSONArray("items").getJSONObject(0); assertTrue(keys.add(item.getString("recordKey"))); names.add(item.getString("name"));
        }
        assertEquals(List.of("Z", "a", "中文", "😀"), names);
        execute(1, "INSERT INTO INCOME_AND_EXPENDITURE_DATA(ID,USER_ID,TYPE,PLATFORM,NAME,AMOUNT,DATE,CURRENCY) VALUES(1,1,'income','Z','Ten','10.00','2026-01-01','GBP')");
        execute(2, "INSERT INTO INCOME_AND_EXPENDITURE_DATA(ID,USER_ID,TYPE,PLATFORM,NAME,AMOUNT,DATE,CURRENCY) VALUES(1,1,'income','a','Two','2.00','2026-01-01','GBP')");
        JSONObject sorted = read(() -> ReadData.transactionsPage(1, new JSONObject().put("sortBy", "amount").put("sortDirection", "asc")));
        assertEquals("2.00", sorted.getJSONArray("items").getJSONObject(0).getString("amount"));
        assertEquals("10.00", sorted.getJSONArray("items").getJSONObject(1).getString("amount"));
    }

    @Test public void simultaneousIngressWritesDoNotLoseRemoteBalanceUpdates() throws Exception {
        cash(1, 1, "A", "0.00");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) { int ingress = i % 2 + 1; results.add(executor.submit(() -> income(ingress, "A", "0.01"))); }
            for (var result : results) assertTrue(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertEquals("0.12", value(1, "SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1"));
        assertEquals(6, count(1, "INCOME_AND_EXPENDITURE_DATA")); assertEquals(6, count(2, "INCOME_AND_EXPENDITURE_DATA"));
        assertEquals("0.12", read(() -> ReadData.cashflowSummary(1, "GBP", new JSONObject())).getString("income"));
    }

    @Test public void currencyTotalsAreMergedBeforeRoundingAcrossNodes() throws Exception {
        var field = Model.Tools.ExchangeRateForVariousCurrencies.class.getDeclaredField("rates");
        field.setAccessible(true); Object previousRate = field.get(null);
        try {
            field.set(null, new Model.Tools.ExchangeRateForVariousCurrencies.RateSnapshot(10.5, "manual", "2026-11-08T00:00:00Z"));
            cash(1, 1, "A", "0.00"); cash(2, 1, "B", "0.00"); assertTrue(income(1, "B", "0.01")); assertTrue(income(2, "A", "0.01"));
            assertEquals("0.21", read(() -> ReadData.assetSummary(1, "CNY")).getString("netWorth"));
            assertEquals("0.21", read(() -> ReadData.cashflowSummary(1, "CNY", new JSONObject())).getString("income"));
            assertEquals("0.21", read(() -> ReadData.riskSummary(1, "CNY")).getJSONArray("riskDistribution").getJSONObject(0).getString("amount"));
        } finally { field.set(null, previousRate); }
    }

    @Test public void identityMigrationCopiesNoBusinessDataAndRejectsAnIndependentDestinationIdentity() throws Exception {
        Assume.assumeTrue(urls.get(1).startsWith("jdbc:mysql:"));
        execute(2, "DELETE FROM USERS WHERE ID=1");
        Path config = Files.createTempFile("finance-shadow-migration-test-", ".properties");
        try {
            Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Properties properties = new Properties();
            for (int node : List.of(1, 2)) {
                properties.setProperty("node" + node + ".url", urls.get(node));
                properties.setProperty("node" + node + ".user", username); properties.setProperty("node" + node + ".password", password);
            }
            try (var writer = Files.newBufferedWriter(config)) { properties.store(writer, "Private disposable test credentials"); }
            IdentityShadowMigration.main(new String[]{config.toString(), "--writers-stopped"});
            assertEquals(generation(1, "USERS", 1), generation(2, "USERS", 1));
            assertEquals("!identity-shadow!", value(2, "SELECT PASSWORD FROM USERS WHERE ID=1"));
            assertEquals("disabled", value(1, "SELECT PASSWORD FROM USERS WHERE ID=1"));
            assertEquals(1, count(1, "BASIC_VARIABLES_AND_DATA")); assertEquals(0, count(2, "BASIC_VARIABLES_AND_DATA"));
            execute(2, "INSERT INTO USERS(ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES(77,'Independent','User','independent@example.invalid','disabled')");
            try { IdentityShadowMigration.main(new String[]{config.toString(), "--writers-stopped"}); fail("Conflicting destination was accepted"); }
            catch (SQLException expected) { assertTrue(expected.getMessage().contains("conflicts")); }
            assertEquals(2, count(2, "USERS"));
        } finally { Files.deleteIfExists(config); }
    }
}
