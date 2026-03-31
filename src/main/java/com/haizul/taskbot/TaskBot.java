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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.lang.Math.toIntExact;

public class TaskBot implements LongPollingSingleThreadUpdateConsumer {

    private enum PendingKind {
        TASK_TITLE, TASK_PRIORITY, TASK_CATEGORY, TASK_DUE, TASK_RECURRENCE, CATEGORY_RENAME
    }

    private record PendingInput(PendingKind kind, String target) {}

    private final TelegramClient telegramClient;
    private final TaskService taskService;
    private final ClaudeService claudeService;
    private final String botUsername;
    private final Map<Long, PendingInput> pendingInputs = new ConcurrentHashMap<>();

    public TaskBot(BotConfig config, TaskService taskService) {
        this.telegramClient = new OkHttpTelegramClient(config.getBotToken());
        this.taskService    = taskService;
        this.botUsername    = config.getBotUsername();

        String apiKey = config.getClaudeApiKey();
        this.claudeService = (apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_CLAUDE_API_KEY"))
                ? new ClaudeService(apiKey, config.getZoneId())
                : null;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) handleMessage(update);
            else if (update.hasCallbackQuery()) handleCallback(update);
        } catch (Exception e) {
            e.printStackTrace();
            if (update.hasMessage()) sendText(update.getMessage().getChatId(), "Something went wrong: " + e.getMessage());
        }
    }

    public TelegramClient getTelegramClient() { return telegramClient; }
    public String getBotUsername()            { return botUsername; }

    // ── Message routing ──────────────────────────────────────────────────────

    private void handleMessage(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText().trim();

        if (text.equals("/cancel")) {
            boolean removed = pendingInputs.remove(userId) != null;
            sendText(chatId, removed ? "Cancelled." : "Nothing to cancel.");
            return;
        }
        if (pendingInputs.containsKey(userId)) {
            if (text.startsWith("/")) { sendText(chatId, "You have an edit in progress. Send the new value, or use /cancel."); return; }
            handlePendingInput(chatId, userId, text);
            return;
        }

        switch (text) {
            case "/start"     -> sendText(chatId, "Task bot is live.\n\n" + TaskService.usageText());
            case "/help"      -> sendText(chatId, TaskService.usageText());
            case "/tasks"     -> sendTaskList(chatId, "📋 Active Tasks", taskService.getActiveTasks(userId), true);
            case "/today"     -> sendTaskList(chatId, "🗓 Due Today", taskService.getTodayTasks(userId), true);
            case "/overdue"   -> sendTaskList(chatId, "⚠️ Overdue", taskService.getOverdueTasks(userId), true);
            case "/stale"     -> sendTaskList(chatId, "🧊 Stale Tasks", taskService.getStaleTasks(userId), true);
            case "/doneitems" -> sendTaskList(chatId, "✅ Completed", taskService.getDoneTasks(userId), false);
            case "/cleardone" -> {
                int count = taskService.deleteAllDone(userId);
                sendText(chatId, count > 0
                        ? "🗑 Cleared " + count + " completed task(s)."
                        : "No completed tasks to clear.");
            }
            case "/review"    -> sendText(chatId, taskService.getReviewSummary(userId));
            case "/categories"-> sendCategoryList(chatId, userId);
            case "/add"       -> sendText(chatId, addHelpText());
            default           -> handleTextCommand(chatId, userId, text);
        }
    }

    private void handleTextCommand(long chatId, long userId, String text) {
        if (text.startsWith("/add ")) {
            String raw = text.substring(5).trim();
            if (raw.isEmpty()) { sendText(chatId, "Please enter a task after /add"); return; }
            try {
                Task task = taskService.createTask(userId, chatId, taskService.parseAddCommand(raw));
                sendTaskAdded(chatId, task);
            } catch (Exception e) { sendText(chatId, "Couldn't parse that. Send /add to see examples."); }
            return;
        }
        if (text.startsWith("/done ")) {
            String id = text.substring(6).trim();
            sendText(chatId, taskService.markDone(userId, id) ? "✅ Marked done: " + id : "Task not found: " + id);
            return;
        }
        if (text.startsWith("/delete ")) {
            String id = text.substring(8).trim();
            sendText(chatId, taskService.deleteTask(userId, id) ? "🗑 Deleted: " + id : "Task not found: " + id);
            return;
        }
        if (text.startsWith("/snooze ")) {
            String[] parts = text.substring(8).trim().split("\\s+");
            if (parts.length < 2) { sendText(chatId, "Usage: /snooze <id> <hours>"); return; }
            long hours = Long.parseLong(parts[1]);
            sendText(chatId, taskService.snoozeTask(userId, parts[0], Duration.ofHours(hours))
                    ? "⏰ Snoozed " + parts[0] + " by " + hours + "h" : "Task not found: " + parts[0]);
            return;
        }
        if (text.startsWith("/addcategory ")) {
            String name = text.substring(13).trim();
            if (name.isBlank()) { sendText(chatId, "Usage: /addcategory <name>"); return; }
            taskService.addCategory(userId, name);
            sendText(chatId, "Added category: " + name.trim().toLowerCase());
            return;
        }
        if (text.startsWith("/")) { sendText(chatId, "Unknown command. Use /help"); return; }

        // Plain text — route to Claude or fallback
        if (claudeService != null) handleNaturalLanguage(chatId, userId, text);
        else sendText(chatId, "Unknown command. Use /help\n\n💡 Set CLAUDE_API_KEY to enable natural language input.");
    }

    // ── Natural language via Claude ──────────────────────────────────────────

    private void handleNaturalLanguage(long chatId, long userId, String text) {
        ClaudeService.ParsedTask parsed = claudeService.parse(text);

        switch (parsed.type()) {
            case "task" -> {
                try {
                    TaskService.AddTaskRequest req = new TaskService.AddTaskRequest(
                            parsed.title(), parsed.priority(), parsed.category(), parsed.dueAt(), parsed.recurrence());
                    sendTaskAdded(chatId, taskService.createTask(userId, chatId, req));
                } catch (Exception e) {
                    sendText(chatId, "I understood your task but couldn't save it. Try /add instead.");
                }
            }
            case "list_tasks"  -> sendTaskList(chatId, "📋 Active Tasks", taskService.getActiveTasks(userId), true);
            case "list_today"  -> sendTaskList(chatId, "🗓 Due Today", taskService.getTodayTasks(userId), true);
            case "list_overdue"-> sendTaskList(chatId, "⚠️ Overdue", taskService.getOverdueTasks(userId), true);
            case "list_stale"  -> sendTaskList(chatId, "🧊 Stale Tasks", taskService.getStaleTasks(userId), true);
            case "list_done"   -> sendTaskList(chatId, "✅ Completed", taskService.getDoneTasks(userId), false);
            case "list_categories" -> sendCategoryList(chatId, userId);
            case "review"      -> sendText(chatId, taskService.getReviewSummary(userId));
            case "mark_all_done" -> {
                int count = taskService.markAllDone(userId);
                sendText(chatId, count > 0
                        ? "✅ Marked " + count + " task(s) as done. Fresh start!"
                        : "No active tasks to clear.");
            }
            case "delete_all_done" -> {
                int count = taskService.deleteAllDone(userId);
                sendText(chatId, count > 0
                        ? "🗑 Cleared " + count + " completed task(s)."
                        : "No completed tasks to clear.");
            }
            case "mark_done" -> {
                String hint = parsed.targetTitle();
                if (hint == null) { sendText(chatId, "Which task would you like to mark done? Try: \"mark the [task name] as done\""); return; }
                Optional<Task> task = taskService.findTaskByTitleHint(userId, hint);
                if (task.isPresent()) {
                    taskService.markDone(userId, task.get().shortId());
                    sendText(chatId, "✅ Marked done: " + task.get().getTitle());
                } else {
                    sendText(chatId, "Couldn't find a task matching \"" + hint + "\". Use /tasks to see your task IDs.");
                }
            }
            case "delete_task" -> {
                String hint = parsed.targetTitle();
                if (hint == null) { sendText(chatId, "Which task would you like to delete?"); return; }
                Optional<Task> task = taskService.findTaskByTitleHint(userId, hint);
                if (task.isPresent()) {
                    taskService.deleteTask(userId, task.get().shortId());
                    sendText(chatId, "🗑 Deleted: " + task.get().getTitle());
                } else {
                    sendText(chatId, "Couldn't find a task matching \"" + hint + "\". Use /tasks to see your tasks.");
                }
            }
            case "snooze_task" -> {
                String hint = parsed.targetTitle();
                int hours   = parsed.snoozeHours() > 0 ? parsed.snoozeHours() : 24;
                if (hint == null) { sendText(chatId, "Which task would you like to snooze?"); return; }
                Optional<Task> task = taskService.findTaskByTitleHint(userId, hint);
                if (task.isPresent()) {
                    taskService.snoozeTask(userId, task.get().shortId(), Duration.ofHours(hours));
                    sendText(chatId, "⏰ Snoozed \"" + task.get().getTitle() + "\" by " + hours + " hour(s).");
                } else {
                    sendText(chatId, "Couldn't find a task matching \"" + hint + "\". Use /tasks to see your tasks.");
                }
            }
            default -> {
                String reply = parsed.clarification() != null
                        ? parsed.clarification()
                        : "Not sure what you mean. Use /help to see what I can do.";
                sendText(chatId, reply);
            }
        }
    }

    // ── Pending edits ────────────────────────────────────────────────────────

    private void handlePendingInput(long chatId, long userId, String text) {
        PendingInput pending = pendingInputs.remove(userId);
        if (pending == null) return;
        boolean ok = switch (pending.kind()) {
            case TASK_TITLE      -> taskService.updateTaskTitle(userId, pending.target(), text);
            case TASK_PRIORITY   -> taskService.updateTaskPriority(userId, pending.target(), text);
            case TASK_CATEGORY   -> taskService.updateTaskCategory(userId, pending.target(), text);
            case TASK_DUE        -> taskService.updateTaskDueAt(userId, pending.target(), text);
            case TASK_RECURRENCE -> taskService.updateTaskRecurrence(userId, pending.target(), text);
            case CATEGORY_RENAME -> taskService.renameCategory(userId, pending.target(), text);
        };
        String label = switch (pending.kind()) {
            case TASK_TITLE      -> "title";
            case TASK_PRIORITY   -> "priority";
            case TASK_CATEGORY   -> "category";
            case TASK_DUE        -> "due date";
            case TASK_RECURRENCE -> "recurrence";
            case CATEGORY_RENAME -> "category name";
        };
        sendText(chatId, ok ? "Updated " + label + "." : "Task not found.");
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    private void handleCallback(Update update) throws TelegramApiException {
        String data   = update.getCallbackQuery().getData();
        long chatId   = update.getCallbackQuery().getMessage().getChatId();
        long userId   = update.getCallbackQuery().getFrom().getId();
        int messageId = toIntExact(update.getCallbackQuery().getMessage().getMessageId());

        if (data.startsWith("done:")) {
            String id = data.substring(5);
            boolean ok = taskService.markDone(userId, id);
            answer(update, ok ? "Done!" : "Not found");
            editMessage(chatId, messageId, ok ? "✅ Completed" : "Task not found", null);
            return;
        }
        if (data.startsWith("delete:")) {
            String id = data.substring(7);
            boolean ok = taskService.deleteTask(userId, id);
            answer(update, ok ? "Deleted" : "Not found");
            editMessage(chatId, messageId, ok ? "🗑 Deleted" : "Task not found", null);
            return;
        }
        if (data.startsWith("snooze24:")) {
            String id  = data.substring(9);
            boolean ok = taskService.snoozeTask(userId, id, Duration.ofHours(24));
            answer(update, ok ? "Snoozed 24h" : "Not found");
            if (ok) {
                Optional<Task> task = taskService.findTaskByShortId(userId, id);
                editMessage(chatId, messageId,
                        "⏰ Snoozed 24h\n\n" + task.map(taskService::formatTask).orElse(id),
                        task.map(this::buildTaskKeyboard).orElse(null));
            } else editMessage(chatId, messageId, "Task not found", null);
            return;
        }
        if (data.startsWith("editmenu:")) {
            String id = data.substring(9);
            taskService.findTaskByShortId(userId, id).ifPresent(task -> {
                try { answer(update, "Choose what to edit"); editMessage(chatId, messageId, "✏️ Editing\n\n" + taskService.formatTask(task), buildTaskEditKeyboard(task)); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            });
            return;
        }
        if (data.startsWith("editback:")) {
            String id = data.substring(9);
            taskService.findTaskByShortId(userId, id).ifPresent(task -> {
                try { answer(update, "Back"); editMessage(chatId, messageId, taskService.formatTask(task), buildTaskKeyboard(task)); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            });
            return;
        }
        if (data.startsWith("edittitle:"))  { promptPending(update, chatId, userId, PendingKind.TASK_TITLE,      data.substring(10), "Send the new task title."); return; }
        if (data.startsWith("editprio:"))   { promptPending(update, chatId, userId, PendingKind.TASK_PRIORITY,   data.substring(9),  "Send priority: high, medium, or low."); return; }
        if (data.startsWith("editcat:"))    { promptPending(update, chatId, userId, PendingKind.TASK_CATEGORY,   data.substring(8),  "Send category name, or 'none' to clear."); return; }
        if (data.startsWith("editdue:"))    { promptPending(update, chatId, userId, PendingKind.TASK_DUE,        data.substring(8),  "Send new due date (e.g. tomorrow 8pm, 2026-04-01, or none)."); return; }
        if (data.startsWith("editrecur:"))  { promptPending(update, chatId, userId, PendingKind.TASK_RECURRENCE, data.substring(10), "Send recurrence: none, daily, weekly, or monthly."); return; }
        if (data.startsWith("catedit:")) {
            String category = decodeValue(data.substring(8));
            pendingInputs.put(userId, new PendingInput(PendingKind.CATEGORY_RENAME, category));
            answer(update, "Send new name");
            sendText(chatId, "Send the new name for category '" + category + "'. Use /cancel to stop.");
            return;
        }
        if (data.startsWith("catdelete:")) {
            String category = decodeValue(data.substring(10));
            boolean ok = taskService.deleteCategory(userId, category);
            answer(update, ok ? "Deleted" : "Not found");
            editMessage(chatId, messageId,
                    ok ? "🗑 Deleted category: " + category + "\nAffected tasks moved to: none." : "Category not found.", null);
        }
        if (data.equals("cleardone")) {
            int count = taskService.deleteAllDone(userId);
            answer(update, count > 0 ? "Cleared " + count + " task(s)" : "Nothing to clear");
            editMessage(chatId, messageId,
                    count > 0 ? "🗑 Cleared " + count + " completed task(s)." : "No completed tasks to clear.",
                    null);
            return;
        }
    }

    private void promptPending(Update update, long chatId, long userId, PendingKind kind, String target, String prompt) throws TelegramApiException {
        pendingInputs.put(userId, new PendingInput(kind, target));
        answer(update, prompt);
        sendText(chatId, prompt + " Use /cancel to stop.");
    }

    // ── Display helpers ──────────────────────────────────────────────────────

    private void sendTaskAdded(long chatId, Task task) {
        execute(SendMessage.builder()
                .chatId(chatId)
                .text("Task added!\n\n" + taskService.formatTask(task))
                .replyMarkup(buildTaskKeyboard(task))
                .build());
    }

    private void sendTaskList(long chatId, String title, List<Task> tasks, boolean withButtons) {
        if (tasks.isEmpty()) {
            sendText(chatId, title + "\n\nNo tasks found."); return;
        }

        // Summary header
        long high   = tasks.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        long medium = tasks.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
        long low    = tasks.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();

        String header = title + "  (" + tasks.size() + ")\n"
                + (high   > 0 ? "🔴 " + high + " high   " : "")
                + (medium > 0 ? "🟡 " + medium + " medium   " : "")
                + (low    > 0 ? "🟢 " + low + " low" : "");
        if (!withButtons) {
            // Done list — show Clear All button
            InlineKeyboardMarkup clearBtn = InlineKeyboardMarkup.builder().keyboard(List.of(
                    new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("🗑 Clear All Completed")
                                    .callbackData("cleardone")
                                    .build()
                    )
            )).build();
            execute(SendMessage.builder().chatId(chatId).text(header.trim()).replyMarkup(clearBtn).build());
        } else {
            sendText(chatId, header.trim());
        }

        // Individual task cards sorted by priority (already sorted from DB, but group visually)
        for (Task task : tasks) {
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId)
                    .text(taskService.formatTask(task))
                    .replyMarkup(withButtons && task.getStatus() == Task.Status.ACTIVE ? buildTaskKeyboard(task) : null)
                    .build();
            execute(msg);
        }
    }

    private void sendCategoryList(long chatId, long userId) {
        List<String> categories = taskService.getCategories(userId);
        if (categories.isEmpty()) {
            sendText(chatId, "No categories yet. Use /addcategory <name>"); return;
        }
        sendText(chatId, "🏷 Categories (" + categories.size() + ")");
        for (String category : categories) {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("📁 " + category)
                    .replyMarkup(buildCategoryKeyboard(category))
                    .build());
        }
    }

    // ── Keyboards ────────────────────────────────────────────────────────────

    private InlineKeyboardMarkup buildTaskKeyboard(Task task) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ Done").callbackData("done:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("⏰ Snooze 24h").callbackData("snooze24:" + task.shortId()).build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✏️ Edit").callbackData("editmenu:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("🗑 Delete").callbackData("delete:" + task.shortId()).build()
                )
        )).build();
    }

    private InlineKeyboardMarkup buildTaskEditKeyboard(Task task) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Title").callbackData("edittitle:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("Priority").callbackData("editprio:" + task.shortId()).build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Category").callbackData("editcat:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("Due Date").callbackData("editdue:" + task.shortId()).build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Recurrence").callbackData("editrecur:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("⬅ Back").callbackData("editback:" + task.shortId()).build()
                )
        )).build();
    }

    private InlineKeyboardMarkup buildCategoryKeyboard(String category) {
        String encoded = encodeValue(category);
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✏️ Rename").callbackData("catedit:" + encoded).build(),
                        InlineKeyboardButton.builder().text("🗑 Delete").callbackData("catdelete:" + encoded).build()
                )
        )).build();
    }

    // ── Telegram helpers ─────────────────────────────────────────────────────

    private void answer(Update update, String text) throws TelegramApiException {
        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(update.getCallbackQuery().getId()).text(text).build());
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        EditMessageText.EditMessageTextBuilder b = EditMessageText.builder().chatId(chatId).messageId(messageId).text(text);
        if (keyboard != null) b.replyMarkup(keyboard);
        telegramClient.execute(b.build());
    }

    public void sendText(long chatId, String text) { execute(SendMessage.builder().chatId(chatId).text(text).build()); }

    private void execute(SendMessage message) {
        try { telegramClient.execute(message); }
        catch (TelegramApiException e) { throw new RuntimeException("Failed to send message", e); }
    }

    private String encodeValue(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decodeValue(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }

    private static String addHelpText() {
        return """
                Add tasks in either of these styles:
                /add Finish CPM assignment
                /add Finish CPM assignment tomorrow 8pm
                /add Finish CPM assignment #school !high tomorrow 8pm every week

                Quick tags:
                #category  →  e.g. #school #finance
                !high      →  priority (!medium / !low)
                every week →  recurring (day/week/month)

                Dates: today 8pm · tomorrow 3pm · fri 14:00 · 2026-04-01

                💡 Or just type naturally without /add:
                "remind me to pay rent on Friday at 9am"
                """;
    }
}
