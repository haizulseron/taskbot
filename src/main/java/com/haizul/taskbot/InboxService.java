package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class InboxService {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL_CLASSIFY = "claude-haiku-4-5-20251001";
    private static final String MODEL_VISION   = "claude-sonnet-4-6";

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            You are a personal assistant. Given the following content, decide: is this an actionable \
            task that someone needs to DO, or is it just information/a note? \
            Return a JSON object with fields: { category, title, body, due_date, priority }. \
            - category: "TASK" if it contains something actionable (a todo, errand, deadline, \
              assignment, thing to buy, thing to fix, appointment to make, etc). "NOTE" for everything else. \
            - title: short summary (max 80 chars) \
            - body: extra context or details (null if none) \
            - due_date: ISO date yyyy-MM-dd if a date is mentioned, null otherwise \
            - priority: HIGH, MEDIUM, or LOW (only if TASK, null otherwise) \
            Only return JSON, no markdown fences.""";

    private static final String VISION_SYSTEM_PROMPT =
            "You extract information from images. Return all visible text and key data points.";

    private static final int PDF_MAX_CHARS = 8000;

    public record ClassifiedContent(String category, String title, String body,
                                    String dueDate, String priority) {}

    private final String claudeApiKey;
    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public InboxService(String claudeApiKey, String botToken) {
        this.claudeApiKey = claudeApiKey;
        this.botToken     = botToken;
        this.httpClient   = HttpClient.newHttpClient();
        this.mapper       = new ObjectMapper();
    }

    // ── Public methods ───────────────────────────────────────────────────────

    /**
     * Classify free-form content into one of the supported categories via Claude.
     * Returns null if parsing fails (caller should ask the user to choose manually).
     */
    public ClassifiedContent classifyContent(String content) {
        try {
            List<Map<String, Object>> messageContent = List.of(
                    Map.of("type", "text", "text", content)
            );

            String raw = callClaude(CLASSIFY_SYSTEM_PROMPT, messageContent, MODEL_CLASSIFY, 512);
            if (raw == null) return null;

            String json = stripFences(raw).trim();
            JsonNode node = mapper.readTree(json);

            return new ClassifiedContent(
                    node.path("category").asText(null),
                    node.path("title").asText(null),
                    node.path("body").asText(null),
                    node.path("due_date").isNull() ? null : node.path("due_date").asText(null),
                    node.path("priority").isNull() ? null : node.path("priority").asText(null)
            );
        } catch (Exception e) {
            System.err.println("[InboxService] classifyContent failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Use Claude vision (claude-sonnet-4-6) to extract text and key data from an image.
     */
    public String extractTextFromImage(byte[] imageBytes) {
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            List<Map<String, Object>> messageContent = List.of(
                    Map.of("type", "image",
                            "source", Map.of(
                                    "type", "base64",
                                    "media_type", "image/jpeg",
                                    "data", base64)),
                    Map.of("type", "text",
                            "text", "Extract all text, data, and key information from this image. Be thorough.")
            );

            String result = callClaude(VISION_SYSTEM_PROMPT, messageContent, MODEL_VISION, 4096);
            return result != null ? result : "";
        } catch (Exception e) {
            System.err.println("[InboxService] extractTextFromImage failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Extract text from a PDF using Apache PDFBox.
     * Truncates to {@value PDF_MAX_CHARS} characters to fit Claude context.
     */
    public String extractTextFromPdf(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text.length() > PDF_MAX_CHARS) {
                text = text.substring(0, PDF_MAX_CHARS);
            }
            return text;
        } catch (Exception e) {
            System.err.println("[InboxService] extractTextFromPdf failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Download a file from Telegram servers by its file ID.
     * Returns the raw bytes, or null on failure.
     */
    public byte[] downloadTelegramFile(String fileId) {
        try {
            // Step 1: resolve the file path via Telegram getFile API
            String getFileUrl = "https://api.telegram.org/bot" + botToken
                    + "/getFile?file_id=" + fileId;

            HttpRequest pathRequest = HttpRequest.newBuilder()
                    .uri(URI.create(getFileUrl))
                    .GET()
                    .build();

            HttpResponse<String> pathResponse =
                    httpClient.send(pathRequest, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(pathResponse.body());
            String filePath = root.path("result").path("file_path").asText(null);
            if (filePath == null) {
                System.err.println("[InboxService] downloadTelegramFile: file_path not found");
                return null;
            }

            // Step 2: download the actual file bytes
            String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .GET()
                    .build();

            HttpResponse<byte[]> downloadResponse =
                    httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());

            return downloadResponse.body();
        } catch (Exception e) {
            System.err.println("[InboxService] downloadTelegramFile failed: " + e.getMessage());
            return null;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Generic Claude API caller. Builds the request body with model, max_tokens,
     * system prompt, and a single user message whose content is the provided list.
     * Returns the text from content[0].text of the response.
     */
    private String callClaude(String systemPrompt, List<Map<String, Object>> messageContent,
                              String model, int maxTokens) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("system", systemPrompt);

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.set("content", mapper.valueToTree(messageContent));

            String json = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLAUDE_API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", claudeApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(response.body());
            return root.path("content").path(0).path("text").asText(null);
        } catch (Exception e) {
            System.err.println("[InboxService] callClaude failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Strips markdown code fences (```json ... ``` or ``` ... ```) from Claude responses.
     */
    private String stripFences(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            // Remove opening fence (possibly with language tag like ```json)
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            // Remove closing fence
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.trim();
        }
        return trimmed;
    }
}
