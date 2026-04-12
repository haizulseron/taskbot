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
        String safeQuery = query.replace("'", "\\'");
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

    public DriveFileResult downloadFile(String fileId) throws Exception {
        File meta = drive.files().get(fileId).setFields("id, name, mimeType").execute();
        String mimeType = meta.getMimeType();
        String name     = meta.getName();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Google Workspace files must be exported, not downloaded directly
        if (mimeType != null && mimeType.startsWith("application/vnd.google-apps.")) {
            String exportMime = switch (mimeType) {
                case "application/vnd.google-apps.spreadsheet" ->
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                default -> "application/pdf";
            };
            drive.files().export(fileId, exportMime).executeMediaAndDownloadTo(out);
            if (!name.contains(".")) {
                name += exportMime.contains("sheet") ? ".xlsx" : ".pdf";
            }
        } else {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
        }

        return new DriveFileResult(name, mimeType, out.toByteArray());
    }
}
