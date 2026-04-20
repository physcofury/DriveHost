package dev.drivehost.client.screen;

import dev.drivehost.tunnel.NgrokTunnel;
import dev.drivehost.tunnel.PlayitTunnel;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen for configuring tunnel credentials.
 * ngrok: paste authtoken
 * Playit.gg: automated claim flow (download binary, open browser for claim)
 */
public class TunnelSetupScreen extends Screen {

    private enum Mode { CHOOSE, NGROK, PLAYIT }

    private final Screen parent;
    private Mode mode = Mode.CHOOSE;

    private EditBox inputField;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;

    // Playit claim-flow state
    private String claimUrl = null;
    private boolean playitRunning = false;
    private Thread playitSetupThread = null;

    public TunnelSetupScreen(Screen parent) {
        super(Component.literal("Tunnel Setup"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int sy = this.height / 4;

        if (mode == Mode.CHOOSE) {
            initChooseMode(cx, sy);
        } else if (mode == Mode.NGROK) {
            initKeyMode(cx, sy, "ngrok", "Paste your ngrok authtoken here", 40);
        } else {
            initPlayitMode(cx, sy);
        }

        this.addRenderableWidget(
            Button.builder(Component.literal("Back"), btn -> {
                if (mode == Mode.CHOOSE) {
                    this.minecraft.setScreen(parent);
                } else {
                    if (mode == Mode.PLAYIT && playitSetupThread != null) {
                        playitSetupThread.interrupt();
                        playitSetupThread = null;
                        playitRunning = false;
                        claimUrl = null;
                    }
                    mode = Mode.CHOOSE;
                    statusMessage = "";
                    init();
                }
            })
            .bounds(cx - 100, this.height - 40, 200, 20)
            .build()
        );
    }

    private void initChooseMode(int cx, int sy) {
        NgrokTunnel ngrok = new NgrokTunnel();
        PlayitTunnel playit = new PlayitTunnel();
        boolean ngrokOk = ngrok.isSetupComplete();
        boolean playitOk = playit.isSetupComplete();

        String ngrokLabel = "ngrok" + (ngrokOk ? " [configured]" : "");
        String playitLabel = "Playit.gg" + (playitOk ? " [configured]" : "") + " (free)";

        this.addRenderableWidget(
            Button.builder(Component.literal(ngrokLabel), btn -> {
                mode = Mode.NGROK;
                statusMessage = "";
                init();
            })
            .bounds(cx - 110, sy + 20, 220, 20)
            .build()
        );
        this.addRenderableWidget(
            Button.builder(Component.literal(playitLabel), btn -> {
                mode = Mode.PLAYIT;
                statusMessage = "";
                init();
            })
            .bounds(cx - 110, sy + 50, 220, 20)
            .build()
        );

        if (ngrokOk || playitOk) {
            statusMessage = "Tunnel configured - you are ready to host!";
            statusColor = 0x55FF55;
        } else {
            statusMessage = "No tunnel configured yet. Choose one below.";
            statusColor = 0xFFFF55;
        }
    }

    private void initPlayitMode(int cx, int sy) {
        PlayitTunnel playit = new PlayitTunnel();

        if (playit.isSetupComplete() && !playitRunning) {
            // Fully configured
            statusMessage = "Playit.gg is configured and ready!";
            statusColor = 0x55FF55;
            this.addRenderableWidget(
                Button.builder(Component.literal("Reconfigure (run setup again)"), btn -> {
                    new PlayitTunnel().resetSetup();
                    claimUrl = null;
                    playitRunning = false;
                    statusMessage = "";
                    init();
                })
                .bounds(cx - 110, sy + 30, 220, 20)
                .build()
            );
            return;
        }

        if (playit.isAgentClaimed() && !playitRunning) {
            // Agent claimed but tunnel not yet confirmed in dashboard
            statusMessage = "Step 2: Create a Minecraft tunnel in the dashboard.";
            statusColor = 0xFFFF55;
            this.addRenderableWidget(
                Button.builder(Component.literal("Open playit.gg Tunnels page"), btn -> {
                    try {
                        new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler",
                            "https://playit.gg/account/tunnels").start();
                    } catch (Exception ignored) {}
                })
                .bounds(cx - 110, sy + 30, 220, 20)
                .build()
            );
            this.addRenderableWidget(
                Button.builder(Component.literal("Done - I created the tunnel!"), btn -> {
                    try {
                        new PlayitTunnel().confirmTunnelCreated();
                        statusMessage = "Setup complete! Ready to host.";
                        statusColor = 0x55FF55;
                        init();
                    } catch (Exception e) {
                        statusMessage = "Error: " + e.getMessage();
                        statusColor = 0xFF5555;
                    }
                })
                .bounds(cx - 110, sy + 60, 220, 20)
                .build()
            );
            return;
        }

        if (playitRunning) {
            if (claimUrl != null) {
                statusMessage = "Waiting for you to claim the agent in your browser...";
                statusColor = 0xFFFF55;
                this.addRenderableWidget(
                    Button.builder(Component.literal("Open claim URL in browser"), btn -> {
                        try {
                            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", claimUrl).start();
                        } catch (Exception ignored) {}
                    })
                    .bounds(cx - 110, sy + 50, 220, 20)
                    .build()
                );
            } else {
                statusMessage = "Downloading playit binary, please wait...";
                statusColor = 0xFFFF55;
            }
            return;
        }

        // Not running, not set up — show Start button
        statusMessage = "Click Start Setup to begin.";
        statusColor = 0xFFFFFF;
        this.addRenderableWidget(
            Button.builder(Component.literal("Start Setup"), btn -> {
                playitRunning = true;
                claimUrl = null;
                statusMessage = "Downloading playit binary...";
                statusColor = 0xFFFF55;
                if (this.minecraft != null) this.minecraft.execute(() -> init());

                PlayitTunnel tunnel = new PlayitTunnel();
                playitSetupThread = new Thread(() -> {
                    try {
                        tunnel.runClaimFlow(
                            status -> {
                                if (status != null && status.startsWith("https://")) {
                                    claimUrl = status;
                                    statusMessage = "Claim the agent in your browser!";
                                    statusColor = 0xFFFF55;
                                } else {
                                    statusMessage = status != null ? status : "";
                                    statusColor = 0xFFFF55;
                                }
                                if (minecraft != null) minecraft.execute(() -> init());
                            },
                            error -> {
                                playitRunning = false;
                                claimUrl = null;
                                if (error == null) {
                                    statusMessage = "Setup complete! Ready to host.";
                                    statusColor = 0x55FF55;
                                } else {
                                    statusMessage = "Setup failed: " + error;
                                    statusColor = 0xFF5555;
                                }
                                if (minecraft != null) minecraft.execute(() -> init());
                            }
                        );
                    } catch (Exception e) {
                        playitRunning = false;
                        statusMessage = "Setup failed: " + e.getMessage();
                        statusColor = 0xFF5555;
                        if (minecraft != null) minecraft.execute(() -> init());
                    }
                }, "DriveHost-PlayitSetup");
                playitSetupThread.setDaemon(true);
                playitSetupThread.start();
            })
            .bounds(cx - 100, sy + 30, 200, 20)
            .build()
        );
    }

    private void initKeyMode(int cx, int sy, String provider, String hint, int fieldOffset) {
        int fieldWidth = 300;
        inputField = new EditBox(this.font, cx - fieldWidth / 2, sy + fieldOffset, fieldWidth, 20,
            Component.literal(hint));
        inputField.setMaxLength(512);
        inputField.setHint(Component.literal(hint));

        // Pre-fill existing value
        if (mode == Mode.NGROK) {
            String saved = new NgrokTunnel().getAuthtoken();
            if (saved != null && !saved.isBlank()) {
                inputField.setValue(saved);
                statusMessage = "Already configured. Paste a new token to update.";
                statusColor = 0x55FF55;
            }
        }
        this.addRenderableWidget(inputField);

        this.addRenderableWidget(
            Button.builder(Component.literal("Save"), btn -> {
                String value = inputField.getValue().trim();
                if (value.isEmpty()) {
                    statusMessage = "Please paste your key first.";
                    statusColor = 0xFF5555;
                    return;
                }
                try {
                    new NgrokTunnel().saveAuthtoken(value);
                    statusMessage = provider + " key saved!";
                    statusColor = 0x55FF55;
                } catch (Exception e) {
                    statusMessage = "Failed to save: " + e.getMessage();
                    statusColor = 0xFF5555;
                }
            })
            .bounds(cx - 100, sy + fieldOffset + 30, 200, 20)
            .build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Open " + provider + " website"), btn -> {
                try {
                    new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler",
                        "https://dashboard.ngrok.com/get-started/your-authtoken").start();
                } catch (Exception ignored) {}
            })
            .bounds(cx - 110, sy + fieldOffset + 60, 220, 20)
            .build()
        );
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int sy = this.height / 4;

        graphics.drawCenteredString(this.font, this.title, cx, 15, 0xFFFFFF);

        if (mode == Mode.CHOOSE) {
            graphics.drawCenteredString(this.font,
                Component.literal("Choose a tunnel provider to let friends connect:"),
                cx, sy, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                Component.literal("Playit.gg is free. ngrok free tier requires credit card."),
                cx, sy + 10, 0xAAAAAA);

        } else if (mode == Mode.NGROK) {
            graphics.drawCenteredString(this.font,
                Component.literal("ngrok Setup"), cx, sy, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                Component.literal("1. Click 'Open ngrok website' and sign in"),
                cx, sy + 14, 0xAAAAAA);
            graphics.drawCenteredString(this.font,
                Component.literal("2. Copy your authtoken and paste it below"),
                cx, sy + 24, 0xAAAAAA);

        } else {
            graphics.drawCenteredString(this.font,
                Component.literal("Playit.gg Setup (free)"), cx, sy, 0xFFFFFF);
            PlayitTunnel pt = new PlayitTunnel();
            if (pt.isSetupComplete()) {
                // nothing extra — status message covers it
            } else if (pt.isAgentClaimed() && !playitRunning) {
                graphics.drawCenteredString(this.font,
                    Component.literal("Agent claimed! Now create a Minecraft Java tunnel:"),
                    cx, sy + 14, 0xAAAAAA);
                graphics.drawCenteredString(this.font,
                    Component.literal("1. Open the Tunnels page below"),
                    cx, sy + 24, 0xAAAAAA);
                graphics.drawCenteredString(this.font,
                    Component.literal("2. Click Add Tunnel -> Minecraft Java (TCP)"),
                    cx, sy + 34, 0xAAAAAA);
                graphics.drawCenteredString(this.font,
                    Component.literal("3. Click 'Done' below when finished"),
                    cx, sy + 44, 0xAAAAAA);
                graphics.drawCenteredString(this.font,
                    Component.literal("Note: free tier gives a random port - share the full address:port"),
                    cx, sy + 54, 0xFFAA00);
            } else if (!playitRunning) {
                graphics.drawCenteredString(this.font,
                    Component.literal("Click Start Setup - mod handles download and browser claim."),
                    cx, sy + 14, 0xAAAAAA);
                graphics.drawCenteredString(this.font,
                    Component.literal("Note: free tier gives a random port - share the full address:port"),
                    cx, sy + 24, 0xFFAA00);
            }
            if (claimUrl != null) {
                graphics.drawCenteredString(this.font,
                    Component.literal(claimUrl),
                    cx, sy + 70, 0x55FFFF);
            }
        }

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal(statusMessage),
                cx, this.height - 60, statusColor);
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (playitSetupThread != null) {
            playitSetupThread.interrupt();
        }
        this.minecraft.setScreen(parent);
    }
}