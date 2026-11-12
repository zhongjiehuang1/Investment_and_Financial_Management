package DatabaseService.Model.Controller.AssetsData.InvestmentAsset;

import DatabaseService.DatabaseInit;
import DatabaseService.Model.Controller.AssetsData.CommonFunctions;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.DeleteIncomeOrExpense;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DeleteAsset extends DatabaseInit {
    public static boolean deleteInvestmentAsset(int userId, int id) {
        try (Connection connection = openConnection()) {
            try {
                lockUser(connection, userId);
                lockIdTables(connection, "ALL_INVESTMENT_ASSETS", "INCOME_AND_EXPENDITURE_DATA");
                String name;
                try (PreparedStatement statement = connection.prepareStatement("SELECT INVESTMENT_PLATFORM FROM ALL_INVESTMENT_ASSETS WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
                    statement.setInt(1, id); statement.setInt(2, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) { rollback(connection); return false; }
                        name = result.getString(1);
                    }
                }
                // Historical relationships use account names, so a duplicate must be resolved before deletion.
                CommonFunctions.available(connection, userId, name, "ALL_INVESTMENT_ASSETS", id);
                DeleteIncomeOrExpense.deleteAccountRecords(connection, userId, name, "ALL_INVESTMENT_ASSETS", id);
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM ALL_INVESTMENT_ASSETS WHERE ID = ? AND USER_ID = ?")) {
                    statement.setInt(1, id); statement.setInt(2, userId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Account delete did not affect one owned row.");
                }
                recycleId(connection, "ALL_INVESTMENT_ASSETS", id);
                connection.commit(); return true;
            } catch (SQLException | IllegalArgumentException | ArithmeticException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }
}
