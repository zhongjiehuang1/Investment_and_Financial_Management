package Model.Controller.AssetsData.NonInvestmentAsset;

public class DeleteAsset {

    public static boolean deleteNonInvestmentAsset(int userID, int id) {
        return DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(userID, id);
    }

}
