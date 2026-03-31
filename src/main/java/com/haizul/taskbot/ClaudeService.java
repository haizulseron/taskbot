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
            // task creation
            String title,
            LocalDateTime dueAt,
            Task.Priority priority,
            String category,
            Task.Recurrence recurrence,
            String notes,
            // targeting a specific task
            String targetTitle,
            // list filters (optional on any list_* intent)
            String filterPriority,
            String filterCategory,
            String filterDueRange,
            // snooze / reschedule
            int snoozeHours,
            LocalDateTime newDueDate,
            // search
            String searchQuery,
            // bulk actions
            String bulkTarget,
            String bulkOp,
            // quiet hours
            String quietStart,
            String quietEnd,
            // per-task reminder interval
            int reminderIntervalMinutes,
            // templates
            String templateName,
            // clarification for unknown
            String clarification
    ) {}

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            You are a smart task bot. Parse the user's message and return ONLY a JSON object — no markdown, no backticks.

            INTENT TYPES:
            task          — add a new task/reminder
            list_tasks    — show active tasks (optionally filtered)
            list_today    — tasks due today
            list_overdue  — overdue tasks
            list_stale    — stale/ignored tasks
            list_done     — completed tasks
            list_categories — show categories
            list_templates — show saved templates
            review        — summary with productivity score and category breakdown
            search_tasks  — search tasks by keyword
            mark_done     — mark a specific task done
            mark_all_done — mark ALL tasks done
            delete_task   — delete a specific task
            delete_all_done — delete all completed tasks
            snooze_task   — snooze a specific task
            reschedule_task — move a task to a new date/time
            duplicate_task  — copy a task (optionally with new due date)
            bulk_action   — act on a group (overdue/stale/all)
            add_notes     — add/update notes on a task
            set_reminder_interval — set how often a task reminds you
            set_quiet_hours — configure no-reminder window
            save_template — save a task as a reusable template
            use_template  — create a task from a saved template
            undo          — undo the last action
            unknown       — unclear or unrelated message

            JSON FIELDS (include only what's relevant):
            type                  string   required
            title                 string   for "task"
            due_date              string   ISO-8601 e.g. "2026-04-02T20:00:00", for "task", "use_template", "reschedule_task", "duplicate_task"
            priority              string   "high"/"medium"/"low", for "task" (default "medium")
            category              string   slug e.g. "school", "work", "none"
            recurrence            string   "none"/"daily"/"weekly"/"monthly"
            notes                 string   extra details, for "task" or "add_notes"
            target_title          string   keywords from task title, for task-targeting intents
            filter_priority       string   "high"/"medium"/"low", for list intents
            filter_category       string   category name, for list intents
            filter_due_range      string   "today"/"this_week"/"overdue", for list intents
            snooze_hours          int      hours to snooze (default 24)
            search_query          string   for "search_tasks"
            bulk_target           string   "overdue"/"stale"/"all_done", for "bulk_action"
            bulk_op               string   "snooze"/"delete"/"mark_done", for "bulk_action"
            quiet_start           string   "HH:mm" e.g. "22:00"
            quiet_end             string   "HH:mm" e.g. "07:00"
            reminder_interval_minutes int  e.g. 30, 60, 120, for "set_reminder_interval"
            template_name         string   for "save_template"/"use_template"
            clarification         string   friendly reply only for "unknown"

            RULES:
            - "ASAP"/"urgent" → high priority; "eventually"/"whenever" → low priority
            - "tonight"/"this evening" = today ~20:00; "next week" = 7 days from now
            - Greetings alone → unknown with friendly reply
            - Filtered lists: e.g. "show high priority tasks" → list_tasks + filter_priority:"high"
            - "show school tasks due this week" → list_tasks + filter_category:"school" + filter_due_range:"this_week"
            - "move gym to tomorrow" → reschedule_task
            - "copy the report task to next Monday" → duplicate_task
            - "remind me about gym every 30 minutes" → set_reminder_interval + target_title:"gym" + reminder_interval_minutes:30
            - "no reminders after 10pm until 7am" → set_quiet_hours + quiet_start:"22:00" + quiet_end:"07:00"
            - "add a note to gym: bring water bottle" → add_notes
            - "save gym as a template called daily workout" → save_template + target_title:"gym" + template_name:"daily workout"
            - "use my daily workout template for tomorrow" → use_template + template_name:"daily workout" + due_date
            - "undo" / "undo that" → undo
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
        String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String userContent = "Today is " + now + " (" + zoneId + ").\n\nUser: " + userMessage;

        try {
            String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", MODEL);
                put("max_tokens", 400);
                put("system", SYSTEM_PROMPT);
                put("messages", java.util.List.of(java.util.Map.of("role", "user", "content", userContent)));
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
                return unknown("AI parser unavailable. Use /help for commands.");
            }

            JsonNode root   = objectMapper.readTree(response.body());
            String rawJson  = root.path("content").get(0).path("text").asText().trim();
            rawJson = rawJson.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
            JsonNode p = objectMapper.readTree(rawJson);

            String type = p.path("type").asText("unknown");
            if ("unknown".equals(type)) {
                String msg = p.path("clarification").asText(null);
                return unknown(msg != null && !msg.isBlank() ? msg : "Not sure what you'd like to do. Use /help.");
            }

            LocalDateTime dueAt   = parseIsoDateTime(p.path("due_date").asText(null));
            LocalDateTime newDue  = parseIsoDateTime(p.path("due_date").asText(null)); // reused for reschedule/duplicate

            return new ParsedTask(
                    type,
                    nullIfBlank(p.path("title").asText(null)),
                    dueAt,
                    Task.Priority.fromText(p.path("priority").asText("medium")),
                    nullIfBlank(p.path("category").asText("none")),
                    Task.Recurrence.fromText(p.path("recurrence").asText("none")),
                    nullIfBlank(p.path("notes").asText(null)),
                    nullIfBlank(p.path("target_title").asText(null)),
                    nullIfBlank(p.path("filter_priority").asText(null)),
                    nullIfBlank(p.path("filter_category").asText(null)),
                    nullIfBlank(p.path("filter_due_range").asText(null)),
                    p.path("snooze_hours").asInt(24),
                    newDue,
                    nullIfBlank(p.path("search_query").asText(null)),
                    nullIfBlank(p.path("bulk_target").asText(null)),
                    nullIfBlank(p.path("bulk_op").asText(null)),
                    nullIfBlank(p.path("quiet_start").asText(null)),
                    nullIfBlank(p.path("quiet_end").asText(null)),
                    p.path("reminder_interval_minutes").asInt(0),
                    nullIfBlank(p.path("template_name").asText(null)),
                    nullIfBlank(p.path("clarification").asText(null))
            );

        } catch (Exception e) {
            System.err.println("ClaudeService.parse error: " + e.getMessage());
            return unknown("Something went wrong. Use /help for commands.");
        }
    }

    private LocalDateTime parseIsoDateTime(String s) {
        if (s == null || s.isBlank() || s.equals("null")) return null;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception ignored) {}
        return null;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank() || s.equals("null")) ? null : s.trim();
    }

    private static ParsedTask unknown(String clarification) {
        return new ParsedTask("unknown", null, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null,
                null, null, 0, null, clarification);
    }
}
