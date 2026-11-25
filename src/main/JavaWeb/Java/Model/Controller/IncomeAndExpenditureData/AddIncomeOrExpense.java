package Model.Controller.IncomeAndExpenditureData;

import Model.Tools.Computing;

public class AddIncomeOrExpense {

    public static boolean addIncomeOrExpense(
            int userID,
            java.lang.String type, java.lang.String platform, java.lang.String name, java.lang.String amount,
            java.lang.String date) {
        amount = Computing.normalize(amount);
        if (type == null || !java.util.Set.of("income", "expense", "transfer_in", "transfer_out").contains(type)) {
            throw new IllegalArgumentException("Invalid transaction type");
        }
        if (Computing.toMinorUnits(amount) < 0) throw new IllegalArgumentException("Transaction amount must be non-negative");
        return DatabaseService.Model.Controller.IncomeAndExpenditureData.AddIncomeOrExpense.addIncomeOrExpense(
                userID, type, platform, name, amount, date);
    }

}
