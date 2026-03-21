package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class TaskService {
    public record AddTaskRequest(String title,
                                 Task.Priority priority,
                                 String category,
                                 LocalDateTime dueAt,
                                 Task.Recurrence recurrence) {
    }

    public record ReminderDue(Task task, int stage, String label) {
    }

    public record UserChat(long userId, long chatId) {
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DEFAULT_CATEGORY = "none";

    private final Database database;
    private final ZoneId zoneId;
    private final int defaultStaleDays;

    public TaskService(Database database, ZoneId zoneId, int defaultStaleDays) {
        this.database = database;
        this.zoneId = zoneId;
        this.defaultStaleDays = defaultStaleDays;
    }

    public AddTaskRequest parseAddCommand(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            throw new IllegalArgumentException("Use: /add title | priority | category | yyyy-MM-dd HH:mm | recurrence\n"
                    + "Example: /add Submit report | high | school | 2026-03-25 20:00 | weekly");
        }

        String[] parts = input.split("\\|");
        String title = parts.length > 0 ? parts[0].trim() : "";
        if (title.isBlank()) {
            throw new IllegalArgumentException("Task title cannot be empty.");
        }

        Task.Priority priority = parts.length > 1 && !parts[1].trim().isBlank()
                ? Task.Priority.fromText(parts[1])
                : Task.Priority.MEDIUM;

        String category = parts.length > 2 && !parts[2].trim().isBlank()
                ? normalizeCategory(parts[2].trim())
                : DEFAULT_CATEGORY;

        LocalDateTime dueAt = null;
        if (parts.length > 3 && !parts[3].trim().isBlank()) {
            dueAt = parseDateTime(parts[3].trim());
        }

        Task.Recurrence recurrence = parts.length > 4 && !parts[4].trim().isBlank()
                ? Task.Recurrence.fromText(parts[4])
                : Task.Recurrence.NONE;

        return new AddTaskRequest(title, priority, category, dueAt, recurrence);
    }

    public Task createTask(long userId, long chatId, AddTaskRequest request) {
        String category = normalizeCategory(request.category());
        ensureCategoryExists(userId, category);
        LocalDateTime now = LocalDateTime.now(zoneId);

        Task task = new Task();
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        task.setUserId(userId);
        task.setChatId(chatId);
        task.setTitle(request.title().trim());
        task.setPriority(request.priority());
        task.setCategory(category);
        task.setDueAt(request.dueAt());
        task.setStatus(Task.Status.ACTIVE);
        task.setRecurrence(request.recurrence());
        task.setStaleAfterDays(defaultStaleDays);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setReminderStage(0);

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO tasks (
                         id, user_id, chat_id, title, priority, category, due_at, status, recurrence,
                         stale_after_days, created_at, updated_at, reminder_stage, last_reminder_at, stale_notified_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bindTask(statement, task);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create task", e);
        }
        return task;
    }

    public List<Task> getActiveTasks(long userId) {
        return queryTasks("SELECT * FROM tasks WHERE user_id = ? AND status = 'ACTIVE' ORDER BY CASE priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, due_at IS NULL, due_at, created_at", userId);
    }

    public List<Task> getTodayTasks(long userId) {
        LocalDate today = LocalDate.now(zoneId);
        return getActiveTasks(userId).stream()
                .filter(task -> task.getDueAt() != null && task.getDueAt().toLocalDate().isEqual(today))
                .collect(Collectors.toList());
    }

    public List<Task> getOverdueTasks(long userId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return getActiveTasks(userId).stream()
                .filter(task -> task.getDueAt() != null && task.getDueAt().isBefore(now))
                .sorted(Comparator.comparing(Task::getDueAt))
                .collect(Collectors.toList());
    }

    public List<Task> getStaleTasks(long userId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return getActiveTasks(userId).stream()
                .filter(task -> isTaskStale(task, now))
                .collect(Collectors.toList());
    }

    public List<Task> getDoneTasks(long userId) {
        return queryTasks("SELECT * FROM tasks WHERE user_id = ? AND status = 'DONE' ORDER BY updated_at DESC", userId);
    }

    public Optional<Task> findTaskByShortId(long userId, String shortId) {
        if (shortId == null || shortId.isBlank()) {
            return Optional.empty();
        }
        List<Task> tasks = queryTasks("SELECT * FROM tasks WHERE user_id = ?", userId);
        return tasks.stream()
                .filter(task -> task.getId().startsWith(shortId.trim()))
                .findFirst();
    }

    public boolean markDone(long userId, String shortId) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }

        Task task = optionalTask.get();
        LocalDateTime now = LocalDateTime.now(zoneId);
        updateTaskStatus(task.getId(), Task.Status.DONE, now);
        if (task.getRecurrence() != Task.Recurrence.NONE) {
            createNextRecurringTask(task, now);
        }
        return true;
    }

    public boolean deleteTask(long userId, String shortId) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        updateTaskStatus(optionalTask.get().getId(), Task.Status.DELETED, LocalDateTime.now(zoneId));
        return true;
    }

    public boolean snoozeTask(long userId, String shortId, Duration duration) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        Task task = optionalTask.get();
        LocalDateTime base = task.getDueAt() != null ? task.getDueAt() : LocalDateTime.now(zoneId);
        task.setDueAt(base.plus(duration));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        task.setReminderStage(0);
        updateTask(task);
        return true;
    }

    public boolean updateTaskTitle(long userId, String shortId, String newTitle) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        String title = newTitle == null ? "" : newTitle.trim();
        if (title.isBlank()) {
            throw new IllegalArgumentException("Task title cannot be empty.");
        }
        Task task = optionalTask.get();
        task.setTitle(title);
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public boolean updateTaskPriority(long userId, String shortId, String newPriority) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        Task task = optionalTask.get();
        task.setPriority(Task.Priority.fromText(newPriority));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public boolean updateTaskCategory(long userId, String shortId, String newCategory) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        String category = normalizeCategory(newCategory);
        ensureCategoryExists(userId, category);
        Task task = optionalTask.get();
        task.setCategory(category);
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public boolean updateTaskDueAt(long userId, String shortId, String input) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        Task task = optionalTask.get();
        if (input == null || input.isBlank() || input.trim().equalsIgnoreCase("none") || input.trim().equalsIgnoreCase("clear")) {
            task.setDueAt(null);
        } else {
            task.setDueAt(parseDateTime(input.trim()));
        }
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        task.setReminderStage(0);
        updateTask(task);
        return true;
    }

    public boolean updateTaskRecurrence(long userId, String shortId, String input) {
        Optional<Task> optionalTask = findTaskByShortId(userId, shortId);
        if (optionalTask.isEmpty()) {
            return false;
        }
        Task task = optionalTask.get();
        String value = input == null ? "" : input.trim();
        if (value.equalsIgnoreCase("clear")) {
            value = "none";
        }
        task.setRecurrence(Task.Recurrence.fromText(value));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public List<String> getCategories(long userId) {
        List<String> categories = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT name FROM categories WHERE user_id = ? ORDER BY name")) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    categories.add(resultSet.getString("name"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch categories", e);
        }
        return categories;
    }

    public void addCategory(long userId, String name) {
        String normalized = normalizeCategory(name);
        ensureCategoryExists(userId, normalized);
    }

    public boolean renameCategory(long userId, String oldName, String newName) {
        String oldCategory = normalizeCategory(oldName);
        String newCategory = normalizeCategory(newName);
        if (DEFAULT_CATEGORY.equals(oldCategory)) {
            return false;
        }
        ensureCategoryExists(userId, newCategory);

        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement updateTasks = connection.prepareStatement("UPDATE tasks SET category = ?, updated_at = ? WHERE user_id = ? AND category = ?");
                 PreparedStatement deleteOld = connection.prepareStatement("DELETE FROM categories WHERE user_id = ? AND name = ?")) {
                updateTasks.setString(1, newCategory);
                updateTasks.setString(2, LocalDateTime.now(zoneId).toString());
                updateTasks.setLong(3, userId);
                updateTasks.setString(4, oldCategory);
                int updated = updateTasks.executeUpdate();

                deleteOld.setLong(1, userId);
                deleteOld.setString(2, oldCategory);
                deleteOld.executeUpdate();

                connection.commit();
                return updated > 0 || categoryExists(userId, newCategory);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename category", e);
        }
    }

    public boolean deleteCategory(long userId, String categoryName) {
        String category = normalizeCategory(categoryName);
        if (DEFAULT_CATEGORY.equals(category)) {
            return false;
        }

        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement updateTasks = connection.prepareStatement("UPDATE tasks SET category = ?, updated_at = ? WHERE user_id = ? AND category = ?");
                 PreparedStatement deleteCategory = connection.prepareStatement("DELETE FROM categories WHERE user_id = ? AND name = ?")) {
                String now = LocalDateTime.now(zoneId).toString();
                updateTasks.setString(1, DEFAULT_CATEGORY);
                updateTasks.setString(2, now);
                updateTasks.setLong(3, userId);
                updateTasks.setString(4, category);
                int tasksChanged = updateTasks.executeUpdate();

                deleteCategory.setLong(1, userId);
                deleteCategory.setString(2, category);
                int categoryDeleted = deleteCategory.executeUpdate();

                connection.commit();
                return tasksChanged > 0 || categoryDeleted > 0;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete category", e);
        }
    }

    public String getReviewSummary(long userId) {
        List<Task> active = getActiveTasks(userId);
        List<Task> today = getTodayTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);
        List<Task> stale = getStaleTasks(userId);
        List<Task> done = getDoneTasks(userId);

        return "📊 Review\n"
                + "Active: " + active.size() + "\n"
                + "Due today: " + today.size() + "\n"
                + "Overdue: " + overdue.size() + "\n"
                + "Stale: " + stale.size() + "\n"
                + "Done: " + done.size();
    }

    public String formatTask(Task task) {
        String priorityEmoji = switch (task.getPriority()) {
            case HIGH -> "🔴";
            case MEDIUM -> "🟡";
            case LOW -> "🟢";
        };

        String due = task.getDueAt() == null ? "No due date" : task.getDueAt().format(DATE_TIME_FORMATTER);
        return priorityEmoji + " [" + task.shortId() + "] " + task.getTitle()
                + "\nCategory: " + safe(task.getCategory())
                + " | Priority: " + task.getPriority()
                + " | Due: " + due
                + " | Recurring: " + task.getRecurrence();
    }

    public String buildMorningSummary(long userId) {
        List<Task> today = getTodayTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);
        List<Task> stale = getStaleTasks(userId);

        StringBuilder builder = new StringBuilder();
        builder.append("☀️ Good morning\n")
                .append("Today: ").append(today.size()).append(" task(s)\n")
                .append("Overdue: ").append(overdue.size()).append("\n")
                .append("Stale: ").append(stale.size()).append("\n\n");

        if (!today.isEmpty()) {
            builder.append("Due today:\n");
            today.forEach(task -> builder.append("- ").append(task.shortId()).append(" | ").append(task.getTitle())
                    .append(task.getDueAt() != null ? " @ " + task.getDueAt().format(DATE_TIME_FORMATTER) : "")
                    .append("\n"));
            builder.append("\n");
        }
        if (!overdue.isEmpty()) {
            builder.append("Overdue:\n");
            overdue.forEach(task -> builder.append("- ").append(task.shortId()).append(" | ").append(task.getTitle()).append("\n"));
        }
        return builder.toString().trim();
    }

    public List<UserChat> getKnownUserChats() {
        List<UserChat> pairs = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT user_id, chat_id FROM tasks")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    pairs.add(new UserChat(resultSet.getLong("user_id"), resultSet.getLong("chat_id")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch user chats", e);
        }
        return pairs;
    }

    public List<ReminderDue> findDueReminders() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        List<Task> activeWithDueDates = queryTasks("SELECT * FROM tasks WHERE status = 'ACTIVE' AND due_at IS NOT NULL", null);
        List<ReminderDue> due = new ArrayList<>();

        for (Task task : activeWithDueDates) {
            long minutesUntilDue = Duration.between(now, task.getDueAt()).toMinutes();
            int currentStage = task.getReminderStage() == null ? 0 : task.getReminderStage();

            if (currentStage < 1 && minutesUntilDue <= 1440 && minutesUntilDue > 120) {
                due.add(new ReminderDue(task, 1, "Due within 24 hours"));
            } else if (currentStage < 2 && minutesUntilDue <= 120 && minutesUntilDue > 30) {
                due.add(new ReminderDue(task, 2, "Due within 2 hours"));
            } else if (task.getPriority() == Task.Priority.HIGH && currentStage < 3 && minutesUntilDue <= 30 && minutesUntilDue >= -10) {
                due.add(new ReminderDue(task, 3, "High priority task due very soon"));
            }
        }
        return due;
    }

    public void markReminderSent(String taskId, int stage) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE tasks SET reminder_stage = ?, last_reminder_at = ? WHERE id = ?")) {
            statement.setInt(1, stage);
            statement.setString(2, now.toString());
            statement.setString(3, taskId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark reminder sent", e);
        }
    }

    public List<Task> findStaleTasksNeedingPing() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        List<Task> activeTasks = queryTasks("SELECT * FROM tasks WHERE status = 'ACTIVE'", null);
        return activeTasks.stream()
                .filter(task -> isTaskStale(task, now))
                .filter(task -> task.getStaleNotifiedAt() == null || task.getStaleNotifiedAt().toLocalDate().isBefore(now.toLocalDate()))
                .collect(Collectors.toList());
    }

    public void markStalePinged(String taskId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE tasks SET stale_notified_at = ? WHERE id = ?")) {
            statement.setString(1, now.toString());
            statement.setString(2, taskId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark stale ping", e);
        }
    }

    public static String usageText() {
        return """
                Available commands:
                /start - intro
                /help - command list
                /add title | priority | category | yyyy-MM-dd HH:mm | recurrence
                /tasks - active tasks
                /today - tasks due today
                /overdue - overdue tasks
                /stale - stale tasks
                /done <taskId> - mark task done
                /delete <taskId> - delete task
                /snooze <taskId> <hours> - push task by X hours
                /doneitems - completed tasks
                /addcategory <name> - add a category
                /categories - list categories with edit/delete buttons
                /review - summary counts
                /cancel - cancel the current edit prompt

                Example:
                /add Finish CPM assignment | high | school | 2026-03-25 20:00 | weekly
                """;
    }

    private void createNextRecurringTask(Task completedTask, LocalDateTime now) {
        if (completedTask.getDueAt() == null) {
            return;
        }

        LocalDateTime nextDue = switch (completedTask.getRecurrence()) {
            case DAILY -> completedTask.getDueAt().plusDays(1);
            case WEEKLY -> completedTask.getDueAt().plusWeeks(1);
            case MONTHLY -> completedTask.getDueAt().plusMonths(1);
            case NONE -> null;
        };

        if (nextDue == null) {
            return;
        }

        Task nextTask = new Task();
        nextTask.setId(UUID.randomUUID().toString().replace("-", ""));
        nextTask.setUserId(completedTask.getUserId());
        nextTask.setChatId(completedTask.getChatId());
        nextTask.setTitle(completedTask.getTitle());
        nextTask.setPriority(completedTask.getPriority());
        nextTask.setCategory(completedTask.getCategory());
        nextTask.setDueAt(nextDue);
        nextTask.setStatus(Task.Status.ACTIVE);
        nextTask.setRecurrence(completedTask.getRecurrence());
        nextTask.setStaleAfterDays(completedTask.getStaleAfterDays());
        nextTask.setCreatedAt(now);
        nextTask.setUpdatedAt(now);
        nextTask.setReminderStage(0);

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO tasks (
                         id, user_id, chat_id, title, priority, category, due_at, status, recurrence,
                         stale_after_days, created_at, updated_at, reminder_stage, last_reminder_at, stale_notified_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bindTask(statement, nextTask);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create recurring task", e);
        }
    }

    private boolean isTaskStale(Task task, LocalDateTime now) {
        if (task.getStatus() != Task.Status.ACTIVE) {
            return false;
        }
        if (task.getDueAt() != null && !task.getDueAt().isAfter(now.plusHours(24))) {
            return false;
        }
        return task.getUpdatedAt().plusDays(task.getStaleAfterDays()).isBefore(now);
    }

    private void ensureCategoryExists(long userId, String category) {
        String normalized = normalizeCategory(category);
        if (normalized.isBlank() || DEFAULT_CATEGORY.equals(normalized)) {
            return;
        }
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO categories (user_id, name, created_at) VALUES (?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setString(2, normalized);
            statement.setString(3, LocalDateTime.now(zoneId).toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure category exists", e);
        }
    }

    private boolean categoryExists(long userId, String category) {
        if (DEFAULT_CATEGORY.equals(category)) {
            return true;
        }
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM categories WHERE user_id = ? AND name = ? LIMIT 1")) {
            statement.setLong(1, userId);
            statement.setString(2, category);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check category", e);
        }
    }

    private List<Task> queryTasks(String sql, Long userIdOrNull) {
        List<Task> tasks = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (userIdOrNull != null && sql.contains("user_id = ?")) {
                statement.setLong(1, userIdOrNull);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tasks.add(mapTask(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query tasks", e);
        }
        return tasks;
    }

    private void updateTaskStatus(String taskId, Task.Status status, LocalDateTime updatedAt) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setString(2, updatedAt.toString());
            statement.setString(3, taskId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update task status", e);
        }
    }

    private void updateTask(Task task) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE tasks SET
                         title = ?, priority = ?, category = ?, due_at = ?, status = ?, recurrence = ?,
                         stale_after_days = ?, updated_at = ?, reminder_stage = ?, last_reminder_at = ?, stale_notified_at = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, task.getTitle());
            statement.setString(2, task.getPriority().name());
            statement.setString(3, task.getCategory());
            statement.setString(4, formatDateTime(task.getDueAt()));
            statement.setString(5, task.getStatus().name());
            statement.setString(6, task.getRecurrence().name());
            statement.setInt(7, task.getStaleAfterDays());
            statement.setString(8, task.getUpdatedAt().toString());
            statement.setInt(9, task.getReminderStage() == null ? 0 : task.getReminderStage());
            statement.setString(10, formatDateTime(task.getLastReminderAt()));
            statement.setString(11, formatDateTime(task.getStaleNotifiedAt()));
            statement.setString(12, task.getId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update task", e);
        }
    }

    private void bindTask(PreparedStatement statement, Task task) throws SQLException {
        statement.setString(1, task.getId());
        statement.setLong(2, task.getUserId());
        statement.setLong(3, task.getChatId());
        statement.setString(4, task.getTitle());
        statement.setString(5, task.getPriority().name());
        statement.setString(6, task.getCategory());
        statement.setString(7, formatDateTime(task.getDueAt()));
        statement.setString(8, task.getStatus().name());
        statement.setString(9, task.getRecurrence().name());
        statement.setInt(10, task.getStaleAfterDays());
        statement.setString(11, task.getCreatedAt().toString());
        statement.setString(12, task.getUpdatedAt().toString());
        statement.setInt(13, task.getReminderStage() == null ? 0 : task.getReminderStage());
        statement.setString(14, formatDateTime(task.getLastReminderAt()));
        statement.setString(15, formatDateTime(task.getStaleNotifiedAt()));
    }

    private Task mapTask(ResultSet resultSet) throws SQLException {
        Task task = new Task();
        task.setId(resultSet.getString("id"));
        task.setUserId(resultSet.getLong("user_id"));
        task.setChatId(resultSet.getLong("chat_id"));
        task.setTitle(resultSet.getString("title"));
        task.setPriority(Task.Priority.fromText(resultSet.getString("priority")));
        task.setCategory(resultSet.getString("category"));
        task.setDueAt(parseStoredDateTime(resultSet.getString("due_at")));
        task.setStatus(Task.Status.fromText(resultSet.getString("status")));
        task.setRecurrence(Task.Recurrence.fromText(resultSet.getString("recurrence")));
        task.setStaleAfterDays(resultSet.getInt("stale_after_days"));
        task.setCreatedAt(parseStoredDateTime(resultSet.getString("created_at")));
        task.setUpdatedAt(parseStoredDateTime(resultSet.getString("updated_at")));
        task.setReminderStage(resultSet.getInt("reminder_stage"));
        task.setLastReminderAt(parseStoredDateTime(resultSet.getString("last_reminder_at")));
        task.setStaleNotifiedAt(parseStoredDateTime(resultSet.getString("stale_notified_at")));
        return task;
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime parseStoredDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value);
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return DEFAULT_CATEGORY;
        }
        String value = category.trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? DEFAULT_CATEGORY : value;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? DEFAULT_CATEGORY : value;
    }

    private LocalDateTime parseDateTime(String input) {
        try {
            return LocalDateTime.parse(input, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }

        String lower = input.toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now(zoneId);
        if (lower.startsWith("today ")) {
            return LocalDateTime.of(today, parseTime(lower.substring(6)));
        }
        if (lower.startsWith("tomorrow ")) {
            return LocalDateTime.of(today.plusDays(1), parseTime(lower.substring(9)));
        }
        throw new IllegalArgumentException("Invalid date format. Use yyyy-MM-dd HH:mm, or 'today HH:mm', or 'tomorrow HH:mm'. Use 'none' to clear.");
    }

    private LocalTime parseTime(String raw) {
        String value = raw.trim();
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalTime.parse(value.toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("h:mma"));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalTime.parse(value.toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("ha"));
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("Invalid time. Use HH:mm, h:mma, or ha. Example: 20:00 or 8PM");
    }
}
