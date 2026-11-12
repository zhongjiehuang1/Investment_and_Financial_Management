package Model.Controller.AssetsData.InvestmentAsset;

public class AddAsset {

    public static boolean addInvestmentAsset(
            int userID, Controller.AssetsData.InvestmentAsset.AddAsset.Command command) {
        Model.Tools.Computing.normalize(command.getInvestmentAmount());
        Model.Tools.Computing.normalize(command.getCashWithdrawal());
        Model.Tools.Computing.normalize(command.getBuyInAmount());
        Model.Tools.Computing.normalize(command.getAmountSold());
        Model.Tools.Computing.normalize(command.getManagementFee());
        Model.Tools.Computing.normalize(command.getHandlingFee());
        Model.Tools.Computing.normalize(command.getRealTimeAmount());
        Model.Tools.Computing.normalize(command.getInitialEarnings());
        if (!Model.Controller.AssetsData.CommonFunctions.assetAlreadyExist(userID, command.getInvestmentPlatform())) {
            return DatabaseService.Model.Controller.AssetsData.InvestmentAsset.AddAsset.addInvestmentAsset(
                    userID, command);
        } else {
            return false;
        }
    }

}
