package DatabaseService.Model.Controller.InflationRatesData;

public class DeleteInflationRate {
    public static boolean deleteInflationRate(int id) {
        return InflationSeries.delete(id);
    }
}
