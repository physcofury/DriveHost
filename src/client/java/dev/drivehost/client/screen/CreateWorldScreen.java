package dev.drivehost.client.screen;

import dev.drivehost.DriveHostMod;
import dev.drivehost.drive.DriveService;
import dev.drivehost.hosting.JoinController;
import dev.drivehost.hosting.JoinController.JoinResult;
import dev.drivehost.tunnel.TunnelManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Screen for creating a new DriveHost world.
 * Owner enters their Drive folder ID, sets a password, and uploads the initial world.
 */
public class CreateWorldScreen extends Screen {

    private static final Path SAVED_FOLDER_ID_FILE = DriveHostMod.getConfigDir().resolve("last-create-folder.txt");

    private final Screen parent;
    private EditBox folderIdField;
    private EditBox passwordField;
    private EditBox confirmPasswordField;
    private Button createButton;
    private Button tunnelSetupButton;
    private String statusMessage = "";
    private String authUrl = null;
    private int statusColor = 0xFFFFFF;
    private boolean processing = false;
    private boolean tunnelFailed = false;

    public CreateWorldScreen(Screen parent) {
        super(Component.literal("Create DriveHost World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldWidth = 250;
        int startY = 60;
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

        // Confirm Password
        confirmPasswordField = new EditBox(this.font, centerX - fieldWidth / 2, startY + spacing * 2, fieldWidth, 20,
            Component.literal("Confirm Password"));
        confirmPasswordField.setMaxLength(128);
        confirmPasswordField.setHint(Component.literal("Confirm Password"));
        this.addRenderableWidget(confirmPasswordField);

        // Create button
        createButton = Button.builder(Component.literal("Create"), button -> {
            onCreate();
        })
        .bounds(centerX - 100, startY + spacing * 3 + 10, 200, 20)
        .build();
        this.addRenderableWidget(createButton);

        // Tunnel Setup button — always visible so users can configure before hosting
        tunnelSetupButton = Button.builder(
            Component.literal(tunnelFailed ? "Setup Tunnel (required to host)" : "Tunnel Setup"),
            button -> this.minecraft.setScreen(new TunnelSetupScreen(this))
        )
        .bounds(centerX - 100, startY + spacing * 4 + 10, 200, 20)
        .build();
        this.addRenderableWidget(tunnelSetupButton);

        // Back button
        this.addRenderableWidget(
            Button.builder(Component.literal("Back"), button -> {
                this.minecraft.setScreen(parent);
            })
            .bounds(centerX - 100, startY + spacing * 5 + 10, 200, 20)
            .build()
        );
    }

    private void onCreate() {
        if (processing) return;

        // Strip URL params — accept full share links or bare IDs
        String folderId = folderIdField.getValue().trim();
        int q = folderId.indexOf('?'); if (q != -1) folderId = folderId.substring(0, q);
        // Extract ID if user pasted full URL
        int slash = folderId.lastIndexOf('/');
        if (slash != -1) folderId = folderId.substring(slash + 1);
        folderId = folderId.trim();
        folderIdField.setValue(folderId); // show cleaned value

        String password = passwordField.getValue();
        String confirm = confirmPasswordField.getValue();

        // Validation
        if (folderId.isEmpty()) {
            setStatus("Please enter a Google Drive Folder ID", 0xFF5555);
            return;
        }
        if (password.isEmpty()) {
            setStatus("Please enter a password", 0xFF5555);
            return;
        }
        if (password.length() < 6) {
            setStatus("Password must be at least 6 characters", 0xFF5555);
            return;
        }
        if (!password.equals(confirm)) {
            setStatus("Passwords do not match", 0xFF5555);
            return;
        }

        // Save folder ID immediately so it survives a restart
        final String folderIdFinal = folderId;
        try { Files.writeString(SAVED_FOLDER_ID_FILE, folderIdFinal); } catch (Exception ignored) {}

        processing = true;
        createButton.active = false;
        authUrl = null;
        setStatus("Authenticating with Google Drive...", 0xFFFF55);

        // Run async to avoid freezing the UI
        Thread worker = new Thread(() -> {
            try {
                DriveService drive = new DriveService();
                drive.setAuthUrlCallback(url -> {
                    authUrl = url;
                    setStatus("Browser opened for sign-in. If nothing opened, copy the URL shown below.", 0xFFFF55);
                });
                drive.authenticate();
                authUrl = null; // clear once authed

                setStatus("Creating world...", 0xFFFF55);

                String playerName = minecraft.getUser().getName();
                TunnelManager tunnelManager = new TunnelManager();
                JoinController controller = new JoinController(drive, tunnelManager, playerName);

                JoinResult result = controller.createWorld(folderIdFinal, password);

                if (result.type() == JoinResult.Type.HOSTING && "FRESH".equals(result.address())) {
                    // Tunnel open + session written — open MC's world creation UI so user can pick settings
                    try { Files.deleteIfExists(SAVED_FOLDER_ID_FILE); } catch (Exception ignored) {}
                    tunnelFailed = false;
                    String tunnelAddr = controller.getHostController().getTunnelResult().publicAddress();
                    setStatus("Tunnel ready (" + tunnelAddr + ") — pick world settings...", 0x55FF55);
                    // Set pending flags before opening MC's CreateWorldScreen; SERVER_STARTED will fire after
                    DriveHostMod.pendingPublishOnLan = true;
                    DriveHostMod.pendingUploadController = controller;
                    DriveHostMod.activeHostController = controller;
                    minecraft.execute(() ->
                        net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(minecraft, CreateWorldScreen.this)
                    );
                } else if (result.type() == JoinResult.Type.TUNNEL_FAILED) {
                    tunnelFailed = true;
                    setStatus("Tunnel failed — click 'Setup Tunnel' to configure ngrok or Playit.gg.", 0xFF5555);
                    if (tunnelSetupButton != null) {
                        tunnelSetupButton.setMessage(Component.literal("Setup Tunnel (required to host)"));
                    }
                }
            } catch (Exception e) {
                DriveHostMod.LOGGER.error("Create world failed", e);
                setStatus("Error: " + e.getMessage(), 0xFF5555);
            } finally {
                processing = false;
                if (createButton != null) createButton.active = true;
            }
        }, "DriveHost-Create");
        worker.setDaemon(true);
        worker.start();
    }

    private void launchFreshWorld(dev.drivehost.hosting.JoinController controller) {
        // Delete any leftover DriveHostWorld saves slot
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

    private void setStatus(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // Instructions
        int instrY = this.height - 80;
        graphics.drawCenteredString(this.font,
            Component.literal("How to get a Drive Folder ID:"),
            this.width / 2, instrY, 0xFFFF55);
        graphics.drawCenteredString(this.font,
            Component.literal("1. Go to drive.google.com and create a new folder"),
            this.width / 2, instrY + 12, 0xAAAAAA);
        graphics.drawCenteredString(this.font,
            Component.literal("2. Right-click > Share > 'Anyone with link' can edit"),
            this.width / 2, instrY + 24, 0xAAAAAA);
        graphics.drawCenteredString(this.font,
            Component.literal("3. Copy the folder ID from the URL (after /folders/)"),
            this.width / 2, instrY + 36, 0xAAAAAA);
        graphics.drawCenteredString(this.font,
            Component.literal("4. Share the folder ID and password with your friends"),
            this.width / 2, instrY + 48, 0xAAAAAA);

        // Status message
        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal(statusMessage),
                this.width / 2, 48, statusColor);
        }
        // Auth URL — show when browser couldn't open
        if (authUrl != null) {
            graphics.drawCenteredString(this.font,
                Component.literal("Open in browser (Ctrl+C to copy from console):"),
                this.width / 2, this.height - 95, 0xFFFF55);
            // Truncate for display
            String display = authUrl.length() > 80 ? authUrl.substring(0, 77) + "..." : authUrl;
            graphics.drawCenteredString(this.font, Component.literal(display), this.width / 2, this.height - 83, 0xAAAAAA);
        }

        // Field labels
        int labelX = this.width / 2 - 125;
        graphics.drawString(this.font, "Drive Folder ID:", labelX, 50, 0xAAAAAA);
        graphics.drawString(this.font, "Password:", labelX, 78, 0xAAAAAA);
        graphics.drawString(this.font, "Confirm Password:", labelX, 106, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
