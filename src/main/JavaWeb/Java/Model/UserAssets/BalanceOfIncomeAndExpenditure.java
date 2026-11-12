package Model.UserAssets;

import Model.Tools.Computing;

public class BalanceOfIncomeAndExpenditure {

    private int ID = 0;
    public int getID() {
        return ID;
    }
    public void setID(int ID) {
        this.ID = ID;
    }

    private java.lang.String type = null, platform = null, name = null, amount = null, currency = null;
    public java.lang.String getType() {
        return type;
    }
    public java.lang.String getPlatform() {
        return platform;
    }
    public java.lang.String getName() {
        return name;
    }
    public java.lang.String getAmount() {
        return amount;
    }
    public java.lang.String getCurrency() {
        return currency;
    }
    public void setType(java.lang.String type) {
        this.type = type;
    }
    public void setPlatform(java.lang.String platform) {
        this.platform = platform;
    }
    public void setName(java.lang.String name) {
        this.name = name;
    }
    public void setAmount(java.lang.String amount) {
        this.amount = Computing.normalizeStored(amount);
    }
    public void setCurrency(java.lang.String currency) {
        this.currency = currency;
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
