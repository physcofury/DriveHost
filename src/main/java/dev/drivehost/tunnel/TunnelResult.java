package dev.drivehost.tunnel;

/**
 * Result of a successful tunnel establishment.
 */
public record TunnelResult(String publicAddress, TunnelMethod method) {

    public enum TunnelMethod {
        UPNP("UPnP"),
        PLAYIT("Playit.gg"),
        NGROK("ngrok");

        private final String displayName;

        TunnelMethod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
