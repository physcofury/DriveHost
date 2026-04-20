package dev.drivehost;

import net.fabricmc.api.ModInitializer;
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
