import Model.User;
import Model.UserAssets.InvestmentAsset;
import Model.UserAssets.NonInvestmentAsset;
import Model.UserAssets.BalanceOfIncomeAndExpenditure;
import Model.Tools.Computing;
import Model.Tools.DateFormat;
import Model.TheFinancialSituationOfTheUser.BasicVariablesAndData;
import Model.TheFinancialSituationOfTheUser.RangeOfRiskDistributionOfCurrentNetAssets;
import org.junit.Test;
import static org.junit.Assert.*;

public class MoneyFinancialModelsTest {
    private static InvestmentAsset investment() {
        InvestmentAsset asset = new InvestmentAsset();
        asset.setCurrency("GBP");
        asset.setInvestmentPlatform("Test account");
        asset.setRiskType("MEDIUM_HIGH_AND_HIGH_RISK_TYPE");
        asset.setInvestmentAmount("1000.00");
        asset.setCashWithdrawal("100.00");
        asset.setBuyInAmount("600.00");
        asset.setAmountSold("100.00");
        asset.setManagementFee("1.00");
        asset.setHandlingFee("2.00");
        asset.setRealTimeAmount("1050.00");
        asset.setInitialEarnings("10.00");
        return asset;
    }

    @Test public void accountEarningsDoNotDoubleCountTradesOrFees() {
        InvestmentAsset asset = investment();
        assertEquals("160.00", asset.getEarnings());
        assertEquals("397.00", asset.getUninvestedAmount());
        asset.setBuyInAmount("800.00");
        asset.setAmountSold("200.00");
        assertEquals("160.00", asset.getEarnings());
    }

    @Test public void transfersChangeCapitalButLeaveEarningsUnchanged() {
        InvestmentAsset incoming = investment();
        incoming.setRealTimeAmount(Computing.add(incoming.getRealTimeAmount(), "123.45"));
        incoming.setInvestmentAmount(Computing.add(incoming.getInvestmentAmount(), "123.45"));
        assertEquals("160.00", incoming.getEarnings());
        InvestmentAsset outgoing = investment();
        outgoing.setRealTimeAmount(Computing.subtract(outgoing.getRealTimeAmount(), "123.45"));
        outgoing.setCashWithdrawal(Computing.add(outgoing.getCashWithdrawal(), "123.45"));
        assertEquals("160.00", outgoing.getEarnings());
    }

    @Test public void riskComponentsReconcileToExactNetAssets() {
        User user = new User();
        user.setCurrencyCurrentChosen("GBP");
        NonInvestmentAsset cash = new NonInvestmentAsset();
        cash.setCurrency("GBP"); cash.setAmount("0.30"); cash.setRiskType("VERY_LOW_AND_LOW_RISK_TYPE");
        user.setNonInvestmentAssets(new NonInvestmentAsset[]{cash});
        user.setInvestmentAssets(new InvestmentAsset[]{investment()});
        user.getTotalCurrentNetAssets().calculateTotalCurrentNetAssets(user);
        user.getRiskDistributionOfCurrentNetAssets().calculateTheRiskDistributionOfTotalCurrentNetAssets(user);
        user.getCurrencyDistributionOfCurrentNetAssets().calculateTheAmountOfCurrentNetAssetsHeldInVariousCurrencies(user);
        user.getCurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets().calculateTotalAmountOfVeryLowRiskCurrentNetAssetsHeldInVariousCurrencies(user);
        assertEquals("1050.30", user.getTotalCurrentNetAssets().getTotalCurrentNetAssets());
        assertEquals("397.30", user.getRiskDistributionOfCurrentNetAssets().getTotalVeryLowAndLowRiskCurrentNetAssets());
        assertEquals("653.00", user.getRiskDistributionOfCurrentNetAssets().getTotalMediumHighAndHighRiskCurrentNetAssets());
        assertEquals("1050.30", user.getCurrencyDistributionOfCurrentNetAssets().getAmountOfCurrentNetAssetsHeldInGBP());
        assertEquals("397.30", user.getCurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets().getTotalVeryLowAndLowRiskCurrentNetAssets_GBP());
    }

