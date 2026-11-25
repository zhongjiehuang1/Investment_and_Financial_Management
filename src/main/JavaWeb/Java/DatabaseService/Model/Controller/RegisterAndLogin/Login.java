package DatabaseService.Model.Controller.RegisterAndLogin;

import DatabaseService.DatabaseInit;
import Model.Tools.Passwords;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class Login extends DatabaseInit {
    public static boolean userLogin(Model.User user, String emailAddress, String password) {
        if (emailAddress == null || password == null || password.length() > 1024) return false;
        String email = emailAddress.trim().toLowerCase(Locale.ROOT);
        try (Connection connection = openPrimaryConnection()) {
            try {
                int id; String forename, surname, stored, generation;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ID, FORENAME, SURNAME, PASSWORD, GENERATION FROM USERS WHERE LOWER(EMAIL_ADDRESS) = ? LIMIT 2")) {
                    statement.setString(1, email);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return Passwords.verify(password, null);
                        id = result.getInt("ID"); forename = result.getString("FORENAME");
                        surname = result.getString("SURNAME"); stored = result.getString("PASSWORD");
                        generation = result.getString("GENERATION");
                        if (result.next()) return Passwords.verify(password, null); // Ambiguous legacy accounts require correction.
                    }
                }
                // Password work runs on an unlocked snapshot; only the selected primary-key row is pinned afterward.
                if (!Passwords.verify(password, stored)) return false;
                String encoded = Passwords.isEncoded(stored) ? null : Passwords.encode(password);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT GENERATION, PASSWORD, EMAIL_ADDRESS, FORENAME, SURNAME FROM USERS WHERE ID = ? FOR UPDATE")) {
                    statement.setInt(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !generation.equals(result.getString("GENERATION"))
                                || !stored.equals(result.getString("PASSWORD"))
                                || !email.equals(result.getString("EMAIL_ADDRESS").trim().toLowerCase(Locale.ROOT))) {
                            rollback(connection); return false;
                        }
                        forename = result.getString("FORENAME"); surname = result.getString("SURNAME");
                    }
                }
                if (encoded != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE USERS SET PASSWORD = ? WHERE ID = ? AND GENERATION = ? AND PASSWORD = ?")) {
                        statement.setString(1, encoded); statement.setInt(2, id);
                        statement.setString(3, generation); statement.setString(4, stored);
                        if (statement.executeUpdate() != 1) { rollback(connection); return false; }
                    }
                }
                connection.commit();
                user.setId(id); user.setGeneration(generation); user.setForename(forename); user.setSurname(surname); user.setEmailAddress(email);
                return true;
            } catch (SQLException exception) { rollback(connection); return false; }
        } catch (SQLException exception) { return false; }
    }
}
