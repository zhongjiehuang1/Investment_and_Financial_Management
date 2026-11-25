package Model.Tools;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Version p1: PBKDF2-HMAC-SHA256, 600000 iterations, 128-bit salt and derived key.
 *  The 46-character encoding fits the unchanged PASSWORD VARCHAR(50) column. */
public final class Passwords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DUMMY_SALT = new byte[16];
    private Passwords() { }

    public static boolean isEncoded(String stored) {
        return stored != null && stored.matches("p1\\$[A-Za-z0-9_-]{43}");
    }

    public static String encode(String password) {
        if (password == null || password.isEmpty() || password.length() > 1024)
            throw new IllegalArgumentException("Invalid password length");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] value = Arrays.copyOf(salt, 32);
        System.arraycopy(derive(password, salt), 0, value, 16, 16);
        return "p1$" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || password.length() > 1024) return false;
        if (stored == null) {
            // Unknown or ambiguous accounts consume the same KDF work as an encoded account.
            // A fixed salt avoids generating a fresh dummy hash on every rejected request.
            derive(password, DUMMY_SALT);
            return false;
        }
        if (isEncoded(stored)) {
            byte[] value = Base64.getUrlDecoder().decode(stored.substring(3));
            return MessageDigest.isEqual(derive(password, Arrays.copyOf(value, 16)), Arrays.copyOfRange(value, 16, 32));
        }
        // Legacy plaintext is accepted only for migration after successful authentication.
        // Rejected legacy logins must not expose account existence through a cheap comparison.
        derive(password, DUMMY_SALT);
        return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 600000, 128);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally { spec.clearPassword(); }
    }
}
