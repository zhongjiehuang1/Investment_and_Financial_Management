package Model.ExternalAPI;

import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One shared daily provider snapshot, retained across application reloads. */
public class ExchangeRate {
    public record UsdRates(double gbp, double cny, String updatedAt) {}
    public record DailySnapshot(Map<String, Double> values, String updatedAt) {}
    private record Snapshot(Map<String, Double> values, String updatedAt) {}
    private static volatile Snapshot rates = new Snapshot(Map.of(), null);
    private static long lastAttemptAt;
    private static boolean cacheLoaded;
    private static final long DAY = 86_400_000L;

    public static double getUSDToGBP() { return rates.values().getOrDefault("GBP", 0.0); }
    public static double getUSDToCNY() { return rates.values().getOrDefault("CNY", 0.0); }
    public static UsdRates getLatestRates() {
        Snapshot snapshot = rates;
        return new UsdRates(snapshot.values().getOrDefault("GBP", 0.0), snapshot.values().getOrDefault("CNY", 0.0), snapshot.updatedAt());
    }
    public static Map<String, Double> getAllRates() { return rates.values(); }
    public static DailySnapshot captureSnapshot() {
        Snapshot snapshot = rates;
        return new DailySnapshot(Map.copyOf(snapshot.values()), snapshot.updatedAt());
    }
    public static String getUpdatedAt() { return rates.updatedAt(); }

    private static String apiKey() {
        String key = System.getenv("IAFM_EXCHANGE_RATE_API_KEY");
        if (key != null && !key.isBlank()) return key;
        try { return (String) new javax.naming.InitialContext().lookup("java:comp/env/iafm/exchangeRateApiKey"); }
        catch (javax.naming.NamingException | ClassCastException missing) { return null; }
    }

    private static Path cachePath() {
        String configured = System.getProperty("iafm.exchangeRate.cache", System.getenv("IAFM_EXCHANGE_RATE_CACHE"));
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String base = System.getProperty("catalina.base");
        return base == null ? null : Path.of(base, "work", "iafm-exchange-rates.json");
    }

    static Map<String, Double> validatedRates(JSONObject values) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String code : values.keySet()) {
            double value = values.getDouble(code);
            if (!code.matches("[A-Z]{3}") || !Double.isFinite(value) || value <= 0 || !Double.isFinite(1.0 / value))
                throw new IllegalArgumentException("Invalid rates");
            result.put(code, value);
        }
        for (String required : new String[]{"USD", "GBP", "CNY", "EUR", "JPY", "HKD", "AUD", "CAD", "CHF", "SGD"})
            if (!result.containsKey(required)) throw new IllegalArgumentException("Incomplete rates");
        if (Math.abs(result.get("USD") - 1.0) > 0.00000001) throw new IllegalArgumentException("Invalid base rate");
        double ratio = result.get("CNY") / result.get("GBP");
        if (!Double.isFinite(ratio) || ratio <= 0 || !Double.isFinite(1.0 / ratio)) throw new IllegalArgumentException("Invalid rates");
        return Map.copyOf(result);
    }

    private static void loadCache() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        Path path = cachePath();
        if (path == null || !Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 131072) throw new IOException("Cache too large");
            JSONObject stored = new JSONObject(Files.readString(path));
            long attempt = stored.optLong("lastAttemptAt", 0);
            if (attempt >= 0 && attempt <= System.currentTimeMillis()) lastAttemptAt = attempt;
            JSONObject values = stored.optJSONObject("conversion_rates");
            if (values != null && !values.isEmpty()) {
                String updatedAt = stored.getString("updatedAt");
                Instant.parse(updatedAt);
                rates = new Snapshot(validatedRates(values), updatedAt);
            }
        } catch (IOException | RuntimeException invalid) {
            System.err.println("Exchange-rate cache could not be loaded");
        }
    }

    private static void saveCache() {
        Path path = cachePath();
        if (path == null) return;
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            JSONObject stored = new JSONObject().put("lastAttemptAt", lastAttemptAt)
                    .put("conversion_rates", rates.values()).put("updatedAt", rates.updatedAt());
            Files.writeString(temporary, stored.toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            System.err.println("Exchange-rate cache could not be saved");
        }
    }

    public static synchronized void getExchangeRate() {
        loadCache();
        long now = System.currentTimeMillis();
        if (lastAttemptAt > 0 && now - lastAttemptAt < DAY) return;
        String key = apiKey();
        if (key == null || key.isBlank()) return;
        if (!key.matches("[A-Za-z0-9_-]{8,200}")) {
            System.err.println("Exchange-rate API key configuration is invalid");
            return;
        }
        // Persist before calling the provider so context reloads never spend another daily request.
        lastAttemptAt = now;
        saveCache();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("https://v6.exchangerate-api.com/v6/" + key + "/latest/USD")
                    .toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Provider unavailable");
            byte[] response;
            try (java.io.InputStream input = connection.getInputStream()) { response = input.readNBytes(131073); }
            if (response.length > 131072) throw new IOException("Response is too large");
            JSONObject body = new JSONObject(new String(response, StandardCharsets.UTF_8));
            if (!"success".equals(body.optString("result"))) throw new IOException("Invalid provider response");
            Map<String, Double> values = validatedRates(body.getJSONObject("conversion_rates"));
            long providerTime = body.optLong("time_last_update_unix", now / 1000);
            rates = new Snapshot(values, Instant.ofEpochSecond(providerTime).toString());
            saveCache();
        } catch (IOException | RuntimeException exception) {
            // The provider key is part of the URL; never log the URL or exception message.
            System.err.println("Exchange-rate refresh failed; retaining the previous daily snapshot");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
