package com.haizul.taskbot;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.toIntExact;

public class TaskBot implements LongPollingSingleThreadUpdateConsumer {

    private enum PendingKind { LOCATION_TASK_HINT, GOOGLE_OAUTH_CODE }
    private record PendingInput(PendingKind kind, String target) {}

    private final TelegramClient telegramClient;
    private final TaskService taskService;
    private final ClaudeService claudeService;
    private final WhisperService whisperService;
    private final NotionService notionService;
    private final NoteService noteService;
    private final GoogleAuthService googleAuthService;
    private final String botUsername;

    // New services (set via setter after construction)
    private GoogleTasksService googleTasksService;
    private JournalService journalService;
    private InboxService inboxService;
    private MoodService moodService;
    private CountdownService countdownService;
    private GoalService goalService;
    private ComposioService composioService;

    private final long allowedUserId;
    private final long groupChatId;
    private final String botToken;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<Long, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    // Files sent by the user via Telegram, queued for email attachment
    private final Map<Long, List<Attachment>> pendingAttachments = new ConcurrentHashMap<>();
    // Timestamps for pending data — used to expire stale entries
    private final Map<Long, Long> pendingInputTimestamps     = new ConcurrentHashMap<>();
    private final Map<Long, Long> pendingAttachmentTimestamps = new ConcurrentHashMap<>();

    public TaskBot(BotConfig config, TaskService taskService,
                   ClaudeService claudeService, WhisperService whisperService,
                   NotionService notionService, NoteService noteService,
                   GoogleAuthService googleAuthService) {
        this.telegramClient   = new OkHttpTelegramClient(config.getBotToken());
        this.botToken         = config.getBotToken();
        this.taskService      = taskService;
        this.claudeService    = claudeService;
        this.whisperService   = whisperService;
        this.notionService    = notionService;
        this.noteService      = noteService;
        this.googleAuthService = googleAuthService;
        this.botUsername      = config.getBotUsername();
        this.allowedUserId    = config.getAllowedUserId();
        this.groupChatId      = config.getGroupChatId();
    }

    public void setExtraServices(GoogleTasksService gtasks,
                                  JournalService journal, InboxService inbox,
                                  MoodService mood, CountdownService countdown, GoalService goal,
                                  ComposioService composio) {
        this.googleTasksService    = gtasks;
        this.journalService        = journal;
        this.inboxService          = inbox;
        this.moodService           = mood;
        this.countdownService      = countdown;
        this.goalService           = goal;
        this.composioService       = composio;
    }

    @Override
    public void consume(Update update) {
        try {
            // Security: reject anyone who isn't the allowed user
            long userId = 0;
            if (update.hasMessage() && update.getMessage().getFrom() != null)
                userId = update.getMessage().getFrom().getId();
            else if (update.hasCallbackQuery())
                userId = update.getCallbackQuery().getFrom().getId();
            if (allowedUserId != 0 && userId != allowedUserId) return;

            // Group chat inbox — everything goes through simple task-or-note flow
            if (update.hasMessage() && groupChatId != 0
                    && update.getMessage().getChatId() == groupChatId) {
                handleGroupInbox(update);
                return;
            }

            if (update.hasMessage()) {
                if (update.getMessage().hasText())           handleMessage(update);
                else if (update.getMessage().hasVoice())     handleVoice(update);
                else if (update.getMessage().hasDocument())  handleDocument(update);
                else if (update.getMessage().hasPhoto())     handlePhoto(update);
                else if (update.getMessage().hasLocation())  handleLocation(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (update.hasMessage()) sendText(update.getMessage().getChatId(), "⚠️ Something went wrong. Please try again.");
        }
    }

    public TelegramClient getTelegramClient() { return telegramClient; }
    public String getBotUsername()            { return botUsername; }

    // ── Voice ────────────────────────────────────────────────────────────────

    private void handleVoice(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();

        if (whisperService == null) { sendText(chatId, "Voice input not enabled. Set WHISPER_API_KEY."); return; }

        sendText(chatId, "🎤 Transcribing...");
        String transcription = whisperService.transcribe(update.getMessage().getVoice().getFileId());

        if (transcription == null || transcription.isBlank()) {
            sendText(chatId, "Couldn't transcribe that. Please try again."); return;
        }

        sendText(chatId, "🎤 Heard: \"" + transcription + "\"");
        routeToAgent(chatId, userId, transcription);
    }

    // ── Incoming document (queued for email attachment) ───────────────────────

    private static final long MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024; // 10 MB

    private void handleDocument(Update update) {
        long chatId   = update.getMessage().getChatId();
        long userId   = update.getMessage().getFrom().getId();
        var  doc      = update.getMessage().getDocument();
        String filename = doc.getFileName() != null ? doc.getFileName() : "attachment";

        // Guard against OOM — reject files larger than 10 MB
        if (doc.getFileSize() != null && doc.getFileSize() > MAX_ATTACHMENT_BYTES) {
            sendText(chatId, "⚠️ File too large (" + doc.getFileSize() / (1024 * 1024) + " MB). Max is 10 MB.");
            return;
        }

        try {
            byte[] data = downloadTelegramFile(doc.getFileId());
            pendingAttachments.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new Attachment(filename, data));
            pendingAttachmentTimestamps.put(userId, System.currentTimeMillis());
            int total = pendingAttachments.get(userId).size();
            sendText(chatId, "📎 Got \"" + filename + "\" (" + data.length / 1024 + " KB). " + total
                    + " file(s) queued for your next email draft or reply.\n\nSay \"draft email to...\" or \"reply to [email]\" to use it.");
        } catch (Exception e) {
            sendText(chatId, "Couldn't save that file: " + e.getMessage());
        }
    }

    // ── Incoming photo (queued for email attachment, or process caption) ────

    private void handlePhoto(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        var photos  = update.getMessage().getPhoto();
        // Get the largest resolution
        String fileId = photos.get(photos.size() - 1).getFileId();

        try {
            byte[] data = downloadTelegramFile(fileId);
            String filename = "photo_" + System.currentTimeMillis() + ".jpg";
            pendingAttachments.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new Attachment(filename, data));
            pendingAttachmentTimestamps.put(userId, System.currentTimeMillis());
            int total = pendingAttachments.get(userId).size();

            String caption = update.getMessage().getCaption();
            if (caption != null && !caption.isBlank()) {
                // Photo with caption — queue the photo AND route the caption to Claude
                sendText(chatId, "📎 Photo queued (" + total + " attachment(s) ready).");
                routeToAgent(chatId, userId, caption);
            } else {
                sendText(chatId, "📎 Got photo (" + data.length / 1024 + " KB). " + total
                        + " file(s) queued for your next email draft or reply.\n\nSay \"draft email to...\" or \"reply to [email]\" to use it.");
            }
        } catch (Exception e) {
            sendText(chatId, "Couldn't save that photo: " + e.getMessage());
        }
    }

