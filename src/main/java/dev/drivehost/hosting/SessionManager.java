package dev.drivehost.hosting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.drivehost.crypto.CryptoManager;
import dev.drivehost.drive.DriveService;
import dev.drivehost.DriveHostMod;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * CRUD operations for session.json.enc on Google Drive.
 * Handles encryption/decryption and etag-based optimistic concurrency.
 */
public class SessionManager {

    private static final String SESSION_FILE = "session.json.enc";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final DriveService drive;
    private final String folderId;
    private final SecretKey key;

    private String lastEtag;

    public SessionManager(DriveService drive, String folderId, SecretKey key) {
        this.drive = drive;
        this.folderId = folderId;
        this.key = key;
    }

    /**
     * Read the current session from Drive.
     * Returns null if session.json.enc doesn't exist yet.
     */
    public SessionData readSession() throws Exception {
        byte[] encrypted = drive.downloadFile(folderId, SESSION_FILE);
        if (encrypted == null) return null;

        lastEtag = drive.getFileEtag(folderId, SESSION_FILE);

        byte[] json = CryptoManager.decrypt(encrypted, key);
        return GSON.fromJson(new String(json, StandardCharsets.UTF_8), SessionData.class);
    }

    /**
     * Write session data to Drive (encrypted).
     * Uses etag check for optimistic concurrency — retries once on conflict.
     */
    public void writeSession(SessionData data) throws Exception {
        byte[] json = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoManager.encrypt(json, key);

        if (lastEtag != null) {
            boolean ok = drive.uploadIfUnchanged(folderId, SESSION_FILE, encrypted, lastEtag);
            if (!ok) {
                // Conflict — re-read and try again
                DriveHostMod.LOGGER.warn("Session write conflict — re-reading and retrying");
                readSession(); // refresh etag
                encrypted = CryptoManager.encrypt(json, key);
                drive.uploadFile(folderId, SESSION_FILE, encrypted);
            }
        } else {
            drive.uploadFile(folderId, SESSION_FILE, encrypted);
        }

        lastEtag = drive.getFileEtag(folderId, SESSION_FILE);
    }

    /**
     * Create a brand new session for a newly created world.
     */
    public void createInitialSession(String ownerName) throws Exception {
        SessionData data = new SessionData();
        data.setOwner(ownerName);
        data.setWorldVersion(1);
        data.updateHeartbeat();
        writeSession(data);
    }
}
