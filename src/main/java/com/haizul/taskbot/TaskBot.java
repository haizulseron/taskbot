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

import static java.lang.Math.toIntExact;

public class TaskBot implements LongPollingSingleThreadUpdateConsumer {
    private enum PendingKind {
        TASK_TITLE,
        TASK_PRIORITY,
        TASK_CATEGORY,
        TASK_DUE,
        TASK_RECURRENCE,
        CATEGORY_RENAME
    }

    private record PendingInput(PendingKind kind, String target) {
    }

    private final TelegramClient telegramClient;
    private final TaskService taskService;
    private final String botUsername;
    private final Map<Long, PendingInput> pendingInputs = new ConcurrentHashMap<>();

    public TaskBot(BotConfig config, TaskService taskService) {
        this.telegramClient = new OkHttpTelegramClient(config.getBotToken());
        this.taskService = taskService;
        this.botUsername = config.getBotUsername();
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (update.hasMessage()) {
                sendText(update.getMessage().getChatId(), "Something went wrong: " + e.getMessage());
            }
        }
    }

    public TelegramClient getTelegramClient() {
        return telegramClient;
    }

    public String getBotUsername() {
        return botUsername;
    }

    private void handleMessage(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText().trim();

        if (text.equals("/cancel")) {
            boolean removed = pendingInputs.remove(userId) != null;
            sendText(chatId, removed ? "Cancelled current edit." : "There is no active edit to cancel.");
            return;
        }

        if (pendingInputs.containsKey(userId)) {
            if (text.startsWith("/")) {
                sendText(chatId, "You have an edit waiting. Send the new value, or use /cancel.");
                return;
            }
            handlePendingInput(chatId, userId, text);
            return;
        }

        if (text.equals("/start")) {
            sendText(chatId, "Task bot is live.\n\n" + TaskService.usageText());
            return;
        }
        if (text.equals("/help")) {
            sendText(chatId, TaskService.usageText());
            return;
        }
        if (text.equals("/add")) {
            sendText(chatId,
                    """
                    Add tasks in either of these styles:
                    /add Finish CPM assignment
                    /add Finish CPM assignment tomorrow 8pm
                    /add Finish CPM assignment #school !high tomorrow 8pm every week
                    /add Pay rent #finance 2026-03-25 09:00 every month

                    Quick tags:
                    #category   -> custom category
                    !high       -> priority (also !medium / !low)
                    every week  -> recurring (day/week/month also works)

                    Dates supported:
                    today 8pm
                    tomorrow 8pm
                    mon 3pm / friday 14:00
                    2026-03-25 20:00
                    2026-03-25

                    Legacy format still works:
                    /add Title | high | school | 2026-03-25 20:00 | weekly
                    """);
            return;
        }
        if (text.startsWith("/add ")) {
            String raw = text.substring(5).trim();

            if (raw.isEmpty()) {
                sendText(chatId, "Please enter a task after /add");
                return;
            }

            try {
                TaskService.AddTaskRequest request = taskService.parseAddCommand(raw);
                Task task = taskService.createTask(userId, chatId, request);
                sendTaskAdded(chatId, task);
            } catch (Exception e) {
                sendText(chatId, "Could not understand that task. Send /add to see the supported formats.");
            }
            return;
        }
        if (text.equals("/tasks")) {
            sendTaskList(chatId, "📋 Active tasks", taskService.getActiveTasks(userId));
            return;
        }
        if (text.equals("/today")) {
            sendTaskList(chatId, "🗓 Tasks due today", taskService.getTodayTasks(userId));
            return;
        }
        if (text.equals("/overdue")) {
            sendTaskList(chatId, "⚠️ Overdue tasks", taskService.getOverdueTasks(userId));
            return;
        }
        if (text.equals("/stale")) {
            sendTaskList(chatId, "🧊 Stale tasks", taskService.getStaleTasks(userId));
            return;
        }
        if (text.equals("/doneitems")) {
            sendTaskList(chatId, "✅ Done tasks", taskService.getDoneTasks(userId));
            return;
        }
        if (text.equals("/review")) {
            sendText(chatId, taskService.getReviewSummary(userId));
            return;
        }
        if (text.equals("/categories")) {
            sendCategoryList(chatId, userId);
            return;
        }
        if (text.startsWith("/addcategory ")) {
            String name = text.substring(13).trim();
            if (name.isBlank()) {
                sendText(chatId, "Usage: /addcategory <name>");
            } else {
                taskService.addCategory(userId, name);
                sendText(chatId, "Added category: " + name.trim().toLowerCase());
            }
            return;
        }
        if (text.startsWith("/done ")) {
            String shortId = text.substring(6).trim();
            boolean ok = taskService.markDone(userId, shortId);
            sendText(chatId, ok ? "Marked as done: " + shortId : "Task not found: " + shortId);
            return;
        }
        if (text.startsWith("/delete ")) {
            String shortId = text.substring(8).trim();
            boolean ok = taskService.deleteTask(userId, shortId);
            sendText(chatId, ok ? "Deleted task: " + shortId : "Task not found: " + shortId);
            return;
        }
        if (text.startsWith("/snooze ")) {
            String[] parts = text.substring(8).trim().split("\\s+");
            if (parts.length < 2) {
                sendText(chatId, "Usage: /snooze <taskId> <hours>");
                return;
            }
            String shortId = parts[0];
            long hours = Long.parseLong(parts[1]);
            boolean ok = taskService.snoozeTask(userId, shortId, Duration.ofHours(hours));
            sendText(chatId, ok ? "Snoozed " + shortId + " by " + hours + " hour(s)." : "Task not found: " + shortId);
            return;
        }

        sendText(chatId, "Unknown command. Use /help");
    }

    private void handlePendingInput(long chatId, long userId, String text) {
        PendingInput pending = pendingInputs.get(userId);
        if (pending == null) {
            return;
        }

        boolean ok;
        switch (pending.kind()) {
            case TASK_TITLE -> {
                ok = taskService.updateTaskTitle(userId, pending.target(), text);
                sendText(chatId, ok ? "Updated task title." : "Task not found.");
            }
            case TASK_PRIORITY -> {
                ok = taskService.updateTaskPriority(userId, pending.target(), text);
                sendText(chatId, ok ? "Updated task priority." : "Task not found.");
            }
            case TASK_CATEGORY -> {
                ok = taskService.updateTaskCategory(userId, pending.target(), text);
                sendText(chatId, ok ? "Updated task category." : "Task not found.");
            }
            case TASK_DUE -> {
                ok = taskService.updateTaskDueAt(userId, pending.target(), text);
                sendText(chatId, ok ? "Updated task due date." : "Task not found.");
            }
            case TASK_RECURRENCE -> {
                ok = taskService.updateTaskRecurrence(userId, pending.target(), text);
                sendText(chatId, ok ? "Updated task recurrence." : "Task not found.");
            }
            case CATEGORY_RENAME -> {
                ok = taskService.renameCategory(userId, pending.target(), text);
                sendText(chatId, ok ? "Renamed category to: " + text.trim().toLowerCase() : "Category not found or cannot be changed.");
            }
            default -> throw new IllegalStateException("Unexpected value: " + pending.kind());
        }
        pendingInputs.remove(userId);
    }

    private void handleCallback(Update update) throws TelegramApiException {
        String data = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        long userId = update.getCallbackQuery().getFrom().getId();
        int messageId = toIntExact(update.getCallbackQuery().getMessage().getMessageId());

        if (data.startsWith("done:")) {
            String taskId = data.substring(5);
            boolean ok = taskService.markDone(userId, taskId);
            answer(update, ok ? "Task completed" : "Task not found");
            editMessage(chatId, messageId, ok ? "✅ Task completed: " + taskId : "Task not found", null);
            return;
        }

        if (data.startsWith("delete:")) {
            String taskId = data.substring(7);
            boolean ok = taskService.deleteTask(userId, taskId);
            answer(update, ok ? "Task deleted" : "Task not found");
            editMessage(chatId, messageId, ok ? "🗑 Task deleted: " + taskId : "Task not found", null);
            return;
        }

        if (data.startsWith("snooze24:")) {
            String taskId = data.substring(9);
            boolean ok = taskService.snoozeTask(userId, taskId, Duration.ofHours(24));
            answer(update, ok ? "Snoozed by 24 hours" : "Task not found");
            if (ok) {
                Optional<Task> task = taskService.findTaskByShortId(userId, taskId);
                editMessage(chatId, messageId, "⏰ Task snoozed by 24 hours\n\n" + task.map(taskService::formatTask).orElse(taskId), task.map(this::buildTaskKeyboard).orElse(null));
            } else {
                editMessage(chatId, messageId, "Task not found", null);
            }
            return;
        }

        if (data.startsWith("editmenu:")) {
            String taskId = data.substring(9);
            Optional<Task> task = taskService.findTaskByShortId(userId, taskId);
            answer(update, task.isPresent() ? "Choose what to edit" : "Task not found");
            if (task.isPresent()) {
                editMessage(chatId, messageId, "✏️ Edit task\n\n" + taskService.formatTask(task.get()), buildTaskEditKeyboard(task.get()));
            }
            return;
        }

        if (data.startsWith("editback:")) {
            String taskId = data.substring(9);
            Optional<Task> task = taskService.findTaskByShortId(userId, taskId);
            answer(update, task.isPresent() ? "Back to task actions" : "Task not found");
            if (task.isPresent()) {
                editMessage(chatId, messageId, taskService.formatTask(task.get()), buildTaskKeyboard(task.get()));
            }
            return;
        }

        if (data.startsWith("edittitle:")) {
            String taskId = data.substring(10);
            pendingInputs.put(userId, new PendingInput(PendingKind.TASK_TITLE, taskId));
            answer(update, "Send the new title");
            sendText(chatId, "Send the new task title. Use /cancel to stop.");
            return;
        }

        if (data.startsWith("editprio:")) {
            String taskId = data.substring(9);
            pendingInputs.put(userId, new PendingInput(PendingKind.TASK_PRIORITY, taskId));
            answer(update, "Send high, medium, or low");
            sendText(chatId, "Send the new priority: high, medium, or low. Use /cancel to stop.");
            return;
        }

        if (data.startsWith("editcat:")) {
            String taskId = data.substring(8);
            pendingInputs.put(userId, new PendingInput(PendingKind.TASK_CATEGORY, taskId));
            answer(update, "Send the new category");
            sendText(chatId, "Send the new category name. Use none to clear it. Use /cancel to stop.");
            return;
        }

        if (data.startsWith("editdue:")) {
            String taskId = data.substring(8);
            pendingInputs.put(userId, new PendingInput(PendingKind.TASK_DUE, taskId));
            answer(update, "Send the new due date");
            sendText(chatId, "Send the new due date like 2026-03-25 20:00, today 8pm, tomorrow 8pm, or none. Use /cancel to stop.");
            return;
        }

        if (data.startsWith("editrecur:")) {
            String taskId = data.substring(10);
            pendingInputs.put(userId, new PendingInput(PendingKind.TASK_RECURRENCE, taskId));
            answer(update, "Send recurrence");
            sendText(chatId, "Send the recurrence: none, daily, weekly, or monthly. Use /cancel to stop.");
            return;
        }

        if (data.startsWith("catedit:")) {
            String category = decodeValue(data.substring(8));
            pendingInputs.put(userId, new PendingInput(PendingKind.CATEGORY_RENAME, category));
            answer(update, "Send the new category name");
            sendText(chatId, "Send the new name for category '" + category + "'. Use /cancel to stop.");
            return;
        }

        if (data.startsWith("catdelete:")) {
            String category = decodeValue(data.substring(10));
            boolean ok = taskService.deleteCategory(userId, category);
            answer(update, ok ? "Category deleted" : "Category not found");
            editMessage(chatId, messageId,
                    ok ? "🗑 Deleted category: " + category + "\nAny tasks using it were moved to category: none."
                            : "Category not found or cannot be deleted.",
                    null);
        }
    }

    private void sendTaskAdded(long chatId, Task task) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Task added\n\n" + taskService.formatTask(task))
                .replyMarkup(buildTaskKeyboard(task))
                .build();
        execute(message);
    }

    private void sendTaskList(long chatId, String title, List<Task> tasks) {
        if (tasks.isEmpty()) {
            sendText(chatId, title + "\n\nNo tasks found.");
            return;
        }

        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            String text = (i == 0 ? title + "\n\n" : "") + taskService.formatTask(task);
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(task.getStatus() == Task.Status.ACTIVE ? buildTaskKeyboard(task) : null)
                    .build();
            execute(message);
        }
    }

    private void sendCategoryList(long chatId, long userId) {
        List<String> categories = taskService.getCategories(userId);
        if (categories.isEmpty()) {
            sendText(chatId, "No categories yet. Use /addcategory <name>");
            return;
        }

        sendText(chatId, "Categories");
        for (String category : categories) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text("🏷 " + category)
                    .replyMarkup(buildCategoryKeyboard(category))
                    .build();
            execute(message);
        }
    }

    private InlineKeyboardMarkup buildTaskKeyboard(Task task) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✅ Done").callbackData("done:" + task.shortId()).build(),
                InlineKeyboardButton.builder().text("⏰ Snooze 24h").callbackData("snooze24:" + task.shortId()).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✏️ Edit").callbackData("editmenu:" + task.shortId()).build(),
                InlineKeyboardButton.builder().text("🗑 Delete").callbackData("delete:" + task.shortId()).build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup buildTaskEditKeyboard(Task task) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Title").callbackData("edittitle:" + task.shortId()).build(),
                InlineKeyboardButton.builder().text("Priority").callbackData("editprio:" + task.shortId()).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Category").callbackData("editcat:" + task.shortId()).build(),
                InlineKeyboardButton.builder().text("Due").callbackData("editdue:" + task.shortId()).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Recurrence").callbackData("editrecur:" + task.shortId()).build(),
                InlineKeyboardButton.builder().text("⬅ Back").callbackData("editback:" + task.shortId()).build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup buildCategoryKeyboard(String category) {
        String encoded = encodeValue(category);
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✏️ Rename").callbackData("catedit:" + encoded).build(),
                InlineKeyboardButton.builder().text("🗑 Delete").callbackData("catdelete:" + encoded).build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void answer(Update update, String text) throws TelegramApiException {
        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(update.getCallbackQuery().getId())
                .text(text)
                .build());
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        EditMessageText.EditMessageTextBuilder builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text);
        if (keyboard != null) {
            builder.replyMarkup(keyboard);
        }
        telegramClient.execute(builder.build());
    }

    private String encodeValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeValue(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public void sendText(long chatId, String text) {
        execute(SendMessage.builder().chatId(chatId).text(text).build());
    }

    private void execute(SendMessage message) {
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send Telegram message", e);
        }
    }
}