    private byte[] downloadTelegramFile(String fileId) throws Exception {
        org.telegram.telegrambots.meta.api.methods.GetFile getFileMethod =
                new org.telegram.telegrambots.meta.api.methods.GetFile(fileId);
        org.telegram.telegrambots.meta.api.objects.File file = telegramClient.execute(getFileMethod);
        String url = "https://api.telegram.org/file/bot" + botToken + "/" + file.getFilePath();
        HttpResponse<byte[]> resp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    // ── Location ─────────────────────────────────────────────────────────────

    private void handleLocation(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        double lat  = update.getMessage().getLocation().getLatitude();
        double lng  = update.getMessage().getLocation().getLongitude();

        expireStaleEntries();  // housekeeping
        PendingInput pending = pendingInputs.get(userId);
        if (pending != null && pending.kind() == PendingKind.LOCATION_TASK_HINT) {
            pendingInputs.remove(userId);
            taskService.findTaskByTitleHint(userId, pending.target()).ifPresentOrElse(task -> {
                taskService.setLocationReminder(userId, task.shortId(), lat, lng, 200);
                sendText(chatId, "📍 Location reminder set for \"" + esc(task.getTitle()) + "\"!");
            }, () -> sendText(chatId, "Couldn't find task \"" + esc(pending.target()) + "\"."));
            return;
        }

        List<Task> triggered = taskService.checkLocationTriggers(userId, lat, lng);
        if (triggered.isEmpty()) {
            sendText(chatId, "📍 Location received. No tasks linked to this area.");
        } else {
            triggered.forEach(t -> sendText(chatId, "📍 You're near the location for:\n\n" + taskService.formatTask(t)));
        }
    }

    // ── Text messages ─────────────────────────────────────────────────────────

    private void handleMessage(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText().trim();

        // If the user replied to a previous bot message, prepend that message's text
        // so the agent knows exactly what is being referred to (e.g. replying to a reminder).
        if (update.getMessage().getReplyToMessage() != null) {
            var replied = update.getMessage().getReplyToMessage();
            String repliedText = replied.hasText() ? replied.getText()
                               : replied.hasCaption() ? replied.getCaption() : null;
            if (repliedText != null && !repliedText.isBlank()) {
                // Truncate long messages to avoid flooding the context
                if (repliedText.length() > 400) repliedText = repliedText.substring(0, 400) + "...";
                text = "[Replying to: \"" + repliedText + "\"]\n" + text;
            }
        }

        if (text.equals("/cancel")) {
            sendText(chatId, pendingInputs.remove(userId) != null ? "Cancelled." : "Nothing to cancel."); return;
        }

        // Handle pending inputs before slash commands
        PendingInput pending = pendingInputs.get(userId);
        if (pending != null) {
            if (pending.kind() == PendingKind.GOOGLE_OAUTH_CODE) {
                pendingInputs.remove(userId);
                sendText(chatId, "🔄 Authorizing...");
                boolean ok = googleAuthService.exchangeCode(text.trim());
                if (ok) {
                    if (claudeService != null) claudeService.clearGoogleOffline();
                    sendText(chatId, "✅ Google account linked for Google Tasks sync.\n\n"
                            + "⚠️ Restart the bot (<code>supervisorctl restart taskbot</code>) so Google Tasks "
                            + "picks up the fresh credentials.\n\n"
                            + "Note: Gmail / Calendar / Drive are now brokered via Composio — connect those in your Composio dashboard, not here.");
                } else {
                    sendText(chatId, "❌ Authorization failed. Please try /authorize again.");
                }
                return;
            }
            sendText(chatId, "Please send your location or /cancel."); return;
        }

        // Slash commands
        switch (text) {
            case "/start"        -> { sendText(chatId, "👋 Hey! I'm Proton, your personal productivity bot.\n\nJust talk to me naturally — add tasks, save notes, check what's due, anything. What do you need?"); return; }
            case "/help"         -> { sendText(chatId, helpText()); return; }
            case "/authorize"         -> { handleAuthorize(chatId, userId); return; }
            case "/myprofile"         -> { sendText(chatId, claudeService != null ? claudeService.getProfileText(userId) : "AI not configured."); return; }
            case "/clearattachments"  -> {
                List<Attachment> removed = pendingAttachments.remove(userId);
                sendText(chatId, removed != null && !removed.isEmpty()
                        ? "🗑 Cleared " + removed.size() + " queued attachment(s)." : "No attachments queued.");
                return;
            }
            case "/tasks"        -> { refreshPinnedTaskList(chatId, userId); return; }
            case "/today"        -> { sendTaskList(chatId, "🗓 Due Today", taskService.getTodayTasks(userId)); return; }
            case "/overdue"      -> { sendTaskList(chatId, "⚠️ Overdue", taskService.getOverdueTasks(userId)); return; }
            case "/stale"        -> { sendTaskList(chatId, "🧊 Stale", taskService.getStaleTasks(userId)); return; }
            case "/doneitems"    -> { sendDoneList(chatId, taskService.getDoneTasks(userId)); return; }
            case "/cleardone"    -> { int n = taskService.deleteAllDone(userId); sendText(chatId, n > 0 ? "🗑 Cleared " + n + " completed tasks." : "No completed tasks."); return; }
            case "/review"       -> { sendText(chatId, taskService.getReviewSummary(userId)); return; }
            case "/habits"       -> { sendHabitList(chatId, userId); return; }
            case "/recentnotes"  -> { handleRecentNotes(chatId, userId); return; }
            case "/setupnotes"   -> { handleSetupNotes(chatId); return; }
            case "/edittasks"    -> { sendEditableTaskList(chatId, taskService.getActiveTasks(userId)); return; }
            case "/stoppomodoro" -> {
                boolean stopped = taskService.stopFocusSession(userId);
                UserSettings us = taskService.getUserSettings(userId);
                taskService.saveUserSettings(userId, us.getQuietStart(), us.getQuietEnd(), null);
                sendText(chatId, stopped ? "⏹ Pomodoro stopped." : "No active session."); return;
            }
            case "/brief"        -> { handleBrief(chatId, userId); return; }
            case "/mood"         -> { handleMoodPrompt(chatId, userId); return; }
            case "/countdowns"   -> { handleCountdowns(chatId, userId); return; }
            case "/goals"        -> { handleGoals(chatId, userId); return; }
            case "/gtasks"       -> { handleGoogleTasks(chatId, userId); return; }
            case "/synctasks"    -> { handleSyncTasks(chatId, userId); return; }
        }

        if (text.startsWith("/forget ")) {
            String key = text.substring(8).trim();
            if (key.isBlank()) { sendText(chatId, "Usage: /forget &lt;key&gt;  (see /myprofile for key names)"); return; }
            boolean removed = claudeService != null && claudeService.forgetProfileKey(userId, key);
            sendText(chatId, removed ? "🗑 Forgot \"" + esc(key) + "\"." : "No preference found with key \"" + esc(key) + "\"."); return;
        }
        if (text.startsWith("/pomodoro")) {
            String[] parts = text.trim().split("\\s+");
            int work   = parts.length > 1 ? parseIntSafe(parts[1], 25) : 25;
            int brk    = parts.length > 2 ? parseIntSafe(parts[2], 5)  : 5;
            int rounds = parts.length > 3 ? parseIntSafe(parts[3], 4)  : 4;
            handleStartPomodoro(chatId, userId, "your session", work, brk, rounds); return;
        }
        if (text.startsWith("/search ")) {
            sendTaskList(chatId, "🔍 " + text.substring(8).trim(), taskService.searchTasks(userId, text.substring(8).trim())); return;
        }
        if (text.startsWith("/journal ")) {
            handleJournal(chatId, userId, text.substring(9).trim()); return;
        }
        if (text.equals("/journal")) {
            sendText(chatId, "Usage: /journal &lt;your entry&gt;\n\nExample: /journal Had a productive morning..."); return;
        }
        if (text.startsWith("/countdown ")) {
            handleAddCountdown(chatId, userId, text.substring(11).trim()); return;
        }
        if (text.equals("/countdown")) {
            sendText(chatId, "Usage: /countdown &lt;name&gt; &lt;date&gt;\n\nExample: /countdown birthday 2026-05-15"); return;
        }
        if (text.startsWith("/goal ")) {
            handleAddGoal(chatId, userId, text.substring(6).trim()); return;
        }
        if (text.equals("/goal")) {
            sendText(chatId, "Usage: /goal &lt;title&gt; [by &lt;date&gt;]\n\nExample: /goal Finish thesis by 2026-06-01"); return;
        }
        if (text.startsWith("/linktask ")) {
            handleLinkTask(chatId, userId, text.substring(10).trim()); return;
        }
        if (text.equals("/linktask")) {
            sendText(chatId, "Usage: /linktask &lt;goal#&gt; &lt;task hint&gt;\n\nExample: /linktask 1 write chapter"); return;
        }
        if (text.startsWith("/timeblock")) {
            handleTimeBlock(chatId, userId); return;
        }
        if (text.startsWith("/")) { sendText(chatId, "Unknown command. Just talk to me naturally!"); return; }

        // Java pre-processing for pomodoro keywords
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("stop pomodoro") || lower.contains("cancel pomodoro")
                || lower.contains("stop the pomodoro") || lower.contains("end pomodoro")
                || lower.contains("stop my pomodoro") || lower.contains("pause pomodoro")
                || lower.contains("stop timer") || lower.contains("stop the timer")
                || lower.contains("stop focus") || lower.contains("end session")
                || lower.contains("end the session") || lower.contains("stop session")) {
            boolean stopped = taskService.stopFocusSession(userId);
            UserSettings us = taskService.getUserSettings(userId);
            taskService.saveUserSettings(userId, us.getQuietStart(), us.getQuietEnd(), null);
            sendText(chatId, stopped ? "⏹ Session stopped." : "No active session."); return;
        }
        if ((lower.contains("pomodoro") || lower.contains("focus session") || lower.contains("focus timer"))
                && !lower.contains("task") && !lower.contains("what")
                && !lower.contains("show") && !lower.contains("list") && !lower.contains("stop")
                && !lower.contains("cancel") && !lower.contains("end") && !lower.contains("pause")) {
            int work = 25, brk = 5, rounds = 4;
            java.util.regex.Matcher mWork = java.util.regex.Pattern.compile("(\\d+)\\s*min").matcher(lower);
            java.util.regex.Matcher mHour = java.util.regex.Pattern.compile("(\\d+)\\s*hour").matcher(lower);
            if (mWork.find()) work = Integer.parseInt(mWork.group(1));
            else if (mHour.find()) { int total = Integer.parseInt(mHour.group(1)) * 60; rounds = Math.max(1, total / 25); }
            handleStartPomodoro(chatId, userId, "your session", work, brk, rounds); return;
        }

        // ── Java pre-processing: task completion ─────────────────────────────
        // Handle directly in Java — far more reliable than depending on LLM tool calls.
        String bulkFilter = IntentPatterns.extractBulkDoneFilter(lower);
        if (bulkFilter != null) {
            if (bulkFilter.matches("(?i)daily|high|medium|low")) {
                Task.Priority p = Task.Priority.fromText(bulkFilter);
                int n = taskService.markAllDoneByPriority(userId, p);
                sendText(chatId, n > 0 ? "✅ Marked " + n + " " + bulkFilter + " task(s) done!"
                                       : "No active " + bulkFilter + " tasks to complete.");
            } else {
                int n = taskService.markAllDone(userId);
                sendText(chatId, n > 0 ? "✅ Marked all " + n + " task(s) done!"
                                       : "No active tasks to complete.");
            }
            refreshPinnedTaskList(chatId, userId);
            return;
        }

        String doneHint = IntentPatterns.extractMarkDoneHint(lower);
        if (doneHint != null) {
            var opt = taskService.findTaskByTitleHint(userId, doneHint);
            if (opt.isPresent()) {
                Task task = opt.get();
                syncCompleteToGoogle(task);
                taskService.markDone(userId, task.shortId());
                taskService.resetReminderIgnoredCount(task.getId());
                sendText(chatId, "✅ Done! \"" + esc(task.getTitle()) + "\" marked complete.");
                refreshPinnedTaskList(chatId, userId);
                return;
            }
            // No match — fall through to Claude, it might interpret differently
        }

        // Set location reminder pending
        final String finalText = text;  // may have been reassigned above (reply context prepend)
        if ((lower.contains("location") && lower.contains("remind")) || lower.contains("when i get to") || lower.contains("when i arrive")) {
            taskService.findTaskByTitleHint(userId, finalText).ifPresentOrElse(task -> {
                pendingInputs.put(userId, new PendingInput(PendingKind.LOCATION_TASK_HINT, task.getTitle()));
                pendingInputTimestamps.put(userId, System.currentTimeMillis());
                sendText(chatId, "📍 Send me your location and I'll set a reminder for \"" + esc(task.getTitle()) + "\".");
            }, () -> routeToAgent(chatId, userId, finalText));
            return;
        }

        // Everything else goes to the agent
        routeToAgent(chatId, userId, finalText);
    }

