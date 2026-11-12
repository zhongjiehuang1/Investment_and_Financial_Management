package Model.Controller.IncomeAndExpenditureData;


public class DeleteIncomeOrExpense {

    public static boolean deleteIncomeOrExpense(
            int userID, int id, java.lang.String type, java.lang.String platform, java.lang.String amount) {
        return DatabaseService.Model.Controller.IncomeAndExpenditureData.DeleteIncomeOrExpense.deleteIncomeOrExpense(
                userID, id, type, platform, amount);
    }

}
