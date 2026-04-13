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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClaudeService {

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String MODEL_FAST  = "claude-haiku-4-5-20251001";  // default: fast & cheap
    private static final String MODEL_SMART = "claude-sonnet-4-6";           // complex tasks only
    private static final int    MAX_TURNS   = 8; // max tool call rounds per message

    private static final String BASE_SYSTEM_PROMPT = """
            You are Proton, a smart personal productivity assistant for Zul (Haizul Ali Seron).
            Timezone: Asia/Singapore. Default categories: work, school, personal, daily.

            ══ TASK INTEGRITY — ABSOLUTE RULES, NO EXCEPTIONS ══
            - Task data in conversation history IS ALWAYS STALE AND WRONG. Ignore it completely.
            - NEVER tell the user what tasks they have based on memory or history.
            - EVERY time the user asks about tasks (what tasks, how many, what's due, what's overdue, etc.) → call list_tasks or search_tasks FIRST. No exceptions.
            - Only after the tool returns can you respond about tasks.
            - Even if you think you know from earlier in this conversation — call the tool. Always.
            ════════════════════════════════════════════════════

            Personality: friendly, casual, like a helpful mate. Keep responses concise.
            Always confirm what you did in plain English after using tools.

            CRITICAL RULES:
            - Only call tools when the user clearly wants to DO something
            - Casual conversation, greetings, or venting → respond naturally with text, NO tool calls
            - Examples that should NEVER trigger tools: "whats up", "how are you", "thanks", "ok", "lol"
            - Examples that SHOULD trigger tools: "add gym tomorrow", "mark report done", "check my inbox", "what's on my calendar"
            - When in doubt whether to call a tool, DON'T — just respond conversationally
            - For multiple actions in one message, call multiple tools
            - Dates/times: always interpret relative to today's date in the system context
            - If a task isn't found by title, say so and ask the user to be more specific
            - For notes, always save them — don't ask for confirmation

            CALENDAR RULES:
            - Interpret all date/times relative to the current date/time in the system context
            - For add_event, if no end time given, default to 1 hour after start
            - For reschedule_event, use the event title as the hint
            - If no specific time is given (e.g. "add holiday on 1 May", "block off Thursday"), set all_day=true and use only the date (yyyy-MM-dd) for start_datetime

            EXAMPLES — correct task handling (follow exactly):
            User: "what tasks do i have" → call list_tasks → then reply
            User: "how many tasks left" → call list_tasks → then reply
            User: "anything due today" → call list_tasks with filter=today → then reply
            User: "find my gym task" → call search_tasks → then reply
            WRONG (never do): responding with task details without calling list_tasks/search_tasks first

            DRIVE RULES:
            - search_drive ONLY lists files — it does NOT send anything to Telegram
            - To actually deliver a file, you MUST call send_drive_file with the exact file_id from search_drive results
            - NEVER say a file was sent without having called send_drive_file first
            - Always call search_drive first to get the file_id, then immediately call send_drive_file in the same turn
            - If the user asks for a specific file (e.g. "first one"), call send_drive_file with that file's id right away

            EMAIL RULES:
            - read_emails shows inbox list (subject, sender, snippet + ID only) — NOT the full email body
            - To read the actual content of an email, call read_email with the message_id
            - ALWAYS write emails in a professional, clear, and polished tone — no exceptions
            - Always open with an appropriate greeting (e.g. "Dear [Name]," or "Hi [Name]," depending on formality)
            - Do NOT add a sign-off or signature in the body — the system automatically appends "Warm regards, Haizul Ali Seron"
            - For draft_email: YOU compose the full subject AND body right now. Do not ask for more info.
            - For reply_email: write a professional reply body, then call the tool with the original message_id
            - Attached files from Telegram are automatically included when drafting/replying — no need to mention them

            NOTES RULES:
            - search_notes shows a summary list only — NOT the full note content
            - To read the full content of a note, call read_note with the page_id from search_notes results
            - If the user asks to "read", "open", or "show full" a note, call search_notes first then read_note
            """;

    private final String apiKey;
    private final ZoneId zoneId;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Database db;
    private final ExecutorService memoryExtractor = Executors.newSingleThreadExecutor();

    // How many individual messages (user + assistant alternating) to keep per user
    private static final int MAX_HISTORY = 10;

    public ClaudeService(String apiKey, ZoneId zoneId, Database db) {
        this.apiKey  = apiKey;
        this.zoneId  = zoneId;
        this.http    = HttpClient.newHttpClient();
        this.mapper  = new ObjectMapper();
        this.db      = db;
    }

    // ── Main agent entry point ────────────────────────────────────────────────

    @FunctionalInterface public interface MessageSender { void send(String text); }
    @FunctionalInterface public interface FileSender    { void send(String filename, byte[] data); }

    public String chat(long userId, long chatId, String userMessage,
                       TaskService taskService, NotionService notionService, NoteService noteService,
                       GmailService gmailService, CalendarService calendarService, DriveService driveService,
                       List<GmailService.Attachment> pendingAttachments,
                       MessageSender sender, FileSender fileSender) {
        String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String systemWithDate = buildSystemPrompt(userId) + "\n\nCurrent date/time: " + now + " (" + zoneId + ")";
        String model = pickModel(userMessage);

        // Build messages list — rolling history + current message
        List<Map<String, Object>> messages = new ArrayList<>(getHistory(userId));
        messages.add(Map.of("role", "user", "content", userMessage));

        List<Map<String, Object>> tools = buildTools();
        StringBuilder allText = new StringBuilder();
        // Track whether a DB-backed task tool was called so we don't save stale task
        // data back into conversation history (which would cause future hallucinations).
        boolean taskDataFetched = false;

        // Agentic loop
        for (int turn = 0; turn < MAX_TURNS; turn++) {
            Map<String, Object> response = callApi(systemWithDate, messages, tools, model);
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
                // If we fetched live task data, save a neutral placeholder instead of
                // Claude's verbose summary — prevents stale counts leaking into history.
                String savedResponse = taskDataFetched
                        ? "[Showed current task list from database]"
                        : (finalText.isEmpty() ? "OK" : finalText);
                addToHistory(userId, userMessage, savedResponse);
                extractAndSaveMemory(userId, userMessage, savedResponse);
                return finalText.isEmpty() ? "\uD83D\uDC4D" : finalText;
            }

            // Execute all tool calls
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (Map<String, Object> toolBlock : toolUseBlocks) {
                String toolName  = (String) toolBlock.get("name");
                String toolUseId = (String) toolBlock.get("id");
                Map<String, Object> input = (Map<String, Object>) toolBlock.get("input");
                // Track if any read-task tool was used this turn
                if ("list_tasks".equals(toolName) || "search_tasks".equals(toolName) || "get_review".equals(toolName)) {
                    taskDataFetched = true;
                }
                String result = executeTool(toolName, input, userId, taskService, notionService, noteService,
                        gmailService, calendarService, driveService, pendingAttachments, sender, fileSender);
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
                                GmailService gmailService, CalendarService calendarService, DriveService driveService,
                                List<GmailService.Attachment> pendingAttachments,
                                MessageSender sender, FileSender fileSender) {
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
                        if (!note.summary().isBlank()) sb.append("   ").append(note.summary()).append("\n");
                        sb.append("\n");
                    }
                    sender.send(sb.toString().trim());
                    // Return page IDs so Claude can read full content on request
                    StringBuilder ids = new StringBuilder("Notes shown. Page IDs for read_note:\n");
                    results.forEach(n -> ids.append("• ").append(n.title()).append(" → ").append(n.pageId()).append("\n"));
                    yield ids.toString();
                }

                case "read_note" -> {
                    if (notionService == null) yield "Notes not configured.";
                    String pageId = str(input, "page_id");
                    String content = notionService.readNote(pageId);
                    if (content == null || content.isBlank()) yield "Note is empty or couldn't be read.";
                    sender.send(content);
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

                // ── Email ──────────────────────────────────────────────────────────
                case "read_emails" -> {
                    if (gmailService == null) yield "Gmail not connected. Send /authorize to link your Google account.";
                    int count = intVal(input, "count", 5);
                    List<GmailService.EmailSummary> emails = gmailService.getInbox(count);
                    if (emails.isEmpty()) yield "No emails in inbox.";
                    StringBuilder sb = new StringBuilder("📧 Inbox (" + emails.size() + ")\n─────────────────\n\n");
                    for (int i = 0; i < emails.size(); i++) {
                        GmailService.EmailSummary e = emails.get(i);
                        sb.append(i + 1).append(". ").append(e.subject()).append("\n");
                        sb.append("   From: ").append(e.from()).append("\n");
                        sb.append("   ").append(e.date()).append("\n");
                        if (e.snippet() != null && !e.snippet().isBlank())
                            sb.append("   ").append(e.snippet()).append("\n");
                        sb.append("   ID: ").append(e.id()).append("\n\n");
                    }
                    sender.send(sb.toString().trim());
                    // Return IDs separately so Claude can reference them for replies
                    StringBuilder ids = new StringBuilder("Emails shown. Message IDs for reply:\n");
                    emails.forEach(e -> ids.append("• ").append(e.subject()).append(" → ").append(e.id()).append("\n"));
                    yield ids.toString();
                }

                case "read_email" -> {
                    if (gmailService == null) yield "Gmail not connected. Send /authorize to link your Google account.";
                    String messageId = str(input, "message_id");
                    GmailService.EmailContent email = gmailService.readEmailContent(messageId);
                    StringBuilder sb = new StringBuilder();
                    sb.append("📧 ").append(email.subject()).append("\n");
                    sb.append("From: ").append(email.from()).append("\n");
                    sb.append("Date: ").append(email.date()).append("\n");
                    sb.append("─────────────────\n\n");
                    String body = email.body();
                    if (body.length() > 3000) body = body.substring(0, 3000) + "\n\n[truncated — email is long]";
                    sb.append(body);
                    sender.send(sb.toString().trim());
                    yield "Email content shown. Message ID for reply: " + email.id();
                }

                case "draft_email" -> {
                    if (gmailService == null) yield "Gmail not connected. Send /authorize to link your Google account.";
                    String to      = str(input, "to");
                    String cc      = str(input, "cc");
                    String subject = str(input, "subject");
                    String body    = str(input, "body");
                    String draftId = gmailService.createDraft(to, cc, subject, body, pendingAttachments);
                    String attNote = (pendingAttachments != null && !pendingAttachments.isEmpty())
                            ? " (" + pendingAttachments.size() + " attachment(s) included)" : "";
                    yield "Draft saved" + attNote + ". Open Gmail to review and send.";
                }

                case "reply_email" -> {
                    if (gmailService == null) yield "Gmail not connected. Send /authorize to link your Google account.";
                    String messageId = str(input, "message_id");
                    String body      = str(input, "body");
                    String draftId   = gmailService.replyToDraft(messageId, body, pendingAttachments);
                    String attNote   = (pendingAttachments != null && !pendingAttachments.isEmpty())
                            ? " (" + pendingAttachments.size() + " attachment(s) included)" : "";
                    yield "Reply draft saved" + attNote + ". Open Gmail to review and send.";
                }

                // ── Calendar ───────────────────────────────────────────────────────
                case "list_events" -> {
                    if (calendarService == null) yield "Calendar not connected. Send /authorize to link your Google account.";
                    int days = intVal(input, "days", 7);
                    List<CalendarService.EventSummary> events = calendarService.getUpcomingEvents(days);
                    if (events.isEmpty()) yield "No events in the next " + days + " days.";
                    StringBuilder sb = new StringBuilder("📅 Calendar (next " + days + " days)\n─────────────────\n\n");
                    for (CalendarService.EventSummary e : events) {
                        sb.append(e.start()).append("\n");
                        sb.append("  📌 ").append(e.title()).append("\n");
                        if (e.location() != null && !e.location().isBlank())
                            sb.append("  📍 ").append(e.location()).append("\n");
                        sb.append("\n");
                    }
                    sender.send(sb.toString().trim());
                    yield "SENT_DIRECTLY";
                }

                case "add_event" -> {
                    if (calendarService == null) yield "Calendar not connected. Send /authorize to link your Google account.";
                    String title   = str(input, "title");
                    String startDt = str(input, "start_datetime");
                    String endDt   = str(input, "end_datetime");
                    String desc    = str(input, "description");
                    boolean allDay = boolVal(input, "all_day");
                    CalendarService.EventSummary created = calendarService.addEvent(title, startDt, endDt, desc, allDay);
                    yield "Added to calendar: \"" + created.title() + "\" — " + created.start();
                }

                case "reschedule_event" -> {
                    if (calendarService == null) yield "Calendar not connected. Send /authorize to link your Google account.";
                    String hint  = str(input, "event_hint");
                    String newDt = str(input, "new_start_datetime");
                    yield calendarService.rescheduleEvent(hint, newDt);
                }

                // ── Google Drive ───────────────────────────────────────────────────
                case "search_drive" -> {
                    if (driveService == null) yield "Drive not connected. Send /authorize to link your Google account.";
                    String query = str(input, "query");
                    List<DriveService.FileSummary> files = driveService.searchFiles(query);
                    if (files.isEmpty()) yield "No files found matching: " + query + ". Try a shorter or different keyword.";
                    StringBuilder sb = new StringBuilder("📁 Drive Results\n─────────────────\n\n");
                    StringBuilder toolResult = new StringBuilder("Found " + files.size() + " file(s):\n");
                    for (int i = 0; i < files.size(); i++) {
                        DriveService.FileSummary f = files.get(i);
                        sb.append(i + 1).append(". ").append(f.name()).append("\n");
                        sb.append("   ID: ").append(f.id()).append("\n");
                        if (f.size() != null) sb.append("   ").append(f.size() / 1024).append(" KB\n");
                        sb.append("   ").append(f.modifiedTime()).append("\n\n");
                        toolResult.append(i + 1).append(". \"").append(f.name()).append("\" — id: ").append(f.id()).append("\n");
                    }
                    toolResult.append("\nNOW call send_drive_file with the correct file_id to deliver it.");
                    sender.send(sb.toString().trim());
                    yield toolResult.toString();
                }

                case "send_drive_file" -> {
                    if (driveService == null) yield "Drive not connected. Send /authorize to link your Google account.";
                    String fileId = str(input, "file_id");
                    DriveService.DriveFileResult file = driveService.downloadFile(fileId);
                    if (file.data() == null || file.data().length == 0)
                        yield "Downloaded file \"" + file.name() + "\" was empty. It may be a Google Workspace file that couldn't be exported, or access was denied.";
                    fileSender.send(file.name(), file.data());
                    yield "SENT_DIRECTLY";
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

        tools.add(tool("search_notes", "Search or retrieve saved notes (shows title, summary, category)",
                props()
                    .opt("query", "string", "Search keywords")
                    .opt("category", "string", "Filter by category")
                    .opt("recent", "boolean", "Set true to get recent notes")
        ));

        tools.add(tool("read_note", "Read the full content of a specific note from Notion",
                props()
                    .req("page_id", "string", "Notion page ID from search_notes results")
        ));

        tools.add(tool("toggle_habit", "Mark or unmark a task as a habit",
                props()
                    .req("task_hint", "string", "Keywords from the task title")
                    .req("enable", "boolean", "true to enable, false to disable")
        ));

        // ── Email tools ─────────────────────────────────────────────────────────
        tools.add(tool("read_emails", "List recent emails from Gmail inbox — shows subject, sender, snippet and ID only",
                props()
                    .opt("count", "integer", "Number of emails to fetch (default: 5, max: 10)")
        ));

        tools.add(tool("read_email", "Read the full body of a specific email by message ID",
                props()
                    .req("message_id", "string", "Message ID from read_emails results")
        ));

        tools.add(tool("draft_email", "Create a professional draft email in Gmail. Always use professional tone. YOU compose subject and body.",
                props()
                    .req("to",      "string", "Recipient email address")
                    .opt("cc",      "string", "CC email address(es)")
                    .req("subject", "string", "Email subject line — write it professionally")
                    .req("body",    "string", "Full email body — always professional and polished, written by you")
        ));

        tools.add(tool("reply_email", "Reply to an email as a draft in Gmail. Always use professional tone. YOU compose the reply body.",
                props()
                    .req("message_id", "string", "ID of the email to reply to (from read_emails results)")
                    .req("body",       "string", "Professional reply body — written by you")
        ));

        // ── Calendar tools ──────────────────────────────────────────────────────
        tools.add(tool("list_events", "List upcoming Google Calendar events",
                props()
                    .opt("days", "integer", "Days ahead to look (default: 7)")
        ));

        tools.add(tool("add_event", "Add a new event to Google Calendar",
                props()
                    .req("title",          "string", "Event title")
                    .req("start_datetime", "string", "Start date or datetime. All-day: yyyy-MM-dd. Timed: ISO-8601 e.g. 2026-04-15T09:00:00")
                    .opt("end_datetime",   "string", "End date/datetime (default: 1 hour after start, or next day for all-day)")
                    .opt("all_day",        "boolean", "Set true for all-day events with no specific time")
                    .opt("description",    "string", "Event description or notes")
        ));

        tools.add(tool("reschedule_event", "Move a Google Calendar event to a new time",
                props()
                    .req("event_hint",        "string", "Keywords from the event title")
                    .req("new_start_datetime","string", "New start datetime ISO-8601")
        ));

        // ── Drive tools ─────────────────────────────────────────────────────────
        tools.add(tool("search_drive", "Search for files in Google Drive",
                props()
                    .req("query", "string", "Filename keywords to search for")
        ));

        tools.add(tool("send_drive_file", "Download a Drive file and send it to Telegram",
                props()
                    .req("file_id", "string", "Google Drive file ID (from search_drive results)")
        ));

        return tools;
    }

    // ── System prompt with injected user profile ──────────────────────────────

    private String buildSystemPrompt(long userId) {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (db != null) {
            Map<String, String> profile = db.getProfile(userId);
            if (!profile.isEmpty()) {
                sb.append("\n\nRemembered user preferences:\n");
                profile.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
            }
        }
        return sb.toString();
    }

    // ── Model routing ─────────────────────────────────────────────────────────

    /**
     * Use Sonnet for tasks that need strong reasoning or long-form generation
     * (emails, complex analysis). Everything else uses Haiku.
     */
    private String pickModel(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("email") || lower.contains("draft") || lower.contains("reply")
                || lower.contains("compose") || lower.contains("write")
                || lower.contains("summarise") || lower.contains("summarize")
                || lower.contains("explain") || lower.contains("analyse") || lower.contains("analyze")
                || lower.contains("report")) {
            return MODEL_SMART;
        }
        return MODEL_FAST;
    }

    // ── Async memory extraction (fires after every exchange) ──────────────────

    private void extractAndSaveMemory(long userId, String userMessage, String assistantResponse) {
        if (db == null) return;
        memoryExtractor.execute(() -> {
            try {
                String prompt = "You extract user preferences and personal facts for a personal assistant app.\n\n"
                        + "User message: " + userMessage + "\n"
                        + "Assistant response: " + assistantResponse + "\n\n"
                        + "Extract ONLY concrete facts/preferences explicitly stated by the user. Do not infer.\n"
                        + "Good examples: name they prefer to be called, time preferences, working style, category preferences.\n"
                        + "Skip: task content, reminders, casual chitchat, anything ephemeral.\n"
                        + "Return JSON array: [{\"key\": \"snake_case_key\", \"value\": \"fact\"}] or [] if nothing to save.\n"
                        + "Return ONLY the JSON array, no other text.";
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", MODEL_FAST);
                body.put("max_tokens", 300);
                body.put("system", "You extract user preferences from chat exchanges. Return only a valid JSON array.");
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                String resp = postJson(body);
                JsonNode root = mapper.readTree(resp);
                String text = root.path("content").get(0).path("text").asText("").trim();
                if (text.isBlank() || text.equals("[]")) return;
                JsonNode arr = mapper.readTree(text);
                if (!arr.isArray()) return;
                for (JsonNode item : arr) {
                    String key   = item.path("key").asText("").trim();
                    String value = item.path("value").asText("").trim();
                    if (!key.isBlank() && !value.isBlank()) db.upsertProfile(userId, key, value);
                }
            } catch (Exception e) {
                System.err.println("Memory extraction error: " + e.getMessage());
            }
        });
    }

    // ── Profile consolidation (called weekly by SchedulerService) ─────────────

    public void consolidateProfile(long userId) {
        if (db == null) return;
        Map<String, String> profile = db.getProfile(userId);
        if (profile.size() < 3) return;
        try {
            StringBuilder facts = new StringBuilder();
            profile.forEach((k, v) -> facts.append(k).append(": ").append(v).append("\n"));
            String prompt = "You manage a user profile for a personal assistant. Current stored facts:\n\n"
                    + facts
                    + "\nConsolidate: merge duplicates, remove redundant/outdated entries, keep most accurate values.\n"
                    + "Return JSON object: {\"key\": \"value\", ...} with only the facts to keep.\n"
                    + "Return ONLY the JSON object, no other text.";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL_FAST);
            body.put("max_tokens", 500);
            body.put("system", "You consolidate user profile facts. Return only valid JSON.");
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            String resp = postJson(body);
            JsonNode root = mapper.readTree(resp);
            String text = root.path("content").get(0).path("text").asText("").trim();
            Map<String, String> consolidated = new LinkedHashMap<>();
            mapper.readTree(text).fields().forEachRemaining(e -> consolidated.put(e.getKey(), e.getValue().asText()));
            if (!consolidated.isEmpty()) db.replaceProfile(userId, consolidated);
        } catch (Exception e) {
            System.err.println("Profile consolidation error: " + e.getMessage());
        }
    }

    // ── Profile helpers (used by TaskBot commands) ────────────────────────────

    public String getProfileText(long userId) {
        if (db == null) return "AI not configured.";
        Map<String, String> profile = db.getProfile(userId);
        if (profile.isEmpty()) return "No preferences saved yet. Just chat naturally and I'll learn your preferences over time!";
        StringBuilder sb = new StringBuilder("🧠 Remembered Preferences\n─────────────────\n\n");
        profile.forEach((k, v) -> sb.append("• ").append(k).append(": ").append(v).append("\n"));
        sb.append("\nUse /forget <key> to remove a specific entry.");
        return sb.toString();
    }

    public boolean forgetProfileKey(long userId, String key) {
        if (db == null) return false;
        Map<String, String> profile = db.getProfile(userId);
        if (!profile.containsKey(key)) return false;
        db.deleteProfileKey(userId, key);
        return true;
    }

    // ── Conversation history (persisted to SQLite) ────────────────────────────
    // Survives bot restarts. Each exchange saves the user message + assistant
    // response as two rows; the DB prunes to MAX_HISTORY rows automatically.

    private void addToHistory(long userId, String userMessage, String assistantResponse) {
        if (db == null) return;
        db.appendConversation(userId, "user",      userMessage);
        db.appendConversation(userId, "assistant", assistantResponse);
    }

    private List<Map<String, Object>> getHistory(long userId) {
        if (db == null) return List.of();
        return db.loadConversation(userId, MAX_HISTORY);
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
            body.put("model", MODEL_FAST);
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
                                         List<Map<String, Object>> tools, String model) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 1024);

            // Cache the system prompt — it's large and identical across turns
            body.put("system", List.of(Map.of(
                "type", "text",
                "text", system,
                "cache_control", Map.of("type", "ephemeral")
            )));

            // Cache the tool definitions — they never change per session
            List<Map<String, Object>> cachedTools = new ArrayList<>(tools);
            if (!cachedTools.isEmpty()) {
                Map<String, Object> last = new LinkedHashMap<>(cachedTools.get(cachedTools.size() - 1));
                last.put("cache_control", Map.of("type", "ephemeral"));
                cachedTools.set(cachedTools.size() - 1, last);
            }
            body.put("tools", cachedTools);
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
                .header("anthropic-beta", "prompt-caching-2024-07-31")
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