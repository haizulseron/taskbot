package com.haizul.taskbot;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class DriveService {

    private final Drive drive;

    public DriveService(GoogleAuthService auth) throws Exception {
        this.drive = new Drive.Builder(
                auth.getHttpTransport(),
                auth.getJsonFactory(),
                auth.getHttpCredentials())
                .setApplicationName("TaskBot")
                .build();
    }

    public record FileSummary(String id, String name, String mimeType, String modifiedTime, Long size) {}
    public record DriveFileResult(String name, String mimeType, byte[] data) {}

    public List<FileSummary> searchFiles(String query) throws Exception {
        String safeQuery = query.replace("\\", "\\\\").replace("'", "\\'");
        FileList result = drive.files().list()
                .setQ("name contains '" + safeQuery + "' and trashed=false")
                .setFields("files(id, name, mimeType, modifiedTime, size)")
                .setPageSize(10)
                .execute();

        List<FileSummary> files = new ArrayList<>();
        if (result.getFiles() == null) return files;
        for (File f : result.getFiles()) {
            files.add(new FileSummary(
                    f.getId(), f.getName(), f.getMimeType(),
                    f.getModifiedTime() != null ? f.getModifiedTime().toString() : "",
                    f.getSize()));
        }
        return files;
    }

    private static final long MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024; // 50 MB safety limit

    public DriveFileResult downloadFile(String fileId) throws Exception {
        File meta = drive.files().get(fileId).setFields("id, name, mimeType, size").execute();
        String mimeType = meta.getMimeType();
        String name     = meta.getName();

        // Guard against OOM — reject excessively large files
        if (meta.getSize() != null && meta.getSize() > MAX_DOWNLOAD_BYTES) {
            throw new RuntimeException("File \"" + name + "\" is too large ("
                    + (meta.getSize() / (1024 * 1024)) + " MB). Max is 50 MB.");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String resultMime = mimeType;

        // Google Workspace files must be exported, not downloaded directly
        if (mimeType != null && mimeType.startsWith("application/vnd.google-apps.")) {
            String exportMime = switch (mimeType) {
                case "application/vnd.google-apps.spreadsheet" ->
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "application/vnd.google-apps.presentation" ->
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "application/vnd.google-apps.document" ->
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                default -> "application/pdf";
            };
            drive.files().export(fileId, exportMime).executeMediaAndDownloadTo(out);
            resultMime = exportMime; // Return the actual exported type, not the Workspace type
            // Append correct extension if the file name has none
            String ext = exportMime.contains("spreadsheet") ? ".xlsx"
                       : exportMime.contains("presentation") ? ".pptx"
                       : exportMime.contains("wordprocessing") ? ".docx"
                       : ".pdf";
            if (!name.contains(".")) name += ext;
        } else {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
        }

        return new DriveFileResult(name, resultMime, out.toByteArray());
    }
}
