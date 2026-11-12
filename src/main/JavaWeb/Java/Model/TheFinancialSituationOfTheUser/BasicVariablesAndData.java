package Model.TheFinancialSituationOfTheUser;

import Model.FixedTypes.Currencies;
import Model.Tools.Computing;
import Model.UserAssets.BalanceOfIncomeAndExpenditure;
import Model.UserAssets.InvestmentAsset;
import Model.UserAssets.NonInvestmentAsset;
import java.util.Objects;

public class BasicVariablesAndData {

    private String idealEarnings = "0.00";
    public String getIdealEarnings() {
        return idealEarnings;
    }

    private double degreeOfCompletion = 0.0;
    public double getDegreeOfCompletion() {
        return degreeOfCompletion;
    }

    private java.lang.String[] actualEarnings_platforms = null;
    private String[] actualEarnings = {"0.00", "0.00"};
    public java.lang.String[] getActualEarnings_platforms() {
        return actualEarnings_platforms;
    }
    public String[] getActualEarnings() {
        return actualEarnings;
    }

    private String cycleEarnings = "0.00";
    public String getCycleEarnings() {
        return cycleEarnings;
    }

    private String otherAccumulatedPassiveIncomeInBasicVariablesAndData = "0.00",
            actualEarningsBeforeInBasicVariablesAndData = "0.00",
            accumulatedAmountInBasicVariablesAndData = "0.00";
    public String getActualEarningsBeforeInBasicVariablesAndData() {
        return actualEarningsBeforeInBasicVariablesAndData;
    }
    public String getAccumulatedAmountInBasicVariablesAndData() {
        return accumulatedAmountInBasicVariablesAndData;
    }
    public void setOtherAccumulatedPassiveIncomeInBasicVariablesAndData(
            String otherAccumulatedPassiveIncomeInBasicVariablesAndData) {
        this.otherAccumulatedPassiveIncomeInBasicVariablesAndData =
                Computing.normalizeStored(otherAccumulatedPassiveIncomeInBasicVariablesAndData);
    }
    public void setActualEarningsBeforeInBasicVariablesAndData(String actualEarningsBeforeInBasicVariablesAndData) {
        this.actualEarningsBeforeInBasicVariablesAndData = Computing.normalizeStored(actualEarningsBeforeInBasicVariablesAndData);
    }
    public void setAccumulatedAmountInBasicVariablesAndData(String accumulatedAmountInBasicVariablesAndData) {
        this.accumulatedAmountInBasicVariablesAndData = Computing.normalizeStored(accumulatedAmountInBasicVariablesAndData);
    }

    private double[] selectedInflationRates_GBP = null, selectedInflationRates_CNY = null;
    private String currentDate = null;
    public double[] getSelectedInflationRates_GBP() {
        return selectedInflationRates_GBP;
    }
    public double[] getSelectedInflationRates_CNY() {
        return selectedInflationRates_CNY;
    }
    public void setSelectedInflationRates_GBP(double[] selectedInflationRates_GBP) {
        this.selectedInflationRates_GBP = selectedInflationRates_GBP == null || selectedInflationRates_GBP.length == 0 ? null : selectedInflationRates_GBP;
    }
    public void setSelectedInflationRates_CNY(double[] selectedInflationRates_CNY) {
        this.selectedInflationRates_CNY = selectedInflationRates_CNY == null || selectedInflationRates_CNY.length == 0 ? null : selectedInflationRates_CNY;
    }
    public void setCurrentDate(java.lang.String currentDate) {
        this.currentDate = currentDate;
    }

    private java.lang.String[] selectedInflationRateDates_GBP = null, selectedInflationRateDates_CNY = null;
    public java.lang.String[] getSelectedInflationRateDates_GBP() {
        return selectedInflationRateDates_GBP;
    }
    public java.lang.String[] getSelectedInflationRateDates_CNY() {
        return selectedInflationRateDates_CNY;
    }
    public void setSelectedDates_GBP(java.lang.String[] selectedInflationRateDates_GBP) {
        this.selectedInflationRateDates_GBP = selectedInflationRateDates_GBP == null || selectedInflationRateDates_GBP.length == 0 ? null : selectedInflationRateDates_GBP;
    }
    public void setSelectedDates_CNY(java.lang.String[] selectedInflationRateDates_CNY) {
        this.selectedInflationRateDates_CNY = selectedInflationRateDates_CNY == null || selectedInflationRateDates_CNY.length == 0 ? null : selectedInflationRateDates_CNY;
    }

