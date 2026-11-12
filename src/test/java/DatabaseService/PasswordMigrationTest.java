package DatabaseService;

import Model.User;
import Model.Tools.Passwords;
import DatabaseService.Model.Controller.RegisterAndLogin.Login;
import DatabaseService.Model.Controller.RegisterAndLogin.Register;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class PasswordMigrationTest extends DatabaseInit {
    private final Map<String,String> before = new HashMap<>();
    @Before public void setup() throws Exception {
        setting("iafm.db.url", "jdbc:h2:mem:password_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        setting("iafm.db.user", "sa"); setting("iafm.db.password", ""); setting("iafm.db.jndi", "");
        new CreateAllTables().createAllTables();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO USERS (ID, FORENAME, SURNAME, EMAIL_ADDRESS, PASSWORD) VALUES (1, 'Test', 'Owner', 'test@example.invalid', 'legacy-test-only')")) {
            statement.executeUpdate(); connection.commit();
        }
    }
    private void setting(String key, String value) { before.put(key, System.getProperty(key)); System.setProperty(key, value); }
    @After public void restore() {
        before.forEach((key,value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key,value); });
    }

    @Test public void hashesAreSaltedAndFitTheExistingColumn() {
        String first = Passwords.encode("test password with spaces"), second = Passwords.encode("test password with spaces");
        assertEquals(46, first.length()); assertNotEquals(first, second);
        assertTrue(Passwords.verify("test password with spaces", first));
        assertFalse(Passwords.verify("test password with spaces!", first));
    }

    @Test public void successfulLegacyLoginUpgradesTheSameRowOnlyAfterVerification() throws Exception {
        assertFalse(Login.userLogin(new User(), "test@example.invalid", "wrong"));
        assertEquals("legacy-test-only", stored("test@example.invalid"));
        User owner = new User();
        assertTrue(Login.userLogin(owner, "TEST@example.invalid", "legacy-test-only"));
        String hashed = stored("test@example.invalid");
        assertTrue(Passwords.isEncoded(hashed)); assertNotEquals("legacy-test-only", hashed);
        assertEquals(1, owner.getId());
        assertTrue(Login.userLogin(new User(), "test@example.invalid", "legacy-test-only"));
        assertEquals(hashed, stored("test@example.invalid"));
        assertFalse(DatabaseService.Model.Controller.ReadData.profile(1).toString().contains("PASSWORD"));
    }

    @Test public void newRegistrationsNeverStorePlaintext() throws Exception {
        assertTrue(Register.userRegister("new@example.invalid", "new-test-only", "Owner", "New"));
        String hashed = stored("new@example.invalid");
        assertTrue(Passwords.isEncoded(hashed)); assertNotEquals("new-test-only", hashed);
        assertTrue(Login.userLogin(new User(), "new@example.invalid", "new-test-only"));
    }

    @Test public void unknownAndAmbiguousAccountsPerformOneFullDummyVerification() throws Exception {
        String encoded = Passwords.encode("known-test-only");
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE USERS SET PASSWORD = ? WHERE ID = 1")) {
            statement.setString(1, encoded); statement.executeUpdate(); connection.commit();
        }
        CountingKdf.delegateProvider = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getProvider();
        java.security.Provider observer = new java.security.Provider("IAFM-Test-Kdf-Observer", "1.0", "Test-only KDF observer") { };
        observer.put("SecretKeyFactory.PBKDF2WithHmacSHA256", CountingKdf.class.getName());
        java.security.Security.insertProviderAt(observer, 1);
        try {
            CountingKdf.calls = 0;
            assertFalse(Login.userLogin(new User(), "missing@example.invalid", "wrong"));
            assertEquals(1, CountingKdf.calls); assertEquals(600000, CountingKdf.iterations);
            byte[] dummySalt = CountingKdf.salt.clone();
            CountingKdf.calls = 0;
            assertFalse(Login.userLogin(new User(), "test@example.invalid", "wrong"));
            assertEquals(1, CountingKdf.calls); assertEquals(600000, CountingKdf.iterations);
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO USERS (ID, FORENAME, SURNAME, EMAIL_ADDRESS, PASSWORD) VALUES (3, 'Legacy', 'Owner', 'legacy@example.invalid', 'legacy-test-only')")) {
                statement.executeUpdate(); connection.commit();
            }
            CountingKdf.calls = 0;
            assertFalse(Login.userLogin(new User(), "legacy@example.invalid", "wrong"));
            assertEquals(1, CountingKdf.calls); assertEquals(600000, CountingKdf.iterations);
            assertArrayEquals(dummySalt, CountingKdf.salt);
            assertEquals("legacy-test-only", stored("legacy@example.invalid"));
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO USERS (ID, FORENAME, SURNAME, EMAIL_ADDRESS, PASSWORD) VALUES (2, 'Other', 'Owner', 'TEST@example.invalid', 'legacy-test-only')")) {
                statement.executeUpdate(); connection.commit();
            }
            CountingKdf.calls = 0;
            assertFalse(Login.userLogin(new User(), "test@example.invalid", "known-test-only"));
            assertEquals(1, CountingKdf.calls); assertEquals(600000, CountingKdf.iterations);
            assertArrayEquals(dummySalt, CountingKdf.salt);
            assertEquals(encoded, stored("test@example.invalid"));
        } finally { java.security.Security.removeProvider(observer.getName()); }
    }

    /** Observe the actual JCA work without unreliable wall-clock timing assertions. */
    public static final class CountingKdf extends javax.crypto.SecretKeyFactorySpi {
        static java.security.Provider delegateProvider;
        static int calls, iterations;
        static byte[] salt;
        @Override protected javax.crypto.SecretKey engineGenerateSecret(java.security.spec.KeySpec input)
                throws java.security.spec.InvalidKeySpecException {
            javax.crypto.spec.PBEKeySpec spec = (javax.crypto.spec.PBEKeySpec) input;
            calls++; iterations = spec.getIterationCount(); salt = spec.getSalt();
            try { return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", delegateProvider).generateSecret(input); }
            catch (java.security.NoSuchAlgorithmException unavailable) { throw new java.security.spec.InvalidKeySpecException(unavailable); }
        }
        @Override protected java.security.spec.KeySpec engineGetKeySpec(javax.crypto.SecretKey key, Class<?> type)
                throws java.security.spec.InvalidKeySpecException { throw new java.security.spec.InvalidKeySpecException("Not used"); }
        @Override protected javax.crypto.SecretKey engineTranslateKey(javax.crypto.SecretKey key)
                throws java.security.InvalidKeyException { throw new java.security.InvalidKeyException("Not used"); }
    }

    private String stored(String email) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT PASSWORD FROM USERS WHERE EMAIL_ADDRESS = ?")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }
}
