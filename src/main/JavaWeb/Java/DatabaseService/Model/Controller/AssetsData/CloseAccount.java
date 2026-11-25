package DatabaseService.Model.Controller.AssetsData;

import DatabaseService.DatabaseInit;
import DatabaseService.ShardScope;

import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;

public final class CloseAccount extends DatabaseInit {
    private CloseAccount() {}

    public static boolean close(int userId, int id, boolean investment, LocalDate day) throws SQLException {
        if (day.isAfter(LocalDate.now(ZoneOffset.UTC))) throw new IllegalArgumentException("Closing date cannot be in the future.");
        String table = investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS";
        String name = investment ? "INVESTMENT_PLATFORM" : "NAME";
        try (Connection connection = openConnection()) {
            try {
                lockUser(connection, userId);
                String platform;
                try (var query = connection.prepareStatement("SELECT " + name + ",DATE,CLOSED_ON FROM " + table
                        + " WHERE ID=? AND USER_ID=? FOR UPDATE")) {
                    query.setInt(1, id);
                    query.setInt(2, userId);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Account not found.");
                        Date closed = rows.getDate(3);
                        if (closed != null) {
                            if (!closed.toLocalDate().equals(day)) throw new IllegalArgumentException("The account is already archived.");
                            connection.commit();
                            return true;
                        }
                        if (day.isBefore(rows.getDate(2).toLocalDate()))
                            throw new IllegalArgumentException("Closing date precedes the opening date.");
                        platform = rows.getString(1);
                    }
                }
                CommonFunctions.available(connection, userId, platform, table, id);
                for (Connection participant : ShardScope.connections(connection)) {
                    try (var query = participant.prepareStatement("SELECT ID FROM INCOME_AND_EXPENDITURE_DATA "
                            + "WHERE USER_ID=? AND PLATFORM=? AND DATE>? LIMIT 1")) {
                        query.setInt(1, userId);
                        query.setString(2, platform);
                        query.setDate(3, Date.valueOf(day));
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) throw new IllegalArgumentException("Transactions exist after the closing date.");
                        }
                    }
                }
                try (var update = connection.prepareStatement("UPDATE " + table
                        + " SET CLOSED_ON=? WHERE ID=? AND USER_ID=? AND CLOSED_ON IS NULL")) {
                    update.setDate(1, Date.valueOf(day));
                    update.setInt(2, id);
                    update.setInt(3, userId);
                    if (update.executeUpdate() != 1) throw new SQLException("Account closure lost its target.");
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        }
    }
}
