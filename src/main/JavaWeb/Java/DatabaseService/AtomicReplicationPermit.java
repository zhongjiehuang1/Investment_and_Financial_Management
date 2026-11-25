package DatabaseService;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Private controller protocol. Legacy UUID-only authority is never sufficient. */
final class AtomicReplicationPermit implements AtomicFinanceTransactions.Authority {
    private final URI authority;
    private final String token;

    AtomicReplicationPermit(Properties configuration) {
        String endpoint = setting(configuration, "atomic.authority.url", "iafm.cluster.authority.url", "IAFM_CLUSTER_AUTHORITY_URL");
        token = setting(configuration, "atomic.authority.token", "iafm.cluster.health.token", "IAFM_CLUSTER_HEALTH_TOKEN");
        authority = URI.create(endpoint);
        if (!"http".equals(authority.getScheme()) || !"127.0.0.1".equals(authority.getHost())
                || authority.getPort() < 1 || !"/authority".equals(authority.getPath())
                || authority.getUserInfo() != null || authority.getRawQuery() != null || authority.getFragment() != null
                || token.length() < 32)
            throw new IllegalArgumentException("Complete private atomic authority configuration is required");
    }

    private static String setting(Properties properties, String key, String system, String environment) {
        String value = properties.getProperty(key);
        if (value == null) value = System.getProperty(system);
        if (value == null) value = System.getenv(environment);
        return value == null ? "" : value.trim();
    }

    private HttpURLConnection connect(URI uri, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(1500); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Cache-Control", "no-store");
        return connection;
    }

    private static String header(HttpURLConnection connection, String key, int maximum) throws SQLException {
        long count=connection.getHeaderFields().entrySet().stream().filter(entry->key.equalsIgnoreCase(entry.getKey())).mapToLong(entry->entry.getValue().size()).sum();
        if(count!=1)throw new SQLException("Atomic authority returned ambiguous evidence");
        String value = connection.getHeaderField(key);
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.trim()) || value.chars().anyMatch(ch -> ch < 33 || ch > 126))
            throw new SQLException("Atomic authority returned incomplete evidence");
        return value;
    }

    @Override public AtomicFinanceTransactions.Proof capture() throws SQLException {
        HttpURLConnection connection = null;
        try {
            connection = connect(authority, "GET");
            if (connection.getResponseCode() != 204) throw new SQLException("Atomic writer authority is unavailable");
            return proof(connection);
        } catch (SQLException failure) { throw failure; }
        catch (Exception failure) { throw new SQLException("Atomic writer authority is unavailable"); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static AtomicFinanceTransactions.Proof proof(HttpURLConnection connection)throws SQLException {
        try{
            String mysql = header(connection, "X-Four-Project-MySQL-UUID", 36);
            if (!UUID.fromString(mysql).toString().equals(mysql.toLowerCase(Locale.ROOT)))
                throw new SQLException("Invalid authorized MySQL identity");
            String view = header(connection, "X-Four-Project-View-ID", 512);
            String epoch = header(connection, "X-Four-Project-Epoch", 19);
            if (!epoch.matches("0|[1-9][0-9]{0,18}") || Long.parseLong(epoch) < 0)
                throw new SQLException("Invalid atomic authority epoch");
            String mode = header(connection, "X-Four-Project-Mode", 6);
            if (!mode.equals("dual") && !mode.equals("single")) throw new SQLException("Invalid authority durability mode");
            String membership = header(connection, "X-Four-Project-Membership", 64);
            if (!membership.matches("[a-f0-9]{64}")) throw new SQLException("Invalid membership digest");
            String boot=header(connection,"X-Four-Project-Controller-Boot-ID",36);
            if(!UUID.fromString(boot).toString().equals(boot.toLowerCase(Locale.ROOT)))throw new SQLException("Invalid controller boot identity");
            String permit = header(connection, "X-Four-Project-Permit", 4096);
            return new AtomicFinanceTransactions.Proof(mysql.toLowerCase(Locale.ROOT), view, epoch, mode, membership,boot.toLowerCase(Locale.ROOT),permit);
        } catch (SQLException failure) { throw failure; }
        catch (Exception failure) { throw new SQLException("Atomic authority returned incomplete evidence"); }
    }

    @Override public void verify(AtomicFinanceTransactions.Proof proof) throws SQLException {
        if (!proof.equals(capture())) throw new SQLException("Atomic writer epoch or membership changed");
    }

    @Override public String confirm(Connection database, AtomicFinanceTransactions.Proof proof, String operation) throws SQLException {
        verify(proof);
        ReplicationDatabaseIdentity.verify(database, proof.mysqlUuid());
        AtomicFinanceTransactions.verifyDurability(database);
        String gtid;
        try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT @@GLOBAL.gtid_executed")) {
            if (!rows.next() || (gtid = rows.getString(1)) == null || gtid.isBlank() || gtid.length() > 1_048_576)
                throw new SQLException("Committed GTID evidence is unavailable");
        }
        HttpURLConnection connection = null;
        try {
            connection = connect(authority.resolve("/confirm"), "POST");
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("permit", proof.permit()).put("gtidSet", gtid)
                    .put("operationId", operation).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
            if (connection.getResponseCode() != 204) throw new SQLException("Committed operation has an unconfirmed outcome");
            if(!proof.equals(proof(connection)))throw new SQLException("Confirmation does not match the captured authority proof");
            String durability = header(connection, "X-Four-Project-Durability", 6);
            if (!durability.equals(proof.mode())) throw new SQLException("Operation durability changed during confirmation");
            return durability;
        } catch (SQLException failure) { throw failure; }
        catch (Exception failure) { throw new SQLException("Committed operation has an unconfirmed outcome"); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
