package DatabaseService;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** One explicitly pinned durable MySQL; this never claims another replica confirmed. */
final class SingleDatabaseAuthority implements AtomicFinanceTransactions.Authority {
    private final AtomicFinanceTransactions.Proof proof;

    SingleDatabaseAuthority(Properties config) {
        String raw=config.getProperty("atomic.mysqlUuid", "");
        String uuid;
        try { uuid=UUID.fromString(raw).toString(); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("A canonical single MySQL UUID is required"); }
        if (!uuid.equals(raw.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("A canonical single MySQL UUID is required");
        if (!"local-or-verified-ssh-tunnel".equals(config.getProperty("atomic.single.transport")))
            throw new IllegalArgumentException("Explicit local or verified SSH tunnel transport is required");
        proof=new AtomicFinanceTransactions.Proof(uuid,"single-database","0","single","single-database","single-database","single-database");
    }
    @Override public AtomicFinanceTransactions.Proof capture() { return proof; }
    @Override public void verify(AtomicFinanceTransactions.Proof value) throws SQLException {
        if (!proof.equals(value)) throw new SQLException("Single database identity changed");
    }
    static void verifyDatabase(Connection connection,String expected) throws SQLException {
        try(Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(
                "SELECT @@server_uuid,@@session.sql_log_bin,@@global.log_bin,@@global.read_only,@@global.super_read_only")) {
            if(!rows.next() || !expected.equalsIgnoreCase(rows.getString(1)) || rows.getInt(2)!=1 || rows.getInt(3)!=1
                    || rows.getInt(4)!=0 || rows.getInt(5)!=0 || rows.next())
                throw new SQLException("Single database identity, binary logging or writable state is invalid");
        }
        try(Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            if(!rows.next() || rows.getString(2)==null || rows.getString(2).isBlank() || rows.next())
                throw new SQLException("Single database connection is not encrypted");
        }
        AtomicFinanceTransactions.verifyDurability(connection);
    }
    @Override public String confirm(Connection database,AtomicFinanceTransactions.Proof value,String operation) throws SQLException {
        verify(value);
        verifyDatabase(database,proof.mysqlUuid());
        return "single";
    }
}
