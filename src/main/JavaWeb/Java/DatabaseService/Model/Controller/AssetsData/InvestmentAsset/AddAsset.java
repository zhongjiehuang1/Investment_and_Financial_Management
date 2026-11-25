package DatabaseService.Model.Controller.AssetsData.InvestmentAsset;

import DatabaseService.DatabaseInit;
import DatabaseService.Model.Controller.AssetsData.CommonFunctions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class AddAsset extends DatabaseInit {
    public static boolean addInvestmentAsset(int userID, Controller.AssetsData.InvestmentAsset.AddAsset.Command command) {
        String[] amounts = {databaseAmount(command.getInvestmentAmount()), databaseAmount(command.getCashWithdrawal()),
                databaseAmount(command.getManagementFee()), databaseAmount(command.getHandlingFee()), databaseAmount(command.getBuyInAmount()),
                databaseAmount(command.getAmountSold()), databaseAmount(command.getRealTimeAmount()), decimalAmount(command.getInitialEarnings())};
        CommonFunctions.validate(command.getInvestmentPlatform(), command.getInvestmentType(), command.getRiskType(), command.getCurrency());
        try {
            try (Connection connection = openConnection()) {
                try {
                    lockUser(connection, userID);
                    int id = nextId(connection, "ALL_INVESTMENT_ASSETS");
                    CommonFunctions.available(connection, userID, command.getInvestmentPlatform(), null, 0);
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ALL_INVESTMENT_ASSETS "
                            + "(ID, USER_ID, INVESTMENT_AMOUNT, CASH_WITHDRAWAL, MANAGEMENT_FEE, HANDLING_FEE, BUY_IN_AMOUNT, AMOUNT_SOLD, "
                            + "REAL_TIME_AMOUNT, INITIAL_EARNINGS, INVESTMENT_PLATFORM, RISK_TYPE, INVESTMENT_TYPE, CURRENCY, DATE) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        statement.setInt(1, id); statement.setInt(2, userID);
                        for (int i = 0; i < amounts.length; i++) statement.setString(i + 3, amounts[i]);
                        statement.setString(11, command.getInvestmentPlatform()); statement.setString(12, command.getRiskType());
                        statement.setString(13, command.getInvestmentType()); statement.setString(14, command.getCurrency());
                        statement.setDate(15, java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.UTC)));
                        if (statement.executeUpdate() != 1) throw new SQLException("Account insert did not affect one row.");
                    }
                    connection.commit(); return true;
                } catch (SQLException | IllegalArgumentException exception) { rollback(connection); return false; }
            }
        } catch (SQLException exception) { return false; }
    }
}
