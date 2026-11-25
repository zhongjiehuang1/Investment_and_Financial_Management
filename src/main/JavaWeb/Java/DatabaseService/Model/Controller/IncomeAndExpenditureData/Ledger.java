package DatabaseService.Model.Controller.IncomeAndExpenditureData;

import Model.Tools.Computing;
import java.sql.*;
import java.util.*;

/** Balanced journals accompany the existing MVC transaction records and account balances. */
public final class Ledger extends DatabaseService.DatabaseInit {
    private Ledger() { }

    public static void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS LEDGER_JOURNALS (ID VARCHAR(36) PRIMARY KEY, USER_ID INT NOT NULL, KIND VARCHAR(20) NOT NULL, DATE DATE NOT NULL, BOOK_CURRENCY VARCHAR(50) NOT NULL, ORIGIN VARCHAR(30) NOT NULL, EVIDENCE VARCHAR(255) NOT NULL, FOREIGN KEY(USER_ID) REFERENCES USERS(ID))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS LEDGER_ENTRIES (JOURNAL_ID VARCHAR(36) NOT NULL, LINE_NUMBER INT NOT NULL, RECORD_ID INT, ACCOUNT_KIND VARCHAR(30) NOT NULL, ACCOUNT_NAME VARCHAR(150) NOT NULL, NATIVE_CURRENCY VARCHAR(50) NOT NULL, NATIVE_AMOUNT VARCHAR(15) NOT NULL, DEBIT_MINOR BIGINT NOT NULL, CREDIT_MINOR BIGINT NOT NULL, BALANCE_TYPE VARCHAR(50), PRIMARY KEY(JOURNAL_ID, LINE_NUMBER), FOREIGN KEY(JOURNAL_ID) REFERENCES LEDGER_JOURNALS(ID))");
        }
        boolean exists = false;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "INCOME_AND_EXPENDITURE_DATA", "JOURNAL_ID")) { exists = columns.next(); }
        if (!exists) try (Statement statement = connection.createStatement()) { statement.executeUpdate("ALTER TABLE INCOME_AND_EXPENDITURE_DATA ADD COLUMN JOURNAL_ID VARCHAR(36)"); }
        boolean indexed = false;
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "INCOME_AND_EXPENDITURE_DATA", false, false)) {
            while (indexes.next()) if ("IDX_IAFM_TRANSACTION_JOURNAL".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) indexed = true;
        }
        if (!indexed) try (Statement statement = connection.createStatement()) { statement.executeUpdate("CREATE INDEX IDX_IAFM_TRANSACTION_JOURNAL ON INCOME_AND_EXPENDITURE_DATA (JOURNAL_ID, USER_ID)"); }
    }

    static void external(Connection connection, int userId, int id, String type, String platform,
                         String amount, java.sql.Date date, String currency) throws SQLException {
        if (!Set.of("income", "expense").contains(type)) throw new IllegalArgumentException("Use a paired transfer for internal movements.");
        String journal = create(connection, userId, type, date, currency, "application", "User entered external cash flow");
        long minor = Computing.toMinorUnits(amount);
        boolean income = "income".equals(type);
        entry(connection, journal, 1, id, "asset", platform, currency, amount, income ? minor : 0, income ? 0 : minor, type);
        entry(connection, journal, 2, null, income ? "external_income" : "external_expense", income ? "Income" : "Expense", currency, amount, income ? 0 : minor, income ? minor : 0, null);
        attach(connection, userId, id, journal);
        validate(connection, journal);
    }

    static void transfer(Connection connection, int userId, int outId, int inId,
                         CommonFunctionsNonDatabaseAbstraction.Account from, CommonFunctionsNonDatabaseAbstraction.Account to,
                         String outgoing, String incoming, java.sql.Date date) throws SQLException {
        String journal = create(connection, userId, "transfer", date, from.currency, "application", "Owned accounts; actual sent and received amounts; fees recorded separately");
        // The sent currency is the book currency. The receiving asset retains its actual native amount.
        // Its book cost is the actual amount surrendered, so FX does not manufacture income or expense.
        long bookMinor = Computing.toMinorUnits(outgoing);
        entry(connection, journal, 1, outId, "asset", from.name, from.currency, outgoing, 0, bookMinor, "transfer_out");
        entry(connection, journal, 2, inId, "asset", to.name, to.currency, incoming, bookMinor, 0, "transfer_in");
        attach(connection, userId, outId, journal); attach(connection, userId, inId, journal);
        validate(connection, journal);
    }

    private static String create(Connection connection, int userId, String kind, java.sql.Date date,
                                 String currency, String origin, String evidence) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO LEDGER_JOURNALS (ID, USER_ID, KIND, DATE, BOOK_CURRENCY, ORIGIN, EVIDENCE) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1,id); statement.setInt(2,userId); statement.setString(3,kind); statement.setDate(4,date);
            statement.setString(5,currency); statement.setString(6,origin); statement.setString(7,evidence); statement.executeUpdate();
        }
        return id;
    }

    private static void entry(Connection connection, String journal, int line, Integer recordId, String kind,
                              String account, String currency, String amount, long debit, long credit, String balanceType) throws SQLException {
        if (debit < 0 || credit < 0 || (debit == 0) == (credit == 0)) throw new IllegalArgumentException("Each entry must have exactly one positive side.");
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO LEDGER_ENTRIES (JOURNAL_ID, LINE_NUMBER, RECORD_ID, ACCOUNT_KIND, ACCOUNT_NAME, NATIVE_CURRENCY, NATIVE_AMOUNT, DEBIT_MINOR, CREDIT_MINOR, BALANCE_TYPE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1,journal); statement.setInt(2,line); if(recordId == null) statement.setNull(3,Types.INTEGER); else statement.setInt(3,recordId);
            statement.setString(4,kind); statement.setString(5,account); statement.setString(6,currency); statement.setString(7,amount);
            statement.setLong(8,debit); statement.setLong(9,credit); statement.setString(10,balanceType); statement.executeUpdate();
        }
    }

    private static void attach(Connection connection, int userId, int id, String journal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE INCOME_AND_EXPENDITURE_DATA SET JOURNAL_ID = ? WHERE ID = ? AND USER_ID = ? AND JOURNAL_ID IS NULL")) {
            statement.setString(1,journal); statement.setInt(2,id); statement.setInt(3,userId);
            if(statement.executeUpdate()!=1) throw new SQLException("A record must belong to exactly one journal.");
        }
    }

    public static void validate(Connection connection, String journal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*), SUM(DEBIT_MINOR), SUM(CREDIT_MINOR) FROM LEDGER_ENTRIES WHERE JOURNAL_ID = ?")) {
            statement.setString(1,journal);
            try(ResultSet result=statement.executeQuery()) {
                if(!result.next() || result.getInt(1)<2 || result.getLong(2)<=0 || result.getLong(2)!=result.getLong(3)) throw new SQLException("Journal is not balanced.");
            }
        }
    }

    static void removeExternal(Connection connection, int userId, int recordId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT JOURNAL_ID, TYPE, AMOUNT, CURRENCY, PLATFORM "
                + "FROM INCOME_AND_EXPENDITURE_DATA WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
            statement.setInt(1, recordId); statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing transaction.");
                String type = result.getString("TYPE"), journal = result.getString("JOURNAL_ID");
                if (!Set.of("income", "expense").contains(type)) throw new IllegalArgumentException("A transfer cannot be edited one leg at a time.");
                requireRecordReferences(connection, recordId, journal);
                if (journal == null) return;
                requireJournalShape(connection, userId, journal, type, 1);
                try (PreparedStatement entry = connection.prepareStatement("SELECT NATIVE_AMOUNT, BALANCE_TYPE, DEBIT_MINOR, CREDIT_MINOR "
                        + "FROM LEDGER_ENTRIES WHERE JOURNAL_ID = ? AND RECORD_ID = ? AND ACCOUNT_KIND = 'asset' "
                        + "AND ACCOUNT_NAME = ? AND NATIVE_CURRENCY = ?")) {
                    entry.setString(1, journal); entry.setInt(2, recordId); entry.setString(3, result.getString("PLATFORM"));
                    entry.setString(4, result.getString("CURRENCY"));
                    try (ResultSet line = entry.executeQuery()) {
                        if (!line.next() || !type.equals(line.getString("BALANCE_TYPE"))
                                || Computing.toMinorUnits(result.getString("AMOUNT")) != Computing.toMinorUnits(line.getString("NATIVE_AMOUNT"))
                                || ("income".equals(type) ? line.getLong("DEBIT_MINOR") <= 0 : line.getLong("CREDIT_MINOR") <= 0)
                                || line.next()) throw new SQLException("External transaction journal does not match its record.");
                    }
                }
                try (PreparedStatement entry = connection.prepareStatement("SELECT COUNT(*) FROM LEDGER_ENTRIES "
                        + "WHERE JOURNAL_ID = ? AND RECORD_ID IS NULL AND ACCOUNT_KIND = ?")) {
                    entry.setString(1, journal); entry.setString(2, "external_" + type);
                    try (ResultSet line = entry.executeQuery()) {
                        if (!line.next() || line.getInt(1) != 1) throw new SQLException("External journal requires its balancing entry.");
                    }
                }
                remove(connection, userId, journal);
            }
        }
    }

    /** Explicit entry deletion makes journal cleanup independent of FK cascade behavior. */
    static void remove(Connection connection, int userId, String journal) throws SQLException {
        requireJournalOwner(connection, userId, journal);
        try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM INCOME_AND_EXPENDITURE_DATA "
                + "WHERE JOURNAL_ID = ? AND USER_ID <> ? LIMIT 1")) {
            statement.setString(1, journal); statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new SQLException("Journal is referenced by another owner.");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM LEDGER_ENTRIES WHERE JOURNAL_ID = ?")) {
            statement.setString(1, journal); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE INCOME_AND_EXPENDITURE_DATA SET JOURNAL_ID = NULL WHERE JOURNAL_ID = ? AND USER_ID = ?")) {
            statement.setString(1, journal); statement.setInt(2, userId); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM LEDGER_JOURNALS WHERE ID = ? AND USER_ID = ?")) {
            statement.setString(1, journal); statement.setInt(2, userId);
            if (statement.executeUpdate() != 1) throw new SQLException("Journal ownership mismatch.");
        }
    }

    static boolean deleteTransfer(Connection connection, int userId, int recordId, String removedTable, int removedId, int removedNode) throws SQLException {
        String journal;
        try (PreparedStatement statement = connection.prepareStatement("SELECT JOURNAL_ID FROM INCOME_AND_EXPENDITURE_DATA WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
            statement.setInt(1, recordId); statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || (journal = result.getString(1)) == null) throw new IllegalArgumentException("Legacy transfer requires reconciliation before deletion.");
            }
        }
        requireJournalShape(connection, userId, journal, "transfer", 2);
        List<Integer> ids = new ArrayList<>();
        List<CommonFunctionsNonDatabaseAbstraction.Account> accounts = new ArrayList<>();
        Set<String> directions = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT T.ID,T.TYPE,T.PLATFORM,T.AMOUNT,T.CURRENCY,"
                + "E.BALANCE_TYPE,E.NATIVE_AMOUNT,E.DEBIT_MINOR,E.CREDIT_MINOR FROM INCOME_AND_EXPENDITURE_DATA T "
                + "JOIN LEDGER_ENTRIES E ON E.JOURNAL_ID=T.JOURNAL_ID AND E.RECORD_ID=T.ID "
                + "AND E.ACCOUNT_KIND='asset' "
                + "WHERE T.JOURNAL_ID=? AND T.USER_ID=? ORDER BY T.ID FOR UPDATE");
             PreparedStatement identity = connection.prepareStatement("SELECT LINE_NUMBER FROM LEDGER_ENTRIES "
                     + "WHERE JOURNAL_ID=? AND RECORD_ID=? AND ACCOUNT_NAME=? AND NATIVE_CURRENCY=?")) {
            statement.setString(1, journal); statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String type = result.getString("TYPE"), originalType = result.getString("BALANCE_TYPE");
                    if (!Set.of("transfer_in", "transfer_out").contains(type)
                            || !(type.equals(originalType) || ("transfer_in".equals(type) ? "income" : "expense").equals(originalType))
                            || Computing.toMinorUnits(result.getString("AMOUNT")) != Computing.toMinorUnits(result.getString("NATIVE_AMOUNT"))
                            || ("transfer_in".equals(type) ? result.getLong("DEBIT_MINOR") <= 0 : result.getLong("CREDIT_MINOR") <= 0)) {
                        throw new SQLException("Invalid transfer journal.");
                    }
                    int id = result.getInt("ID");
                    // Bind text values instead of joining differently collated historical columns. This preserves
                    // the ledger column's case semantics without requiring a schema-wide text conversion.
                    identity.setString(1, journal); identity.setInt(2, id);
                    identity.setString(3, result.getString("PLATFORM")); identity.setString(4, result.getString("CURRENCY"));
                    try (ResultSet matching = identity.executeQuery()) {
                        if (!matching.next() || matching.next()) throw new SQLException("Transfer entry identity differs from its record.");
                    }
                    requireRecordReferences(connection, id, journal);
                    directions.add(type); ids.add(id);
                    CommonFunctionsNonDatabaseAbstraction.Account account = CommonFunctionsNonDatabaseAbstraction.find(connection, userId, result.getString("PLATFORM"));
                    if (!account.currency.equals(result.getString("CURRENCY"))) throw new IllegalArgumentException("Historical account currency differs.");
                    String table = account.investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS";
                    // Historical reclassification retains the original contribution semantics. The removed parent needs no reversal.
                    if (!(table.equals(removedTable) && account.id == removedId && account.databaseId == removedNode)) {
                        CommonFunctionsNonDatabaseAbstraction.change(account, originalType, result.getString("AMOUNT"), true);
                    }
                    accounts.add(account);
                }
            }
        }
        if (ids.size() != 2 || directions.size() != 2 || (accounts.get(0).databaseId == accounts.get(1).databaseId && accounts.get(0).id == accounts.get(1).id && accounts.get(0).investment == accounts.get(1).investment)) {
            throw new SQLException("Transfer requires two different owned accounts.");
        }
        for (CommonFunctionsNonDatabaseAbstraction.Account account : accounts) {
            String table = account.investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS";
            if (!(table.equals(removedTable) && account.id == removedId && account.databaseId == removedNode)) CommonFunctionsNonDatabaseAbstraction.save(connection, account);
        }
        remove(connection, userId, journal);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM INCOME_AND_EXPENDITURE_DATA WHERE ID IN (?,?) AND USER_ID=?")) {
            statement.setInt(1, ids.get(0)); statement.setInt(2, ids.get(1)); statement.setInt(3, userId);
            if (statement.executeUpdate() != 2) throw new SQLException("Both transfer legs must be deleted together.");
        }
        for (int id : ids) recycleId(connection, "INCOME_AND_EXPENDITURE_DATA", id);
        return true;
    }

    /** User deletion removes every owned journal, but must never detach another tenant's records. */
    public static void removeOwnedJournals(Connection connection, int userId) throws SQLException {
        String[] conflicts = {
                "SELECT T.ID FROM INCOME_AND_EXPENDITURE_DATA T LEFT JOIN LEDGER_JOURNALS J ON J.ID=T.JOURNAL_ID "
                        + "WHERE T.USER_ID=? AND T.JOURNAL_ID IS NOT NULL AND (J.ID IS NULL OR J.USER_ID<>?) LIMIT 1",
                "SELECT T.ID FROM INCOME_AND_EXPENDITURE_DATA T JOIN LEDGER_JOURNALS J ON J.ID=T.JOURNAL_ID WHERE J.USER_ID=? AND T.USER_ID<>? LIMIT 1",
                "SELECT T.ID FROM LEDGER_ENTRIES E JOIN INCOME_AND_EXPENDITURE_DATA T ON T.ID=E.RECORD_ID "
                        + "LEFT JOIN LEDGER_JOURNALS J ON J.ID=E.JOURNAL_ID WHERE T.USER_ID=? AND (J.ID IS NULL OR J.USER_ID<>?) LIMIT 1",
                "SELECT T.ID FROM LEDGER_ENTRIES E JOIN LEDGER_JOURNALS J ON J.ID=E.JOURNAL_ID "
                        + "JOIN INCOME_AND_EXPENDITURE_DATA T ON T.ID=E.RECORD_ID WHERE J.USER_ID=? AND T.USER_ID<>? LIMIT 1"
        };
        for (String sql : conflicts) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, userId); statement.setInt(2, userId);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) throw new SQLException("Cross-owner journal reference requires reconciliation.");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM LEDGER_ENTRIES WHERE JOURNAL_ID IN (SELECT ID FROM LEDGER_JOURNALS WHERE USER_ID=?)")) {
            statement.setInt(1, userId); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE INCOME_AND_EXPENDITURE_DATA SET JOURNAL_ID=NULL WHERE USER_ID=? AND JOURNAL_ID IS NOT NULL")) {
            statement.setInt(1, userId); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM LEDGER_JOURNALS WHERE USER_ID=?")) {
            statement.setInt(1, userId); statement.executeUpdate();
        }
    }

    private static void requireJournalOwner(Connection connection, int userId, String journal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM LEDGER_JOURNALS WHERE ID=? AND USER_ID=? FOR UPDATE")) {
            statement.setString(1, journal); statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw new SQLException("Journal ownership mismatch."); }
        }
    }

    private static void requireJournalShape(Connection connection, int userId, String journal, String kind, int records) throws SQLException {
        requireJournalOwner(connection, userId, journal);
        try (PreparedStatement statement = connection.prepareStatement("SELECT KIND FROM LEDGER_JOURNALS WHERE ID=?")) {
            statement.setString(1, journal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !kind.equals(result.getString(1))) throw new SQLException("Journal kind mismatch.");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*), MIN(USER_ID), MAX(USER_ID) FROM INCOME_AND_EXPENDITURE_DATA WHERE JOURNAL_ID=?")) {
            statement.setString(1, journal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != records || result.getInt(2) != userId || result.getInt(3) != userId) {
                    throw new SQLException("Journal has unexpected transaction references.");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM LEDGER_ENTRIES WHERE JOURNAL_ID=?")) {
            statement.setString(1, journal);
            try (ResultSet result = statement.executeQuery()) { if (!result.next() || result.getInt(1) != 2) throw new SQLException("Journal has unexpected entries."); }
        }
        validate(connection, journal);
    }

    private static void requireRecordReferences(Connection connection, int recordId, String journal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT JOURNAL_ID FROM LEDGER_ENTRIES WHERE RECORD_ID=?"
                + (journal == null ? "" : " AND JOURNAL_ID<>?") + " LIMIT 1")) {
            statement.setInt(1, recordId); if (journal != null) statement.setString(2, journal);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new SQLException("Transaction has an unexpected journal reference.");
            }
        }
    }
}
