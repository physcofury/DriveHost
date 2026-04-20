package dev.drivehost.tunnel;

import dev.drivehost.DriveHostMod;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayitTunnel implements Tunnel {

    private static final String BINARY_URL =
        "https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-windows-x86_64.exe";

    // "INFO playit_cli::ui: 2f221e88b1"
    private static final Pattern CLAIM_CODE_PATTERN =
        Pattern.compile("INFO playit_cli[^:]*::\\s*ui:\\s+(\\S+)");
    // tunnel address like "xxx.joinmc.link:19832" or "tcp: xxx:port"
    private static final Pattern ADDRESS_PATTERN =
        Pattern.compile("([\\w\\-]+\\.joinmc\\.link:\\d+|(?:tcp|address)[:\\s]+[\\w.\\-]+:\\d+)",
            Pattern.CASE_INSENSITIVE);

    private static final String API_BASE = "https://api.playit.gg";

    /** Marker file we write after user confirms they created a tunnel in the dashboard */
    private static Path getTunnelConfirmedMarker() {
        return getPlayitDir().resolve(".tunnel_confirmed");
    }

    private Process agentProcess;

    private static Path getPlayitDir() {
        return DriveHostMod.getConfigDir().resolve("playit");
    }

    private static Path getBinaryPath() {
        return getPlayitDir().resolve("playit.exe");
    }

    /** Path where playit saves the secret by default (Windows: %LOCALAPPDATA%\playit_gg\playit.toml) */
    private static Path getSystemSecretPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            return Paths.get(localAppData, "playit_gg", "playit.toml");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "playit_gg", "playit.toml");
    }

    @Override
    public String open(int localPort) throws Exception {
        DriveHostMod.LOGGER.info("Starting Playit.gg tunnel on port {}...", localPort);
        Path binary = getBinaryPath();
        if (!Files.exists(binary)) {
            DriveHostMod.LOGGER.warn("playit binary missing, cannot start tunnel");
            return null;
        }

        // First try to get address from API (faster, more reliable)
        String apiAddress = getTunnelAddressFromApi();
        if (apiAddress != null) {
            DriveHostMod.LOGGER.info("Playit.gg tunnel address from API: {}", apiAddress);
        }

        ProcessBuilder pb = new ProcessBuilder(
            binary.toString(), "-s", "start"
        ).directory(getPlayitDir().toFile()).redirectErrorStream(true);
        agentProcess = pb.start();

        AtomicReference<String> foundAddress = new AtomicReference<>(apiAddress);
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(agentProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    DriveHostMod.LOGGER.info("[playit-run] {}", line);
                    if (foundAddress.get() == null) {
                        Matcher m = ADDRESS_PATTERN.matcher(line);
                        if (m.find()) foundAddress.set(m.group(1));
                    }
                }
            } catch (IOException ignored) {}
        }, "DriveHost-PlayitRun");
        reader.setDaemon(true);
        reader.start();

        // If we already have address from API, just wait a moment for agent to fully connect
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(apiAddress != null ? 8 : 30);
        while (System.currentTimeMillis() < deadline && (apiAddress == null && foundAddress.get() == null)) {
            if (!agentProcess.isAlive()) {
                DriveHostMod.LOGGER.warn("playit exited early (code {})", agentProcess.exitValue());
                return null;
            }
            Thread.sleep(500);
        }

        String addr = foundAddress.get();
        if (addr != null) {
            DriveHostMod.LOGGER.info("Playit.gg tunnel: {}", addr);
        } else {
            DriveHostMod.LOGGER.warn("Playit.gg: no address in 30s — create a Minecraft tunnel at playit.gg/account/tunnels");
            close();
        }
        return addr;
    }

    /** Reads the tunnel address from the Playit API without needing to parse agent stdout. */
    private String getTunnelAddressFromApi() {
        try {
            String secret = Files.readString(getSystemSecretPath()).trim();
            // Extract secret_key value from toml
            if (secret.contains("secret_key")) {
                java.util.regex.Matcher m = Pattern.compile("secret_key\\s*=\\s*\"([^\"]+)\"").matcher(secret);
                if (m.find()) secret = m.group(1);
                else return null;
            }
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/agents/rundata"))
                .header("Authorization", "agent-key " + secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(8))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            DriveHostMod.LOGGER.debug("agents/rundata: {}", body);
            // Parse "assigned_domain" + "port": {"from": N}
            java.util.regex.Matcher dom = Pattern.compile("\"assigned_domain\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            java.util.regex.Matcher port = Pattern.compile("\"port\"\\s*:\\s*\\{\\s*\"from\"\\s*:\\s*(\\d+)").matcher(body);
            if (dom.find() && port.find()) return dom.group(1) + ":" + port.group(1);
            // fallback: assigned_srv
            java.util.regex.Matcher srv = Pattern.compile("\"assigned_srv\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (srv.find()) return srv.group(1);
        } catch (Exception e) {
            DriveHostMod.LOGGER.debug("Could not get address from API: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void close() {
        if (agentProcess != null && agentProcess.isAlive()) {
            agentProcess.destroy();
            agentProcess = null;
        }
        DriveHostMod.LOGGER.info("Playit.gg tunnel closed");
    }

    @Override
    public TunnelResult.TunnelMethod getMethod() { return TunnelResult.TunnelMethod.PLAYIT; }

    @Override
    public boolean requiresSetup() { return true; }

    @Override
    public boolean isSetupComplete() {
        return Files.exists(getSystemSecretPath()) && Files.exists(getTunnelConfirmedMarker());
    }

    public boolean isAgentClaimed() {
        return Files.exists(getSystemSecretPath());
    }

    public void confirmTunnelCreated() throws IOException {
        Files.createDirectories(getTunnelConfirmedMarker().getParent());
        Files.writeString(getTunnelConfirmedMarker(), "ok");
    }

    public void resetSetup() {
        try { Files.deleteIfExists(getSystemSecretPath()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(getTunnelConfirmedMarker()); } catch (IOException ignored) {}
    }

    /**
     * Runs the full claim flow on a background thread.
     * statusConsumer: called with status strings ("Downloading...", claim URL, etc.)
     * onDone: called with null on success, error message on failure
     */
    public void runClaimFlow(Consumer<String> statusConsumer,
                             Consumer<String> onDone) throws Exception {
        Path binary = getBinaryPath();
        Files.createDirectories(binary.getParent());
        if (!Files.exists(binary)) {
            statusConsumer.accept("Downloading playit binary...");
            downloadBinary(binary);
        }

        // Step 1: generate claim code
        statusConsumer.accept("Generating claim code...");
        Process genProc = new ProcessBuilder(
            binary.toString(), "-s", "claim", "generate"
        ).directory(getPlayitDir().toFile()).redirectErrorStream(true).start();

        String claimCode = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(genProc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                DriveHostMod.LOGGER.info("[playit-claim-gen] {}", line);
                Matcher m = CLAIM_CODE_PATTERN.matcher(line);
                if (m.find()) {
                    claimCode = m.group(1);
                    break;
                }
                // fallback: if line is just the code (simple word/hex)
                String trimmed = line.trim();
                if (trimmed.matches("[a-z0-9\\-]{5,20}") && !trimmed.contains(" ")) {
                    claimCode = trimmed;
                    break;
                }
            }
        }
        genProc.destroyForcibly();

        if (claimCode == null) {
            throw new IOException("Could not get claim code from playit binary");
        }

        String claimUrl = "https://playit.gg/claim/" + claimCode;
        statusConsumer.accept(claimUrl);
        DriveHostMod.LOGGER.info("Playit.gg claim URL: {}", claimUrl);

        // Open browser
        try {
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", claimUrl).start();
        } catch (Exception ignored) {}

        // Step 2: exchange claim code for secret (polls until user claims in browser)
        statusConsumer.accept("Waiting for browser claim...");
        Process exchangeProc = new ProcessBuilder(
            binary.toString(), "-s", "claim", "exchange", claimCode
        ).directory(getPlayitDir().toFile()).redirectErrorStream(true).start();

        // drain output
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchangeProc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    DriveHostMod.LOGGER.info("[playit-exchange] {}", line);
                }
            } catch (IOException ignored) {}
        }, "DriveHost-PlayitExchange").start();

        boolean claimed = exchangeProc.waitFor(3, TimeUnit.MINUTES);
        if (!claimed) {
            exchangeProc.destroyForcibly();
            throw new IOException("Timed out waiting for claim (3 min)");
        }

        if (Files.exists(getSystemSecretPath())) {
            onDone.accept(null); // success
        } else {
            throw new IOException("Exchange completed but no secret file found at " + getSystemSecretPath());
        }
    }

    private void downloadBinary(Path dest) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BINARY_URL))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            Files.deleteIfExists(dest);
            throw new IOException("Failed to download playit binary: HTTP " + resp.statusCode());
        }
        dest.toFile().setExecutable(true);
        DriveHostMod.LOGGER.info("playit binary downloaded to {}", dest);
    }
}