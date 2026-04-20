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

/**
 * Screen for creating a new DriveHost world.
 * Owner enters their Drive folder ID, sets a password, and uploads the initial world.
 */
public class CreateWorldScreen extends Screen {

    private final Screen parent;
    private EditBox folderIdField;
    private EditBox passwordField;
    private EditBox confirmPasswordField;
    private Button createButton;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;
    private boolean processing = false;

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
        folderIdField.setMaxLength(128);
        folderIdField.setHint(Component.literal("Google Drive Folder ID"));
        this.addRenderableWidget(folderIdField);

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

        // Back button
        this.addRenderableWidget(
            Button.builder(Component.literal("Back"), button -> {
                this.minecraft.setScreen(parent);
            })
            .bounds(centerX - 100, startY + spacing * 4 + 10, 200, 20)
            .build()
        );
    }

    private void onCreate() {
        if (processing) return;

        String folderId = folderIdField.getValue().trim();
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

        processing = true;
        createButton.active = false;
        setStatus("Authenticating with Google Drive...", 0xFFFF55);

        // Run async to avoid freezing the UI
        Thread worker = new Thread(() -> {
            try {
                DriveService drive = new DriveService();
                drive.authenticate();

                setStatus("Creating world...", 0xFFFF55);

                String playerName = minecraft.getUser().getName();
                TunnelManager tunnelManager = new TunnelManager();
                JoinController controller = new JoinController(drive, tunnelManager, playerName);

                JoinResult result = controller.createWorld(folderId, password);

                if (result.type() == JoinResult.Type.NEEDS_WORLD_SETUP) {
                    setStatus("World created! Setting up hosting...", 0x55FF55);

                    // For MVP: create an empty world directory as placeholder
                    // In a full implementation, this would use the Minecraft world generator
                    java.nio.file.Path tempWorld = DriveHostMod.getTempDir().resolve("new-world");
                    java.nio.file.Files.createDirectories(tempWorld);
                    // Create a minimal level.dat marker so it's recognized
                    java.nio.file.Files.writeString(
                        tempWorld.resolve("drivehost-marker.txt"),
                        "DriveHost world created by " + playerName
                    );

                    JoinResult hostResult = controller.uploadWorldAndHost(tempWorld);

                    if (hostResult.type() == JoinResult.Type.HOSTING) {
                        setStatus("Hosting at: " + controller.getHostController().getTunnelResult().publicAddress(), 0x55FF55);
                    } else {
                        setStatus("Failed to start hosting — tunnel unavailable. Try port-forwarding.", 0xFF5555);
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
