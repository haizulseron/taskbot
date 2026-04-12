package com.haizul.taskbot;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.toIntExact;

public class TaskBot implements LongPollingSingleThreadUpdateConsumer {

    private enum PendingKind { LOCATION_TASK_HINT }
    private record PendingInput(PendingKind kind, String target) {}

    private final TelegramClient telegramClient;
    private final TaskService taskService;
    private final ClaudeService claudeService;
    private final WhisperService whisperService;
    private final NotionService notionService;
    private final NoteService noteService;
    private final String botUsername;

    private final long allowedUserId;
    private final Map<Long, PendingInput> pendingInputs = new ConcurrentHashMap<>();

    public TaskBot(BotConfig config, TaskService taskService,
                   ClaudeService claudeService, WhisperService whisperService,
                   NotionService notionService, NoteService noteService) {
        this.telegramClient = new OkHttpTelegramClient(config.getBotToken());
        this.taskService    = taskService;
        this.claudeService  = claudeService;
        this.whisperService = whisperService;
        this.notionService  = notionService;
        this.noteService    = noteService;
        this.botUsername    = config.getBotUsername();
        this.allowedUserId  = config.getAllowedUserId();
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
            if (update.hasMessage()) {
                if (update.getMessage().hasText())         handleMessage(update);
                else if (update.getMessage().hasVoice())   handleVoice(update);
                else if (update.getMessage().hasLocation()) handleLocation(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (update.hasMessage()) sendText(update.getMessage().getChatId(), "Something went wrong: " + e.getMessage());
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

    // ── Location ─────────────────────────────────────────────────────────────

    private void handleLocation(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        double lat  = update.getMessage().getLocation().getLatitude();
        double lng  = update.getMessage().getLocation().getLongitude();

        PendingInput pending = pendingInputs.get(userId);
        if (pending != null && pending.kind() == PendingKind.LOCATION_TASK_HINT) {
            pendingInputs.remove(userId);
            taskService.findTaskByTitleHint(userId, pending.target()).ifPresentOrElse(task -> {
                taskService.setLocationReminder(userId, task.shortId(), lat, lng, 200);
                sendText(chatId, "📍 Location reminder set for \"" + task.getTitle() + "\"!");
            }, () -> sendText(chatId, "Couldn't find task \"" + pending.target() + "\"."));
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

        if (text.equals("/cancel")) {
            sendText(chatId, pendingInputs.remove(userId) != null ? "Cancelled." : "Nothing to cancel."); return;
        }
        if (pendingInputs.containsKey(userId)) {
            sendText(chatId, "Please send your location or /cancel."); return;
        }

        // Slash commands
        switch (text) {
            case "/start"        -> { sendText(chatId, "👋 Hey! I'm Proton, your personal productivity bot.\n\nJust talk to me naturally — add tasks, save notes, check what's due, anything. What do you need?"); return; }
            case "/help"         -> { sendText(chatId, helpText()); return; }
            case "/tasks"        -> { sendTaskList(chatId, "📋 Active Tasks", taskService.getActiveTasks(userId)); return; }
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
        if (text.startsWith("/")) { sendText(chatId, "Unknown command. Just talk to me naturally!"); return; }

        // Java pre-processing for pomodoro keywords
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("stop pomodoro") || lower.contains("cancel pomodoro")
                || lower.contains("stop focus") || lower.contains("end session")) {
            boolean stopped = taskService.stopFocusSession(userId);
            UserSettings us = taskService.getUserSettings(userId);
            taskService.saveUserSettings(userId, us.getQuietStart(), us.getQuietEnd(), null);
            sendText(chatId, stopped ? "⏹ Session stopped." : "No active session."); return;
        }
        if (lower.contains("pomodoro") && !lower.contains("task") && !lower.contains("what")
                && !lower.contains("show") && !lower.contains("list")) {
            int work = 25, brk = 5, rounds = 4;
            java.util.regex.Matcher mWork = java.util.regex.Pattern.compile("(\\d+)\\s*min").matcher(lower);
            java.util.regex.Matcher mHour = java.util.regex.Pattern.compile("(\\d+)\\s*hour").matcher(lower);
            if (mWork.find()) work = Integer.parseInt(mWork.group(1));
            else if (mHour.find()) { int total = Integer.parseInt(mHour.group(1)) * 60; rounds = Math.max(1, total / 25); }
            handleStartPomodoro(chatId, userId, "your session", work, brk, rounds); return;
        }

        // Set location reminder pending
        if (lower.contains("location") && lower.contains("remind") || lower.contains("when i get to") || lower.contains("when i arrive")) {
            taskService.findTaskByTitleHint(userId, text).ifPresentOrElse(task -> {
                pendingInputs.put(userId, new PendingInput(PendingKind.LOCATION_TASK_HINT, task.getTitle()));
                sendText(chatId, "📍 Send me your location and I'll set a reminder for \"" + task.getTitle() + "\".");
            }, () -> routeToAgent(chatId, userId, text));
            return;
        }

        // Everything else goes to the agent
        routeToAgent(chatId, userId, text);
    }

    // ── Agent routing ─────────────────────────────────────────────────────────

    private void routeToAgent(long chatId, long userId, String text) {
        if (claudeService == null) { sendText(chatId, "AI not configured. Set CLAUDE_API_KEY."); return; }
        String response = claudeService.chat(userId, chatId, text, taskService, notionService, noteService,
                msg -> sendText(chatId, msg));
        // Don't send if Claude's response is just acknowledging a direct send
        if (response != null && !response.isBlank()
                && !response.equals("SENT_DIRECTLY")
                && !response.contains("SENT_DIRECTLY")) {
            sendText(chatId, response);
        }
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
                taskService.markDone(userId, id);
                taskService.resetReminderIgnoredCount(task.getId());
                try { answer(update, "Done!"); editMessage(chatId, messageId, "✅ " + task.getTitle(), null); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            }, () -> sendText(chatId, "Task not found."));
            return;
        }
        if (data.startsWith("delete:")) {
            String id = data.substring(7);
            taskService.findTaskByShortId(userId, id).ifPresentOrElse(task -> {
                taskService.deleteTask(userId, id);
                try { answer(update, "Deleted"); editMessage(chatId, messageId, "🗑 " + task.getTitle(), null); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            }, () -> sendText(chatId, "Task not found."));
            return;
        }
        if (data.startsWith("snooze24:")) {
            String id = data.substring(9);
            taskService.findTaskByShortId(userId, id).ifPresentOrElse(task -> {
                taskService.snoozeTask(userId, id, Duration.ofHours(24));
                try { answer(update, "Snoozed 24h"); editMessage(chatId, messageId, "⏰ Snoozed: " + task.getTitle(), null); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            }, () -> sendText(chatId, "Task not found."));
            return;
        }
        if (data.equals("cleardone")) {
            int n = taskService.deleteAllDone(userId);
            answer(update, n > 0 ? "Cleared " + n : "Nothing");
            editMessage(chatId, messageId, n > 0 ? "🗑 Cleared " + n + " completed tasks." : "No completed tasks.", null);
        }
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private void sendTaskList(long chatId, String title, List<Task> tasks) {
        if (tasks.isEmpty()) { sendText(chatId, title + "\n\nNo tasks found."); return; }

        List<Task> main  = tasks.stream().filter(t -> t.getPriority() != Task.Priority.DAILY).toList();
        List<Task> daily = tasks.stream().filter(t -> t.getPriority() == Task.Priority.DAILY).toList();

        long high = main.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        long med  = main.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
        long low  = main.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();

        StringBuilder sb = new StringBuilder();
        sb.append(title).append("  (").append(tasks.size()).append(")\n");
        if (high > 0) sb.append("🔴 ").append(high).append(" high   ");
        if (med  > 0) sb.append("🟡 ").append(med).append(" medium   ");
        if (low  > 0) sb.append("🟢 ").append(low).append(" low");
        if (!daily.isEmpty()) sb.append("  🔵 ").append(daily.size()).append(" daily");
        sb.append("\n─────────────────\n");

        for (int i = 0; i < main.size(); i++) {
            Task t = main.get(i);
            String dot = dot(t);
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

        sendText(chatId, sb.toString().trim());
    }

    private void sendDoneList(long chatId, List<Task> tasks) {
        if (tasks.isEmpty()) { sendText(chatId, "✅ Completed\n\nNo completed tasks."); return; }
        StringBuilder sb = new StringBuilder("✅ Completed  (" + tasks.size() + ")\n─────────────────\n");
        tasks.forEach(t -> sb.append("✅ ").append(t.getTitle()).append("\n"));
        execute(SendMessage.builder().chatId(chatId).text(sb.toString().trim())
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("🗑 Clear All Completed").callbackData("cleardone").build()
                ))).build()).build());
    }

    private void sendEditableTaskList(long chatId, List<Task> tasks) {
        if (tasks.isEmpty()) { sendText(chatId, "No active tasks."); return; }
        sendText(chatId, "✏️ Active Tasks — tap to act:");
        for (Task task : tasks) {
            execute(SendMessage.builder().chatId(chatId).text(taskService.formatTask(task))
                    .replyMarkup(buildTaskKeyboard(task)).build());
        }
    }

    private void sendHabitList(long chatId, long userId) {
        List<Task> habits = taskService.getHabits(userId);
        if (habits.isEmpty()) { sendText(chatId, "No habits yet.\n\nSay: \"mark gym as a habit\""); return; }
        StringBuilder sb = new StringBuilder("🔄 Habits (" + habits.size() + ")\n─────────────────\n");
        habits.forEach(h -> {
            int streak = taskService.getHabitStreak(h.getId());
            sb.append(dot(h)).append(" ").append(h.getTitle())
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
            StringBuilder sb = new StringBuilder("📝 Recent Notes\n─────────────────\n\n");
            results.forEach(n -> sb.append("📌 ").append(n.title()).append("\n")
                    .append("   📁 ").append(n.category())
                    .append(n.tags().isEmpty() ? "" : "  🏷 " + String.join(", ", n.tags()))
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
        sendText(chatId, "🍅 Pomodoro started!\n\nTask: " + taskTitle + "\nRound 1 of " + rounds
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

    // ── Telegram helpers ──────────────────────────────────────────────────────

    private void answer(Update update, String text) throws TelegramApiException {
        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(update.getCallbackQuery().getId()).text(text).build());
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        EditMessageText.EditMessageTextBuilder b = EditMessageText.builder().chatId(chatId).messageId(messageId).text(text);
        if (keyboard != null) b.replyMarkup(keyboard);
        telegramClient.execute(b.build());
    }

    public void sendText(long chatId, String text) {
        execute(SendMessage.builder().chatId(chatId).text(text).build());
    }

    private void execute(SendMessage msg) {
        try { telegramClient.execute(msg); }
        catch (TelegramApiException e) { throw new RuntimeException("Failed to send message", e); }
    }

    private String dot(Task t) {
        return switch (t.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; case DAILY -> "🔵"; };
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static String helpText() {
        return """
                I'm Proton — just talk to me naturally!
                
                Examples:
                "add gym tomorrow 9am"
                "add report friday 3pm #school high priority"
                "mark gym done"
                "reschedule report to next monday"
                "snooze all overdue tasks by 2 hours"
                "what's due today?"
                "show my high priority tasks"
                "remember that Jacob's birthday is 15 May"
                "when is Jacob's birthday?"
                "mark daily quran as a habit"
                "no reminders after 10pm"
                
                Commands:
                /tasks /today /overdue /review /habits
                /recentnotes /setupnotes /edittasks
                /pomodoro [work] [break] [rounds]
                /stoppomodoro /doneitems /cleardone
                """;
    }
}