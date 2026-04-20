package dev.drivehost.hosting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data model for session.json — the live session state stored (encrypted) on Google Drive.
 */
public class SessionData {

    private String hostTunnelAddress;
    private String hostPlayerName;
    private String hostLastSeen;       // ISO-8601 timestamp
    private String tunnelMethod;       // UPNP, PLAYIT, or NGROK
    private int worldVersion;
    private String lastSaveTimestamp;   // ISO-8601 timestamp
    private List<String> activePlayers = new ArrayList<>();
    private String owner;

    // --- Getters/Setters ---

    public String getHostTunnelAddress() {
        return hostTunnelAddress;
    }

    public void setHostTunnelAddress(String hostTunnelAddress) {
        this.hostTunnelAddress = hostTunnelAddress;
    }

    public String getHostPlayerName() {
        return hostPlayerName;
    }

    public void setHostPlayerName(String hostPlayerName) {
        this.hostPlayerName = hostPlayerName;
    }

    public String getHostLastSeen() {
        return hostLastSeen;
    }

    public void setHostLastSeen(String hostLastSeen) {
        this.hostLastSeen = hostLastSeen;
    }

    public String getTunnelMethod() {
        return tunnelMethod;
    }

    public void setTunnelMethod(String tunnelMethod) {
        this.tunnelMethod = tunnelMethod;
    }

    public int getWorldVersion() {
        return worldVersion;
    }

    public void setWorldVersion(int worldVersion) {
        this.worldVersion = worldVersion;
    }

    public String getLastSaveTimestamp() {
        return lastSaveTimestamp;
    }

    public void setLastSaveTimestamp(String lastSaveTimestamp) {
        this.lastSaveTimestamp = lastSaveTimestamp;
    }

    public List<String> getActivePlayers() {
        return activePlayers;
    }

    public void setActivePlayers(List<String> activePlayers) {
        this.activePlayers = activePlayers != null ? activePlayers : new ArrayList<>();
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    // --- Convenience ---

    public void updateHeartbeat() {
        this.hostLastSeen = Instant.now().toString();
    }

    public boolean isHostAlive() {
        return isHostAlive(15);
    }

    /**
     * Check if the host is alive (heartbeat within the given threshold in seconds).
     */
    public boolean isHostAlive(int thresholdSeconds) {
        if (hostLastSeen == null || hostLastSeen.isBlank()) return false;
        try {
            Instant lastSeen = Instant.parse(hostLastSeen);
            return Instant.now().minusSeconds(thresholdSeconds).isBefore(lastSeen);
        } catch (Exception e) {
            return false;
        }
    }

    public void incrementWorldVersion() {
        this.worldVersion++;
        this.lastSaveTimestamp = Instant.now().toString();
    }
}
