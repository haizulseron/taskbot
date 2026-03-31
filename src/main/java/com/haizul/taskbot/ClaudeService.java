package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ClaudeService {

    public record ParsedTask(
            String type,       // "task", "reminder", or "unknown"
            String title,
            LocalDateTime dueAt,
            Task.Priority priority,
            String category,
            Task.Recurrence recurrence,
            String clarification  // message to send back if type is "unknown"
    ) {}

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            You are a task/reminder parser for a Telegram bot. The user sends a message in plain English.
            Extract task details and respond ONLY with a JSON object — no explanation, no markdown, no backticks.

            JSON fields:
            - type: "task" if the user wants to add a task or reminder, "unknown" if the message is unclear or unrelated
            - title: short clear task title (string)
            - due_date: ISO-8601 datetime string like "2026-04-01T20:00:00", or null if not specified
            - priority: "high", "medium", or "low" (default "medium")
            - category: a category slug like "school", "finance", "work", or "none" if not mentioned
            - recurrence: "none", "daily", "weekly", or "monthly"
            - clarification: if type is "unknown", a short friendly message asking the user to clarify; otherwise null

            Rules:
            - Infer urgency from words like "ASAP", "urgent", "important" → high priority
            - "soon", "eventually", "whenever" → low priority
            - Recognise natural date phrases: "tonight", "this evening" = today ~8pm, "next week" = 7 days from now
            - If the user seems to just be chatting (not adding a task), set type to "unknown"
            """;

    private final String apiKey;
    private final ZoneId zoneId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeService(String apiKey, ZoneId zoneId) {
        this.apiKey       = apiKey;
        this.zoneId       = zoneId;
        this.httpClient   = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public ParsedTask parse(String userMessage) {
        String now = LocalDateTime.now(zoneId)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        String userContent = "Today is " + now + " (" + zoneId + ").\n\nUser message: " + userMessage;

        try {
            String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", MODEL);
                put("max_tokens", 256);
                put("system", SYSTEM_PROMPT);
                put("messages", java.util.List.of(
                        java.util.Map.of("role", "user", "content", userContent)
                ));
            }});

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Claude API error " + response.statusCode() + ": " + response.body());
                return unknown("Sorry, I couldn't reach the AI parser right now. Use /add to add tasks manually.");
            }

            JsonNode root    = objectMapper.readTree(response.body());
            String rawJson   = root.path("content").get(0).path("text").asText().trim();
            JsonNode parsed  = objectMapper.readTree(rawJson);

            String type          = parsed.path("type").asText("unknown");
            String title         = parsed.path("title").asText("").trim();
            String dueDateStr    = parsed.path("due_date").asText(null);
            String priorityStr   = parsed.path("priority").asText("medium");
            String categoryStr   = parsed.path("category").asText("none");
            String recurrenceStr = parsed.path("recurrence").asText("none");
            String clarification = parsed.path("clarification").asText(null);

            if (!"task".equals(type)) {
                String msg = (clarification != null && !clarification.isBlank())
                        ? clarification
                        : "I'm not sure what you'd like to do. Use /add to add a task, or /help for all commands.";
                return unknown(msg);
            }

            LocalDateTime dueAt = null;
            if (dueDateStr != null && !dueDateStr.isBlank() && !dueDateStr.equals("null")) {
                try {
                    dueAt = LocalDateTime.parse(dueDateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception e) {
                    // non-fatal — leave dueAt as null
                }
            }

            Task.Priority priority    = Task.Priority.fromText(priorityStr);
            Task.Recurrence recurrence = Task.Recurrence.fromText(recurrenceStr);
            String category           = (categoryStr == null || categoryStr.isBlank()) ? "none" : categoryStr;

            return new ParsedTask("task", title, dueAt, priority, category, recurrence, null);

        } catch (Exception e) {
            System.err.println("ClaudeService.parse error: " + e.getMessage());
            return unknown("Something went wrong with the AI parser. Use /add to add tasks manually.");
        }
    }

    private static ParsedTask unknown(String clarification) {
        return new ParsedTask("unknown", null, null, null, null, null, clarification);
    }
}
