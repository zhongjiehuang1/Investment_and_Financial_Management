package Model.Controller.AssetsData.InvestmentAsset;

import Model.Tools.Computing;

public class EditAsset {

    public static boolean editInvestmentAsset(
            int userID, int id,
            java.lang.String investmentPlatform,
            java.lang.String investmentType,
            java.lang.String riskType,
            java.lang.String investmentAmount,
            java.lang.String cashWithdrawal,
            java.lang.String buyInAmount,
            java.lang.String amountSold,
            java.lang.String managementFee,
            java.lang.String handlingFee,
            java.lang.String realTimeAmount, java.lang.String initialEarnings, java.lang.String currency) {
        investmentAmount = Computing.normalize(investmentAmount);
        cashWithdrawal = Computing.normalize(cashWithdrawal);
        buyInAmount = Computing.normalize(buyInAmount);
        amountSold = Computing.normalize(amountSold);
        managementFee = Computing.normalize(managementFee);
        handlingFee = Computing.normalize(handlingFee);
        realTimeAmount = Computing.normalize(realTimeAmount);
        initialEarnings = Computing.normalize(initialEarnings);
        return DatabaseService.Model.Controller.AssetsData.InvestmentAsset.EditAsset.editInvestmentAsset(
                userID, id,
                investmentPlatform,
                investmentType,
                riskType,
                investmentAmount,
                cashWithdrawal,
                buyInAmount, amountSold, managementFee, handlingFee, realTimeAmount, initialEarnings, currency);
    }

}