    private double selectedInflationRateBeforeSelection_GBP = 0.0, selectedInflationRateBeforeSelection_CNY = 0.0;
    public void setSelectedInflationRateBeforeSelection_GBP(double selectedInflationRateBeforeSelection_GBP) {
        this.selectedInflationRateBeforeSelection_GBP = selectedInflationRateBeforeSelection_GBP;
    }
    public void setSelectedInflationRateBeforeSelection_CNY(double selectedInflationRateBeforeSelection_CNY) {
        this.selectedInflationRateBeforeSelection_CNY = selectedInflationRateBeforeSelection_CNY;
    }

    public void calculateIdealEarnings(Model.User user) {
        BalanceOfIncomeAndExpenditure[] balanceOfIncomeAndExpenditures = user.getBalanceOfIncomesAndExpenditures();
        if (balanceOfIncomeAndExpenditures == null) balanceOfIncomeAndExpenditures = new BalanceOfIncomeAndExpenditure[0];
        NonInvestmentAsset[] nonInvestmentAssets = user.getNonInvestmentAssets();
        if (nonInvestmentAssets == null) nonInvestmentAssets = new NonInvestmentAsset[0];
        InvestmentAsset[] investmentAssets = user.getInvestmentAssets();
        if (investmentAssets == null) investmentAssets = new InvestmentAsset[0];
        long idealEarnings = 0, assetAmount, balanceOfIncomeAndExpenditureAmount;
        int assets_length, balanceOfIncomeAndExpenditures_length;

        assets_length = nonInvestmentAssets.length;
        for (int i = 0; i < assets_length; i++) {
            assetAmount = currencyConversion(
                    user.getCurrencyCurrentChosen(),
                    Computing.toMinorUnits(nonInvestmentAssets[i].getAmount()), nonInvestmentAssets[i].getCurrency());
            idealEarnings = amountCalculationBranch_asset(
                    idealEarnings,
                    assetAmount, nonInvestmentAssets[i].getCurrency(), nonInvestmentAssets[i].getDate());
        }
        assets_length = investmentAssets.length;
        for (int i = 0; i < assets_length; i++) {
            assetAmount = currencyConversion(
                    user.getCurrencyCurrentChosen(),
                    Computing.toMinorUnits(investmentAssets[i].getRealTimeAmount()), investmentAssets[i].getCurrency());
            idealEarnings = amountCalculationBranch_asset(
                    idealEarnings, assetAmount, investmentAssets[i].getCurrency(), investmentAssets[i].getDate());
        }

        balanceOfIncomeAndExpenditures_length = balanceOfIncomeAndExpenditures.length;
        for (int i = 0; i < balanceOfIncomeAndExpenditures_length; i++) {
            if (!"income".equals(balanceOfIncomeAndExpenditures[i].getType())
                    && !"expense".equals(balanceOfIncomeAndExpenditures[i].getType())) continue;
            balanceOfIncomeAndExpenditureAmount = currencyConversion(
                    user.getCurrencyCurrentChosen(),
                    Computing.toMinorUnits(balanceOfIncomeAndExpenditures[i].getAmount()),
                    balanceOfIncomeAndExpenditures[i].getCurrency());
            if (Objects.equals(balanceOfIncomeAndExpenditures[i].getType(), "income")) {
                balanceOfIncomeAndExpenditureAmount = Math.negateExact(balanceOfIncomeAndExpenditureAmount);
            }
            idealEarnings = amountCalculationBranch_balanceOfIncomeAndExpenditure(
                    user, balanceOfIncomeAndExpenditures[i], idealEarnings, balanceOfIncomeAndExpenditureAmount);
        }
        this.idealEarnings = Computing.fromMinorUnits(idealEarnings);
    }
    private long amountCalculationBranch_asset(long idealEarnings, long assetAmount,
                                               String assetCurrency, java.util.Date assetDate) {
        return addInflationAdjustment(idealEarnings, assetAmount, assetCurrency,
                Model.Tools.DateFormat.setToString(assetDate), currentDate);
    }

