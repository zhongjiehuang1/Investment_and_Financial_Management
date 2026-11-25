package DatabaseService;

import Model.ExternalAPI.ExchangeRate;
import Model.Tools.Computing;
import Model.Tools.ExchangeRateForVariousCurrencies;
import org.junit.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class ExchangeRateSnapshotTest {
    private java.lang.reflect.Field providerField, legacyField;
    private Object oldProvider, oldLegacy;
    @Before public void capture() throws Exception {
        providerField = ExchangeRate.class.getDeclaredField("rates"); providerField.setAccessible(true); oldProvider = providerField.get(null);
        legacyField = ExchangeRateForVariousCurrencies.class.getDeclaredField("rates"); legacyField.setAccessible(true); oldLegacy = legacyField.get(null);
    }
    @After public void restore() throws Exception { providerField.set(null, oldProvider); legacyField.set(null, oldLegacy); }
    private void provider(Map<String, Double> values, String timestamp) throws Exception {
        var constructor = Class.forName("Model.ExternalAPI.ExchangeRate$Snapshot").getDeclaredConstructor(Map.class, String.class);
        constructor.setAccessible(true); providerField.set(null, constructor.newInstance(values, timestamp));
    }
    private Connection connection() throws Exception { return DriverManager.getConnection("jdbc:h2:mem:fx_" + UUID.randomUUID(), "sa", ""); }

    @Test public void reportPinsProviderMatrixAndDerivesLegacyPairFromThatSameRefreshGeneration() throws Exception {
        provider(Map.of("USD", 1.0, "GBP", .5, "CNY", 5.0, "EUR", 2.0), "before");
        legacyField.set(null, new ExchangeRateForVariousCurrencies.RateSnapshot(99, "exchangerate-api", "older-legacy-cache"));
        try (Connection a = connection(); Connection b = connection(); ShardScope scope = ShardScope.begin(Map.of(1, a, 2, b), 2)) {
            assertEquals("10.00", Computing.convert("1.00", "GBP", "CNY"));
            assertEquals("2.50", Computing.convert("1.00", "EUR", "CNY"));
            assertEquals("before", ExchangeRateForVariousCurrencies.getLastUpdatedAt());
            provider(Map.of("USD", 1.0, "GBP", .8, "CNY", 16.0, "EUR", 4.0), "after");
            legacyField.set(null, new ExchangeRateForVariousCurrencies.RateSnapshot(777, "exchangerate-api", "mismatched-legacy-cache"));
            assertEquals("10.00", Computing.convert("1.00", "GBP", "CNY"));
            assertEquals("5.00", Computing.convert("1.00", "USD", "CNY"));
            assertEquals("2.50", Computing.convert("1.00", "EUR", "CNY"));
            assertEquals("before", ExchangeRateForVariousCurrencies.getLastUpdatedAt());
        }
        assertEquals("20.00", Computing.convert("1.00", "GBP", "CNY"));
        assertEquals("16.00", Computing.convert("1.00", "USD", "CNY"));
    }

    @Test public void manualPairAndOtherCurrencyRatesRemainPinnedUntilScopeEnds() throws Exception {
        provider(Map.of("USD", 1.0, "GBP", .5, "CNY", 5.0), "before");
        var manual = new ExchangeRateForVariousCurrencies.RateSnapshot(10.5, "manual", "manual-before");
        legacyField.set(null, manual);
        try (Connection a = connection(); Connection b = connection(); ShardScope scope = ShardScope.begin(Map.of(1, a, 2, b), 1)) {
            legacyField.set(null, new ExchangeRateForVariousCurrencies.RateSnapshot(20, "manual", "manual-after"));
            provider(Map.of("USD", 1.0, "GBP", 1.0, "CNY", 30.0), "after");
            assertEquals("10.50", Computing.convert("1.00", "GBP", "CNY"));
            assertEquals("5.00", Computing.convert("1.00", "USD", "CNY"));
            assertSame(manual, ExchangeRateForVariousCurrencies.getSnapshot());
        }
        assertEquals("20.00", Computing.convert("1.00", "GBP", "CNY"));
        assertEquals("30.00", Computing.convert("1.00", "USD", "CNY"));
    }

    @Test public void aMissingRateCannotAppearHalfwayThroughTheSameReport() throws Exception {
        provider(Map.of(), null); legacyField.set(null, new ExchangeRateForVariousCurrencies.RateSnapshot(0, "unavailable", null));
        try (Connection a = connection(); Connection b = connection(); ShardScope scope = ShardScope.begin(Map.of(1, a, 2, b), 1)) {
            assertThrows(Computing.ExchangeRateUnavailableException.class, () -> Computing.convert("1.00", "GBP", "CNY"));
            provider(Map.of("USD", 1.0, "GBP", .5, "CNY", 5.0), "new");
            assertThrows(Computing.ExchangeRateUnavailableException.class, () -> Computing.convert("1.00", "GBP", "CNY"));
        }
        assertEquals("10.00", Computing.convert("1.00", "GBP", "CNY"));
    }
}
