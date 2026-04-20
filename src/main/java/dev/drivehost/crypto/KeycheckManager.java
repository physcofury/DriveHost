package dev.drivehost.crypto;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Manages keycheck.json creation and verification.
 * keycheck.json is a plaintext file on Drive containing only:
 *   - salt (base64)
 *   - hmac (base64) — HMAC of a known challenge string
 * This allows password verification without storing the password or key.
 */
public final class KeycheckManager {

    public static final String CHALLENGE = "drivehost-keycheck-v1";

    private KeycheckManager() {}

    /**
     * Create a new keycheck from a password.
     * Generates a fresh salt, derives the key, and computes the HMAC.
     */
    public static KeycheckData createKeycheck(String password) throws Exception {
        byte[] salt = CryptoManager.generateSalt();
        SecretKey key = CryptoManager.deriveKey(password, salt);
        byte[] hmac = CryptoManager.computeHMAC(key, CHALLENGE.getBytes(StandardCharsets.UTF_8));

        return new KeycheckData(
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hmac),
            CHALLENGE
        );
    }

    /**
     * Verify a password against existing keycheck data.
     * Returns the derived SecretKey if the password is correct, null otherwise.
     */
    public static SecretKey verifyPassword(String password, KeycheckData keycheck) throws Exception {
        byte[] salt = Base64.getDecoder().decode(keycheck.salt());
        SecretKey key = CryptoManager.deriveKey(password, salt);
        byte[] expectedHmac = CryptoManager.computeHMAC(key, keycheck.challenge().getBytes(StandardCharsets.UTF_8));
        byte[] actualHmac = Base64.getDecoder().decode(keycheck.hmac());

        if (constantTimeEquals(expectedHmac, actualHmac)) {
            return key;
        }
        return null;
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Keycheck data record — serialized as keycheck.json (plaintext) on Drive.
     */
    public record KeycheckData(String salt, String hmac, String challenge) {}
}
