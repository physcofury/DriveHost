package dev.drivehost.hosting;

import com.google.gson.Gson;
import dev.drivehost.DriveHostMod;
import dev.drivehost.crypto.CryptoManager;
import dev.drivehost.crypto.KeycheckManager;
import dev.drivehost.crypto.KeycheckManager.KeycheckData;
import dev.drivehost.crypto.KeystoreManager;
import dev.drivehost.drive.DriveService;
import dev.drivehost.tunnel.TunnelManager;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manages joining a DriveHost world: password verification, connecting or becoming host.
 * Also handles failover monitoring.
 */
public class JoinController {

    private static final long POLL_INTERVAL_MS = 10_000;
    private static final int HOST_STALE_THRESHOLD_SECONDS = 15;
    private static final Gson GSON = new Gson();

    private final DriveService drive;
    private final TunnelManager tunnelManager;
    private final String playerName;

    private String folderId;
    private SecretKey key;
    private SessionManager sessionManager;
    private WorldManager worldManager;
    private HostController hostController;

    private ScheduledExecutorService poller;
    private volatile boolean active = false;
    private volatile SessionData lastSession;

    public JoinController(DriveService drive, TunnelManager tunnelManager, String playerName) {
        this.drive = drive;
        this.tunnelManager = tunnelManager;
        this.playerName = playerName;
    }

    /**
     * Join a world by folder ID and password.
     * Returns the server address to connect to (host's tunnel address, or localhost if we became host).
     */
    public JoinResult joinWorld(String folderId, String password) throws Exception {
        this.folderId = folderId;

        // 1. Verify password
        DriveHostMod.LOGGER.info("Verifying password for folder {}...", folderId);
        key = verifyPassword(folderId, password);
        if (key == null) {
            return JoinResult.wrongPassword();
        }

        // Cache the key locally
        KeystoreManager.storeKey(folderId, key);

        // 2. Set up managers
        sessionManager = new SessionManager(drive, folderId, key);
        worldManager = new WorldManager(drive, folderId, key);

        // 3. Read session
        SessionData session = sessionManager.readSession();

        if (session != null && session.isHostAlive()) {
            // Host is alive — connect to them
            DriveHostMod.LOGGER.info("Host '{}' is alive at {}",
                session.getHostPlayerName(), session.getHostTunnelAddress());

            // Add ourselves to active players
            if (!session.getActivePlayers().contains(playerName)) {
                session.getActivePlayers().add(playerName);
                sessionManager.writeSession(session);
            }

            lastSession = session;
            startPolling();
            return JoinResult.connectTo(session.getHostTunnelAddress());
        } else {
            // No host — we become the host
            DriveHostMod.LOGGER.info("No active host — becoming host");
            return becomeHostInternal();
        }
    }

    /**
     * Create a new world: set up keycheck, session, and open tunnel.
     * No world is downloaded — the caller should use createFreshLevel() to generate
     * the world locally. DriveHostMod.pendingUploadController is set so the
     * SERVER_STARTED event can upload the world to Drive automatically.
     */
    public JoinResult createWorld(String folderId, String password) throws Exception {
        this.folderId = folderId;

        // 1. Create keycheck
        DriveHostMod.LOGGER.info("Creating new world in folder {}...", folderId);
        KeycheckData keycheck = KeycheckManager.createKeycheck(password);
        byte[] keycheckJson = GSON.toJson(keycheck).getBytes(StandardCharsets.UTF_8);
        drive.uploadFile(folderId, "keycheck.json", keycheckJson);

        // 2. Derive key and cache it
        key = KeycheckManager.verifyPassword(password, keycheck);
        KeystoreManager.storeKey(folderId, key);

        // 3. Set up managers
        sessionManager = new SessionManager(drive, folderId, key);
        worldManager = new WorldManager(drive, folderId, key);

        // 4. Create initial session
        sessionManager.createInitialSession(playerName);

        // 5. Open tunnel + write session with address, then caller uses createFreshLevel()
        return becomeHostFresh();
    }

    /**
     * Become host for a fresh new world — opens tunnel and writes session without
     * downloading any world. The caller is responsible for generating the world via
     * createFreshLevel(), and should set DriveHostMod.pendingUploadController = this
     * so the initial world is uploaded once the server starts.
     */
    public JoinResult becomeHostFresh() throws Exception {
        hostController = new HostController(tunnelManager, sessionManager, worldManager, playerName);
        boolean success = hostController.becomeHost();
        if (!success) return JoinResult.tunnelFailed();
        lastSession = sessionManager.readSession();
        active = true;
        startPolling();
        return JoinResult.hosting("FRESH");
    }

    /**
     * Upload the given world directory to Drive (called after createFreshLevel starts).
     */
    public void uploadWorldAfterCreate(java.nio.file.Path worldDir) {
        try {
            worldManager.setCurrentWorldDir(worldDir);
            worldManager.encryptAndUploadWorld(worldDir);
            DriveHostMod.LOGGER.info("Initial world uploaded to Drive from {}", worldDir);
        } catch (Exception e) {
            DriveHostMod.LOGGER.error("Failed to upload initial world to Drive", e);
        }
    }

