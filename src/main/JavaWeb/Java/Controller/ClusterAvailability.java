package Controller;

import java.net.HttpURLConnection;
import java.net.URI;

/** Private readiness is an admission check, never a reason to select another database. */
public final class ClusterAvailability {
    public static final String MESSAGE = "服务器故障，请等待恢复";
    private ClusterAvailability() { }

    static String setting(String key, String environment, String fallback) {
        String value = System.getProperty(key);
        if (value == null) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static int serverId() {
        String id = setting("iafm.server.id", "IAFM_SERVER_ID", "");
        try { return id.matches("[1-9][0-9]*") ? Integer.parseInt(id) : 0; }
        catch (NumberFormatException invalid) { return 0; }
    }

    public static boolean passive() {
        return "passive".equals(setting("iafm.coordinator.mode", "IAFM_COORDINATOR_MODE", "active"));
    }

    public static boolean ready() {
        if (passive()) return false;
        String required = setting("iafm.cluster.health.required", "IAFM_CLUSTER_HEALTH_REQUIRED", "false");
        String address = setting("iafm.cluster.health.url", "IAFM_CLUSTER_HEALTH_URL", "");
        if (address.isEmpty()) return "false".equals(required);
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(address);
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
            if (uri.getUserInfo() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) return false;
            connection = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(1500); connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-store");
            String token = setting("iafm.cluster.health.token", "IAFM_CLUSTER_HEALTH_TOKEN", "");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            return connection.getResponseCode() == 204;
        } catch (Exception unavailable) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static void requireReady() {
        if (!ready()) throw new Unavailable();
    }

    /**
     * Recovery and startup repair happen before application readiness. In the
     * replicated configuration they must consult the local database authority
     * directly, rather than recursively calling the application health check.
     */
    public static boolean recoveryAuthorityReady(boolean required) {
        if (!required) return true; // Preserve the existing non-replicated deployment.
        return recoveryAuthorityIdentity() != null;
    }

    /** The authenticated observer binds permission to its own MySQL instance. */
    public static String recoveryAuthorityIdentity() {
        if (passive()) return null;
        String address = setting("iafm.cluster.authority.url", "IAFM_CLUSTER_AUTHORITY_URL", "");
        String token = setting("iafm.cluster.health.token", "IAFM_CLUSTER_HEALTH_TOKEN", "");
        if (address.isEmpty() || token.length() < 32) return null;
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(address);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1 || !"/authority".equals(uri.getPath())
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) return null;
            connection = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(1500); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (connection.getResponseCode() != 204) return null;
            String value=connection.getHeaderField("X-Four-Project-MySQL-UUID");
            if(value==null)return null;
            String normalized=java.util.UUID.fromString(value).toString();
            return normalized.equals(value.toLowerCase(java.util.Locale.ROOT)) ? normalized : null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super(MESSAGE); }
    }
}
