package DatabaseService.Model.Controller.InflationRatesData;

public class EditInflationRate {
    public static boolean editInflationRate(int id, double annualInflationRate, String currency, String date) {
        return InflationSeries.edit(id, annualInflationRate, currency, date);
    }
}
