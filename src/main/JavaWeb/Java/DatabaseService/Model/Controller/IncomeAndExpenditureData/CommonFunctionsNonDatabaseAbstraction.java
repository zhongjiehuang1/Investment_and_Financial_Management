package DatabaseService.Model.Controller.IncomeAndExpenditureData;

import DatabaseService.DatabaseInit;
import Model.Tools.Computing;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/** Changes a stored balance under the caller's transaction; no SQL floating point arithmetic. */
class CommonFunctionsNonDatabaseAbstraction extends DatabaseInit {
    private static final Set<String> TYPES = Set.of("income", "expense", "transfer_in", "transfer_out");

    static String amount(String value) {
        String normalized = databaseAmount(value);
        if (Computing.toMinorUnits(normalized) <= 0) throw new IllegalArgumentException("Amount must be positive.");
        return normalized;
    }

    static void validateType(String type) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Invalid transaction type.");
    }

    static java.sql.Date date(String value) {
        return value == null || value.isBlank() ? java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.UTC)) : recordDate(value);
    }

    static Account find(Connection connection, int userId, String platform) throws SQLException {
        if (platform == null || platform.isBlank() || platform.length() > 50) throw new IllegalArgumentException("Invalid account.");
        Account found = null;
        for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
        for (boolean investment : new boolean[]{false, true}) {
            String table = investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS";
            String name = investment ? "INVESTMENT_PLATFORM" : "NAME";
            String columns = (investment ? "ID, CURRENCY, REAL_TIME_AMOUNT, INVESTMENT_AMOUNT, CASH_WITHDRAWAL" : "ID, CURRENCY, AMOUNT") + ", " + name + " AS ACCOUNT_NAME";
            try (PreparedStatement statement = participant.prepareStatement("SELECT " + columns + " FROM " + table
                    + " WHERE USER_ID = ? AND " + name + " = ? FOR UPDATE")) {
                statement.setInt(1, userId); statement.setString(2, platform);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (found != null) throw new IllegalArgumentException("Account name is ambiguous; rename duplicate accounts first.");
                        found = new Account(); found.id = result.getInt("ID"); found.userId = userId;
                        found.owner = participant; found.databaseId = DatabaseService.ShardScope.nodeOf(participant);
                        found.investment = investment; found.currency = result.getString("CURRENCY"); found.name = result.getString("ACCOUNT_NAME");
                        found.balance = Computing.toMinorUnits(result.getString(investment ? "REAL_TIME_AMOUNT" : "AMOUNT"));
                        if (investment) {
                            found.invested = Computing.toMinorUnits(result.getString("INVESTMENT_AMOUNT"));
                            found.withdrawn = Computing.toMinorUnits(result.getString("CASH_WITHDRAWAL"));
                        }
                    }
                }
            }
        }
        }
        if (found == null) throw new IllegalArgumentException("Account does not belong to this user.");
        return found;
    }

    static void change(Account account, String type, String amount, boolean reverse) {
        validateType(type);
        long value = Computing.toMinorUnits(amount);
        if (value <= 0) throw new IllegalArgumentException("Amount must be positive.");
        if (reverse) value = Math.negateExact(value);
        boolean incoming = "income".equals(type) || "transfer_in".equals(type);
        account.balance = incoming ? Math.addExact(account.balance, value) : Math.subtractExact(account.balance, value);
        if (account.investment) {
            if ("transfer_in".equals(type)) account.invested = Math.addExact(account.invested, value);
            if ("transfer_out".equals(type)) account.withdrawn = Math.addExact(account.withdrawn, value);
        }
    }

    static void save(Connection connection, Account account) throws SQLException {
        String table = account.investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS";
        String columns = account.investment ? "REAL_TIME_AMOUNT = ?, INVESTMENT_AMOUNT = ?, CASH_WITHDRAWAL = ?" : "AMOUNT = ?";
        try (PreparedStatement statement = account.owner.prepareStatement("UPDATE " + table + " SET " + columns + " WHERE ID = ? AND USER_ID = ?")) {
            statement.setString(1, databaseAmount(Computing.fromMinorUnits(account.balance)));
            int index = 2;
            if (account.investment) {
                statement.setString(index++, databaseAmount(Computing.fromMinorUnits(account.invested)));
                statement.setString(index++, databaseAmount(Computing.fromMinorUnits(account.withdrawn)));
            }
            statement.setInt(index++, account.id); statement.setInt(index, account.userId);
            if (statement.executeUpdate() != 1) throw new SQLException("Account update did not affect one owned row.");
        }
    }

    static void insert(Connection connection, int id, int userId, String type, String platform, String name,
                       String amount, java.sql.Date date, String currency) throws SQLException {
        validateType(type);
        if (name == null || name.isBlank() || name.length() > 150) throw new IllegalArgumentException("Invalid transaction description.");
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO INCOME_AND_EXPENDITURE_DATA "
                + "(ID, USER_ID, TYPE, PLATFORM, NAME, AMOUNT, DATE, CURRENCY) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, id); statement.setInt(2, userId); statement.setString(3, type); statement.setString(4, platform);
            statement.setString(5, name); statement.setString(6, amount(amount)); statement.setDate(7, date); statement.setString(8, currency);
            if (statement.executeUpdate() != 1) throw new SQLException("Transaction was not inserted.");
        }
    }

    static class Account {
        int id, userId, databaseId;
        Connection owner;
        boolean investment;
        String currency, name;
        long balance, invested, withdrawn;
    }
}
