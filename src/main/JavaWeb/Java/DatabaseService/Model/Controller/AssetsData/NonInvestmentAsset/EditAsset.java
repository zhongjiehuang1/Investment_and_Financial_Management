package DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset;

import DatabaseService.DatabaseInit;
import DatabaseService.Model.Controller.AssetsData.CommonFunctions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class EditAsset extends DatabaseInit {
    public static boolean editNonInvestmentAsset(int userId, int id, String assetName, String assetType,
            String riskType, String amount, String currency) {
        String normalized = databaseAmount(amount);
        CommonFunctions.validate(assetName, assetType, riskType, currency);
        try (Connection connection = openConnection()) {
            try {
                lockUser(connection, userId);
                String oldName, oldCurrency;
                try (PreparedStatement statement = connection.prepareStatement("SELECT NAME, CURRENCY FROM ALL_NON_INVESTMENT_ASSETS WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
                    statement.setInt(1, id); statement.setInt(2, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) { rollback(connection); return false; }
                        oldName = result.getString("NAME"); oldCurrency = result.getString("CURRENCY");
                    }
                }
                CommonFunctions.available(connection, userId, assetName, "ALL_NON_INVESTMENT_ASSETS", id);
                CommonFunctions.rename(connection, userId, oldName, assetName, oldCurrency, currency);
                try (PreparedStatement statement = connection.prepareStatement("UPDATE ALL_NON_INVESTMENT_ASSETS "
                        + "SET NAME = ?, AMOUNT = ?, TYPE = ?, RISK_TYPE = ?, CURRENCY = ? WHERE ID = ? AND USER_ID = ?")) {
                    statement.setString(1, assetName); statement.setString(2, normalized); statement.setString(3, assetType);
                    statement.setString(4, riskType); statement.setString(5, currency); statement.setInt(6, id); statement.setInt(7, userId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Account update did not affect one owned row.");
                }
                connection.commit(); return true;
            } catch (SQLException | IllegalArgumentException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }
}
