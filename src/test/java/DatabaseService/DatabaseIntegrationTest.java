package DatabaseService;

import DatabaseService.Model.Controller.ReadData;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.AddIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.EditIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.DeleteIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.TransferBetweenAccounts;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

/** Synthetic in-memory data only. Tests use the application's unchanged table definitions. */
public class DatabaseIntegrationTest extends DatabaseInit {
    private static final String LOW = "VERY_LOW_AND_LOW_RISK_TYPE", HIGH = "MEDIUM_HIGH_AND_HIGH_RISK_TYPE";
    private static final String TODAY = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    private final Map<String, String> previous = new HashMap<>();

    @Before public void createDatabase() throws Exception {
        setProperty("iafm.db.url", "jdbc:h2:mem:database_" + UUID.randomUUID() + ";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
        setProperty("iafm.db.user", "sa"); setProperty("iafm.db.password", "");
        setProperty("iafm.base.currency", "GBP"); setProperty("iafm.db.jndi", "");
        new CreateAllTables().createAllTables();
        insert("INSERT INTO USERS (ID, FORENAME, SURNAME, EMAIL_ADDRESS, PASSWORD) VALUES (?, ?, ?, ?, ?)", 1, "User", "One", "one@example.invalid", "synthetic");
        insert("INSERT INTO USERS (ID, FORENAME, SURNAME, EMAIL_ADDRESS, PASSWORD) VALUES (?, ?, ?, ?, ?)", 2, "User", "Two", "two@example.invalid", "synthetic");
        insert("INSERT INTO BASIC_VARIABLES_AND_DATA (ID,USER_ID) VALUES (?,?)", 1, 1);
        insert("INSERT INTO BASIC_VARIABLES_AND_DATA (ID,USER_ID) VALUES (?,?)", 2, 2);
    }

    @After public void restoreConfiguration() throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("DROP ALL OBJECTS")) {
            statement.executeUpdate(); connection.commit();
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey()); else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private void setProperty(String key, String value) { previous.put(key, System.getProperty(key)); System.setProperty(key, value); }

