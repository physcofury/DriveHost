package dev.drivehost.tunnel;

import dev.drivehost.DriveHostMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tier 3: ngrok tunneling via the ngrok-java SDK (JNI).
 * Requires a free ngrok account and authtoken.
 * TCP tunnels on ngrok free tier require credit card verification.
 */
public class NgrokTunnel implements Tunnel {

    private static final Path AUTHTOKEN_PATH = DriveHostMod.getConfigDir().resolve("ngrok-authtoken.txt");

    // Using Object types to avoid hard compile-time dependency on ngrok-java
    // which may not be available at compile time in all environments.
    // At runtime, the shaded classes will be present.
    private Object session;    // com.ngrok.Session
    private Object forwarder;  // com.ngrok.Forwarder
    private String authtoken;

    @Override
    public String open(int localPort) throws Exception {
        DriveHostMod.LOGGER.info("Trying ngrok tunnel on port {}...", localPort);

        loadAuthtoken();
        if (authtoken == null || authtoken.isBlank()) {
            DriveHostMod.LOGGER.info("No ngrok authtoken configured");
            return null;
        }

        try {
            // Use reflection to avoid hard dependency — ngrok-java may not be on classpath in dev
            Class<?> sessionClass = Class.forName("com.ngrok.Session");
            Object builder = sessionClass.getMethod("withAuthtoken", String.class).invoke(null, authtoken);
            session = builder.getClass().getMethod("connect").invoke(builder);

            Object tcpBuilder = session.getClass().getMethod("tcpEndpoint").invoke(session);

            // Forward to localhost
            Class<?> urlClass = java.net.URL.class;
            Object url = urlClass.getConstructor(String.class).newInstance("http://localhost:" + localPort);
            forwarder = tcpBuilder.getClass().getMethod("forward", urlClass).invoke(tcpBuilder, url);

            String publicUrl = (String) forwarder.getClass().getMethod("getUrl").invoke(forwarder);

            // Strip tcp:// prefix for Minecraft connection
            String address = publicUrl;
            if (address.startsWith("tcp://")) {
                address = address.substring("tcp://".length());
            }

            DriveHostMod.LOGGER.info("ngrok tunnel opened: {}", address);
            return address;
        } catch (ClassNotFoundException e) {
            DriveHostMod.LOGGER.warn("ngrok-java SDK not available on classpath");
            return null;
        } catch (Exception e) {
            DriveHostMod.LOGGER.error("Failed to open ngrok tunnel", e);
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (forwarder != null) {
                forwarder.getClass().getMethod("close").invoke(forwarder);
            }
            if (session != null) {
                session.getClass().getMethod("close").invoke(session);
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("Error closing ngrok tunnel", e);
        }
        forwarder = null;
        session = null;
        DriveHostMod.LOGGER.info("ngrok tunnel closed");
    }

    @Override
    public TunnelResult.TunnelMethod getMethod() {
        return TunnelResult.TunnelMethod.NGROK;
    }

    @Override
    public boolean requiresSetup() {
        return true;
    }

    @Override
    public boolean isSetupComplete() {
        try {
            loadAuthtoken();
        } catch (Exception ignored) {}
        return authtoken != null && !authtoken.isBlank();
    }

    /**
     * Save an authtoken provided by the user through the UI.
     */
    public void saveAuthtoken(String token) throws IOException {
        Files.writeString(AUTHTOKEN_PATH, token.trim());
        this.authtoken = token.trim();
    }

    /**
     * Get the saved authtoken, or null if not set.
     */
    public String getAuthtoken() {
        try {
            loadAuthtoken();
        } catch (Exception ignored) {}
        return authtoken;
    }

    private void loadAuthtoken() throws IOException {
        if (authtoken != null) return;
        if (Files.exists(AUTHTOKEN_PATH)) {
            authtoken = Files.readString(AUTHTOKEN_PATH).trim();
        }
    }
}
