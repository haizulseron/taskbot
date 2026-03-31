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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.lang.Math.toIntExact;

public class TaskBot implements LongPollingSingleThreadUpdateConsumer {

    private enum PendingKind {
        TASK_TITLE, TASK_PRIORITY, TASK_CATEGORY, TASK_DUE, TASK_RECURRENCE,
        TASK_NOTES, REMINDER_INTERVAL, CATEGORY_RENAME
    }

    private record PendingInput(PendingKind kind, String target) {}

    // Undo support
    private enum UndoType { MARK_DONE, DELETE, SNOOZE }
    private record UndoAction(UndoType type, String taskId, String taskTitle,
                              Task.Status prevStatus, LocalDateTime prevDueAt) {}

    private final TelegramClient telegramClient;
    private final TaskService taskService;
    private final ClaudeService claudeService;
    private final String botUsername;
    private final Map<Long, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Map<Long, UndoAction>   undoStack     = new ConcurrentHashMap<>();

    public TaskBot(BotConfig config, TaskService taskService) {
        this.telegramClient = new OkHttpTelegramClient(config.getBotToken());
        this.taskService    = taskService;
        this.botUsername    = config.getBotUsername();
        String apiKey = config.getClaudeApiKey();
        this.claudeService = (apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_CLAUDE_API_KEY"))
                ? new ClaudeService(apiKey, config.getZoneId()) : null;
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
            sendText(chatId, pendingInputs.remove(userId) != null ? "Cancelled." : "Nothing to cancel."); return;
        }
        if (pendingInputs.containsKey(userId)) {
            if (text.startsWith("/")) { sendText(chatId, "Edit in progress. Send the value or use /cancel."); return; }
            handlePendingInput(chatId, userId, text); return;
        }

        switch (text) {
            case "/start"     -> sendText(chatId, "Task bot is live.\n\n" + TaskService.usageText());
            case "/help"      -> sendText(chatId, TaskService.usageText());
            case "/tasks"     -> sendTaskList(chatId, "📋 Active Tasks", taskService.getActiveTasks(userId), true);
            case "/today"     -> sendTaskList(chatId, "🗓 Due Today", taskService.getTodayTasks(userId), true);
            case "/overdue"   -> sendTaskList(chatId, "⚠️ Overdue", taskService.getOverdueTasks(userId), true);
            case "/stale"     -> sendTaskList(chatId, "🧊 Stale Tasks", taskService.getStaleTasks(userId), true);
            case "/doneitems" -> sendTaskList(chatId, "✅ Completed", taskService.getDoneTasks(userId), false);
            case "/cleardone" -> { int n = taskService.deleteAllDone(userId); sendText(chatId, n > 0 ? "🗑 Cleared " + n + " completed task(s)." : "No completed tasks to clear."); }
            case "/review"    -> sendText(chatId, taskService.getReviewSummary(userId));
            case "/categories"-> sendCategoryList(chatId, userId);
            case "/templates" -> sendTemplateList(chatId, userId);
            case "/add"       -> sendText(chatId, addHelpText());
            default           -> handleTextCommand(chatId, userId, text);
        }
    }

