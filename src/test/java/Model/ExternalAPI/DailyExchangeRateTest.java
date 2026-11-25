package Model.ExternalAPI;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.Assert.*;

/** Provider-free regressions for validation and the persisted daily request limit. */
public class DailyExchangeRateTest {
    private static JSONObject fixture() {
        return new JSONObject(Map.of("USD", 1.0, "GBP", 0.783425, "CNY", 7.123456,
                "EUR", 0.93, "JPY", 149.99, "HKD", 7.78, "AUD", 1.52, "CAD", 1.35, "CHF", 0.89, "SGD", 1.33));
    }

    @Test public void fullPrecisionSnapshotRejectsIncompleteOrInvalidRates() {
        Map<String, Double> rates = ExchangeRate.validatedRates(fixture());
        assertEquals(0.783425, rates.get("GBP"), 0.0);
        assertEquals(7.123456, rates.get("CNY"), 0.0);
        assertThrows(UnsupportedOperationException.class, () -> rates.put("GBP", 1.0));
        JSONObject incomplete = fixture(); incomplete.remove("JPY");
        assertThrows(IllegalArgumentException.class, () -> ExchangeRate.validatedRates(incomplete));
        assertThrows(IllegalArgumentException.class, () -> ExchangeRate.validatedRates(fixture().put("GBP", 0)));
        assertThrows(IllegalArgumentException.class, () -> ExchangeRate.validatedRates(fixture().put("USD", 2)));
    }

    @Test public void recentPersistedSnapshotLoadsWithoutAProviderRequestAfterReload() throws Exception {
        Path cache = Files.createTempFile("iafm-rates-test-", ".json");
        String property = System.getProperty("iafm.exchangeRate.cache");
        java.lang.reflect.Field loaded = ExchangeRate.class.getDeclaredField("cacheLoaded");
        java.lang.reflect.Field attempt = ExchangeRate.class.getDeclaredField("lastAttemptAt");
        java.lang.reflect.Field rates = ExchangeRate.class.getDeclaredField("rates");
        java.lang.reflect.Field budgets = ExchangeRate.class.getDeclaredField("attempts"); budgets.setAccessible(true);
        loaded.setAccessible(true); attempt.setAccessible(true); rates.setAccessible(true);
        Object oldLoaded = loaded.get(null), oldAttempt = attempt.get(null), oldRates = rates.get(null);
        java.util.Map<Object,Object> originalBudgets = new java.util.HashMap<>((java.util.Map<?,?>)budgets.get(null));
        String provider = System.getProperty("iafm.exchangeRate.provider");
        try {
            long now = System.currentTimeMillis();
            String asOf = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
            String updated = asOf+"T00:00:00Z";
            JSONObject budget = new JSONObject().put("at", now).put("succeeded", true);
            String persisted = new JSONObject().put("version",2).put("source","ecb").put("asOf",asOf).put("fetchedAt",java.time.Instant.ofEpochMilli(now).toString())
                    .put("lastAttemptAt", now).put("attempts",new JSONObject().put("ecb",budget).put("exchangerate-api",budget))
                    .put("conversion_rates", fixture()).put("updatedAt", updated).toString();
            Files.writeString(cache, persisted);
            System.setProperty("iafm.exchangeRate.cache", cache.toString());
            System.setProperty("iafm.exchangeRate.provider", "ecb");
            ((java.util.Map<?,?>)budgets.get(null)).clear();
            loaded.set(null, false); attempt.set(null, 0L);
            ExchangeRate.getExchangeRate();
            ExchangeRate.getExchangeRate();
            assertEquals(now, attempt.getLong(null));
            assertEquals(persisted, Files.readString(cache));
            assertEquals(updated, ExchangeRate.getLatestRates().updatedAt());
            assertEquals(0.783425, ExchangeRate.getLatestRates().gbp(), 0.0);
            assertEquals(10, ExchangeRate.getAllRates().size());
        } finally {
            loaded.set(null, oldLoaded); attempt.set(null, oldAttempt); rates.set(null, oldRates);
            ((java.util.Map<Object,Object>)budgets.get(null)).clear(); ((java.util.Map<Object,Object>)budgets.get(null)).putAll(originalBudgets);
            if(provider==null)System.clearProperty("iafm.exchangeRate.provider");else System.setProperty("iafm.exchangeRate.provider",provider);
            if (property == null) System.clearProperty("iafm.exchangeRate.cache"); else System.setProperty("iafm.exchangeRate.cache", property);
            Files.deleteIfExists(cache);
        }
    }
}
