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
            YOU MUST NEVER CLAIM A TASK ACTION HAPPENED WITHOUT CALLING THE TOOL.
            - To mark done → MUST call mark_done tool. NEVER say "marked done" without calling it.
            - To create → MUST call create_task tool. NEVER say "created" without calling it.
            - To delete → MUST call delete_task tool. NEVER say "deleted" without calling it.
            - To list/check → MUST call list_tasks or search_tasks FIRST, then respond.
            - Task data in conversation history IS ALWAYS STALE. Ignore it completely.
            - If mark_done returns "Task not found", tell the user — do NOT pretend it worked.
            - EVERY time the user asks about tasks → call list_tasks FIRST. No exceptions.
            ════════════════════════════════════════════════════

            Personality: friendly, casual, like a helpful mate. Keep responses concise.
            Always confirm what you did in plain English after using tools.

            CRITICAL RULES:
            - Only call tools when the user clearly wants to DO something
            - Casual conversation, greetings, or venting → respond naturally with text, NO tool calls
            - Examples that should NEVER trigger tools: "whats up", "how are you", "thanks", "ok", "lol"
            - Examples that SHOULD trigger tools: "add gym tomorrow", "mark report done", "check my inbox", "what's on my calendar", "complete X", "done with X", "finish X"
            - "complete X", "done X", "finish X" → ALWAYS call mark_done with task_hint=X
            - When in doubt whether to call a tool, DON'T — just respond conversationally
            - For multiple actions in one message, call multiple tools
            - Dates/times: always interpret relative to today's date in the system context
            - If a task isn't found by title, say so and ask the user to be more specific
            - For notes, always save them — don't ask for confirmation

            TASK-TO-CALENDAR RULES:
            - When creating a task, decide whether to set add_to_calendar=true
            - SET TRUE for: deadlines, exams, appointments, meetings, interviews, project due dates, events with specific times
            - SET FALSE for: quick todos, chores, daily habits, grocery runs, small errands, routine tasks
            - Only works if the task has a due date — skip for undated tasks
            - Calendar events are one-way: tasks go to calendar, but calendar events do NOT create tasks

            CALENDAR RULES:
            - Interpret all date/times relative to the current date/time in the system context
            - For add_event, if no end time given, default to 1 hour after start
            - For edit_event, delete_event, reschedule_event: the event_hint should be ONLY the key words from the event title — strip out dates, times, and filler words. Example: user says "change my 9th June MRI CGH appointment" → event_hint = "MRI CGH"
            - If no specific time is given (e.g. "add holiday on 1 May", "block off Thursday"), set all_day=true and use only the date (yyyy-MM-dd) for start_datetime
            - When user asks to add something to calendar, ALWAYS use the add_event tool — this creates a Google Calendar event directly

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

            MOOD RULES:
            - When the user tells you how they're feeling or says a mood number, call log_mood
            - If they give two numbers like "4 3", first is mood, second is energy

            JOURNAL RULES:
            - When the user wants to write a journal entry, call add_journal with the content
            - Save the raw text they provide as the journal content
            - If the user mentions mood or energy in the journal (e.g. "mood is 4", "feeling great", "energy low"), include the mood and/or energy parameters (1-5 scale)
            - Mood and energy logged via journal are automatically synced to the mood tracker

            GOAL RULES:
            - When the user talks about goals, objectives, or targets, use add_goal or list_goals
            - When creating a task that relates to an existing goal, call link_task_to_goal after creating the task

            ══ MEMORY SYSTEM — YOU HAVE PERSISTENT MEMORY ══
            - Your "Remembered user preferences" section above is a real database that persists across ALL sessions and restarts.
            - NEVER tell the user you don't have memory or can't remember things between sessions — you absolutely can and do.
            - When the user asks you to remember, keep, or save a preference → call save_preference IMMEDIATELY with a clear key and value.
            - Preferences shown to you at the start of each session are always up to date — apply them without being asked.
            - EXAMPLES of when to call save_preference:
              • "remember I prefer brief summaries" → save_preference(key="email_display_style", value="brief summary only, show full on request")
              • "always reply casually" → save_preference(key="response_tone", value="casual and friendly")
              • "my name is Zul" → save_preference(key="preferred_name", value="Zul")
            ════════════════════════════════════════════════════
            """;

    private final String apiKey;
    private final ZoneId zoneId;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Database db;
    private final ExecutorService memoryExtractor = Executors.newSingleThreadExecutor();
    private MoodService moodService;
    private CountdownService countdownService;
    private GoalService goalService;
    private GoogleTasksService googleTasksService;

    public void setExtraServices(MoodService mood, CountdownService countdown, GoalService goal) {
        this.moodService = mood;
        this.countdownService = countdown;
        this.goalService = goal;
    }

    public void setGoogleTasksService(GoogleTasksService gts) {
        this.googleTasksService = gts;
    }

    private void syncCompleteToGoogle(Task task) {
        if (googleTasksService == null || task.getGoogleTaskId() == null) return;
        try { googleTasksService.completeGoogleTask(task.getGoogleTaskId(), task.getGoogleTasklistId()); }
        catch (Exception e) { System.err.println("Google Tasks sync (complete) failed: " + e.getMessage()); }
    }

    private void syncDeleteToGoogle(Task task) {
        if (googleTasksService == null || task.getGoogleTaskId() == null) return;
        try { googleTasksService.deleteGoogleTask(task.getGoogleTaskId(), task.getGoogleTasklistId()); }
        catch (Exception e) { System.err.println("Google Tasks sync (delete) failed: " + e.getMessage()); }
    }

    private void syncEditToGoogle(Task task) {
        if (googleTasksService == null || task.getGoogleTaskId() == null) return;
        try {
            String dueDate = task.getDueAt() != null ? task.getDueAt().toLocalDate().toString() : null;
            googleTasksService.updateGoogleTask(task.getGoogleTaskId(), task.getGoogleTasklistId(),
                    task.getTitle(), task.getNotes(), dueDate);
        } catch (Exception e) { System.err.println("Google Tasks sync (edit) failed: " + e.getMessage()); }
    }

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
                       GoogleCalendarService googleCalendarService, JournalService journalService,
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
                // Safety net: if user asked to complete/done a task but Claude didn't call mark_done,
                // force the tool call ourselves instead of letting Claude hallucinate.
                String lowerMsg = userMessage.toLowerCase().trim();
                java.util.regex.Matcher completeMatcher = java.util.regex.Pattern
                        .compile("(?i)^(?:complete|done|finish|mark done|mark as done|completed)\\s+(.+)")
                        .matcher(lowerMsg);
                if (completeMatcher.find() && !taskDataFetched) {
                    String taskHint = completeMatcher.group(1).replaceAll("(?i)^(my|the|task)\\s+", "").trim();
                    System.err.println("[SAFETY] Claude skipped mark_done — forcing for: " + taskHint);
                    var opt = taskService.findTaskByTitleHint(userId, taskHint);
                    if (opt.isPresent()) {
                        syncCompleteToGoogle(opt.get());
                        taskService.markDone(userId, opt.get().shortId());
                        taskService.resetReminderIgnoredCount(opt.get().getId());
                        String result = "✅ Done! \"" + opt.get().getTitle() + "\" is marked complete.";
                        addToHistory(userId, userMessage, result);
                        return result;
                    }
                }
                String finalText = allText.toString().trim();
                // If task data was fetched and Claude just listed/repeated tasks verbatim,
                // save a placeholder to avoid stale counts in history.
                // But if Claude generated real analysis/summary, save that — it's valuable.
                String savedResponse;
                if (taskDataFetched && finalText.isEmpty()) {
                    savedResponse = "[Showed current task list from database]";
                } else {
                    savedResponse = finalText.isEmpty() ? "OK" : finalText;
                }
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
                        gmailService, calendarService, driveService, googleCalendarService, journalService,
                        pendingAttachments, sender, fileSender);
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
                                GoogleCalendarService googleCalendarService, JournalService journalService,
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
                    boolean addToCal  = bool(input, "add_to_calendar");

                    LocalDateTime due = parseDate(dueStr);
                    Task.Priority p   = Task.Priority.fromText(priority);
                    Task.Recurrence r = Task.Recurrence.fromText(recurrence);

                    Task created = taskService.createTask(userId, userId,
                            new TaskService.AddTaskRequest(title, p, category, due, r, notes));

                    // Auto-sync to Google Tasks
                    if (googleTasksService != null) {
                        try {
                            String listName = googleTasksService.listNameForPriority(priority.toUpperCase());
                            String dueDate = due != null ? due.toLocalDate().toString() : null;
                            var gTask = googleTasksService.createGoogleTask(title, notes, dueDate, listName);
                            taskService.setGoogleTaskId(created.getId(), gTask.taskId(), gTask.taskListId());
                        } catch (Exception e) {
                            System.err.println("Auto-sync to Google Tasks failed: " + e.getMessage());
                        }
                    }

                    String calNote = "";
                    if (addToCal && googleCalendarService != null && due != null) {
                        try {
                            String eventId = googleCalendarService.createEventForTask(
                                    title, due.toLocalDate().toString(), notes, priority.toUpperCase());
                            taskService.setGoogleEventId(created.getId(), eventId);
                            calNote = "\n📅 Also added to Google Calendar.";
                        } catch (Exception e) {
                            calNote = "\n(Calendar sync failed: " + e.getMessage() + ")";
                        }
                    }
                    yield "Created task: " + taskService.formatTask(created) + calNote;
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
                    // Send the nicely formatted list directly to the user
                    sender.send(sb.toString().trim());

                    // Also return task data to Claude so it can analyse, summarise, or act on it
                    StringBuilder data = new StringBuilder("Task list sent. Data for analysis (").append(tasks.size()).append(" tasks):\n");
                    for (Task t : main) {
                        String p = switch (t.getPriority()) { case HIGH -> "HIGH"; case MEDIUM -> "MED"; case LOW -> "LOW"; default -> ""; };
                        data.append("• [").append(p).append("] ").append(t.getTitle());
                        if (t.getDueAt() != null) data.append(" — due ").append(taskService.friendlyDate(t.getDueAt()));
                        if (!"none".equals(t.getCategory())) data.append(" [").append(t.getCategory()).append("]");
                        data.append("\n");
                    }
                    for (Task t : daily) {
                        data.append("• [DAILY] ").append(t.getTitle());
                        if (t.getDueAt() != null) data.append(" — due ").append(taskService.friendlyDate(t.getDueAt()));
                        data.append("\n");
                    }
                    yield data.toString();
                }

                case "mark_done" -> {
                    String hint = str(input, "task_hint");
                    var opt = taskService.findTaskByTitleHint(userId, hint);
                    if (opt.isEmpty()) yield "Task not found: " + hint;
                    syncCompleteToGoogle(opt.get());
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
                    syncDeleteToGoogle(opt.get());
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
                    // Re-fetch to get updated state, then sync
                    var updated = taskService.findTaskByTitleHint(userId, hint);
                    updated.ifPresent(this::syncEditToGoogle);
                    yield "Rescheduled \"" + opt.get().getTitle() + "\" to " + taskService.friendlyDate(dt);
                }

                case "edit_task" -> {
                    String hint  = str(input, "task_hint");
                    String field = str(input, "field");
                    String value = str(input, "value");
                    boolean ok = taskService.editTaskField(userId, hint, field, value);
                    if (ok) {
                        // Sync updated task to Google Tasks
                        taskService.findTaskByTitleHint(userId, hint).ifPresent(this::syncEditToGoogle);
                    }
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
                    // Return event data to Claude for analysis
                    StringBuilder data = new StringBuilder("Calendar sent. Events for analysis:\n");
                    events.forEach(e -> data.append("• ").append(e.start()).append(" — ").append(e.title()).append("\n"));
                    yield data.toString();
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

                case "edit_event" -> {
                    if (calendarService == null) yield "Calendar not connected. Send /authorize to link your Google account.";
                    String hint    = str(input, "event_hint");
                    String newTitle = str(input, "new_title");
                    String newDesc  = str(input, "new_description");
                    yield calendarService.editEvent(hint, newTitle, newDesc);
                }

                case "delete_event" -> {
                    if (calendarService == null) yield "Calendar not connected. Send /authorize to link your Google account.";
                    String hint = str(input, "event_hint");
                    yield calendarService.deleteEvent(hint);
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

                case "save_preference" -> {
                    String key   = str(input, "key");
                    String value = str(input, "value");
                    if (key == null || value == null) yield "Missing key or value.";
                    if (db != null) db.upsertProfile(userId, key, value);
                    yield "Saved preference: " + key + " = " + value;
                }

                case "log_mood" -> {
                    if (moodService == null) yield "Mood tracking not configured.";
                    int mood = intVal(input, "mood", 0);
                    Integer energy = input.containsKey("energy") ? intVal(input, "energy", 0) : null;
                    if (energy != null && energy == 0) energy = null;
                    MoodService.MoodEntry entry = moodService.logMood(userId, mood, energy);
                    yield moodService.formatMoodLog(entry);
                }

                case "add_journal" -> {
                    if (journalService == null) yield "Journal not configured. Set NOTION_JOURNAL_DB_ID.";
                    String content = str(input, "content");
                    Integer mood = input.containsKey("mood") ? intVal(input, "mood", 0) : null;
                    Integer energy = input.containsKey("energy") ? intVal(input, "energy", 0) : null;
                    if (mood != null && mood == 0) mood = null;
                    if (energy != null && energy == 0) energy = null;
                    // Sync mood/energy to MoodService so they're tracked together
                    if (moodService != null && mood != null) {
                        moodService.logMood(userId, mood, energy);
                    }
                    var result = journalService.saveJournal(content, mood, energy, zoneId);
                    StringBuilder resp = new StringBuilder("\uD83D\uDCD3 Journal saved: \"" + result.title() + "\" [" + result.type() + "]");
                    if (!result.tags().isEmpty()) resp.append("\nTags: ").append(String.join(", ", result.tags()));
                    if (mood != null) resp.append("\nMood: ").append(mood).append("/5");
                    if (energy != null) resp.append(" | Energy: ").append(energy).append("/5");
                    yield resp.toString();
                }

                case "add_countdown" -> {
                    if (countdownService == null) yield "Countdown service not available.";
                    String name2 = str(input, "name");
                    String dateStr = str(input, "target_date");
                    java.time.LocalDate target;
                    try { target = java.time.LocalDate.parse(dateStr); } catch (Exception e) { yield "Couldn't parse date: " + dateStr; }
                    var cd = countdownService.addCountdown(userId, name2, target);
                    yield countdownService.formatCountdown(cd);
                }

                case "list_countdowns" -> {
                    if (countdownService == null) yield "Countdown service not available.";
                    var list = countdownService.getCountdowns(userId);
                    if (list.isEmpty()) yield "No countdowns set.";
                    StringBuilder sb = new StringBuilder("\u23F3 Countdowns\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
                    list.forEach(cd -> sb.append(countdownService.formatCountdown(cd)).append("\n"));
                    sender.send(sb.toString().trim());
                    yield "Countdowns shown.";
                }

                case "add_goal" -> {
                    if (goalService == null) yield "Goal service not available.";
                    String title = str(input, "title");
                    String dateStr = str(input, "target_date");
                    java.time.LocalDate target = null;
                    if (dateStr != null && !dateStr.isBlank()) {
                        try { target = java.time.LocalDate.parse(dateStr); } catch (Exception ignored) {}
                    }
                    var goal = goalService.createGoal(userId, title, target);
                    yield "\uD83C\uDFAF Goal set: " + goal.title() + (goal.targetDate() != null ? " \u2014 target: " + goal.targetDate() : "");
                }

                case "list_goals" -> {
                    if (goalService == null) yield "Goal service not available.";
                    var goals = goalService.getActiveGoals(userId);
                    if (goals.isEmpty()) yield "No active goals.";
                    StringBuilder sb = new StringBuilder("\uD83C\uDFAF Goals\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
                    goals.forEach(g -> sb.append(goalService.formatGoal(g)).append("\n\n"));
                    sender.send(sb.toString().trim());
                    yield "Goals shown.";
                }

                case "link_task_to_goal" -> {
                    if (goalService == null) yield "Goal service not available.";
                    String goalHint = str(input, "goal_hint");
                    String taskHint = str(input, "task_hint");
                    var goal = goalService.findGoalByHint(userId, goalHint);
                    if (goal.isEmpty()) yield "Goal not found: " + goalHint;
                    var task = taskService.findTaskByTitleHint(userId, taskHint);
                    if (task.isEmpty()) yield "Task not found: " + taskHint;
                    goalService.linkTask(goal.get().id(), task.get().getId());
                    yield "Linked \"" + task.get().getTitle() + "\" to goal \"" + goal.get().title() + "\".";
                }

                case "sync_google_tasks" -> {
                    if (googleCalendarService == null) yield "Google not connected. Send /authorize to link your Google account.";
                    List<Task> active = taskService.getActiveTasks(userId);
                    int synced = 0;
                    for (Task task : active) {
                        if (task.getGoogleTaskId() == null) {
                            try {
                                String listName = googleTasksService != null
                                        ? googleTasksService.listNameForPriority(task.getPriority().name())
                                        : "Medium Priority";
                                String dueDate = task.getDueAt() != null
                                        ? task.getDueAt().toLocalDate().toString() : null;
                                var gTask = googleTasksService.createGoogleTask(
                                        task.getTitle(), task.getNotes(), dueDate, listName);
                                taskService.setGoogleTaskId(task.getId(), gTask.taskId(), gTask.taskListId());
                                synced++;
                            } catch (Exception e) {
                                System.err.println("Failed to sync task " + task.getTitle() + ": " + e.getMessage());
                            }
                        }
                    }
                    yield "Synced " + synced + " new task(s) to Google Tasks." + (synced == 0 ? " All tasks already synced." : "");
                }

                case "time_block_suggestion" -> {
                    if (googleCalendarService == null) yield "Calendar not connected. Send /authorize to link your Google account.";
                    var events = googleCalendarService.getEventsForTimeBlocking(2);
                    var tasks = taskService.getTopPendingTasks(userId, 5);
                    if (tasks.isEmpty()) yield "No pending tasks to schedule.";
                    StringBuilder prompt = new StringBuilder("Here are my calendar events for today/tomorrow:\n");
                    events.forEach(e -> prompt.append("- ").append(e.startDate()).append(" to ").append(e.endDate()).append(": ").append(e.title()).append("\n"));
                    prompt.append("\nHere are my top pending tasks:\n");
                    tasks.forEach(t -> prompt.append("- [").append(t.getPriority()).append("] ").append(t.getTitle())
                            .append(t.getDueAt() != null ? " (due " + taskService.friendlyDate(t.getDueAt()) + ")" : "").append("\n"));
                    prompt.append("\nIdentify free time gaps of 30 minutes or more. Suggest which tasks to schedule in which gaps, with reasoning. Return as a friendly, conversational message with specific time slots.");
                    // Use main agent to generate the suggestion
                    yield prompt.toString();
                }

                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            System.err.println("Tool execution error [" + name + "]: " + e.getMessage());
            // Sanitize — don't leak SQL errors, file paths, or stack details to the LLM
            return "Error: could not complete " + name + ". Please try again.";
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
                    .opt("add_to_calendar", "boolean", "Set true to also create a Google Calendar event. Use your judgement: set true for significant tasks with a due date (meetings, deadlines, exams, appointments) and false for small/routine tasks (chores, daily habits, quick todos)")
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

        tools.add(tool("save_preference", "Save a user preference or personal fact to persistent memory (survives restarts and new sessions)",
                props()
                    .req("key",   "string", "Short snake_case identifier, e.g. email_display_style, response_tone, preferred_name")
                    .req("value", "string", "The preference or fact to remember")
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

        tools.add(tool("edit_event", "Edit a Google Calendar event's title or description",
                props()
                    .req("event_hint", "string", "Keywords from the event title to find it")
                    .opt("new_title", "string", "New title for the event")
                    .opt("new_description", "string", "New description for the event")
        ));

        tools.add(tool("delete_event", "Delete/cancel a Google Calendar event",
                props()
                    .req("event_hint", "string", "Keywords from the event title to find it")
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

        tools.add(tool("log_mood", "Log the user's mood and/or energy level (1-5 each)",
                props()
                    .req("mood", "integer", "Mood rating 1-5")
                    .opt("energy", "integer", "Energy rating 1-5")
        ));

        tools.add(tool("add_journal", "Save a journal entry to Notion",
                props()
                    .req("content", "string", "The journal text")
                    .opt("mood", "integer", "Mood 1-5 if mentioned")
                    .opt("energy", "integer", "Energy 1-5 if mentioned")
        ));

        tools.add(tool("add_countdown", "Create a countdown to a future date",
                props()
                    .req("name", "string", "Name of the event/milestone")
                    .req("target_date", "string", "Target date yyyy-MM-dd")
        ));

        tools.add(tool("list_countdowns", "List all countdown milestones", props()));

        tools.add(tool("add_goal", "Create a new goal to track",
                props()
                    .req("title", "string", "Goal title")
                    .opt("target_date", "string", "Target date yyyy-MM-dd if applicable")
        ));

        tools.add(tool("list_goals", "List all active goals with progress", props()));

        tools.add(tool("link_task_to_goal", "Link a task to a goal",
                props()
                    .req("goal_hint", "string", "Keywords from the goal title")
                    .req("task_hint", "string", "Keywords from the task title")
        ));

        tools.add(tool("sync_google_tasks", "Sync all unsynced local tasks to Google Tasks. Use when the user asks to sync, update, or push tasks to Google Tasks.", props()));
        tools.add(tool("time_block_suggestion", "Suggest time blocks for tasks based on calendar availability", props()));

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
                String prompt = "You extract user preferences and personal facts from a conversation for a personal assistant.\n\n"
                        + "User message: " + userMessage + "\n"
                        + "Assistant response: " + assistantResponse + "\n\n"
                        + "Extract any preference, habit, personal fact, or behavioural instruction the USER expressed.\n"
                        + "Be generous — if the user stated how they want things done, save it.\n"
                        + "Good examples to save:\n"
                        + "  - How they want content displayed (brief vs full, formatted vs plain)\n"
                        + "  - Communication style preferences (casual, concise, detailed)\n"
                        + "  - Scheduling habits, working hours, routines\n"
                        + "  - Personal context (job, school, family situation)\n"
                        + "  - Names, nicknames, relationships\n"
                        + "Skip: specific task content, one-off requests, casual greetings.\n"
                        + "Return JSON array: [{\"key\": \"snake_case_key\", \"value\": \"preference\"}] or [] if nothing.\n"
                        + "Return ONLY the JSON array, no other text.";
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", MODEL_FAST);
                body.put("max_tokens", 300);
                body.put("system", "You extract user preferences from chat exchanges. Return only a valid JSON array.");
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                String resp = postJson(body);
                JsonNode root = mapper.readTree(resp);
                String text = root.path("content").path(0).path("text").asText("").trim();
                // Strip markdown code fences if Claude wraps JSON in ```json ... ```
                if (text.startsWith("```")) {
                    text = text.replaceAll("^```[a-z]*\\s*", "").replaceAll("\\s*```$", "").trim();
                }
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
            String text = root.path("content").path(0).path("text").asText("").trim();
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
            String text = root.path("content").path(0).path("text").asText("").trim();
            return text.isBlank() ? null : text;
        } catch (Exception e) { return null; }
    }

    // ── HTTP / builder helpers ────────────────────────────────────────────────

    private Map<String, Object> callApi(String system, List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools, String model) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", MODEL_SMART.equals(model) ? 4096 : 1024);

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
                .header("anthropic-version", "2023-06-01") // stable version; new features via anthropic-beta header
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
    private boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(v.toString().trim());
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
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); } catch (Exception ignored) {}
        // Date-only (e.g. "2026-04-15") → treat as start of day
        try { return java.time.LocalDate.parse(s).atStartOfDay(); } catch (Exception ignored) {}
        return null;
    }
}