    private static void insert(String sql, Object... values) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate(); connection.commit();
        }
    }

    private static void cash(int id, int user, String name, String amount, String currency) throws Exception {
        insert("INSERT INTO ALL_NON_INVESTMENT_ASSETS (ID,USER_ID,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES (?,?,?,?,?,?,?,?)",
                id, user, name, amount, "DEMAND_DEPOSIT", LOW, currency, TODAY);
    }

    private static void investment(int id, int user, String name, String balance, String invested, String currency) throws Exception {
        insert("INSERT INTO ALL_INVESTMENT_ASSETS (ID,USER_ID,INVESTMENT_PLATFORM,REAL_TIME_AMOUNT,INVESTMENT_AMOUNT,CASH_WITHDRAWAL," +
                        "MANAGEMENT_FEE,HANDLING_FEE,BUY_IN_AMOUNT,AMOUNT_SOLD,INITIAL_EARNINGS,INVESTMENT_TYPE,RISK_TYPE,CURRENCY,DATE) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, user, name, balance, invested, "0.00", "0.00", "0.00", invested, "0.00", "0.00", "STOCK", HIGH, currency, TODAY);
    }

    private static void record(int id, int user, String type, String platform, String amount, String name) throws Exception {
        insert("INSERT INTO INCOME_AND_EXPENDITURE_DATA (ID,USER_ID,TYPE,PLATFORM,AMOUNT,NAME,CURRENCY,DATE) VALUES (?,?,?,?,?,?,?,?)",
                id, user, type, platform, amount, name, "GBP", TODAY);
    }

    private static String value(String table, String column, int id) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM " + table + " WHERE ID = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }

    @Test public void paginatesInDatabaseWithStableOrderAndOwnerScope() throws Exception {
        for (int i = 1; i <= 205; i++) { cash(i, 1, "Account " + i, "0.01", "GBP"); record(i, 1, "income", "Account " + i, "0.01", "record " + i); }
        cash(300, 2, "Private other account", "900.00", "GBP"); record(300, 2, "income", "Private other account", "900.00", "private other record");
        JSONObject first = ReadData.assetsPage(1, "nonInvestment", 1, 100, "", "all");
        JSONObject second = ReadData.assetsPage(1, "nonInvestment", 2, 100, "", LOW);
        JSONObject third = ReadData.assetsPage(1, "nonInvestment", 3, 100, "", "");
        assertEquals(205, first.getLong("total")); assertEquals(100, first.getJSONArray("items").length()); assertTrue(first.getBoolean("hasNext"));
        assertEquals(5, third.getJSONArray("items").length()); assertFalse(third.getBoolean("hasNext"));
        Set<Integer> ids = new HashSet<>();
        for (JSONObject page : List.of(first, second, third)) for (Object row : page.getJSONArray("items")) assertTrue(ids.add(((JSONObject) row).getInt("id")));
        assertEquals(205, ids.size()); assertFalse(ids.contains(300));
        assertEquals(205, first.getJSONArray("items").getJSONObject(0).getInt("id"));
        JSONObject ledger = ReadData.transactionsPage(1, new JSONObject());
        assertEquals(100, ledger.getJSONArray("items").length()); assertEquals(205, ledger.getLong("total"));
        assertEquals("GBP", ledger.getJSONArray("items").getJSONObject(0).getString("currency"));
        assertEquals("2.05", ReadData.assetSummary(1, "GBP").getString("netWorth"));
        assertEquals("2.05", ReadData.cashflowSummary(1, "GBP", new JSONObject()).getString("income"));
        assertEquals(100, ReadData.platformsPage(1, 1, 100, "").getJSONArray("items").length());
    }

    @Test public void filtersCannotInjectSqlAndWildcardsAreLiteral() throws Exception {
        cash(1, 1, "O'Brien 100%", "1.00", "GBP"); cash(2, 2, "Other", "100.00", "GBP");
        record(1, 1, "income", "O'Brien 100%", "1.00", "Literal 100% receipt"); record(2, 2, "income", "Other", "100.00", "private");
        JSONObject injected = new JSONObject().put("filterPlatform", new JSONArray().put("x') OR 1=1 --"));
        assertEquals(0, ReadData.transactionsPage(1, injected).getLong("total"));
        assertEquals("0.00", ReadData.cashflowSummary(1, "GBP", injected).getString("income"));
        assertEquals(1, ReadData.transactionsPage(1, new JSONObject().put("q", "100%")).getLong("total"));
        assertEquals(1, ReadData.assetsPage(1, "nonInvestment", 1, 100, "O'Brien 100%", "all").getLong("total"));
        assertThrows(IllegalArgumentException.class, () -> ReadData.transactionsPage(1, new JSONObject().put("pageSize", 101)));
        assertThrows(IllegalArgumentException.class, () -> ReadData.assetsPage(1, "nonInvestment", 0, 100, "", "all"));
    }

    @Test public void internalTransfersDoNotInflateCashflowOrInvestmentEarnings() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP"); investment(1, 1, "Broker", "120.00", "100.00", "GBP");
        cash(2, 2, "Other user bank", "999.00", "GBP");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Bank", "Broker", "25.10", null, TODAY, "Own accounts"));
        assertEquals("74.90", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
        assertEquals("145.10", value("ALL_INVESTMENT_ASSETS", "REAL_TIME_AMOUNT", 1));
        assertEquals("125.10", value("ALL_INVESTMENT_ASSETS", "INVESTMENT_AMOUNT", 1));
        assertEquals("20.00", ReadData.earningsSummary(1, "GBP").getString("earnings"));
        assertEquals("220.00", ReadData.assetSummary(1, "GBP").getString("netWorth"));
        JSONObject flow = ReadData.cashflowSummary(1, "GBP", new JSONObject());
        assertEquals("0.00", flow.getString("income")); assertEquals("0.00", flow.getString("expense")); assertEquals(2, flow.getLong("transferCount"));
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Broker", "Interest", "0.10", TODAY));
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "expense", "Broker", "Fee", "0.03", TODAY));
        flow = ReadData.cashflowSummary(1, "GBP", new JSONObject());
        assertEquals("0.10", flow.getString("income")); assertEquals("0.03", flow.getString("expense"));
        assertEquals("20.07", ReadData.earningsSummary(1, "GBP").getString("earnings"));
        assertEquals("999.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 2));
    }

    @Test public void failedTransferRollsBackBothSidesAndRecords() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP"); cash(2, 1, "Second", "50.00", "GBP"); cash(3, 2, "Foreign owner", "600.00", "GBP");
        assertFalse(TransferBetweenAccounts.transferBetweenAccounts(1, "Bank", "Foreign owner", "5.00", null, TODAY, "Rejected"));
        assertFalse(TransferBetweenAccounts.transferBetweenAccounts(1, "Bank", "Second", "5.00", "4.99", TODAY, "Mismatch"));
        assertFalse(TransferBetweenAccounts.transferBetweenAccounts(1, "Bank", "Second", "5.00", null, TODAY, "x".repeat(151)));
        assertEquals("100.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
        assertEquals("50.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 2));
        assertEquals(0, ReadData.transactionsPage(1, new JSONObject()).getLong("total"));
    }

    @Test public void crossCurrencyTransferRequiresActualReceivedAmount() throws Exception {
        cash(1, 1, "GBP Bank", "100.00", "GBP"); cash(2, 1, "CNY Bank", "50.00", "CNY");
        assertFalse(TransferBetweenAccounts.transferBetweenAccounts(1, "GBP Bank", "CNY Bank", "2.50", null, TODAY, "Needs amount"));
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "GBP Bank", "CNY Bank", "2.50", "22.75", TODAY, "Actual exchanged amount"));
        assertEquals("97.50", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1)); assertEquals("72.75", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 2));
        JSONArray items = ReadData.transactionsPage(1, new JSONObject().put("section", "transfers")).getJSONArray("items");
        assertEquals("CNY", items.getJSONObject(0).getString("currency")); assertEquals("22.75", items.getJSONObject(0).getString("amount"));
    }

    @Test public void editsAndDeletesUseOnlyOwnedStoredFieldsAndAreReversible() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP"); investment(1, 1, "Broker", "0.00", "0.00", "GBP"); cash(2, 2, "Other", "900.00", "GBP");
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Bank", "Original", "10.00", TODAY));
        int id = ReadData.transactionsPage(1, new JSONObject()).getJSONArray("items").getJSONObject(0).getInt("id");
        assertFalse(DeleteIncomeOrExpense.deleteIncomeOrExpense(2, id, "income", "Other", "1000000.00"));
        java.time.LocalDate today = java.time.LocalDate.parse(TODAY);
        assertFalse(EditIncomeOrExpense.editIncomeOrExpense(2, id, "expense", "Other", "Intrusion", "1", "1", 2026, "1.00"));
        assertThrows(IllegalArgumentException.class, () -> EditIncomeOrExpense.editIncomeOrExpense(1, id, "transfer_in", "Broker", "Unpaired reclassification",
                String.valueOf(today.getDayOfMonth()), String.valueOf(today.getMonthValue()), today.getYear(), "12.00"));
        assertTrue(EditIncomeOrExpense.editIncomeOrExpense(1, id, "income", "Broker", "Corrected account",
                String.valueOf(today.getDayOfMonth()), String.valueOf(today.getMonthValue()), today.getYear(), "12.00"));
        assertEquals("100.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
        assertEquals("12.00", value("ALL_INVESTMENT_ASSETS", "REAL_TIME_AMOUNT", 1));
        assertEquals("0.00", value("ALL_INVESTMENT_ASSETS", "INVESTMENT_AMOUNT", 1));
        assertEquals("12.00", ReadData.earningsSummary(1, "GBP").getString("earnings"));
        assertTrue(DeleteIncomeOrExpense.deleteIncomeOrExpense(1, id, "expense", "Other", "999999.99"));
        assertEquals("0.00", value("ALL_INVESTMENT_ASSETS", "REAL_TIME_AMOUNT", 1));
        assertEquals("0.00", value("ALL_INVESTMENT_ASSETS", "INVESTMENT_AMOUNT", 1));
        assertFalse(DeleteIncomeOrExpense.deleteIncomeOrExpense(1, id, null, null, null));
        assertEquals("900.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 2));
    }

    @Test public void accountMutationsAreScopedAndKeepHistoryConsistent() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP"); cash(2, 2, "Other", "900.00", "GBP");
        assertFalse(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(2, 1, "Stolen", "DEMAND_DEPOSIT", LOW, "0.00", "GBP"));
        assertFalse(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(2, 1));
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Bank", "Pay", "5.00", TODAY));
        assertFalse(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(1, 1, "Bank", "DEMAND_DEPOSIT", LOW, "105.00", "CNY"));
        assertTrue(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(1, 1, "Renamed", "DEMAND_DEPOSIT", LOW, "105.00", "GBP"));
        assertEquals("Renamed", ReadData.transactionsPage(1, new JSONObject()).getJSONArray("items").getJSONObject(0).getString("platform"));
        assertTrue(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 1));
        assertEquals(0, ReadData.transactionsPage(1, new JSONObject()).getLong("total"));
        assertEquals("900.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 2));
    }

    @Test public void riskRetainsUninvestedCashAndFeesAreNotDeductedTwice() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP"); investment(1, 1, "Broker", "120.00", "100.00", "GBP");
        insert("UPDATE ALL_INVESTMENT_ASSETS SET BUY_IN_AMOUNT='60.00', MANAGEMENT_FEE='2.00', HANDLING_FEE='1.00' WHERE ID=1");
        JSONObject earnings = ReadData.earningsSummary(1, "GBP");
        assertEquals("20.00", earnings.getString("earnings")); assertEquals("3.00", earnings.getString("fees"));
        JSONObject risk = ReadData.riskSummary(1, "GBP");
        assertEquals("137.00", risk.getString("Total very low and low risk liquid assets"));
        assertEquals("83.00", risk.getString("Total medium high and high risk liquid assets"));
        assertEquals(3, risk.getJSONArray("riskDistribution").length());
    }

    @Test public void sequenceReusesLegacyIdsAndAllocatesUniquelyUnderConcurrency() throws Exception {
        record(70, 1, "income", "unused synthetic platform", "0.01", "Existing");
        insert("INSERT INTO ID_RECORDER_INCOME_AND_EXPENDITURE_DATA (TABLE_ID) VALUES (95)");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) futures.add(executor.submit(() -> {
                ready.await(); List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < 20; i++) try (Connection connection = openConnection()) {
                    int id = nextId(connection, "INCOME_AND_EXPENDITURE_DATA");
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO INCOME_AND_EXPENDITURE_DATA (ID,USER_ID,TYPE,PLATFORM,AMOUNT,NAME,CURRENCY,DATE) VALUES (?,1,'income','unused','0.01','Concurrent','GBP',?)")) {
                        statement.setInt(1, id); statement.setDate(2, java.sql.Date.valueOf(TODAY)); statement.executeUpdate();
                    }
                    connection.commit(); ids.add(id);
                }
                return ids;
            }));
            ready.countDown(); Set<Integer> ids = new HashSet<>();
            for (Future<List<Integer>> future : futures) for (int id : future.get(30, java.util.concurrent.TimeUnit.SECONDS)) assertTrue(ids.add(id));
            assertEquals(160, ids.size()); assertTrue(ids.contains(95)); assertFalse(ids.contains(70));
            try (Connection connection = openConnection()) { assertEquals(255, nextId(connection, "INCOME_AND_EXPENDITURE_DATA")); connection.rollback(); }
        } finally { executor.shutdownNow(); }
    }

    @Test public void concurrentWritesDoNotLoseCentsOrCrossUserBalances() throws Exception {
        cash(1, 1, "Bank", "0.00", "GBP"); cash(2, 2, "Bank", "10.00", "GBP");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                final int owner = i % 2 == 0 ? 1 : 2;
                futures.add(executor.submit(() -> AddIncomeOrExpense.addIncomeOrExpense(owner, "income", "Bank", "Synthetic cent", "0.01", TODAY)));
            }
            for (Future<Boolean> future : futures) assertTrue(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("0.30", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
            assertEquals("10.30", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 2));
            assertEquals(30, ReadData.transactionsPage(1, new JSONObject()).getLong("total"));
            assertEquals(30, ReadData.transactionsPage(2, new JSONObject()).getLong("total"));
        } finally { executor.shutdownNow(); }
    }

    @Test public void registrationCreatesOneIdentityForConcurrentDuplicateEmail() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> DatabaseService.Model.Controller.RegisterAndLogin.Register.userRegister(
                    "duplicate@example.invalid", "synthetic-password", "User", "Test")));
            int successes = 0;
            for (Future<Boolean> future : futures) if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally { executor.shutdownNow(); }
    }

    @Test public void validatesStorageCapacityAndDoesNotExposeCredentials() throws Exception {
        cash(1, 1, "Bank", "1.00", "GBP");
        assertThrows(IllegalArgumentException.class, () -> AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Bank", "too much", "1000000000000.00", TODAY));
        assertThrows(IllegalArgumentException.class, () -> AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Bank", "fraction", "0.001", TODAY));
        assertFalse(ReadData.profile(1).has("password")); assertEquals("GBP", ReadData.basicVariables(1).getString("basicCurrency"));
        assertEquals("1.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
    }

    @Test public void accountIdentityUsesOwnedIdRatherThanClientNameCase() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP");
        assertFalse(TransferBetweenAccounts.transferBetweenAccounts(1, "Bank", "bank", "5.00", null, TODAY, "Same account"));
        assertEquals("100.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "bank", "Case-insensitive name", "10.00", TODAY));
        JSONObject row = ReadData.transactionsPage(1, new JSONObject()).getJSONArray("items").getJSONObject(0);
        assertEquals("Bank", row.getString("platform"));
        java.time.LocalDate today = java.time.LocalDate.parse(TODAY);
        assertTrue(EditIncomeOrExpense.editIncomeOrExpense(1, row.getInt("id"), "income", "BANK", "Change amount",
                String.valueOf(today.getDayOfMonth()), String.valueOf(today.getMonthValue()), today.getYear(), "15.00"));
        assertEquals("115.00", value("ALL_NON_INVESTMENT_ASSETS", "AMOUNT", 1));
    }

    private static long scalar(String sql) throws Exception {
        try (Connection connection=openConnection(); PreparedStatement statement=connection.prepareStatement(sql); ResultSet result=statement.executeQuery()) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }

    @Test public void transferJournalBalancesInOneBookCurrencyAndDeletionReversesBothLegs() throws Exception {
        cash(1,1,"Sterling","100.00","GBP"); cash(2,1,"Yuan","50.00","CNY");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1,"Sterling","Yuan","2.50","22.75",TODAY,"Actual FX"));
        JSONObject leg=ReadData.transactionsPage(1,new JSONObject().put("section","transfers")).getJSONArray("items").getJSONObject(0);
        assertTrue(leg.getBoolean("pairedTransfer")); assertFalse(leg.getBoolean("canEdit")); assertTrue(leg.getBoolean("canDelete"));
        assertEquals(0,ReadData.transactionsPage(1,new JSONObject()).getInt("total"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM LEDGER_JOURNALS WHERE BOOK_CURRENCY='GBP' AND KIND='transfer'"));
        assertEquals(250,scalar("SELECT SUM(DEBIT_MINOR) FROM LEDGER_ENTRIES"));
        assertEquals(250,scalar("SELECT SUM(CREDIT_MINOR) FROM LEDGER_ENTRIES"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM LEDGER_ENTRIES WHERE NATIVE_CURRENCY='CNY' AND NATIVE_AMOUNT='22.75'"));
        assertFalse(EditIncomeOrExpense.editIncomeOrExpense(1,leg.getInt("id"),"income","Yuan","Cannot split","1","1",2026,"5.00"));
        assertFalse(DeleteIncomeOrExpense.deleteIncomeOrExpense(2,leg.getInt("id"),null,null,null));
        assertTrue(DeleteIncomeOrExpense.deleteIncomeOrExpense(1,leg.getInt("id"),null,null,null));
        assertEquals("100.00",value("ALL_NON_INVESTMENT_ASSETS","AMOUNT",1));
        assertEquals("50.00",value("ALL_NON_INVESTMENT_ASSETS","AMOUNT",2));
        assertEquals(0,scalar("SELECT COUNT(*) FROM LEDGER_JOURNALS")); assertEquals(0,scalar("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
        assertEquals(0,ReadData.transactionsPage(1,new JSONObject().put("section","all")).getInt("total"));
    }

    @Test public void legacyUnpairedTransferIsReadOnlyAndCannotBeManufactured() throws Exception {
        cash(1,1,"Bank","10.00","GBP"); record(1,1,"transfer_in","Bank","5.00","Legacy");
        JSONObject row=ReadData.transactionsPage(1,new JSONObject().put("section","transfers")).getJSONArray("items").getJSONObject(0);
        assertEquals("legacy_unpaired",row.getString("pairStatus")); assertFalse(row.getBoolean("canDelete"));
        assertFalse(DeleteIncomeOrExpense.deleteIncomeOrExpense(1,1,null,null,null));
        assertThrows(IllegalArgumentException.class,()->AddIncomeOrExpense.addIncomeOrExpense(1,"transfer_in","Bank","Single leg","1.00",TODAY));
        assertEquals("10.00",value("ALL_NON_INVESTMENT_ASSETS","AMOUNT",1));
    }

    @Test public void zeroValueLegacyStatementRowCanBeRemovedWithoutInventingBalanceMovement() throws Exception {
        cash(1,1,"Bank","10.00","GBP"); record(1,1,"income","Bank","0.00","Zero-value source row");
        assertTrue(DeleteIncomeOrExpense.deleteIncomeOrExpense(1,1,null,null,null));
        assertEquals("10.00",value("ALL_NON_INVESTMENT_ASSETS","AMOUNT",1));
        assertEquals(0,scalar("SELECT COUNT(*) FROM LEDGER_JOURNALS"));
    }

    @Test public void sortingUsesNumericAmountsAndAllowlistsBeforePagination() throws Exception {
        cash(1,1,"Bank","0.00","GBP"); record(1,1,"income","Bank","9.00","Nine"); record(2,1,"income","Bank","100.00","Hundred");
        JSONObject filters=new JSONObject().put("sortBy","amount").put("sortDirection","asc").put("pageSize",1);
        assertEquals(1,ReadData.transactionsPage(1,filters).getJSONArray("items").getJSONObject(0).getInt("id"));
        filters.put("sortDirection","desc");
        assertEquals(2,ReadData.transactionsPage(1,filters).getJSONArray("items").getJSONObject(0).getInt("id"));
        assertThrows(IllegalArgumentException.class,()->ReadData.transactionsPage(1,new JSONObject().put("sortBy","DATE; DROP TABLE USERS")));
        assertThrows(IllegalArgumentException.class,()->ReadData.transactionsPage(1,new JSONObject().put("sortDirection","desc; DROP TABLE USERS")));
        assertThrows(IllegalArgumentException.class,()->ReadData.transactionsPage(1,new JSONObject().put("section","transfers' OR 1=1")));
        assertEquals(2,scalar("SELECT COUNT(*) FROM USERS"));
    }

    @Test public void concurrentTransferDeletionCommitsOnceAndNeverCreatesOrphanEntries() throws Exception {
        cash(1,1,"Bank","100.00","GBP"); cash(2,1,"Savings","0.00","GBP");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1,"Bank","Savings","10.00",null,TODAY,"Own"));
        JSONArray legs=ReadData.transactionsPage(1,new JSONObject().put("section","transfers")).getJSONArray("items");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first=pool.submit(()->DeleteIncomeOrExpense.deleteIncomeOrExpense(1,legs.getJSONObject(0).getInt("id"),null,null,null));
            Future<Boolean> second=pool.submit(()->DeleteIncomeOrExpense.deleteIncomeOrExpense(1,legs.getJSONObject(1).getInt("id"),null,null,null));
            assertNotEquals(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals("100.00",value("ALL_NON_INVESTMENT_ASSETS","AMOUNT",1)); assertEquals("0.00",value("ALL_NON_INVESTMENT_ASSETS","AMOUNT",2));
        assertEquals(0,scalar("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
    }

    @Test public void mysqlStreamingBranchAndJdbcResourcesAreBoundedOnSuccessAndFailure() throws Exception {
        cash(1, 1, "Bank", "100.00", "GBP"); investment(1, 1, "Broker", "120.00", "100.00", "GBP");
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL(System.getProperty("iafm.db.url")); h2.setUser("sa"); h2.setPassword("");
        JdbcSpy spy = new JdbcSpy();
        TestNamingFactory.source = spy.wrapSource(h2);
        setProperty("java.naming.factory.initial", TestNamingFactory.class.getName());
        setProperty("iafm.db.jndi", "test/in-memory-only");
        assertEquals("220.00", ReadData.assetSummary(1, "GBP").getString("netWorth"));
        assertEquals("20.00", ReadData.earningsSummary(1, "GBP").getString("earnings"));
        ReadData.riskSummary(1, "GBP"); ReadData.cashflowSummary(1, "GBP", new JSONObject());
        assertEquals(6, spy.streamingRequests.get());
        assertEquals(0, spy.connections.get()); assertEquals(0, spy.statements.get()); assertEquals(0, spy.results.get());
        spy.failLedger = true;
        try {
            assertThrows(java.sql.SQLException.class, () -> ReadData.cashflowSummary(1, "GBP", new JSONObject()));
            assertEquals(0, spy.connections.get()); assertEquals(0, spy.statements.get()); assertEquals(0, spy.results.get());
        } finally { spy.failLedger = false; }
    }

    /** JNDI test seam returns only a proxy around this test's in-memory H2 connection. */
    public static class TestNamingFactory implements javax.naming.spi.InitialContextFactory {
        static javax.sql.DataSource source;
        public javax.naming.Context getInitialContext(java.util.Hashtable<?, ?> environment) {
            return (javax.naming.Context) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{javax.naming.Context.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "lookup" -> source;
                        case "close" -> null;
                        case "getEnvironment" -> new java.util.Hashtable<>();
                        case "toString" -> "In-memory test naming context";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** Assert MySQL fetch mode without ever opening a network or production database connection. */
    private static class JdbcSpy {
        final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger statements = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger results = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger streamingRequests = new java.util.concurrent.atomic.AtomicInteger();
        boolean failLedger;

        javax.sql.DataSource wrapSource(javax.sql.DataSource delegate) {
            return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                        Object value = call(delegate, method, args);
                        if (value instanceof Connection connection) return wrapConnection(connection);
                        return value;
                    });
        }

        Connection wrapConnection(Connection delegate) {
            connections.incrementAndGet(); java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) connections.decrementAndGet();
                Object value = call(delegate, method, args);
                if (value instanceof PreparedStatement statement) return wrapStatement(statement, String.valueOf(args[0]));
                if ("getMetaData".equals(method.getName())) {
                    Object metadata = value;
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.DatabaseMetaData.class},
                            (ignored, metadataMethod, metadataArgs) -> "getDatabaseProductName".equals(metadataMethod.getName()) ? "MySQL" : call(metadata, metadataMethod, metadataArgs));
                }
                return value;
            });
        }

        PreparedStatement wrapStatement(PreparedStatement delegate, String sql) {
            statements.incrementAndGet(); java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) statements.decrementAndGet();
                if ("setFetchSize".equals(method.getName()) && ((Integer) args[0]) == Integer.MIN_VALUE) {
                    streamingRequests.incrementAndGet(); delegate.setFetchSize(100); return null;
                }
                if ("executeQuery".equals(method.getName()) && failLedger && sql.contains("INCOME_AND_EXPENDITURE_DATA")) throw new java.sql.SQLException("Synthetic read failure");
                Object value = call(delegate, method, args);
                if (value instanceof ResultSet result) return wrapResult(result);
                return value;
            });
        }

        ResultSet wrapResult(ResultSet delegate) {
            results.incrementAndGet(); java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) results.decrementAndGet();
                return call(delegate, method, args);
            });
        }

        static Object call(Object delegate, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(delegate, args); }
            catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
        }
    }

}
