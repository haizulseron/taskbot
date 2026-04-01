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
            String title, LocalDateTime dueAt, Task.Priority priority,
            String category, Task.Recurrence recurrence, String notes,
            String targetTitle,
            String filterPriority, String filterCategory, String filterDueRange,
            int snoozeHours, LocalDateTime newDueDate,
            String searchQuery,
            String bulkTarget, String bulkOp,
            String quietStart, String quietEnd,
            int reminderIntervalMinutes,
            String templateName,
            // natural language edit
            String editField, String editValue,
            // focus session
            int focusDurationMinutes,
            // habit
            boolean habitToggle,
            // clarification
            String clarification
    ) {}

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            You are a smart task bot. Parse the user's message and return ONLY a JSON object — no markdown, no backticks.

            INTENT TYPES:
            task                  — add a new task/reminder
            list_tasks            — show active tasks (optionally filtered)
            list_today            — tasks due today
            list_overdue          — overdue tasks
            list_stale            — stale tasks
            list_done             — completed tasks
            list_categories       — show categories
            list_templates        — show saved templates
            review                — summary + productivity + habits
            search_tasks          — search tasks by keyword
            mark_done             — mark a specific task done
            mark_all_done         — mark ALL tasks done
            delete_task           — delete a specific task
            delete_all_done       — delete all completed tasks
            snooze_task           — snooze a specific task
            reschedule_task       — move task to new date/time
            duplicate_task        — copy a task
            bulk_action           — act on a group (overdue/stale/all)
            add_notes             — add/update notes on a task
            set_reminder_interval — set how often a task reminds you
            set_quiet_hours       — configure no-reminder window
            save_template         — save a task as a template
            use_template          — create task from a saved template
            edit_task             — change a specific field on a task
            toggle_habit          — mark/unmark a task as a habit
            start_focus           — start a focus/Pomodoro session
            stop_focus            — cancel active focus session
            set_location_reminder — set a location-based reminder for a task
            undo                  — undo the last action
            unknown               — unclear or unrelated message

            JSON FIELDS:
            type                      string   required
            title                     string   for "task"
            due_date                  string   ISO-8601 e.g. "2026-04-02T20:00:00"
            priority                  string   "high"/"medium"/"low" (default "medium")
            category                  string   slug e.g. "school", "work", "none"
            recurrence                string   "none"/"daily"/"weekly"/"monthly"
            notes                     string   extra details
            target_title              string   keywords from task title
            filter_priority           string   for list intents
            filter_category           string   for list intents
            filter_due_range          string   "today"/"this_week"/"overdue"
            snooze_hours              int      default 24
            search_query              string   for search_tasks
            bulk_target               string   "overdue"/"stale"/"all_done"
            bulk_op                   string   "snooze"/"delete"/"mark_done"
            quiet_start               string   "HH:mm"
            quiet_end                 string   "HH:mm"
            reminder_interval_minutes int
            template_name             string
            edit_field                string   "title"/"priority"/"category"/"due"/"recurrence"/"notes"
            edit_value                string   the new value as a string
            focus_duration_minutes    int      default 25
            habit_toggle              boolean  true to enable, false to disable
            clarification             string   only for "unknown"

            RULES:
            - "ASAP"/"urgent" → high priority; "eventually"/"whenever" → low priority
            - "tonight" = today ~20:00; "next week" = 7 days from now
            - Greetings alone → unknown with friendly reply
            - "change the gym task to high priority" → edit_task + target_title:"gym" + edit_field:"priority" + edit_value:"high"
            - "rename the report task to Final CEE Report" → edit_task + edit_field:"title" + edit_value:"Final CEE Report"
            - "move gym to next Monday" → reschedule_task (not edit_task)
            - "mark gym as a habit" → toggle_habit + target_title:"gym" + habit_toggle:true
            - "start a 25 min focus session for the CEE report" → start_focus + target_title:"CEE report" + focus_duration_minutes:25
            - "stop focus" / "end session" → stop_focus
            - "remind me when I get to school for the lab task" → set_location_reminder + target_title:"lab task"
            - "set reminder for all overdue tasks" → set_reminder_interval + target_title:"ALL_OVERDUE"
            - "snooze all overdue tasks" → snooze_task + target_title:"ALL_OVERDUE"
            - "undo" / "undo that" → undo
            """;

    private final String apiKey;
    private final ZoneId zoneId;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ClaudeService(String apiKey, ZoneId zoneId) {
        this.apiKey  = apiKey;
        this.zoneId  = zoneId;
        this.http    = HttpClient.newHttpClient();
        this.mapper  = new ObjectMapper();
    }

    public ParsedTask parse(String userMessage) {
        String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String content = "Today is " + now + " (" + zoneId + ").\n\nUser: " + userMessage;
        return callApi(content, 400, SYSTEM_PROMPT, this::mapToParsedTask);
    }

    /** Generates a short contextual motivating reminder message using Haiku */
    public String generateReminderMessage(String taskTitle, Task.Priority priority, String label, int ignoredCount) {
        String urgency = switch (priority) { case HIGH -> "HIGH PRIORITY"; case MEDIUM -> "medium priority"; case LOW -> "low priority"; };
        String escalation = ignoredCount >= 3 ? " This is the " + (ignoredCount + 1) + "th reminder — it's overdue for attention!" : "";
        String prompt = "Generate a short (1 sentence max), motivating reminder message for a task bot. Task: \"" + taskTitle
                + "\". Priority: " + urgency + ". Status: " + label + "." + escalation
                + " Be encouraging but direct. No emojis. No quotes around the output.";

        String sys = "You generate short task reminder messages. Return ONLY the message text — no JSON, no quotes, no punctuation at end unless natural.";
        try {
            return callApi(prompt, 80, sys, node -> {
                String text = node.path("content").get(0).path("text").asText("").trim();
                return text.isBlank() ? null : text;
            });
        } catch (Exception e) { return null; }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ResponseMapper<T> { T map(JsonNode root) throws Exception; }

    private <T> T callApi(String userContent, int maxTokens, String systemPrompt, ResponseMapper<T> mapper) {
        try {
            String body = this.mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", MODEL);
                put("max_tokens", maxTokens);
                put("system", systemPrompt);
                put("messages", java.util.List.of(java.util.Map.of("role", "user", "content", userContent)));
            }});

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { System.err.println("Claude API error " + res.statusCode()); return null; }
            return mapper.map(this.mapper.readTree(res.body()));
        } catch (Exception e) { System.err.println("ClaudeService error: " + e.getMessage()); return null; }
    }

    private ParsedTask mapToParsedTask(JsonNode root) throws Exception {
        String raw = root.path("content").get(0).path("text").asText().trim();
        raw = raw.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
        JsonNode p = mapper.readTree(raw);

        String type = p.path("type").asText("unknown");
        if ("unknown".equals(type)) {
            String msg = p.path("clarification").asText(null);
            return unknown(msg != null && !msg.isBlank() ? msg : "Not sure what you'd like to do. Use /help.");
        }

        return new ParsedTask(
                type,
                nb(p.path("title")), parseIso(p.path("due_date")),
                Task.Priority.fromText(p.path("priority").asText("medium")),
                nb(p.path("category")), Task.Recurrence.fromText(p.path("recurrence").asText("none")),
                nb(p.path("notes")), nb(p.path("target_title")),
                nb(p.path("filter_priority")), nb(p.path("filter_category")), nb(p.path("filter_due_range")),
                p.path("snooze_hours").asInt(24), parseIso(p.path("due_date")),
                nb(p.path("search_query")), nb(p.path("bulk_target")), nb(p.path("bulk_op")),
                nb(p.path("quiet_start")), nb(p.path("quiet_end")),
                p.path("reminder_interval_minutes").asInt(0),
                nb(p.path("template_name")),
                nb(p.path("edit_field")), nb(p.path("edit_value")),
                p.path("focus_duration_minutes").asInt(25),
                p.path("habit_toggle").asBoolean(true),
                nb(p.path("clarification"))
        );
    }

    private LocalDateTime parseIso(JsonNode node) {
        String s = node.asText(null);
        if (s == null || s.isBlank() || s.equals("null")) return null;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception e) { return null; }
    }

    private static String nb(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String s = n.asText(null);
        return (s == null || s.isBlank() || s.equals("null")) ? null : s.trim();
    }

    private static ParsedTask unknown(String clarification) {
        return new ParsedTask("unknown", null, null, null, null, null, null, null,
                null, null, null, 0, null, null, null, null, null, null,
                0, null, null, null, 0, false, clarification);
    }
}
