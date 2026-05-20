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
    private static final int    MAX_TURNS   = 8; // max tool call rounds per message

    /** Tool names whose execution mutates the user's task list. After any of these
     *  fires, TaskBot refreshes the pinned active-tasks summary. */
    private static final java.util.Set<String> TASK_MUTATING_TOOLS = java.util.Set.of(
            "create_task", "mark_done", "mark_all_done", "delete_task",
            "snooze_task", "reschedule_task", "edit_task",
            "set_reminder_interval", "toggle_habit", "sync_google_tasks"
    );

    private static final String BASE_SYSTEM_PROMPT = """
            You are Proton, a smart personal productivity assistant for Zul (Haizul Ali Seron).
            Timezone: Asia/Singapore. Default categories: work, school, personal, daily.

            ══ ANTI-PHANTOM RULES — YOUR #1 DIRECTIVE ══
            YOU ARE FORBIDDEN FROM CLAIMING YOU DID SOMETHING WITHOUT ACTUALLY CALLING THE TOOL.
            "Phantom completing" = saying "done!", "marked", "deleted", "added", "scheduled",
            "sent", "drafted", "rescheduled", "snoozed", "saved", "created" — without having
            called the matching tool in THIS turn. This is a critical failure. NEVER do it.

            BEFORE you type a word of confirmation, ask yourself:
              "Did I call a tool for this action in the current turn?"
            If NO → call the tool now. Do not respond yet.
            If YES → confirm plainly, exactly referencing what the tool returned.

            If you are unsure which task/event/email the user means, call the matching
            SEARCH tool first (search_tasks, search_notes, search_drive, list_events, read_emails).
            If the tool returns "not found" or an error, SAY SO honestly. Never paper over
            a failure with a fake "done!". The user will catch you and lose trust.
            ════════════════════════════════════════════════════

            ══ TASK INTEGRITY — ABSOLUTE RULES, NO EXCEPTIONS ══
            - To mark done → MUST call mark_done tool. NEVER say "marked done" without calling it.
            - To mark multiple done → MUST call mark_all_done (with priority_filter if applicable).
            - To create → MUST call create_task tool. NEVER say "created" without calling it.
            - To delete → MUST call delete_task tool. NEVER say "deleted" without calling it.
            - To snooze → MUST call snooze_task tool. NEVER say "snoozed" without calling it.
            - To reschedule → MUST call reschedule_task tool. NEVER say "rescheduled" without calling it.
            - To list/check → MUST call list_tasks or search_tasks FIRST, then respond.
            - Task data in conversation history IS ALWAYS STALE. Ignore it completely.
            - If mark_done returns "Task not found", tell the user — do NOT pretend it worked.
            - EVERY time the user asks about tasks → call list_tasks FIRST. No exceptions.
            - If the user's message contains the words "mark", "complete", "done", "finish",
              "delete", "remove", "snooze", "reschedule" + a task hint → ALWAYS call the
              corresponding tool. Do NOT just reply conversationally.
            ════════════════════════════════════════════════════

            Personality: friendly, casual, like a helpful mate. Keep responses concise.
            Always confirm what you did in plain English after using tools.

            ══ KNOW YOUR CAPABILITIES ══
            You have tools for: tasks, calendar, email, drive, notes, journal, mood, countdowns, goals, Google Tasks sync, preferences.
            These features exist as SLASH COMMANDS (handled before reaching you — do NOT say they don't exist):
            - /pomodoro [work] [break] [rounds] — start a Pomodoro focus timer
            - /stoppomodoro — stop the current Pomodoro
            - /brief — morning briefing
            - /mood — log mood & energy
            - /journal <entry> — save journal entry
            - /countdown, /countdowns — manage countdowns
            - /goal, /goals — manage goals
            - /gtasks — view Google Tasks
            - /synctasks — sync tasks to Google Tasks
            - /authorize — link Google account
            If the user asks about ANY of these, tell them the command exists. NEVER say "I don't have that feature" for anything listed above.
            ════════════════════════════

            CRITICAL RULES:
            - Only call tools when the user clearly wants to DO something
            - Casual conversation, greetings, or venting → respond naturally with text, NO tool calls
            - Examples that should NEVER trigger tools: "whats up", "how are you", "thanks", "ok", "lol"
            - Examples that SHOULD trigger tools: "add gym tomorrow", "mark report done", "check my inbox", "what's on my calendar", "complete X", "done with X", "finish X"
            - "complete X", "done X", "finish X" → ALWAYS call mark_done with task_hint=X
            - "complete all daily tasks", "done both daily tasks", "finish all daily" → call mark_all_done with priority_filter=daily
            - "complete all tasks", "mark everything done" → call mark_all_done (no filter)
            - When in doubt whether to call a tool, DON'T — just respond conversationally
            - For multiple actions in one message, call multiple tools
            - Dates/times: always interpret relative to today's date in the system context
            - If a task isn't found by title, say so and ask the user to be more specific
            - For notes, always save them — don't ask for confirmation

            TASK vs CALENDAR — SEPARATE SYSTEMS:
            - Tasks and calendar are SEPARATE. Creating a task NEVER adds to Google Calendar.
            - If the user says "add to calendar" or mentions a class/event/appointment → use add_event (calendar tool)
            - If the user says "add task" or a todo/reminder → use create_task (task tool)
            - Do NOT use both for the same item unless explicitly asked

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
    private ComposioService composio;
    private KimiService kimi;

    // ── Google offline tracking ───────────────────────────────────────────────
    // When invalid_grant fires, we set googleOfflineSince to now. Every Google
    // tool short-circuits with ERROR_AUTH_FAILED for the next 60 minutes instead
    // of hammering Google with a dead token. Cleared by clearGoogleOffline()
    // (called from TaskBot on successful /authorize).
    private volatile long googleOfflineSince = 0L; // epoch millis, 0 = online
    // Track whether we already notified the user this offline-window so we don't spam
    private volatile boolean googleOfflineNotified = false;
    private static final long GOOGLE_OFFLINE_TTL_MS = 60 * 60 * 1000L; // 1 hour

    public void markGoogleOffline(String reason) {
        if (googleOfflineSince == 0L) {
            System.err.println("[GOOGLE OFFLINE] " + reason);
        }
        googleOfflineSince = System.currentTimeMillis();
    }

    public void clearGoogleOffline() {
        googleOfflineSince = 0L;
        googleOfflineNotified = false;
        System.err.println("[GOOGLE OFFLINE] cleared");
    }

    public boolean isGoogleOffline() {
        if (googleOfflineSince == 0L) return false;
        if (System.currentTimeMillis() - googleOfflineSince > GOOGLE_OFFLINE_TTL_MS) {
            // TTL expired — give it another chance
            googleOfflineSince = 0L;
            googleOfflineNotified = false;
            return false;
        }
        return true;
    }

    /** If Google is offline, return a structured ERROR_AUTH_FAILED string and (once
     *  per window) push a heads-up message to the user. Returns null if online. */
    private String googleOfflineCheck(String toolName, MessageSender sender) {
        if (!isGoogleOffline()) return null;
        if (!googleOfflineNotified && sender != null) {
            googleOfflineNotified = true;
            try {
                sender.send("⚠️ Your Google access has expired (token revoked). "
                        + "Run /authorize to re-link your account. "
                        + "Until then, I can't read or write Gmail, Calendar, Drive, or Google Tasks.");
            } catch (Exception ignored) {}
        }
        return "ERROR_AUTH_FAILED: Google is offline — token revoked. "
             + "Tool [" + toolName + "] did NOT execute. "
             + "Tell the user the action did NOT happen and they need to run /authorize. "
             + "Do NOT pretend it worked.";
    }

    public void setExtraServices(MoodService mood, CountdownService countdown, GoalService goal) {
        this.moodService = mood;
        this.countdownService = countdown;
        this.goalService = goal;
    }

    public void setGoogleTasksService(GoogleTasksService gts) {
        this.googleTasksService = gts;
    }

    public void setComposioService(ComposioService composio) {
        this.composio = composio;
    }

    public void setKimiService(KimiService kimi) {
        this.kimi = kimi;
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
                       JournalService journalService,
                       List<Attachment> pendingAttachments,
                       MessageSender sender, FileSender fileSender) {
        return chat(userId, chatId, userMessage, taskService, notionService, noteService,
                journalService, pendingAttachments, sender, fileSender, null);
    }

    public String chat(long userId, long chatId, String userMessage,
                       TaskService taskService, NotionService notionService, NoteService noteService,
                       JournalService journalService,
                       List<Attachment> pendingAttachments,
                       MessageSender sender, FileSender fileSender,
                       Runnable onTasksMutated) {
        // Mutable single-element array so we can flip the flag from inside this method
        // and have try/finally see the latest value regardless of which return fires.
        final boolean[] tasksMutated = {false};
        try {
        String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String systemWithDate = buildSystemPrompt(userId)
                + "\n\nCurrent date/time: " + now + " (" + zoneId + ")"
                + renderRecentActions(userId);
        String model = pickModel(userMessage);

        // Build messages list — rolling history + current message
        List<Map<String, Object>> messages = new ArrayList<>(getHistory(userId));
        messages.add(Map.of("role", "user", "content", userMessage));

        List<Map<String, Object>> tools = buildTools();
        StringBuilder allText = new StringBuilder();
        // Track whether a DB-backed task tool was called so we don't save stale task
        // data back into conversation history (which would cause future hallucinations).
        boolean taskDataFetched = false;
        // Track whether ANY tool call happened across all turns — used by the phantom-action
        // detector to avoid false-positives when Claude already did work then text-confirmed.
        boolean anyToolCalled = false;
        // Track the most recent tool error (ERROR_* prefix) seen in this conversation.
        // If Claude then claims success in its assistant text, we override with the truth.
        String lastToolError = null;
        String lastErroredTool = null;

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
                // Safety net: if user asked to complete/done tasks but Claude didn't call the tool,
                // force the tool call ourselves instead of letting Claude hallucinate.
                String lowerMsg = userMessage.toLowerCase().trim();

                // ── Bulk completion patterns (must check before single-task) ──
                java.util.regex.Matcher bulkMatcher = java.util.regex.Pattern
                        .compile("(?i)^(?:complete|done|finish|mark done|mark as done|completed)\\s+(?:all|both|every)\\s+(?:(?:my|the)\\s+)?(.+?)(?:\\s+tasks?)?$")
                        .matcher(lowerMsg);
                if (bulkMatcher.find() && !taskDataFetched) {
                    String qualifier = bulkMatcher.group(1).trim();
                    System.err.println("[SAFETY] Claude skipped mark_all_done — forcing for: " + qualifier);
                    // Check if it's a priority filter
                    String result;
                    if (qualifier.matches("(?i)daily|high|medium|low")) {
                        Task.Priority p = Task.Priority.fromText(qualifier);
                        int n = taskService.markAllDoneByPriority(userId, p);
                        result = n > 0 ? "✅ Marked " + n + " " + qualifier + " task(s) done!"
                                       : "No active " + qualifier + " tasks to complete.";
                    } else {
                        // Might be a category or just "all tasks"
                        int n = taskService.markAllDone(userId);
                        result = n > 0 ? "✅ Marked all " + n + " task(s) done!"
                                       : "No active tasks to complete.";
                    }
                    tasksMutated[0] = true;
                    if (db != null) db.logAction(userId, "mark_all_done",
                            "filter=" + qualifier + " (safety-forced)", "success", null, result);
                    addToHistory(userId, userMessage, result);
                    return result;
                }

                // ── Single task completion ──
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
                        tasksMutated[0] = true;
                        String result = "✅ Done! \"" + opt.get().getTitle() + "\" is marked complete.";
                        if (db != null) db.logAction(userId, "mark_done",
                                "hint=" + taskHint + " (safety-forced)", "success", null, result);
                        addToHistory(userId, userMessage, result);
                        return result;
                    } else {
                        // Task not found — don't let Claude's hallucinated "done" through
                        String result = "❌ Couldn't find a task matching \"" + taskHint + "\". Check /tasks for your current list.";
                        if (db != null) db.logAction(userId, "mark_done",
                                "hint=" + taskHint + " (safety-forced)", "error", "ERROR_NOT_FOUND", result);
                        addToHistory(userId, userMessage, result);
                        return result;
                    }
                }

                // ── Delete patterns ──
                java.util.regex.Matcher deleteMatcher = java.util.regex.Pattern
                        .compile("(?i)^(?:delete|remove|cancel|drop)\\s+(?:my\\s+|the\\s+|task\\s+)*(.+)")
                        .matcher(lowerMsg);
                if (deleteMatcher.find() && !taskDataFetched) {
                    String taskHint = deleteMatcher.group(1).trim();
                    // Avoid false positives for non-task things
                    if (!taskHint.matches("(?i).*(email|event|note|meeting|appointment|draft|calendar).*")) {
                        System.err.println("[SAFETY] Claude skipped delete_task — forcing for: " + taskHint);
                        var opt = taskService.findTaskByTitleHint(userId, taskHint);
                        if (opt.isPresent()) {
                            syncDeleteToGoogle(opt.get());
                            taskService.deleteTask(userId, opt.get().shortId());
                            String result = "🗑 Deleted: \"" + opt.get().getTitle() + "\".";
                            addToHistory(userId, userMessage, result);
                            return result;
                        } else {
                            String result = "❌ Couldn't find a task matching \"" + taskHint + "\". Check /tasks for your current list.";
                            addToHistory(userId, userMessage, result);
                            return result;
                        }
                    }
                }

                // ── Snooze patterns (e.g. "snooze gym 2 hours") ──
                java.util.regex.Matcher snoozeMatcher = java.util.regex.Pattern
                        .compile("(?i)^snooze\\s+(.+?)(?:\\s+(?:by\\s+)?(\\d+)\\s*(h|hr|hrs|hour|hours|m|min|mins|minutes))?$")
                        .matcher(lowerMsg);
                if (snoozeMatcher.find() && !taskDataFetched) {
                    String taskHint = snoozeMatcher.group(1).replaceAll("(?i)^(my|the|task)\\s+", "").trim();
                    int hours = 24;
                    if (snoozeMatcher.group(2) != null) {
                        int n = Integer.parseInt(snoozeMatcher.group(2));
                        String unit = snoozeMatcher.group(3).toLowerCase();
                        hours = unit.startsWith("m") ? Math.max(1, n / 60) : n;
                    }
                    System.err.println("[SAFETY] Claude skipped snooze_task — forcing for: " + taskHint);
                    var opt = taskService.findTaskByTitleHint(userId, taskHint);
                    if (opt.isPresent()) {
                        taskService.snoozeTask(userId, opt.get().shortId(), java.time.Duration.ofHours(hours));
                        String result = "😴 Snoozed \"" + opt.get().getTitle() + "\" by " + hours + "h.";
                        addToHistory(userId, userMessage, result);
                        return result;
                    } else {
                        String result = "❌ Couldn't find a task matching \"" + taskHint + "\". Check /tasks for your current list.";
                        addToHistory(userId, userMessage, result);
                        return result;
                    }
                }

                // ── Phantom-action detector — last line of defence ──
                String finalTextCheck = allText.toString();
                String lowerResp = finalTextCheck.toLowerCase();
                boolean claimsAction = !finalTextCheck.isEmpty() && lowerResp.matches(
                    "(?s).*\\b(i've |i have |i just |successfully |done\\b|marked |deleted |snoozed |rescheduled |created |added |scheduled |drafted |sent |saved )\\b.*"
                ) && lowerResp.matches(
                    "(?s).*\\b(task|email|event|note|reminder|meeting|appointment|draft|message|journal|goal)\\b.*"
                );
                boolean userAskedAction = lowerMsg.matches(
                    "(?i).*(add|create|mark|complete|done|finish|delete|remove|snooze|reschedule|send|draft|schedule|save|log).*"
                );

                // Case A: Claude called NO tool at all but claims action — original detector.
                if (claimsAction && userAskedAction && !anyToolCalled) {
                    System.err.println("[SAFETY] Phantom-action (no tool called). userMsg=" + userMessage + " resp=" + finalTextCheck);
                    String honest = "⚠️ I may not have actually completed that — I didn't hit the tool. Could you rephrase or be more specific so I can run it properly?";
                    addToHistory(userId, userMessage, honest);
                    return honest;
                }

                // Case B: A tool returned an ERROR_* but Claude is still claiming success.
                // This is the case that bit us on draft_email today. Replace Claude's text
                // with an honest summary derived from the actual tool error.
                if (claimsAction && lastToolError != null) {
                    System.err.println("[SAFETY] Phantom-action (tool errored, Claude claimed success). tool=" + lastErroredTool
                            + " err=" + lastToolError.substring(0, Math.min(120, lastToolError.length()))
                            + " resp=" + finalTextCheck);
                    String honest = honestErrorReply(lastErroredTool, lastToolError);
                    addToHistory(userId, userMessage, honest);
                    return honest;
                }
                String finalText = allText.toString().trim();
                // History grounding (item 7): when tools fired, prefer a compact action
                // summary derived from the audit log over Claude's chatty prose. Stops
                // the bot from reasoning off its own apologies and rationalisations
                // when the conversation goes sideways.
                String savedResponse;
                if (anyToolCalled) {
                    String compact = buildCompactActionSummary(userId);
                    if (compact != null && !compact.isBlank()) {
                        savedResponse = compact;
                    } else if (taskDataFetched && finalText.isEmpty()) {
                        savedResponse = "[Showed current task list from database]";
                    } else {
                        // Fall back to Claude's text but trimmed
                        savedResponse = finalText.isEmpty() ? "[tool ran, no text]"
                                : (finalText.length() > 400 ? finalText.substring(0, 400) + "..." : finalText);
                    }
                } else {
                    // Pure conversation — keep the prose
                    savedResponse = finalText.isEmpty() ? "OK" : finalText;
                }
                addToHistory(userId, userMessage, savedResponse);
                extractAndSaveMemory(userId, userMessage, savedResponse);
                return finalText.isEmpty() ? "\uD83D\uDC4D" : finalText;
            }

            // Execute all tool calls
            anyToolCalled = true;
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
                        journalService, pendingAttachments, sender, fileSender);
                System.out.println("Tool [" + toolName + "] result: " + result.substring(0, Math.min(120, result.length())));
                if (TASK_MUTATING_TOOLS.contains(toolName)) tasksMutated[0] = true;
                // Track tool errors — phantom detector uses these to override false success claims.
                String errCat = null;
                if (result != null && result.startsWith("ERROR_")) {
                    lastToolError = result;
                    lastErroredTool = toolName;
                    int colon = result.indexOf(':');
                    errCat = colon > 0 ? result.substring(0, colon) : "ERROR_UNKNOWN";
                } else {
                    if (lastErroredTool != null && lastErroredTool.equals(toolName)) {
                        lastToolError = null;
                        lastErroredTool = null;
                    }
                }
                // Persist to action_log — grounding source-of-truth for what actually happened.
                if (db != null) {
                    try {
                        String inputSummary = summarizeInput(input);
                        String status = errCat != null ? "error" : "success";
                        String resultSummary = result == null ? null
                                : (result.length() > 200 ? result.substring(0, 200) : result);
                        db.logAction(userId, toolName, inputSummary, status, errCat, resultSummary);
                    } catch (Exception ignored) {}
                }
                toolResults.add(Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", result));
            }

            messages.add(Map.of("role", "user", "content", toolResults));
        }

        return "I got a bit stuck on that one. Try rephrasing?";
        } finally {
            if (tasksMutated[0] && onTasksMutated != null) {
                try { onTasksMutated.run(); } catch (Exception e) {
                    System.err.println("onTasksMutated callback failed: " + e.getMessage());
                }
            }
        }
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    private String executeTool(String name, Map<String, Object> input, long userId,
                                TaskService taskService, NotionService notionService, NoteService noteService,
                                JournalService journalService,
                                List<Attachment> pendingAttachments,
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
                    String title = switch(filter) {
                        case "today"   -> "🗓 Due Today";
                        case "overdue" -> "⚠️ Overdue";
                        case "stale"   -> "🧊 Stale";
                        case "done"    -> "✅ Completed";
                        default        -> "📋 Active Tasks";
                    };
                    // Send the compact HTML list directly to the user
                    sender.send(taskService.formatTaskListHtml(title, tasks));

                    // Also return plain task data to Claude so it can analyse or act on it
                    List<Task> main  = tasks.stream().filter(t -> t.getPriority() != Task.Priority.DAILY).toList();
                    List<Task> daily = tasks.stream().filter(t -> t.getPriority() == Task.Priority.DAILY).toList();
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
                        sb.append("📌 <b>").append(TaskService.esc(note.title())).append("</b>\n");
                        sb.append("   📁 ").append(TaskService.esc(note.category()));
                        if (!note.tags().isEmpty()) sb.append("  🏷 ").append(TaskService.esc(String.join(", ", note.tags())));
                        sb.append("  📅 ").append(note.created()).append("\n");
                        if (!note.summary().isBlank()) sb.append("   ").append(TaskService.esc(note.summary())).append("\n");
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
                    sender.send(TaskService.esc(content));
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
                    if (composio == null) yield "Gmail not configured. Set composio.api.key in application.properties.";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("max_results", intVal(input, "count", 5));
                    args.put("verbose", true);
                    String query = str(input, "query");
                    if (query != null && !query.isBlank()) args.put("query", query);
                    ComposioService.Result r = composio.execute("GMAIL_FETCH_EMAILS", args);
                    if (r.isError()) yield r.error();
                    JsonNode msgs = r.data().path("messages");
                    if (!msgs.isArray() || msgs.size() == 0) yield "No emails found.";
                    StringBuilder sb = new StringBuilder("📧 Inbox (" + msgs.size() + ")\n─────────────────\n\n");
                    StringBuilder ids = new StringBuilder("Emails shown. IDs for reply:\n");
                    for (int i = 0; i < msgs.size(); i++) {
                        JsonNode m = msgs.get(i);
                        String id = m.path("messageId").asText("");
                        String threadId = m.path("threadId").asText("");
                        String subject = m.path("subject").asText("(no subject)");
                        String from = m.path("sender").asText("");
                        String date = m.path("messageTimestamp").asText("");
                        String snippet = m.path("preview").path("body").asText("");
                        if (snippet.isBlank()) snippet = m.path("preview").asText("");
                        sb.append(i + 1).append(". <b>").append(TaskService.esc(subject)).append("</b>\n");
                        sb.append("   From: ").append(TaskService.esc(from)).append("\n");
                        sb.append("   ").append(date).append("\n");
                        if (!snippet.isBlank()) sb.append("   ").append(TaskService.esc(snippet)).append("\n");
                        sb.append("   <code>").append(id).append("</code>\n\n");
                        ids.append("• ").append(subject).append(" → message_id=").append(id)
                                .append(" thread_id=").append(threadId).append("\n");
                    }
                    sender.send(sb.toString().trim());
                    yield ids.toString();
                }

                case "read_email" -> {
                    if (composio == null) yield "Gmail not configured. Set composio.api.key in application.properties.";
                    String messageId = str(input, "message_id");
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("message_id", messageId);
                    args.put("format", "full");
                    ComposioService.Result r = composio.execute("GMAIL_FETCH_MESSAGE_BY_MESSAGE_ID", args);
                    if (r.isError()) yield r.error();
                    JsonNode d = r.data();
                    String subject = d.path("subject").asText("(no subject)");
                    String from = d.path("sender").asText("");
                    String date = d.path("messageTimestamp").asText("");
                    String threadId = d.path("threadId").asText("");
                    String body = d.path("messageText").asText("");
                    StringBuilder sb = new StringBuilder();
                    sb.append("📧 <b>").append(TaskService.esc(subject)).append("</b>\n");
                    sb.append("From: ").append(TaskService.esc(from)).append("\n");
                    sb.append("Date: ").append(date).append("\n");
                    sb.append("─────────────────\n\n");
                    if (body.length() > 3000) body = body.substring(0, 3000) + "\n\n[truncated — email is long]";
                    sb.append(TaskService.esc(body));
                    sender.send(sb.toString().trim());
                    yield "Email content shown. message_id=" + messageId + " thread_id=" + threadId;
                }

                case "draft_email" -> {
                    if (composio == null) yield "Gmail not configured. Set composio.api.key in application.properties.";
                    String to      = str(input, "to");
                    String cc      = str(input, "cc");
                    String subject = str(input, "subject");
                    String body    = str(input, "body");
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("recipient_email", to);
                    args.put("subject", subject);
                    args.put("body", body);
                    if (cc != null && !cc.isBlank()) args.put("cc", List.of(cc.split("\\s*,\\s*")));
                    ComposioService.Result r = composio.execute("GMAIL_CREATE_EMAIL_DRAFT", args);
                    if (r.isError()) yield r.error();
                    String draftId = r.data().path("response_data").path("id").asText("");
                    String attNote = (pendingAttachments != null && !pendingAttachments.isEmpty())
                            ? " — note: " + pendingAttachments.size() + " queued attachment(s) NOT included (attachments via brokered API not yet supported)" : "";
                    String idNote = draftId.isBlank() ? "" : " [draft id: " + draftId + "]";
                    yield "Draft saved" + idNote + ". Open Gmail to review and send." + attNote;
                }

                case "reply_email" -> {
                    if (composio == null) yield "Gmail not configured. Set composio.api.key in application.properties.";
                    String threadId = str(input, "thread_id");
                    String recipient = str(input, "to");
                    String body      = str(input, "body");
                    if (threadId == null || threadId.isBlank())
                        yield "ERROR_BAD_INPUT: reply_email needs thread_id. Get it from read_emails first.";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("thread_id", threadId);
                    args.put("recipient_email", recipient);
                    args.put("message_body", body);
                    ComposioService.Result r = composio.execute("GMAIL_REPLY_TO_THREAD", args);
                    if (r.isError()) yield r.error();
                    String attNote = (pendingAttachments != null && !pendingAttachments.isEmpty())
                            ? " — note: " + pendingAttachments.size() + " queued attachment(s) NOT included" : "";
                    yield "Reply sent to thread " + threadId + "." + attNote;
                }

                // ── Calendar ───────────────────────────────────────────────────────
                case "list_events" -> {
                    if (composio == null) yield "Calendar not configured. Set composio.api.key in application.properties.";
                    int days = intVal(input, "days", 7);
                    String timeMin = LocalDateTime.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                    String timeMax = LocalDateTime.now(zoneId).plusDays(days).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("calendarId", "primary");
                    args.put("timeMin", timeMin);
                    args.put("timeMax", timeMax);
                    args.put("singleEvents", true);
                    args.put("orderBy", "startTime");
                    args.put("maxResults", 25);
                    ComposioService.Result r = composio.execute("GOOGLECALENDAR_EVENTS_LIST", args);
                    if (r.isError()) yield r.error();
                    JsonNode items = r.data().path("items");
                    if (!items.isArray() || items.size() == 0) yield "No events in the next " + days + " days.";
                    StringBuilder sb = new StringBuilder("<b>📅 Calendar</b> (next " + days + " days)\n─────────────────\n");
                    StringBuilder data = new StringBuilder("Calendar sent. Events for analysis:\n");
                    for (JsonNode e : items) {
                        String title = e.path("summary").asText("(no title)");
                        String start = e.path("start").path("dateTime").asText(e.path("start").path("date").asText(""));
                        String loc = e.path("location").asText("");
                        sb.append("📅 ").append(TaskService.esc(start)).append("  <b>").append(TaskService.esc(title)).append("</b>");
                        if (!loc.isBlank()) sb.append("  📍 ").append(TaskService.esc(loc));
                        sb.append("\n");
                        data.append("• ").append(start).append(" — ").append(title)
                            .append(" [id=").append(e.path("id").asText("")).append("]\n");
                    }
                    sender.send(sb.toString().trim());
                    yield data.toString();
                }

                case "add_event" -> {
                    if (composio == null) yield "Calendar not configured. Set composio.api.key in application.properties.";
                    String title   = str(input, "title");
                    String startDt = str(input, "start_datetime");
                    String endDt   = str(input, "end_datetime");
                    String desc    = str(input, "description");
                    boolean allDay = boolVal(input, "all_day");
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("calendar_id", "primary");
                    args.put("summary", title);
                    // Composio wants naive datetime (no Z, no offset) and a separate timezone field.
                    args.put("start_datetime", startDt.replace("Z", "").replaceAll("\\+\\d{2}:\\d{2}$", ""));
                    args.put("timezone", zoneId.getId());
                    if (desc != null && !desc.isBlank()) args.put("description", desc);
                    if (allDay) {
                        args.put("event_duration_hour", 24);
                    } else if (endDt != null && !endDt.isBlank()) {
                        try {
                            var s = LocalDateTime.parse(startDt.replace("Z","").replaceAll("\\+\\d{2}:\\d{2}$",""));
                            var e = LocalDateTime.parse(endDt.replace("Z","").replaceAll("\\+\\d{2}:\\d{2}$",""));
                            long mins = java.time.Duration.between(s, e).toMinutes();
                            args.put("event_duration_hour", (int)(mins / 60));
                            args.put("event_duration_minutes", (int)(mins % 60));
                        } catch (Exception ex) {
                            args.put("event_duration_hour", 1);
                        }
                    } else {
                        args.put("event_duration_hour", 1);
                    }
                    ComposioService.Result r = composio.execute("GOOGLECALENDAR_CREATE_EVENT", args);
                    if (r.isError()) yield r.error();
                    String id = r.data().path("response_data").path("id").asText(r.data().path("id").asText(""));
                    yield "Added to calendar: \"" + title + "\" — " + startDt + (id.isBlank() ? "" : " [event id: " + id + "]");
                }

                case "reschedule_event" -> {
                    if (composio == null) yield "Calendar not configured. Set composio.api.key in application.properties.";
                    String hint  = str(input, "event_hint");
                    String newDt = str(input, "new_start_datetime");
                    String eventId = findEventId(hint);
                    if (eventId == null) yield "ERROR_NOT_FOUND: No event matching \"" + hint + "\".";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("calendar_id", "primary");
                    args.put("event_id", eventId);
                    args.put("start_time", newDt);
                    args.put("timezone", zoneId.getId());
                    ComposioService.Result r = composio.execute("GOOGLECALENDAR_PATCH_EVENT", args);
                    if (r.isError()) yield r.error();
                    yield "Rescheduled \"" + hint + "\" to " + newDt + ".";
                }

                case "edit_event" -> {
                    if (composio == null) yield "Calendar not configured. Set composio.api.key in application.properties.";
                    String hint    = str(input, "event_hint");
                    String newTitle = str(input, "new_title");
                    String newDesc  = str(input, "new_description");
                    String eventId = findEventId(hint);
                    if (eventId == null) yield "ERROR_NOT_FOUND: No event matching \"" + hint + "\".";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("calendar_id", "primary");
                    args.put("event_id", eventId);
                    if (newTitle != null && !newTitle.isBlank()) args.put("summary", newTitle);
                    if (newDesc != null && !newDesc.isBlank())  args.put("description", newDesc);
                    ComposioService.Result r = composio.execute("GOOGLECALENDAR_PATCH_EVENT", args);
                    if (r.isError()) yield r.error();
                    yield "Updated event \"" + hint + "\".";
                }

                case "delete_event" -> {
                    if (composio == null) yield "Calendar not configured. Set composio.api.key in application.properties.";
                    String hint = str(input, "event_hint");
                    String eventId = findEventId(hint);
                    if (eventId == null) yield "ERROR_NOT_FOUND: No event matching \"" + hint + "\".";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("calendar_id", "primary");
                    args.put("event_id", eventId);
                    ComposioService.Result r = composio.execute("GOOGLECALENDAR_DELETE_EVENT", args);
                    if (r.isError()) yield r.error();
                    yield "Deleted event \"" + hint + "\".";
                }

                // ── Google Drive ───────────────────────────────────────────────────
                case "search_drive" -> {
                    if (composio == null) yield "Drive not configured. Set composio.api.key in application.properties.";
                    String query = str(input, "query");
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("q", "name contains '" + query.replace("'", "\\'") + "' and trashed=false");
                    args.put("pageSize", 10);
                    ComposioService.Result r = composio.execute("GOOGLEDRIVE_FIND_FILE", args);
                    if (r.isError()) yield r.error();
                    JsonNode files = r.data().path("files");
                    if (!files.isArray() || files.size() == 0) yield "No files found matching: " + query + ".";
                    StringBuilder sb = new StringBuilder("📁 Drive Results\n─────────────────\n\n");
                    StringBuilder toolResult = new StringBuilder("Found " + files.size() + " file(s):\n");
                    for (int i = 0; i < files.size(); i++) {
                        JsonNode f = files.get(i);
                        String fname = f.path("name").asText("");
                        String fid = f.path("id").asText("");
                        long fsize = f.path("size").asLong(0);
                        String modTime = f.path("modifiedTime").asText("");
                        sb.append(i + 1).append(". ").append(fname).append("\n");
                        sb.append("   ID: ").append(fid).append("\n");
                        if (fsize > 0) sb.append("   ").append(fsize / 1024).append(" KB\n");
                        sb.append("   ").append(modTime).append("\n\n");
                        toolResult.append(i + 1).append(". \"").append(fname).append("\" — id: ").append(fid).append("\n");
                    }
                    toolResult.append("\nNOW call send_drive_file with the correct file_id to deliver it.");
                    sender.send(sb.toString().trim());
                    yield toolResult.toString();
                }

                case "send_drive_file" -> {
                    if (composio == null) yield "Drive not configured. Set composio.api.key in application.properties.";
                    String fileId = str(input, "file_id");
                    yield downloadAndSendDriveFile(fileId, fileSender);
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
                    StringBuilder sb = new StringBuilder("<b>⏳ Countdowns</b>\n─────────────────\n\n");
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
                    StringBuilder sb = new StringBuilder("<b>🎯 Goals</b>\n─────────────────\n\n");
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
                    if (googleTasksService == null) yield "Google Tasks not connected. Send /authorize to link your Google account.";
                    String off = googleOfflineCheck(name, sender); if (off != null) yield off;
                    List<Task> active = taskService.getActiveTasks(userId);
                    int synced = 0;
                    for (Task task : active) {
                        if (task.getGoogleTaskId() == null) {
                            try {
                                String listName = googleTasksService.listNameForPriority(task.getPriority().name());
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
                    if (composio == null) yield "Calendar not configured. Set composio.api.key in application.properties.";
                    String timeMin = LocalDateTime.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                    String timeMax = LocalDateTime.now(zoneId).plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("calendarId", "primary");
                    args.put("timeMin", timeMin);
                    args.put("timeMax", timeMax);
                    args.put("singleEvents", true);
                    args.put("orderBy", "startTime");
                    args.put("maxResults", 50);
                    ComposioService.Result evRes = composio.execute("GOOGLECALENDAR_EVENTS_LIST", args);
                    if (evRes.isError()) yield evRes.error();
                    var tasks = taskService.getTopPendingTasks(userId, 5);
                    if (tasks.isEmpty()) yield "No pending tasks to schedule.";
                    StringBuilder prompt = new StringBuilder("Here are my calendar events for today/tomorrow:\n");
                    for (JsonNode e : evRes.data().path("items")) {
                        String s = e.path("start").path("dateTime").asText(e.path("start").path("date").asText(""));
                        String en = e.path("end").path("dateTime").asText(e.path("end").path("date").asText(""));
                        prompt.append("- ").append(s).append(" to ").append(en).append(": ").append(e.path("summary").asText("")).append("\n");
                    }
                    prompt.append("\nHere are my top pending tasks:\n");
                    tasks.forEach(t -> prompt.append("- [").append(t.getPriority()).append("] ").append(t.getTitle())
                            .append(t.getDueAt() != null ? " (due " + taskService.friendlyDate(t.getDueAt()) + ")" : "").append("\n"));
                    prompt.append("\nIdentify free time gaps of 30 minutes or more. Suggest which tasks to schedule in which gaps, with reasoning. Return as a friendly, conversational message with specific time slots.");
                    yield prompt.toString();
                }

                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            System.err.println("Tool execution error [" + name + "]: " + e.getMessage());
            return categorizeError(name, e);
        }
    }

    // ── Tool error categorization ─────────────────────────────────────────────
    // Returns a structured, instruction-laden error string. The ERROR_<CATEGORY>:
    // prefix is a hard signal to Claude that the action FAILED. The trailing
    // instruction tells Claude exactly how to respond to the user — leaving no
    // wiggle room for hallucinated success.

    /** Resolve a Google Calendar event ID from a fuzzy text hint via FIND_EVENT.
     *  Returns null if no event matches. */
    private String findEventId(String hint) {
        if (composio == null || hint == null || hint.isBlank()) return null;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("calendar_id", "primary");
        args.put("query", hint);
        args.put("max_results", 5);
        args.put("single_events", true);
        args.put("order_by", "startTime");
        // Search recent + upcoming
        args.put("timeMin", LocalDateTime.now(zoneId).minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
        args.put("timeMax", LocalDateTime.now(zoneId).plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
        ComposioService.Result r = composio.execute("GOOGLECALENDAR_FIND_EVENT", args);
        if (r.isError()) return null;
        JsonNode items = r.data().path("items");
        if (!items.isArray() || items.size() == 0) {
            items = r.data().path("events");
            if (!items.isArray() || items.size() == 0) return null;
        }
        return items.get(0).path("id").asText(null);
    }

    /** Download a Drive file via Composio. Handles Workspace export by looking up
     *  the mime type from metadata first. Pushes bytes to Telegram via fileSender. */
    private String downloadAndSendDriveFile(String fileId, FileSender fileSender) {
        // Fetch metadata to detect Workspace mime type + filename
        Map<String, Object> metaArgs = new LinkedHashMap<>();
        metaArgs.put("file_id", fileId);
        ComposioService.Result meta = composio.execute("GOOGLEDRIVE_GET_FILE_METADATA", metaArgs);
        String fname = "drive-file";
        String mime  = "";
        if (!meta.isError() && meta.data() != null) {
            fname = meta.data().path("name").asText(fname);
            mime  = meta.data().path("mimeType").asText("");
        }
        Map<String, Object> dlArgs = new LinkedHashMap<>();
        dlArgs.put("file_id", fileId);
        if (mime.startsWith("application/vnd.google-apps.")) {
            String exportMime = switch (mime) {
                case "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "application/vnd.google-apps.document" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                default -> "application/pdf";
            };
            dlArgs.put("mime_type", exportMime);
            String ext = exportMime.contains("spreadsheet") ? ".xlsx"
                       : exportMime.contains("presentation") ? ".pptx"
                       : exportMime.contains("wordprocessing") ? ".docx"
                       : ".pdf";
            if (!fname.contains(".")) fname += ext;
        }
        ComposioService.Result r = composio.execute("GOOGLEDRIVE_DOWNLOAD_FILE", dlArgs);
        if (r.isError()) return r.error();
        // Composio returns file content either as base64 inline or as a download URL.
        JsonNode d = r.data();
        byte[] bytes = null;
        String b64 = d.path("file_content").asText(d.path("file").asText(d.path("content").asText("")));
        if (!b64.isBlank()) {
            try { bytes = java.util.Base64.getDecoder().decode(b64); }
            catch (Exception ignored) {
                try { bytes = java.util.Base64.getUrlDecoder().decode(b64); } catch (Exception ignored2) {}
            }
        }
        if (bytes == null || bytes.length == 0) {
            String url = d.path("download_url").asText(d.path("url").asText(d.path("file_url").asText("")));
            if (!url.isBlank()) {
                try {
                    HttpRequest dlReq = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                    HttpResponse<byte[]> dlRes = http.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (dlRes.statusCode() == 200) bytes = dlRes.body();
                } catch (Exception ex) {
                    return "ERROR_TOOL: Failed to fetch Drive file from URL: " + ex.getMessage();
                }
            }
        }
        if (bytes == null || bytes.length == 0)
            return "ERROR_TOOL: Drive download returned no content for file " + fileId + ".";
        fileSender.send(fname, bytes);
        return "SENT_DIRECTLY";
    }

    private String categorizeError(String toolName, Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String low = msg.toLowerCase(java.util.Locale.ROOT);
        Throwable cause = e.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";

        // Auth failure — refresh token revoked/expired. Most common Google failure.
        if (low.contains("invalid_grant") || causeMsg.contains("invalid_grant")
                || low.contains("token has been expired") || low.contains("token has been revoked")
                || low.contains("unauthorized_client")) {
            markGoogleOffline("invalid_grant on " + toolName);
            return "ERROR_AUTH_FAILED: Google access token is revoked or expired. "
                 + "STOP. The action did NOT happen. "
                 + "Tell the user verbatim: \"Your Google access expired — please run /authorize to re-link your account.\" "
                 + "Do NOT say the action succeeded. Do NOT retry with a different tool.";
        }

        // Bad input — malformed date, missing required field, etc.
        if (e instanceof java.time.format.DateTimeParseException
                || e instanceof IllegalArgumentException
                || low.contains("invalid date") || low.contains("invalid time")
                || low.contains("could not parse") || low.contains("unparseable")) {
            return "ERROR_BAD_INPUT: " + toolName + " rejected the inputs. Reason: "
                 + safeMsg(msg) + ". "
                 + "Tell the user what was wrong (in plain English) and ask them to clarify. "
                 + "Do NOT say the action succeeded.";
        }

        // Not found — task hint, event hint, file id didn't match anything.
        if (low.contains("not found") || low.contains("no match") || low.contains("404")) {
            return "ERROR_NOT_FOUND: " + toolName + " couldn't find what was requested. "
                 + "Tell the user no match was found and suggest /tasks or /search. "
                 + "Do NOT pretend the action succeeded.";
        }

        // Transient — network, rate limit, temporary 5xx. Retry-safe.
        if (low.contains("timeout") || low.contains("timed out")
                || low.contains("connection reset") || low.contains("503")
                || low.contains("502") || low.contains("rate limit")
                || low.contains("429") || low.contains("too many requests")) {
            return "ERROR_TRANSIENT: " + toolName + " hit a temporary network/service issue. "
                 + "Tell the user the system briefly hiccupped and you'll need them to try again in a moment. "
                 + "Do NOT claim it succeeded.";
        }

        // Unknown — anything else. Don't leak details to the LLM.
        return "ERROR_UNKNOWN: " + toolName + " failed for an unexpected reason. "
             + "Tell the user the action did NOT complete and ask them to try again or rephrase. "
             + "Do NOT pretend it worked.";
    }

    /** Convert a structured ERROR_* string into a user-facing honest message.
     *  Used when the phantom detector catches Claude claiming success after a real failure. */
    static String honestErrorReply(String toolName, String errorString) {
        if (errorString == null) errorString = "";
        if (errorString.startsWith("ERROR_AUTH_FAILED")) {
            return "⚠️ I tried to do that, but Google rejected the request — your access has expired. "
                 + "Please run /authorize to re-link your account, then try again. (I should not have said it succeeded — sorry.)";
        }
        if (errorString.startsWith("ERROR_NOT_FOUND")) {
            return "⚠️ I couldn't find what you asked about — no match. "
                 + "Try /tasks or be a bit more specific with the title. (I should not have claimed I did it — sorry.)";
        }
        if (errorString.startsWith("ERROR_BAD_INPUT")) {
            return "⚠️ I couldn't run that — the input wasn't valid (e.g. the date or required field). "
                 + "Could you rephrase? (I should not have claimed it worked — sorry.)";
        }
        if (errorString.startsWith("ERROR_TRANSIENT")) {
            return "⚠️ Network or service hiccup on the way to running that. Try again in a moment. "
                 + "(I should not have claimed success — sorry.)";
        }
        return "⚠️ The action did not actually go through (tool: " + (toolName == null ? "unknown" : toolName) + "). "
             + "Could you try again? (I should not have claimed it worked — sorry.)";
    }

    /** One-line summary of tool inputs for the audit log. Truncates and avoids dumping huge bodies. */
    private static String summarizeInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (i++ > 0) sb.append(", ");
            String v = String.valueOf(e.getValue());
            if (v.length() > 60) v = v.substring(0, 60) + "...";
            sb.append(e.getKey()).append("=").append(v);
            if (sb.length() > 300) { sb.append(",..."); break; }
        }
        return sb.toString();
    }

    /** Compact one-line-per-action summary of what the bot just did THIS turn.
     *  Saved to conversation_history instead of Claude's chatty prose so the next
     *  turn sees facts, not rationalizations. Looks at the last 30 seconds of action_log. */
    private String buildCompactActionSummary(long userId) {
        if (db == null) return null;
        List<Database.ActionLogEntry> recent = db.getRecentActions(userId, 30, 6);
        if (recent.isEmpty()) return null;
        // Newest first; build oldest-first for readability
        java.util.Collections.reverse(recent);
        StringBuilder sb = new StringBuilder();
        for (Database.ActionLogEntry a : recent) {
            String mark = "error".equals(a.status()) ? "✗" : "✓";
            sb.append("[").append(mark).append(" ").append(a.toolName());
            if (a.inputSummary() != null && !a.inputSummary().isBlank()) {
                String trimmed = a.inputSummary().length() > 80
                        ? a.inputSummary().substring(0, 80) + "..." : a.inputSummary();
                sb.append("(").append(trimmed).append(")");
            }
            if ("error".equals(a.status())) {
                sb.append(" → ").append(a.errorCategory() == null ? "ERROR" : a.errorCategory());
            }
            sb.append("] ");
        }
        return sb.toString().trim();
    }

    /** Render the recent action log as a compact block for inclusion in the system prompt.
     *  Gives Claude a factual record of what it actually did, separate from chatty history. */
    private String renderRecentActions(long userId) {
        if (db == null) return "";
        List<Database.ActionLogEntry> recent = db.getRecentActions(userId, 1800, 8); // last 30 min, max 8
        if (recent.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n══ YOUR RECENT ACTIONS (source of truth — trust this over your own prose) ══\n");
        long now = System.currentTimeMillis() / 1000;
        for (Database.ActionLogEntry a : recent) {
            long ago = now - a.ts();
            String when = ago < 60 ? ago + "s ago" : (ago / 60) + "m ago";
            String marker = "error".equals(a.status()) ? "❌ FAILED" : "✅ ok";
            sb.append("• ").append(when).append(" — ").append(a.toolName())
              .append(" (").append(marker).append(")");
            if (a.inputSummary() != null && !a.inputSummary().isBlank()) {
                sb.append(" — input: ").append(a.inputSummary());
            }
            if ("error".equals(a.status())) {
                sb.append(" — ").append(a.errorCategory() == null ? "ERROR_UNKNOWN" : a.errorCategory());
            }
            sb.append("\n");
        }
        sb.append("If the user asks 'did you X?', refer to this log — never invent answers.\n");
        sb.append("══════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /** Trim and sanitize an exception message for inclusion in error strings to Claude. */
    private static String safeMsg(String msg) {
        if (msg == null) return "(no detail)";
        String trimmed = msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
        // Strip newlines / SQL details
        return trimmed.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }

    // ── Tool definitions ──────────────────────────────────────────────────────

    private List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool("create_task", "Create a new task or reminder. Does NOT add to Google Calendar — use add_event for calendar.",
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

        tools.add(tool("save_preference", "Save a user preference or personal fact to persistent memory (survives restarts and new sessions)",
                props()
                    .req("key",   "string", "Short snake_case identifier, e.g. email_display_style, response_tone, preferred_name")
                    .req("value", "string", "The preference or fact to remember")
        ));

        // ── Email tools ─────────────────────────────────────────────────────────
        tools.add(tool("read_emails", "List recent emails from ALL Gmail folders (inbox, spam, trash, promotions, social) — shows subject, sender, snippet and ID",
                props()
                    .opt("count", "integer", "Number of emails to fetch (default: 5, max: 10)")
                    .opt("query", "string", "Gmail search query to filter (e.g. 'in:inbox', 'from:someone@gmail.com', 'is:unread', 'in:spam'). Omit for all recent emails.")
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

        tools.add(tool("reply_email", "Reply within an existing Gmail thread. Sends immediately (does NOT create a draft). Always use professional tone. YOU compose the reply body.",
                props()
                    .req("thread_id", "string", "Thread ID of the email to reply to (from read_emails or read_email results)")
                    .req("to",        "string", "Primary recipient email address")
                    .req("body",      "string", "Professional reply body — written by you")
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
    /**
     * Foreground always goes to the Chat/Reasoning/Agentic slot (Sonnet) per
     * routing-architecture.md. Per-message branching by content type is
     * deliberately gone — coherence within a conversation beats marginal
     * cost savings on simple messages.
     */
    private String pickModel(String userMessage) {
        return ModelRouting.AGENTIC;
    }

    // ── Async memory extraction (fires after every exchange) ──────────────────

    private void extractAndSaveMemory(long userId, String userMessage, String assistantResponse) {
        if (db == null) return;
        // Skip extraction for very short or clearly non-preference messages
        String lower = userMessage.toLowerCase().trim();
        if (lower.length() < 10 || lower.matches("(?i)^(ok|yes|no|thanks|lol|haha|cool|nice|done|hi|hey|yo|sup|what|how).*")) return;
        // Skip task commands — these don't contain preference info
        if (lower.matches("(?i)^(add|create|mark|complete|delete|done|finish|snooze|reschedule|show|list|check|search)\\s.*")) return;

        memoryExtractor.execute(() -> {
            try {
                // Load existing preferences so the extractor can avoid duplicates
                Map<String, String> existing = db.getProfile(userId);
                StringBuilder existingStr = new StringBuilder();
                if (!existing.isEmpty()) {
                    existingStr.append("\nAlready stored preferences (DO NOT re-save these or similar):\n");
                    existing.forEach((k, v) -> existingStr.append("- ").append(k).append(": ").append(v).append("\n"));
                }

                String prompt = "You extract user preferences from a conversation for a personal assistant.\n\n"
                        + "User message: " + userMessage + "\n"
                        + "Assistant response: " + assistantResponse + "\n"
                        + existingStr + "\n"
                        + "Rules:\n"
                        + "- ONLY extract explicit, durable preferences or personal facts the USER stated.\n"
                        + "- DO NOT extract anything already covered by existing preferences above.\n"
                        + "- DO NOT create multiple keys for the same concept — use ONE canonical key.\n"
                        + "- Skip: task content, one-off requests, greetings, commands, things the assistant said.\n"
                        + "- Be conservative — when in doubt, return [].\n"
                        + "Return JSON array: [{\"key\": \"snake_case_key\", \"value\": \"preference\"}] or [] if nothing new.\n"
                        + "Return ONLY the JSON array, no other text.";
                String sys = "You extract NEW user preferences from chat. Return only a valid JSON array. Be conservative — only extract clear, durable preferences not already stored.";

                // Memory summarization slot → Kimi. Falls back to Haiku-on-Anthropic
                // if Kimi isn't configured, so the bot still works without a Moonshot key.
                String text;
                if (kimi != null) {
                    text = kimi.complete(ModelRouting.MEMORY_SUMMARIZATION, sys, prompt, 800);
                    if (text == null) return; // Kimi error already logged
                    text = text.trim();
                } else {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", ModelRouting.REFLECTIONS);
                    body.put("max_tokens", 800);
                    body.put("system", sys);
                    body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                    String resp = postJson(body);
                    JsonNode root = mapper.readTree(resp);
                    String stopReason = root.path("stop_reason").asText("");
                    if ("max_tokens".equals(stopReason)) {
                        System.err.println("Memory extraction skipped: model hit max_tokens (output truncated). Consider raising the cap.");
                        return;
                    }
                    text = root.path("content").path(0).path("text").asText("").trim();
                }
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
        if (profile.size() < 5) return;
        try {
            StringBuilder facts = new StringBuilder();
            profile.forEach((k, v) -> facts.append(k).append(": ").append(v).append("\n"));
            String prompt = "You manage a user profile for a personal assistant. Current stored facts (" + profile.size() + " entries):\n\n"
                    + facts
                    + "\nAggressively consolidate this profile:\n"
                    + "1. MERGE all entries about the same topic into ONE canonical key (e.g. merge daily_arabic_habit, arabic_learning_routine, daily_arabic_study into just daily_routines)\n"
                    + "2. REMOVE redundant entries that repeat the same information\n"
                    + "3. REMOVE obvious/low-value entries (e.g. has_child: yes if already stated in family context)\n"
                    + "4. Keep max 15-20 high-value entries total\n"
                    + "5. Use clear, concise values — no redundancy\n"
                    + "Return JSON object: {\"key\": \"value\", ...} with only the consolidated facts.\n"
                    + "Return ONLY the JSON object, no other text.";
            String sys = "You aggressively consolidate user profile data. Merge duplicates, remove redundancy. Target 15-20 entries max. Return only valid JSON.";
            String text;
            if (kimi != null) {
                text = kimi.complete(ModelRouting.MEMORY_SUMMARIZATION, sys, prompt, 2500);
                if (text == null) return;
                text = text.trim();
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", ModelRouting.REFLECTIONS);
                body.put("max_tokens", 2500);
                body.put("system", sys);
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                String resp = postJson(body);
                JsonNode root = mapper.readTree(resp);
                String stopReason = root.path("stop_reason").asText("");
                if ("max_tokens".equals(stopReason)) {
                    System.err.println("Profile consolidation aborted: model hit max_tokens. "
                            + "Profile has " + profile.size() + " entries — raise the cap.");
                    return;
                }
                text = root.path("content").path(0).path("text").asText("").trim();
            }
            if (text.startsWith("```")) {
                text = text.replaceAll("^```[a-z]*\\s*", "").replaceAll("\\s*```$", "").trim();
            }
            Map<String, String> consolidated = new LinkedHashMap<>();
            mapper.readTree(text).fields().forEachRemaining(e -> consolidated.put(e.getKey(), e.getValue().asText()));
            if (!consolidated.isEmpty() && consolidated.size() <= profile.size()) {
                db.replaceProfile(userId, consolidated);
                System.out.println("Profile consolidated: " + profile.size() + " → " + consolidated.size() + " entries");
            }
        } catch (Exception e) {
            // Include a snippet of what we got so future failures are debuggable.
            String detail = e.getMessage();
            if (detail != null && detail.length() > 250) detail = detail.substring(0, 250) + "...";
            System.err.println("Profile consolidation error: " + detail);
        }
    }

    // ── Profile helpers (used by TaskBot commands) ────────────────────────────

    public String getProfileText(long userId) {
        if (db == null) return "AI not configured.";
        Map<String, String> profile = db.getProfile(userId);
        if (profile.isEmpty()) return "No preferences saved yet. Just chat naturally and I'll learn your preferences over time!";
        StringBuilder sb = new StringBuilder("🧠 <b>Remembered Preferences</b>\n─────────────────\n\n");
        profile.forEach((k, v) -> sb.append("• ").append(TaskService.esc(k)).append(": ").append(TaskService.esc(v)).append("\n"));
        sb.append("\nUse /forget &lt;key&gt; to remove a specific entry.");
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

    /**
     * Self-audit: one-shot Claude call (no history, no tools) that summarises the
     * bot's last 24 hours of behaviour. Caller (SchedulerService) supplies the raw
     * stats and log tail; this method formats and asks Claude for a brief verdict.
     *
     * @return the audit summary text, or null if the call failed (caller decides
     *         whether to ping the user with a fallback "audit unavailable" message).
     */
    public String runDailyAudit(String actionStats, String errLogTail, String googleHealth) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You're auditing the last 24 hours of a personal Telegram task-bot. ");
        prompt.append("You won't fix anything — just produce a brief readable verdict.\n\n");

        prompt.append("══ Action log stats (last 24h) ══\n");
        prompt.append(actionStats == null || actionStats.isBlank() ? "(no actions logged)\n" : actionStats);
        prompt.append("\n");

        prompt.append("══ Error log tail (last ~200 lines) ══\n");
        prompt.append(errLogTail == null || errLogTail.isBlank() ? "(empty)\n" : errLogTail);
        prompt.append("\n");

        prompt.append("══ Google service status ══\n");
        prompt.append(googleHealth == null ? "unknown\n" : googleHealth + "\n");

        prompt.append("\nProduce a report in this exact format (Telegram HTML, under 250 words):\n\n");
        prompt.append("<b>🌙 Nightly Audit</b>  [STATUS]\n");
        prompt.append("─────────────────\n");
        prompt.append("Replace [STATUS] with 🟢 healthy / 🟡 watch / 🔴 issue.\n");
        prompt.append("Then 1-3 short bullet points of what stood out (ONLY genuine concerns — don't pad).\n");
        prompt.append("Then ONE concrete suggestion if applicable, else skip the section.\n\n");
        prompt.append("If the day was clean, the entire report can be just '🟢 All clear — no notable issues.'\n");
        prompt.append("Don't manufacture findings. Be terse.");

        String sys = "You are a code/runtime auditor. You produce short, factual reports. " +
                     "Never fabricate issues. Use the supplied data only — do not speculate about " +
                     "things you can't see. Output is plain text + simple Telegram HTML (b, i, code).";

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", ModelRouting.REFLECTIONS); // periodic reflection over recent history
            body.put("max_tokens", 600);
            body.put("system", sys);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt.toString())));
            String resp = postJson(body);
            JsonNode root = mapper.readTree(resp);
            String text = root.path("content").path(0).path("text").asText("").trim();
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            System.err.println("Daily audit call failed: " + e.getMessage());
            return null;
        }
    }

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
            body.put("model", ModelRouting.REFLECTIONS);
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
            body.put("max_tokens", ModelRouting.AGENTIC.equals(model) ? 4096 : 1024);

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