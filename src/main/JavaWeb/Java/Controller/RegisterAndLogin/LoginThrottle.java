package Controller.RegisterAndLogin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded per-client login budget, checked before expensive password verification. */
final class LoginThrottle {
    private static final long WINDOW_MILLIS = 300_000L;
    private static final int LIMIT = 20, MAX_CLIENTS = 5000;
    private record Bucket(long start, int attempts) { }
    private static final Map<String, Bucket> ATTEMPTS = new LinkedHashMap<>();
    private LoginThrottle() { }

    static String client(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if ("127.0.0.1".equals(peer) || "::1".equals(peer) || "0:0:0:0:0:0:0:1".equals(peer)) {
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && forwarded.matches("[0-9a-fA-F:.]{3,45}")) return forwarded;
        }
        return peer == null ? "unknown" : peer;
    }

    static synchronized boolean allow(String client, long now) {
        ATTEMPTS.entrySet().removeIf(entry -> now - entry.getValue().start() >= WINDOW_MILLIS);
        Bucket bucket = ATTEMPTS.get(client);
        if (bucket == null) {
            if (ATTEMPTS.size() >= MAX_CLIENTS) return false;
            ATTEMPTS.put(client, new Bucket(now, 1)); return true;
        }
        if (bucket.attempts() >= LIMIT) return false;
        ATTEMPTS.put(client, new Bucket(bucket.start(), bucket.attempts() + 1)); return true;
    }
}
