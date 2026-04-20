package dev.drivehost.client.screen;

import dev.drivehost.DriveHostMod;
import dev.drivehost.tunnel.NgrokTunnel;
import dev.drivehost.tunnel.PlayitTunnel;
import dev.drivehost.upnp.UPnP;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Main hub screen for DriveHost — offers Create World and Join World options.
 */
public class DriveHostMainScreen extends Screen {

    private final Screen parent;

    // Tunnel compatibility check state
    // null = still checking, true = ok, false = no tunnels configured
    private volatile Boolean tunnelReady = null;
    private String tunnelWarning = null;

    public DriveHostMainScreen(Screen parent) {
        super(Component.literal("DriveHost"));
        this.parent = parent;
        startTunnelCheck();
    }

    /** Runs UPnP probe + checks for configured tunnels in background */
    private void startTunnelCheck() {
        Thread t = new Thread(() -> {
            // Check if a tunnel is already configured (fast checks first)
            if (new NgrokTunnel().isSetupComplete() || new PlayitTunnel().isSetupComplete()) {
                tunnelReady = true;
                return;
            }
            // Try UPnP (~3s)
            UPnP.waitInit();
            if (UPnP.isUPnPAvailable()) {
                tunnelReady = true;
                return;
            }
            // Nothing works
            tunnelReady = false;
            tunnelWarning = "Your router may not support UPnP. Set up Playit.gg or ngrok via Tunnel Setup for reliable hosting.";
        }, "DriveHost-TunnelCheck");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 4 + 24;
        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 28;

        // Create World button
        this.addRenderableWidget(
            Button.builder(Component.literal("Create World"), button -> {
                this.minecraft.setScreen(new CreateWorldScreen(this));
            })
            .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
            .build()
        );

        // Join World button
        this.addRenderableWidget(
            Button.builder(Component.literal("Join World"), button -> {
                this.minecraft.setScreen(new JoinWorldScreen(this));
            })
            .bounds(centerX - buttonWidth / 2, startY + spacing, buttonWidth, buttonHeight)
            .build()
        );

        // Tunnel Setup button
        this.addRenderableWidget(
            Button.builder(Component.literal("Tunnel Setup (ngrok / Playit.gg)"), button -> {
                this.minecraft.setScreen(new TunnelSetupScreen(this));
            })
            .bounds(centerX - buttonWidth / 2, startY + spacing * 2, buttonWidth, buttonHeight)
            .build()
        );

        // Back button
        this.addRenderableWidget(
            Button.builder(Component.literal("Back"), button -> {
                this.minecraft.setScreen(parent);
            })
            .bounds(centerX - buttonWidth / 2, startY + spacing * 4, buttonWidth, buttonHeight)
            .build()
        );
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
            Component.literal("Decentralised P2P hosting via Google Drive"),
            this.width / 2, 36, 0xAAAAAA);

        // Tunnel compatibility banner
        if (tunnelReady == null) {
            graphics.drawCenteredString(this.font,
                Component.literal("Checking tunnel compatibility..."),
                this.width / 2, this.height - 30, 0x888888);
        } else if (Boolean.FALSE.equals(tunnelReady) && tunnelWarning != null) {
            // Word-wrap the warning across two lines if needed
            int maxW = this.width - 20;
            java.util.List<net.minecraft.util.FormattedCharSequence> lines =
                this.font.split(Component.literal("⚠ " + tunnelWarning), maxW);
            int y = this.height - 10 - lines.size() * 10;
            for (net.minecraft.util.FormattedCharSequence line : lines) {
                graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFAA00);
                y += 10;
            }
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
