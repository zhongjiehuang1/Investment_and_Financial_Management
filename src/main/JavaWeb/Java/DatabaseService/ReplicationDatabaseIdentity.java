package DatabaseService;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Bind every replicated logical shard and decision store to the local writer. */
final class ReplicationDatabaseIdentity {
    private ReplicationDatabaseIdentity() { }
    private record Endpoint(int port,String schema) { }

    private static Endpoint endpoint(Properties properties,String key) {
        String raw=properties.getProperty(key,"");
        if(!raw.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("Missing private MySQL endpoint: "+key);
        URI uri=URI.create(raw.substring(5));
        String path=uri.getRawPath();
        if(!"mysql".equals(uri.getScheme()) || !("127.0.0.1".equals(uri.getHost())||"localhost".equals(uri.getHost()))
                || uri.getPort()<1 || uri.getUserInfo()!=null || uri.getFragment()!=null
                || path==null || !path.matches("/[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Replicated endpoints require an explicit loopback MySQL port and schema");
        return new Endpoint(uri.getPort(),path.substring(1));
    }

    static void validateEndpoints(Properties properties) {
        Endpoint one=endpoint(properties,"node1.url"),two=endpoint(properties,"node2.url"),decision=endpoint(properties,"objectStore.jdbc.url");
        if(one.port()!=two.port()||one.port()!=decision.port()||one.schema().equalsIgnoreCase(two.schema()))
            throw new IllegalArgumentException("Logical shards and decisions must use one local MySQL instance and distinct shard schemas");
    }

    static void verify(Connection connection,String expected) throws SQLException {
        if(expected==null)throw new SQLException("Missing authenticated local MySQL identity");
        try(Statement query=connection.createStatement();ResultSet rows=query.executeQuery("SELECT @@server_uuid,@@session.sql_log_bin,@@session.group_replication_consistency")) {
            if(!rows.next())throw new SQLException("MySQL identity is unavailable");
            String actual=rows.getString(1);
            try {
                if(actual==null||!UUID.fromString(actual).toString().equals(expected.toLowerCase(Locale.ROOT)))
                    throw new SQLException("MySQL endpoint does not match the authorized writer");
            } catch(IllegalArgumentException invalid) {
                throw new SQLException("Invalid MySQL instance identity",invalid);
            }
            String consistency=rows.getString(3);
            if(rows.getInt(2)!=1||!("AFTER".equals(consistency)||"BEFORE_AND_AFTER".equals(consistency)))
                throw new SQLException("Database session does not guarantee replicated writes");
        }
    }

    static Connection open(Properties properties,String prefix) throws SQLException {
        String url=properties.getProperty(prefix+"url"),user=properties.getProperty(prefix+"user"),password=properties.getProperty(prefix+"password");
        if(url==null||user==null||user.isBlank()||password==null)throw new SQLException("Incomplete replicated database configuration");
        return DriverManager.getConnection(FinanceCluster.boundedUrl(url),user,password);
    }
}
