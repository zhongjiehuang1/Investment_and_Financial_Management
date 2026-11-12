package Model.Controller.InflationRatesData;

public class AddInflationRate {

    public static boolean addInflationRate(
            double annualInflationRate, java.lang.String currency, java.lang.String date) {
        return DatabaseService.Model.Controller.InflationRatesData.AddInflationRate.addInflationRate(
                annualInflationRate, currency, date);
    }

}
