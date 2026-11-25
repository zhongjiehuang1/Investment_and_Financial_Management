package DatabaseService;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Read-only prerequisite, not a schema migration or proof of multi-primary safety. */
public final class ReplicationSchemaSafety {
    private ReplicationSchemaSafety() { }

    public static void verify(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        record Table(String catalog, String schema, String name) { }
        List<Table> tables = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (rows.next()) tables.add(new Table(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME")));
        }
        if (tables.isEmpty()) throw new SQLException("Replicated schema metadata is unavailable or empty.");
        // Close the table cursor before opening another metadata query. This also
        // works with drivers that allow only one active metadata result at a time.
        for (Table table : tables) {
            try (ResultSet keys = metadata.getImportedKeys(table.catalog(), table.schema(), table.name())) {
                while (keys.next()) {
                    if (cascades(keys.getShort("DELETE_RULE")) || cascades(keys.getShort("UPDATE_RULE")))
                        throw new SQLException("Replicated schemas require a separately verified migration of cascading foreign keys.");
                }
            }
        }
    }

    private static boolean cascades(short rule) {
        return rule == DatabaseMetaData.importedKeyCascade || rule == DatabaseMetaData.importedKeySetNull
                || rule == DatabaseMetaData.importedKeySetDefault;
    }
}
