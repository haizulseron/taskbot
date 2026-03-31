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
                                 Task.Recurrence recurrence) {}

    public record ReminderDue(Task task, int stage, String label) {}

    public record UserChat(long userId, long chatId) {}

    private static final DateTimeFormatter STORE_FORMATTER   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER    = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DEFAULT_CATEGORY = "none";

    private final Database database;
    private final ZoneId zoneId;
    private final int defaultStaleDays;

    public TaskService(Database database, ZoneId zoneId, int defaultStaleDays) {
        this.database = database;
        this.zoneId = zoneId;
        this.defaultStaleDays = defaultStaleDays;
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    public AddTaskRequest parseAddCommand(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            throw new IllegalArgumentException("Use /add followed by a task title. Send /add for examples.");
        }
        return input.contains("|") ? parseLegacyAddCommand(input) : parseNaturalAddCommand(input);
    }

    private AddTaskRequest parseLegacyAddCommand(String input) {
        String[] parts = input.split("\\|");
        String title = parts.length > 0 ? parts[0].trim() : "";
        if (title.isBlank()) throw new IllegalArgumentException("Task title cannot be empty.");

        Task.Priority priority   = parts.length > 1 && !parts[1].trim().isBlank() ? Task.Priority.fromText(parts[1]) : Task.Priority.MEDIUM;
        String category          = parts.length > 2 && !parts[2].trim().isBlank() ? normalizeCategory(parts[2].trim()) : DEFAULT_CATEGORY;
        LocalDateTime dueAt      = parts.length > 3 && !parts[3].trim().isBlank() ? parseDateTime(parts[3].trim()) : null;
        Task.Recurrence recurrence = parts.length > 4 && !parts[4].trim().isBlank() ? Task.Recurrence.fromText(parts[4]) : Task.Recurrence.NONE;

        return new AddTaskRequest(title, priority, category, dueAt, recurrence);
    }

    private AddTaskRequest parseNaturalAddCommand(String input) {
        List<String> tokens = new ArrayList<>(Arrays.asList(input.split("\\s+")));
        if (tokens.isEmpty()) throw new IllegalArgumentException("Task title cannot be empty.");

        Task.Priority priority     = Task.Priority.MEDIUM;
        String category            = DEFAULT_CATEGORY;
        Task.Recurrence recurrence = Task.Recurrence.NONE;
        List<String> titleTokens   = new ArrayList<>();
        List<String> dateTokens    = new ArrayList<>();

        Set<String> weekdayTokens = new HashSet<>(Arrays.asList(
                "mon", "monday", "tue", "tues", "tuesday", "wed", "wednesday",
                "thu", "thur", "thurs", "thursday", "fri", "friday",
                "sat", "saturday", "sun", "sunday"));

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);

            if (lower.startsWith("#") && lower.length() > 1) { category = normalizeCategory(lower.substring(1)); continue; }
            if (lower.equals("!high") || lower.equals("!medium") || lower.equals("!low")) { priority = Task.Priority.fromText(lower.substring(1)); continue; }

            if (lower.equals("every") && i + 1 < tokens.size()) {
                String next = tokens.get(i + 1).toLowerCase(Locale.ROOT);
                recurrence = switch (next) {
                    case "day", "daily"       -> Task.Recurrence.DAILY;
                    case "week", "weekly"     -> Task.Recurrence.WEEKLY;
                    case "month", "monthly"   -> Task.Recurrence.MONTHLY;
                    default -> recurrence;
                };
                boolean consumed = List.of("day","daily","week","weekly","month","monthly").contains(next);
                if (!consumed) titleTokens.add(token); else i++;
                continue;
            }

            if (lower.equals("today") || lower.equals("tomorrow")) {
                dateTokens.add(token);
                if (i + 1 < tokens.size() && looksLikeTimeToken(tokens.get(i + 1))) { dateTokens.add(tokens.get(++i)); }
                continue;
            }
            if (weekdayTokens.contains(lower)) {
                dateTokens.add(token);
                if (i + 1 < tokens.size() && looksLikeTimeToken(tokens.get(i + 1))) { dateTokens.add(tokens.get(++i)); }
                continue;
            }
            if (looksLikeDateToken(token)) {
                dateTokens.add(token);
                if (i + 1 < tokens.size() && looksLikeTimeToken(tokens.get(i + 1))) { dateTokens.add(tokens.get(++i)); }
                continue;
            }
            titleTokens.add(token);
        }

        String title = String.join(" ", titleTokens).trim();
        if (title.isBlank()) throw new IllegalArgumentException("Task title cannot be empty.");

        LocalDateTime dueAt = dateTokens.isEmpty() ? null : parseDateTime(String.join(" ", dateTokens));
        return new AddTaskRequest(title, priority, category, dueAt, recurrence);
    }

    private boolean looksLikeDateToken(String token) { return token.toLowerCase(Locale.ROOT).matches("\\d{4}-\\d{2}-\\d{2}"); }
    private boolean looksLikeTimeToken(String token) {
        String v = token.toLowerCase(Locale.ROOT);
        return v.matches("\\d{1,2}:\\d{2}") || v.matches("\\d{1,2}(am|pm)") || v.matches("\\d{1,2}:\\d{2}(am|pm)");
    }

    // ── Task CRUD ────────────────────────────────────────────────────────────

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
                .filter(t -> t.getDueAt() != null && t.getDueAt().toLocalDate().isEqual(today))
                .collect(Collectors.toList());
    }

    public List<Task> getOverdueTasks(long userId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return getActiveTasks(userId).stream()
                .filter(t -> t.getDueAt() != null && t.getDueAt().isBefore(now))
                .sorted(Comparator.comparing(Task::getDueAt))
                .collect(Collectors.toList());
    }

    public List<Task> getStaleTasks(long userId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return getActiveTasks(userId).stream()
                .filter(t -> isTaskStale(t, now))
                .collect(Collectors.toList());
    }

    public List<Task> getDoneTasks(long userId) {
        return queryTasks("SELECT * FROM tasks WHERE user_id = ? AND status = 'DONE' ORDER BY updated_at DESC", userId);
    }

    public Optional<Task> findTaskByShortId(long userId, String shortId) {
        if (shortId == null || shortId.isBlank()) return Optional.empty();
        return queryTasks("SELECT * FROM tasks WHERE user_id = ?", userId).stream()
                .filter(t -> t.getId().startsWith(shortId.trim()))
                .findFirst();
    }

    public Optional<Task> findTaskByTitleHint(long userId, String hint) {
        if (hint == null || hint.isBlank()) return Optional.empty();
        String lower = hint.toLowerCase(Locale.ROOT);
        return getActiveTasks(userId).stream()
                .filter(t -> t.getTitle().toLowerCase(Locale.ROOT).contains(lower))
                .findFirst();
    }

    public boolean markDone(long userId, String shortId) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        LocalDateTime now = LocalDateTime.now(zoneId);
        updateTaskStatus(task.getId(), Task.Status.DONE, now);
        if (task.getRecurrence() != Task.Recurrence.NONE) createNextRecurringTask(task, now);
        return true;
    }

    public int markAllDone(long userId) {
        List<Task> active = getActiveTasks(userId);
        LocalDateTime now = LocalDateTime.now(zoneId);
        int count = 0;
        for (Task task : active) {
            updateTaskStatus(task.getId(), Task.Status.DONE, now);
            if (task.getRecurrence() != Task.Recurrence.NONE) createNextRecurringTask(task, now);
            count++;
        }
        return count;
    }

    public int deleteAllDone(long userId) {
        List<Task> done = getDoneTasks(userId);
        LocalDateTime now = LocalDateTime.now(zoneId);
        for (Task task : done) {
            updateTaskStatus(task.getId(), Task.Status.DELETED, now);
        }
        return done.size();
    }

    public boolean deleteTask(long userId, String shortId) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        updateTaskStatus(opt.get().getId(), Task.Status.DELETED, LocalDateTime.now(zoneId));
        return true;
    }

    public boolean snoozeTask(long userId, String shortId, Duration duration) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        LocalDateTime base = task.getDueAt() != null ? task.getDueAt() : LocalDateTime.now(zoneId);
        task.setDueAt(base.plus(duration));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        task.setReminderStage(0);
        updateTask(task);
        return true;
    }

    public boolean updateTaskTitle(long userId, String shortId, String newTitle) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        String title = newTitle == null ? "" : newTitle.trim();
        if (title.isBlank()) throw new IllegalArgumentException("Task title cannot be empty.");
        Task task = opt.get();
        task.setTitle(title);
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public boolean updateTaskPriority(long userId, String shortId, String newPriority) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        task.setPriority(Task.Priority.fromText(newPriority));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public boolean updateTaskCategory(long userId, String shortId, String newCategory) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        String category = normalizeCategory(newCategory);
        ensureCategoryExists(userId, category);
        Task task = opt.get();
        task.setCategory(category);
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public boolean updateTaskDueAt(long userId, String shortId, String input) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
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
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        String value = input == null ? "" : input.trim();
        if (value.equalsIgnoreCase("clear")) value = "none";
        task.setRecurrence(Task.Recurrence.fromText(value));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    // ── Categories ───────────────────────────────────────────────────────────

    public List<String> getCategories(long userId) {
        List<String> categories = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT name FROM categories WHERE user_id = ? ORDER BY name")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) categories.add(rs.getString("name"));
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to fetch categories", e); }
        return categories;
    }

    public void addCategory(long userId, String name) { ensureCategoryExists(userId, normalizeCategory(name)); }

    public boolean renameCategory(long userId, String oldName, String newName) {
        String oldCat = normalizeCategory(oldName), newCat = normalizeCategory(newName);
        if (DEFAULT_CATEGORY.equals(oldCat)) return false;
        ensureCategoryExists(userId, newCat);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement upd = c.prepareStatement("UPDATE tasks SET category = ?, updated_at = ? WHERE user_id = ? AND category = ?");
                 PreparedStatement del = c.prepareStatement("DELETE FROM categories WHERE user_id = ? AND name = ?")) {
                upd.setString(1, newCat); upd.setString(2, LocalDateTime.now(zoneId).toString()); upd.setLong(3, userId); upd.setString(4, oldCat);
                int updated = upd.executeUpdate();
                del.setLong(1, userId); del.setString(2, oldCat); del.executeUpdate();
                c.commit();
                return updated > 0 || categoryExists(userId, newCat);
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        } catch (SQLException e) { throw new RuntimeException("Failed to rename category", e); }
    }

    public boolean deleteCategory(long userId, String categoryName) {
        String category = normalizeCategory(categoryName);
        if (DEFAULT_CATEGORY.equals(category)) return false;
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement upd = c.prepareStatement("UPDATE tasks SET category = ?, updated_at = ? WHERE user_id = ? AND category = ?");
                 PreparedStatement del = c.prepareStatement("DELETE FROM categories WHERE user_id = ? AND name = ?")) {
                upd.setString(1, DEFAULT_CATEGORY); upd.setString(2, LocalDateTime.now(zoneId).toString()); upd.setLong(3, userId); upd.setString(4, category);
                int changed = upd.executeUpdate();
                del.setLong(1, userId); del.setString(2, category); int deleted = del.executeUpdate();
                c.commit();
                return changed > 0 || deleted > 0;
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        } catch (SQLException e) { throw new RuntimeException("Failed to delete category", e); }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    public String formatTask(Task task) {
        String priorityEmoji = switch (task.getPriority()) {
            case HIGH   -> "🔴";
            case MEDIUM -> "🟡";
            case LOW    -> "🟢";
        };
        String recur = task.getRecurrence() == Task.Recurrence.NONE ? "" : "  🔁 " + capitalize(task.getRecurrence().name());
        String cat   = DEFAULT_CATEGORY.equals(task.getCategory()) ? "" : "  📁 " + task.getCategory();
        String due   = task.getDueAt() == null ? "" : "  📅 " + formatDue(task.getDueAt());

        return priorityEmoji + " " + task.getTitle()
                + "\n" + cat + due + recur
                + "\nID: " + task.shortId();
    }

    public String formatDue(LocalDateTime dueAt) {
        if (dueAt == null) return "—";
        LocalDate today    = LocalDate.now(zoneId);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate dueDate  = dueAt.toLocalDate();
        String time        = dueAt.format(TIME_FORMATTER);

        if (dueDate.isEqual(today))    return "Today, " + time;
        if (dueDate.isEqual(tomorrow)) return "Tomorrow, " + time;
        return dueAt.format(DISPLAY_FORMATTER);
    }

    public String getReviewSummary(long userId) {
        List<Task> active  = getActiveTasks(userId);
        List<Task> today   = getTodayTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);
        List<Task> stale   = getStaleTasks(userId);
        List<Task> done    = getDoneTasks(userId);

        long high   = active.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        long medium = active.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
        long low    = active.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();

        return "📊 Your Review\n"
                + "─────────────────\n"
                + "Active:      " + active.size() + " task(s)\n"
                + "  🔴 High    " + high + "\n"
                + "  🟡 Medium  " + medium + "\n"
                + "  🟢 Low     " + low + "\n"
                + "─────────────────\n"
                + "📅 Due today:  " + today.size() + "\n"
                + "⚠️ Overdue:   " + overdue.size() + "\n"
                + "🧊 Stale:     " + stale.size() + "\n"
                + "✅ Done:      " + done.size();
    }

    public String buildMorningSummary(long userId) {
        List<Task> today   = getTodayTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);
        List<Task> stale   = getStaleTasks(userId);

        StringBuilder sb = new StringBuilder();
        sb.append("☀️ Good morning! Here's your day:\n")
          .append("─────────────────\n")
          .append("📅 Due today: ").append(today.size()).append("\n")
          .append("⚠️ Overdue:  ").append(overdue.size()).append("\n")
          .append("🧊 Stale:    ").append(stale.size()).append("\n");

        if (!today.isEmpty()) {
            sb.append("\nDue today:\n");
            today.forEach(t -> sb.append("  ").append(priorityDot(t)).append(" ").append(t.getTitle())
                    .append(t.getDueAt() != null ? " @ " + t.getDueAt().format(TIME_FORMATTER) : "").append("\n"));
        }
        if (!overdue.isEmpty()) {
            sb.append("\nOverdue:\n");
            overdue.forEach(t -> sb.append("  ").append(priorityDot(t)).append(" ").append(t.getTitle()).append("\n"));
        }
        return sb.toString().trim();
    }

    private String priorityDot(Task t) {
        return switch (t.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; };
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    public static String usageText() {
        return """
                Available commands:
                /start — intro
                /help — command list
                /add — add a task (see examples)
                /tasks — active tasks
                /today — due today
                /overdue — overdue tasks
                /stale — stale tasks
                /done <id> — mark done
                /delete <id> — delete task
                /snooze <id> <hours> — snooze task
                /doneitems — completed tasks
                /review — summary overview
                /categories — manage categories
                /addcategory <name> — add category
                /cancel — cancel active edit

                💡 Or just type naturally — no commands needed!
                e.g. "remind me to submit the report tomorrow 3pm"
                     "show me my tasks"
                     "mark the gym task as done"
                     "clear everything"
                """;
    }

    // ── Reminders / Scheduler ────────────────────────────────────────────────

    public List<UserChat> getKnownUserChats() {
        List<UserChat> pairs = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT DISTINCT user_id, chat_id FROM tasks")) {
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) pairs.add(new UserChat(rs.getLong("user_id"), rs.getLong("chat_id")));
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to fetch user chats", e); }
        return pairs;
    }

    public List<ReminderDue> findDueReminders() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        List<Task> active = queryTasks("SELECT * FROM tasks WHERE status = 'ACTIVE' AND due_at IS NOT NULL", null);
        List<ReminderDue> due = new ArrayList<>();
        for (Task task : active) {
            long mins = Duration.between(now, task.getDueAt()).toMinutes();
            int stage = task.getReminderStage() == null ? 0 : task.getReminderStage();
            if (stage < 1 && mins <= 1440 && mins > 120) due.add(new ReminderDue(task, 1, "Due within 24 hours"));
            else if (stage < 2 && mins <= 120 && mins > 30) due.add(new ReminderDue(task, 2, "Due within 2 hours"));
            else if (task.getPriority() == Task.Priority.HIGH && stage < 3 && mins <= 30 && mins >= -10)
                due.add(new ReminderDue(task, 3, "High priority task due very soon"));
        }
        return due;
    }

    public void markReminderSent(String taskId, int stage) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET reminder_stage = ?, last_reminder_at = ? WHERE id = ?")) {
            s.setInt(1, stage); s.setString(2, now.toString()); s.setString(3, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to mark reminder sent", e); }
    }

    public List<Task> findStaleTasksNeedingPing() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return queryTasks("SELECT * FROM tasks WHERE status = 'ACTIVE'", null).stream()
                .filter(t -> isTaskStale(t, now))
                .filter(t -> t.getStaleNotifiedAt() == null || t.getStaleNotifiedAt().toLocalDate().isBefore(now.toLocalDate()))
                .collect(Collectors.toList());
    }

    public void markStalePinged(String taskId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET stale_notified_at = ? WHERE id = ?")) {
            s.setString(1, now.toString()); s.setString(2, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to mark stale ping", e); }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void createNextRecurringTask(Task completedTask, LocalDateTime now) {
        if (completedTask.getDueAt() == null) return;
        LocalDateTime nextDue = switch (completedTask.getRecurrence()) {
            case DAILY   -> completedTask.getDueAt().plusDays(1);
            case WEEKLY  -> completedTask.getDueAt().plusWeeks(1);
            case MONTHLY -> completedTask.getDueAt().plusMonths(1);
            case NONE    -> null;
        };
        if (nextDue == null) return;

        Task next = new Task();
        next.setId(UUID.randomUUID().toString().replace("-", ""));
        next.setUserId(completedTask.getUserId()); next.setChatId(completedTask.getChatId());
        next.setTitle(completedTask.getTitle()); next.setPriority(completedTask.getPriority());
        next.setCategory(completedTask.getCategory()); next.setDueAt(nextDue);
        next.setStatus(Task.Status.ACTIVE); next.setRecurrence(completedTask.getRecurrence());
        next.setStaleAfterDays(completedTask.getStaleAfterDays());
        next.setCreatedAt(now); next.setUpdatedAt(now); next.setReminderStage(0);

        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO tasks (
                         id, user_id, chat_id, title, priority, category, due_at, status, recurrence,
                         stale_after_days, created_at, updated_at, reminder_stage, last_reminder_at, stale_notified_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bindTask(s, next); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to create recurring task", e); }
    }

    private boolean isTaskStale(Task task, LocalDateTime now) {
        if (task.getStatus() != Task.Status.ACTIVE) return false;
        if (task.getDueAt() != null && !task.getDueAt().isAfter(now.plusHours(24))) return false;
        return task.getUpdatedAt().plusDays(task.getStaleAfterDays()).isBefore(now);
    }

    private void ensureCategoryExists(long userId, String category) {
        String normalized = normalizeCategory(category);
        if (normalized.isBlank() || DEFAULT_CATEGORY.equals(normalized)) return;
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO categories (user_id, name, created_at) VALUES (?, ?, ?)")) {
            s.setLong(1, userId); s.setString(2, normalized); s.setString(3, LocalDateTime.now(zoneId).toString());
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to ensure category exists", e); }
    }

    private boolean categoryExists(long userId, String category) {
        if (DEFAULT_CATEGORY.equals(category)) return true;
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM categories WHERE user_id = ? AND name = ? LIMIT 1")) {
            s.setLong(1, userId); s.setString(2, category);
            try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new RuntimeException("Failed to check category", e); }
    }

    private List<Task> queryTasks(String sql, Long userIdOrNull) {
        List<Task> tasks = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (userIdOrNull != null && sql.contains("user_id = ?")) s.setLong(1, userIdOrNull);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) tasks.add(mapTask(rs)); }
        } catch (SQLException e) { throw new RuntimeException("Failed to query tasks", e); }
        return tasks;
    }

    private void updateTaskStatus(String taskId, Task.Status status, LocalDateTime updatedAt) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?")) {
            s.setString(1, status.name()); s.setString(2, updatedAt.toString()); s.setString(3, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to update task status", e); }
    }

    private void updateTask(Task task) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     UPDATE tasks SET
                         title = ?, priority = ?, category = ?, due_at = ?, status = ?, recurrence = ?,
                         stale_after_days = ?, updated_at = ?, reminder_stage = ?, last_reminder_at = ?, stale_notified_at = ?
                     WHERE id = ?
                     """)) {
            s.setString(1, task.getTitle()); s.setString(2, task.getPriority().name());
            s.setString(3, task.getCategory()); s.setString(4, formatDateTime(task.getDueAt()));
            s.setString(5, task.getStatus().name()); s.setString(6, task.getRecurrence().name());
            s.setInt(7, task.getStaleAfterDays()); s.setString(8, task.getUpdatedAt().toString());
            s.setInt(9, task.getReminderStage() == null ? 0 : task.getReminderStage());
            s.setString(10, formatDateTime(task.getLastReminderAt())); s.setString(11, formatDateTime(task.getStaleNotifiedAt()));
            s.setString(12, task.getId()); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to update task", e); }
    }

    private void bindTask(PreparedStatement s, Task task) throws SQLException {
        s.setString(1, task.getId()); s.setLong(2, task.getUserId()); s.setLong(3, task.getChatId());
        s.setString(4, task.getTitle()); s.setString(5, task.getPriority().name());
        s.setString(6, task.getCategory()); s.setString(7, formatDateTime(task.getDueAt()));
        s.setString(8, task.getStatus().name()); s.setString(9, task.getRecurrence().name());
        s.setInt(10, task.getStaleAfterDays()); s.setString(11, task.getCreatedAt().toString());
        s.setString(12, task.getUpdatedAt().toString()); s.setInt(13, task.getReminderStage() == null ? 0 : task.getReminderStage());
        s.setString(14, formatDateTime(task.getLastReminderAt())); s.setString(15, formatDateTime(task.getStaleNotifiedAt()));
    }

    private Task mapTask(ResultSet rs) throws SQLException {
        Task task = new Task();
        task.setId(rs.getString("id")); task.setUserId(rs.getLong("user_id")); task.setChatId(rs.getLong("chat_id"));
        task.setTitle(rs.getString("title")); task.setPriority(Task.Priority.fromText(rs.getString("priority")));
        task.setCategory(rs.getString("category")); task.setDueAt(parseStoredDateTime(rs.getString("due_at")));
        task.setStatus(Task.Status.fromText(rs.getString("status"))); task.setRecurrence(Task.Recurrence.fromText(rs.getString("recurrence")));
        task.setStaleAfterDays(rs.getInt("stale_after_days")); task.setCreatedAt(parseStoredDateTime(rs.getString("created_at")));
        task.setUpdatedAt(parseStoredDateTime(rs.getString("updated_at"))); task.setReminderStage(rs.getInt("reminder_stage"));
        task.setLastReminderAt(parseStoredDateTime(rs.getString("last_reminder_at")));
        task.setStaleNotifiedAt(parseStoredDateTime(rs.getString("stale_notified_at")));
        return task;
    }

    private static String formatDateTime(LocalDateTime v) { return v == null ? null : v.toString(); }
    private static LocalDateTime parseStoredDateTime(String v) { return (v == null || v.isBlank()) ? null : LocalDateTime.parse(v); }
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return DEFAULT_CATEGORY;
        String v = category.trim().toLowerCase(Locale.ROOT);
        return v.isBlank() ? DEFAULT_CATEGORY : v;
    }
    private static String safe(String value) { return value == null || value.isBlank() ? DEFAULT_CATEGORY : value; }

    private LocalDateTime parseDateTime(String input) {
        String value = input.trim(), lower = value.toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now(zoneId);
        try { return LocalDateTime.parse(value, STORE_FORMATTER); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.of(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE), LocalTime.of(9, 0)); } catch (DateTimeParseException ignored) {}
        if (lower.startsWith("today")) {
            String rem = value.length() > 5 ? value.substring(5).trim() : "";
            return LocalDateTime.of(today, rem.isBlank() ? LocalTime.of(9, 0) : parseTime(rem));
        }
        if (lower.startsWith("tomorrow")) {
            String rem = value.length() > 8 ? value.substring(8).trim() : "";
            return LocalDateTime.of(today.plusDays(1), rem.isBlank() ? LocalTime.of(9, 0) : parseTime(rem));
        }
        LocalDate wd = tryParseWeekday(lower, today);
        if (wd != null) {
            String[] parts = value.split("\\s+", 2);
            return LocalDateTime.of(wd, parts.length > 1 ? parseTime(parts[1].trim()) : LocalTime.of(9, 0));
        }
        throw new IllegalArgumentException("Invalid date format.");
    }

    private LocalDate tryParseWeekday(String input, LocalDate today) {
        String day = input.split("\\s+", 2)[0];
        int target = switch (day) {
            case "mon", "monday"                -> 1;
            case "tue", "tues", "tuesday"       -> 2;
            case "wed", "wednesday"             -> 3;
            case "thu", "thur", "thurs", "thursday" -> 4;
            case "fri", "friday"                -> 5;
            case "sat", "saturday"              -> 6;
            case "sun", "sunday"                -> 7;
            default -> -1;
        };
        if (target == -1) return null;
        int ahead = (target - today.getDayOfWeek().getValue() + 7) % 7;
        return today.plusDays(ahead == 0 ? 7 : ahead);
    }

    private LocalTime parseTime(String raw) {
        String value = raw.trim();
        try { return LocalTime.parse(value); } catch (DateTimeParseException ignored) {}
        try { return LocalTime.parse(value.toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("h:mma")); } catch (DateTimeParseException ignored) {}
        try { return LocalTime.parse(value.toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("ha")); } catch (DateTimeParseException ignored) {}
        throw new IllegalArgumentException("Invalid time format.");
    }
}
