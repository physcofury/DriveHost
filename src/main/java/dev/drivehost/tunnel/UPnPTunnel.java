package dev.drivehost.tunnel;

import dev.drivehost.DriveHostMod;
import dev.drivehost.upnp.UPnP;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tier 1: UPnP port mapping via WaifUPnP.
 * Works for ~70% of home routers. Zero user interaction needed.
 */
public class UPnPTunnel implements Tunnel {

    private int mappedPort = -1;

    @Override
    public String open(int localPort) throws Exception {
        DriveHostMod.LOGGER.info("Trying UPnP tunnel on port {}...", localPort);

        UPnP.waitInit();
        if (!UPnP.isUPnPAvailable()) {
            DriveHostMod.LOGGER.info("UPnP not available on this network");
            return null;
        }

        // Close any stale mapping first
        if (UPnP.isMappedTCP(localPort)) {
            UPnP.closePortTCP(localPort);
        }

        boolean opened = UPnP.openPortTCP(localPort);
        if (!opened) {
            DriveHostMod.LOGGER.warn("UPnP port mapping failed");
            return null;
        }

        mappedPort = localPort;

        String externalIP = UPnP.getExternalIP();
        if (externalIP == null) {
            externalIP = getExternalIPFallback();
        }
        if (externalIP == null) {
            DriveHostMod.LOGGER.warn("Could not determine external IP");
            UPnP.closePortTCP(localPort);
            mappedPort = -1;
            return null;
        }

        String address = externalIP + ":" + localPort;
        DriveHostMod.LOGGER.info("UPnP tunnel opened: {}", address);
        return address;
    }

    @Override
    public void close() {
        if (mappedPort > 0) {
            UPnP.closePortTCP(mappedPort);
            DriveHostMod.LOGGER.info("UPnP port {} closed", mappedPort);
            mappedPort = -1;
        }
    }

    @Override
    public TunnelResult.TunnelMethod getMethod() {
        return TunnelResult.TunnelMethod.UPNP;
    }

    @Override
    public boolean requiresSetup() {
        return false;
    }

    @Override
    public boolean isSetupComplete() {
        return true;
    }

    /**
     * Fallback: get external IP from a public API.
     */
    private String getExternalIPFallback() {
        String[] apis = {
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com"
        };
        for (String api : apis) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ip = reader.readLine();
                    if (ip != null) return ip.trim();
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                // Try next
            }
        }
        return null;
    }
}
