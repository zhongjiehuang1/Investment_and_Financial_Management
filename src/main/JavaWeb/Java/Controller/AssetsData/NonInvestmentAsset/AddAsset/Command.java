package Controller.AssetsData.NonInvestmentAsset.AddAsset;

public class Command {

    private java.lang.String assetName = null, assetType = null, riskType = null, amount = null, currency = null;

    public java.lang.String getAssetName() {
        return assetName;
    }
    public java.lang.String getAssetType() {
        return assetType;
    }
    public java.lang.String getRiskType() {
        return riskType;
    }
    public java.lang.String getAmount() {
        return amount;
    }
    public java.lang.String getCurrency() {
        return currency;
    }
    void setAssetName(java.lang.String assetName) {
        this.assetName = assetName;
    }
    void setAssetType(java.lang.String assetType) {
        this.assetType = assetType;
    }
    void setRiskType(java.lang.String riskType) {
        this.riskType = riskType;
    }
    void setAmount(java.lang.String amount) {
        this.amount = amount;
    }
    void setCurrency(java.lang.String currency) {
        this.currency = currency;
    }

    private void cleanData() {
        assetName = assetName.trim();
        assetType = assetType.trim();
        riskType = riskType.trim();
        amount = Model.Tools.Computing.normalize(amount);
        currency = currency.trim();
    }

    boolean runCommand(int userID, Controller.AssetsData.NonInvestmentAsset.AddAsset.Command command) {
        cleanData();
        return Model.Controller.AssetsData.NonInvestmentAsset.AddAsset.addNonInvestmentAsset(userID, command);
    }

}
