package DatabaseService.Model.Controller;

import DatabaseService.DatabaseInit;
import Model.User;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

public final class SavedPeriodReports extends DatabaseInit {
    public static final String TABLE = "SAVED_PERIOD_REPORTS";
    private static final int MAX_REPORTS = 100;

    private SavedPeriodReports() {}

    public static void ensureSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "REPORT_ID CHAR(36) PRIMARY KEY, USER_ID INT NOT NULL, USER_GENERATION CHAR(36) NOT NULL, "
                    + "CURRENCY CHAR(3) NOT NULL, SCOPE_ID VARCHAR(64) NOT NULL, SCOPE_NAME VARCHAR(150) NOT NULL, "
                    + "START_DATE DATE NOT NULL, END_DATE DATE NOT NULL, REVIEWED_ON DATE NOT NULL, "
                    + "INPUT_JSON MEDIUMTEXT NOT NULL, RESULT_JSON MEDIUMTEXT NOT NULL, "
                    + "INDEX IDX_IAFM_PERIOD_OWNER(USER_ID,USER_GENERATION,CURRENCY,END_DATE), "
                    + "FOREIGN KEY (USER_ID) REFERENCES USERS(ID)) ENGINE=InnoDB");
        }
    }

    public static void verifySchema(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT REPORT_ID,INPUT_JSON,RESULT_JSON FROM " + TABLE + " WHERE 1=0")) {
            query.executeQuery().close();
        }
    }

    public static JSONObject library(User user, String currency, String requested) throws SQLException {
        JSONArray reports = new JSONArray();
        var library = new JSONObject().put("reports", reports);
        try (Connection connection = openPrimaryConnection()) {
            String selected = null;
            try (var query = connection.prepareStatement("SELECT REPORT_ID,SCOPE_ID,SCOPE_NAME,START_DATE,END_DATE,REVIEWED_ON "
                    + "FROM " + TABLE + " WHERE USER_ID=? AND USER_GENERATION=? AND CURRENCY=? "
                    + "ORDER BY END_DATE DESC,REVIEWED_ON DESC,REPORT_ID LIMIT " + MAX_REPORTS)) {
                query.setInt(1, user.getId()); query.setString(2, user.getGeneration()); query.setString(3, currency);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        if (selected == null || id.equals(requested)) selected = id;
                        reports.put(new JSONObject().put("id", id).put("scopeId", rows.getString(2))
                                .put("scope", rows.getString(3)).put("startDate", rows.getDate(4).toString())
                                .put("endDate", rows.getDate(5).toString()).put("reviewedOn", rows.getDate(6).toString()));
                    }
                }
            }
            if (selected != null) library.put("selectedId", selected)
                    .put("performance", document(connection, user, selected, false));
            connection.commit();
        }
        return library;
    }

    public static JSONObject input(User user, String id) throws SQLException {
        try (Connection connection = openPrimaryConnection()) {
            JSONObject input = document(connection, user, id, true);
            connection.commit();
            return input;
        }
    }

    private static JSONObject document(Connection connection, User user, String id, boolean input) throws SQLException {
        try (var query = connection.prepareStatement("SELECT " + (input ? "INPUT_JSON" : "RESULT_JSON") + " FROM "
                + TABLE + " WHERE USER_ID=? AND USER_GENERATION=? AND REPORT_ID=?")) {
            query.setInt(1, user.getId()); query.setString(2, user.getGeneration()); query.setString(3, reportId(id));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Saved report was not found.");
                return new JSONObject(rows.getString(1));
            }
        }
    }

    public static String save(User user, String requestedId, JSONObject input, JSONObject result) throws SQLException {
        boolean editing = !requestedId.isEmpty();
        String id = editing ? reportId(requestedId) : UUID.randomUUID().toString();
        try (Connection connection = openPrimaryConnection()) {
            try {
                lockUser(connection, user.getId());
                if (!editing) try (var count = connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE
                        + " WHERE USER_ID=? AND USER_GENERATION=?")) {
                    count.setInt(1, user.getId()); count.setString(2, user.getGeneration());
                    try (var rows = count.executeQuery()) {
                        if (!rows.next() || rows.getInt(1) >= MAX_REPORTS)
                            throw new IllegalArgumentException("The saved report limit has been reached.");
                    }
                }
                String fields = "CURRENCY=?,SCOPE_ID=?,SCOPE_NAME=?,START_DATE=?,END_DATE=?,REVIEWED_ON=?,INPUT_JSON=?,RESULT_JSON=?";
                String query = editing
                        ? "UPDATE " + TABLE + " SET " + fields + " WHERE REPORT_ID=? AND USER_ID=? AND USER_GENERATION=?"
                        : "INSERT INTO " + TABLE + " (CURRENCY,SCOPE_ID,SCOPE_NAME,START_DATE,END_DATE,REVIEWED_ON,INPUT_JSON,RESULT_JSON,REPORT_ID,USER_ID,USER_GENERATION) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
                try (var update = connection.prepareStatement(query)) {
                    update.setString(1, result.getString("currency"));
                    update.setString(2, input.getString("scopeId")); update.setString(3, result.getString("scope"));
                    update.setDate(4, java.sql.Date.valueOf(result.getString("startDate")));
                    update.setDate(5, java.sql.Date.valueOf(result.getString("endDate")));
                    update.setDate(6, java.sql.Date.valueOf(result.getString("reviewedOn")));
                    update.setString(7, input.toString()); update.setString(8, result.toString());
                    update.setString(9, id); update.setInt(10, user.getId()); update.setString(11, user.getGeneration());
                    if (update.executeUpdate() != 1 && !editing) throw new SQLException("Saved report insert failed.");
                }
                if (editing) document(connection, user, id, false);
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        }
    }

    public static void delete(User user, String id) throws SQLException {
        try (Connection connection = openPrimaryConnection()) {
            try {
                lockUser(connection, user.getId());
                try (var statement = connection.prepareStatement("DELETE FROM " + TABLE
                        + " WHERE USER_ID=? AND USER_GENERATION=? AND REPORT_ID=?")) {
                    statement.setInt(1, user.getId()); statement.setString(2, user.getGeneration());
                    statement.setString(3, reportId(id));
                    if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Saved report was not found.");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        }
    }

    private static String reportId(String value) {
        if (!DatabaseService.RequestIdentity.validGeneration(value)) throw new IllegalArgumentException("Invalid report ID.");
        return value;
    }
}
