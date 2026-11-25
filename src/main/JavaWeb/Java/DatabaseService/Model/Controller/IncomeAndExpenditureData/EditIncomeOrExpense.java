package DatabaseService.Model.Controller.IncomeAndExpenditureData;

import DatabaseService.DatabaseInit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class EditIncomeOrExpense extends DatabaseInit {
    public static boolean editIncomeOrExpense(int userID, int id, String type, String platform, String name,
            String dateOfMonth, String monthOfYear, int year, String amount) {
        String normalized = CommonFunctionsNonDatabaseAbstraction.amount(amount);
        CommonFunctionsNonDatabaseAbstraction.validateType(type);
        if (!java.util.Set.of("income", "expense").contains(type)) throw new IllegalArgumentException("Use the paired transfer action for internal transfers.");
        java.sql.Date date = recordDate(String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, Integer.parseInt(monthOfYear), Integer.parseInt(dateOfMonth)));
        if (name == null || name.isBlank() || name.length() > 150) throw new IllegalArgumentException("Invalid description.");
        try (Connection connection = openConnection()) {
            try {
                lockUser(connection, userID);
                Ledger.removeExternal(connection, userID, id);
                String oldType, oldPlatform, oldAmount, oldCurrency;
                try (PreparedStatement statement = connection.prepareStatement("SELECT TYPE, PLATFORM, AMOUNT, CURRENCY FROM "
                        + "INCOME_AND_EXPENDITURE_DATA WHERE ID = ? AND USER_ID = ? FOR UPDATE")) {
                    statement.setInt(1, id); statement.setInt(2, userID);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) { rollback(connection); return false; }
                        oldType = result.getString("TYPE"); oldPlatform = result.getString("PLATFORM");
                        oldAmount = result.getString("AMOUNT"); oldCurrency = result.getString("CURRENCY");
                    }
                }
                CommonFunctionsNonDatabaseAbstraction.Account old = CommonFunctionsNonDatabaseAbstraction.find(connection, userID, oldPlatform);
                if (!old.currency.equals(oldCurrency)) throw new IllegalArgumentException("The historical transaction currency differs from the account currency.");
                CommonFunctionsNonDatabaseAbstraction.Account target = oldPlatform.equals(platform)
                        ? old : CommonFunctionsNonDatabaseAbstraction.find(connection, userID, platform);
                if (target.databaseId == old.databaseId && target.id == old.id && target.investment == old.investment) target = old;
                if (Model.Tools.Computing.toMinorUnits(oldAmount) != 0) CommonFunctionsNonDatabaseAbstraction.change(old, oldType, oldAmount, true);
                CommonFunctionsNonDatabaseAbstraction.change(target, type, normalized, false);
                CommonFunctionsNonDatabaseAbstraction.save(connection, old);
                if (target != old) CommonFunctionsNonDatabaseAbstraction.save(connection, target);
                try (PreparedStatement statement = connection.prepareStatement("UPDATE INCOME_AND_EXPENDITURE_DATA "
                        + "SET TYPE = ?, PLATFORM = ?, NAME = ?, DATE = ?, AMOUNT = ?, CURRENCY = ? WHERE ID = ? AND USER_ID = ?")) {
                    statement.setString(1, type); statement.setString(2, target.name); statement.setString(3, name);
                    statement.setDate(4, date); statement.setString(5, normalized); statement.setString(6, target.currency);
                    statement.setInt(7, id); statement.setInt(8, userID);
                    if (statement.executeUpdate() != 1) throw new SQLException("Transaction update did not affect one owned row.");
                }
                Ledger.external(connection, userID, id, type, target.name, normalized, date, target.currency);
                connection.commit(); return true;
            } catch (SQLException | IllegalArgumentException | ArithmeticException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }
}
