package dev.drivehost.hosting;

import dev.drivehost.DriveHostMod;
import dev.drivehost.tunnel.TunnelManager;
import dev.drivehost.tunnel.TunnelResult;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * Controls the hosting lifecycle: start/stop embedded server, tunnel, heartbeat, autosave.
 */
public class HostController {

    private static final int DEFAULT_PORT = 25565;
    private static final long HEARTBEAT_INTERVAL_MS = 5_000;
    private static final long AUTOSAVE_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

    private final TunnelManager tunnelManager;
    private final SessionManager sessionManager;
    private final WorldManager worldManager;
    private final String playerName;

    private ScheduledExecutorService scheduler;
    private volatile boolean hosting = false;
    private TunnelResult tunnelResult;
    private int port = DEFAULT_PORT;

    public HostController(TunnelManager tunnelManager, SessionManager sessionManager,
                          WorldManager worldManager, String playerName) {
        this.tunnelManager = tunnelManager;
        this.sessionManager = sessionManager;
        this.worldManager = worldManager;
        this.playerName = playerName;
    }

    /**
     * Become the host: open tunnel, update session, start heartbeat + autosave.
     * The actual Minecraft server is started by the caller (integrated server via Minecraft's API).
     */
    public boolean becomeHost() {
        DriveHostMod.LOGGER.info("Becoming host as '{}'...", playerName);

        // Open tunnel
        tunnelResult = tunnelManager.openTunnel(port);
        if (tunnelResult == null) {
            DriveHostMod.LOGGER.error("Failed to open any tunnel — cannot host. " +
                "Consider manually port-forwarding port {} on your router.", port);
            return false;
        }

        DriveHostMod.LOGGER.info("Tunnel opened via {}: {}",
            tunnelResult.method().getDisplayName(), tunnelResult.publicAddress());

        // Update session.json on Drive
        try {
            SessionData session = sessionManager.readSession();
            if (session == null) {
                session = new SessionData();
            }
            session.setHostTunnelAddress(tunnelResult.publicAddress());
            session.setHostPlayerName(playerName);
            session.setTunnelMethod(tunnelResult.method().name());
            session.updateHeartbeat();
            if (!session.getActivePlayers().contains(playerName)) {
                session.getActivePlayers().add(playerName);
            }
            sessionManager.writeSession(session);
        } catch (Exception e) {
            DriveHostMod.LOGGER.error("Failed to update session on Drive", e);
            tunnelManager.closeTunnel();
            return false;
        }

        // Start heartbeat and autosave timers
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "DriveHost-Scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::heartbeat,
            HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::autosave,
            AUTOSAVE_INTERVAL_MS, AUTOSAVE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        hosting = true;
        DriveHostMod.LOGGER.info("Now hosting via {} at {}",
            tunnelResult.method().getDisplayName(), tunnelResult.publicAddress());
        return true;
    }

    /**
     * Stop hosting: final save, close tunnel, stop timers.
     */
    public void stopHosting() {
        if (!hosting) return;

        DriveHostMod.LOGGER.info("Stopping hosting...");
        hosting = false;

        // Stop timers
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        // Final save (call doAutosave directly — autosave() guards on `hosting` which is now false)
        doAutosave();

        // Clear host from session
        try {
            SessionData session = sessionManager.readSession();
            if (session != null) {
                session.setHostTunnelAddress(null);
                session.setHostPlayerName(null);
                session.getActivePlayers().remove(playerName);
                sessionManager.writeSession(session);
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("Failed to clear session on stop", e);
        }

        // Close tunnel
        tunnelManager.closeTunnel();

        // Cleanup temp files
        worldManager.cleanupTempFiles();

        DriveHostMod.LOGGER.info("Hosting stopped");
    }

    public boolean isHosting() {
        return hosting;
    }

    public TunnelResult getTunnelResult() {
        return tunnelResult;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    // --- Internal ---

    private void heartbeat() {
        if (!hosting) return;
        try {
            SessionData session = sessionManager.readSession();
            if (session != null) {
                session.updateHeartbeat();
                sessionManager.writeSession(session);
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("Heartbeat failed", e);
        }
    }

    private void autosave() {
        if (!hosting) return;
        doAutosave();
    }

    private void doAutosave() {
        Path worldDir = worldManager.getCurrentWorldDir();
        if (worldDir == null) return;

        try {
            DriveHostMod.LOGGER.info("Autosaving world to Drive...");
            worldManager.encryptAndUploadWorld(worldDir);

            SessionData session = sessionManager.readSession();
            if (session != null) {
                session.incrementWorldVersion();
                sessionManager.writeSession(session);
            }
            DriveHostMod.LOGGER.info("Autosave complete");
        } catch (Exception e) {
            DriveHostMod.LOGGER.error("Autosave failed", e);
        }
    }
}
