package Model.Controller.AssetsData.NonInvestmentAsset;

import Model.Tools.Computing;

public class EditAsset {

    public static boolean editNonInvestmentAsset(
            int userID, int id,
            java.lang.String assetName,
            java.lang.String assetType,
            java.lang.String riskType, java.lang.String amount, java.lang.String currency) {
        amount = Computing.normalize(amount);
        return DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(
                userID, id, assetName, assetType, riskType, amount, currency);
    }

}
