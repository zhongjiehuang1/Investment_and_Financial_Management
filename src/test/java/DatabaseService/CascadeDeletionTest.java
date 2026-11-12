package DatabaseService;

import DatabaseService.Model.Controller.IncomeAndExpenditureData.AddIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.DeleteIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.TransferBetweenAccounts;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

/** Runs against isolated H2 by default; an explicitly configured disposable MySQL schema exercises the same cases. */
public class CascadeDeletionTest extends DatabaseInit {
    private static final String LOW = "VERY_LOW_AND_LOW_RISK_TYPE", DAY = "2026-01-15";
    private final Map<String, String> previous = new HashMap<>();
    private boolean mysql;

    @Before public void setup() throws Exception {
        String mysqlUrl = System.getProperty("iafm.cascade.mysql.url");
        mysql = mysqlUrl != null;
        if (mysql && !mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/finance_validation_cascade(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only an explicitly named disposable localhost test schema is allowed.");
        }
        setting("iafm.db.url", mysql ? mysqlUrl : "jdbc:h2:mem:cascade_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        setting("iafm.db.user", mysql ? "root" : "sa"); setting("iafm.db.password", ""); setting("iafm.db.jndi", "");
        if (mysql) clearMysql();
        new CreateAllTables().createAllTables();
        for (int user = 1; user <= 2; user++) {
            execute("INSERT INTO USERS(ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES (?,'Synthetic','Owner',?,'synthetic')", user, "owner" + user + "@example.invalid");
            execute("INSERT INTO BASIC_VARIABLES_AND_DATA(ID,USER_ID) VALUES (?,?)", user + 100, user);
        }
    }

    private void setting(String key, String value) { previous.put(key, System.getProperty(key)); System.setProperty(key, value); }
    @After public void cleanup() throws Exception {
        try {
            if (mysql) clearMysql();
            else execute("DROP ALL OBJECTS");
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    private static void clearMysql() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("SET FOREIGN_KEY_CHECKS=0");
            List<String> tables = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SHOW TABLES")) { while (result.next()) tables.add(result.getString(1)); }
            for (String table : tables) {
                if (!table.matches("[A-Z_]+")) throw new SQLException("Unexpected test table.");
                statement.executeUpdate("DROP TABLE `" + table + "`");
            }
            statement.executeUpdate("SET FOREIGN_KEY_CHECKS=1"); connection.commit();
        }
    }

    private static void execute(String sql, Object... values) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate(); connection.commit();
        }
    }
    private static String value(String sql) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            assertTrue(result.next()); return result.getString(1);
        }
    }
    private static long count(String sql) throws Exception { return Long.parseLong(value(sql)); }
    private static void cash(int id, int user, String name, String amount, String currency) throws Exception {
        execute("INSERT INTO ALL_NON_INVESTMENT_ASSETS(ID,USER_ID,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES (?,?,?,?,?,?,?,?)",
                id, user, name, amount, "DEMAND_DEPOSIT", LOW, currency, DAY);
    }
    private static void investment(int id, int user, String name, String currency) throws Exception {
        execute("INSERT INTO ALL_INVESTMENT_ASSETS(ID,USER_ID,INVESTMENT_PLATFORM,REAL_TIME_AMOUNT,INVESTMENT_AMOUNT,CASH_WITHDRAWAL,MANAGEMENT_FEE,HANDLING_FEE,BUY_IN_AMOUNT,AMOUNT_SOLD,INITIAL_EARNINGS,INVESTMENT_TYPE,RISK_TYPE,CURRENCY,DATE) VALUES (?,?,?,'100.00','100.00','0.00','0.00','0.00','100.00','0.00','0.00','STOCK',?,?,?)",
                id, user, name, LOW, currency, DAY);
    }
    private static void recycled(String table, int... ids) throws Exception {
        for (int id : ids) assertEquals("Missing recycled " + table + " ID " + id, 1,
                count("SELECT COUNT(*) FROM ID_RECORDER_" + table + " WHERE TABLE_ID=" + id));
    }
    private static int[] transactionIds() throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT ID FROM INCOME_AND_EXPENDITURE_DATA WHERE USER_ID=1 ORDER BY ID")) {
            while (result.next()) ids.add(result.getInt(1));
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test public void investmentCascadeReversesSurvivingFxCounterpartAndRecyclesAllChildren() throws Exception {
        cash(7, 1, "Sterling", "100.00", "GBP"); investment(19, 1, "Broker", "CNY"); cash(8, 2, "Broker", "900.00", "CNY");
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Broker", "Dividend", "5.01", DAY));
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Sterling", "Broker", "2.50", "22.75", DAY, "Actual FX"));
        int[] ids = transactionIds(); assertEquals(3, ids.length);
        assertTrue(DatabaseService.Model.Controller.AssetsData.InvestmentAsset.DeleteAsset.deleteInvestmentAsset(1, 19));
        assertEquals("100.00", value("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=7"));
        assertEquals("900.00", value("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=8"));
        assertEquals(0, count("SELECT COUNT(*) FROM LEDGER_JOURNALS"));
        assertEquals(0, count("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
        assertEquals(0, count("SELECT COUNT(*) FROM INCOME_AND_EXPENDITURE_DATA"));
        recycled("ALL_INVESTMENT_ASSETS", 19); recycled("INCOME_AND_EXPENDITURE_DATA", ids);
    }

    @Test public void deletingCashAccountReversesInvestmentContributionAndPreservesUnrelatedJournal() throws Exception {
        cash(7, 1, "Cash", "100.00", "GBP"); investment(19, 1, "Broker", "GBP");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Cash", "Broker", "20.01", null, DAY, "Contribution"));
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Broker", "Retained dividend", "3.00", DAY));
        assertTrue(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 7));
        assertEquals("103.00", value("SELECT REAL_TIME_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        assertEquals("100.00", value("SELECT INVESTMENT_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        assertEquals(1, count("SELECT COUNT(*) FROM LEDGER_JOURNALS"));
        assertEquals(2, count("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
        assertEquals(0, count("SELECT SUM(DEBIT_MINOR)-SUM(CREDIT_MINOR) FROM LEDGER_ENTRIES"));
        recycled("ALL_NON_INVESTMENT_ASSETS", 7); recycled("INCOME_AND_EXPENDITURE_DATA", 1, 2);
    }

    @Test public void recyclerFailureRollsBackParentChildrenJournalsAndCounterpartBalance() throws Exception {
        cash(7, 1, "Cash", "100.00", "GBP"); investment(19, 1, "Broker", "GBP");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Cash", "Broker", "20.01", null, DAY, "Contribution"));
        execute("ALTER TABLE ID_RECORDER_ALL_NON_INVESTMENT_ASSETS ADD CONSTRAINT SYNTHETIC_RECYCLE_FAILURE CHECK(TABLE_ID<>7)");
        assertFalse(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 7));
        assertEquals("79.99", value("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=7"));
        assertEquals("120.01", value("SELECT REAL_TIME_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        assertEquals(2, count("SELECT COUNT(*) FROM INCOME_AND_EXPENDITURE_DATA"));
        assertEquals(2, count("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
        assertEquals(1, count("SELECT COUNT(*) FROM LEDGER_JOURNALS"));
        assertEquals(0, count("SELECT COUNT(*) FROM ID_RECORDER_INCOME_AND_EXPENDITURE_DATA WHERE TABLE_ID>0"));
    }

    @Test public void userCascadeIncludesLegacyRowsAndNeverDeletesOtherOwnersOrGlobalInflation() throws Exception {
        cash(7, 1, "Shared name", "100.00", "GBP"); investment(19, 1, "Broker", "GBP"); cash(8, 2, "Shared name", "900.00", "GBP");
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Shared name", "Mine", "3.00", DAY));
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(2, "income", "Shared name", "Other", "2.00", DAY));
        execute("INSERT INTO INCOME_AND_EXPENDITURE_DATA(ID,USER_ID,TYPE,PLATFORM,NAME,AMOUNT,DATE,CURRENCY) VALUES (99,1,'transfer_in','Shared name','Legacy unknown pair','1.00',?,'GBP')", DAY);
        execute("INSERT INTO ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES(ID,AIR_IN_VARIOUS_CURRENCIES,VARIOUS_CURRENCIES,DATE) VALUES (50,2.50,'GBP',?)", DAY);
        String generation = value("SELECT GENERATION FROM USERS WHERE ID=1");
        assertFalse(DatabaseService.Model.User.deleteUser(1, UUID.randomUUID().toString()));
        assertTrue(DatabaseService.Model.User.deleteUser(1, generation));
        assertFalse(DatabaseService.Model.User.deleteUser(1, generation));
        assertEquals(1, count("SELECT COUNT(*) FROM USERS"));
        assertEquals("902.00", value("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=8"));
        assertEquals(1, count("SELECT COUNT(*) FROM INCOME_AND_EXPENDITURE_DATA WHERE USER_ID=2"));
        assertEquals(1, count("SELECT COUNT(*) FROM LEDGER_JOURNALS WHERE USER_ID=2"));
        assertEquals(2, count("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
        assertEquals(1, count("SELECT COUNT(*) FROM ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES"));
        recycled("USERS", 1); recycled("ALL_NON_INVESTMENT_ASSETS", 7); recycled("ALL_INVESTMENT_ASSETS", 19);
        recycled("BASIC_VARIABLES_AND_DATA", 101); recycled("INCOME_AND_EXPENDITURE_DATA", 1, 99);
    }

    @Test public void unpairedTransferPreventsPartialAccountDeletionWithoutGuessingItsCounterpart() throws Exception {
        cash(7, 1, "Cash", "100.00", "GBP");
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Cash", "Receipt", "3.00", DAY));
        execute("INSERT INTO INCOME_AND_EXPENDITURE_DATA(ID,USER_ID,TYPE,PLATFORM,NAME,AMOUNT,DATE,CURRENCY) VALUES (99,1,'transfer_in','Cash','Legacy unknown pair','1.00',?,'GBP')", DAY);
        assertFalse(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 7));
        assertEquals("103.00", value("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=7"));
        assertEquals(2, count("SELECT COUNT(*) FROM INCOME_AND_EXPENDITURE_DATA"));
        assertEquals(1, count("SELECT COUNT(*) FROM LEDGER_JOURNALS"));
        assertEquals(0, count("SELECT COUNT(*) FROM ID_RECORDER_INCOME_AND_EXPENDITURE_DATA WHERE TABLE_ID>0"));
    }

    @Test public void transactionDeletionRecyclesBothLegsAndRejectsAnUnrelatedJournalReference() throws Exception {
        cash(7, 1, "Cash", "100.00", "GBP"); cash(8, 1, "Savings", "0.00", "GBP");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Cash", "Savings", "10.00", null, DAY, "Transfer"));
        assertTrue(DeleteIncomeOrExpense.deleteIncomeOrExpense(1, 1, null, null, null));
        recycled("INCOME_AND_EXPENDITURE_DATA", 1, 2);
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Cash", "Reused ID", "5.00", DAY));
        assertEquals("1", value("SELECT ID FROM INCOME_AND_EXPENDITURE_DATA"));
        String journal = value("SELECT JOURNAL_ID FROM INCOME_AND_EXPENDITURE_DATA WHERE ID=1");
        execute("INSERT INTO LEDGER_JOURNALS(ID,USER_ID,KIND,DATE,BOOK_CURRENCY,ORIGIN,EVIDENCE) VALUES ('unrelated',2,'income',?,'GBP','synthetic','invalid cross-owner reference')", DAY);
        execute("INSERT INTO LEDGER_ENTRIES(JOURNAL_ID,LINE_NUMBER,RECORD_ID,ACCOUNT_KIND,ACCOUNT_NAME,NATIVE_CURRENCY,NATIVE_AMOUNT,DEBIT_MINOR,CREDIT_MINOR) VALUES ('unrelated',1,1,'asset','Other','GBP','1.00',100,0)");
        assertFalse(DeleteIncomeOrExpense.deleteIncomeOrExpense(1, 1, null, null, null));
        assertFalse(DatabaseService.Model.User.deleteUser(1, value("SELECT GENERATION FROM USERS WHERE ID=1")));
        assertEquals(journal, value("SELECT JOURNAL_ID FROM INCOME_AND_EXPENDITURE_DATA WHERE ID=1"));
        assertEquals("105.00", value("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=7"));
    }

    @Test public void accountCascadeCollectsEveryChildAcrossMultipleBoundedPages() throws Exception {
        cash(7, 1, "Cash", "100.00", "GBP");
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO INCOME_AND_EXPENDITURE_DATA(ID,USER_ID,TYPE,PLATFORM,NAME,AMOUNT,DATE,CURRENCY) VALUES (?,1,'income','Cash','Synthetic legacy row','0.00',?,'GBP')")) {
            for (int id = 1; id <= 205; id++) {
                statement.setInt(1, id); statement.setString(2, DAY); statement.addBatch();
            }
            statement.executeBatch(); connection.commit();
        }
        assertTrue(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 7));
        assertEquals(0, count("SELECT COUNT(*) FROM INCOME_AND_EXPENDITURE_DATA"));
        assertEquals(205, count("SELECT COUNT(*) FROM ID_RECORDER_INCOME_AND_EXPENDITURE_DATA WHERE TABLE_ID>0"));
        recycled("INCOME_AND_EXPENDITURE_DATA", 1, 100, 101, 200, 205);
    }

    @Test public void historicalReclassifiedTransferDoesNotInventAContributionReversal() throws Exception {
        cash(7, 1, "Cash", "100.00", "GBP"); investment(19, 1, "Broker", "GBP");
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Cash", "Broker", "20.01", null, DAY, "Historical transfer"));
        execute("UPDATE LEDGER_ENTRIES SET BALANCE_TYPE='income' WHERE RECORD_ID=2");
        execute("UPDATE LEDGER_ENTRIES SET BALANCE_TYPE='expense' WHERE RECORD_ID=1");
        execute("UPDATE ALL_INVESTMENT_ASSETS SET INVESTMENT_AMOUNT='100.00' WHERE ID=19");
        assertTrue(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 7));
        assertEquals("100.00", value("SELECT REAL_TIME_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        assertEquals("100.00", value("SELECT INVESTMENT_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        recycled("INCOME_AND_EXPENDITURE_DATA", 1, 2);
    }


    @Test public void restoredMixedCollationsPreserveCaseSemanticsDuringAuthenticatedAccountCascade() throws Exception {
        org.junit.Assume.assumeTrue("MySQL collation regression", mysql);
        execute("ALTER TABLE LEDGER_ENTRIES MODIFY ACCOUNT_NAME VARCHAR(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL, "
                + "MODIFY NATIVE_CURRENCY VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL");
        execute("ALTER TABLE INCOME_AND_EXPENDITURE_DATA MODIFY PLATFORM VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL, "
                + "MODIFY CURRENCY VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
        cash(7, 1, "Cash", "100.00", "GBP"); investment(19, 1, "Broker", "GBP");
        assertTrue(AddIncomeOrExpense.addIncomeOrExpense(1, "income", "Cash", "Income before transfer", "5.00", DAY));
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1, "Cash", "Broker", "10.00", null, DAY, "Mixed collations"));
        execute("UPDATE LEDGER_ENTRIES SET ACCOUNT_NAME=LOWER(ACCOUNT_NAME), NATIVE_CURRENCY=LOWER(NATIVE_CURRENCY) WHERE BALANCE_TYPE IN ('transfer_in','transfer_out')");
        Model.User user = new Model.User(); user.setId(1); user.setGeneration(value("SELECT GENERATION FROM USERS WHERE ID=1"));
        RequestIdentity.Record account = new RequestIdentity.Record("ALL_NON_INVESTMENT_ASSETS", 7,
                value("SELECT GENERATION FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=7"));
        try (RequestIdentity ignored = RequestIdentity.begin(user, true, List.of(account), List.of())) {
            assertTrue(DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(1, 7));
        }
        assertEquals("100.00", value("SELECT REAL_TIME_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        assertEquals("100.00", value("SELECT INVESTMENT_AMOUNT FROM ALL_INVESTMENT_ASSETS WHERE ID=19"));
        assertEquals(0, count("SELECT COUNT(*) FROM INCOME_AND_EXPENDITURE_DATA"));
        assertEquals(0, count("SELECT COUNT(*) FROM LEDGER_JOURNALS"));
        assertEquals(0, count("SELECT COUNT(*) FROM LEDGER_ENTRIES"));
        recycled("INCOME_AND_EXPENDITURE_DATA", 1, 2, 3); recycled("ALL_NON_INVESTMENT_ASSETS", 7);
    }

}
