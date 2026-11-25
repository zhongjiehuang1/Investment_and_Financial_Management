package Model.ExternalAPI;

import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable daily reference quotes. No key defaults to the official free ECB feed. */
public class ExchangeRate {
    public record UsdRates(double gbp, double cny, String updatedAt) { }
    public record DailySnapshot(Map<String, Double> values, String updatedAt, String source, String asOf, String fetchedAt) {
        public DailySnapshot { values = Map.copyOf(values); }
        public String sha256() {
            if (values.isEmpty()) return null;
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                digest.update((source+"\n"+asOf+"\n").getBytes(StandardCharsets.UTF_8));
                for (var value : new java.util.TreeMap<>(values).entrySet())
                    digest.update((value.getKey()+":"+Double.toHexString(value.getValue())+"\n").getBytes(StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
    }
    private record Snapshot(Map<String, Double> values, String updatedAt, String source, String asOf, String fetchedAt) {
        // Retain the legacy fixture construction API; runtime input uses validated metadata.
        private Snapshot(Map<String, Double> values, String updatedAt) {
            this(Map.copyOf(values), updatedAt, values.isEmpty() ? "unavailable" : "exchangerate-api", dateOf(updatedAt), updatedAt);
        }
        DailySnapshot daily() { return new DailySnapshot(values, updatedAt, source, asOf, fetchedAt); }
    }
    private record Attempt(long at, boolean succeeded) { }
    private static volatile Snapshot rates = new Snapshot(Map.of(), null);
    private static long lastAttemptAt;
    private static final Map<String, Attempt> attempts = new HashMap<>();
    private static boolean cacheLoaded;
    private static final long DAY = 86_400_000L, HOUR = 3_600_000L, ECB_FAILURE_DELAY = 900_000L;
    static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";

    public static double getUSDToGBP() { return rates.values().getOrDefault("GBP", 0.0); }
    public static double getUSDToCNY() { return rates.values().getOrDefault("CNY", 0.0); }
    public static UsdRates getLatestRates() {
        Snapshot s = rates; return new UsdRates(s.values().getOrDefault("GBP", 0.0), s.values().getOrDefault("CNY", 0.0), s.updatedAt());
    }
    public static Map<String, Double> getAllRates() { return rates.values(); }
    public static DailySnapshot captureSnapshot() { return rates.daily(); }
    public static String getUpdatedAt() { return rates.updatedAt(); }
    public static int refreshIntervalHours(String source) { return "ecb".equals(source) ? 1 : 24; }
    private static String dateOf(String value) {
        try { return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate().toString(); }
        catch (RuntimeException invalid) { return null; }
    }
    private static boolean sourceValid(String value) { return "ecb".equals(value) || "exchangerate-api".equals(value); }

    static String selectProvider(String setting) {
        String source = setting == null || setting.isBlank() ? "ecb" : setting.trim().toLowerCase(java.util.Locale.ROOT);
        if (!sourceValid(source)) throw new IllegalArgumentException("Invalid exchange-rate provider");
        return source;
    }
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
        if (values.length() > 200) throw new IllegalArgumentException("Invalid rates");
        for (String code : values.keySet()) {
            double value = values.getDouble(code);
            if (!code.matches("[A-Z]{3}") || !Double.isFinite(value) || value <= 0 || !Double.isFinite(1.0 / value)) throw new IllegalArgumentException("Invalid rates");
            result.put(code, value);
        }
        for (String required : new String[]{"USD", "GBP", "CNY", "EUR", "JPY", "HKD", "AUD", "CAD", "CHF", "SGD"})
            if (!result.containsKey(required)) throw new IllegalArgumentException("Incomplete rates");
        if (Math.abs(result.get("USD") - 1.0) > 0.00000001) throw new IllegalArgumentException("Invalid base rate");
        double ratio = result.get("CNY") / result.get("GBP");
        if (!Double.isFinite(ratio) || ratio <= 0 || !Double.isFinite(1.0 / ratio)) throw new IllegalArgumentException("Invalid rates");
        return Map.copyOf(result);
    }
    static DailySnapshot validatedSnapshot(JSONObject stored, Instant now) {
        String source = stored.getString("source"), asOf = stored.getString("asOf"), fetched = stored.getString("fetchedAt"), updated = stored.getString("updatedAt");
        LocalDate day = LocalDate.parse(asOf); Instant fetchedAt = Instant.parse(fetched), updatedAt = Instant.parse(updated);
        if (!sourceValid(source) || !asOf.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || day.isBefore(LocalDate.of(1999, 1, 1)) || day.isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate())
                || fetchedAt.isAfter(now.plusSeconds(300)) || fetchedAt.isBefore(updatedAt)
                || !day.equals(updatedAt.atOffset(ZoneOffset.UTC).toLocalDate())
                || "ecb".equals(source) && !updatedAt.equals(day.atStartOfDay().toInstant(ZoneOffset.UTC))) throw new IllegalArgumentException("Invalid reference metadata");
        return new DailySnapshot(validatedRates(stored.getJSONObject("conversion_rates")), updated, source, asOf, fetched);
    }
    private static void loadCache() {
        if (cacheLoaded) return; cacheLoaded = true;
        Path path = cachePath();
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            byte[] cacheBytes;
            try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                cacheBytes = input.readNBytes(EcbDailyRates.MAX_BYTES+1);
            }
            if (cacheBytes.length > EcbDailyRates.MAX_BYTES) throw new IOException("Cache too large");
            JSONObject stored = new JSONObject(new String(cacheBytes, StandardCharsets.UTF_8)); long now = System.currentTimeMillis();
            long legacy = stored.optLong("lastAttemptAt", 0);
            // Preserve an older paid-provider budget even when its untagged rates cannot be trusted.
            if (legacy > 0 && legacy <= now) lastAttemptAt = legacy;
            if (stored.optInt("version", 0) != 2) {
                if (legacy > 0 && legacy <= now) attempts.put("exchangerate-api", new Attempt(legacy, false));
                return;
            }
            JSONObject budget = stored.optJSONObject("attempts");
            if (budget != null) for (String source : budget.keySet()) {
                if (!sourceValid(source)) throw new IllegalArgumentException("Invalid provider budget");
                JSONObject value = budget.getJSONObject(source); long at = value.getLong("at");
                if (at < 0 || at > now) throw new IllegalArgumentException("Invalid provider budget");
                attempts.put(source, new Attempt(at, value.getBoolean("succeeded")));
            }
            JSONObject values = stored.optJSONObject("conversion_rates");
            if (values != null && !values.isEmpty()) {
                DailySnapshot snapshot = validatedSnapshot(stored, Instant.ofEpochMilli(now));
                rates = new Snapshot(snapshot.values(), snapshot.updatedAt(), snapshot.source(), snapshot.asOf(), snapshot.fetchedAt());
            }
        } catch (IOException | RuntimeException invalid) { System.err.println("Exchange-rate cache could not be loaded"); }
    }
    /** A durable attempt record must exist before any provider request. */
    private static boolean saveCache() {
        Path path = cachePath(); if (path == null) return false; Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent(); Files.createDirectories(parent);
            if (Files.isSymbolicLink(path) || Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe cache");
            Snapshot s = rates; JSONObject budgets = new JSONObject();
            for (var entry : attempts.entrySet()) budgets.put(entry.getKey(), new JSONObject().put("at", entry.getValue().at()).put("succeeded", entry.getValue().succeeded()));
            JSONObject stored = new JSONObject().put("version", 2).put("lastAttemptAt", lastAttemptAt).put("attempts", budgets)
                    .put("conversion_rates", s.values()).put("updatedAt", s.updatedAt()).put("source", s.source()).put("asOf", s.asOf()).put("fetchedAt", s.fetchedAt());
            temporary = Files.createTempFile(parent, path.getFileName()+".", ".tmp",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(stored.toString());
                while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw new IOException("Cache short write");
                channel.force(true);
            }
            // Never replace the durable gate non-atomically and then make an outbound request.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); temporary = null;
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
            return true;
        } catch (IOException | RuntimeException failure) { System.err.println("Exchange-rate cache could not be persisted"); return false; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    static boolean due(String source, long now) {
        Attempt prior = attempts.get(source); if (prior == null) return true;
        long interval = "exchangerate-api".equals(source) ? DAY : prior.succeeded() ? HOUR : ECB_FAILURE_DELAY;
        return now >= prior.at() && now - prior.at() >= interval;
    }
    private static DailySnapshot fetch(String source, String key) throws IOException {
        HttpURLConnection connection = null;
        try {
            String address = "ecb".equals(source) ? ECB_URL : "https://v6.exchangerate-api.com/v6/"+key+"/latest/USD";
            connection = (HttpURLConnection) URI.create(address).toURL().openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(5000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestMethod("GET"); connection.setRequestProperty("Cache-Control", "no-cache");
            if (connection.getResponseCode() != 200) throw new IOException("Provider unavailable");
            byte[] bytes; try (var input = connection.getInputStream()) { bytes = input.readNBytes(EcbDailyRates.MAX_BYTES+1); }
            if (bytes.length > EcbDailyRates.MAX_BYTES) throw new IOException("Response too large");
            Instant received = Instant.now();
            if ("ecb".equals(source)) return EcbDailyRates.parse(bytes, received);
            JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"success".equals(body.optString("result"))) throw new IOException("Invalid provider response");
            Instant updated = Instant.ofEpochSecond(body.getLong("time_last_update_unix"));
            JSONObject stored = new JSONObject().put("source", source).put("asOf", updated.atOffset(ZoneOffset.UTC).toLocalDate().toString())
                    .put("fetchedAt", received.toString()).put("updatedAt", updated.toString()).put("conversion_rates", body.getJSONObject("conversion_rates"));
            return validatedSnapshot(stored, received);
        } catch (RuntimeException invalid) { throw new IOException("Provider snapshot is invalid"); }
        finally { if (connection != null) connection.disconnect(); }
    }
    public static synchronized void getExchangeRate() {
        loadCache(); long now = System.currentTimeMillis(); String source;
        try { source = selectProvider(System.getProperty("iafm.exchangeRate.provider", System.getenv("IAFM_EXCHANGE_RATE_PROVIDER"))); }
        catch (IllegalArgumentException invalid) { System.err.println("Exchange-rate provider configuration is invalid"); return; }
        // Merely retaining an old key must never silently select a billed provider.
        String key = "exchangerate-api".equals(source) ? apiKey() : null;
        if ("exchangerate-api".equals(source) && (key == null || !key.matches("[A-Za-z0-9_-]{8,200}"))) { System.err.println("Exchange-rate API key configuration is invalid"); return; }
        if (!due(source, now)) return;
        lastAttemptAt = now; attempts.put(source, new Attempt(now, false));
        if (!saveCache()) return;
        try {
            DailySnapshot snapshot = fetch(source, key);
            rates = new Snapshot(snapshot.values(), snapshot.updatedAt(), snapshot.source(), snapshot.asOf(), snapshot.fetchedAt());
            attempts.put(source, new Attempt(now, true)); saveCache();
        } catch (IOException | RuntimeException failure) {
            // A configured provider key is in its URL: never log request URLs or exception messages.
            System.err.println("Exchange-rate refresh failed; retaining the previous daily reference snapshot");
        }
    }
}
