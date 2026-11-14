package Model.Tools;

import Model.ExternalAPI.ExchangeRate;
import Model.FixedTypes.Currencies;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.util.TimerTask;

@WebListener
public class ExchangeRateForVariousCurrencies extends TimerTask implements ServletContextListener {
    public record RateSnapshot(double gbpToCny, String source, String updatedAt) {}
    private java.util.Timer timer;
    private static volatile RateSnapshot rates = initialRates();
    private record ConversionSnapshot(RateSnapshot legacy, ExchangeRate.DailySnapshot provider) {}
    private static final ThreadLocal<ConversionSnapshot> PINNED = new ThreadLocal<>();

    public static double getGbpToCny() { return getSnapshot().gbpToCny(); }
    public static String getRateSource() { return getSnapshot().source(); }
    public static String getLastUpdatedAt() { return getSnapshot().updatedAt(); }
    public static ExchangeRate.DailySnapshot getProviderSnapshot() {
        ConversionSnapshot pinned = PINNED.get();
        return pinned == null ? ExchangeRate.captureSnapshot() : pinned.provider();
    }
    public static org.json.JSONObject referenceMetadata() {
        RateSnapshot legacy = getSnapshot(); ExchangeRate.DailySnapshot provider = getProviderSnapshot();
        boolean manual = "manual".equals(legacy.source());
        String asOf = provider.asOf(), fetched = provider.fetchedAt();
        if (manual) { asOf = null; fetched = null; } // A configured value has no claimed provider reference date.
        boolean stale = asOf != null && java.time.LocalDate.parse(asOf).isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2));
        return new org.json.JSONObject().put("source", manual ? "manual" : provider.source())
                .put("asOf", asOf == null ? org.json.JSONObject.NULL : asOf)
                .put("fetchedAt", fetched == null ? org.json.JSONObject.NULL : fetched)
                .put("referenceSha256", provider.sha256() == null || manual ? org.json.JSONObject.NULL : provider.sha256())
                .put("referenceOnly", true).put("stale", stale);
    }
    public static RateSnapshot getSnapshot() { ConversionSnapshot pinned = PINNED.get(); return pinned == null ? rates : pinned.legacy; }

    private static ConversionSnapshot captureConversionSnapshot() {
        RateSnapshot legacy = rates;
        ExchangeRate.DailySnapshot provider = ExchangeRate.captureSnapshot();
        if (!"manual".equals(legacy.source())) {
            // Derive the legacy provider pair from this exact USD snapshot, never
            // from the independently refreshed legacy cache's earlier generation.
            double gbp = provider.values().getOrDefault("GBP", 0.0), cny = provider.values().getOrDefault("CNY", 0.0);
            legacy = gbp > 0 && cny > 0
                    ? new RateSnapshot(cny / gbp, provider.source(), provider.updatedAt())
                    : new RateSnapshot(0, "unavailable", provider.updatedAt());
        }
        return new ConversionSnapshot(legacy, provider);
    }
    public static final class RatePin implements AutoCloseable {
        private final ConversionSnapshot previous;
        private boolean closed;
        private RatePin(ConversionSnapshot previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            if (previous == null) PINNED.remove(); else PINNED.set(previous);
            closed = true;
        }
    }
    /** Freeze every conversion and its source metadata for the current request thread. */
    public static RatePin pinForTransaction() {
        ConversionSnapshot previous = PINNED.get();
        if (previous == null) PINNED.set(captureConversionSnapshot());
        return new RatePin(previous);
    }

    private static RateSnapshot initialRates() {
        String configured = System.getenv("IAFM_GBP_TO_CNY");
        if (configured != null && !configured.isBlank()) {
            try {
                double value = Double.parseDouble(configured.trim());
                if (!Double.isFinite(value) || value <= 0 || !Double.isFinite(1.0 / value)) {
                    throw new IllegalArgumentException("Invalid rate");
                }
                return new RateSnapshot(value, "manual", java.time.Instant.now().toString());
            } catch (IllegalArgumentException exception) {
                System.err.println("IAFM_GBP_TO_CNY must be a finite positive rate; rate remains unavailable");
            }
        }
        return new RateSnapshot(0.0, "unavailable", null);
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        timer = new java.util.Timer("investment-exchange-rates", true);
        // An hourly due check uses the persisted 24-hour gate; it never fetches per visitor.
        timer.schedule(new ExchangeRateForVariousCurrencies(), 0, 3600000);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (timer != null) timer.cancel();
    }

    @Override
    public void run() { getExchangeRateForVariousCurrencies(); }

    private static void getExchangeRateForVariousCurrencies() {
        if ("manual".equals(rates.source())) return;
        ExchangeRate.getExchangeRate();
        ExchangeRate.DailySnapshot refreshed = ExchangeRate.captureSnapshot();
        double gbp = refreshed.values().getOrDefault("GBP", 0.0), cny = refreshed.values().getOrDefault("CNY", 0.0);
        if (gbp > 0 && cny > 0) {
            double ratio = cny / gbp;
            if (Double.isFinite(ratio) && ratio > 0 && Double.isFinite(1.0 / ratio)) {
                rates = new RateSnapshot(ratio, refreshed.source(), refreshed.updatedAt());
            }
        }
    }

    public static double currencyConversion(String currencyCurrentChosen, double currencyAmount, String currencyType) {
        Currencies.valueOf(currencyCurrentChosen);
        Currencies.valueOf(currencyType);
        if (!Double.isFinite(currencyAmount)) throw new IllegalArgumentException("Amount must be finite");
        if (currencyType.equals(currencyCurrentChosen) || currencyAmount == 0) return currencyAmount;
        ConversionSnapshot captured = PINNED.get();
        if (captured == null) captured = captureConversionSnapshot();
        RateSnapshot snapshot = captured.legacy;
        boolean legacyPair = ("GBP".equals(currencyType) && "CNY".equals(currencyCurrentChosen))
                || ("CNY".equals(currencyType) && "GBP".equals(currencyCurrentChosen));
        double ratio;
        if (legacyPair && snapshot.gbpToCny() > 0) {
            ratio = "CNY".equals(currencyCurrentChosen) ? snapshot.gbpToCny() : 1.0 / snapshot.gbpToCny();
        } else {
            // Both sides come from the same immutable daily USD snapshot.
            java.util.Map<String, Double> all = captured.provider().values();
            double from = all.getOrDefault(currencyType, 0.0), to = all.getOrDefault(currencyCurrentChosen, 0.0);
            if (from <= 0 || to <= 0) throw new Computing.ExchangeRateUnavailableException();
            ratio = to / from;
        }
        if (!Double.isFinite(ratio) || ratio <= 0) throw new Computing.ExchangeRateUnavailableException();
        double converted = currencyAmount * ratio;
        if (!Double.isFinite(converted)) throw new ArithmeticException("Converted amount exceeds the supported range");
        return converted;
    }
}
