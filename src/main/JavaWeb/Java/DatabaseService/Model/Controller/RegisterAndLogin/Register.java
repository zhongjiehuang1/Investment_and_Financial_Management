package DatabaseService.Model.Controller.RegisterAndLogin;

import DatabaseService.DatabaseInit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class Register extends DatabaseInit {
    public static boolean userRegister(String emailAddress, String password, String surname, String forename) {
        if (emailAddress == null || emailAddress.isBlank() || emailAddress.length() > 50 || password == null
                || password.isEmpty() || password.length() > 50 || surname == null || surname.isBlank()
                || surname.length() > 50 || forename == null || forename.isBlank() || forename.length() > 50) {
            throw new IllegalArgumentException("Registration fields do not fit the existing database schema.");
        }
        String email = emailAddress.trim().toLowerCase(Locale.ROOT);
        String encodedPassword = Model.Tools.Passwords.encode(password);
        try {
            try (Connection connection = openPrimaryConnection()) {
                try {
                    // All registrations hold these locks in canonical order through both inserts.
                    lockIdTables(connection, "USERS", "BASIC_VARIABLES_AND_DATA");
                    try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM USERS WHERE LOWER(EMAIL_ADDRESS) = ?")) {
                        statement.setString(1, email);
                        try (ResultSet result = statement.executeQuery()) {
                            if (result.next()) { rollback(connection); return false; }
                        }
                    }
                    int userId = nextId(connection, "USERS"), basicId = nextId(connection, "BASIC_VARIABLES_AND_DATA");
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO USERS (ID, FORENAME, SURNAME, EMAIL_ADDRESS, PASSWORD) VALUES (?, ?, ?, ?, ?)")) {
                        statement.setInt(1, userId); statement.setString(2, forename); statement.setString(3, surname);
                        statement.setString(4, email); statement.setString(5, encodedPassword);
                        if (statement.executeUpdate() != 1) throw new SQLException("User insert did not affect one row.");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO BASIC_VARIABLES_AND_DATA (ID, USER_ID) VALUES (?, ?)")) {
                        statement.setInt(1, basicId); statement.setInt(2, userId);
                        if (statement.executeUpdate() != 1) throw new SQLException("Basic variables insert did not affect one row.");
                    }
                    if (DatabaseService.ShardScope.isActive()) DatabaseService.ShardScope.current().ensureIdentityShadow(userId);
                    connection.commit(); return true;
                } catch (SQLException exception) { rollback(connection); return false; }
            }
        } catch (SQLException exception) { return false; }
    }
}
