package DatabaseService;

/** No switch can authorize direct/JNDI/legacy XA access to a managed schema. */
final class LegacyDatabaseIsolation {
    private LegacyDatabaseIsolation() { }
    static boolean allowed(String url) {
        return url != null && (url.matches("jdbc:h2:mem:[^;]+(?:;.*)?")
                || url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/finance_(?:atomic_)?validation_[a-z0-9_]+(?:[?].*)?"));
    }
    static void require(java.sql.Connection connection) throws java.sql.SQLException {
        String url=connection.getMetaData().getURL();require(url);
        if(url.startsWith("jdbc:mysql:")) {
            String configured=url.substring(url.indexOf('/',"jdbc:mysql://".length())+1).split("[?]",2)[0];
            if(!configured.equals(connection.getCatalog()))throw new IllegalStateException("Legacy database catalog does not match its isolated endpoint.");
        }
    }
    static void require(String url) {
        if (!allowed(url)) throw new IllegalStateException(
                "Direct and legacy database access is confined to isolated validation schemas; production requires the atomic cluster configuration.");
    }
}
