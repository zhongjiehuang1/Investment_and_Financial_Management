package Model.UserAssets;

import Model.Tools.Computing;

public class InvestmentAsset implements Asset {

    private int id = 0;
    public int getId() {
        return id;
    }
    public void setId(int id) {
        this.id = id;
    }

    private java.lang.String investmentAmount = "0.00", cashWithdrawal = "0.00", buyInAmount = "0.00", amountSold = "0.00", managementFee = "0.00", handlingFee = "0.00", realTimeAmount = "0.00";
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
    @Override
    public java.lang.String getAmount() {
        return getRealTimeAmount();
    }
    public void setInvestmentAmount(java.lang.String investmentAmount) {
        this.investmentAmount = Computing.normalizeStored(investmentAmount);
    }
    public void setCashWithdrawal(java.lang.String cashWithdrawal) {
        this.cashWithdrawal = Computing.normalizeStored(cashWithdrawal);
    }
    public void setBuyInAmount(java.lang.String buyInAmount) {
        this.buyInAmount = Computing.normalizeStored(buyInAmount);
    }
    public void setAmountSold(java.lang.String amountSold) {
        this.amountSold = Computing.normalizeStored(amountSold);
    }
    public void setManagementFee(java.lang.String managementFee) {
        this.managementFee = Computing.normalizeStored(managementFee);
    }
    public void setHandlingFee(java.lang.String handlingFee) {
        this.handlingFee = Computing.normalizeStored(handlingFee);
    }
    public void setRealTimeAmount(java.lang.String realTimeAmount) {
        this.realTimeAmount = Computing.normalizeStored(realTimeAmount);
    }

    /** Current account value includes cash; buys, sales and already-paid fees must not be counted twice. */
    public String getEarnings() {
        return Computing.sum(Computing.subtract(realTimeAmount, investmentAmount), cashWithdrawal, initialEarnings);
    }

    public String getUninvestedAmount() {
        String balance = Computing.subtract(investmentAmount, cashWithdrawal);
        balance = Computing.subtract(balance, managementFee);
        balance = Computing.subtract(balance, handlingFee);
        balance = Computing.subtract(balance, buyInAmount);
        return Computing.add(balance, amountSold);
    }

    private java.util.Date date = null;
    public java.util.Date getDate() {
        return date;
    }
    public void setDate(java.util.Date date) {
        this.date = date;
    }

    private String initialEarnings = "0.00";
    public String getInitialEarnings() {
        return initialEarnings;
    }
    public void setInitialEarnings(String initialEarnings) {
        this.initialEarnings = Computing.normalizeStored(initialEarnings);
    }

    private java.lang.String investmentPlatform = null, riskType = null, investmentType = null, currency = null;
    public java.lang.String getInvestmentPlatform() {
        return investmentPlatform;
    }
    public java.lang.String getRiskType() {
        return riskType;
    }
    public java.lang.String getInvestmentType() {
        return investmentType;
    }
    @Override
    public java.lang.String getCurrency() {
        return currency;
    }
    public void setInvestmentPlatform(java.lang.String investmentPlatform) {
        this.investmentPlatform = investmentPlatform;
    }
    public void setRiskType(java.lang.String riskType) {
        this.riskType = riskType;
    }
    public void setInvestmentType(java.lang.String investmentType) {
        this.investmentType = investmentType;
    }
    public void setCurrency(java.lang.String currency) {
        this.currency = currency;
    }

    private int dateOfMonth = 0, monthOfYear = 0, year = 0;
    public int getDateOfMonth() {
        return dateOfMonth;
    }
    public int getMonthOfYear() {
        return monthOfYear;
    }
    public int getYear() {
        return year;
    }
    public void setDateOfMonth(int dateOfMonth) {
        this.dateOfMonth = dateOfMonth;
    }
    public void setMonthOfYear(int monthOfYear) {
        this.monthOfYear = monthOfYear;
    }
    public void setYear(int year) {
        this.year = year;
    }

}
