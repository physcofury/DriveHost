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

/**
 * Screen for joining an existing DriveHost world.
 * Player enters the Drive folder ID and world password.
 */
public class JoinWorldScreen extends Screen {

    private final Screen parent;
    private EditBox folderIdField;
    private EditBox passwordField;
    private Button joinButton;
    private String statusMessage = "";
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
        folderIdField.setMaxLength(128);
        folderIdField.setHint(Component.literal("Google Drive Folder ID"));
        this.addRenderableWidget(folderIdField);

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

        String folderId = folderIdField.getValue().trim();
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

        processing = true;
        joinButton.active = false;
        setStatus("Authenticating with Google Drive...", 0xFFFF55);

        Thread worker = new Thread(() -> {
            try {
                DriveService drive = new DriveService();
                drive.authenticate();

                setStatus("Verifying password...", 0xFFFF55);

                String playerName = minecraft.getUser().getName();
                TunnelManager tunnelManager = new TunnelManager();
                JoinController controller = new JoinController(drive, tunnelManager, playerName);

                JoinResult result = controller.joinWorld(folderId, password);

                switch (result.type()) {
                    case CONNECT -> {
                        setStatus("Connecting to host at " + result.address() + "...", 0x55FF55);
                        // Connect to the server on the main thread
                        String address = result.address();
                        minecraft.execute(() -> connectToServer(address));
                    }
                    case HOSTING -> {
                        setStatus("You are now hosting! Address: " +
                            controller.getHostController().getTunnelResult().publicAddress(), 0x55FF55);
                        // Load the world as integrated server — for MVP, connect to self
                        String selfAddress = controller.getHostController().getTunnelResult().publicAddress();
                        minecraft.execute(() -> connectToServer(selfAddress));
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
