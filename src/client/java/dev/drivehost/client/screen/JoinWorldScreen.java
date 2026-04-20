package dev.drivehost.client.screen;

import dev.drivehost.DriveHostMod;
import dev.drivehost.drive.DriveService;
import dev.drivehost.hosting.JoinController;
import dev.drivehost.hosting.JoinController.JoinResult;
import dev.drivehost.tunnel.TunnelManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Screen for joining an existing DriveHost world.
 * Player enters the Drive folder ID and world password.
 */
public class JoinWorldScreen extends Screen {

    private static final Path SAVED_FOLDER_ID_FILE = DriveHostMod.getConfigDir().resolve("last-join-folder.txt");

    private final Screen parent;
    private EditBox folderIdField;
    private EditBox passwordField;
    private Button joinButton;
    private String statusMessage = "";
    private String authUrl = null;
    private int statusColor = 0xFFFFFF;
    private boolean processing = false;

    public JoinWorldScreen(Screen parent) {
        super(Component.literal("Join DriveHost World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldWidth = 250;
        int startY = 70;
        int spacing = 28;

        // Google Drive Folder ID
        folderIdField = new EditBox(this.font, centerX - fieldWidth / 2, startY, fieldWidth, 20,
            Component.literal("Drive Folder ID"));
        folderIdField.setMaxLength(200);
        folderIdField.setHint(Component.literal("Google Drive Folder ID"));
        this.addRenderableWidget(folderIdField);
        // Restore saved folder ID
        try {
            if (Files.exists(SAVED_FOLDER_ID_FILE)) {
                String saved = Files.readString(SAVED_FOLDER_ID_FILE).trim();
                if (!saved.isEmpty()) folderIdField.setValue(saved);
            }
        } catch (Exception ignored) {}

        // Password
        passwordField = new EditBox(this.font, centerX - fieldWidth / 2, startY + spacing, fieldWidth, 20,
            Component.literal("Password"));
        passwordField.setMaxLength(128);
        passwordField.setHint(Component.literal("World Password"));
        this.addRenderableWidget(passwordField);

        // Join button
        joinButton = Button.builder(Component.literal("Join"), button -> {
            onJoin();
        })
        .bounds(centerX - 100, startY + spacing * 2 + 10, 200, 20)
        .build();
        this.addRenderableWidget(joinButton);

        // Back button
        this.addRenderableWidget(
            Button.builder(Component.literal("Back"), button -> {
                this.minecraft.setScreen(parent);
            })
            .bounds(centerX - 100, startY + spacing * 3 + 10, 200, 20)
            .build()
        );
    }

    private void onJoin() {
        if (processing) return;

        // Strip URL params — accept full share links or bare IDs
        String folderId = folderIdField.getValue().trim();
        int q = folderId.indexOf('?'); if (q != -1) folderId = folderId.substring(0, q);
        int slash = folderId.lastIndexOf('/');
        if (slash != -1) folderId = folderId.substring(slash + 1);
        folderId = folderId.trim();
        folderIdField.setValue(folderId);

        String password = passwordField.getValue();

        // Validation
        if (folderId.isEmpty()) {
            setStatus("Please enter a Google Drive Folder ID", 0xFF5555);
            return;
        }
        if (password.isEmpty()) {
            setStatus("Please enter the world password", 0xFF5555);
            return;
        }

        // Save folder ID immediately
        final String folderIdFinal = folderId;
        try { Files.writeString(SAVED_FOLDER_ID_FILE, folderIdFinal); } catch (Exception ignored) {}

        processing = true;
        joinButton.active = false;
        authUrl = null;
        setStatus("Authenticating with Google Drive...", 0xFFFF55);

        Thread worker = new Thread(() -> {
            try {
                DriveService drive = new DriveService();
                drive.setAuthUrlCallback(url -> {
                    authUrl = url;
                    setStatus("Browser opened for sign-in. If nothing opened, copy the URL shown below.", 0xFFFF55);
                });
                drive.authenticate();
                authUrl = null;

                setStatus("Verifying password...", 0xFFFF55);

                String playerName = minecraft.getUser().getName();
                TunnelManager tunnelManager = new TunnelManager();
                JoinController controller = new JoinController(drive, tunnelManager, playerName);

                JoinResult result = controller.joinWorld(folderIdFinal, password);

                switch (result.type()) {
                    case CONNECT -> {
                        try { Files.deleteIfExists(SAVED_FOLDER_ID_FILE); } catch (Exception ignored) {}
                        setStatus("Connecting to host at " + result.address() + "...", 0x55FF55);
                        String address = result.address();
                        minecraft.execute(() -> connectToServer(address));
                    }
                    case HOSTING -> {
                        String tunnelAddr = controller.getHostController().getTunnelResult().publicAddress();
                        setStatus("You are now hosting at " + tunnelAddr + " — loading world...", 0x55FF55);
                        loadWorldForHosting(result.address(), controller);
                    }
                    case WRONG_PASSWORD -> {
                        setStatus("Wrong password. Please try again.", 0xFF5555);
                    }
                    case TUNNEL_FAILED -> {
                        setStatus("All tunnel methods failed. Try port-forwarding port 25565.", 0xFF5555);
                    }
                    default -> {
                        setStatus("Unexpected result: " + result.type(), 0xFF5555);
                    }
                }
            } catch (Exception e) {
                DriveHostMod.LOGGER.error("Join world failed", e);
                setStatus("Error: " + e.getMessage(), 0xFF5555);
            } finally {
                processing = false;
                if (joinButton != null) joinButton.active = true;
            }
        }, "DriveHost-Join");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadWorldForHosting(String worldPath, dev.drivehost.hosting.JoinController controller) {
        java.nio.file.Path savesDir = minecraft.gameDirectory.toPath().resolve("saves").resolve("DriveHostWorld");
        boolean hasRealWorld = false;
        try {
            java.nio.file.Path src = java.nio.file.Paths.get(worldPath);
            if (java.nio.file.Files.exists(savesDir)) {
                java.nio.file.Files.walk(savesDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
            java.nio.file.Files.createDirectories(savesDir);
            java.nio.file.Files.walk(src).forEach(p -> {
                try {
                    java.nio.file.Path dest = savesDir.resolve(src.relativize(p));
                    if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(dest);
                    else java.nio.file.Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            });
            hasRealWorld = java.nio.file.Files.exists(savesDir.resolve("level.dat"));
        } catch (Exception e) {
            DriveHostMod.LOGGER.error("Failed to prepare world for hosting", e);
            setStatus("Error preparing world: " + e.getMessage(), 0xFF5555);
            return;
        }
        if (hasRealWorld) {
            // Proper MC world — load it directly
            controller.setHostWorldDir(savesDir);
            DriveHostMod.pendingPublishOnLan = true;
            DriveHostMod.activeHostController = controller;
            minecraft.execute(() -> minecraft.createWorldOpenFlows().checkForBackupAndLoad("DriveHostWorld", () -> {}));
        } else {
            // Placeholder/old world with no level.dat — generate a fresh MC world and re-upload
            DriveHostMod.LOGGER.warn("No level.dat in downloaded world — generating fresh world and re-uploading");
            setStatus("World had no data — generating fresh world...", 0xFFAA00);
            launchFreshWorld(controller);
        }
    }

    private void launchFreshWorld(dev.drivehost.hosting.JoinController controller) {
        java.nio.file.Path savesDir = minecraft.gameDirectory.toPath().resolve("saves").resolve("DriveHostWorld");
        try {
            if (java.nio.file.Files.exists(savesDir)) {
                java.nio.file.Files.walk(savesDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        } catch (Exception e) {
            DriveHostMod.LOGGER.warn("Could not clear old DriveHostWorld", e);
        }
        DriveHostMod.pendingPublishOnLan = true;
        DriveHostMod.pendingUploadController = controller;
        DriveHostMod.activeHostController = controller;
        minecraft.execute(() -> {
            net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(
                "DriveHostWorld",
                net.minecraft.world.level.GameType.SURVIVAL,
                false,
                net.minecraft.world.Difficulty.EASY,
                false,
                new net.minecraft.world.level.GameRules(),
                net.minecraft.world.level.WorldDataConfiguration.DEFAULT
            );
            net.minecraft.world.level.levelgen.WorldOptions worldOptions =
                net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed();
            minecraft.createWorldOpenFlows().createFreshLevel(
                "DriveHostWorld", settings, worldOptions,
                net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions,
                this
            );
        });
    }

    /**
     * Connect to a Minecraft server by address string (e.g. "1.2.3.4:25565").
     */
    private void connectToServer(String address) {
        try {
            ServerAddress serverAddr = ServerAddress.parseString(address);
            ServerData serverData = new ServerData("DriveHost", address, ServerData.Type.OTHER);

            // Use Minecraft's built-in connection flow
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                this, this.minecraft, serverAddr, serverData, false
            );
        } catch (Exception e) {
            DriveHostMod.LOGGER.error("Failed to connect to server: {}", address, e);
            setStatus("Failed to connect: " + e.getMessage(), 0xFF5555);
        }
    }

    private void setStatus(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
            Component.literal("Enter the folder ID and password shared by the world owner"),
            this.width / 2, 32, 0xAAAAAA);

        // Status message
        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal(statusMessage),
                this.width / 2, 52, statusColor);
        }
        // Auth URL fallback display
        if (authUrl != null) {
            graphics.drawCenteredString(this.font,
                Component.literal("Open in browser (Ctrl+C to copy from console):"),
                this.width / 2, this.height - 70, 0xFFFF55);
            String display = authUrl.length() > 80 ? authUrl.substring(0, 77) + "..." : authUrl;
            graphics.drawCenteredString(this.font, Component.literal(display), this.width / 2, this.height - 58, 0xAAAAAA);
        }

        // Field labels
        int labelX = this.width / 2 - 125;
        graphics.drawString(this.font, "Drive Folder ID:", labelX, 60, 0xAAAAAA);
        graphics.drawString(this.font, "Password:", labelX, 88, 0xAAAAAA);

        // Instructions at bottom
        int instrY = this.height - 50;
        graphics.drawCenteredString(this.font,
            Component.literal("Ask the world owner for the Folder ID and password"),
            this.width / 2, instrY, 0x888888);
        graphics.drawCenteredString(this.font,
            Component.literal("You'll need a Google account for Drive access"),
            this.width / 2, instrY + 12, 0x888888);

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
