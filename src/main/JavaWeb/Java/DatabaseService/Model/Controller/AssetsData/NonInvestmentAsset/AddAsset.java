package DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset;

import DatabaseService.DatabaseInit;
import DatabaseService.Model.Controller.AssetsData.CommonFunctions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class AddAsset extends DatabaseInit {
    public static boolean addNonInvestmentAsset(int userID, Controller.AssetsData.NonInvestmentAsset.AddAsset.Command command) {
        String amount = databaseAmount(command.getAmount());
        CommonFunctions.validate(command.getAssetName(), command.getAssetType(), command.getRiskType(), command.getCurrency());
        try {
            try (Connection connection = openConnection()) {
                try {
                    lockUser(connection, userID);
                    int id = nextId(connection, "ALL_NON_INVESTMENT_ASSETS");
                    CommonFunctions.available(connection, userID, command.getAssetName(), null, 0);
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ALL_NON_INVESTMENT_ASSETS "
                            + "(ID, USER_ID, NAME, AMOUNT, TYPE, RISK_TYPE, CURRENCY, DATE) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        statement.setInt(1, id); statement.setInt(2, userID); statement.setString(3, command.getAssetName());
                        statement.setString(4, amount); statement.setString(5, command.getAssetType());
                        statement.setString(6, command.getRiskType()); statement.setString(7, command.getCurrency());
                        statement.setDate(8, java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.UTC)));
                        if (statement.executeUpdate() != 1) throw new SQLException("Account insert did not affect one row.");
                    }
                    connection.commit(); return true;
                } catch (SQLException | IllegalArgumentException exception) { rollback(connection); return false; }
            }
        } catch (SQLException exception) { return false; }
    }
}
