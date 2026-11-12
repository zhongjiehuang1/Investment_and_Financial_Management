package Model.TheFinancialSituationOfTheUser;

import Model.FixedTypes.Currencies;
import Model.Tools.Computing;
import Model.UserAssets.Asset;

/** Stores and calculates the user's net asset value in the selected currency. */
public class TotalCurrentNetAssets {
    private String totalCurrentNetAssets = "0.00";

    public String getTotalCurrentNetAssets() {
        return totalCurrentNetAssets;
    }

    public void calculateTotalCurrentNetAssets(Model.User user) {
        long total = 0;
        for (Currencies currency : Currencies.values()) {
            long amount = Math.addExact(calculateTotalAmountForCurrency(user.getNonInvestmentAssets(), currency.name()),
                    calculateTotalAmountForCurrency(user.getInvestmentAssets(), currency.name()));
            total = Math.addExact(total, Computing.convertMinorUnits(amount, currency.name(), user.getCurrencyCurrentChosen()));
        }
        totalCurrentNetAssets = Computing.fromMinorUnits(total);
    }

    static long calculateTotalAmountForCurrency(Asset[] assets, String currency) {
        long total = 0;
        if (assets != null) {
            for (Asset asset : assets) {
                if (currency.equals(asset.getCurrency())) total = Math.addExact(total, Computing.toMinorUnits(asset.getAmount()));
            }
        }
        return total;
    }
}
