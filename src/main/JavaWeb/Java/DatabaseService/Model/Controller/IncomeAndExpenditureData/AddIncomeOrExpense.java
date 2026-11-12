package DatabaseService.Model.Controller.IncomeAndExpenditureData;

import DatabaseService.DatabaseInit;
import java.sql.Connection;
import java.sql.SQLException;

public class AddIncomeOrExpense extends DatabaseInit {
    public static boolean addIncomeOrExpense(int userID, String type, String platform, String name, String amount, String date) {
        String normalized = CommonFunctionsNonDatabaseAbstraction.amount(amount);
        CommonFunctionsNonDatabaseAbstraction.validateType(type);
        if (!java.util.Set.of("income", "expense").contains(type)) throw new IllegalArgumentException("Use the paired transfer action for internal transfers.");
        java.sql.Date recordDate = CommonFunctionsNonDatabaseAbstraction.date(date);
        try {
            try (Connection connection = openConnection()) {
                try {
                    lockUser(connection, userID);
                    int id = nextId(connection, "INCOME_AND_EXPENDITURE_DATA");
                    CommonFunctionsNonDatabaseAbstraction.Account account = CommonFunctionsNonDatabaseAbstraction.find(connection, userID, platform);
                    CommonFunctionsNonDatabaseAbstraction.insert(connection, id, userID, type, account.name, name, normalized, recordDate, account.currency);
                    Ledger.external(connection, userID, id, type, account.name, normalized, recordDate, account.currency);
                    CommonFunctionsNonDatabaseAbstraction.change(account, type, normalized, false);
                    CommonFunctionsNonDatabaseAbstraction.save(connection, account);
                    connection.commit(); return true;
                } catch (SQLException | IllegalArgumentException | ArithmeticException exception) {
                    rollback(connection); return false;
                }
            }
        } catch (SQLException exception) { return false; }
    }
}
