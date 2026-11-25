package Model.Controller.AssetsData.InvestmentAsset;

public class DeleteAsset {

    public static boolean deleteInvestmentAsset(int userID, int id) {
        return DatabaseService.Model.Controller.AssetsData.InvestmentAsset.DeleteAsset.deleteInvestmentAsset(userID, id);
    }

}
