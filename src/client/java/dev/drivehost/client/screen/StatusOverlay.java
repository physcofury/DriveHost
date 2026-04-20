package dev.drivehost.client.screen;

import dev.drivehost.hosting.JoinController;
import dev.drivehost.hosting.SessionData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD overlay showing DriveHost session status while in-game.
 * Renders in the top-left corner: host name, player count, hosting badge.
 */
public class StatusOverlay {

    private static JoinController activeController;

    public static void setActiveController(JoinController controller) {
        activeController = controller;
    }

    public static void render(GuiGraphics graphics) {
        if (activeController == null || !activeController.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        SessionData session = activeController.getLastSession();
        if (session == null) return;

        int x = 4;
        int y = 4;
        int lineHeight = 10;
        int bgColor = 0x80000000; // Semi-transparent black

        // Background
        int bgWidth = 140;
        int bgHeight = lineHeight * 3 + 8;
        graphics.fill(x - 2, y - 2, x + bgWidth, y + bgHeight, bgColor);

        // Host name
        String hostText = "Host: " + (session.getHostPlayerName() != null ? session.getHostPlayerName() : "None");
        graphics.drawString(mc.font, hostText, x, y, 0xFFFFFF, false);

        // Player count
        int playerCount = session.getActivePlayers() != null ? session.getActivePlayers().size() : 0;
        graphics.drawString(mc.font, "Players: " + playerCount, x, y + lineHeight, 0xAAAAAA, false);

        // Hosting badge
        if (activeController.isHosting()) {
            graphics.drawString(mc.font, "[HOSTING]", x, y + lineHeight * 2, 0x55FF55, false);
        } else {
            graphics.drawString(mc.font, "[CONNECTED]", x, y + lineHeight * 2, 0x55FFFF, false);
        }
    }
}