    private void handleTextCommand(long chatId, long userId, String text) {
        if (text.startsWith("/add ")) {
            String raw = text.substring(5).trim();
            if (raw.isEmpty()) { sendText(chatId, "Please enter a task after /add"); return; }
            try { sendTaskAdded(chatId, taskService.createTask(userId, chatId, taskService.parseAddCommand(raw))); }
            catch (Exception e) { sendText(chatId, "Couldn't parse that. Send /add to see examples."); }
            return;
        }
        if (text.startsWith("/done "))     { String id = text.substring(6).trim(); doMarkDone(chatId, userId, id); return; }
        if (text.startsWith("/delete "))   { String id = text.substring(8).trim(); doDelete(chatId, userId, id); return; }
        if (text.startsWith("/snooze ")) {
            String[] p = text.substring(8).trim().split("\\s+");
            if (p.length < 2) { sendText(chatId, "Usage: /snooze <id> <hours>"); return; }
            doSnooze(chatId, userId, p[0], Integer.parseInt(p[1])); return;
        }
        if (text.startsWith("/addcategory ")) {
            String name = text.substring(13).trim();
            if (name.isBlank()) { sendText(chatId, "Usage: /addcategory <n>"); return; }
            taskService.addCategory(userId, name);
            sendText(chatId, "Added category: " + name.trim().toLowerCase()); return;
        }
        if (text.startsWith("/search ")) {
            String query = text.substring(8).trim();
            sendTaskList(chatId, "🔍 Search: " + query, taskService.searchTasks(userId, query), true); return;
        }
        if (text.startsWith("/")) { sendText(chatId, "Unknown command. Use /help"); return; }

        if (claudeService != null) handleNaturalLanguage(chatId, userId, text);
        else sendText(chatId, "Unknown command. Use /help\n\n💡 Set CLAUDE_API_KEY to enable natural language.");
    }

    // ── Natural language ─────────────────────────────────────────────────────