    // ── Agent routing ─────────────────────────────────────────────────────────

    private void routeToAgent(long chatId, long userId, String text) {
        if (claudeService == null) { sendText(chatId, "AI not configured. Set CLAUDE_API_KEY."); return; }
        List<Attachment> attachments = pendingAttachments.getOrDefault(userId, List.of());
        String response = claudeService.chat(userId, chatId, text,
                taskService, notionService, noteService,
                journalService,
                attachments,
                msg -> sendText(chatId, msg),
                (filename, data) -> sendDocument(chatId, filename, data),
                () -> refreshPinnedTaskList(chatId, userId));
        // Clear queued attachments after any email draft/reply was created
        if (response != null && (response.contains("Draft saved") || response.contains("Reply draft saved"))) {
            pendingAttachments.remove(userId);
        }
        if (response != null && !response.isBlank()
                && !response.equals("SENT_DIRECTLY")
                && !response.contains("SENT_DIRECTLY")) {
            sendText(chatId, esc(response));
        }
    }

    // ── Google authorization ──────────────────────────────────────────────────

    private void handleAuthorize(long chatId, long userId) {
        if (googleAuthService == null || !googleAuthService.isInitialized()) {
            sendText(chatId, "Google integration not configured.\n\nSet GOOGLE_CREDENTIALS_PATH in your config to enable Gmail, Calendar, and Drive."); return;
        }
        // Always allow re-auth: a stored refresh token can be revoked server-side
        // (Google's 6-month inactivity policy, password change, manual revoke), and we
        // can't tell without calling the API. Letting the user re-run /authorize
        // overwrites the dead token with a fresh one.
        String url = googleAuthService.getAuthorizationUrl();
        pendingInputs.put(userId, new PendingInput(PendingKind.GOOGLE_OAUTH_CODE, null));
        pendingInputTimestamps.put(userId, System.currentTimeMillis());
        sendText(chatId, "🔐 Authorize Google access:\n\n" + url
                + "\n\n1. Open the link above\n"
                + "2. Sign in and grant access\n"
                + "3. The browser will try to load <code>http://localhost/?code=...</code> and fail — that's expected\n"
                + "4. Copy the entire localhost URL from the address bar (or just the <code>code=...</code> value) and send it here");
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    private void handleCallback(Update update) throws TelegramApiException {
        String data   = update.getCallbackQuery().getData();
        long chatId   = update.getCallbackQuery().getMessage().getChatId();
        long userId   = update.getCallbackQuery().getFrom().getId();
        int messageId = toIntExact(update.getCallbackQuery().getMessage().getMessageId());

        if (data.startsWith("done:")) {
            String id = data.substring(5);
            taskService.findTaskByShortId(userId, id).ifPresentOrElse(task -> {
                syncCompleteToGoogle(task);
                taskService.markDone(userId, id);
                taskService.resetReminderIgnoredCount(task.getId());
                try { answer(update, "Done!"); editMessage(chatId, messageId, "✅ " + esc(task.getTitle()), null); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
                refreshPinnedTaskList(chatId, userId);
            }, () -> sendText(chatId, "Task not found."));
            return;
        }
        if (data.startsWith("delete:")) {
            String id = data.substring(7);
            taskService.findTaskByShortId(userId, id).ifPresentOrElse(task -> {
                syncDeleteToGoogle(task);
                taskService.deleteTask(userId, id);
                try { answer(update, "Deleted"); editMessage(chatId, messageId, "🗑 " + esc(task.getTitle()), null); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
                refreshPinnedTaskList(chatId, userId);
            }, () -> sendText(chatId, "Task not found."));
            return;
        }
        if (data.startsWith("snooze24:")) {
            String id = data.substring(9);
            taskService.findTaskByShortId(userId, id).ifPresentOrElse(task -> {
                taskService.snoozeTask(userId, id, Duration.ofHours(24));
                try { answer(update, "Snoozed 24h"); editMessage(chatId, messageId, "⏰ Snoozed: " + esc(task.getTitle()), null); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
                refreshPinnedTaskList(chatId, userId);
            }, () -> sendText(chatId, "Task not found."));
            return;
        }
        if (data.equals("cleardone")) {
            int n = taskService.deleteAllDone(userId);
            answer(update, n > 0 ? "Cleared " + n : "Nothing");
            editMessage(chatId, messageId, n > 0 ? "🗑 Cleared " + n + " completed tasks." : "No completed tasks.", null);
            // /cleardone doesn't change ACTIVE tasks, but refresh anyway so the pinned summary
            // reflects "Done" count moving to 0.
            refreshPinnedTaskList(chatId, userId);
            return;
        }
        if (data.startsWith("filter:")) {
            String filter = data.substring(7);
            String title; List<Task> tasks;
            switch (filter) {
                case "today"   -> { title = "📅 Due Today"; tasks = taskService.getTodayTasks(userId); }
                case "overdue" -> { title = "⚠️ Overdue"; tasks = taskService.getOverdueTasks(userId); }
                case "done"    -> { title = "✅ Completed"; tasks = taskService.getDoneTasks(userId); }
                default        -> { title = "📋 Active Tasks"; tasks = taskService.getActiveTasks(userId); }
            }
            answer(update, filter);
            String html = taskService.formatTaskListHtml(title, tasks);
            editMessage(chatId, messageId, html, buildFilterKeyboard());
        }
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private void sendTaskList(long chatId, String title, List<Task> tasks) {
        sendTaskList(chatId, title, tasks, null);
    }

    private void sendTaskList(long chatId, String title, List<Task> tasks, InlineKeyboardMarkup keyboard) {
        String html = taskService.formatTaskListHtml(title, tasks);
        if (keyboard == null) {
            // Add filter buttons to the main task list
            keyboard = buildFilterKeyboard();
        }
        execute(SendMessage.builder().chatId(chatId).text(html)
                .parseMode(ParseMode.HTML).replyMarkup(keyboard).build());
    }

    // ── Pinned active-tasks summary ───────────────────────────────────────────
    // Sends the active-tasks list, pins it, and unpins whatever was previously
    // pinned for this user. Called from /tasks and from any task-mutating action
    // so the pinned summary always reflects current state.

    public void refreshPinnedTaskList(long chatId, long userId) {
        try {
            List<Task> tasks = taskService.getActiveTasks(userId);
            String html = taskService.formatTaskListHtml("📋 Active Tasks", tasks);
            Message sent;
            try {
                sent = telegramClient.execute(SendMessage.builder()
                        .chatId(chatId).text(html)
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(buildFilterKeyboard())
                        .build());
            } catch (TelegramApiException e) {
                // Fallback to plain text if HTML parse fails
                String plain = html.replaceAll("<[^>]+>", "")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
                sent = telegramClient.execute(SendMessage.builder()
                        .chatId(chatId).text(plain)
                        .replyMarkup(buildFilterKeyboard())
                        .build());
            }
            if (sent == null) return;
            int newId = toIntExact(sent.getMessageId());

            Integer prevId = taskService.getPinnedTasksMessageId(userId);
            if (prevId != null && prevId != newId) {
                try {
                    telegramClient.execute(UnpinChatMessage.builder()
                            .chatId(chatId).messageId(prevId).build());
                } catch (TelegramApiException ignored) {
                    // Old message may have been deleted or already unpinned — ignore
                }
            }
            try {
                telegramClient.execute(PinChatMessage.builder()
                        .chatId(chatId).messageId(newId)
                        .disableNotification(true)
                        .build());
                taskService.setPinnedTasksMessageId(userId, newId);
            } catch (TelegramApiException e) {
                // Lacking pin permission isn't fatal — just log and skip persisting
                System.err.println("Pin task list failed: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("refreshPinnedTaskList failed: " + e.getMessage());
        }
    }

    private void sendDoneList(long chatId, List<Task> tasks) {
        if (tasks.isEmpty()) { sendText(chatId, "✅ <b>Completed</b>\n\nNo completed tasks."); return; }
        StringBuilder sb = new StringBuilder("✅ <b>Completed</b>  (" + tasks.size() + ")\n─────────────────\n");
        tasks.forEach(t -> sb.append("✅ ").append(esc(t.getTitle())).append("\n"));
        execute(SendMessage.builder().chatId(chatId).text(sb.toString().trim())
                .parseMode(ParseMode.HTML)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("🗑 Clear All Completed").callbackData("cleardone").build()
                ))).build()).build());
    }

    private void sendEditableTaskList(long chatId, List<Task> tasks) {
        if (tasks.isEmpty()) { sendText(chatId, "No active tasks."); return; }
        sendText(chatId, "✏️ <b>Active Tasks</b> — tap to act:");
        for (Task task : tasks) {
            execute(SendMessage.builder().chatId(chatId).text(taskService.formatTaskHtml(task))
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(buildTaskKeyboard(task)).build());
        }
    }

    private void sendHabitList(long chatId, long userId) {
        List<Task> habits = taskService.getHabits(userId);
        if (habits.isEmpty()) { sendText(chatId, "No habits yet.\n\nSay: \"mark gym as a habit\""); return; }
        StringBuilder sb = new StringBuilder("🔄 <b>Habits</b> (" + habits.size() + ")\n─────────────────\n");
        habits.forEach(h -> {
            int streak = taskService.getHabitStreak(h.getId());
            sb.append(dot(h)).append(" <b>").append(esc(h.getTitle())).append("</b>")
              .append("\n  🔥 ").append(streak).append(" day streak\n\n");
        });
        sendText(chatId, sb.toString().trim());
    }

    private void handleRecentNotes(long chatId, long userId) {
        if (notionService == null) { sendText(chatId, "Notes not enabled. Set NOTION_API_KEY and NOTION_DATABASE_ID."); return; }
        sendText(chatId, "📝 Fetching recent notes...");
        try {
            List<NotionService.NoteResult> results = notionService.getRecentNotes(5);
            if (results.isEmpty()) { sendText(chatId, "No notes saved yet."); return; }
            StringBuilder sb = new StringBuilder("📝 <b>Recent Notes</b>\n─────────────────\n\n");
            results.forEach(n -> sb.append("📌 <b>").append(esc(n.title())).append("</b>\n")
                    .append("   📁 ").append(esc(n.category()))
                    .append(n.tags().isEmpty() ? "" : "  🏷 " + esc(String.join(", ", n.tags())))
                    .append("  📅 ").append(n.created()).append("\n\n"));
            sendText(chatId, sb.toString().trim());
        } catch (Exception e) { sendText(chatId, "Couldn't fetch notes right now."); }
    }

    private void handleSetupNotes(long chatId) {
        if (notionService == null) { sendText(chatId, "Notes not enabled. Set NOTION_API_KEY and NOTION_DATABASE_ID."); return; }
        sendText(chatId, "⚙️ Setting up your Quick Notes database...");
        try {
            notionService.setupDatabase();
            sendText(chatId, "✅ Quick Notes is ready!\n\nTry: \"remember that Jacob's birthday is 15 May\"\nOr: \"when is Jacob's birthday?\"");
        } catch (Exception e) { sendText(chatId, "Setup failed: " + e.getMessage()); }
    }

    // ── Pomodoro ─────────────────────────────────────────────────────────────

    private void handleStartPomodoro(long chatId, long userId, String taskTitle, int workMins, int breakMins, int rounds) {
        taskService.stopFocusSession(userId);
        FocusSession session = taskService.startFocusSession(userId, chatId,
                taskTitle + " [Pomodoro 1/" + rounds + "]", workMins);
        if (session == null) { sendText(chatId, "Couldn't start Pomodoro session."); return; }
        String pomConfig = "POMODORO:" + rounds + ":" + workMins + ":" + breakMins + ":1:work";
        UserSettings us = taskService.getUserSettings(userId);
        taskService.saveUserSettings(userId, us.getQuietStart(), us.getQuietEnd(), pomConfig);
        sendText(chatId, "🍅 <b>Pomodoro started!</b>\n\nTask: <b>" + esc(taskTitle) + "</b>\nRound 1 of " + rounds
                + " — " + workMins + " min work\nBreak: " + breakMins + " min\n\nFocus up! 💪");
    }

    // ── Keyboards ────────────────────────────────────────────────────────────

    private InlineKeyboardMarkup buildTaskKeyboard(Task task) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ Done").callbackData("done:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("⏰ Snooze 24h").callbackData("snooze24:" + task.shortId()).build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("🗑 Delete").callbackData("delete:" + task.shortId()).build())
        )).build();
    }

    private InlineKeyboardMarkup buildFilterKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("📋 All").callbackData("filter:active").build(),
                        InlineKeyboardButton.builder().text("📅 Today").callbackData("filter:today").build(),
                        InlineKeyboardButton.builder().text("⚠️ Overdue").callbackData("filter:overdue").build(),
                        InlineKeyboardButton.builder().text("✅ Done").callbackData("filter:done").build())
        )).build();
    }

    // ── Telegram helpers ──────────────────────────────────────────────────────

    private void answer(Update update, String text) throws TelegramApiException {
        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(update.getCallbackQuery().getId()).text(text).build());
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        EditMessageText.EditMessageTextBuilder b = EditMessageText.builder()
                .chatId(chatId).messageId(messageId).text(text).parseMode(ParseMode.HTML);
        if (keyboard != null) b.replyMarkup(keyboard);
        telegramClient.execute(b.build());
    }

    public void sendText(long chatId, String text) {
        if (text == null || text.isBlank()) return;
        // Telegram max message length is 4096 characters
        if (text.length() <= 4096) {
            execute(SendMessage.builder().chatId(chatId).text(text)
                    .parseMode(ParseMode.HTML).build());
            return;
        }
        // Split into chunks, preferring line-break boundaries
        int maxLen = 4096;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start) end = newline + 1;
            }
            execute(SendMessage.builder().chatId(chatId).text(text.substring(start, end))
                    .parseMode(ParseMode.HTML).build());
            start = end;
        }
    }

    public void sendDocument(long chatId, String filename, byte[] data) {
        java.io.File tmp = null;
        try {
            // Write to a temp file so OkHttp knows the content-length for multipart upload.
            // Sanitize filename: '/' and other special chars break Files.createTempFile on Linux.
            String safeSuffix = "_" + filename.replaceAll("[/\\\\:*?\"<>|\\s]", "_");
            tmp = Files.createTempFile("taskbot_", safeSuffix).toFile();
            try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(data); }

            telegramClient.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(tmp, filename))
                    .build());
        } catch (Exception e) {
            System.err.println("sendDocument failed: " + e.getMessage());
            // Surface the real error to the user
            try { sendText(chatId, "Couldn't send file \"" + esc(filename) + "\": " + esc(e.getMessage())); }
            catch (Exception ignored) {}
            // Re-throw so the tool reports failure to Claude instead of saying "sent"
            throw new RuntimeException("File send failed: " + e.getMessage(), e);
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    private void execute(SendMessage msg) {
        try {
            telegramClient.execute(msg);
        } catch (TelegramApiException e) {
            // If HTML parsing failed, retry without parse mode
            if (e.getMessage() != null && e.getMessage().contains("can't parse entities")) {
                System.err.println("HTML parse failed, retrying as plain text: " + e.getMessage());
                try {
                    // Strip HTML tags and send as plain text
                    String plainText = msg.getText().replaceAll("<[^>]+>", "").replace("&lt;", "<")
                            .replace("&gt;", ">").replace("&amp;", "&");
                    telegramClient.execute(SendMessage.builder()
                            .chatId(msg.getChatId()).text(plainText).build());
                } catch (TelegramApiException e2) {
                    throw new RuntimeException("Failed to send message (even as plain text)", e2);
                }
            } else {
                throw new RuntimeException("Failed to send message", e);
            }
        }
    }

    private String dot(Task t) {
        return switch (t.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; case DAILY -> "🔵"; };
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Group chat inbox (forwarded media) ─────────────────────────────────

    /**
     * Group chat inbox — simple linear flow:
     * 1. Extract text from whatever was sent (text, photo, PDF, voice)
     * 2. Ask Claude: is this an actionable task? If yes → create task. If no → save as quick note.
     */
    private void handleGroupInbox(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String content = null;

        // Extract text from any message type
        if (update.getMessage().hasText()) {
            content = update.getMessage().getText();
        } else if (update.getMessage().hasPhoto() && inboxService != null) {
            var photos = update.getMessage().getPhoto();
            String fileId = photos.get(photos.size() - 1).getFileId();
            byte[] imageBytes = inboxService.downloadTelegramFile(fileId);
            if (imageBytes != null) content = inboxService.extractTextFromImage(imageBytes);
        } else if (update.getMessage().hasDocument() && inboxService != null) {
            var doc = update.getMessage().getDocument();
            String filename = doc.getFileName() != null ? doc.getFileName() : "";
            byte[] data = inboxService.downloadTelegramFile(doc.getFileId());
            if (data != null) {
                content = filename.toLowerCase().endsWith(".pdf")
                        ? inboxService.extractTextFromPdf(data)
                        : new String(data, java.nio.charset.StandardCharsets.UTF_8);
            }
        } else if (update.getMessage().hasVoice() && whisperService != null) {
            content = whisperService.transcribe(update.getMessage().getVoice().getFileId());
        }

        // Add caption if present (photos/docs often have captions)
        String caption = update.getMessage().getCaption();
        if (caption != null && !caption.isBlank()) {
            content = (content != null ? caption + "\n\n" + content : caption);
        }

        if (content == null || content.isBlank()) return; // silently ignore unreadable content

        // Classify in priority order: Calendar → Task → Note.
        final String extracted = content;
        InboxService.ClassifiedContent classified = (inboxService != null)
                ? inboxService.classifyContent(extracted) : null;
        String category = classified != null ? classified.category() : null;

        // 1) CALENDAR — has an explicit event time. Route through Claude so the
        // existing Composio-brokered add_event tool handles it (consistent path).
        if ("CALENDAR".equalsIgnoreCase(category) && classified.startDatetime() != null
                && !classified.startDatetime().isBlank()) {
            String title = classified.title() != null ? classified.title() : extracted;
            if (title.length() > 100) title = title.substring(0, 100);
            String routed = "Add to calendar: \"" + title + "\" starting " + classified.startDatetime()
                    + (classified.endDatetime() != null && !classified.endDatetime().isBlank()
                        ? " until " + classified.endDatetime() : "")
                    + (classified.body() != null && !classified.body().isBlank()
                        ? "\nDetails: " + classified.body() : "");
            routeToAgent(chatId, userId, routed);
            return;
        }

        // 2) TASK — actionable work. Also fall-through target if Calendar failed.
        if (("TASK".equalsIgnoreCase(category) || "CALENDAR".equalsIgnoreCase(category))
                && classified != null) {
            Task.Priority pri = classified.priority() != null
                    ? Task.Priority.fromText(classified.priority()) : Task.Priority.MEDIUM;
            java.time.LocalDateTime dueAt = null;
            // Prefer startDatetime (calendar-fallback), else due_date
            String whenStr = classified.startDatetime() != null && !classified.startDatetime().isBlank()
                    ? classified.startDatetime() : classified.dueDate();
            if (whenStr != null && !whenStr.isBlank()) {
                try {
                    if (whenStr.contains("T")) dueAt = java.time.LocalDateTime.parse(whenStr);
                    else dueAt = java.time.LocalDate.parse(whenStr.substring(0, Math.min(10, whenStr.length()))).atTime(9, 0);
                } catch (Exception ignored) {}
            }
            String title = classified.title() != null ? classified.title() : extracted;
            if (title.length() > 100) title = title.substring(0, 100);
            TaskService.AddTaskRequest req = new TaskService.AddTaskRequest(
                    title, pri, "inbox", dueAt, Task.Recurrence.NONE, classified.body());
            Task task = taskService.createTask(userId, chatId, req);
            autoSyncNewTaskToGoogle(task);
            sendText(chatId, "✅ " + esc(task.getTitle()));
            return;
        }

        // 3) NOTE — informational, non-actionable. Save as quick note.
        if (notionService != null) {
            try {
                String noteTitle = (classified != null && classified.title() != null)
                        ? classified.title()
                        : (extracted.length() > 80 ? extracted.substring(0, 80) + "..." : extracted);
                notionService.saveNote(noteTitle, "inbox", List.of("inbox"), extracted, null, "group-inbox");
                sendText(chatId, "📝 " + esc(noteTitle));
            } catch (Exception e) {
                sendText(chatId, "📝 Couldn't save note: " + e.getMessage());
            }
        } else {
            // No Notion — last-resort fallback: save as task anyway
            String title = extracted.length() > 100 ? extracted.substring(0, 100) : extracted;
            TaskService.AddTaskRequest req = new TaskService.AddTaskRequest(
                    title, Task.Priority.MEDIUM, "inbox", null, Task.Recurrence.NONE, null);
            Task task = taskService.createTask(userId, chatId, req);
            autoSyncNewTaskToGoogle(task);
            sendText(chatId, "✅ " + esc(title));
        }
    }

    // ── New command handlers ─────────────────────────────────────────────────

    private void handleBrief(long chatId, long userId) {
        routeToAgent(chatId, userId, "Give me my morning brief — today's tasks, calendar, habits, countdowns, mood trend");
    }

    private void handleMoodPrompt(long chatId, long userId) {
        if (moodService == null) { sendText(chatId, "Mood tracking not available."); return; }
        var todayMood = moodService.getToday(userId);
        if (todayMood.isPresent()) {
            var entry = todayMood.get();
            sendText(chatId, "Already logged today: mood " + entry.mood() + "/5, energy " + entry.energy() + "/5"
                    + "\n\nTo update, just say \"mood 4 3\" (mood energy).");
        } else {
            sendText(chatId, "How are you feeling?\n\nRate your mood (1-5) and energy (1-5).\nExample: \"mood 4 3\"");
        }
    }

    private void handleCountdowns(long chatId, long userId) {
        if (countdownService == null) { sendText(chatId, "Countdown service not available."); return; }
        var list = countdownService.getCountdowns(userId);
        if (list.isEmpty()) { sendText(chatId, "No countdowns set.\n\nUse: /countdown &lt;name&gt; &lt;date&gt;"); return; }
        StringBuilder sb = new StringBuilder("<b>⏳ Countdowns</b>\n─────────────────\n");
        list.forEach(c -> sb.append(countdownService.formatCountdown(c)).append("\n"));
        sendText(chatId, sb.toString().trim());
    }

    private void handleAddCountdown(long chatId, long userId, String args) {
        if (countdownService == null) { sendText(chatId, "Countdown service not available."); return; }
        // Parse: name date (e.g., "birthday 2026-05-15")
        int lastSpace = args.lastIndexOf(' ');
        if (lastSpace <= 0) { sendText(chatId, "Usage: /countdown &lt;name&gt; &lt;date&gt;\nExample: /countdown birthday 2026-05-15"); return; }
        String name = args.substring(0, lastSpace).trim();
        String dateStr = args.substring(lastSpace + 1).trim();
        try {
            java.time.LocalDate target = java.time.LocalDate.parse(dateStr);
            countdownService.addCountdown(userId, name, target);
            long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(countdownService.getZoneId()), target);
            sendText(chatId, "⏳ Countdown set: <b>" + esc(name) + "</b> — " + days + " days away!");
        } catch (Exception e) {
            sendText(chatId, "Invalid date format. Use yyyy-MM-dd (e.g., 2026-05-15)");
        }
    }

    private void handleGoals(long chatId, long userId) {
        if (goalService == null) { sendText(chatId, "Goal tracking not available."); return; }
        var goals = goalService.getActiveGoals(userId);
        if (goals.isEmpty()) { sendText(chatId, "No active goals.\n\nUse: /goal &lt;title&gt; [by &lt;date&gt;]"); return; }
        StringBuilder sb = new StringBuilder("<b>🎯 Active Goals</b>\n─────────────────\n");
        goals.forEach(g -> sb.append(goalService.formatGoal(g)).append("\n\n"));
        sendText(chatId, sb.toString().trim());
    }

    private void handleAddGoal(long chatId, long userId, String args) {
        if (goalService == null) { sendText(chatId, "Goal tracking not available."); return; }
        String title = args;
        java.time.LocalDate targetDate = null;
        // Parse "by <date>" at the end
        int byIdx = args.toLowerCase().lastIndexOf(" by ");
        if (byIdx > 0) {
            title = args.substring(0, byIdx).trim();
            String dateStr = args.substring(byIdx + 4).trim();
            try { targetDate = java.time.LocalDate.parse(dateStr); } catch (Exception ignored) {}
        }
        goalService.createGoal(userId, title, targetDate);
        sendText(chatId, "🎯 Goal created: " + esc(title)
                + (targetDate != null ? "\n📅 Target: " + targetDate : ""));
    }

    private void handleLinkTask(long chatId, long userId, String args) {
        if (goalService == null) { sendText(chatId, "Goal tracking not available."); return; }
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) { sendText(chatId, "Usage: /linktask &lt;goal#&gt; &lt;task hint&gt;"); return; }
        try {
            int goalId = Integer.parseInt(parts[0]);
            String taskHint = parts[1].trim();
            taskService.findTaskByTitleHint(userId, taskHint).ifPresentOrElse(task -> {
                goalService.linkTask(goalId, task.getId());
                sendText(chatId, "🔗 Linked \"" + esc(task.getTitle()) + "\" to goal #" + goalId);
            }, () -> sendText(chatId, "Task not found: " + esc(taskHint)));
        } catch (NumberFormatException e) {
            sendText(chatId, "First argument must be a goal number. Use /goals to see IDs.");
        }
    }

    private void handleJournal(long chatId, long userId, String content) {
        if (journalService == null) { sendText(chatId, "Journal not configured. Set NOTION_JOURNAL_API_KEY and NOTION_JOURNAL_DB_ID."); return; }
        sendText(chatId, "📔 Saving journal entry...");
        try {
            // Parse mood/energy directly from journal text
            Integer mood = extractNumber(content, "mood");
            Integer energy = extractNumber(content, "energy");
            // Fallback: pull today's mood/energy if not mentioned in text
            if (mood == null && energy == null && moodService != null) {
                var todayMood = moodService.getToday(userId);
                if (todayMood.isPresent()) {
                    mood = todayMood.get().mood();
                    energy = todayMood.get().energy();
                }
            }
            // Sync to mood tracker
            if (moodService != null && mood != null) {
                moodService.logMood(userId, mood, energy);
            }
            JournalService.JournalResult result = journalService.saveJournal(
                    content, mood, energy, java.time.ZoneId.of("Asia/Singapore"));
            StringBuilder sb = new StringBuilder("📔 Journal saved!\n");
            sb.append("Title: ").append(esc(result.title())).append("\n");
            if (result.tags() != null && !result.tags().isEmpty()) {
                sb.append("Tags: ").append(esc(String.join(", ", result.tags()))).append("\n");
            }
            if (mood != null) sb.append("Mood: ").append(mood).append("/5");
            if (energy != null) sb.append(" | Energy: ").append(energy).append("/5");
            sendText(chatId, sb.toString().trim());
        } catch (Exception e) {
            sendText(chatId, "Failed to save journal: " + e.getMessage());
        }
    }

    /** Extract a number (1-5) following a keyword like "mood" or "energy" in text. */
    private static Integer extractNumber(String text, String keyword) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)" + keyword + "\\s*(?:is|:|=)?\\s*(\\d)")
                .matcher(text);
        if (m.find()) {
            int val = Integer.parseInt(m.group(1));
            return (val >= 1 && val <= 5) ? val : null;
        }
        return null;
    }

    private void handleTimeBlock(long chatId, long userId) {
        if (composioService == null) { sendText(chatId, "Calendar not configured. Set composio.api.key in application.properties."); return; }
        routeToAgent(chatId, userId, "Suggest time blocks for my pending tasks based on today's calendar gaps");
    }

    private void handleGoogleTasks(long chatId, long userId) {
        if (googleTasksService == null) { sendText(chatId, "Google Tasks not linked. Use /authorize first."); return; }
        sendText(chatId, "📋 Fetching Google Tasks...");
        try {
            var grouped = googleTasksService.fetchTasksForDisplay();
            boolean empty = grouped.values().stream().allMatch(List::isEmpty);
            if (empty) { sendText(chatId, "No tasks in Google Tasks."); return; }
            StringBuilder sb = new StringBuilder("📋 Google Tasks\n─────────────────\n");
            for (var entry : grouped.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                sb.append("\n📁 ").append(esc(entry.getKey())).append("\n");
                for (var item : entry.getValue()) {
                    sb.append("  • ").append(esc(item.title()));
                    if (item.due() != null) sb.append(" (due: ").append(item.due()).append(")");
                    sb.append("\n");
                }
            }
            sendText(chatId, sb.toString().trim());
        } catch (Exception e) {
            sendText(chatId, "Failed to fetch Google Tasks: " + e.getMessage());
        }
    }

    private void handleSyncTasks(long chatId, long userId) {
        if (googleTasksService == null) { sendText(chatId, "Google Tasks not linked. Use /authorize first."); return; }
        sendText(chatId, "🔄 Syncing tasks to Google Tasks...");
        try {
            List<Task> active = taskService.getActiveTasks(userId);
            int synced = 0;
            for (Task task : active) {
                if (task.getGoogleTaskId() == null) {
                    String listName = googleTasksService.listNameForPriority(task.getPriority().name());
                    String dueDate = task.getDueAt() != null
                            ? task.getDueAt().toLocalDate().toString() : null;
                    var gTask = googleTasksService.createGoogleTask(
                            task.getTitle(), task.getNotes(), dueDate, listName);
                    taskService.setGoogleTaskId(task.getId(), gTask.taskId(), gTask.taskListId());
                    synced++;
                }
            }
            sendText(chatId, "✅ Synced " + synced + " new task(s) to Google Tasks."
                    + (synced == 0 ? " All tasks already synced." : ""));
        } catch (Exception e) {
            sendText(chatId, "Sync failed: " + e.getMessage());
        }
    }

    // ── Google Tasks sync helpers ──────────────────────────────────────────

    private void syncCompleteToGoogle(Task task) {
        if (googleTasksService == null || task.getGoogleTaskId() == null) return;
        try {
            googleTasksService.completeGoogleTask(task.getGoogleTaskId(), task.getGoogleTasklistId());
        } catch (Exception e) {
            System.err.println("Failed to sync completion to Google Tasks: " + e.getMessage());
        }
    }

    private void syncDeleteToGoogle(Task task) {
        if (googleTasksService == null || task.getGoogleTaskId() == null) return;
        try {
            googleTasksService.deleteGoogleTask(task.getGoogleTaskId(), task.getGoogleTasklistId());
        } catch (Exception e) {
            System.err.println("Failed to sync deletion to Google Tasks: " + e.getMessage());
        }
    }

    private void autoSyncNewTaskToGoogle(Task task) {
        if (googleTasksService == null) return;
        try {
            String listName = googleTasksService.listNameForPriority(task.getPriority().name());
            String dueDate = task.getDueAt() != null ? task.getDueAt().toLocalDate().toString() : null;
            var gTask = googleTasksService.createGoogleTask(task.getTitle(), task.getNotes(), dueDate, listName);
            taskService.setGoogleTaskId(task.getId(), gTask.taskId(), gTask.taskListId());
        } catch (Exception e) {
            System.err.println("Auto-sync new task to Google Tasks failed: " + e.getMessage());
        }
    }

    private void syncEditToGoogle(Task task) {
        if (googleTasksService == null || task.getGoogleTaskId() == null) return;
        try {
            String dueDate = task.getDueAt() != null ? task.getDueAt().toLocalDate().toString() : null;
            googleTasksService.updateGoogleTask(task.getGoogleTaskId(), task.getGoogleTasklistId(),
                    task.getTitle(), task.getNotes(), dueDate);
        } catch (Exception e) {
            System.err.println("Failed to sync edit to Google Tasks: " + e.getMessage());
        }
    }

    /** Expire pending inputs/attachments older than 1 hour to prevent memory leaks. */
    private void expireStaleEntries() {
        long now = System.currentTimeMillis();
        long ttl = 60 * 60 * 1000L; // 1 hour
        pendingInputTimestamps.entrySet().removeIf(e -> {
            if (now - e.getValue() > ttl) { pendingInputs.remove(e.getKey()); return true; }
            return false;
        });
        pendingAttachmentTimestamps.entrySet().removeIf(e -> {
            if (now - e.getValue() > ttl) { pendingAttachments.remove(e.getKey()); return true; }
            return false;
        });
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static String helpText() {
        return """
                I'm Proton — just talk to me naturally!

                <b>Examples:</b>
                "add gym tomorrow 9am"
                "add report friday 3pm #school high priority"
                "mark gym done"
                "complete all daily tasks"
                "reschedule report to next monday"
                "snooze all overdue tasks by 2 hours"
                "what's due today?"
                "show my high priority tasks"
                "remember that Jacob's birthday is 15 May"
                "when is Jacob's birthday?"
                "mark daily quran as a habit"
                "no reminders after 10pm"
                "mood 4 3"

                <b>Commands:</b>
                /tasks /today /overdue /review /habits
                /recentnotes /setupnotes /edittasks
                /brief — morning briefing on demand
                /mood — log mood &amp; energy
                /journal — save journal to Notion
                /countdown — add countdown
                /countdowns — list countdowns
                /goal — create a goal
                /goals — list active goals
                /linktask — link task to goal
                /timeblock — AI time-blocking suggestions
                /gtasks — show Google Tasks
                /synctasks — sync tasks to Google Tasks
                /pomodoro — focus timer
                /stoppomodoro /doneitems /cleardone
                /myprofile — view saved preferences
                /forget — remove a preference
                /authorize — link Google
                /help — this menu
                """;
    }

    // Intent regex extraction lives in IntentPatterns.java — pure, testable, isolated.
}