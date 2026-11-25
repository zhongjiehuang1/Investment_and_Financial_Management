package Model.TheFinancialSituationOfTheUser;

import Model.FixedTypes.Currencies;
import Model.FixedTypes.RiskTypes;
import Model.Tools.Computing;
import Model.UserAssets.InvestmentAsset;
import Model.UserAssets.NonInvestmentAsset;

/** Calculates low-risk liquid net assets and their proportions by currency. */
public class CurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets {

    private String totalVeryLowAndLowRiskCurrentNetAssets_GBP = "0.00", totalVeryLowAndLowRiskCurrentNetAssets_CNY = "0.00";
    public String getTotalVeryLowAndLowRiskCurrentNetAssets_GBP() {
        return totalVeryLowAndLowRiskCurrentNetAssets_GBP;
    }
    public String getTotalVeryLowAndLowRiskCurrentNetAssets_CNY() {
        return totalVeryLowAndLowRiskCurrentNetAssets_CNY;
    }

    private double percentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInGBP = 0.0,
            percentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInCNY = 0.0;
    public double getPercentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInGBP() {
        return percentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInGBP;
    }
    public double getPercentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInCNY() {
        return percentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInCNY;
    }

    public void calculateTotalAmountOfVeryLowRiskCurrentNetAssetsHeldInVariousCurrencies(Model.User user) {
        totalVeryLowAndLowRiskCurrentNetAssets_GBP = lowRiskAmount(user, "GBP");
        totalVeryLowAndLowRiskCurrentNetAssets_CNY = lowRiskAmount(user, "CNY");
    }

    private static String lowRiskAmount(Model.User user, String currency) {
        long total = 0;
        if (user.getNonInvestmentAssets() != null) for (NonInvestmentAsset asset : user.getNonInvestmentAssets()) {
            if (currency.equals(asset.getCurrency()) && RiskTypes.VERY_LOW_AND_LOW_RISK_TYPE.name().equals(asset.getRiskType())) {
                total = Math.addExact(total, Computing.toMinorUnits(asset.getAmount()));
            }
        }
        if (user.getInvestmentAssets() != null) for (InvestmentAsset asset : user.getInvestmentAssets()) {
            if (currency.equals(asset.getCurrency())) {
                String cash = RiskTypes.VERY_LOW_AND_LOW_RISK_TYPE.name().equals(asset.getRiskType())
                        ? asset.getRealTimeAmount() : asset.getUninvestedAmount();
                total = Math.addExact(total, Computing.toMinorUnits(cash));
            }
        }
        return Computing.fromMinorUnits(Computing.convertMinorUnits(total, currency, user.getCurrencyCurrentChosen()));
    }

    public void calculatePercentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInVariousCurrencies(
            RiskDistributionOfCurrentNetAssets riskDistributionOfCurrentNetAssets) {
        percentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInGBP = Computing.percentage(totalVeryLowAndLowRiskCurrentNetAssets_GBP,
                        riskDistributionOfCurrentNetAssets.getTotalVeryLowAndLowRiskCurrentNetAssets());
        percentageOfVeryLowAndLowRiskCurrentNetAssetsHeldInCNY = Computing.percentage(totalVeryLowAndLowRiskCurrentNetAssets_CNY,
                        riskDistributionOfCurrentNetAssets.getTotalVeryLowAndLowRiskCurrentNetAssets());
    }

}