    @Test public void emptyPortfolioReturnsFiniteZeroPercentages() {
        User user = new User();
        user.setCurrencyCurrentChosen("GBP");
        user.getTotalCurrentNetAssets().calculateTotalCurrentNetAssets(user);
        user.getCurrencyDistributionOfCurrentNetAssets().calculateTheAmountOfCurrentNetAssetsHeldInVariousCurrencies(user);
        user.getCurrencyDistributionOfCurrentNetAssets().calculateThePercentageOfTheAmountOfCurrentNetAssetsHeldInVariousCurrencies(user.getTotalCurrentNetAssets());
        assertEquals("0.00", user.getTotalCurrentNetAssets().getTotalCurrentNetAssets());
        assertEquals(0.0, user.getCurrencyDistributionOfCurrentNetAssets().getPercentageOfAmountOfCurrentNetAssetsHeldInGBP(), 0.0);
    }

    @Test public void totalEarningsIncludesOtherPassiveIncomeExactlyOnce() {
        User user = new User(); user.setCurrencyCurrentChosen("GBP");
        user.setInvestmentAssets(new InvestmentAsset[]{investment()});
        BasicVariablesAndData report = user.getBasicVariablesAndData();
        report.setOtherAccumulatedPassiveIncomeInBasicVariablesAndData("0.20");
        report.setActualEarningsBeforeInBasicVariablesAndData("100.10");
        report.calculateActualEarnings(user); report.calculateCycleEarnings();
        assertArrayEquals(new String[]{"160.00", "0.20", "160.20"}, report.getActualEarnings());
        assertEquals("60.10", report.getCycleEarnings());
    }

    @Test public void inflationHandlesAssetsAndTransactionsWithinTheSameRateInterval() {
        User user = new User(); user.setCurrencyCurrentChosen("GBP");
        NonInvestmentAsset cash = new NonInvestmentAsset();
        cash.setCurrency("GBP"); cash.setAmount("1100.00"); cash.setAssetName("Cash");
        cash.setDate(DateFormat.setDate("2025-02-01"));
        BalanceOfIncomeAndExpenditure income = new BalanceOfIncomeAndExpenditure();
        income.setType("income"); income.setAmount("100.00"); income.setPlatform("Cash");
        income.setCurrency("GBP"); income.setDate(DateFormat.setDate("2025-02-10"));
        user.setNonInvestmentAssets(new NonInvestmentAsset[]{cash});
        user.setBalanceOfIncomesAndExpenditures(new BalanceOfIncomeAndExpenditure[]{income});
        BasicVariablesAndData report = user.getBasicVariablesAndData();
        report.setCurrentDate("2025-02-28");
        report.setSelectedDates_GBP(new String[]{"2025-01-01", "2025-03-01"});
        report.setSelectedInflationRates_GBP(new double[]{2.0, 3.0});
        report.calculateIdealEarnings(user);
        assertEquals("1.58", report.getIdealEarnings());
        income.setType("transfer_in");
        report.calculateIdealEarnings(user);
        assertEquals("1.63", report.getIdealEarnings());
    }

    @Test public void legacyAllocationUpperBoundIsNotOverwrittenWithZero() throws Exception {
        User user = new User(); user.setCurrencyCurrentChosen("GBP");
        NonInvestmentAsset cash = new NonInvestmentAsset(); cash.setCurrency("GBP"); cash.setAmount("1000.01");
        user.setNonInvestmentAssets(new NonInvestmentAsset[]{cash});
        user.getTotalCurrentNetAssets().calculateTotalCurrentNetAssets(user);
        RangeOfRiskDistributionOfCurrentNetAssets range = user.getRangeOfRiskDistributionOfCurrentNetAssets();
        java.lang.reflect.Field rate = RangeOfRiskDistributionOfCurrentNetAssets.class.getDeclaredField("optimisedAnnualisedInflationRate");
        rate.setAccessible(true); rate.setDouble(range, 21.0);
        range.calculateRequiredDistributionOfLiquidAssetsAndAnnualisedRatesOfReturn(user.getTotalCurrentNetAssets());
        assertEquals("100.00", range.getTotalAmountOfVeryLowAndLowRiskCurrentNetAssets_distributionRegulations_minimum());
        assertEquals("900.01", range.getTotalAmountOfMediumLowAndMediumRiskCurrentNetAssets_distributionRegulations_maximum());
        assertEquals("300.00", range.getTotalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_maximum());
        rate.setDouble(range, 1.5);
        range.calculateRequiredDistributionOfLiquidAssetsAndAnnualisedRatesOfReturn(user.getTotalCurrentNetAssets());
        assertEquals("100.00", range.getTotalAmountOfMediumHighAndHighRiskCurrentNetAssets_distributionRegulations_maximum());
    }
}
