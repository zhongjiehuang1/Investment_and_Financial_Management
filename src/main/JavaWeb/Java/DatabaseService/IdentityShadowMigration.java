package DatabaseService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Maintenance-only, one-way identity metadata seeding. No financial records or password hashes are copied. */
public final class IdentityShadowMigration {
    private record Identity(int id, String generation, String forename, String surname, String email) { }
    private IdentityShadowMigration() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--writers-stopped".equals(args[1]))
            throw new IllegalArgumentException("Supply a private cluster properties file and --writers-stopped after stopping both writers.");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { properties.load(reader); }
        try (Connection canonical = connect(properties, 1); Connection shadow = connect(properties, 2)) {
            canonical.setAutoCommit(false); shadow.setAutoCommit(false);
            try {
                Map<Integer, Identity> identities = new LinkedHashMap<>();
                Set<String> generations = new HashSet<>();
                try (Statement statement = canonical.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT ID,GENERATION,FORENAME,SURNAME,EMAIL_ADDRESS FROM USERS ORDER BY ID FOR UPDATE")) {
                    while (rows.next()) {
                        Identity identity = new Identity(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5));
                        if (identity.id <= 0 || !RequestIdentity.validGeneration(identity.generation)
                                || !generations.add(identity.generation.toLowerCase(Locale.ROOT))) throw new SQLException("Invalid canonical identity.");
                        identities.put(identity.id, identity);
                    }
                }
                Set<Integer> existing = new HashSet<>();
                try (Statement statement = shadow.createStatement(); ResultSet rows = statement.executeQuery("SELECT ID,GENERATION FROM USERS ORDER BY ID FOR UPDATE")) {
                    while (rows.next()) {
                        Identity identity = identities.get(rows.getInt(1));
                        if (identity == null || !identity.generation.equalsIgnoreCase(rows.getString(2)))
                            throw new SQLException("A destination identity conflicts with canonical data; manual reconciliation is required.");
                        existing.add(identity.id);
                    }
                }
                for (Identity identity : identities.values()) {
                    String sql = existing.contains(identity.id)
                            ? "UPDATE USERS SET GENERATION=?,FORENAME=?,SURNAME=?,EMAIL_ADDRESS=?,PASSWORD=? WHERE ID=?"
                            : "INSERT INTO USERS(GENERATION,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD,ID) VALUES(?,?,?,?,?,?)";
                    try (PreparedStatement statement = shadow.prepareStatement(sql)) {
                        statement.setString(1, identity.generation); statement.setString(2, identity.forename);
                        statement.setString(3, identity.surname); statement.setString(4, identity.email);
                        statement.setString(5, "!identity-shadow!"); statement.setInt(6, identity.id);
                        if (statement.executeUpdate() != 1) throw new SQLException("Identity shadow was not written.");
                    }
                }
                shadow.commit(); canonical.rollback();
                System.out.println("Verified identity shadows: " + identities.size() + "; financial records copied: 0; password hashes copied: 0.");
            } catch (Exception failure) {
                try { shadow.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                try { canonical.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    private static Connection connect(Properties properties, int node) throws SQLException {
        String prefix = "node" + node + ".";
        String url = properties.getProperty(prefix + "url"), user = properties.getProperty(prefix + "user"), password = properties.getProperty(prefix + "password");
        if (url == null || !url.startsWith("jdbc:mysql:") || user == null || password == null)
            throw new IllegalArgumentException("The private properties file must define both MySQL nodes.");
        return DriverManager.getConnection(url, user, password);
    }
}
