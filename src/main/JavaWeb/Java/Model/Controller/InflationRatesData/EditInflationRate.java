package Model.Controller.InflationRatesData;

public class EditInflationRate {

    public static boolean editInflationRate(
            int id, double annualInflationRate, java.lang.String currency, java.lang.String date) {
        return DatabaseService.Model.Controller.InflationRatesData.EditInflationRate.editInflationRate(
                id, annualInflationRate, currency, date);
    }

}
