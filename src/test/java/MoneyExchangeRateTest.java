import Model.Tools.Computing;
import Model.Tools.ExchangeRateForVariousCurrencies;
import org.junit.Test;
import static org.junit.Assert.*;

/** All exchange-rate tests use in-memory snapshots and never invoke an external provider. */
public class MoneyExchangeRateTest {
    @Test public void allTenCurrenciesUseTheSameDailyCrossRates() throws Exception {
        java.util.Map<String, Double> values = java.util.Map.of("USD",1.0,"GBP",0.8,"CNY",7.2,"EUR",0.9,
                "JPY",150.0,"HKD",7.8,"AUD",1.5,"CAD",1.4,"CHF",0.88,"SGD",1.3);
        Class<?> snapshotClass = Class.forName("Model.ExternalAPI.ExchangeRate$Snapshot");
        var constructor = snapshotClass.getDeclaredConstructor(java.util.Map.class, String.class);
        constructor.setAccessible(true);
        var field = Model.ExternalAPI.ExchangeRate.class.getDeclaredField("rates");
        field.setAccessible(true); Object old = field.get(null);
        try {
            field.set(null, constructor.newInstance(values, "2026-10-16T00:00:00Z"));
            withRate(new ExchangeRateForVariousCurrencies.RateSnapshot(9.0, "exchangerate-api", "2026-10-16T00:00:00Z"), () -> {
                for (String from : values.keySet()) for (String to : values.keySet()) {
                    long expected = Math.round(10000.0 * values.get(to) / values.get(from));
                    assertEquals(from + " to " + to, expected, Computing.convertMinorUnits(10000, from, to));
                }
                assertEquals("150.00", Computing.convert("1.00", "USD", "JPY"));
                assertEquals("1.00", Computing.convert("150.00", "JPY", "USD"));
                assertThrows(IllegalArgumentException.class, () -> Computing.convert("1.00", "BAD", "USD"));
            });
        } finally { field.set(null, old); }
    }
    @Test public void missingRatesFailOnlyForNonzeroCrossCurrencyAmounts() throws Exception {
        withRate(new ExchangeRateForVariousCurrencies.RateSnapshot(0, "unavailable", null), () -> {
            assertEquals("0.01", Computing.convert("0.01", "GBP", "GBP"));
            assertEquals("0.00", Computing.convert("0.00", "GBP", "CNY"));
            assertThrows(IllegalStateException.class, () -> Computing.convert("0.01", "GBP", "CNY"));
            assertEquals("unavailable", ExchangeRateForVariousCurrencies.getRateSource());
        });
    }

    @Test public void rateBoundaryRoundsPositiveAndNegativeHalfCentsConsistently() throws Exception {
        withRate(new ExchangeRateForVariousCurrencies.RateSnapshot(10.5, "manual", "2026-10-16T00:00:00Z"), () -> {
            assertEquals("10.50", Computing.convert("1.00", "GBP", "CNY"));
            assertEquals("1.00", Computing.convert("10.50", "CNY", "GBP"));
            assertEquals("0.11", Computing.convert("0.01", "GBP", "CNY"));
            assertEquals("-0.11", Computing.convert("-0.01", "GBP", "CNY"));
            assertThrows(ArithmeticException.class, () -> Computing.convert("92233720368547758.07", "GBP", "CNY"));
        });
    }

    @Test public void manualRateRefreshKeepsItsSourceAndNeverFetchesProviderData() throws Exception {
        ExchangeRateForVariousCurrencies.RateSnapshot manual =
                new ExchangeRateForVariousCurrencies.RateSnapshot(9.5, "manual", "2026-10-16T00:00:00Z");
        withRate(manual, () -> {
            new ExchangeRateForVariousCurrencies().run();
            assertSame(manual, ExchangeRateForVariousCurrencies.getSnapshot());
            assertEquals("manual", ExchangeRateForVariousCurrencies.getRateSource());
            assertEquals("2026-10-16T00:00:00Z", ExchangeRateForVariousCurrencies.getLastUpdatedAt());
        });
    }

    private static void withRate(ExchangeRateForVariousCurrencies.RateSnapshot value, Runnable assertions) throws Exception {
        java.lang.reflect.Field field = ExchangeRateForVariousCurrencies.class.getDeclaredField("rates");
        field.setAccessible(true);
        Object previous = field.get(null);
        try { field.set(null, value); assertions.run(); }
        finally { field.set(null, previous); }
    }
}
