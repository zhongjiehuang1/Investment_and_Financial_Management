package Model.Controller.IncomeAndExpenditureData;

import Model.Tools.Computing;

public class EditIncomeOrExpense {

    public static boolean editIncomeOrExpense(
            int userID,
            int id,
            java.lang.String type,
            java.lang.String platform,
            java.lang.String name,
            java.lang.String dateOfMonth, java.lang.String monthOfYear, int year, java.lang.String amount) {
        amount = Computing.normalize(amount);
        if (type == null || !java.util.Set.of("income", "expense", "transfer_in", "transfer_out").contains(type)) {
            throw new IllegalArgumentException("Invalid transaction type");
        }
        if (Computing.toMinorUnits(amount) < 0) throw new IllegalArgumentException("Transaction amount must be non-negative");
        return DatabaseService.Model.Controller.IncomeAndExpenditureData.EditIncomeOrExpense.editIncomeOrExpense(
                userID, id, type, platform, name, dateOfMonth, monthOfYear, year, amount);
    }

}
