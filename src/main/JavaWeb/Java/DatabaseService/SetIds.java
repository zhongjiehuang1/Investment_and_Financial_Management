package DatabaseService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reconciles every recyclable table before startup permits requests. */
class SetIds extends DatabaseInit {
    private static final Map<String, List<String>> REFERENCES = new ConcurrentHashMap<>();
    private static final int PAGE_SIZE = 512;
    private static final int SMALL_GAP = 128;
    private static final int MAX_RECOVERY_POINTS = 4096;

    void setAllIds() {
        try (Connection connection = openConnection()) {
            setAllIds(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("ID consistency checks failed; writes must remain unavailable.", failure);
        }
    }

    void setAllIds(Connection connection) throws SQLException {
        for (String table : ID_TABLES) {
            try {
                lockIdTables(connection, table);
                repair(connection, table);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        }
    }

    static void clearReferenceCache() { REFERENCES.clear(); }

    /** Include actual declared references and the legacy ledger's intentionally nullable record link. */
    private static List<String> referenceSelects(Connection connection, String table) throws SQLException {
        requireIdTable(table);
        String key = connection.getMetaData().getURL() + ":" + connection.getCatalog() + ":" + table;
        List<String> cached = REFERENCES.get(key);
        if (cached != null) return cached;
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        try (ResultSet keys = connection.getMetaData().getExportedKeys(connection.getCatalog(), null, table)) {
            while (keys.next()) {
                if (!"ID".equalsIgnoreCase(keys.getString("PKCOLUMN_NAME"))) continue;
                String child = identifier(keys.getString("FKTABLE_NAME"));
                String column = identifier(keys.getString("FKCOLUMN_NAME"));
                sources.add("SELECT `" + column + "` AS ID FROM `" + child + "` WHERE `" + column + "`>0");
            }
        }
        if ("INCOME_AND_EXPENDITURE_DATA".equals(table)) {
            sources.add("SELECT RECORD_ID AS ID FROM LEDGER_ENTRIES WHERE RECORD_ID>0");
        }
        cached = List.copyOf(sources);
        REFERENCES.put(key, cached);
        return cached;
    }

    private static String identifier(String value) throws SQLException {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) throw new SQLException("Unsupported referenced identifier.");
        return value;
    }

    private static String unavailableSelect(Connection connection, String table) throws SQLException {
        List<String> selects = new ArrayList<>();
        selects.add("SELECT ID FROM " + table + " WHERE ID>0");
        selects.addAll(referenceSelects(connection, table));
        return String.join(" UNION ", selects);
    }

    static boolean isUnavailable(Connection connection, String table, int id) throws SQLException {
        requireIdTable(table);
        List<String> selects = new ArrayList<>();
        selects.add("SELECT ID FROM " + table + " WHERE ID>0");
        selects.addAll(referenceSelects(connection, table));
        // Probe each indexed identity directly instead of materializing a union for every allocation.
        for (String select : selects) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ID FROM (" + select + ") OCCUPIED WHERE ID=? LIMIT 1")) {
                statement.setInt(1, id);
                try (ResultSet result = statement.executeQuery()) { if (result.next()) return true; }
            }
        }
        return false;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            result.next(); return result.getLong(1);
        }
    }

    private static void repair(Connection connection, String table) throws SQLException {
        String recorder = "ID_RECORDER_" + table;
        if (scalar(connection, "SELECT COUNT(*) FROM " + table + " WHERE ID<=0") != 0) {
            throw new SQLException("Nonpositive business IDs require explicit repair: " + table);
        }
        long highWater;
        try (PreparedStatement statement = connection.prepareStatement("SELECT HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { result.next(); highWater = result.getLong(1); }
        }
        highWater = Math.max(highWater, scalar(connection, "SELECT COALESCE(MAX(ID),0) FROM " + table));
        highWater = Math.max(highWater, scalar(connection, "SELECT COALESCE(MAX(TABLE_ID),0) FROM " + recorder + " WHERE TABLE_ID>0"));
        // MIN_VALUE was a lock marker, never a sequence. Other negative values retain tail-allocation evidence.
        highWater = Math.max(highWater, -scalar(connection, "SELECT COALESCE(MIN(TABLE_ID),0) FROM " + recorder
                + " WHERE TABLE_ID<0 AND TABLE_ID>" + Integer.MIN_VALUE));
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(LAST_ID),0) FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { result.next(); highWater = Math.max(highWater, result.getLong(1)); }
        }
        if (highWater < 0 || highWater > Integer.MAX_VALUE) throw new SQLException("Invalid historical ID high-water mark.");
        rememberHighWater(connection, table, (int) highWater);
        String occupied = unavailableSelect(connection, table);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + recorder
                + " WHERE TABLE_ID>0 AND TABLE_ID IN (SELECT ID FROM (" + occupied + ") OCCUPIED)")) {
            statement.executeUpdate();
        }
        // Range metadata is derived from preserved high-water evidence; explicit recycle records remain intact.
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM ID_RECOVERY_RANGES WHERE TABLE_NAME=?")) {
            statement.setString(1, table); statement.executeUpdate();
        }
        // Retain explicit recycle evidence, but never expand many small gaps into an enormous point pool.
        // Counting preserved points also keeps repeated startup checks from gradually expanding stored ranges.
        int pointBudget = (int) Math.max(0, MAX_RECOVERY_POINTS
                - scalar(connection, "SELECT COUNT(*) FROM " + recorder + " WHERE TABLE_ID>0"));
        String known = occupied + " UNION SELECT TABLE_ID AS ID FROM " + recorder + " WHERE TABLE_ID>0";
        long cursor = 0;
        while (cursor < highWater) {
            List<Integer> ids = new ArrayList<>(PAGE_SIZE);
            try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM (" + known
                    + ") KNOWN_IDS WHERE ID>? AND ID<=? ORDER BY ID LIMIT " + PAGE_SIZE)) {
                statement.setLong(1, cursor); statement.setLong(2, highWater);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(result.getInt(1)); }
            }
            if (ids.isEmpty()) { addGap(connection, table, cursor + 1, highWater, pointBudget); break; }
            for (int id : ids) {
                if ((long) id > cursor + 1) pointBudget = addGap(connection, table, cursor + 1, (long) id - 1, pointBudget);
                cursor = id;
            }
        }
        // The clear, transactional metadata above replaces both obsolete counters and lock markers.
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + recorder + " WHERE TABLE_ID<=0")) {
            statement.executeUpdate();
        }
    }

    private static int addGap(Connection connection, String table, long first, long last, int pointBudget) throws SQLException {
        if (first > last) return pointBudget;
        long size = last - first + 1;
        if (size <= SMALL_GAP && size <= pointBudget) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ID_RECORDER_" + table
                    + " (TABLE_ID) VALUES (?) ON DUPLICATE KEY UPDATE TABLE_ID=TABLE_ID")) {
                for (long id = first; id <= last; id++) { statement.setInt(1, (int) id); statement.addBatch(); }
                statement.executeBatch();
            }
            pointBudget -= (int) size;
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ID_RECOVERY_RANGES (TABLE_NAME,FIRST_ID,LAST_ID) VALUES (?,?,?)")) {
                statement.setString(1, table); statement.setInt(2, (int) first); statement.setInt(3, (int) last); statement.executeUpdate();
            }
        }
        return pointBudget;
    }
}
