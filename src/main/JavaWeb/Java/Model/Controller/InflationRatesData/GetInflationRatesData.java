package Model.Controller.InflationRatesData;

public class GetInflationRatesData {

    public static InflationRate[] getInflationRatesData(java.lang.String currency) {
        return DatabaseService.Model.Controller.InflationRatesData.GetInflationRatesData.importData(currency);
    }

    public static class InflationRate {

        private int ID;
        private double annualInflationRate;
        private java.lang.String currency, date;

        public int getID() {
            return ID;
        }
        public double getAnnualInflationRate() {
            return annualInflationRate;
        }
        public java.lang.String getCurrency() {
            return currency;
        }
        public java.lang.String getDate() {
            return date;
        }
        public void setID(int ID) {
            this.ID = ID;
        }
        public void setAnnualInflationRate(double annualInflationRate) {
            this.annualInflationRate = annualInflationRate;
        }
        public void setCurrency(java.lang.String currency) {
            this.currency = currency;
        }
        public void setDate(java.lang.String date) {
            this.date = date;
        }

    }

}
