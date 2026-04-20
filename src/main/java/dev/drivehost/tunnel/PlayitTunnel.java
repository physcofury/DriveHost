package dev.drivehost.tunnel;

import dev.drivehost.DriveHostMod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tier 2: Playit.gg tunneling — pure Java, no binary needed.
 * Uses the Playit.gg REST API to create tunnels.
 * First-time setup requires a one-time browser claim.
 */
public class PlayitTunnel implements Tunnel {

    private static final String API_BASE = "https://api.playit.gg";
    private static final String CLAIM_URL_PREFIX = "https://playit.gg/claim/";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .build();
    private final Gson gson = new Gson();

    private String secretKey;
    private String agentId;
    private String tunnelAddress;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> claimCode = new AtomicReference<>();

    private static Path getSecretPath() {
        return DriveHostMod.getConfigDir().resolve("playit-secret.json");
    }

    @Override
    public String open(int localPort) throws Exception {
        DriveHostMod.LOGGER.info("Trying Playit.gg tunnel on port {}...", localPort);

        // Load saved secret key
        loadSecret();
        if (secretKey == null) {
            // Need to do the claim flow
            DriveHostMod.LOGGER.info("No Playit.gg secret found — starting claim flow");
            boolean claimed = runClaimFlow();
            if (!claimed || secretKey == null) {
                DriveHostMod.LOGGER.info("Playit.gg claim flow not completed");
                return null;
            }
        }

        // Create/find a Minecraft Java tunnel
        tunnelAddress = ensureTunnel(localPort);
        if (tunnelAddress != null) {
            running.set(true);
            DriveHostMod.LOGGER.info("Playit.gg tunnel opened: {}", tunnelAddress);
        }
        return tunnelAddress;
    }

    @Override
    public void close() {
        running.set(false);
        tunnelAddress = null;
        DriveHostMod.LOGGER.info("Playit.gg tunnel closed");
    }

    @Override
    public TunnelResult.TunnelMethod getMethod() {
        return TunnelResult.TunnelMethod.PLAYIT;
    }

    @Override
    public boolean requiresSetup() {
        return true;
    }

    @Override
    public boolean isSetupComplete() {
        try {
            loadSecret();
        } catch (Exception ignored) {}
        return secretKey != null;
    }

    /**
     * Get the claim code for display in the UI (e.g. "Visit playit.gg/claim/XXXX").
     */
    public String getClaimCode() {
        return claimCode.get();
    }

    public String getClaimUrl() {
        String code = claimCode.get();
        return code != null ? CLAIM_URL_PREFIX + code : null;
    }

    // --- Internal ---

    private void loadSecret() throws IOException {
        Path path = getSecretPath();
        if (Files.exists(path)) {
            String json = Files.readString(path);
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            secretKey = obj.has("secret_key") ? obj.get("secret_key").getAsString() : null;
            agentId = obj.has("agent_id") ? obj.get("agent_id").getAsString() : null;
        }
    }

    private void saveSecret() throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("secret_key", secretKey);
        if (agentId != null) obj.addProperty("agent_id", agentId);
        Files.writeString(getSecretPath(), gson.toJson(obj));
    }

    /**
     * Run the claim flow: create a guest session, generate a claim code,
     * wait for the user to accept in their browser.
     * Returns true if the claim was accepted and the secret key was obtained.
     */
    private boolean runClaimFlow() throws Exception {
        // 1. Create guest account
        JsonObject guestResp = apiPost("/login/create-guest", new JsonObject());
        if (guestResp == null || !guestResp.has("session_key")) {
            DriveHostMod.LOGGER.warn("Failed to create Playit.gg guest account");
            return false;
        }
        String sessionKey = guestResp.get("session_key").getAsString();

        // 2. Create an agent
        JsonObject agentReq = new JsonObject();
        agentReq.addProperty("name", "DriveHost");
        JsonObject agentResp = apiPostAuth("/agents", agentReq, sessionKey);
        if (agentResp != null && agentResp.has("id")) {
            agentId = agentResp.get("id").getAsString();
        }

        // 3. Generate claim code
        JsonObject claimReq = new JsonObject();
        JsonObject claimResp = apiPostAuth("/claim/setup", claimReq, sessionKey);
        if (claimResp == null || !claimResp.has("code")) {
            DriveHostMod.LOGGER.warn("Failed to get Playit.gg claim code");
            return false;
        }
        String code = claimResp.get("code").getAsString();
        claimCode.set(code);
        DriveHostMod.LOGGER.info("Playit.gg claim code: {} — Visit {}{}", code, CLAIM_URL_PREFIX, code);

        // 4. Poll for claim acceptance (max 5 minutes)
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000);
            JsonObject pollReq = new JsonObject();
            pollReq.addProperty("code", code);
            JsonObject pollResp = apiPostAuth("/claim/exchange", pollReq, sessionKey);
            if (pollResp != null && pollResp.has("secret_key")) {
                secretKey = pollResp.get("secret_key").getAsString();
                saveSecret();
                claimCode.set(null);
                DriveHostMod.LOGGER.info("Playit.gg claim accepted — secret key saved");
                return true;
            }
        }

        claimCode.set(null);
        return false;
    }

    /**
     * Ensure a Minecraft Java tunnel exists for this agent, targeting the given port.
     */
    private String ensureTunnel(int localPort) throws Exception {
        // List existing tunnels
        JsonObject listReq = new JsonObject();
        JsonObject listResp = apiPostAuth("/v1/tunnels/list", listReq, "agent-key " + secretKey);
        if (listResp != null && listResp.has("tunnels")) {
            JsonArray tunnels = listResp.getAsJsonArray("tunnels");
            for (var element : tunnels) {
                JsonObject tunnel = element.getAsJsonObject();
                if (tunnel.has("domain") && tunnel.has("port")) {
                    String domain = tunnel.get("domain").getAsJsonObject().get("custom").getAsString();
                    int port = tunnel.get("port").getAsInt();
                    return domain + ":" + port;
                }
                // Return first tunnel address we find
                if (tunnel.has("connect_address")) {
                    return tunnel.get("connect_address").getAsString();
                }
            }
        }

        // Create a new tunnel
        JsonObject createReq = new JsonObject();
        createReq.addProperty("tunnel_type", "minecraft-java");
        createReq.addProperty("port_type", "tcp");
        createReq.addProperty("local_port", localPort);
        if (agentId != null) {
            createReq.addProperty("agent_id", agentId);
        }

        JsonObject createResp = apiPostAuth("/v1/tunnels/create", createReq, "agent-key " + secretKey);
        if (createResp != null && createResp.has("connect_address")) {
            return createResp.get("connect_address").getAsString();
        }

        // Try to extract address from different response format
        if (createResp != null) {
            if (createResp.has("domain") && createResp.has("port")) {
                return createResp.get("domain").getAsString() + ":" + createResp.get("port").getAsInt();
            }
            if (createResp.has("assigned_domain")) {
                return createResp.get("assigned_domain").getAsString();
            }
        }

        DriveHostMod.LOGGER.warn("Failed to create Playit.gg tunnel");
        return null;
    }

    private JsonObject apiPost(String endpoint, JsonObject body) throws Exception {
        return apiPostAuth(endpoint, body, null);
    }

    private JsonObject apiPostAuth(String endpoint, JsonObject body, String auth) throws Exception {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(API_BASE + endpoint))
            .timeout(HTTP_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));

        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
            HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return gson.fromJson(resp.body(), JsonObject.class);
        }
        DriveHostMod.LOGGER.debug("Playit API {} returned {}: {}", endpoint, resp.statusCode(), resp.body());
        return null;
    }
}
