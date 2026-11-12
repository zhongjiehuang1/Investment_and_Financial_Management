package DatabaseService.Model.Controller.AssetsData;

import DatabaseService.DatabaseInit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class CommonFunctions extends DatabaseInit {
    public static boolean assetAlreadyExist(int userID, String name) {
        try (Connection connection = openConnection()) {
            return exists(connection, userID, name, null, 0);
        } catch (SQLException exception) { return true; }
    }

    public static void validate(String name, String type, String risk, String currency) {
        if (name == null || name.isBlank() || name.length() > 50) throw new IllegalArgumentException("Account name must be 1–50 characters.");
        Model.FixedTypes.AssetTypes.valueOf(type);
        Model.FixedTypes.RiskTypes.valueOf(risk);
        Model.FixedTypes.Currencies.valueOf(currency);
    }

    /** Caller holds the owner row lock, making the duplicate-name check atomic with writes. */
    public static void available(Connection connection, int userId, String name, String excludedTable, int excludedId) throws SQLException {
        if (exists(connection, userId, name, excludedTable, excludedId)) throw new IllegalArgumentException("Account name already exists.");
    }

    private static boolean exists(Connection connection, int userId, String name, String excludedTable, int excludedId) throws SQLException {
        for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            for (String table : new String[]{"ALL_NON_INVESTMENT_ASSETS", "ALL_INVESTMENT_ASSETS"}) {
                String column = "ALL_NON_INVESTMENT_ASSETS".equals(table) ? "NAME" : "INVESTMENT_PLATFORM";
                String exclusion = table.equals(excludedTable) && DatabaseService.ShardScope.sameNode(participant, connection) ? " AND ID <> ?" : "";
                try (PreparedStatement statement = participant.prepareStatement("SELECT ID FROM " + table
                        + " WHERE USER_ID = ? AND " + column + " = ?" + exclusion + " LIMIT 1")) {
                    statement.setInt(1, userId); statement.setString(2, name);
                    if (!exclusion.isEmpty()) statement.setInt(3, excludedId);
                    try (ResultSet result = statement.executeQuery()) { if (result.next()) return true; }
                }
            }
        }
        return false;
    }

    public static boolean hasTransactions(Connection connection, int userId, String name) throws SQLException {
        for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            try (PreparedStatement statement = participant.prepareStatement("SELECT ID FROM INCOME_AND_EXPENDITURE_DATA "
                    + "WHERE USER_ID = ? AND PLATFORM = ? LIMIT 1")) {
                statement.setInt(1, userId); statement.setString(2, name);
                try (ResultSet result = statement.executeQuery()) { if (result.next()) return true; }
            }
        }
        return false;
    }

    public static void rename(Connection connection, int userId, String oldName, String newName, String oldCurrency, String newCurrency) throws SQLException {
        if (!oldCurrency.equals(newCurrency) && hasTransactions(connection, userId, oldName)) {
            throw new IllegalArgumentException("An account with historical transactions cannot change currency.");
        }
        if (!oldName.equals(newName)) {
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            try (PreparedStatement statement = participant.prepareStatement("UPDATE INCOME_AND_EXPENDITURE_DATA SET PLATFORM = ? WHERE USER_ID = ? AND PLATFORM = ?")) {
                statement.setString(1, newName); statement.setInt(2, userId); statement.setString(3, oldName);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = participant.prepareStatement("UPDATE LEDGER_ENTRIES SET ACCOUNT_NAME = ? "
                    + "WHERE ACCOUNT_KIND = 'asset' AND ACCOUNT_NAME = ? "
                    + "AND JOURNAL_ID IN (SELECT ID FROM LEDGER_JOURNALS WHERE USER_ID = ?)")) {
                statement.setString(1, newName); statement.setString(2, oldName); statement.setInt(3, userId);
                statement.executeUpdate();
            }
            }
        }
    }
}