    private long amountCalculationBranch_balanceOfIncomeAndExpenditure(Model.User user,
            BalanceOfIncomeAndExpenditure entry, long idealEarnings, long amount) {
        java.util.Date opened = null;
        if (user.getNonInvestmentAssets() != null) for (NonInvestmentAsset asset : user.getNonInvestmentAssets()) {
            if (Objects.equals(asset.getAssetName(), entry.getPlatform())) { opened = asset.getDate(); break; }
        }
        if (user.getInvestmentAssets() != null) for (InvestmentAsset asset : user.getInvestmentAssets()) {
            if (Objects.equals(asset.getInvestmentPlatform(), entry.getPlatform())) { opened = asset.getDate(); break; }
        }
        return addInflationAdjustment(idealEarnings, amount, entry.getCurrency(),
                Model.Tools.DateFormat.setToString(opened), Model.Tools.DateFormat.setToString(entry.getDate()));
    }

    private long addInflationAdjustment(long total, long amount, String currency, String from, String to) {
        if (from == null || to == null || from.compareTo(to) >= 0) return total;
        String[] dates;
        double[] rates;
        double activeRate;
        switch (currency) {
            case "GBP" -> {
                dates = selectedInflationRateDates_GBP;
                rates = selectedInflationRates_GBP;
                activeRate = selectedInflationRateBeforeSelection_GBP;
            }
            case "CNY" -> {
                dates = selectedInflationRateDates_CNY;
                rates = selectedInflationRates_CNY;
                activeRate = selectedInflationRateBeforeSelection_CNY;
            }
            default -> throw new IllegalArgumentException("Unknown currency");
        }
        String cursor = from;
        if (dates != null) {
            if (rates == null || dates.length != rates.length) throw new IllegalStateException("Inflation dates and rates differ");
            for (int i = 0; i < dates.length; i++) {
                if (i > 0 && dates[i].compareTo(dates[i - 1]) < 0) throw new IllegalStateException("Inflation dates must be ordered");
                if (dates[i].compareTo(from) <= 0) { activeRate = rates[i]; continue; }
                if (dates[i].compareTo(to) >= 0) break;
                total = calculateAmount(total, cursor, dates[i], amount, activeRate);
                cursor = dates[i];
                activeRate = rates[i];
            }
        }
        return calculateAmount(total, cursor, to, amount, activeRate);
    }

    private static long currencyConversion(String chosen, long minor, String currency) {
        return Computing.convertMinorUnits(minor, currency, chosen);
    }

    private static long calculateAmount(long idealEarnings, String dateBefore, String dateAfter,
                                        long amount, double selectedInflationRate) {
        if (dateBefore == null || dateAfter == null || amount == 0) return idealEarnings;
        java.time.LocalDate before = java.time.LocalDate.parse(dateBefore);
        java.time.LocalDate after = java.time.LocalDate.parse(dateAfter);
        long days = java.time.temporal.ChronoUnit.DAYS.between(before, after);
        if (days <= 0) return idealEarnings;
        // Inflation is DECIMAL(5,2) in the unchanged schema; represent its percentage in basis points.
        long basisPoints = Computing.toMinorUnits(Double.toString(selectedInflationRate));
        // Preserve the existing 365.25-day annualisation convention using the integer ratio 4 / 1461.
        long numerator = Math.multiplyExact(Math.multiplyExact(days, basisPoints), 4);
        return Math.addExact(idealEarnings, Computing.scale(amount, numerator, 14610000));
    }

    public void calculateDegreeOfCompletion() {
        degreeOfCompletion = Computing.percentage(actualEarnings[actualEarnings.length - 1], idealEarnings);
    }

    public void calculateActualEarnings(Model.User user) {
        InvestmentAsset[] assets = user.getInvestmentAssets();
        if (assets == null) assets = new InvestmentAsset[0];
        actualEarnings_platforms = new String[assets.length];
        actualEarnings = new String[assets.length + 2];
        long total = Computing.toMinorUnits(otherAccumulatedPassiveIncomeInBasicVariablesAndData);
        for (int i = 0; i < assets.length; i++) {
            actualEarnings_platforms[i] = assets[i].getInvestmentPlatform();
            actualEarnings[i] = Computing.convert(assets[i].getEarnings(), assets[i].getCurrency(), user.getCurrencyCurrentChosen());
            total = Math.addExact(total, Computing.toMinorUnits(actualEarnings[i]));
        }
        actualEarnings[assets.length] = otherAccumulatedPassiveIncomeInBasicVariablesAndData;
        actualEarnings[assets.length + 1] = Computing.fromMinorUnits(total);
    }

    public void calculateCycleEarnings() {
        cycleEarnings = Computing.subtract(actualEarnings[actualEarnings.length - 1], actualEarningsBeforeInBasicVariablesAndData);
    }
}
