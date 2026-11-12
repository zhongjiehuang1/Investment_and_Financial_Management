package Model;

import Model.TheFinancialSituationOfTheUser.*;
import Model.UserAssets.*;

public class User implements java.io.Serializable {
    @java.io.Serial private static final long serialVersionUID = 1L;

    /** Only session identity/preferences cross JVMs, never financial caches. */
    @java.io.Serial private Object writeReplace() { return new SessionIdentity(this); }
    @java.io.Serial private void readObject(java.io.ObjectInputStream input) throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Session identity proxy required");
    }
    private static final class SessionIdentity implements java.io.Serializable {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final int id;
        private final String generation, forename, surname, emailAddress, currency;
        private final String[] currencies, platforms;
        SessionIdentity(User user) {
            id=user.id;generation=user.generation;forename=user.forename;surname=user.surname;
            emailAddress=user.emailAddress;currency=user.currencyCurrentChosen;
            currencies=user.currencies==null?null:user.currencies.clone();
            platforms=user.platforms==null?null:user.platforms.clone();
        }
        @java.io.Serial private Object readResolve() throws java.io.InvalidObjectException {
            if(id<=0||!DatabaseService.RequestIdentity.validGeneration(generation))
                throw new java.io.InvalidObjectException("Invalid replicated session identity");
            try { Model.FixedTypes.Currencies.valueOf(currency); }
            catch(IllegalArgumentException|NullPointerException invalid) { throw new java.io.InvalidObjectException("Invalid replicated currency preference"); }
            // The constructor restores all non-null derived helper objects.
            // Every request still validates the generation against MySQL.
            User user=new User();user.id=id;user.generation=generation;user.forename=forename;
            user.surname=surname;user.emailAddress=emailAddress;user.currencyCurrentChosen=currency;
            user.currencies=currencies==null?null:currencies.clone();
            user.platforms=platforms==null?null:platforms.clone();return user;
        }
    }


    private int id = 0;
    private String generation;
    public String getGeneration() { return generation; }
    public void setGeneration(String generation) { this.generation = generation; }
    private java.lang.String forename = null, surname = null, emailAddress = null;
    private volatile java.lang.String currencyCurrentChosen = "GBP";
    public int getId() {
        return id;
    }
    public java.lang.String getForename() {
        return forename;
    }
    public java.lang.String getSurname() {
        return surname;
    }
    public java.lang.String getEmailAddress() {
        return emailAddress;
    }
    public java.lang.String getCurrencyCurrentChosen() {
        return currencyCurrentChosen;
    }
    public void setId(int id) {
        this.id = id;
    }
    public void setForename(java.lang.String forename) {
        this.forename = forename;
    }
    public void setSurname(java.lang.String surname) {
        this.surname = surname;
    }
    public void setEmailAddress(java.lang.String emailAddress) {
        this.emailAddress = emailAddress;
    }
    public void setCurrencyCurrentChosen(java.lang.String currencyCurrentChosen) {
        this.currencyCurrentChosen = currencyCurrentChosen;
    }

    private java.lang.String[] currencies = null, platforms = null;
    public java.lang.String[] getCurrencies() {
        return currencies;
    }
    public java.lang.String[] getPlatforms() {
        return platforms;
    }
    public void setCurrencies(java.lang.String[] currencies) {
        this.currencies = currencies;
    }
    public void setPlatforms(java.lang.String[] platforms) {
        this.platforms = platforms;
    }

    private final TotalCurrentNetAssets totalCurrentNetAssets = new TotalCurrentNetAssets();
    private final CurrencyDistributionOfCurrentNetAssets currencyDistributionOfCurrentNetAssets =
            new CurrencyDistributionOfCurrentNetAssets();
    private final RiskDistributionOfCurrentNetAssets riskDistributionOfCurrentNetAssets =
            new RiskDistributionOfCurrentNetAssets();
    private final CurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets
            currencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets =
            new CurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets();
    private final RangeOfRiskDistributionOfCurrentNetAssets rangeOfRiskDistributionOfCurrentNetAssets =
            new RangeOfRiskDistributionOfCurrentNetAssets();
    private final BasicVariablesAndData basicVariablesAndData = new BasicVariablesAndData();
    public TotalCurrentNetAssets getTotalCurrentNetAssets() {
        return totalCurrentNetAssets;
    }
    public CurrencyDistributionOfCurrentNetAssets getCurrencyDistributionOfCurrentNetAssets() {
        return currencyDistributionOfCurrentNetAssets;
    }
    public RiskDistributionOfCurrentNetAssets getRiskDistributionOfCurrentNetAssets() {
        return riskDistributionOfCurrentNetAssets;
    }
    public CurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets
    getCurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets() {
        return currencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets;
    }
    public RangeOfRiskDistributionOfCurrentNetAssets getRangeOfRiskDistributionOfCurrentNetAssets() {
        return rangeOfRiskDistributionOfCurrentNetAssets;
    }
    public BasicVariablesAndData getBasicVariablesAndData() {
        return basicVariablesAndData;
    }

    private InvestmentAsset[] investmentAssets = null;
    private NonInvestmentAsset[] nonInvestmentAssets = null;
    private BalanceOfIncomeAndExpenditure[] balanceOfIncomesAndExpenditures = null;
    public InvestmentAsset[] getInvestmentAssets() {
        return investmentAssets;
    }
    public NonInvestmentAsset[] getNonInvestmentAssets() {
        return nonInvestmentAssets;
    }
    public BalanceOfIncomeAndExpenditure[] getBalanceOfIncomesAndExpenditures() {
        return balanceOfIncomesAndExpenditures;
    }
    public void setInvestmentAssets(InvestmentAsset[] investmentAssets) {
        this.investmentAssets = investmentAssets;
    }
    public void setNonInvestmentAssets(NonInvestmentAsset[] nonInvestmentAssets) {
        this.nonInvestmentAssets = nonInvestmentAssets;
    }
    public void setBalanceOfIncomesAndExpenditures(BalanceOfIncomeAndExpenditure[] balanceOfIncomesAndExpenditures) {
        this.balanceOfIncomesAndExpenditures = balanceOfIncomesAndExpenditures;
    }

    public static boolean getCurrenciesChosen(Model.User user) {
        return DatabaseService.Model.User.importData_currenciesChosen(user);
    }
    public static boolean getAllPlatforms(Model.User user) {
        return DatabaseService.Model.User.importData_allPlatforms(user);
    }

}
