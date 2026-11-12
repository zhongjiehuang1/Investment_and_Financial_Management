package DatabaseService.Model;

import DatabaseService.DatabaseInit;
import DatabaseService.Model.Controller.ReadData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public class User extends DatabaseInit {
    public static boolean importData_currenciesChosen(Model.User user) {
        try (Connection connection = openPrimaryConnection()) {
            java.util.Set<String> currencies = new java.util.TreeSet<>();
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
                try (PreparedStatement statement = participant.prepareStatement(
                        "SELECT DISTINCT CURRENCY FROM ALL_NON_INVESTMENT_ASSETS WHERE USER_ID = ? UNION "
                                + "SELECT DISTINCT CURRENCY FROM ALL_INVESTMENT_ASSETS WHERE USER_ID = ? ORDER BY CURRENCY")) {
                    statement.setInt(1, user.getId()); statement.setInt(2, user.getId());
                    try (ResultSet result = statement.executeQuery()) { while (result.next()) currencies.add(result.getString(1)); }
                }
            }
            user.setCurrencies(currencies.toArray(new String[0])); return true;
        } catch (SQLException exception) { return false; }
    }

    /** Compatibility only: applications must request subsequent platform pages explicitly. */
    public static boolean importData_allPlatforms(Model.User user) {
        try {
            JSONArray items = ReadData.platformsPage(user.getId(), 1, 100, "").getJSONArray("items");
            String[] platforms = new String[items.length()];
            for (int i = 0; i < items.length(); i++) platforms[i] = items.getJSONObject(i).getString("name");
            user.setPlatforms(platforms); return true;
        } catch (SQLException exception) { return false; }
    }

    /** Delete the authenticated user's complete private data atomically; the generation prevents reused identities. */
    public static boolean deleteUser(int userId, String generation) {
        if (generation == null || generation.isBlank()) return false;
        String[] children = {"INCOME_AND_EXPENDITURE_DATA", "ALL_INVESTMENT_ASSETS",
                "ALL_NON_INVESTMENT_ASSETS", "BASIC_VARIABLES_AND_DATA"};
        try (Connection connection = openPrimaryConnection()) {
            try {
                lockUser(connection, userId);
                for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
                lockIdTables(participant, "USERS", "INCOME_AND_EXPENDITURE_DATA", "ALL_INVESTMENT_ASSETS",
                        "ALL_NON_INVESTMENT_ASSETS", "BASIC_VARIABLES_AND_DATA");
                try (PreparedStatement statement = participant.prepareStatement("SELECT ID FROM USERS WHERE ID=? AND GENERATION=? FOR UPDATE")) {
                    statement.setInt(1, userId); statement.setString(2, generation);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) { rollback(connection); return false; }
                    }
                }
                DatabaseService.Model.Controller.IncomeAndExpenditureData.Ledger.removeOwnedJournals(participant, userId);
                // Explicitly collect every integer child ID before removal; FK cascades cannot populate recycle pools.
                for (String table : children) deleteOwnedRows(participant, table, userId);
                try (PreparedStatement statement = participant.prepareStatement("DELETE FROM USERS WHERE ID=? AND GENERATION=?")) {
                    statement.setInt(1, userId); statement.setString(2, generation);
                    if (statement.executeUpdate() != 1) throw new SQLException("User deletion did not affect the authenticated identity.");
                }
                if (DatabaseService.ShardScope.nodeOf(participant) == 1) recycleId(participant, "USERS", userId);
                }
                connection.commit(); return true;
            } catch (SQLException | IllegalArgumentException | ArithmeticException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }

    private static void deleteOwnedRows(Connection connection, String table, int userId) throws SQLException {
        while (true) {
            List<Integer> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM " + table + " WHERE USER_ID=? ORDER BY ID LIMIT 100 FOR UPDATE")) {
                statement.setInt(1, userId);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(result.getInt(1)); }
            }
            if (ids.isEmpty()) return;
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE ID=? AND USER_ID=?")) {
                for (int id : ids) {
                    statement.setInt(1, id); statement.setInt(2, userId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Owned child disappeared during deletion.");
                    recycleId(connection, table, id);
                }
            }
        }
    }

}
