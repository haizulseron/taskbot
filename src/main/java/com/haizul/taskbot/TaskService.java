package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

public class TaskService {

    public record AddTaskRequest(String title, Task.Priority priority, String category,
                                 LocalDateTime dueAt, Task.Recurrence recurrence, String notes) {
        public AddTaskRequest(String title, Task.Priority priority, String category,
                              LocalDateTime dueAt, Task.Recurrence recurrence) {
            this(title, priority, category, dueAt, recurrence, null);
        }
    }

    public record ReminderDue(Task task, String label) {}
    public record UserChat(long userId, long chatId) {}

    private static final DateTimeFormatter STORE_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("d MMM, HH:mm");
    private static final DateTimeFormatter TIME_FMT    = DateTimeFormatter.ofPattern("HH:mm");
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
        if (input.isBlank()) throw new IllegalArgumentException("Use /add followed by a task title.");
        return input.contains("|") ? parseLegacy(input) : parseNatural(input);
    }

    private AddTaskRequest parseLegacy(String input) {
        String[] p = input.split("\\|");
        String title = p.length > 0 ? p[0].trim() : "";
        if (title.isBlank()) throw new IllegalArgumentException("Task title cannot be empty.");
        Task.Priority priority = p.length > 1 && !p[1].isBlank() ? Task.Priority.fromText(p[1]) : Task.Priority.MEDIUM;
        String category        = p.length > 2 && !p[2].isBlank() ? normalizeCategory(p[2]) : DEFAULT_CATEGORY;
        LocalDateTime dueAt    = p.length > 3 && !p[3].isBlank() ? parseDateTime(p[3].trim()) : null;
        Task.Recurrence rec    = p.length > 4 && !p[4].isBlank() ? Task.Recurrence.fromText(p[4]) : Task.Recurrence.NONE;
        return new AddTaskRequest(title, priority, category, dueAt, rec);
    }

    private AddTaskRequest parseNatural(String input) {
        List<String> tokens = new ArrayList<>(Arrays.asList(input.split("\\s+")));
        Task.Priority priority = Task.Priority.MEDIUM;
        String category = DEFAULT_CATEGORY;
        Task.Recurrence recurrence = Task.Recurrence.NONE;
        List<String> titleTokens = new ArrayList<>(), dateTokens = new ArrayList<>();

        Set<String> weekdays = Set.of("mon","monday","tue","tues","tuesday","wed","wednesday",
                "thu","thur","thurs","thursday","fri","friday","sat","saturday","sun","sunday");

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i), lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("#") && lower.length() > 1) { category = normalizeCategory(lower.substring(1)); continue; }
            if (lower.equals("!high") || lower.equals("!medium") || lower.equals("!low") || lower.equals("!daily")) { priority = Task.Priority.fromText(lower.substring(1)); continue; }
            if (lower.equals("every") && i + 1 < tokens.size()) {
                String next = tokens.get(i + 1).toLowerCase(Locale.ROOT);
                recurrence = switch (next) {
                    case "day","daily" -> Task.Recurrence.DAILY;
                    case "week","weekly" -> Task.Recurrence.WEEKLY;
                    case "month","monthly" -> Task.Recurrence.MONTHLY;
                    default -> recurrence;
                };
                if (List.of("day","daily","week","weekly","month","monthly").contains(next)) i++;
                else titleTokens.add(token);
                continue;
            }
            if (lower.equals("today") || lower.equals("tomorrow")) {
                dateTokens.add(token);
                if (i + 1 < tokens.size() && looksLikeTime(tokens.get(i + 1))) dateTokens.add(tokens.get(++i));
                continue;
            }
            if (weekdays.contains(lower)) {
                dateTokens.add(token);
                if (i + 1 < tokens.size() && looksLikeTime(tokens.get(i + 1))) dateTokens.add(tokens.get(++i));
                continue;
            }
            if (looksLikeDate(token)) {
                dateTokens.add(token);
                if (i + 1 < tokens.size() && looksLikeTime(tokens.get(i + 1))) dateTokens.add(tokens.get(++i));
                continue;
            }
            titleTokens.add(token);
        }
        String title = String.join(" ", titleTokens).trim();
        if (title.isBlank()) throw new IllegalArgumentException("Task title cannot be empty.");
        LocalDateTime dueAt = dateTokens.isEmpty() ? null : parseDateTime(String.join(" ", dateTokens));
        return new AddTaskRequest(title, priority, category, dueAt, recurrence);
    }

    private boolean looksLikeDate(String t) { return t.toLowerCase(Locale.ROOT).matches("\\d{4}-\\d{2}-\\d{2}"); }
    private boolean looksLikeTime(String t) {
        String v = t.toLowerCase(Locale.ROOT);
        return v.matches("\\d{1,2}:\\d{2}") || v.matches("\\d{1,2}(am|pm)") || v.matches("\\d{1,2}:\\d{2}(am|pm)");
    }

    // ── Task CRUD ────────────────────────────────────────────────────────────

    public Task createTask(long userId, long chatId, AddTaskRequest req) {
        String category = normalizeCategory(req.category());
        ensureCategoryExists(userId, category);
        LocalDateTime now = LocalDateTime.now(zoneId);

        Task task = new Task();
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        task.setUserId(userId); task.setChatId(chatId);
        task.setTitle(req.title().trim()); task.setNotes(req.notes());
        task.setPriority(req.priority()); task.setCategory(category);
        task.setDueAt(req.dueAt()); task.setStatus(Task.Status.ACTIVE);
        task.setRecurrence(req.recurrence()); task.setStaleAfterDays(defaultStaleDays);
        task.setCreatedAt(now); task.setUpdatedAt(now); task.setReminderStage(0);

        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO tasks (id,user_id,chat_id,title,priority,category,due_at,status,recurrence,
                         stale_after_days,created_at,updated_at,reminder_stage,last_reminder_at,stale_notified_at,
                         notes,reminder_interval_minutes,repeat_reminder,is_habit,reminder_ignored_count,
                         reminder_lat,reminder_lng,reminder_radius_meters,
                         google_task_id,google_tasklist_id,google_event_id)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            bindTask(s, task); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to create task", e); }
        return task;
    }

    public Task duplicateTask(long userId, long chatId, String hint, LocalDateTime newDueAt) {
        Optional<Task> original = findTaskByTitleHint(userId, hint);
        if (original.isEmpty()) return null;
        Task orig = original.get();
        AddTaskRequest req = new AddTaskRequest(
                orig.getTitle(), orig.getPriority(), orig.getCategory(),
                newDueAt != null ? newDueAt : orig.getDueAt(),
                orig.getRecurrence(), orig.getNotes());
        return createTask(userId, chatId, req);
    }

    public List<Task> getActiveTasks(long userId) {
        return queryTasks("SELECT * FROM tasks WHERE user_id=? AND status='ACTIVE' ORDER BY CASE priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 ELSE 4 END, due_at IS NULL, due_at, created_at", userId);
    }

    public List<Task> getFilteredTasks(long userId, String priorityFilter, String categoryFilter, String dueRangeFilter) {
        List<Task> tasks = getActiveTasks(userId);
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalDate today = now.toLocalDate();

        if (priorityFilter != null && !priorityFilter.isBlank()) {
            Task.Priority p = Task.Priority.fromText(priorityFilter);
            tasks = tasks.stream().filter(t -> t.getPriority() == p).collect(Collectors.toList());
        }
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            String cat = normalizeCategory(categoryFilter);
            tasks = tasks.stream().filter(t -> cat.equals(t.getCategory())).collect(Collectors.toList());
        }
        if (dueRangeFilter != null) {
            tasks = switch (dueRangeFilter.toLowerCase(Locale.ROOT)) {
                case "today" -> tasks.stream().filter(t -> t.getDueAt() != null && t.getDueAt().toLocalDate().isEqual(today)).collect(Collectors.toList());
                case "this_week" -> tasks.stream().filter(t -> t.getDueAt() != null && !t.getDueAt().toLocalDate().isBefore(today) && t.getDueAt().toLocalDate().isBefore(today.plusDays(7))).collect(Collectors.toList());
                case "overdue" -> tasks.stream().filter(t -> t.getDueAt() != null && t.getDueAt().isBefore(now)).collect(Collectors.toList());
                default -> tasks;
            };
        }
        return tasks;
    }

    public List<Task> getTodayTasks(long userId) {
        LocalDate today = LocalDate.now(zoneId);
        return getActiveTasks(userId).stream().filter(t -> t.getDueAt() != null && t.getDueAt().toLocalDate().isEqual(today)).collect(Collectors.toList());
    }

    public List<Task> getOverdueTasks(long userId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return getActiveTasks(userId).stream().filter(t -> t.getDueAt() != null && t.getDueAt().isBefore(now)).sorted(Comparator.comparing(Task::getDueAt)).collect(Collectors.toList());
    }

    public List<Task> getStaleTasks(long userId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return getActiveTasks(userId).stream().filter(t -> isTaskStale(t, now)).collect(Collectors.toList());
    }

    public List<Task> getDoneTasks(long userId) {
        return queryTasks("SELECT * FROM tasks WHERE user_id=? AND status='DONE' ORDER BY updated_at DESC", userId);
    }

    public List<Task> searchTasks(long userId, String query) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase(Locale.ROOT);
        return queryTasks("SELECT * FROM tasks WHERE user_id=? AND status='ACTIVE'", userId).stream()
                .filter(t -> t.getTitle().toLowerCase(Locale.ROOT).contains(lower)
                        || (t.getNotes() != null && t.getNotes().toLowerCase(Locale.ROOT).contains(lower))
                        || t.getCategory().toLowerCase(Locale.ROOT).contains(lower))
                .collect(Collectors.toList());
    }

    public Optional<Task> findTaskByShortId(long userId, String shortId) {
        if (shortId == null || shortId.isBlank()) return Optional.empty();
        return queryTasks("SELECT * FROM tasks WHERE user_id=?", userId).stream()
                .filter(t -> t.getId().startsWith(shortId.trim())).findFirst();
    }

    public Optional<Task> findTaskByTitleHint(long userId, String hint) {
        if (hint == null || hint.isBlank()) return Optional.empty();
        String lower = hint.toLowerCase(Locale.ROOT);
        List<Task> active = getActiveTasks(userId);

        // 1. Exact substring match (fast path)
        Optional<Task> exact = active.stream()
                .filter(t -> t.getTitle().toLowerCase(Locale.ROOT).contains(lower))
                .findFirst();
        if (exact.isPresent()) return exact;

        // 2. Word-based fuzzy match — handles word-order mismatches and filler words.
        //    e.g. hint "mock cs2030de paper" matches title "CS2030DE Mock Paper"
        //    e.g. hint "graduation gown matters" matches title "Graduation Gown Collection"
        // Drop common filler words from the hint so they don't drag down the threshold.
        Set<String> stop = Set.of("the","a","an","my","your","task","tasks","matter","matters",
                "thing","things","item","items","with","for","of","to","on","and","or","please");
        String[] hintWords = lower.split("\\s+");
        List<String> meaningful = new ArrayList<>();
        for (String w : hintWords) {
            if (w.length() >= 2 && !stop.contains(w)) meaningful.add(w);
        }
        if (meaningful.isEmpty()) meaningful = Arrays.asList(hintWords);

        Task best = null;
        int bestScore = 0;
        for (Task t : active) {
            String titleLower = t.getTitle().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String w : meaningful) {
                if (titleLower.contains(w)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        // Threshold relaxed: short hints (≤3 meaningful words) only need 1 match,
        // longer hints still need ~half. Better to over-match and let user confirm
        // than fail with "task not found" when intent is obvious.
        int n = meaningful.size();
        int minScore = n <= 3 ? 1 : (n + 1) / 2;
        return bestScore >= minScore ? Optional.of(best) : Optional.empty();
    }

    public boolean markDone(long userId, String shortId) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        LocalDateTime now = LocalDateTime.now(zoneId);
        updateTaskStatus(task.getId(), Task.Status.DONE, now);
        if (task.isHabit()) logHabitCompletion(task.getId(), userId);
        if (task.getRecurrence() != Task.Recurrence.NONE) createNextRecurring(task, now);
        return true;
    }

    public int markAllDone(long userId) {
        List<Task> active = getActiveTasks(userId);
        LocalDateTime now = LocalDateTime.now(zoneId);
        for (Task t : active) {
            updateTaskStatus(t.getId(), Task.Status.DONE, now);
            if (t.isHabit()) logHabitCompletion(t.getId(), userId);
            if (t.getRecurrence() != Task.Recurrence.NONE) createNextRecurring(t, now);
        }
        return active.size();
    }

    public int markAllDoneByPriority(long userId, Task.Priority priority) {
        List<Task> targets = getActiveTasks(userId).stream()
                .filter(t -> t.getPriority() == priority)
                .collect(Collectors.toList());
        LocalDateTime now = LocalDateTime.now(zoneId);
        for (Task t : targets) {
            updateTaskStatus(t.getId(), Task.Status.DONE, now);
            if (t.isHabit()) logHabitCompletion(t.getId(), userId);
            if (t.getRecurrence() != Task.Recurrence.NONE) createNextRecurring(t, now);
        }
        return targets.size();
    }

    public boolean deleteTask(long userId, String shortId) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        updateTaskStatus(opt.get().getId(), Task.Status.DELETED, LocalDateTime.now(zoneId));
        return true;
    }

    public int deleteAllDone(long userId) {
        List<Task> done = getDoneTasks(userId);
        LocalDateTime now = LocalDateTime.now(zoneId);
        done.forEach(t -> updateTaskStatus(t.getId(), Task.Status.DELETED, now));
        return done.size();
    }

    public boolean snoozeTask(long userId, String shortId, Duration duration) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        LocalDateTime base = task.getDueAt() != null ? task.getDueAt() : LocalDateTime.now(zoneId);
        task.setDueAt(base.plus(duration));
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        task.setReminderStage(0);
        task.setLastReminderAt(null);
        task.setReminderIgnoredCount(0);
        updateTask(task);
        return true;
    }

    /** Bulk snooze overdue tasks */
    public int bulkSnoozeOverdue(long userId, int hours) {
        List<Task> overdue = getOverdueTasks(userId);
        Duration d = Duration.ofHours(hours);
        for (Task t : overdue) snoozeTask(userId, t.shortId(), d);
        return overdue.size();
    }

    /** Bulk delete stale tasks */
    public int bulkDeleteStale(long userId) {
        List<Task> stale = getStaleTasks(userId);
        LocalDateTime now = LocalDateTime.now(zoneId);
        stale.forEach(t -> updateTaskStatus(t.getId(), Task.Status.DELETED, now));
        return stale.size();
    }

    /** Bulk mark done by category */
    public int bulkMarkDoneByCategory(long userId, String category) {
        List<Task> tasks = getActiveTasks(userId).stream()
                .filter(t -> t.getCategory().equals(normalizeCategory(category)))
                .collect(Collectors.toList());
        LocalDateTime now = LocalDateTime.now(zoneId);
        for (Task t : tasks) {
            updateTaskStatus(t.getId(), Task.Status.DONE, now);
            if (t.isHabit()) logHabitCompletion(t.getId(), userId);
            if (t.getRecurrence() != Task.Recurrence.NONE) createNextRecurring(t, now);
        }
        return tasks.size();
    }

    public boolean restoreTask(String taskId, Task.Status prevStatus, LocalDateTime prevDueAt) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET status=?, due_at=?, updated_at=?, reminder_stage=0 WHERE id=?")) {
            s.setString(1, prevStatus.name());
            s.setString(2, formatDateTime(prevDueAt));
            s.setString(3, LocalDateTime.now(zoneId).toString());
            s.setString(4, taskId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) { throw new RuntimeException("Failed to restore task", e); }
    }

    // ── Task field updates ───────────────────────────────────────────────────

    public boolean updateTaskTitle(long userId, String shortId, String newTitle) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        String t = newTitle == null ? "" : newTitle.trim();
        if (t.isBlank()) throw new IllegalArgumentException("Title cannot be empty.");
        Task task = opt.get(); task.setTitle(t); task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task); return true;
    }

    public boolean updateTaskPriority(long userId, String shortId, String newPriority) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get(); task.setPriority(Task.Priority.fromText(newPriority));
        task.setUpdatedAt(LocalDateTime.now(zoneId)); updateTask(task); return true;
    }

    public boolean updateTaskCategory(long userId, String shortId, String newCategory) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        String category = normalizeCategory(newCategory);
        ensureCategoryExists(userId, category);
        Task task = opt.get(); task.setCategory(category);
        task.setUpdatedAt(LocalDateTime.now(zoneId)); updateTask(task); return true;
    }

    public boolean updateTaskDueAt(long userId, String shortId, String input) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        if (input == null || input.isBlank() || input.equalsIgnoreCase("none") || input.equalsIgnoreCase("clear"))
            task.setDueAt(null);
        else task.setDueAt(parseDateTime(input.trim()));
        task.setUpdatedAt(LocalDateTime.now(zoneId)); task.setReminderStage(0);
        updateTask(task); return true;
    }

    public boolean updateTaskDueAtDirectly(long userId, String shortId, LocalDateTime newDueAt) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get(); task.setDueAt(newDueAt);
        task.setUpdatedAt(LocalDateTime.now(zoneId)); task.setReminderStage(0);
        task.setLastReminderAt(null); updateTask(task); return true;
    }

    public boolean updateTaskRecurrence(long userId, String shortId, String input) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        String v = (input == null ? "" : input.trim()).equalsIgnoreCase("clear") ? "none" : (input == null ? "none" : input.trim());
        task.setRecurrence(Task.Recurrence.fromText(v));
        task.setUpdatedAt(LocalDateTime.now(zoneId)); updateTask(task); return true;
    }

    public boolean updateTaskNotes(long userId, String shortId, String notes) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get(); task.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
        task.setUpdatedAt(LocalDateTime.now(zoneId)); updateTask(task); return true;
    }

    public boolean setReminderInterval(long userId, String shortId, int minutes) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        task.setReminderIntervalMinutes(minutes > 0 ? minutes : null);
        task.setUpdatedAt(LocalDateTime.now(zoneId)); updateTask(task); return true;
    }

    // ── Categories ───────────────────────────────────────────────────────────

    public List<String> getCategories(long userId) {
        List<String> list = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT name FROM categories WHERE user_id=? ORDER BY name")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) list.add(rs.getString("name")); }
        } catch (SQLException e) { throw new RuntimeException("Failed to fetch categories", e); }
        return list;
    }

    public void addCategory(long userId, String name) { ensureCategoryExists(userId, normalizeCategory(name)); }

    public boolean renameCategory(long userId, String oldName, String newName) {
        String oldCat = normalizeCategory(oldName), newCat = normalizeCategory(newName);
        if (DEFAULT_CATEGORY.equals(oldCat)) return false;
        ensureCategoryExists(userId, newCat);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement upd = c.prepareStatement("UPDATE tasks SET category=?,updated_at=? WHERE user_id=? AND category=?");
                 PreparedStatement del = c.prepareStatement("DELETE FROM categories WHERE user_id=? AND name=?")) {
                upd.setString(1, newCat); upd.setString(2, LocalDateTime.now(zoneId).toString()); upd.setLong(3, userId); upd.setString(4, oldCat);
                int updated = upd.executeUpdate();
                del.setLong(1, userId); del.setString(2, oldCat); del.executeUpdate();
                c.commit(); return updated > 0 || categoryExists(userId, newCat);
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        } catch (SQLException e) { throw new RuntimeException("Failed to rename category", e); }
    }

    public boolean deleteCategory(long userId, String categoryName) {
        String cat = normalizeCategory(categoryName);
        if (DEFAULT_CATEGORY.equals(cat)) return false;
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement upd = c.prepareStatement("UPDATE tasks SET category=?,updated_at=? WHERE user_id=? AND category=?");
                 PreparedStatement del = c.prepareStatement("DELETE FROM categories WHERE user_id=? AND name=?")) {
                upd.setString(1, DEFAULT_CATEGORY); upd.setString(2, LocalDateTime.now(zoneId).toString()); upd.setLong(3, userId); upd.setString(4, cat);
                int changed = upd.executeUpdate();
                del.setLong(1, userId); del.setString(2, cat); int deleted = del.executeUpdate();
                c.commit(); return changed > 0 || deleted > 0;
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        } catch (SQLException e) { throw new RuntimeException("Failed to delete category", e); }
    }

    // ── User Settings ────────────────────────────────────────────────────────

    public UserSettings getUserSettings(long userId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM user_settings WHERE user_id=?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    String pomState = null;
                    try { pomState = rs.getString("pomodoro_state"); } catch (Exception ignored) {}
                    return new UserSettings(userId, rs.getString("quiet_start"), rs.getString("quiet_end"), rs.getInt("weekly_digest") == 1, pomState);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to fetch user settings", e); }
        return new UserSettings(userId, null, null, true, null);
    }

    public void saveUserSettings(long userId, String quietStart, String quietEnd) {
        saveUserSettings(userId, quietStart, quietEnd, null);
    }

    public void saveUserSettings(long userId, String quietStart, String quietEnd, String pomodoroState) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO user_settings (user_id, quiet_start, quiet_end, weekly_digest, created_at, updated_at, pomodoro_state)
                     VALUES (?, ?, ?, 1, ?, ?, ?)
                     ON CONFLICT(user_id) DO UPDATE SET quiet_start=excluded.quiet_start, quiet_end=excluded.quiet_end,
                         pomodoro_state=excluded.pomodoro_state, updated_at=excluded.updated_at
                     """)) {
            s.setLong(1, userId); s.setString(2, quietStart); s.setString(3, quietEnd);
            s.setString(4, now.toString()); s.setString(5, now.toString());
            s.setString(6, pomodoroState);
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to save user settings", e); }
    }

    // ── Pinned /tasks message tracking ───────────────────────────────────────

    /** Returns the message ID of the most recently pinned /tasks list, or null if none. */
    public Integer getPinnedTasksMessageId(long userId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT pinned_tasks_message_id FROM user_settings WHERE user_id=?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /** Persist the message ID we just pinned (or null after unpinning). */
    public void setPinnedTasksMessageId(long userId, Integer messageId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO user_settings (user_id, weekly_digest, created_at, updated_at, pinned_tasks_message_id)
                     VALUES (?, 1, ?, ?, ?)
                     ON CONFLICT(user_id) DO UPDATE SET pinned_tasks_message_id=excluded.pinned_tasks_message_id,
                         updated_at=excluded.updated_at
                     """)) {
            s.setLong(1, userId);
            s.setString(2, now.toString());
            s.setString(3, now.toString());
            if (messageId == null) s.setNull(4, java.sql.Types.INTEGER);
            else s.setInt(4, messageId);
            s.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to persist pinned tasks message id: " + e.getMessage());
        }
    }

    // ── Templates ────────────────────────────────────────────────────────────

    public List<Template> getTemplates(long userId) {
        List<Template> list = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM templates WHERE user_id=? ORDER BY name")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Template(
                        rs.getInt("id"), userId, rs.getString("name"), rs.getString("title"),
                        Task.Priority.fromText(rs.getString("priority")), rs.getString("category"),
                        Task.Recurrence.fromText(rs.getString("recurrence")), rs.getString("notes")));
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to fetch templates", e); }
        return list;
    }

    public Optional<Template> findTemplate(long userId, String name) {
        return getTemplates(userId).stream()
                .filter(t -> t.getName().equalsIgnoreCase(name.trim())).findFirst();
    }

    public boolean saveTemplate(long userId, String hint, String templateName) {
        Optional<Task> opt = findTaskByTitleHint(userId, hint);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO templates (user_id, name, title, priority, category, recurrence, notes, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(user_id, name) DO UPDATE SET title=excluded.title, priority=excluded.priority,
                         category=excluded.category, recurrence=excluded.recurrence, notes=excluded.notes
                     """)) {
            s.setLong(1, userId); s.setString(2, templateName.trim().toLowerCase());
            s.setString(3, task.getTitle()); s.setString(4, task.getPriority().name());
            s.setString(5, task.getCategory()); s.setString(6, task.getRecurrence().name());
            s.setString(7, task.getNotes()); s.setString(8, now.toString());
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to save template", e); }
        return true;
    }

    public Task useTemplate(long userId, long chatId, String templateName, LocalDateTime dueAt) {
        Optional<Template> opt = findTemplate(userId, templateName);
        if (opt.isEmpty()) return null;
        Template tmpl = opt.get();
        AddTaskRequest req = new AddTaskRequest(tmpl.getTitle(), tmpl.getPriority(), tmpl.getCategory(), dueAt, tmpl.getRecurrence(), tmpl.getNotes());
        return createTask(userId, chatId, req);
    }

    public boolean deleteTemplate(long userId, String name) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM templates WHERE user_id=? AND name=?")) {
            s.setLong(1, userId); s.setString(2, name.trim().toLowerCase());
            return s.executeUpdate() > 0;
        } catch (SQLException e) { throw new RuntimeException("Failed to delete template", e); }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Plain-text single task (used by ClaudeService tool results for Claude's analysis). */
    public String formatTask(Task task) {
        String dot  = switch (task.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; case DAILY -> "🔵"; };
        String cat  = "none".equals(task.getCategory()) ? "" : "  📁 " + task.getCategory();
        String due  = task.getDueAt() == null ? "" : "  📅 " + friendlyDate(task.getDueAt());
        String rec  = task.getRecurrence() == Task.Recurrence.NONE ? "" : "  🔁 " + capitalize(task.getRecurrence().name());
        String notes = task.getNotes() != null ? "\n📝 " + task.getNotes() : "";
        String interval = task.getReminderIntervalMinutes() != null ? "  ⏱ every " + task.getReminderIntervalMinutes() + "min" : "";
        String habit = task.isHabit() ? "  🔄 Habit" : "";
        String location = task.hasLocationReminder() ? "  📍 Location reminder" : "";
        return dot + " " + task.getTitle()
                + "\n" + cat + due + rec + interval + habit + location
                + notes
                + "\nID: " + task.shortId();
    }

    /** HTML single task detail (used in /edittasks with action buttons). */
    public String formatTaskHtml(Task task) {
        String dot  = dot(task);
        String cat  = "none".equals(task.getCategory()) ? "" : "  📁 " + esc(task.getCategory());
        String due  = task.getDueAt() == null ? "" : "  📅 " + friendlyDate(task.getDueAt());
        String rec  = task.getRecurrence() == Task.Recurrence.NONE ? "" : "  🔁 " + capitalize(task.getRecurrence().name());
        String notes = task.getNotes() != null ? "\n📝 " + esc(task.getNotes()) : "";
        String interval = task.getReminderIntervalMinutes() != null ? "  ⏱ every " + task.getReminderIntervalMinutes() + "min" : "";
        String habit = task.isHabit() ? "  🔄" : "";
        String location = task.hasLocationReminder() ? "  📍" : "";
        return dot + " <b>" + esc(task.getTitle()) + "</b>"
                + "\n" + cat + due + rec + interval + habit + location
                + notes
                + "\n<code>" + task.shortId() + "</code>";
    }

    /**
     * Compact HTML task list — one line per task.
     * Format: 🔴 <b>Title</b>  📅 Due
     */
    public String formatTaskListHtml(String title, List<Task> tasks) {
        if (tasks.isEmpty()) return title + "\n\nNo tasks found.";

        List<Task> main  = tasks.stream().filter(t -> t.getPriority() != Task.Priority.DAILY).toList();
        List<Task> daily = tasks.stream().filter(t -> t.getPriority() == Task.Priority.DAILY).toList();

        long high = main.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        long med  = main.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
        long low  = main.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(esc(title)).append("</b>  (").append(tasks.size()).append(")\n");
        if (high > 0) sb.append("🔴 ").append(high).append("  ");
        if (med  > 0) sb.append("🟡 ").append(med).append("  ");
        if (low  > 0) sb.append("🟢 ").append(low);
        if (!daily.isEmpty()) sb.append("  🔵 ").append(daily.size());
        sb.append("\n─────────────────\n");

        for (Task t : main) {
            sb.append(dot(t)).append(" <b>").append(esc(t.getTitle())).append("</b>");
            if (t.getDueAt() != null) sb.append("  📅 ").append(friendlyDate(t.getDueAt()));
            if (t.getCategory() != null && !"none".equals(t.getCategory())) sb.append("  [").append(esc(t.getCategory())).append("]");
            if (t.isHabit()) sb.append("  🔄");
            sb.append("\n");
        }

        if (!daily.isEmpty()) {
            sb.append("─────────────────\n");
            for (Task t : daily) {
                sb.append("🔵 <b>").append(esc(t.getTitle())).append("</b>");
                if (t.getDueAt() != null) sb.append("  📅 ").append(friendlyDate(t.getDueAt()));
                if (t.getCategory() != null && !"none".equals(t.getCategory()) && !"daily".equals(t.getCategory())) sb.append("  [").append(esc(t.getCategory())).append("]");
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    public String friendlyDate(LocalDateTime dt) {
        if (dt == null) return "—";
        LocalDate today = LocalDate.now(zoneId), tomorrow = today.plusDays(1), date = dt.toLocalDate();
        String time = dt.format(TIME_FMT);
        if (date.isEqual(today))    return "Today, " + time;
        if (date.isEqual(tomorrow)) return "Tomorrow, " + time;
        return dt.format(DISPLAY_FMT);
    }

    public String getReviewSummary(long userId) {
        List<Task> active  = getActiveTasks(userId);
        List<Task> today   = getTodayTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);
        List<Task> stale   = getStaleTasks(userId);
        List<Task> done    = getDoneTasks(userId);

        long high  = active.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        long med   = active.stream().filter(t -> t.getPriority() == Task.Priority.MEDIUM).count();
        long low   = active.stream().filter(t -> t.getPriority() == Task.Priority.LOW).count();
        long daily = active.stream().filter(t -> t.getPriority() == Task.Priority.DAILY).count();

        // Productivity score: done this week / (done + active this week)
        LocalDateTime weekAgo = LocalDateTime.now(zoneId).minusDays(7);
        long doneThisWeek    = done.stream().filter(t -> t.getUpdatedAt() != null && t.getUpdatedAt().isAfter(weekAgo)).count();
        long createdThisWeek = active.stream().filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(weekAgo)).count();
        long total = doneThisWeek + createdThisWeek;
        String score = total > 0 ? Math.round((doneThisWeek * 100.0) / total) + "%" : "N/A";

        // Category breakdown
        Map<String, Long> byCategory = active.stream()
                .collect(Collectors.groupingBy(t -> t.getCategory() == null ? "none" : t.getCategory(), Collectors.counting()));
        StringBuilder catBreakdown = new StringBuilder();
        byCategory.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> catBreakdown.append("  📁 ").append(esc(e.getKey())).append(": ").append(e.getValue()).append("\n"));

        // Habit streaks
        List<Task> habits = getHabits(userId);
        StringBuilder habitSection = new StringBuilder();
        if (!habits.isEmpty()) {
            habitSection.append("─────────────────\n<b>🔄 Habits:</b>\n");
            habits.forEach(h -> {
                int streak = getHabitStreak(h.getId());
                habitSection.append("  ").append(esc(h.getTitle())).append(": <b>").append(streak).append("</b> day streak\n");
            });
        }

        return "<b>📊 Review</b>\n"
                + "─────────────────\n"
                + "Active:     <b>" + active.size() + "</b> task(s)\n"
                + "  🔴 High   " + high + "\n"
                + "  🟡 Medium " + med + "\n"
                + "  🟢 Low    " + low + "\n"
                + "  🔵 Daily  " + daily + "\n"
                + "─────────────────\n"
                + "📅 Due today:  <b>" + today.size() + "</b>\n"
                + "⚠️ Overdue:   <b>" + overdue.size() + "</b>\n"
                + "🧊 Stale:     " + stale.size() + "\n"
                + "✅ Done:      " + done.size() + "\n"
                + "─────────────────\n"
                + "📈 This week: <b>" + score + "</b> completion rate\n"
                + (catBreakdown.length() > 0 ? "─────────────────\n<b>By category:</b>\n" + catBreakdown : "")
                + habitSection;
    }

    public String buildMorningSummary(long userId) {
        List<Task> today   = getTodayTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);
        List<Task> stale   = getStaleTasks(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("<b>☀️ Good morning!</b>\n─────────────────\n")
          .append("📅 Due today: <b>").append(today.size()).append("</b>\n")
          .append("⚠️ Overdue:  <b>").append(overdue.size()).append("</b>\n")
          .append("🧊 Stale:    <b>").append(stale.size()).append("</b>\n");
        if (!today.isEmpty()) {
            sb.append("\n<b>Due today:</b>\n");
            today.forEach(t -> sb.append("  ").append(dot(t)).append(" <b>").append(esc(t.getTitle())).append("</b>")
                    .append(t.getDueAt() != null ? "  " + t.getDueAt().format(TIME_FMT) : "").append("\n"));
        }
        if (!overdue.isEmpty()) {
            sb.append("\n<b>Overdue:</b>\n");
            overdue.forEach(t -> sb.append("  ").append(dot(t)).append(" <b>").append(esc(t.getTitle())).append("</b>\n"));
        }
        return sb.toString().trim();
    }

    public String buildWeeklyDigest(long userId) {
        LocalDateTime weekAgo = LocalDateTime.now(zoneId).minusDays(7);
        List<Task> done   = getDoneTasks(userId).stream().filter(t -> t.getUpdatedAt() != null && t.getUpdatedAt().isAfter(weekAgo)).collect(Collectors.toList());
        List<Task> active = getActiveTasks(userId);
        List<Task> overdue = getOverdueTasks(userId);

        long total = done.size() + active.size();
        String score = total > 0 ? Math.round((done.size() * 100.0) / total) + "%" : "N/A";

        StringBuilder sb = new StringBuilder();
        sb.append("<b>📆 Weekly Digest</b>\n─────────────────\n")
          .append("✅ Completed: <b>").append(done.size()).append("</b>\n")
          .append("📋 Active:    <b>").append(active.size()).append("</b>\n")
          .append("⚠️ Overdue:   <b>").append(overdue.size()).append("</b>\n")
          .append("📈 Rate:      <b>").append(score).append("</b>\n");

        if (!done.isEmpty()) {
            sb.append("\n<b>Completed:</b>\n");
            done.stream().limit(10).forEach(t -> sb.append("  ✅ ").append(esc(t.getTitle())).append("\n"));
            if (done.size() > 10) sb.append("  … and ").append(done.size() - 10).append(" more\n");
        }
        return sb.toString().trim();
    }

    public String dot(Task t) { return switch (t.getPriority()) { case HIGH -> "🔴"; case MEDIUM -> "🟡"; case LOW -> "🟢"; case DAILY -> "🔵"; }; }

    public static String usageText() {
        return """
                <b>Available commands:</b>
                /start · /help · /add · /tasks · /today
                /overdue · /stale · /doneitems · /cleardone
                /review · /habits · /categories · /addcategory
                /done · /delete · /snooze · /search · /templates
                /recentnotes · /cancel

                💡 <b>Or just talk naturally:</b>
                "show my high priority school tasks"
                "move gym to tomorrow 9am"
                "remind me about report every 30 minutes"
                "no reminders after 10pm"
                "mark gym as a habit"
                "start a 25 min focus session for CEE report"
                "change gym task to high priority"
                "save gym as a template called workout"
                "undo"
                """;
    }

    // ── Scheduler support ────────────────────────────────────────────────────

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
        List<Task> active = queryTasks("SELECT * FROM tasks WHERE status='ACTIVE' AND due_at IS NOT NULL", null);
        List<ReminderDue> due = new ArrayList<>();

        for (Task task : active) {
            long minsUntilDue = Duration.between(now, task.getDueAt()).toMinutes();

            // Only start reminding when within the natural reminder window based on priority:
            // HIGH: remind from 24h out, MEDIUM/DAILY: 6h out, LOW: 2h out
            long windowMins = switch (task.getPriority()) {
                case HIGH   -> 1440; // 24h
                case MEDIUM -> 360;  // 6h
                case DAILY  -> 360;  // 6h
                case LOW    -> 120;  // 2h
            };
            // If a custom interval is set, use that as the window too
            if (task.getReminderIntervalMinutes() != null) {
                windowMins = Math.max(windowMins, task.getReminderIntervalMinutes() * 3L);
            }
            if (minsUntilDue > windowMins) continue;
            if (minsUntilDue < -60 && !task.isRepeatReminder()) continue;

            // How often to repeat the reminder once inside the window
            int intervalMins = task.getReminderIntervalMinutes() != null && task.getReminderIntervalMinutes() > 0
                    ? task.getReminderIntervalMinutes()
                    : switch (task.getPriority()) {
                        case HIGH   -> 60;
                        case MEDIUM -> 120;
                        case DAILY  -> 120;
                        case LOW    -> 60;
                    };

            LocalDateTime lastReminded = task.getLastReminderAt();
            // Never reminded yet — send first reminder now that we're in the window
            if (lastReminded == null) {
                String label = minsUntilDue < 0 ? "Overdue"
                        : minsUntilDue <= 30  ? "Due very soon (" + minsUntilDue + " min)"
                        : minsUntilDue <= 120 ? "Due within 2 hours"
                        : "Due today";
                due.add(new ReminderDue(task, label));
                continue;
            }

            long minsSinceLast = Duration.between(lastReminded, now).toMinutes();
            if (minsSinceLast >= intervalMins) {
                String label = minsUntilDue < 0 ? "Overdue"
                        : minsUntilDue <= 30  ? "Due very soon (" + minsUntilDue + " min)"
                        : minsUntilDue <= 120 ? "Due within 2 hours"
                        : "Due today";
                due.add(new ReminderDue(task, label));
            }
        }
        return due;
    }

    public void markReminderSent(String taskId, int stage) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET reminder_stage=?, last_reminder_at=? WHERE id=?")) {
            s.setInt(1, stage); s.setString(2, now.toString()); s.setString(3, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to mark reminder sent", e); }
    }

    public void updateLastReminderAt(String taskId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET last_reminder_at=? WHERE id=?")) {
            s.setString(1, LocalDateTime.now(zoneId).toString()); s.setString(2, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to update last_reminder_at", e); }
    }

    public List<Task> findStaleTasksNeedingPing() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return queryTasks("SELECT * FROM tasks WHERE status='ACTIVE'", null).stream()
                .filter(t -> isTaskStale(t, now))
                .filter(t -> t.getStaleNotifiedAt() == null || t.getStaleNotifiedAt().toLocalDate().isBefore(now.toLocalDate()))
                .collect(Collectors.toList());
    }

    public void markStalePinged(String taskId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET stale_notified_at=? WHERE id=?")) {
            s.setString(1, LocalDateTime.now(zoneId).toString()); s.setString(2, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to mark stale pinged", e); }
    }

    // ── Habit tracking ───────────────────────────────────────────────────────

    public boolean toggleHabit(long userId, String shortId, boolean enable) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        task.setHabit(enable);
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    public List<Task> getHabits(long userId) {
        return getActiveTasks(userId).stream().filter(Task::isHabit).collect(Collectors.toList());
    }

    public void logHabitCompletion(String taskId, long userId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO habit_logs (task_id, user_id, completed_at) VALUES (?, ?, ?)")) {
            s.setString(1, taskId); s.setLong(2, userId);
            s.setString(3, LocalDateTime.now(zoneId).toString());
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to log habit", e); }
    }

    /** Returns the current consecutive-day streak for a habit task. */
    public int getHabitStreak(String taskId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT completed_at FROM habit_logs WHERE task_id=? ORDER BY completed_at DESC")) {
            s.setString(1, taskId);
            try (ResultSet rs = s.executeQuery()) {
                int streak = 0;
                LocalDate expected = LocalDate.now(zoneId);
                while (rs.next()) {
                    LocalDate date = LocalDateTime.parse(rs.getString("completed_at")).toLocalDate();
                    if (date.isEqual(expected) || date.isEqual(expected.minusDays(1))) {
                        streak++; expected = date.minusDays(1);
                    } else break;
                }
                return streak;
            }
        } catch (SQLException e) { return 0; }
    }

    // ── Focus sessions ───────────────────────────────────────────────────────

    public FocusSession startFocusSession(long userId, long chatId, String taskTitle, int durationMinutes) {
        // Cancel any existing session first
        stopFocusSession(userId);
        LocalDateTime now    = LocalDateTime.now(zoneId);
        LocalDateTime endsAt = now.plusMinutes(durationMinutes);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO focus_sessions (user_id,chat_id,task_title,duration_minutes,started_at,ends_at,completed,notified) VALUES (?,?,?,?,?,?,0,0)")) {
            s.setLong(1, userId); s.setLong(2, chatId);
            s.setString(3, taskTitle); s.setInt(4, durationMinutes);
            s.setString(5, now.toString()); s.setString(6, endsAt.toString());
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to start focus session", e); }
        return getActiveFocusSession(userId);
    }

    public boolean stopFocusSession(long userId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE focus_sessions SET completed=1, notified=1 WHERE user_id=? AND completed=0")) {
            s.setLong(1, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) { throw new RuntimeException("Failed to stop focus session", e); }
    }

    public FocusSession getActiveFocusSession(long userId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT * FROM focus_sessions WHERE user_id=? AND completed=0 ORDER BY started_at DESC LIMIT 1")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) return mapFocusSession(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to get focus session", e); }
        return null;
    }

    public List<FocusSession> findUnnotifiedCompletedSessions() {
        List<FocusSession> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT * FROM focus_sessions WHERE completed=0 AND notified=0 AND ends_at <= ?")) {
            s.setString(1, now.toString());
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) list.add(mapFocusSession(rs)); }
        } catch (SQLException e) { throw new RuntimeException("Failed to find completed sessions", e); }
        return list;
    }

    public void markFocusSessionNotified(int sessionId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE focus_sessions SET completed=1, notified=1 WHERE id=?")) {
            s.setInt(1, sessionId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to mark session notified", e); }
    }

    private FocusSession mapFocusSession(ResultSet rs) throws SQLException {
        return new FocusSession(
                rs.getInt("id"), rs.getLong("user_id"), rs.getLong("chat_id"),
                rs.getString("task_title"), rs.getInt("duration_minutes"),
                LocalDateTime.parse(rs.getString("started_at")),
                LocalDateTime.parse(rs.getString("ends_at")),
                rs.getInt("completed") == 1, rs.getInt("notified") == 1);
    }

    // ── Location reminders ───────────────────────────────────────────────────

    public boolean setLocationReminder(long userId, String shortId, double lat, double lng, int radiusMeters) {
        Optional<Task> opt = findTaskByShortId(userId, shortId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        task.setReminderLat(lat); task.setReminderLng(lng);
        task.setReminderRadiusMeters(radiusMeters);
        task.setUpdatedAt(LocalDateTime.now(zoneId));
        updateTask(task);
        return true;
    }

    /** Returns tasks whose location reminder is triggered by the given coordinates. */
    public List<Task> checkLocationTriggers(long userId, double lat, double lng) {
        return getActiveTasks(userId).stream()
                .filter(Task::hasLocationReminder)
                .filter(t -> {
                    int radius = t.getReminderRadiusMeters() != null ? t.getReminderRadiusMeters() : 200;
                    return t.distanceMetersTo(lat, lng) <= radius;
                })
                .collect(Collectors.toList());
    }

    // ── Natural language field edit dispatch ─────────────────────────────────

    /** Dispatches a field edit by name, used by the edit_task intent. Returns false if task not found or field unknown. */
    public boolean editTaskField(long userId, String hint, String field, String value) {
        Optional<Task> opt = findTaskByTitleHint(userId, hint);
        if (opt.isEmpty()) return false;
        String shortId = opt.get().shortId();
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "title"      -> updateTaskTitle(userId, shortId, value);
            case "priority"   -> updateTaskPriority(userId, shortId, value);
            case "category"   -> updateTaskCategory(userId, shortId, value);
            case "due", "due_date" -> updateTaskDueAt(userId, shortId, value);
            case "recurrence" -> updateTaskRecurrence(userId, shortId, value);
            case "notes"      -> updateTaskNotes(userId, shortId, value);
            default -> false;
        };
    }

    // ── Escalating reminders ─────────────────────────────────────────────────

    public void incrementReminderIgnoredCount(String taskId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE tasks SET reminder_ignored_count = reminder_ignored_count + 1 WHERE id=?")) {
            s.setString(1, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to increment ignored count", e); }
    }

    public void resetReminderIgnoredCount(String taskId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE tasks SET reminder_ignored_count = 0 WHERE id=?")) {
            s.setString(1, taskId); s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to reset ignored count", e); }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void createNextRecurring(Task completed, LocalDateTime now) {
        if (completed.getDueAt() == null) return;
        LocalDateTime nextDue = switch (completed.getRecurrence()) {
            case DAILY   -> completed.getDueAt().plusDays(1);
            case WEEKLY  -> completed.getDueAt().plusWeeks(1);
            case MONTHLY -> completed.getDueAt().plusMonths(1);
            case NONE    -> null;
        };
        if (nextDue == null) return;
        AddTaskRequest req = new AddTaskRequest(completed.getTitle(), completed.getPriority(),
                completed.getCategory(), nextDue, completed.getRecurrence(), completed.getNotes());
        Task next = createTask(completed.getUserId(), completed.getChatId(), req);
        next.setReminderIntervalMinutes(completed.getReminderIntervalMinutes());
        next.setRepeatReminder(completed.isRepeatReminder());
        next.setHabit(completed.isHabit());
        next.setReminderLat(completed.getReminderLat());
        next.setReminderLng(completed.getReminderLng());
        next.setReminderRadiusMeters(completed.getReminderRadiusMeters());
        updateTask(next);
    }

    private boolean isTaskStale(Task task, LocalDateTime now) {
        if (task.getStatus() != Task.Status.ACTIVE) return false;
        if (task.getDueAt() != null && !task.getDueAt().isAfter(now.plusHours(24))) return false;
        return task.getUpdatedAt().plusDays(task.getStaleAfterDays()).isBefore(now);
    }

    private void ensureCategoryExists(long userId, String category) {
        String norm = normalizeCategory(category);
        if (norm.isBlank() || DEFAULT_CATEGORY.equals(norm)) return;
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO categories (user_id,name,created_at) VALUES (?,?,?)")) {
            s.setLong(1, userId); s.setString(2, norm); s.setString(3, LocalDateTime.now(zoneId).toString());
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to ensure category", e); }
    }

    private boolean categoryExists(long userId, String category) {
        if (DEFAULT_CATEGORY.equals(category)) return true;
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM categories WHERE user_id=? AND name=? LIMIT 1")) {
            s.setLong(1, userId); s.setString(2, category);
            try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new RuntimeException("Failed to check category", e); }
    }

    private List<Task> queryTasks(String sql, Long userId) {
        List<Task> tasks = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (userId != null && sql.contains("user_id=?")) s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) tasks.add(mapTask(rs)); }
        } catch (SQLException e) { throw new RuntimeException("Failed to query tasks", e); }
        return tasks;
    }

    private void updateTaskStatus(String taskId, Task.Status status, LocalDateTime updatedAt) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET status=?,updated_at=? WHERE id=?")) {
            s.setString(1, status.name()); s.setString(2, updatedAt.toString()); s.setString(3, taskId);
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to update task status", e); }
    }

    private void updateTask(Task task) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     UPDATE tasks SET title=?,priority=?,category=?,due_at=?,status=?,recurrence=?,
                         stale_after_days=?,updated_at=?,reminder_stage=?,last_reminder_at=?,stale_notified_at=?,
                         notes=?,reminder_interval_minutes=?,repeat_reminder=?,is_habit=?,reminder_ignored_count=?,
                         reminder_lat=?,reminder_lng=?,reminder_radius_meters=?,
                         google_task_id=?,google_tasklist_id=?,google_event_id=?
                     WHERE id=?
                     """)) {
            s.setString(1, task.getTitle()); s.setString(2, task.getPriority().name());
            s.setString(3, task.getCategory()); s.setString(4, formatDateTime(task.getDueAt()));
            s.setString(5, task.getStatus().name()); s.setString(6, task.getRecurrence().name());
            s.setInt(7, task.getStaleAfterDays()); s.setString(8, task.getUpdatedAt().toString());
            s.setInt(9, task.getReminderStage() == null ? 0 : task.getReminderStage());
            s.setString(10, formatDateTime(task.getLastReminderAt()));
            s.setString(11, formatDateTime(task.getStaleNotifiedAt()));
            s.setString(12, task.getNotes());
            if (task.getReminderIntervalMinutes() != null) s.setInt(13, task.getReminderIntervalMinutes());
            else s.setNull(13, java.sql.Types.INTEGER);
            s.setInt(14, task.isRepeatReminder() ? 1 : 0);
            s.setInt(15, task.isHabit() ? 1 : 0);
            s.setInt(16, task.getReminderIgnoredCount());
            if (task.getReminderLat() != null) s.setDouble(17, task.getReminderLat());
            else s.setNull(17, java.sql.Types.REAL);
            if (task.getReminderLng() != null) s.setDouble(18, task.getReminderLng());
            else s.setNull(18, java.sql.Types.REAL);
            if (task.getReminderRadiusMeters() != null) s.setInt(19, task.getReminderRadiusMeters());
            else s.setNull(19, java.sql.Types.INTEGER);
            s.setString(20, task.getGoogleTaskId());
            s.setString(21, task.getGoogleTasklistId());
            s.setString(22, task.getGoogleEventId());
            s.setString(23, task.getId());
            s.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to update task", e); }
    }

    private void bindTask(PreparedStatement s, Task task) throws SQLException {
        s.setString(1, task.getId()); s.setLong(2, task.getUserId()); s.setLong(3, task.getChatId());
        s.setString(4, task.getTitle()); s.setString(5, task.getPriority().name());
        s.setString(6, task.getCategory()); s.setString(7, formatDateTime(task.getDueAt()));
        s.setString(8, task.getStatus().name()); s.setString(9, task.getRecurrence().name());
        s.setInt(10, task.getStaleAfterDays()); s.setString(11, task.getCreatedAt().toString());
        s.setString(12, task.getUpdatedAt().toString());
        s.setInt(13, task.getReminderStage() == null ? 0 : task.getReminderStage());
        s.setString(14, formatDateTime(task.getLastReminderAt()));
        s.setString(15, formatDateTime(task.getStaleNotifiedAt()));
        s.setString(16, task.getNotes());
        if (task.getReminderIntervalMinutes() != null) s.setInt(17, task.getReminderIntervalMinutes());
        else s.setNull(17, java.sql.Types.INTEGER);
        s.setInt(18, task.isRepeatReminder() ? 1 : 0);
        s.setInt(19, task.isHabit() ? 1 : 0);
        s.setInt(20, task.getReminderIgnoredCount());
        if (task.getReminderLat() != null) s.setDouble(21, task.getReminderLat());
        else s.setNull(21, java.sql.Types.REAL);
        if (task.getReminderLng() != null) s.setDouble(22, task.getReminderLng());
        else s.setNull(22, java.sql.Types.REAL);
        if (task.getReminderRadiusMeters() != null) s.setInt(23, task.getReminderRadiusMeters());
        else s.setNull(23, java.sql.Types.INTEGER);
        s.setString(24, task.getGoogleTaskId());
        s.setString(25, task.getGoogleTasklistId());
        s.setString(26, task.getGoogleEventId());
    }

    private Task mapTask(ResultSet rs) throws SQLException {
        Task task = new Task();
        task.setId(rs.getString("id")); task.setUserId(rs.getLong("user_id")); task.setChatId(rs.getLong("chat_id"));
        task.setTitle(rs.getString("title")); task.setPriority(Task.Priority.fromText(rs.getString("priority")));
        task.setCategory(rs.getString("category")); task.setDueAt(parseDt(rs.getString("due_at")));
        task.setStatus(Task.Status.fromText(rs.getString("status"))); task.setRecurrence(Task.Recurrence.fromText(rs.getString("recurrence")));
        task.setStaleAfterDays(rs.getInt("stale_after_days")); task.setCreatedAt(parseDt(rs.getString("created_at")));
        task.setUpdatedAt(parseDt(rs.getString("updated_at"))); task.setReminderStage(rs.getInt("reminder_stage"));
        task.setLastReminderAt(parseDt(rs.getString("last_reminder_at")));
        task.setStaleNotifiedAt(parseDt(rs.getString("stale_notified_at")));
        try { task.setNotes(rs.getString("notes")); } catch (SQLException ignored) {}
        try { int rim = rs.getInt("reminder_interval_minutes"); task.setReminderIntervalMinutes(rs.wasNull() ? null : rim); } catch (SQLException ignored) {}
        try { task.setRepeatReminder(rs.getInt("repeat_reminder") == 1); } catch (SQLException ignored) {}
        try { task.setHabit(rs.getInt("is_habit") == 1); } catch (SQLException ignored) {}
        try { task.setReminderIgnoredCount(rs.getInt("reminder_ignored_count")); } catch (SQLException ignored) {}
        try { double lat = rs.getDouble("reminder_lat"); if (!rs.wasNull()) task.setReminderLat(lat); } catch (SQLException ignored) {}
        try { double lng = rs.getDouble("reminder_lng"); if (!rs.wasNull()) task.setReminderLng(lng); } catch (SQLException ignored) {}
        try { int rad = rs.getInt("reminder_radius_meters"); if (!rs.wasNull()) task.setReminderRadiusMeters(rad); } catch (SQLException ignored) {}
        try { task.setGoogleTaskId(rs.getString("google_task_id")); } catch (SQLException ignored) {}
        try { task.setGoogleTasklistId(rs.getString("google_tasklist_id")); } catch (SQLException ignored) {}
        try { task.setGoogleEventId(rs.getString("google_event_id")); } catch (SQLException ignored) {}
        return task;
    }

    private static String formatDateTime(LocalDateTime v) { return v == null ? null : v.toString(); }
    private static LocalDateTime parseDt(String v) { return (v == null || v.isBlank()) ? null : LocalDateTime.parse(v); }
    private static String normalizeCategory(String c) {
        if (c == null || c.isBlank()) return DEFAULT_CATEGORY;
        String v = c.trim().toLowerCase(Locale.ROOT);
        return v.isBlank() ? DEFAULT_CATEGORY : v;
    }
    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // ── Google sync helpers ────────────────────────────────────────────────

    public void setGoogleTaskId(String taskId, String googleTaskId, String googleTasklistId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET google_task_id=?, google_tasklist_id=? WHERE id=?")) {
            s.setString(1, googleTaskId); s.setString(2, googleTasklistId); s.setString(3, taskId);
            s.executeUpdate();
        } catch (SQLException e) { System.err.println("Failed to set google_task_id: " + e.getMessage()); }
    }

    public void setGoogleEventId(String taskId, String googleEventId) {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE tasks SET google_event_id=? WHERE id=?")) {
            s.setString(1, googleEventId); s.setString(2, taskId);
            s.executeUpdate();
        } catch (SQLException e) { System.err.println("Failed to set google_event_id: " + e.getMessage()); }
    }

    public Optional<Task> findByGoogleTaskId(long userId, String googleTaskId) {
        return getActiveTasks(userId).stream()
                .filter(t -> googleTaskId.equals(t.getGoogleTaskId()))
                .findFirst();
    }

    public Optional<Task> findByGoogleEventId(long userId, String googleEventId) {
        return queryTasks("SELECT * FROM tasks WHERE user_id=?", userId).stream()
                .filter(t -> googleEventId.equals(t.getGoogleEventId()))
                .findFirst();
    }

    public Set<String> getAllGoogleEventIds(long userId) {
        Set<String> ids = new java.util.HashSet<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT google_event_id FROM tasks WHERE user_id=? AND google_event_id IS NOT NULL")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) ids.add(rs.getString("google_event_id")); }
        } catch (SQLException e) { System.err.println("Failed to get google event ids: " + e.getMessage()); }
        return ids;
    }

    /** Get top N tasks sorted by priority then due date (for time blocking suggestions). */
    public List<Task> getTopPendingTasks(long userId, int limit) {
        return getActiveTasks(userId).stream()
                .filter(t -> t.getPriority() != Task.Priority.DAILY)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public LocalDateTime parseDateTime(String input) {
        String value = input.trim(), lower = value.toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now(zoneId);
        try { return LocalDateTime.parse(value, STORE_FMT); } catch (DateTimeParseException ignored) {}
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
            case "mon","monday" -> 1; case "tue","tues","tuesday" -> 2;
            case "wed","wednesday" -> 3; case "thu","thur","thurs","thursday" -> 4;
            case "fri","friday" -> 5; case "sat","saturday" -> 6; case "sun","sunday" -> 7;
            default -> -1;
        };
        if (target == -1) return null;
        int ahead = (target - today.getDayOfWeek().getValue() + 7) % 7;
        return today.plusDays(ahead == 0 ? 7 : ahead);
    }

    private LocalTime parseTime(String raw) {
        String v = raw.trim();
        try { return LocalTime.parse(v); } catch (DateTimeParseException ignored) {}
        try { return LocalTime.parse(v.toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("h:mma")); } catch (DateTimeParseException ignored) {}
        try { return LocalTime.parse(v.toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("ha")); } catch (DateTimeParseException ignored) {}
        throw new IllegalArgumentException("Invalid time format.");
    }
}
