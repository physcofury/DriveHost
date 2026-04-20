package dev.drivehost.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import dev.drivehost.DriveHostMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

/**
 * Google Drive API wrapper for DriveHost.
 * Handles OAuth2 authentication and file operations on a shared Drive folder.
 */
public class DriveService {

    private static final String APPLICATION_NAME = "DriveHost";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);

    private Drive driveService;
    private boolean authenticated = false;

    /**
     * Authenticate with Google Drive.
     * Opens the user's browser for OAuth consent on first use.
     * Subsequent calls use cached refresh tokens.
     */
    public void authenticate() throws IOException, GeneralSecurityException {
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = getCredentials(transport);
        driveService = new Drive.Builder(transport, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build();
        authenticated = true;
        DriveHostMod.LOGGER.info("Google Drive authenticated successfully");
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Upload a file to a Drive folder. Overwrites if a file with the same name exists.
     */
    public void uploadFile(String folderId, String fileName, byte[] data) throws IOException {
        ensureAuthenticated();

        // Check if file already exists
        String existingId = findFileId(folderId, fileName);

        File fileMetadata = new File();
        fileMetadata.setName(fileName);
        ByteArrayContent content = new ByteArrayContent("application/octet-stream", data);

        if (existingId != null) {
            // Update existing file
            driveService.files().update(existingId, fileMetadata, content).execute();
        } else {
            // Create new file
            fileMetadata.setParents(Collections.singletonList(folderId));
            driveService.files().create(fileMetadata, content)
                .setFields("id")
                .execute();
        }
    }

    /**
     * Download a file from a Drive folder by name.
     * Returns null if the file doesn't exist.
     */
    public byte[] downloadFile(String folderId, String fileName) throws IOException {
        ensureAuthenticated();

        String fileId = findFileId(folderId, fileName);
        if (fileId == null) return null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        driveService.files().get(fileId).executeMediaAndDownloadTo(out);
        return out.toByteArray();
    }

    /**
     * Get the etag of a file for optimistic concurrency control.
     */
    public String getFileEtag(String folderId, String fileName) throws IOException {
        ensureAuthenticated();

        String fileId = findFileId(folderId, fileName);
        if (fileId == null) return null;

        File file = driveService.files().get(fileId)
            .setFields("id,version")
            .execute();
        Long version = file.getVersion();
        return version != null ? version.toString() : null;
    }

    /**
     * Upload a file only if its etag hasn't changed (optimistic concurrency).
     * Returns true if the upload succeeded, false if the file was modified by someone else.
     */
    public boolean uploadIfUnchanged(String folderId, String fileName, byte[] data, String expectedEtag)
            throws IOException {
        ensureAuthenticated();

        String currentEtag = getFileEtag(folderId, fileName);
        if (currentEtag != null && !currentEtag.equals(expectedEtag)) {
            return false; // File was modified — caller should re-read
        }

        uploadFile(folderId, fileName, data);
        return true;
    }

    /**
     * List all files in a Drive folder.
     */
    public List<File> listFiles(String folderId) throws IOException {
        ensureAuthenticated();

        FileList result = driveService.files().list()
            .setQ("'" + folderId + "' in parents and trashed = false")
            .setFields("files(id, name, modifiedTime, etag)")
            .setPageSize(100)
            .execute();
        return result.getFiles();
    }

    /**
     * Check if a file exists in a folder.
     */
    public boolean fileExists(String folderId, String fileName) throws IOException {
        return findFileId(folderId, fileName) != null;
    }

    // --- Internal ---

    private String findFileId(String folderId, String fileName) throws IOException {
        // Sanitize fileName for Drive query — escape single quotes
        String safeName = fileName.replace("'", "\\'");
        FileList result = driveService.files().list()
            .setQ("'" + folderId + "' in parents and name = '" + safeName + "' and trashed = false")
            .setFields("files(id)")
            .setPageSize(1)
            .execute();
        List<File> files = result.getFiles();
        return (files != null && !files.isEmpty()) ? files.get(0).getId() : null;
    }

    private Credential getCredentials(NetHttpTransport transport) throws IOException {
        InputStream in = DriveService.class.getResourceAsStream("/credentials.json");
        if (in == null) {
            throw new IOException(
                "credentials.json not found. See README for Google Cloud Console setup.");
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                transport, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(new FileDataStoreFactory(
                DriveHostMod.getTokensDir().toFile()))
            .setAccessType("offline")
            .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
            .setPort(8888)
            .build();

        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private void ensureAuthenticated() {
        if (!authenticated || driveService == null) {
            throw new IllegalStateException("Not authenticated with Google Drive. Call authenticate() first.");
        }
    }
}
