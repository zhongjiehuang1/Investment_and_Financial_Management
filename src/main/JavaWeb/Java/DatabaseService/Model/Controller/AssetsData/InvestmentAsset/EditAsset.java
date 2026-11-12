package DatabaseService.Model.Controller.AssetsData.InvestmentAsset;

import DatabaseService.DatabaseInit;
import DatabaseService.Model.Controller.AssetsData.CommonFunctions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class EditAsset extends DatabaseInit {
    public static boolean editInvestmentAsset(int userId, int id, String investmentPlatform, String investmentType, String riskType,
            String investmentAmount, String cashWithdrawal, String buyInAmount, String amountSold,
            String managementFee, String handlingFee, String realTimeAmount, String initialEarnings, String currency) {
        String[] amounts = {databaseAmount(investmentAmount), databaseAmount(cashWithdrawal), databaseAmount(managementFee),
                databaseAmount(handlingFee), databaseAmount(buyInAmount), databaseAmount(amountSold), databaseAmount(realTimeAmount), decimalAmount(initialEarnings)};
        CommonFunctions.validate(investmentPlatform, investmentType, riskType, currency);
        try (Connection connection = openConnection()) {
            try {
                lockUser(connection, userId);
                String oldName, oldCurrency;
                try (PreparedStatement statement = connection.prepareStatement("SELECT INVESTMENT_PLATFORM, CURRENCY FROM ALL_INVESTMENT_ASSETS WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
                    statement.setInt(1, id); statement.setInt(2, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) { rollback(connection); return false; }
                        oldName = result.getString("INVESTMENT_PLATFORM"); oldCurrency = result.getString("CURRENCY");
                    }
                }
                CommonFunctions.available(connection, userId, investmentPlatform, "ALL_INVESTMENT_ASSETS", id);
                CommonFunctions.rename(connection, userId, oldName, investmentPlatform, oldCurrency, currency);
                try (PreparedStatement statement = connection.prepareStatement("UPDATE ALL_INVESTMENT_ASSETS SET INVESTMENT_AMOUNT = ?, "
                        + "CASH_WITHDRAWAL = ?, MANAGEMENT_FEE = ?, HANDLING_FEE = ?, BUY_IN_AMOUNT = ?, AMOUNT_SOLD = ?, REAL_TIME_AMOUNT = ?, "
                        + "INITIAL_EARNINGS = ?, INVESTMENT_PLATFORM = ?, RISK_TYPE = ?, INVESTMENT_TYPE = ?, CURRENCY = ? WHERE ID = ? AND USER_ID = ?")) {
                    for (int i = 0; i < amounts.length; i++) statement.setString(i + 1, amounts[i]);
                    statement.setString(9, investmentPlatform); statement.setString(10, riskType); statement.setString(11, investmentType);
                    statement.setString(12, currency); statement.setInt(13, id); statement.setInt(14, userId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Account update did not affect one owned row.");
                }
                connection.commit(); return true;
            } catch (SQLException | IllegalArgumentException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }
}
