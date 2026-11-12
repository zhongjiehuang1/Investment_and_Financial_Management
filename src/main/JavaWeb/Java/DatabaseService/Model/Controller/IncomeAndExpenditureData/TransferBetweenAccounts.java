package DatabaseService.Model.Controller.IncomeAndExpenditureData;

import DatabaseService.DatabaseInit;
import java.sql.Connection;
import java.sql.SQLException;

public class TransferBetweenAccounts extends DatabaseInit {
    /** Both directional records, both balances, and investment contributions commit together. */
    public static boolean transferBetweenAccounts(int userId, String fromPlatform, String toPlatform,
            String amount, String receivedAmount, String date, String name) {
        if (fromPlatform == null || fromPlatform.equals(toPlatform)) throw new IllegalArgumentException("Choose two different accounts.");
        String outgoing = CommonFunctionsNonDatabaseAbstraction.amount(amount);
        java.sql.Date recordDate = CommonFunctionsNonDatabaseAbstraction.date(date);
        try {
            try (Connection connection = openConnection()) {
                try {
                    lockUser(connection, userId);
                    lockIdTables(connection, "INCOME_AND_EXPENDITURE_DATA");
                    CommonFunctionsNonDatabaseAbstraction.Account from = CommonFunctionsNonDatabaseAbstraction.find(connection, userId, fromPlatform);
                    CommonFunctionsNonDatabaseAbstraction.Account to = CommonFunctionsNonDatabaseAbstraction.find(connection, userId, toPlatform);
                    if (from.databaseId == to.databaseId && from.id == to.id && from.investment == to.investment) throw new IllegalArgumentException("Choose two different accounts.");
                    boolean sameCurrency = from.currency.equals(to.currency);
                    if (!sameCurrency && (receivedAmount == null || receivedAmount.isBlank())) {
                        throw new IllegalArgumentException("Enter the actual received amount for a currency transfer.");
                    }
                    String incoming = sameCurrency && (receivedAmount == null || receivedAmount.isBlank())
                            ? outgoing : CommonFunctionsNonDatabaseAbstraction.amount(receivedAmount);
                    if (sameCurrency && !outgoing.equals(incoming)) throw new IllegalArgumentException("Same-currency transfer amounts must match.");
                    CommonFunctionsNonDatabaseAbstraction.change(from, "transfer_out", outgoing, false);
                    CommonFunctionsNonDatabaseAbstraction.change(to, "transfer_in", incoming, false);
                    CommonFunctionsNonDatabaseAbstraction.save(connection, from);
                    CommonFunctionsNonDatabaseAbstraction.save(connection, to);
                    int outId = nextId(connection, "INCOME_AND_EXPENDITURE_DATA");
                    CommonFunctionsNonDatabaseAbstraction.insert(connection, outId, userId, "transfer_out", from.name, name, outgoing, recordDate, from.currency);
                    int inId = nextId(connection, "INCOME_AND_EXPENDITURE_DATA");
                    CommonFunctionsNonDatabaseAbstraction.insert(connection, inId, userId, "transfer_in", to.name, name, incoming, recordDate, to.currency);
                    Ledger.transfer(connection, userId, outId, inId, from, to, outgoing, incoming, recordDate);
                    connection.commit(); return true;
                } catch (SQLException | IllegalArgumentException | ArithmeticException exception) { rollback(connection); return false; }
            }
        } catch (SQLException exception) { return false; }
    }
}
