package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ClaudeService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";
    private static final int    MAX_TURNS = 8; // max tool call rounds per message

    private static final String SYSTEM_PROMPT = """
            You are Proton, a smart personal productivity assistant managing tasks and notes for the user.
            You have tools to create/edit/complete tasks, save notes, and search notes.
            
            Personality: friendly, casual, like a helpful mate. Keep responses concise.
            Always confirm what you did in plain English after using tools.
            
            CRITICAL RULES:
            - Only call tools when the user clearly wants to DO something with tasks or notes
            - Casual conversation, questions about yourself, greetings, or venting → respond naturally with text, NO tool calls
            - Examples that should NEVER trigger tools: "whats up", "how are you", "you're making no sense", "what can you do", "thanks", "ok", "lol"
            - Examples that SHOULD trigger tools: "add gym tomorrow", "mark report done", "show my tasks", "remember that X"
            - When in doubt whether to call a tool, DON'T — just respond conversationally
            - For multiple actions in one message, call multiple tools
            - When listing tasks, format them clearly with priority emoji: 🔴 high, 🟡 medium, 🟢 low, 🔵 daily
            - Dates/times: always interpret relative to today's date in the system context
            - If a task isn't found by title, say so and ask the user to be more specific
            - For notes, always save them — don't ask for confirmation
            """;

    private final String apiKey;
    private final ZoneId zoneId;
    private final HttpClient http;
    private final ObjectMapper mapper;

    // Per-user conversation history (rolling, user messages only for context)
    private final Map<Long, LinkedList<Map<String, Object>>> history = new HashMap<>();
    private static final int MAX_HISTORY = 10;

    public ClaudeService(String apiKey, ZoneId zoneId) {
        this.apiKey  = apiKey;
        this.zoneId  = zoneId;
        this.http    = HttpClient.newHttpClient();
        this.mapper  = new ObjectMapper();
    }

    // ── Main agent entry point ────────────────────────────────────────────────

    @FunctionalInterface
    public interface MessageSender { void send(String text); }

    public String chat(long userId, long chatId, String userMessage, TaskService taskService,
                       NotionService notionService, NoteService noteService, MessageSender sender) {
        String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String systemWithDate = SYSTEM_PROMPT + "\n\nCurrent date/time: " + now + " (" + zoneId + ")";

        // Build messages list — rolling history + current message
        List<Map<String, Object>> messages = new ArrayList<>(getHistory(userId));
        messages.add(Map.of("role", "user", "content", userMessage));

        List<Map<String, Object>> tools = buildTools();
        StringBuilder allText = new StringBuilder();

        // Agentic loop
        for (int turn = 0; turn < MAX_TURNS; turn++) {
            Map<String, Object> response = callApi(systemWithDate, messages, tools);
            if (response == null) return "Sorry, something went wrong. Please try again.";

            String stopReason = (String) response.get("stop_reason");
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");

            StringBuilder turnText = new StringBuilder();
            List<Map<String, Object>> toolUseBlocks = new ArrayList<>();

            for (Map<String, Object> block : content) {
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    String t = ((String) block.get("text")).trim();
                    if (!t.isEmpty()) turnText.append(t).append("\n");
                } else if ("tool_use".equals(type)) {
                    toolUseBlocks.add(block);
                }
            }

            if (turnText.length() > 0) allText.append(turnText);
            messages.add(Map.of("role", "assistant", "content", content));

            // No tool calls — we are done
            if (toolUseBlocks.isEmpty()) {
                String finalText = allText.toString().trim();
                // Save exchange to history for follow-up context
                addToHistory(userId, userMessage, finalText.isEmpty() ? "OK" : finalText);
                return finalText.isEmpty() ? "\uD83D\uDC4D" : finalText;
            }

            // Execute all tool calls
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (Map<String, Object> toolBlock : toolUseBlocks) {
                String toolName  = (String) toolBlock.get("name");
                String toolUseId = (String) toolBlock.get("id");
                Map<String, Object> input = (Map<String, Object>) toolBlock.get("input");
                String result = executeTool(toolName, input, userId, taskService, notionService, noteService, sender);
                System.out.println("Tool [" + toolName + "] result: " + result.substring(0, Math.min(120, result.length())));
                toolResults.add(Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", result));
            }

            messages.add(Map.of("role", "user", "content", toolResults));
        }

        return "I got a bit stuck on that one. Try rephrasing?";
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    private String executeTool(String name, Map<String, Object> input, long userId,
                                TaskService taskService, NotionService notionService, NoteService noteService,
                                MessageSender sender) {
        try {
            return switch (name) {
                case "create_task" -> {
                    String title      = str(input, "title");
                    String priority   = str(input, "priority", "medium");
                    String category   = str(input, "category", "none");
                    String dueStr     = str(input, "due_date");
                    String recurrence = str(input, "recurrence", "none");
                    String notes      = str(input, "notes");

                    LocalDateTime due = parseDate(dueStr);
                    Task.Priority p   = Task.Priority.fromText(priority);
                    Task.Recurrence r = Task.Recurrence.fromText(recurrence);

                    Task created = taskService.createTask(userId, userId,
                            new TaskService.AddTaskRequest(title, p, category, due, r, notes));
                    yield "Created task: " + taskService.formatTask(created);
                }

                case "list_tasks" -> {
                    String filter = str(input, "filter", "active");
                    List<Task> tasks = switch (filter) {
                        case "today"   -> taskService.getTodayTasks(userId);
                        case "overdue" -> taskService.getOverdueTasks(userId);
                        case "stale"   -> taskService.getStaleTasks(userId);
                        case "done"    -> taskService.getDoneTasks(userId);
                        default        -> taskService.getActiveTasks(userId);
                    };
                    if (tasks.isEmpty()) yield "No tasks found.";
                    // Build the same formatted list as /tasks command
                    List<Task> main  = tasks.stream().filter(t -> t.getPriority() != Task.Priority.DAILY).toList();
                    List<Task> daily = tasks.stream().filter(t -> t.getPriority() == Task.Priority.DAILY).toList();
                    long high = main.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
                    long med  = main.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
                    long low  = main.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();
                    StringBuilder sb = new StringBuilder();
                    String title = switch(filter) {
                        case "today"   -> "🗓 Due Today";
                        case "overdue" -> "⚠️ Overdue";
                        case "stale"   -> "🧊 Stale";
                        case "done"    -> "✅ Completed";
                        default        -> "📋 Active Tasks";
                    };
                    sb.append(title).append("  (").append(tasks.size()).append(")\n");
                    if (high > 0) sb.append("🔴 ").append(high).append(" high   ");
                    if (med  > 0) sb.append("🟡 ").append(med).append(" medium   ");
                    if (low  > 0) sb.append("🟢 ").append(low).append(" low");
                    if (!daily.isEmpty()) sb.append("  🔵 ").append(daily.size()).append(" daily");
                    sb.append("\n─────────────────\n");
                    for (int i = 0; i < main.size(); i++) {
                        Task t = main.get(i);
                        String dot = switch (t.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; case DAILY -> "🔵"; };
                        String due = t.getDueAt() != null ? "  📅 " + taskService.friendlyDate(t.getDueAt()) : "";
                        String cat = "none".equals(t.getCategory()) ? "" : "  📁 " + t.getCategory();
                        sb.append(dot).append(" ").append(t.getTitle()).append("\n");
                        sb.append("   ").append(cat).append(due);
                        if (t.isHabit()) sb.append("  🔄");
                        sb.append("\n");
                        if (i < main.size() - 1) sb.append("\n");
                    }
                    if (!daily.isEmpty()) {
                        if (!main.isEmpty()) sb.append("\n");
                        sb.append("─────────────────\n🔵 Daily\n─────────────────\n");
                        for (Task t : daily) {
                            String due = t.getDueAt() != null ? "  📅 " + taskService.friendlyDate(t.getDueAt()) : "";
                            String cat = "none".equals(t.getCategory()) ? "" : "  📁 " + t.getCategory();
                            sb.append("🔵 ").append(t.getTitle()).append("\n");
                            sb.append("   ").append(cat).append(due).append("\n\n");
                        }
                    }
                    // Send directly as a Telegram message so Claude doesn't reformat it
                    sender.send(sb.toString().trim());
                    yield "SENT_DIRECTLY";
                }

                case "mark_done" -> {
                    String hint = str(input, "task_hint");
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    taskService.markDone(userId, opt.get().shortId());
                    taskService.resetReminderIgnoredCount(opt.get().getId());
                    yield "Marked done: " + opt.get().getTitle();
                }

                case "mark_all_done" -> {
                    String priorityFilter = str(input, "priority_filter");
                    if (priorityFilter != null && !priorityFilter.isBlank()) {
                        Task.Priority p = Task.Priority.fromText(priorityFilter);
                        int n = taskService.markAllDoneByPriority(userId, p);
                        yield "Marked " + n + " " + priorityFilter + " tasks done.";
                    }
                    int n = taskService.markAllDone(userId);
                    yield "Marked all " + n + " tasks done.";
                }

                case "delete_task" -> {
                    String hint = str(input, "task_hint");
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    taskService.deleteTask(userId, opt.get().shortId());
                    yield "Deleted: " + opt.get().getTitle();
                }

                case "snooze_task" -> {
                    String hint  = str(input, "task_hint");
                    int hours    = intVal(input, "hours", 24);
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    taskService.snoozeTask(userId, opt.get().shortId(), java.time.Duration.ofHours(hours));
                    yield "Snoozed \"" + opt.get().getTitle() + "\" by " + hours + "h.";
                }

                case "reschedule_task" -> {
                    String hint   = str(input, "task_hint");
                    String newDue = str(input, "new_due_date");
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    LocalDateTime dt = parseDate(newDue);
                    if (dt == null) yield "Couldn't parse date: " + newDue;
                    taskService.updateTaskDueAtDirectly(userId, opt.get().shortId(), dt);
                    yield "Rescheduled \"" + opt.get().getTitle() + "\" to " + taskService.friendlyDate(dt);
                }

                case "edit_task" -> {
                    String hint  = str(input, "task_hint");
                    String field = str(input, "field");
                    String value = str(input, "value");
                    boolean ok = taskService.editTaskField(userId, hint, field, value);
                    yield ok ? "Updated " + field + " for task matching \"" + hint + "\"."
                             : "Task not found or unknown field: " + hint + " / " + field;
                }

                case "search_tasks" -> {
                    String query = str(input, "query");
                    List<Task> tasks = taskService.searchTasks(userId, query);
                    if (tasks.isEmpty()) yield "No tasks found matching: " + query;
                    StringBuilder sb = new StringBuilder();
                    tasks.forEach(t -> sb.append(taskService.formatTask(t)).append("\n---\n"));
                    yield sb.toString().trim();
                }

                case "set_reminder_interval" -> {
                    String hint = str(input, "task_hint");
                    int mins    = intVal(input, "minutes", 60);
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    taskService.setReminderInterval(userId, opt.get().shortId(), mins);
                    yield "Set reminder every " + mins + " min for \"" + opt.get().getTitle() + "\".";
                }

                case "set_quiet_hours" -> {
                    String start = str(input, "start");
                    String end   = str(input, "end");
                    taskService.saveUserSettings(userId, start, end);
                    yield "Quiet hours set: " + start + " – " + end;
                }

                case "get_review" -> {
                    yield taskService.getReviewSummary(userId);
                }

                case "save_note" -> {
                    if (noteService == null || notionService == null) yield "Notes not configured.";
                    String raw = str(input, "text");
                    String source = str(input, "source", "text");
                    NoteService.StructuredNote structured = noteService.structure(raw);
                    NotionService.SavedNote saved = notionService.saveNote(
                            structured.title(), structured.category(), structured.tags(),
                            raw, structured.summary(), source);
                    yield "Saved note: \"" + saved.title() + "\" [" + saved.category() + "]"
                            + (saved.tags().isEmpty() ? "" : " tags: " + String.join(", ", saved.tags()))
                            + "\nSummary: " + structured.summary();
                }

                case "search_notes" -> {
                    if (notionService == null || noteService == null) yield "Notes not configured.";
                    String query    = str(input, "query");
                    String category = str(input, "category");
                    boolean recent  = boolVal(input, "recent");
                    List<NotionService.NoteResult> results = recent
                            ? notionService.getRecentNotes(5)
                            : notionService.searchNotes(query, category, 5);
                    if (results.isEmpty()) yield "No notes found.";
                    StringBuilder sb = new StringBuilder();
                    sb.append("📝 Notes\n─────────────────\n\n");
                    for (NotionService.NoteResult note : results) {
                        sb.append("📌 ").append(note.title()).append("\n");
                        sb.append("   📁 ").append(note.category());
                        if (!note.tags().isEmpty()) sb.append("  🏷 ").append(String.join(", ", note.tags()));
                        sb.append("  📅 ").append(note.created()).append("\n");
                        if (!note.summary().isBlank()) sb.append(note.summary()).append("\n");
                        sb.append("\n");
                    }
                    sender.send(sb.toString().trim());
                    yield "SENT_DIRECTLY";
                }

                case "toggle_habit" -> {
                    String hint  = str(input, "task_hint");
                    boolean enable = boolVal(input, "enable");
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    taskService.toggleHabit(userId, opt.get().shortId(), enable);
                    yield (enable ? "Marked as habit: " : "Removed from habits: ") + opt.get().getTitle();
                }

                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            System.err.println("Tool execution error [" + name + "]: " + e.getMessage());
            return "Error executing " + name + ": " + e.getMessage();
        }
    }

    // ── Tool definitions ──────────────────────────────────────────────────────

    private List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool("create_task", "Create a new task or reminder",
                props()
                    .req("title", "string", "Task title")
                    .opt("priority", "string", "high, medium, low, or daily (default: medium)")
                    .opt("category", "string", "Category slug e.g. school, work, none")
                    .opt("due_date", "string", "ISO-8601 datetime e.g. 2026-04-15T09:00:00")
                    .opt("recurrence", "string", "none, daily, weekly, monthly")
                    .opt("notes", "string", "Extra notes for the task")
        ));

        tools.add(tool("list_tasks", "List tasks",
                props()
                    .opt("filter", "string", "active (default), today, overdue, stale, done")
        ));

        tools.add(tool("mark_done", "Mark a specific task as done",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
        ));

        tools.add(tool("mark_all_done", "Mark all tasks done, optionally filtered by priority",
                props()
                    .opt("priority_filter", "string", "high, medium, low, daily — omit to mark all done")
        ));

        tools.add(tool("delete_task", "Delete a specific task",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
        ));

        tools.add(tool("snooze_task", "Snooze a task by a number of hours",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
                    .opt("hours", "integer", "Hours to snooze (default: 24)")
        ));

        tools.add(tool("reschedule_task", "Move a task to a new date/time",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
                    .req("new_due_date", "string", "New ISO-8601 datetime")
        ));

        tools.add(tool("edit_task", "Edit a specific field on a task",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
                    .req("field", "string", "title, priority, category, due, recurrence, or notes")
                    .req("value", "string", "New value for the field")
        ));

        tools.add(tool("search_tasks", "Search tasks by keyword",
                props()
                    .req("query", "string", "Search keywords")
        ));

        tools.add(tool("set_reminder_interval", "Set how often a task reminds you",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
                    .req("minutes", "integer", "Reminder interval in minutes")
        ));

        tools.add(tool("set_quiet_hours", "Set no-reminder window",
                props()
                    .req("start", "string", "Start time HH:mm")
                    .req("end", "string", "End time HH:mm")
        ));

        tools.add(tool("get_review", "Get productivity review and task summary", props()));

        tools.add(tool("save_note", "Save a note to Notion",
                props()
                    .req("text", "string", "The note content to save")
                    .opt("source", "string", "text or voice")
        ));

        tools.add(tool("search_notes", "Search or retrieve saved notes",
                props()
                    .opt("query", "string", "Search keywords")
                    .opt("category", "string", "Filter by category")
                    .opt("recent", "boolean", "Set true to get recent notes")
        ));

        tools.add(tool("toggle_habit", "Mark or unmark a task as a habit",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
                    .req("enable", "boolean", "true to enable, false to disable")
        ));

        return tools;
    }

    // ── Conversation history ──────────────────────────────────────────────────
    // Stores pairs of (userMessage, assistantResponse) as clean text.
    // This gives Claude context for follow-ups like "actually make it 10am".

    /** Call after each completed exchange with the user message and Claude's final text response. */
    private void addToHistory(long userId, String userMessage, String assistantResponse) {
        LinkedList<Map<String, Object>> hist = history.computeIfAbsent(userId, k -> new LinkedList<>());
        hist.add(Map.of("role", "user", "content", userMessage));
        hist.add(Map.of("role", "assistant", "content", assistantResponse));
        // Keep last MAX_HISTORY messages (pairs)
        while (hist.size() > MAX_HISTORY) hist.removeFirst();
    }

    private List<Map<String, Object>> getHistory(long userId) {
        LinkedList<Map<String, Object>> hist = history.get(userId);
        if (hist == null || hist.size() < 2) return List.of();
        return new ArrayList<>(hist);
    }

    // ── Reminder message (used by SchedulerService) ───────────────────────────

    public String generateReminderMessage(Task task, String label, int ignoredCount, int habitStreak) {
        String urgency = switch (task.getPriority()) {
            case HIGH   -> "high priority";
            case MEDIUM -> "medium priority";
            case LOW    -> "low priority";
            case DAILY  -> "daily routine";
        };
        String cat   = (task.getCategory() != null && !task.getCategory().equals("none")) ? task.getCategory() : null;
        String notes = task.getNotes();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Write a short, natural reminder message (1-2 sentences) for a personal task bot.\n\n");
        prompt.append("Task: \"").append(task.getTitle()).append("\"\n");
        prompt.append("Priority: ").append(urgency).append("\n");
        prompt.append("Status: ").append(label).append("\n");
        if (cat != null)        prompt.append("Category: ").append(cat).append("\n");
        if (notes != null)      prompt.append("Notes: ").append(notes).append("\n");
        if (habitStreak > 1)    prompt.append("Habit streak: ").append(habitStreak).append(" days\n");
        if (ignoredCount >= 5)  prompt.append("Ignored ").append(ignoredCount).append(" times — be urgent\n");
        else if (ignoredCount >= 3) prompt.append("Reminded ").append(ignoredCount).append(" times — nudge firmly\n");
        prompt.append("\nTone: friendly, casual. No emojis. Just the message text.");

        String sys = "You write short reminder messages. Return ONLY the message — no JSON, no quotes.";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("max_tokens", 100);
            body.put("system", sys);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt.toString())));
            String resp = postJson(body);
            JsonNode root = mapper.readTree(resp);
            String text = root.path("content").get(0).path("text").asText("").trim();
            return text.isBlank() ? null : text;
        } catch (Exception e) { return null; }
    }

    // ── HTTP / builder helpers ────────────────────────────────────────────────

    private Map<String, Object> callApi(String system, List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("max_tokens", 1024);
            body.put("system", system);
            body.put("tools", tools);
            body.put("messages", messages);

            String resp = postJson(body);
            JsonNode root = mapper.readTree(resp);

            if (root.has("error")) {
                System.err.println("Claude API error: " + root.path("error").path("message").asText());
                return null;
            }

            String stopReason = root.path("stop_reason").asText("end_turn");
            List<Map<String, Object>> content = new ArrayList<>();

            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    content.add(Map.of("type", "text", "text", block.path("text").asText("")));
                } else if ("tool_use".equals(type)) {
                    Map<String, Object> inputMap = mapper.convertValue(block.path("input"), Map.class);
                    content.add(Map.of(
                        "type", "tool_use",
                        "id", block.path("id").asText(),
                        "name", block.path("name").asText(),
                        "input", inputMap != null ? inputMap : Map.of()
                    ));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stop_reason", stopReason);
            result.put("content", content);
            return result;
        } catch (Exception e) {
            System.err.println("ClaudeService.callApi error: " + e.getMessage());
            return null;
        }
    }

    private String postJson(Map<String, Object> body) throws Exception {
        String bodyStr = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            System.err.println("Claude API HTTP " + res.statusCode() + ": " + res.body());
        }
        return res.body();
    }

    // ── Tool builder DSL ──────────────────────────────────────────────────────

    private Map<String, Object> tool(String name, String description, Props props) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", props.build());
        return t;
    }

    private Props props() { return new Props(); }

    private static class Props {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        Props req(String name, String type, String description) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type); p.put("description", description);
            properties.put(name, p); required.add(name); return this;
        }
        Props opt(String name, String type, String description) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type); p.put("description", description);
            properties.put(name, p); return this;
        }
        Map<String, Object> build() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) schema.put("required", required);
            return schema;
        }
    }

    // ── Value extraction helpers ──────────────────────────────────────────────

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null || "null".equals(v.toString().trim())) ? null : v.toString().trim();
    }
    private String str(Map<String, Object> m, String key, String fallback) {
        String v = str(m, key); return v == null ? fallback : v;
    }
    private int intVal(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return fallback; }
    }
    private boolean boolVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return false;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception e) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); } catch (Exception e) {}
        return null;
    }
}