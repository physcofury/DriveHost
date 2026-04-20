package dev.drivehost.tunnel;

import dev.drivehost.DriveHostMod;

/**
 * Manages tunneling with a 3-tier fallback:
 *   Tier 1: UPnP (zero setup, ~70% of home routers)
 *   Tier 2: Playit.gg (one-time browser claim, works through any NAT)
 *   Tier 3: ngrok (requires account + authtoken)
 *
 * If all three fail, the caller should show manual port-forward instructions.
 */
public class TunnelManager {

    private final UPnPTunnel upnpTunnel = new UPnPTunnel();
    private final PlayitTunnel playitTunnel = new PlayitTunnel();
    private final NgrokTunnel ngrokTunnel = new NgrokTunnel();

    private Tunnel activeTunnel;
    private TunnelResult lastResult;

    /**
     * Attempt to open a tunnel, trying each tier in order.
     * Returns a TunnelResult on success, null if all methods fail.
     */
    public TunnelResult openTunnel(int localPort) {
        DriveHostMod.LOGGER.info("Opening tunnel for port {}...", localPort);

        // Tier 1: UPnP
        try {
            String address = upnpTunnel.open(localPort);
            if (address != null) {
                activeTunnel = upnpTunnel;
                lastResult = new TunnelResult(address, TunnelResult.TunnelMethod.UPNP);
                return lastResult;
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("UPnP tunnel failed", e);
        }

        // Tier 2: Playit.gg
        try {
            String address = playitTunnel.open(localPort);
            if (address != null) {
                activeTunnel = playitTunnel;
                lastResult = new TunnelResult(address, TunnelResult.TunnelMethod.PLAYIT);
                return lastResult;
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("Playit.gg tunnel failed", e);
        }

        // Tier 3: ngrok
        try {
            String address = ngrokTunnel.open(localPort);
            if (address != null) {
                activeTunnel = ngrokTunnel;
                lastResult = new TunnelResult(address, TunnelResult.TunnelMethod.NGROK);
                return lastResult;
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("ngrok tunnel failed", e);
        }

        DriveHostMod.LOGGER.error("All tunnel methods failed for port {}", localPort);
        return null;
    }

    /**
     * Close the currently active tunnel.
     */
    public void closeTunnel() {
        if (activeTunnel != null) {
            DriveHostMod.LOGGER.info("Closing {} tunnel", activeTunnel.getMethod().getDisplayName());
            activeTunnel.close();
            activeTunnel = null;
            lastResult = null;
        }
    }

    /**
     * Get the last successful tunnel result.
     */
    public TunnelResult getLastResult() {
        return lastResult;
    }

    /**
     * Get the currently active tunnel, or null.
     */
    public Tunnel getActiveTunnel() {
        return activeTunnel;
    }

    public PlayitTunnel getPlayitTunnel() {
        return playitTunnel;
    }

    public NgrokTunnel getNgrokTunnel() {
        return ngrokTunnel;
    }
}
