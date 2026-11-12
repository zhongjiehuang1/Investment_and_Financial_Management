package Model.TheFinancialSituationOfTheUser;

import Model.Tools.AnnualInflationRatesInVariousCurrencies;
import Model.Tools.Computing;

/** Calculates the target risk distribution of current net assets in a selected currency. */
public class RangeOfRiskDistributionOfCurrentNetAssets {

    private double optimisedAnnualisedInflationRate = 0.0;

    private String totalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_minimum = "0.00",
            totalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_maximum = "0.00",
            totalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_minimum = "0.00",
            totalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_maximum = "0.00",
            totalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_minimum = "0.00",
            totalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_maximum = "0.00";
    public String getTotalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_minimum() {
        return totalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_minimum;
    }
    public String getTotalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_maximum() {
        return totalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_maximum;
    }
    public String getTotalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_minimum() {
        return totalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_minimum;
    }
    public String getTotalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_maximum() {
        return totalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_maximum;
    }
    public String getTotalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_minimum() {
        return totalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_minimum;
    }
    public String getTotalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_maximum() {
        return totalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_maximum;
    }

    private double percentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_minimum =
            0.0,
            percentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_maximum = 0.0,
            percentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_minimum = 0.0,
            percentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_maximum = 0.0,
            percentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_minimum = 0.0,
            percentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_maximum = 0.0;
    public double getPercentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_minimum() {
        return percentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_minimum;
    }
    public double getPercentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_maximum() {
        return percentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_maximum;
    }
    public double
    getPercentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_minimum() {
        return percentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_minimum;
    }
    public double
    getPercentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_maximum() {
        return percentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_maximum;
    }
    public double
    getPercentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_minimum() {
        return percentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_minimum;
    }
    public double
    getPercentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_maximum() {
        return percentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_maximum;
    }

    public void calculateOptimisedAnnualInflationRate(Model.User user) {
        String total = user.getTotalCurrentNetAssets().getTotalCurrentNetAssets();
        CurrencyDistributionOfCurrentNetAssets distribution = user.getCurrencyDistributionOfCurrentNetAssets();
        optimisedAnnualisedInflationRate =
                Computing.percentage(distribution.getAmountOfCurrentNetAssetsHeldInGBP(), total) / 100.0
                        * AnnualInflationRatesInVariousCurrencies.getCurrentAnnualInflationRateOfGBP()
                + Computing.percentage(distribution.getAmountOfCurrentNetAssetsHeldInCNY(), total) / 100.0
                        * AnnualInflationRatesInVariousCurrencies.getCurrentAnnualInflationRateOfCNY();
    }

    public void calculateRequiredDistributionOfLiquidAssetsAndAnnualisedRatesOfReturn(
            TotalCurrentNetAssets totalCurrentNetAssets) {
        long total = Computing.toMinorUnits(totalCurrentNetAssets.getTotalCurrentNetAssets());
        // Preserve the existing thresholds. The old upper-bound branch was overwritten by zero locals.
        double rate = optimisedAnnualisedInflationRate;
        int lowMinimum;
        if (rate <= 0) lowMinimum = 100;
        else if (rate <= 10) lowMinimum = 100 - (int) Math.ceil(rate) * 5;
        else if (rate <= 12) lowMinimum = 45;
        else if (rate <= 14) lowMinimum = 35;
        else if (rate <= 16) lowMinimum = 25;
        else if (rate <= 18) lowMinimum = 15;
        else lowMinimum = 10;
        int mediumMaximum = 100 - lowMinimum;
        int highMaximum = Math.min(mediumMaximum, 30);
        totalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_minimum =
                Computing.fromMinorUnits(Computing.scale(total, lowMinimum, 100));
        totalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_maximum =
                Computing.fromMinorUnits(total);
        totalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_minimum = "0.00";
        totalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_maximum =
                Computing.fromMinorUnits(Computing.scale(total, mediumMaximum, 100));
        totalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_minimum = "0.00";
        totalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_maximum =
                Computing.fromMinorUnits(Computing.scale(total, highMaximum, 100));
        percentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_minimum = total == 0 ? 0 : lowMinimum;
        percentageOfTotalAmountOfVeryLowAndLowRiskCurrentNetAssetsHeld_distributionRegulations_maximum = total == 0 ? 0 : 100;
        percentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_minimum = 0;
        percentageOfTotalAmountOfMediumLowAndMediumRiskCurrentNetAssetsHeld_distributionRegulations_maximum = total == 0 ? 0 : mediumMaximum;
        percentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_minimum = 0;
        percentageOfTotalAmountOfMediumHighAndHighRiskCurrentNetAssetsHeld_distributionRegulations_maximum = total == 0 ? 0 : highMaximum;
    }
}