    /**
     * After createWorld and world generation, upload the world and start hosting.
     * @deprecated Use becomeHostFresh() + createFreshLevel() flow instead.
     */
    @Deprecated
    public JoinResult uploadWorldAndHost(java.nio.file.Path worldDir) throws Exception {
        worldManager.uploadInitialWorld(worldDir);
        return becomeHostInternal();
    }

    /**
     * Disconnect from the world.
     */
    public void disconnect() {
        active = false;
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        if (hostController != null) {
            hostController.stopHosting();
            hostController = null;
        }
        if (worldManager != null) {
            worldManager.cleanupTempFiles();
        }

        // Remove from active players
        if (sessionManager != null) {
            try {
                SessionData session = sessionManager.readSession();
                if (session != null) {
                    session.getActivePlayers().remove(playerName);
                    sessionManager.writeSession(session);
                }
            } catch (Exception e) {
                DriveHostMod.LOGGER.warn("Failed to remove from active players on disconnect", e);
            }
        }

        DriveHostMod.LOGGER.info("Disconnected from DriveHost world");
    }

    public boolean isActive() {
        return active;
    }

    public boolean isHosting() {
        return hostController != null && hostController.isHosting();
    }

    public SessionData getLastSession() {
        return lastSession;
    }

    public HostController getHostController() {
        return hostController;
    }

    /** Update the world directory used for autosaves (set to the MC saves folder after world is loaded). */
    public void setHostWorldDir(java.nio.file.Path dir) {
        if (worldManager != null) worldManager.setCurrentWorldDir(dir);
    }

    // --- Internal ---

    private SecretKey verifyPassword(String folderId, String password) throws Exception {
        // First check if we have a cached key
        SecretKey cached = KeystoreManager.loadKey(folderId);
        if (cached != null) {
            // Verify it still works
            byte[] keycheckBytes = drive.downloadFile(folderId, "keycheck.json");
            if (keycheckBytes != null) {
                KeycheckData keycheck = GSON.fromJson(
                    new String(keycheckBytes, StandardCharsets.UTF_8), KeycheckData.class);
                SecretKey verified = KeycheckManager.verifyPassword(password, keycheck);
                if (verified != null) return verified;
            }
        }

        // No cache — verify from Drive
        byte[] keycheckBytes = drive.downloadFile(folderId, "keycheck.json");
        if (keycheckBytes == null) {
            throw new IllegalStateException("No keycheck.json found in Drive folder — is this a DriveHost world?");
        }
        KeycheckData keycheck = GSON.fromJson(
            new String(keycheckBytes, StandardCharsets.UTF_8), KeycheckData.class);
        return KeycheckManager.verifyPassword(password, keycheck);
    }

    private JoinResult becomeHostInternal() throws Exception {
        // Download and decrypt world
        java.nio.file.Path worldDir = worldManager.downloadAndDecryptWorld();

        // Become host
        hostController = new HostController(tunnelManager, sessionManager, worldManager, playerName);
        boolean success = hostController.becomeHost();
        if (!success) {
            worldManager.cleanupTempFiles();
            return JoinResult.tunnelFailed();
        }

        lastSession = sessionManager.readSession();
        active = true;
        startPolling();
        return JoinResult.hosting(worldDir.toString());
    }

    private void startPolling() {
        active = true;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DriveHost-Poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(this::pollSession, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollSession() {
        if (!active) return;
        try {
            SessionData session = sessionManager.readSession();
            if (session == null) return;
            lastSession = session;

            // If we're not the host, check if host is alive
            if (!isHosting() && !session.isHostAlive(HOST_STALE_THRESHOLD_SECONDS)) {
                DriveHostMod.LOGGER.warn("Host '{}' appears stale — considering failover",
                    session.getHostPlayerName());
                attemptFailover(session);
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("Session poll failed", e);
        }
    }

    private void attemptFailover(SessionData session) {
        // Simple election: alphabetically first active player becomes host
        List<String> players = session.getActivePlayers();
        if (players.isEmpty()) return;

        Collections.sort(players);
        String elected = players.get(0);

        if (elected.equals(playerName)) {
            DriveHostMod.LOGGER.info("Elected as new host — initiating failover");
            try {
                becomeHostInternal();
            } catch (Exception e) {
                DriveHostMod.LOGGER.error("Failover failed", e);
            }
        } else {
            DriveHostMod.LOGGER.info("'{}' elected as new host — waiting for reconnect", elected);
        }
    }

    // --- Result types ---

    public record JoinResult(Type type, String address) {
        public enum Type {
            CONNECT,         // Connect to an existing host
            HOSTING,         // We became the host
            WRONG_PASSWORD,
            TUNNEL_FAILED,
            NEEDS_WORLD_SETUP // World creation flow — needs world generation before hosting
        }

        static JoinResult connectTo(String address) { return new JoinResult(Type.CONNECT, address); }
        static JoinResult hosting(String worldPath) { return new JoinResult(Type.HOSTING, worldPath); }
        static JoinResult wrongPassword() { return new JoinResult(Type.WRONG_PASSWORD, null); }
        static JoinResult tunnelFailed() { return new JoinResult(Type.TUNNEL_FAILED, null); }
        static JoinResult needsWorldSetup() { return new JoinResult(Type.NEEDS_WORLD_SETUP, null); }
    }
}