    private void handleNaturalLanguage(long chatId, long userId, String text) {
        ClaudeService.ParsedTask p = claudeService.parse(text);

        switch (p.type()) {
            case "task" -> {
                try {
                    TaskService.AddTaskRequest req = new TaskService.AddTaskRequest(
                            p.title(), p.priority(), p.category(), p.dueAt(), p.recurrence(), p.notes());
                    sendTaskAdded(chatId, taskService.createTask(userId, chatId, req));
                } catch (Exception e) { sendText(chatId, "Understood but couldn't save. Try /add instead."); }
            }

            // List with optional filters
            case "list_tasks" -> {
                boolean filtered = p.filterPriority() != null || p.filterCategory() != null || p.filterDueRange() != null;
                if (filtered) {
                    String title = buildFilterTitle(p);
                    sendTaskList(chatId, title, taskService.getFilteredTasks(userId, p.filterPriority(), p.filterCategory(), p.filterDueRange()), true);
                } else {
                    sendTaskList(chatId, "📋 Active Tasks", taskService.getActiveTasks(userId), true);
                }
            }
            case "list_today"      -> sendTaskList(chatId, "🗓 Due Today", taskService.getTodayTasks(userId), true);
            case "list_overdue"    -> sendTaskList(chatId, "⚠️ Overdue", taskService.getOverdueTasks(userId), true);
            case "list_stale"      -> sendTaskList(chatId, "🧊 Stale Tasks", taskService.getStaleTasks(userId), true);
            case "list_done"       -> sendTaskList(chatId, "✅ Completed", taskService.getDoneTasks(userId), false);
            case "list_categories" -> sendCategoryList(chatId, userId);
            case "list_templates"  -> sendTemplateList(chatId, userId);
            case "review"          -> sendText(chatId, taskService.getReviewSummary(userId));

            case "search_tasks" -> {
                if (p.searchQuery() == null) { sendText(chatId, "What would you like to search for?"); return; }
                sendTaskList(chatId, "🔍 Search: " + p.searchQuery(), taskService.searchTasks(userId, p.searchQuery()), true);
            }

            case "mark_done" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task? Try: \"mark [task name] as done\""); return; }
                taskService.findTaskByTitleHint(userId, p.targetTitle()).ifPresentOrElse(
                        t -> { doMarkDone(chatId, userId, t.shortId()); },
                        () -> sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\". Use /tasks to see IDs."));
            }

            case "mark_all_done" -> {
                int n = taskService.markAllDone(userId);
                sendText(chatId, n > 0 ? "✅ Marked " + n + " task(s) done. Fresh start!" : "No active tasks.");
            }

            case "delete_task" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task would you like to delete?"); return; }
                taskService.findTaskByTitleHint(userId, p.targetTitle()).ifPresentOrElse(
                        t -> doDelete(chatId, userId, t.shortId()),
                        () -> sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\"."));
            }

            case "delete_all_done" -> {
                int n = taskService.deleteAllDone(userId);
                sendText(chatId, n > 0 ? "🗑 Cleared " + n + " completed task(s)." : "No completed tasks to clear.");
            }

            case "snooze_task" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task would you like to snooze?"); return; }
                int hours = p.snoozeHours() > 0 ? p.snoozeHours() : 24;
                taskService.findTaskByTitleHint(userId, p.targetTitle()).ifPresentOrElse(
                        t -> doSnooze(chatId, userId, t.shortId(), hours),
                        () -> sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\"."));
            }

            case "reschedule_task" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task would you like to reschedule?"); return; }
                if (p.newDueDate() == null)  { sendText(chatId, "When would you like to reschedule it to?"); return; }
                taskService.findTaskByTitleHint(userId, p.targetTitle()).ifPresentOrElse(t -> {
                    taskService.updateTaskDueAtDirectly(userId, t.shortId(), p.newDueDate());
                    sendText(chatId, "📅 Rescheduled \"" + t.getTitle() + "\" to " + taskService.friendlyDate(p.newDueDate()));
                }, () -> sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\"."));
            }

            case "duplicate_task" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task would you like to duplicate?"); return; }
                Task dup = taskService.duplicateTask(userId, chatId, p.targetTitle(), p.newDueDate());
                if (dup != null) sendTaskAdded(chatId, dup);
                else sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\".");
            }

            case "bulk_action" -> handleBulkAction(chatId, userId, p);

            case "add_notes" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task would you like to add notes to?"); return; }
                taskService.findTaskByTitleHint(userId, p.targetTitle()).ifPresentOrElse(t -> {
                    taskService.updateTaskNotes(userId, t.shortId(), p.notes());
                    sendText(chatId, "📝 Notes updated for \"" + t.getTitle() + "\".");
                }, () -> sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\"."));
            }

            case "set_reminder_interval" -> {
                if (p.targetTitle() == null) { sendText(chatId, "Which task?"); return; }
                if (p.reminderIntervalMinutes() <= 0) { sendText(chatId, "How often (in minutes) should I remind you?"); return; }
                taskService.findTaskByTitleHint(userId, p.targetTitle()).ifPresentOrElse(t -> {
                    taskService.setReminderInterval(userId, t.shortId(), p.reminderIntervalMinutes());
                    sendText(chatId, "⏱ Set reminder for \"" + t.getTitle() + "\" every " + p.reminderIntervalMinutes() + " min.");
                }, () -> sendText(chatId, "Couldn't find \"" + p.targetTitle() + "\"."));
            }

            case "set_quiet_hours" -> {
                if (p.quietStart() == null || p.quietEnd() == null) {
                    sendText(chatId, "Please specify start and end time, e.g. \"no reminders from 10pm to 7am\""); return;
                }
                taskService.saveUserSettings(userId, p.quietStart(), p.quietEnd());
                sendText(chatId, "🌙 Quiet hours set: " + p.quietStart() + " – " + p.quietEnd() + ". No reminders during this window.");
            }

            case "save_template" -> {
                if (p.targetTitle() == null || p.templateName() == null) {
                    sendText(chatId, "Try: \"save the gym task as a template called workout\""); return;
                }
                boolean ok = taskService.saveTemplate(userId, p.targetTitle(), p.templateName());
                sendText(chatId, ok ? "💾 Saved template \"" + p.templateName() + "\"." : "Couldn't find task \"" + p.targetTitle() + "\".");
            }

            case "use_template" -> {
                if (p.templateName() == null) { sendText(chatId, "Which template? Try: \"use my workout template for tomorrow\""); return; }
                Task task = taskService.useTemplate(userId, chatId, p.templateName(), p.dueAt());
                if (task != null) sendTaskAdded(chatId, task);
                else sendText(chatId, "Template \"" + p.templateName() + "\" not found. Use /templates to see your templates.");
            }

            case "undo" -> handleUndo(chatId, userId);

            default -> {
                String reply = p.clarification() != null ? p.clarification() : "Not sure what you mean. Use /help.";
                sendText(chatId, reply);
            }
        }
    }

    // ── Action helpers ───────────────────────────────────────────────────────

    private void doMarkDone(long chatId, long userId, String shortId) {
        taskService.findTaskByShortId(userId, shortId).ifPresentOrElse(task -> {
            undoStack.put(userId, new UndoAction(UndoType.MARK_DONE, task.getId(), task.getTitle(), Task.Status.ACTIVE, task.getDueAt()));
            taskService.markDone(userId, shortId);
            sendText(chatId, "✅ Done: " + task.getTitle() + "\n\nSay \"undo\" to reverse.");
        }, () -> sendText(chatId, "Task not found: " + shortId));
    }

    private void doDelete(long chatId, long userId, String shortId) {
        taskService.findTaskByShortId(userId, shortId).ifPresentOrElse(task -> {
            undoStack.put(userId, new UndoAction(UndoType.DELETE, task.getId(), task.getTitle(), Task.Status.ACTIVE, task.getDueAt()));
            taskService.deleteTask(userId, shortId);
            sendText(chatId, "🗑 Deleted: " + task.getTitle() + "\n\nSay \"undo\" to reverse.");
        }, () -> sendText(chatId, "Task not found: " + shortId));
    }

    private void doSnooze(long chatId, long userId, String shortId, int hours) {
        taskService.findTaskByShortId(userId, shortId).ifPresentOrElse(task -> {
            undoStack.put(userId, new UndoAction(UndoType.SNOOZE, task.getId(), task.getTitle(), Task.Status.ACTIVE, task.getDueAt()));
            taskService.snoozeTask(userId, shortId, Duration.ofHours(hours));
            sendText(chatId, "⏰ Snoozed \"" + task.getTitle() + "\" by " + hours + "h.\n\nSay \"undo\" to reverse.");
        }, () -> sendText(chatId, "Task not found: " + shortId));
    }

    private void handleUndo(long chatId, long userId) {
        UndoAction undo = undoStack.remove(userId);
        if (undo == null) { sendText(chatId, "Nothing to undo."); return; }
        taskService.restoreTask(undo.taskId(), undo.prevStatus(), undo.prevDueAt());
        sendText(chatId, "↩️ Undone: \"" + undo.taskTitle() + "\" restored.");
    }

    private void handleBulkAction(long chatId, long userId, ClaudeService.ParsedTask p) {
        String target = p.bulkTarget(), op = p.bulkOp();
        if (target == null || op == null) { sendText(chatId, "What would you like to do in bulk?"); return; }
        int hours = p.snoozeHours() > 0 ? p.snoozeHours() : 24;

        int count = switch (target.toLowerCase()) {
            case "overdue" -> switch (op.toLowerCase()) {
                case "snooze" -> taskService.bulkSnoozeOverdue(userId, hours);
                case "delete" -> { List<Task> t = taskService.getOverdueTasks(userId); t.forEach(x -> taskService.deleteTask(userId, x.shortId())); yield t.size(); }
                case "mark_done" -> { List<Task> t = taskService.getOverdueTasks(userId); t.forEach(x -> taskService.markDone(userId, x.shortId())); yield t.size(); }
                default -> 0;
            };
            case "stale" -> switch (op.toLowerCase()) {
                case "delete" -> taskService.bulkDeleteStale(userId);
                case "mark_done" -> { List<Task> t = taskService.getStaleTasks(userId); t.forEach(x -> taskService.markDone(userId, x.shortId())); yield t.size(); }
                default -> 0;
            };
            case "all_done" -> taskService.deleteAllDone(userId);
            default -> 0;
        };

        String opLabel = switch (op.toLowerCase()) {
            case "snooze" -> "snoozed " + hours + "h";
            case "delete" -> "deleted";
            case "mark_done" -> "marked done";
            default -> op;
        };
        sendText(chatId, count > 0 ? "✅ " + count + " task(s) " + opLabel + "." : "No tasks to act on.");
    }

    private String buildFilterTitle(ClaudeService.ParsedTask p) {
        List<String> parts = new ArrayList<>();
        if (p.filterPriority() != null) parts.add(p.filterPriority());
        if (p.filterCategory() != null)  parts.add(p.filterCategory());
        if (p.filterDueRange() != null)   parts.add(p.filterDueRange().replace("_", " "));
        return "🔍 " + String.join(" · ", parts) + " tasks";
    }

    // ── Pending edits ────────────────────────────────────────────────────────

    private void handlePendingInput(long chatId, long userId, String text) {
        PendingInput pending = pendingInputs.remove(userId);
        if (pending == null) return;
        boolean ok = switch (pending.kind()) {
            case TASK_TITLE        -> taskService.updateTaskTitle(userId, pending.target(), text);
            case TASK_PRIORITY     -> taskService.updateTaskPriority(userId, pending.target(), text);
            case TASK_CATEGORY     -> taskService.updateTaskCategory(userId, pending.target(), text);
            case TASK_DUE          -> taskService.updateTaskDueAt(userId, pending.target(), text);
            case TASK_RECURRENCE   -> taskService.updateTaskRecurrence(userId, pending.target(), text);
            case TASK_NOTES        -> taskService.updateTaskNotes(userId, pending.target(), text);
            case REMINDER_INTERVAL -> { try { taskService.setReminderInterval(userId, pending.target(), Integer.parseInt(text.trim())); yield true; } catch (Exception e) { yield false; } }
            case CATEGORY_RENAME   -> taskService.renameCategory(userId, pending.target(), text);
        };
        String label = switch (pending.kind()) {
            case TASK_TITLE -> "title"; case TASK_PRIORITY -> "priority"; case TASK_CATEGORY -> "category";
            case TASK_DUE -> "due date"; case TASK_RECURRENCE -> "recurrence"; case TASK_NOTES -> "notes";
            case REMINDER_INTERVAL -> "reminder interval"; case CATEGORY_RENAME -> "category name";
        };
        sendText(chatId, ok ? "Updated " + label + "." : "Task not found or invalid value.");
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    private void handleCallback(Update update) throws TelegramApiException {
        String data   = update.getCallbackQuery().getData();
        long chatId   = update.getCallbackQuery().getMessage().getChatId();
        long userId   = update.getCallbackQuery().getFrom().getId();
        int messageId = toIntExact(update.getCallbackQuery().getMessage().getMessageId());

        if (data.startsWith("done:")) {
            String id = data.substring(5); doMarkDone(chatId, userId, id);
            answer(update, "Done!"); editMessage(chatId, messageId, "✅ Completed", null); return;
        }
        if (data.startsWith("delete:")) {
            String id = data.substring(7); doDelete(chatId, userId, id);
            answer(update, "Deleted"); editMessage(chatId, messageId, "🗑 Deleted", null); return;
        }
        if (data.startsWith("snooze24:")) {
            String id = data.substring(9); doSnooze(chatId, userId, id, 24);
            answer(update, "Snoozed 24h");
            taskService.findTaskByShortId(userId, id).ifPresent(task -> {
                try { editMessage(chatId, messageId, "⏰ Snoozed 24h\n\n" + taskService.formatTask(task), buildTaskKeyboard(task)); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            });
            return;
        }
        if (data.startsWith("editmenu:")) {
            String id = data.substring(9);
            taskService.findTaskByShortId(userId, id).ifPresent(task -> {
                try { answer(update, "Choose what to edit"); editMessage(chatId, messageId, "✏️ Editing\n\n" + taskService.formatTask(task), buildTaskEditKeyboard(task)); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            }); return;
        }
        if (data.startsWith("editback:")) {
            String id = data.substring(9);
            taskService.findTaskByShortId(userId, id).ifPresent(task -> {
                try { answer(update, "Back"); editMessage(chatId, messageId, taskService.formatTask(task), buildTaskKeyboard(task)); }
                catch (TelegramApiException e) { throw new RuntimeException(e); }
            }); return;
        }
        if (data.startsWith("edittitle:"))   { promptPending(update, chatId, userId, PendingKind.TASK_TITLE,      data.substring(10), "Send the new task title."); return; }
        if (data.startsWith("editprio:"))    { promptPending(update, chatId, userId, PendingKind.TASK_PRIORITY,   data.substring(9),  "Send priority: high, medium, or low."); return; }
        if (data.startsWith("editcat:"))     { promptPending(update, chatId, userId, PendingKind.TASK_CATEGORY,   data.substring(8),  "Send category name, or 'none' to clear."); return; }
        if (data.startsWith("editdue:"))     { promptPending(update, chatId, userId, PendingKind.TASK_DUE,        data.substring(8),  "Send new due date (e.g. tomorrow 8pm, none)."); return; }
        if (data.startsWith("editrecur:"))   { promptPending(update, chatId, userId, PendingKind.TASK_RECURRENCE, data.substring(10), "Send recurrence: none, daily, weekly, or monthly."); return; }
        if (data.startsWith("editnotes:"))   { promptPending(update, chatId, userId, PendingKind.TASK_NOTES,      data.substring(10), "Send notes for this task, or 'none' to clear."); return; }
        if (data.startsWith("editinterval:")){ promptPending(update, chatId, userId, PendingKind.REMINDER_INTERVAL, data.substring(13), "Send reminder interval in minutes (e.g. 30, 60, 120)."); return; }
        if (data.startsWith("catedit:")) {
            String cat = decodeValue(data.substring(8));
            pendingInputs.put(userId, new PendingInput(PendingKind.CATEGORY_RENAME, cat));
            answer(update, "Send new name"); sendText(chatId, "Send new name for '" + cat + "'. Use /cancel to stop."); return;
        }
        if (data.startsWith("catdelete:")) {
            String cat = decodeValue(data.substring(10));
            boolean ok = taskService.deleteCategory(userId, cat);
            answer(update, ok ? "Deleted" : "Not found");
            editMessage(chatId, messageId, ok ? "🗑 Deleted category: " + cat : "Category not found.", null); return;
        }
        if (data.equals("cleardone")) {
            int n = taskService.deleteAllDone(userId);
            answer(update, n > 0 ? "Cleared " + n : "Nothing to clear");
            editMessage(chatId, messageId, n > 0 ? "🗑 Cleared " + n + " completed task(s)." : "No completed tasks.", null); return;
        }
    }

    private void promptPending(Update update, long chatId, long userId, PendingKind kind, String target, String prompt) throws TelegramApiException {
        pendingInputs.put(userId, new PendingInput(kind, target));
        answer(update, prompt);
        sendText(chatId, prompt + " Use /cancel to stop.");
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private void sendTaskAdded(long chatId, Task task) {
        execute(SendMessage.builder().chatId(chatId).text("Task added!\n\n" + taskService.formatTask(task)).replyMarkup(buildTaskKeyboard(task)).build());
    }

    private void sendTaskList(long chatId, String title, List<Task> tasks, boolean withButtons) {
        if (tasks.isEmpty()) { sendText(chatId, title + "\n\nNo tasks found."); return; }
        long high = tasks.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        long med  = tasks.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
        long low  = tasks.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();
        String header = title + "  (" + tasks.size() + ")\n"
                + (high > 0 ? "🔴 " + high + " high   " : "")
                + (med  > 0 ? "🟡 " + med  + " medium   " : "")
                + (low  > 0 ? "🟢 " + low  + " low" : "");
        if (!withButtons) {
            execute(SendMessage.builder().chatId(chatId).text(header.trim())
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text("🗑 Clear All Completed").callbackData("cleardone").build()
                    ))).build()).build());
        } else {
            sendText(chatId, header.trim());
        }
        for (Task task : tasks) {
            execute(SendMessage.builder().chatId(chatId).text(taskService.formatTask(task))
                    .replyMarkup(withButtons && task.getStatus() == Task.Status.ACTIVE ? buildTaskKeyboard(task) : null).build());
        }
    }

    private void sendCategoryList(long chatId, long userId) {
        List<String> cats = taskService.getCategories(userId);
        if (cats.isEmpty()) { sendText(chatId, "No categories yet. Use /addcategory <n>"); return; }
        sendText(chatId, "🏷 Categories (" + cats.size() + ")");
        for (String cat : cats) {
            execute(SendMessage.builder().chatId(chatId).text("📁 " + cat).replyMarkup(buildCategoryKeyboard(cat)).build());
        }
    }

    private void sendTemplateList(long chatId, long userId) {
        List<Template> templates = taskService.getTemplates(userId);
        if (templates.isEmpty()) { sendText(chatId, "No templates yet.\n\nSay: \"save the gym task as a template called workout\""); return; }
        StringBuilder sb = new StringBuilder("💾 Templates (" + templates.size() + ")\n─────────────────\n");
        templates.forEach(t -> {
            String dot = switch (t.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; };
            sb.append(dot).append(" ").append(t.getName()).append("\n")
              .append("  Title: ").append(t.getTitle()).append("\n")
              .append("  Category: ").append(t.getCategory()).append("\n\n");
        });
        sb.append("Say: \"use my [name] template for tomorrow\"");
        sendText(chatId, sb.toString().trim());
    }

    // ── Keyboards ────────────────────────────────────────────────────────────

    private InlineKeyboardMarkup buildTaskKeyboard(Task task) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ Done").callbackData("done:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("⏰ Snooze 24h").callbackData("snooze24:" + task.shortId()).build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✏️ Edit").callbackData("editmenu:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("🗑 Delete").callbackData("delete:" + task.shortId()).build())
        )).build();
    }

    private InlineKeyboardMarkup buildTaskEditKeyboard(Task task) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Title").callbackData("edittitle:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("Priority").callbackData("editprio:" + task.shortId()).build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Category").callbackData("editcat:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("Due Date").callbackData("editdue:" + task.shortId()).build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Recurrence").callbackData("editrecur:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("Notes").callbackData("editnotes:" + task.shortId()).build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("⏱ Reminder").callbackData("editinterval:" + task.shortId()).build(),
                        InlineKeyboardButton.builder().text("⬅ Back").callbackData("editback:" + task.shortId()).build())
        )).build();
    }

    private InlineKeyboardMarkup buildCategoryKeyboard(String category) {
        String encoded = encodeValue(category);
        return InlineKeyboardMarkup.builder().keyboard(List.of(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✏️ Rename").callbackData("catedit:" + encoded).build(),
                InlineKeyboardButton.builder().text("🗑 Delete").callbackData("catdelete:" + encoded).build()
        ))).build();
    }

    // ── Telegram helpers ─────────────────────────────────────────────────────

    private void answer(Update update, String text) throws TelegramApiException {
        telegramClient.execute(AnswerCallbackQuery.builder().callbackQueryId(update.getCallbackQuery().getId()).text(text).build());
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        EditMessageText.EditMessageTextBuilder b = EditMessageText.builder().chatId(chatId).messageId(messageId).text(text);
        if (keyboard != null) b.replyMarkup(keyboard);
        telegramClient.execute(b.build());
    }

    public void sendText(long chatId, String text) { execute(SendMessage.builder().chatId(chatId).text(text).build()); }

    private void execute(SendMessage msg) {
        try { telegramClient.execute(msg); }
        catch (TelegramApiException e) { throw new RuntimeException("Failed to send message", e); }
    }

    private String encodeValue(String v) { return Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8)); }
    private String decodeValue(String v) { return new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8); }

    private static String addHelpText() {
        return """
                /add Finish CEE report
                /add Submit assignment tomorrow 8pm #school !high
                /add Pay rent #finance 2026-04-25 every month

                Quick tags: #category  !high/!medium/!low  every week

                💡 Or just type naturally:
                "remind me to submit the report tomorrow at 3pm"
                "show my high priority school tasks"
                "move gym to next Monday"
                """;
    }
}
