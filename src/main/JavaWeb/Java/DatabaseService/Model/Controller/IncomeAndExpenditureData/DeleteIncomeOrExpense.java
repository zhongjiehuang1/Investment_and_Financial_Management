package DatabaseService.Model.Controller.IncomeAndExpenditureData;

import DatabaseService.DatabaseInit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DeleteIncomeOrExpense extends DatabaseInit {
    /** Client-supplied type/account/amount are deliberately ignored; the owned stored row is authoritative. */
    public static boolean deleteIncomeOrExpense(int userID, int id, String ignoredType, String ignoredPlatform, String ignoredAmount) {
        try (Connection connection = openConnection()) {
            try {
                lockUser(connection, userID);
                lockIdTables(connection, "INCOME_AND_EXPENDITURE_DATA");
                if (!deleteRecord(connection, userID, id, null, 0, 0)) { rollback(connection); return false; }
                connection.commit(); return true;
            } catch (SQLException | IllegalArgumentException | ArithmeticException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }

    /** Caller holds the owner and both affected ID-table locks; the entire cascade shares its transaction. */
    public static void deleteAccountRecords(Connection connection, int userId, String name, String table, int accountId) throws SQLException {
        int ownerNode = DatabaseService.ShardScope.nodeOf(connection);
        for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            lockIdTables(participant, "INCOME_AND_EXPENDITURE_DATA");
            deleteAccountRecordsOnNode(participant, userId, name, table, accountId, ownerNode);
        }
    }

    private static void deleteAccountRecordsOnNode(Connection connection, int userId, String name, String table, int accountId, int ownerNode) throws SQLException {
        // Fetch a bounded page each time: deleting a transfer also removes its other account's leg.
        while (true) {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM INCOME_AND_EXPENDITURE_DATA "
                    + "WHERE USER_ID = ? AND PLATFORM = ? ORDER BY ID LIMIT 100 FOR UPDATE")) {
                statement.setInt(1, userId); statement.setString(2, name);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(result.getInt(1)); }
            }
            if (ids.isEmpty()) break;
            for (int id : ids) if (!deleteRecord(connection, userId, id, table, accountId, ownerNode)) {
                throw new SQLException("An account transaction disappeared during deletion.");
            }
        }
        // Unattached historical entries need reconciliation, not an invented counterpart balance adjustment.
        try (PreparedStatement statement = connection.prepareStatement("SELECT E.JOURNAL_ID FROM LEDGER_ENTRIES E "
                + "JOIN LEDGER_JOURNALS J ON J.ID = E.JOURNAL_ID WHERE J.USER_ID = ? "
                + "AND E.ACCOUNT_KIND = 'asset' AND E.ACCOUNT_NAME = ? LIMIT 1")) {
            statement.setInt(1, userId); statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new SQLException("Unattached account journal requires reconciliation before deletion.");
            }
        }
    }

    private static boolean deleteRecord(Connection connection, int userId, int id, String removedTable, int removedId, int removedNode) throws SQLException {
        String type, platform, amount, currency;
        try (PreparedStatement statement = connection.prepareStatement("SELECT TYPE, PLATFORM, AMOUNT, CURRENCY FROM "
                + "INCOME_AND_EXPENDITURE_DATA WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
            statement.setInt(1, id); statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                type = result.getString("TYPE"); platform = result.getString("PLATFORM");
                amount = result.getString("AMOUNT"); currency = result.getString("CURRENCY");
            }
        }
        if (type.startsWith("transfer_")) return Ledger.deleteTransfer(connection, userId, id, removedTable, removedId, removedNode);
        Ledger.removeExternal(connection, userId, id);
        // The deleted parent needs no reversal. Surviving accounts must reverse the authoritative stored effect.
        if (removedTable == null && Model.Tools.Computing.toMinorUnits(amount) != 0) {
            CommonFunctionsNonDatabaseAbstraction.Account account = CommonFunctionsNonDatabaseAbstraction.find(connection, userId, platform);
            if (!account.currency.equals(currency)) throw new IllegalArgumentException("The historical transaction currency differs from the account currency.");
            CommonFunctionsNonDatabaseAbstraction.change(account, type, amount, true);
            CommonFunctionsNonDatabaseAbstraction.save(connection, account);
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM INCOME_AND_EXPENDITURE_DATA WHERE ID = ? AND USER_ID = ?")) {
            statement.setInt(1, id); statement.setInt(2, userId);
            if (statement.executeUpdate() != 1) throw new SQLException("Transaction delete did not affect one owned row.");
        }
        recycleId(connection, "INCOME_AND_EXPENDITURE_DATA", id);
        return true;
    }
}
