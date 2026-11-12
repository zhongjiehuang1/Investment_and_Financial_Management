package Model.Controller.AssetsData.NonInvestmentAsset;

public class AddAsset {

    public static boolean addNonInvestmentAsset(
            int userID, Controller.AssetsData.NonInvestmentAsset.AddAsset.Command command) {
        Model.Tools.Computing.normalize(command.getAmount());
        if (!Model.Controller.AssetsData.CommonFunctions.assetAlreadyExist(userID, command.getAssetName())) {
            return DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.AddAsset.addNonInvestmentAsset(
                    userID, command);
        } else {
            return false;
        }
    }

}
