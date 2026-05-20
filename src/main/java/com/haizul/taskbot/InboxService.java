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
    private static final String MODEL_CLASSIFY = ModelRouting.REFLECTIONS;
    private static final String MODEL_VISION   = ModelRouting.REASONING;

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            You are a personal assistant. Given the following content, classify it into ONE of three categories \
            by trying them in strict priority order: CALENDAR → TASK → NOTE. \
            Return a JSON object with fields: { category, title, body, start_datetime, end_datetime, due_date, priority }. \
            \
            CATEGORY RULES — try them in this order, pick the FIRST one that fits: \
            1. CALENDAR — a scheduled event at a SPECIFIC date AND time: meetings, appointments, classes, \
               flights, interviews, gatherings at a clock time (e.g. "lunch with mom tomorrow 1pm", \
               "dentist 15 may 3:30pm", "flight to KL wed 8am"). Must have an explicit time-of-day or datetime. \
               All-day events (like "mom's birthday 15 may") are CALENDAR only if clearly an event, not a todo. \
            2. TASK — actionable work the user needs to DO: errands, assignments, deadlines, things to buy, \
               things to fix, calls to make, reminders to do something. May have a due date (no specific time \
               required). Examples: "buy milk", "finish report by friday", "call plumber", "CS2030 assignment \
               due next tuesday". If it's work to be completed rather than attended, it's a TASK. \
            3. NOTE — pure information, reference material, journal-style thought, or anything not actionable \
               and not a scheduled event. If it doesn't fit CALENDAR or TASK, it's a NOTE. \
            \
            FIELD RULES: \
            - category: exactly "CALENDAR", "TASK", or "NOTE" \
            - title: short summary (max 80 chars) \
            - body: extra context/details (null if none) \
            - start_datetime: ISO yyyy-MM-dd'T'HH:mm (e.g. "2026-04-21T13:00") if CALENDAR with a time, \
              or yyyy-MM-dd if CALENDAR all-day. null for TASK and NOTE. \
            - end_datetime: ISO yyyy-MM-dd'T'HH:mm if CALENDAR and end is known, else null \
            - due_date: ISO yyyy-MM-dd if TASK with a deadline, else null \
            - priority: HIGH, MEDIUM, or LOW — only for TASK, null otherwise \
            \
            Today's date context is provided with the content. Use it to resolve relative dates \
            like "tomorrow", "next friday", etc. \
            \
            Only return JSON, no markdown fences, no commentary.""";

    private static final String VISION_SYSTEM_PROMPT =
            "You extract information from images. Return all visible text and key data points.";

    private static final int PDF_MAX_CHARS = 8000;

    public record ClassifiedContent(String category, String title, String body,
                                    String startDatetime, String endDatetime,
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
            java.time.ZoneId sg = java.time.ZoneId.of("Asia/Singapore");
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(sg);
            String contextPrefix = "Today is " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd"))
                    + " (current time " + now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    + " Asia/Singapore).\n\nContent to classify:\n";

            List<Map<String, Object>> messageContent = List.of(
                    Map.of("type", "text", "text", contextPrefix + content)
            );

            String raw = callClaude(CLASSIFY_SYSTEM_PROMPT, messageContent, MODEL_CLASSIFY, 512);
            if (raw == null) return null;

            String json = stripFences(raw).trim();
            JsonNode node = mapper.readTree(json);

            return new ClassifiedContent(
                    node.path("category").asText(null),
                    node.path("title").asText(null),
                    node.path("body").asText(null),
                    node.path("start_datetime").isNull() ? null : node.path("start_datetime").asText(null),
                    node.path("end_datetime").isNull() ? null : node.path("end_datetime").asText(null),
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
