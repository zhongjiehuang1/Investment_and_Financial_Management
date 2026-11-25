package Controller.AssetsData.InvestmentAsset.AddAsset;

public class Command {

    private java.lang.String investmentPlatform = null, investmentType = null, riskType = null,
            investmentAmount = null, cashWithdrawal = null, buyInAmount = null, amountSold = null,
            managementFee = null, handlingFee = null, realTimeAmount = null, currency = null, initialEarnings = null;

    public java.lang.String getInvestmentPlatform() {
        return investmentPlatform;
    }
    public java.lang.String getInvestmentType() {
        return investmentType;
    }
    public java.lang.String getRiskType() {
        return riskType;
    }
    public java.lang.String getInvestmentAmount() {
        return investmentAmount;
    }
    public java.lang.String getCashWithdrawal() {
        return cashWithdrawal;
    }
    public java.lang.String getBuyInAmount() {
        return buyInAmount;
    }
    public java.lang.String getAmountSold() {
        return amountSold;
    }
    public java.lang.String getManagementFee() {
        return managementFee;
    }
    public java.lang.String getHandlingFee() {
        return handlingFee;
    }
    public java.lang.String getRealTimeAmount() {
        return realTimeAmount;
    }
    public java.lang.String getCurrency() {
        return currency;
    }
    public java.lang.String getInitialEarnings() {
        return initialEarnings;
    }
    void setInvestmentPlatform(java.lang.String investmentPlatform) {
        this.investmentPlatform = investmentPlatform;
    }
    void setInvestmentType(java.lang.String investmentType) {
        this.investmentType = investmentType;
    }
    void setRiskType(java.lang.String riskType) {
        this.riskType = riskType;
    }
    void setInvestmentAmount(java.lang.String investmentAmount) {
        this.investmentAmount = investmentAmount;
    }
    void setCashWithdrawal(java.lang.String cashWithdrawal) {
        this.cashWithdrawal = cashWithdrawal;
    }
    void setBuyInAmount(java.lang.String buyInAmount) {
        this.buyInAmount = buyInAmount;
    }
    void setAmountSold(java.lang.String amountSold) {
        this.amountSold = amountSold;
    }
    void setManagementFee(java.lang.String managementFee) {
        this.managementFee = managementFee;
    }
    void setHandlingFee(java.lang.String handlingFee) {
        this.handlingFee = handlingFee;
    }
    void setRealTimeAmount(java.lang.String realTimeAmount) {
        this.realTimeAmount = realTimeAmount;
    }
    void setCurrency(java.lang.String currency) {
        this.currency = currency;
    }
    void setInitialEarnings(java.lang.String initialEarnings) {
        this.initialEarnings = initialEarnings;
    }

    private void cleanData() {
        investmentPlatform = investmentPlatform.trim();
        investmentType = investmentType.trim();
        riskType = riskType.trim();
        investmentAmount = Model.Tools.Computing.normalize(investmentAmount);
        cashWithdrawal = Model.Tools.Computing.normalize(cashWithdrawal);
        buyInAmount = Model.Tools.Computing.normalize(buyInAmount);
        amountSold = Model.Tools.Computing.normalize(amountSold);
        managementFee = Model.Tools.Computing.normalize(managementFee);
        handlingFee = Model.Tools.Computing.normalize(handlingFee);
        realTimeAmount = Model.Tools.Computing.normalize(realTimeAmount);
        currency = currency.trim();
        initialEarnings = Model.Tools.Computing.normalize(initialEarnings);
    }

    boolean runCommand(int userID, Controller.AssetsData.InvestmentAsset.AddAsset.Command command) {
        cleanData();
        return Model.Controller.AssetsData.InvestmentAsset.AddAsset.addInvestmentAsset(userID, command);
    }

}
