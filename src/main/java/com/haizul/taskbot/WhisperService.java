package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class WhisperService {
    private static final String WHISPER_URL   = "https://api.openai.com/v1/audio/transcriptions";
    private static final String TELEGRAM_FILE = "https://api.telegram.org/file/bot%s/%s";

    private final String openAiKey;
    private final String botToken;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public WhisperService(String openAiKey, String botToken) {
        this.openAiKey = openAiKey;
        this.botToken  = botToken;
        this.http      = HttpClient.newHttpClient();
        this.mapper    = new ObjectMapper();
    }

    /**
     * Downloads a Telegram voice file by file_id and transcribes it via Whisper.
     * Returns the transcription text, or null if anything fails.
     */
    public String transcribe(String fileId) {
        try {
            // Step 1 — resolve file_id to a download path
            String getFileUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;
            HttpRequest req1 = HttpRequest.newBuilder().uri(URI.create(getFileUrl)).GET().build();
            HttpResponse<String> res1 = http.send(req1, HttpResponse.BodyHandlers.ofString());

            JsonNode fileJson = mapper.readTree(res1.body());
            String filePath = fileJson.path("result").path("file_path").asText(null);
            if (filePath == null || filePath.isBlank()) {
                System.err.println("WhisperService: could not resolve file path");
                return null;
            }

            // Step 2 — download the audio bytes
            String downloadUrl = String.format(TELEGRAM_FILE, botToken, filePath);
            HttpRequest req2 = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build();
            HttpResponse<byte[]> res2 = http.send(req2, HttpResponse.BodyHandlers.ofByteArray());
            byte[] audioBytes = res2.body();

            // Step 3 — multipart POST to Whisper
            String boundary = "----TaskBotBoundary" + System.currentTimeMillis();
            byte[] multipart = buildMultipart(boundary, audioBytes, "voice.ogg");

            HttpRequest req3 = HttpRequest.newBuilder()
                    .uri(URI.create(WHISPER_URL))
                    .header("Authorization", "Bearer " + openAiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                    .build();

            HttpResponse<String> res3 = http.send(req3, HttpResponse.BodyHandlers.ofString());

            if (res3.statusCode() != 200) {
                System.err.println("Whisper API error " + res3.statusCode() + ": " + res3.body());
                return null;
            }

            JsonNode whisperJson = mapper.readTree(res3.body());
            return whisperJson.path("text").asText(null);

        } catch (Exception e) {
            System.err.println("WhisperService.transcribe error: " + e.getMessage());
            return null;
        }
    }

    private byte[] buildMultipart(String boundary, byte[] audioBytes, String filename) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";

        // model field
        writeString(out, "--" + boundary + crlf);
        writeString(out, "Content-Disposition: form-data; name=\"model\"" + crlf + crlf);
        writeString(out, "whisper-1" + crlf);

        // language hint — Singapore English
        writeString(out, "--" + boundary + crlf);
        writeString(out, "Content-Disposition: form-data; name=\"language\"" + crlf + crlf);
        writeString(out, "en" + crlf);

        // audio file
        writeString(out, "--" + boundary + crlf);
        writeString(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + crlf);
        writeString(out, "Content-Type: audio/ogg" + crlf + crlf);
        out.write(audioBytes);
        writeString(out, crlf);

        writeString(out, "--" + boundary + "--" + crlf);
        return out.toByteArray();
    }

    private void writeString(ByteArrayOutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
