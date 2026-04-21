package dev.drivehost;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class DriveHostMod implements ModInitializer {

    public static final String MOD_ID = "drivehost";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Set to true before loading an integrated server so SERVER_STARTED publishes it to LAN on port 25565. */
    public static volatile boolean pendingPublishOnLan = false;

    /** Set before createFreshLevel() so SERVER_STARTED uploads the generated world to Drive. */
    public static volatile dev.drivehost.hosting.JoinController pendingUploadController = null;

    /** The active host controller for the current session — used to shut down tunnel on SERVER_STOPPING. */
    public static volatile dev.drivehost.hosting.JoinController activeHostController = null;

    private static Path configDir;

    @Override
    public void onInitialize() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(getTempDir());
            Files.createDirectories(getTokensDir());
        } catch (IOException e) {
            LOGGER.error("Failed to create DriveHost config directories", e);
        }

        // Crash recovery: wipe leftover temp files from previous sessions
        cleanupTempFiles();

        // Upload the world to Drive once the integrated server has fully started.
        // publishServer is intentionally NOT called here — it must run on the client thread
        // (see DriveHostClient.onInitializeClient via ClientPlayConnectionEvents.JOIN).
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            dev.drivehost.hosting.JoinController uploadCtrl = pendingUploadController;
            if (uploadCtrl != null) {
                pendingUploadController = null;
                java.nio.file.Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize();
                Thread t = new Thread(() -> uploadCtrl.uploadWorldAfterCreate(worldDir), "DriveHost-InitialUpload");
                t.setDaemon(true);
                t.start();
            }
        });

        // When the integrated server stops (player left world), shut down the tunnel and do a final upload.
        // SERVER_STOPPED fires after MC has fully saved playerdata, chunks, and released all file locks.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            dev.drivehost.hosting.JoinController ctrl = activeHostController;
            if (ctrl != null) {
                activeHostController = null;
                pendingPublishOnLan = false;
                Thread t = new Thread(ctrl::disconnect, "DriveHost-Shutdown");
                t.setDaemon(false); // non-daemon so JVM doesn't kill it mid-upload
                t.start();
            }
        });

        LOGGER.info("DriveHost mod initialized");
    }

    public static Path getConfigDir() {
        return configDir;
    }

    public static Path getTempDir() {
        return configDir.resolve("temp");
    }

    public static Path getTokensDir() {
        return configDir.resolve("tokens");
    }

    /**
     * Wipe the temp directory on startup to recover from crashes
     * that may have left decrypted world files on disk.
     */
    private void cleanupTempFiles() {
        Path tempDir = getTempDir();
        if (!Files.exists(tempDir)) return;

        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                .filter(p -> !p.equals(tempDir))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        LOGGER.warn("Failed to clean temp file: {}", p, e);
                    }
                });
            LOGGER.info("Cleaned up DriveHost temp directory");
        } catch (IOException e) {
            LOGGER.warn("Failed to walk temp directory for cleanup", e);
        }
    }
}
