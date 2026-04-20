package dev.drivehost.hosting;

import dev.drivehost.DriveHostMod;
import dev.drivehost.crypto.CryptoManager;
import dev.drivehost.drive.DriveService;

import javax.crypto.SecretKey;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages world files: download/decrypt from Drive, encrypt/upload to Drive.
 * The decrypted world only ever exists in a temp directory during an active session.
 */
public class WorldManager {

    private static final String WORLD_FILE = "world.zip.enc";

    private final DriveService drive;
    private final String folderId;
    private final SecretKey key;

    private Path currentWorldDir;

    public WorldManager(DriveService drive, String folderId, SecretKey key) {
        this.drive = drive;
        this.folderId = folderId;
        this.key = key;
    }

    /**
     * Download the world from Drive, decrypt it, and unzip to a temp directory.
     * Returns the path to the unzipped world directory.
     */
    public Path downloadAndDecryptWorld() throws Exception {
        DriveHostMod.LOGGER.info("Downloading world from Drive...");

        byte[] encrypted = drive.downloadFile(folderId, WORLD_FILE);
        if (encrypted == null) {
            throw new IOException("World file not found on Drive");
        }

        byte[] zipped = CryptoManager.decrypt(encrypted, key);

        // Create temp directory for this world
        Path tempDir = DriveHostMod.getTempDir().resolve("world-" + System.currentTimeMillis());
        Files.createDirectories(tempDir);

        unzip(zipped, tempDir);
        currentWorldDir = tempDir;

        DriveHostMod.LOGGER.info("World downloaded and decrypted to {}", tempDir);
        return tempDir;
    }

    /**
     * Zip the current world directory, encrypt it, and upload to Drive.
     */
    public void encryptAndUploadWorld(Path worldDir) throws Exception {
        DriveHostMod.LOGGER.info("Uploading world to Drive...");

        byte[] zipped = zipDirectory(worldDir);
        byte[] encrypted = CryptoManager.encrypt(zipped, key);

        drive.uploadFile(folderId, WORLD_FILE, encrypted);

        DriveHostMod.LOGGER.info("World encrypted and uploaded ({} bytes)", encrypted.length);
    }

    /**
     * Upload a world directory as the initial world for a new DriveHost session.
     */
    public void uploadInitialWorld(Path worldDir) throws Exception {
        encryptAndUploadWorld(worldDir);
    }

    /**
     * Wipe all temp files (decrypted worlds).
     */
    public void cleanupTempFiles() {
        if (currentWorldDir != null && Files.exists(currentWorldDir)) {
            deleteDirectory(currentWorldDir);
            currentWorldDir = null;
        }
    }

    public Path getCurrentWorldDir() {
        return currentWorldDir;
    }

    public void setCurrentWorldDir(Path dir) {
        this.currentWorldDir = dir;
    }

    // --- Zip/Unzip ---

    private void unzip(byte[] zipData, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // Zip slip protection
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry is outside target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private byte[] zipDirectory(Path sourceDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                    // Skip files locked by the running MC server (e.g. session.lock, open region files)
                    try {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        DriveHostMod.LOGGER.warn("[DriveHost] Skipping locked file during zip: {} ({})", entryName, e.getMessage());
                        try { zos.closeEntry(); } catch (IOException ignored) {}
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourceDir)) {
                        String entryName = sourceDir.relativize(dir).toString().replace('\\', '/') + "/";
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return baos.toByteArray();
    }

    private void deleteDirectory(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        DriveHostMod.LOGGER.warn("Failed to delete: {}", p, e);
                    }
                });
        } catch (IOException e) {
            DriveHostMod.LOGGER.warn("Failed to walk directory for deletion: {}", dir, e);
        }
    }
}
