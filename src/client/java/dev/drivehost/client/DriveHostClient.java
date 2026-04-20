package dev.drivehost.client;

import dev.drivehost.DriveHostMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.world.level.GameType;

public class DriveHostClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Publish the integrated server to LAN on port 25565 after the player joins.
        // Must run on the client/render thread — same as vanilla "Open to LAN".
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (DriveHostMod.pendingPublishOnLan && client.getSingleplayerServer() != null) {
                DriveHostMod.pendingPublishOnLan = false;
                boolean ok = client.getSingleplayerServer().publishServer(GameType.SURVIVAL, false, 25565);
                DriveHostMod.LOGGER.info("DriveHost: published integrated server to LAN on port 25565 (ok={})", ok);
            }
        });

        DriveHostMod.LOGGER.info("DriveHost client initialized");
    }
}
