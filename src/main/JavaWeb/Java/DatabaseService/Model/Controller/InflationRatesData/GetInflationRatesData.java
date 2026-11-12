package DatabaseService.Model.Controller.InflationRatesData;

import DatabaseService.DatabaseInit;
import Model.Controller.InflationRatesData.GetInflationRatesData.InflationRate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class GetInflationRatesData extends DatabaseInit {
    public static InflationRate[] importData(String currency) {
        try (Connection connection = openPrimaryConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT ID, AIR_IN_VARIOUS_CURRENCIES, VARIOUS_CURRENCIES, DATE FROM ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES "
                        + "WHERE VARIOUS_CURRENCIES = ? ORDER BY DATE DESC, ID DESC LIMIT 100")) {
            statement.setString(1, currency);
            List<InflationRate> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    InflationRate rate = new InflationRate(); rate.setID(result.getInt("ID"));
                    rate.setAnnualInflationRate(result.getDouble("AIR_IN_VARIOUS_CURRENCIES"));
                    rate.setCurrency(result.getString("VARIOUS_CURRENCIES")); rate.setDate(result.getString("DATE")); values.add(rate);
                }
            }
            return values.toArray(new InflationRate[0]);
        } catch (SQLException exception) { return null; }
    }
}
