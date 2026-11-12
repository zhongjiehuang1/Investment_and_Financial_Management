package Model.Controller.IncomeAndExpenditureData;

import Model.Tools.Computing;

/** A transfer is a single transaction containing both ledger entries and both balance changes. */
public final class TransferBetweenAccounts {
    private TransferBetweenAccounts() { }
    public static boolean transferBetweenAccounts(int userId, String fromPlatform, String toPlatform,
                                                   String amount, String receivedAmount, String date, String name) {
        amount = Computing.normalize(amount);
        if (Computing.toMinorUnits(amount) <= 0) throw new IllegalArgumentException("Transfer must be positive");
        if (fromPlatform.equals(toPlatform)) throw new IllegalArgumentException("Choose different accounts");
        if (receivedAmount != null && !receivedAmount.isBlank()) {
            receivedAmount = Computing.normalize(receivedAmount);
            if (Computing.toMinorUnits(receivedAmount) <= 0) throw new IllegalArgumentException("Transfer must be positive");
        }
        java.time.LocalDate.parse(date);
        return DatabaseService.Model.Controller.IncomeAndExpenditureData.TransferBetweenAccounts.transferBetweenAccounts(
                userId, fromPlatform, toPlatform, amount, receivedAmount, date, name);
    }
}
