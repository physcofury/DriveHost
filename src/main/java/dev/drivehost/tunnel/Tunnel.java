package dev.drivehost.tunnel;

/**
 * Common interface for all tunneling methods.
 */
public interface Tunnel {

    /**
     * Attempt to open a tunnel to the given local port.
     * Returns the public address if successful, null if this method is unavailable.
     */
    String open(int localPort) throws Exception;

    /**
     * Close the tunnel and release resources.
     */
    void close();

    /**
     * Which tunnel method this implementation uses.
     */
    TunnelResult.TunnelMethod getMethod();

    /**
     * Whether this tunnel method requires one-time setup (e.g. account creation, authtoken).
     */
    boolean requiresSetup();

    /**
     * Whether the one-time setup has been completed.
     */
    boolean isSetupComplete();
}
