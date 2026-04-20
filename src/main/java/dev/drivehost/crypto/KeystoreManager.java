package dev.drivehost.crypto;

import dev.drivehost.DriveHostMod;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Caches derived AES keys locally so users don't re-enter their password every session.
 * Uses a PKCS12 keystore file protected by a machine-derived password.
 */
public final class KeystoreManager {

    private static final String KEYSTORE_FILE = "keystore.p12";
    private static final String KEYSTORE_TYPE = "PKCS12";

    private KeystoreManager() {}

    private static Path getKeystorePath() {
        return DriveHostMod.getConfigDir().resolve(KEYSTORE_FILE);
    }

    /**
     * Machine-derived password for the keystore.
     * Not truly secret — it's defense-in-depth, not the primary security layer.
     * The real security comes from the world password + PBKDF2.
     */
    private static char[] getKeystorePassword() {
        String user = System.getProperty("user.name", "drivehost");
        String home = System.getProperty("user.home", "/tmp");
        return ("dh-ks-" + user + "-" + home.hashCode()).toCharArray();
    }

    /**
     * Store a derived key for a world (identified by Drive folder ID).
     */
    public static void storeKey(String worldId, SecretKey key) throws Exception {
        KeyStore ks = loadOrCreateKeystore();
        KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry(
            new SecretKeySpec(key.getEncoded(), "AES")
        );
        ks.setEntry(worldId, entry,
            new KeyStore.PasswordProtection(getKeystorePassword()));
        saveKeystore(ks);
    }

    /**
     * Load a cached key for a world. Returns null if not found.
     */
    public static SecretKey loadKey(String worldId) throws Exception {
        KeyStore ks = loadOrCreateKeystore();
        if (!ks.containsAlias(worldId)) {
            return null;
        }
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(
            worldId, new KeyStore.PasswordProtection(getKeystorePassword())
        );
        if (entry == null) return null;
        return entry.getSecretKey();
    }

    private static KeyStore loadOrCreateKeystore() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        Path path = getKeystorePath();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                ks.load(in, getKeystorePassword());
            }
        } else {
            ks.load(null, getKeystorePassword());
        }
        return ks;
    }

    private static void saveKeystore(KeyStore ks) throws Exception {
        Path path = getKeystorePath();
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, getKeystorePassword());
        }
    }
}
