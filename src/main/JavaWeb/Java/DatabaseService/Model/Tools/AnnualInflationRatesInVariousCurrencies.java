package DatabaseService.Model.Tools;

import Model.Controller.InflationRatesData.GetInflationRatesData.InflationRate;

/** Legacy cache is bounded; current pages and reports use request-local database reads. */
public class AnnualInflationRatesInVariousCurrencies {
    public static void importData() {
        for (String currency : new String[]{"GBP", "CNY"}) {
            InflationRate[] records = DatabaseService.Model.Controller.InflationRatesData.GetInflationRatesData.importData(currency);
            if (records == null) return;
            double[] rates = new double[records.length]; String[] dates = new String[records.length];
            for (int i = 0; i < records.length; i++) {
                rates[i] = records[records.length - 1 - i].getAnnualInflationRate();
                dates[i] = records[records.length - 1 - i].getDate();
            }
            if ("GBP".equals(currency)) {
                Model.Tools.AnnualInflationRatesInVariousCurrencies.setAnnualInflationRatesOfGBP(rates);
                Model.Tools.AnnualInflationRatesInVariousCurrencies.setAnnualInflationRateDatesOfGBP(dates);
            } else {
                Model.Tools.AnnualInflationRatesInVariousCurrencies.setAnnualInflationRatesOfCNY(rates);
                Model.Tools.AnnualInflationRatesInVariousCurrencies.setAnnualInflationRateDatesOfCNY(dates);
            }
        }
    }
}
