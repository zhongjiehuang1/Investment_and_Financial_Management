package Model.TheFinancialSituationOfTheUser;

import Model.FixedTypes.Currencies;
import Model.Tools.Computing;
import Model.UserAssets.InvestmentAsset;
import Model.UserAssets.NonInvestmentAsset;

/** Calculates balances and proportions for the three asset risk groups. */
public class RiskDistributionOfCurrentNetAssets {

    private String totalVeryLowAndLowRiskCurrentNetAssets = "0.00", totalMediumLowAndMediumRiskCurrentNetAssets = "0.00",
            totalMediumHighAndHighRiskCurrentNetAssets = "0.00";
    public String getTotalVeryLowAndLowRiskCurrentNetAssets() {
        return totalVeryLowAndLowRiskCurrentNetAssets;
    }
    public String getTotalMediumLowAndMediumRiskCurrentNetAssets() {
        return totalMediumLowAndMediumRiskCurrentNetAssets;
    }
    public String getTotalMediumHighAndHighRiskCurrentNetAssets() {
        return totalMediumHighAndHighRiskCurrentNetAssets;
    }

    private double percentageOfTotalVeryLowAndLowRiskCurrentNetAssets = 0.0,
            percentageOfTotalMediumLowAndMediumRiskCurrentNetAssets = 0.0,
            percentageOfTotalMediumHighAndHighRiskCurrentNetAssets = 0.0;
    public double getPercentageOfTotalVeryLowAndLowRiskCurrentNetAssets() {
        return percentageOfTotalVeryLowAndLowRiskCurrentNetAssets;
    }
    public double getPercentageOfTotalMediumLowAndMediumRiskCurrentNetAssets() {
        return percentageOfTotalMediumLowAndMediumRiskCurrentNetAssets;
    }
    public double getPercentageOfTotalMediumHighAndHighRiskCurrentNetAssets() {
        return percentageOfTotalMediumHighAndHighRiskCurrentNetAssets;
    }

    public void calculateTheRiskDistributionOfTotalCurrentNetAssets(Model.User user) {
        long[] totals = new long[3];
        for (Currencies currency : Currencies.values()) {
            long[] byRisk = new long[3];
            if (user.getNonInvestmentAssets() != null) for (NonInvestmentAsset asset : user.getNonInvestmentAssets()) {
                if (currency.name().equals(asset.getCurrency())) {
                    int risk = riskIndex(asset.getRiskType());
                    byRisk[risk] = Math.addExact(byRisk[risk], Computing.toMinorUnits(asset.getAmount()));
                }
            }
            if (user.getInvestmentAssets() != null) for (InvestmentAsset asset : user.getInvestmentAssets()) {
                if (currency.name().equals(asset.getCurrency())) {
                    int risk = riskIndex(asset.getRiskType());
                    long current = Computing.toMinorUnits(asset.getRealTimeAmount());
                    long cash = risk == 0 ? current : Computing.toMinorUnits(asset.getUninvestedAmount());
                    byRisk[0] = Math.addExact(byRisk[0], cash);
                    if (risk != 0) byRisk[risk] = Math.addExact(byRisk[risk], Math.subtractExact(current, cash));
                }
            }
            for (int risk = 0; risk < 3; risk++) totals[risk] = Math.addExact(totals[risk],
                    Computing.convertMinorUnits(byRisk[risk], currency.name(), user.getCurrencyCurrentChosen()));
        }
        totalVeryLowAndLowRiskCurrentNetAssets = Computing.fromMinorUnits(totals[0]);
        totalMediumLowAndMediumRiskCurrentNetAssets = Computing.fromMinorUnits(totals[1]);
        totalMediumHighAndHighRiskCurrentNetAssets = Computing.fromMinorUnits(totals[2]);
    }

    private static int riskIndex(String riskType) {
        return switch (riskType) {
            case "VERY_LOW_AND_LOW_RISK_TYPE" -> 0;
            case "MEDIUM_LOW_AND_MEDIUM_RISK_TYPE" -> 1;
            case "MEDIUM_HIGH_AND_HIGH_RISK_TYPE" -> 2;
            default -> throw new IllegalArgumentException("Unknown risk type");
        };
    }

    public void calculatePercentageOfTotalVeryLowAndLowRiskCurrentNetAssets(
            TotalCurrentNetAssets totalCurrentNetAssets) {
        percentageOfTotalVeryLowAndLowRiskCurrentNetAssets =
                Computing.percentage(totalVeryLowAndLowRiskCurrentNetAssets, totalCurrentNetAssets.getTotalCurrentNetAssets());
    }
    public void calculatePercentageOfTotalMediumLowAndMediumRiskCurrentNetAssets(
            TotalCurrentNetAssets totalCurrentNetAssets) {
        percentageOfTotalMediumLowAndMediumRiskCurrentNetAssets =
                Computing.percentage(totalMediumLowAndMediumRiskCurrentNetAssets, totalCurrentNetAssets.getTotalCurrentNetAssets());
    }
    public void calculatePercentageOfTotalMediumHighAndHighRiskCurrentNetAssets(
            TotalCurrentNetAssets totalCurrentNetAssets) {
        percentageOfTotalMediumHighAndHighRiskCurrentNetAssets =
                Computing.percentage(totalMediumHighAndHighRiskCurrentNetAssets, totalCurrentNetAssets.getTotalCurrentNetAssets());
    }

}
