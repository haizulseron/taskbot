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
            String type,
            String title,
            LocalDateTime dueAt,
            Task.Priority priority,
            String category,
            Task.Recurrence recurrence,
            String targetTitle,
            int snoozeHours,
            String clarification
    ) {}

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            You are a smart task bot assistant. The user sends casual natural language messages.
            Detect intent and respond ONLY with a JSON object — no explanation, no markdown, no backticks.

            Intent types and when to use them:
            - "task"          : user wants to add a new task or reminder
            - "list_tasks"    : user wants to see all active tasks ("show my tasks", "what do I have", "open tasks")
            - "list_today"    : user wants tasks due today ("what's due today", "today's tasks")
            - "list_overdue"  : user wants overdue tasks ("what's overdue", "what am I late on")
            - "list_stale"    : user wants stale tasks ("what have I been ignoring", "stale tasks")
            - "list_done"     : user wants completed tasks ("show done", "what did I finish")
            - "list_categories": user wants to see their categories ("show categories", "my categories")
            - "review"        : user wants a summary overview ("review", "how am I doing", "give me a summary")
            - "mark_done"     : user wants to mark a specific task done ("mark gym as done", "finish the report task", "I did X")
            - "mark_all_done" : user wants ALL tasks marked done ("clear everything", "mark all done", "reset my tasks", "I'm done with everything")
            - "delete_all_done": user wants to delete/clear all completed tasks ("clear my done tasks", "wipe completed", "delete finished tasks")
            - "delete_task"   : user wants to delete a specific task ("delete gym task", "remove the report", "drop X")
            - "snooze_task"   : user wants to snooze a specific task ("snooze the report by 2 hours", "push gym to later")
            - "unknown"       : message is unclear, a greeting, or unrelated to tasks

            JSON fields:
            - type: one of the intent types above (string, required)
            - title: for "task" — the task title (string or null)
            - due_date: for "task" — ISO-8601 datetime like "2026-04-01T20:00:00", or null
            - priority: for "task" — "high", "medium", or "low" (default "medium")
            - category: for "task" — slug like "school", "finance", "work", or "none"
            - recurrence: for "task" — "none", "daily", "weekly", or "monthly"
            - target_title: for mark_done, delete_task, snooze_task — key words from the task title the user mentioned (string or null)
            - snooze_hours: for snooze_task — number of hours to snooze (integer, default 24)
            - clarification: if "unknown" — a short friendly reply; otherwise null

            Rules:
            - "ASAP", "urgent", "important" → high priority
            - "soon", "eventually", "whenever" → low priority
            - "tonight", "this evening" = today ~8pm, "next week" = 7 days from now
            - Greetings alone ("hi", "hello", "hey") → unknown with friendly clarification
            - If the user says something like "could you open my tasks" or "can you show me what I have" → list_tasks
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
                put("max_tokens", 300);
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
                return unknown("Sorry, I couldn't reach the AI right now. Use /help for manual commands.");
            }

            JsonNode root   = objectMapper.readTree(response.body());
            String rawJson  = root.path("content").get(0).path("text").asText().trim();
            rawJson = rawJson.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
            JsonNode parsed = objectMapper.readTree(rawJson);

            String type          = parsed.path("type").asText("unknown");
            String title         = parsed.path("title").asText(null);
            String dueDateStr    = parsed.path("due_date").asText(null);
            String priorityStr   = parsed.path("priority").asText("medium");
            String categoryStr   = parsed.path("category").asText("none");
            String recurrenceStr = parsed.path("recurrence").asText("none");
            String targetTitle   = parsed.path("target_title").asText(null);
            int snoozeHours      = parsed.path("snooze_hours").asInt(24);
            String clarification = parsed.path("clarification").asText(null);

            if ("unknown".equals(type)) {
                String msg = (clarification != null && !clarification.isBlank())
                        ? clarification
                        : "Not sure what you'd like to do. Use /help for all commands.";
                return unknown(msg);
            }

            LocalDateTime dueAt = null;
            if (dueDateStr != null && !dueDateStr.isBlank() && !dueDateStr.equals("null")) {
                try {
                    dueAt = LocalDateTime.parse(dueDateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception ignored) {}
            }

            Task.Priority priority     = Task.Priority.fromText(priorityStr);
            Task.Recurrence recurrence = Task.Recurrence.fromText(recurrenceStr);
            String category            = (categoryStr == null || categoryStr.isBlank()) ? "none" : categoryStr;
            String cleanTarget         = (targetTitle != null && !targetTitle.isBlank()) ? targetTitle.trim() : null;

            return new ParsedTask(type, title, dueAt, priority, category, recurrence, cleanTarget, snoozeHours, null);

        } catch (Exception e) {
            System.err.println("ClaudeService.parse error: " + e.getMessage());
            return unknown("Something went wrong. Use /help for manual commands.");
        }
    }

    private static ParsedTask unknown(String clarification) {
        return new ParsedTask("unknown", null, null, null, null, null, null, 0, clarification);
    }
}
