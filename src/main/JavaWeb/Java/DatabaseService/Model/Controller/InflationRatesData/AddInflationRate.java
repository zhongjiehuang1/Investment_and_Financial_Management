package DatabaseService.Model.Controller.InflationRatesData;

public class AddInflationRate {
    public static boolean addInflationRate(double annualInflationRate, String currency, String date) {
        return InflationSeries.add(annualInflationRate, currency, date);
    }
}
