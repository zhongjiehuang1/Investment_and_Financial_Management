package Model.UserAssets;

import Model.Tools.Computing;

public class NonInvestmentAsset implements Asset {

    private int id = 0;
    public int getId() {
        return id;
    }
    public void setId(int id) {
        this.id = id;
    }

    private java.lang.String currency = null, assetName = null, assetType = null, riskType = null, amount = "0.00";
    @Override
    public java.lang.String getCurrency() {
        return currency;
    }
    public java.lang.String getAssetName() {
        return assetName;
    }
    public java.lang.String getAssetType() {
        return assetType;
    }
    public java.lang.String getRiskType() {
        return riskType;
    }
    @Override
    public java.lang.String getAmount() {
        return amount;
    }
    public void setCurrency(java.lang.String currency) {
        this.currency = currency;
    }
    public void setAssetName(java.lang.String assetName) {
        this.assetName = assetName;
    }
    public void setAssetType(java.lang.String assetType) {
        this.assetType = assetType;
    }
    public void setRiskType(java.lang.String riskType) {
        this.riskType = riskType;
    }
    public void setAmount(java.lang.String amount) {
        this.amount = Computing.normalizeStored(amount);
    }

    private java.util.Date date = null;
    public java.util.Date getDate() {
        return date;
    }
    public void setDate(java.util.Date date) {
        this.date = date;
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
