package dev.drivehost.client;

import net.fabricmc.api.ClientModInitializer;
import dev.drivehost.DriveHostMod;

public class DriveHostClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        DriveHostMod.LOGGER.info("DriveHost client initialized");
    }
}
