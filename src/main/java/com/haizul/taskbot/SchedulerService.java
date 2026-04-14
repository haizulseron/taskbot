package com.haizul.taskbot;

import java.time.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SchedulerService {
    private final TaskService taskService;
    private final TaskBot taskBot;
    private final ClaudeService claudeService;
    private MoodService moodService;
    private CountdownService countdownService;
    private GoalService goalService;
    private GoogleCalendarService googleCalendarService;
    private GoogleTasksService googleTasksService;
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final int intervalSeconds;
    private final LocalTime morningBriefTime;
    private final LocalTime moodCheckinTime;
    private final long allowedUserId;
    private final ScheduledExecutorService executor;
    private final Set<String> sentMorningKeys      = ConcurrentHashMap.newKeySet();
    private final Set<String> sentWeeklyKeys       = ConcurrentHashMap.newKeySet();
    private final Set<String> sentConsolidateKeys  = ConcurrentHashMap.newKeySet();

    public SchedulerService(TaskService taskService, TaskBot taskBot, ClaudeService claudeService,
                            ZoneId zoneId, LocalTime morningSummaryTime, int intervalSeconds,
                            LocalTime morningBriefTime, LocalTime moodCheckinTime, long allowedUserId) {
        this.taskService        = taskService;
        this.taskBot            = taskBot;
        this.claudeService      = claudeService;
        this.zoneId             = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.intervalSeconds    = intervalSeconds;
        this.morningBriefTime   = morningBriefTime;
        this.moodCheckinTime    = moodCheckinTime;
        this.allowedUserId      = allowedUserId;
        this.executor           = Executors.newSingleThreadScheduledExecutor();
    }

    public void setExtraServices(MoodService mood, CountdownService countdown, GoalService goal,
                                  GoogleCalendarService gcal, GoogleTasksService gtasks) {
        this.moodService = mood;
        this.countdownService = countdown;
        this.goalService = goal;
        this.googleCalendarService = gcal;
        this.googleTasksService = gtasks;
    }

    public void start() {
        executor.scheduleAtFixedRate(this::runSafely, 10, intervalSeconds, TimeUnit.SECONDS);
    }

    public void shutdown() { executor.shutdownNow(); }

    private void runSafely() {
        try { runCycle(); } catch (Exception e) { e.printStackTrace(); }
    }

    private void runCycle() {
        sendReminders();
        sendStaleTaskPings();
        checkPomodoro();       // must run before checkFocusSessions
        checkFocusSessions();  // skips sessions already handled by pomodoro
        sendMorningSummaries();
        sendWeeklyDigests();
        runWeeklyProfileConsolidation();
        sendMorningBrief();
        sendMoodCheckin();
        syncGoogleTaskCompletions();
        cleanupKeys();
    }

    // ── Reminders ────────────────────────────────────────────────────────────

    private void sendReminders() {
        LocalTime now = LocalTime.now(zoneId);
        for (TaskService.ReminderDue reminder : taskService.findDueReminders()) {
            Task task = reminder.task();
            UserSettings settings = taskService.getUserSettings(task.getUserId());
            if (settings.isQuietHour(now)) {
                // Still count as ignored so we don't increment the count unfairly
                continue;
            }

            int ignoredCount = task.getReminderIgnoredCount();

            // Build reminder message — contextual if Claude available, escalating if ignored enough
            String messageText = buildReminderText(task, reminder.label(), ignoredCount);
            taskBot.sendText(task.getChatId(), messageText);
            // Increment AFTER sending — this means the NEXT reminder knows this one was "seen".
            // The count represents "reminders sent without user action" and resets when task is marked done.
            taskService.updateLastReminderAt(task.getId());
            taskService.incrementReminderIgnoredCount(task.getId());
        }
    }

    private String buildReminderText(Task task, String label, int ignoredCount) {
        String prefix;
        if (ignoredCount >= 5) {
            prefix = "🚨 URGENT — You've missed " + ignoredCount + " reminders for this task!";
        } else if (ignoredCount >= 3) {
            prefix = "⚠️ You've had " + ignoredCount + " reminders for this and haven't acted yet.";
        } else {
            prefix = "⏰ Reminder: " + label;
        }

        // Try to get a contextual motivating message from Claude
        String contextualMsg = null;
        if (claudeService != null) {
            try {
                int habitStreak = task.isHabit() ? taskService.getHabitStreak(task.getId()) : 0;
                contextualMsg = claudeService.generateReminderMessage(task, label, ignoredCount, habitStreak);
            } catch (Exception ignored) {}
        }

        String baseText = prefix + "\n\n" + taskService.formatTask(task);
        return contextualMsg != null ? baseText + "\n\n💬 " + contextualMsg : baseText;
    }

    // ── Focus sessions ───────────────────────────────────────────────────────

    private void checkFocusSessions() {
        for (FocusSession session : taskService.findUnnotifiedCompletedSessions()) {
            // Skip sessions managed by Pomodoro cycle
            UserSettings settings = taskService.getUserSettings(session.getUserId());
            if (settings.getPomodoroState() != null && settings.getPomodoroState().startsWith("POMODORO:")) continue;

            String msg = "🎯 Focus session complete!\n\n"
                    + "You focused on: " + session.getTaskTitle() + "\n"
                    + "Duration: " + session.getDurationMinutes() + " minutes\n\n"
                    + "Great work! Take a short break. 🌿";
            taskBot.sendText(session.getChatId(), msg);
            taskService.markFocusSessionNotified(session.getId());
        }
    }

    // ── Stale tasks ──────────────────────────────────────────────────────────

    private void sendStaleTaskPings() {
        LocalTime now = LocalTime.now(zoneId);
        for (Task task : taskService.findStaleTasksNeedingPing()) {
            UserSettings settings = taskService.getUserSettings(task.getUserId());
            if (settings.isQuietHour(now)) continue;
            taskBot.sendText(task.getChatId(),
                    "🧊 Stale task:\n\n" + taskService.formatTask(task)
                    + "\n\nUse /done " + task.shortId() + " or /delete " + task.shortId());
            taskService.markStalePinged(task.getId());
        }
    }

    // ── Morning summaries ────────────────────────────────────────────────────

    private void sendMorningSummaries() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        // Window must be wider than one scheduler interval to avoid misses
        int windowSeconds = Math.max(300, intervalSeconds + 60);
        if (now.toLocalTime().isBefore(morningSummaryTime) ||
                now.toLocalTime().isAfter(morningSummaryTime.plusSeconds(windowSeconds))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            String key = uc.userId() + ":morning:" + LocalDate.now(zoneId);
            if (sentMorningKeys.contains(key)) continue;
            taskBot.sendText(uc.chatId(), taskService.buildMorningSummary(uc.userId()));
            sentMorningKeys.add(key);
        }
    }

    // ── Weekly digest ────────────────────────────────────────────────────────

    private void sendWeeklyDigests() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        if (now.getDayOfWeek() != DayOfWeek.SUNDAY) return;
        int wdWindowSeconds = Math.max(300, intervalSeconds + 60);
        if (now.toLocalTime().isBefore(morningSummaryTime) ||
                now.toLocalTime().isAfter(morningSummaryTime.plusSeconds(wdWindowSeconds))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            UserSettings settings = taskService.getUserSettings(uc.userId());
            if (!settings.isWeeklyDigest()) continue;
            String key = uc.userId() + ":weekly:" + LocalDate.now(zoneId);
            if (sentWeeklyKeys.contains(key)) continue;
            taskBot.sendText(uc.chatId(), taskService.buildWeeklyDigest(uc.userId()));
            sentWeeklyKeys.add(key);
        }
    }


    // ── Weekly profile consolidation ─────────────────────────────────────────

    private void runWeeklyProfileConsolidation() {
        if (claudeService == null) return;
        LocalDateTime now = LocalDateTime.now(zoneId);
        if (now.getDayOfWeek() != DayOfWeek.SUNDAY) return;
        // Run in a window 10 minutes after morning summary (avoids clash)
        LocalTime window = morningSummaryTime.plusMinutes(10);
        int cpWindowSeconds = Math.max(300, intervalSeconds + 60);
        if (now.toLocalTime().isBefore(window) || now.toLocalTime().isAfter(window.plusSeconds(cpWindowSeconds))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            String key = uc.userId() + ":consolidate:" + LocalDate.now(zoneId);
            if (sentConsolidateKeys.contains(key)) continue;
            try { claudeService.consolidateProfile(uc.userId()); } catch (Exception e) {
                System.err.println("Profile consolidation error for " + uc.userId() + ": " + e.getMessage());
            }
            sentConsolidateKeys.add(key);
        }
    }

    // ── Pomodoro ─────────────────────────────────────────────────────────────

    private void checkPomodoro() {
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            UserSettings settings = taskService.getUserSettings(uc.userId());
            String state = settings.getPomodoroState();
            if (state == null || !state.startsWith("POMODORO:")) continue;

            // Format: POMODORO:rounds:workMins:breakMins:currentRound:phase
            String[] parts = state.split(":");
            if (parts.length < 6) continue;

            try {
                int totalRounds    = Integer.parseInt(parts[1]);
                int workMins       = Integer.parseInt(parts[2]);
                int breakMins      = Integer.parseInt(parts[3]);
                int currentRound   = Integer.parseInt(parts[4]);
                String phase       = parts[5]; // "work" or "break"

                FocusSession session = taskService.getActiveFocusSession(uc.userId());

                // Check if current phase session has ended
                java.util.List<FocusSession> completed = taskService.findUnnotifiedCompletedSessions();
                boolean currentDone = completed.stream().anyMatch(s -> s.getUserId() == uc.userId());

                if (!currentDone) continue;

                // Mark current session notified
                completed.stream().filter(s -> s.getUserId() == uc.userId())
                        .forEach(s -> taskService.markFocusSessionNotified(s.getId()));

                String taskTitle = session != null ? session.getTaskTitle()
                        .replaceAll(" \\[Pomodoro .*\\]", "") : "your task";

                if (phase.equals("work")) {
                    if (currentRound >= totalRounds) {
                        // All rounds done!
                        taskBot.sendText(uc.chatId(), "🍅 Pomodoro complete!\n\n"
                                + "You finished all " + totalRounds + " rounds for: " + taskTitle + "\n\n"
                                + "Excellent work! Take a proper break. 🎉");
                        taskService.saveUserSettings(uc.userId(), settings.getQuietStart(), settings.getQuietEnd(), null);
                    } else {
                        // Start break
                        String newState = "POMODORO:" + totalRounds + ":" + workMins + ":" + breakMins + ":" + currentRound + ":break";
                        taskService.saveUserSettings(uc.userId(), settings.getQuietStart(), settings.getQuietEnd(), newState);
                        taskService.startFocusSession(uc.userId(), uc.chatId(),
                                taskTitle + " [Break " + currentRound + "/" + totalRounds + "]", breakMins);
                        taskBot.sendText(uc.chatId(), "☕ Break time! (" + breakMins + " min)\n\n"
                                + "Round " + currentRound + "/" + totalRounds + " done. Rest up, next work session starts after your break.");
                    }
                } else {
                    // Break done — start next work round
                    int nextRound = currentRound + 1;
                    String newState = "POMODORO:" + totalRounds + ":" + workMins + ":" + breakMins + ":" + nextRound + ":work";
                    taskService.saveUserSettings(uc.userId(), settings.getQuietStart(), settings.getQuietEnd(), newState);
                    taskService.startFocusSession(uc.userId(), uc.chatId(),
                            taskTitle + " [Pomodoro " + nextRound + "/" + totalRounds + "]", workMins);
                    taskBot.sendText(uc.chatId(), "🍅 Back to work! Round " + nextRound + "/" + totalRounds + "\n\n"
                            + workMins + " min focus session started. Let's go! 💪");
                }
            } catch (Exception e) {
                System.err.println("Pomodoro check error: " + e.getMessage());
            }
        }
    }

    // ── Enhanced Morning Brief ──────────────────────────────────────────────

    private final Set<String> sentBriefKeys = ConcurrentHashMap.newKeySet();

    private void sendMorningBrief() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        int windowSeconds = Math.max(300, intervalSeconds + 60);
        if (now.toLocalTime().isBefore(morningBriefTime) ||
                now.toLocalTime().isAfter(morningBriefTime.plusSeconds(windowSeconds))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            String key = uc.userId() + ":brief:" + LocalDate.now(zoneId);
            if (sentBriefKeys.contains(key)) continue;
            try {
                String brief = buildMorningBrief(uc.userId());
                taskBot.sendText(uc.chatId(), brief);
            } catch (Exception e) {
                System.err.println("Morning brief error: " + e.getMessage());
            }
            sentBriefKeys.add(key);
        }
    }

    private String buildMorningBrief(long userId) {
        LocalDate today = LocalDate.now(zoneId);
        StringBuilder sb = new StringBuilder();

        // 1. Greeting
        sb.append("☀️ Good morning! Today is ")
          .append(today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")))
          .append("\n\n");

        // 2. Countdowns
        if (countdownService != null) {
            var countdowns = countdownService.getUpcomingForBrief(userId);
            if (!countdowns.isEmpty()) {
                countdowns.forEach(cd -> sb.append(countdownService.formatCountdown(cd)).append("\n"));
                sb.append("\n");
            }
        }

        // 3. Calendar events
        if (googleCalendarService != null) {
            try {
                var events = googleCalendarService.getTodayEvents();
                if (!events.isEmpty()) {
                    sb.append("📅 Today's Calendar\n");
                    events.forEach(e -> sb.append("  • ").append(e.startDate()).append(" — ").append(e.title()).append("\n"));
                    sb.append("\n");
                }
            } catch (Exception ignored) {}
        }

        // 4. Overdue tasks (max 3)
        var overdue = taskService.getOverdueTasks(userId);
        if (!overdue.isEmpty()) {
            sb.append("❗ Overdue\n");
            overdue.stream().limit(3).forEach(t ->
                sb.append("  • ").append(t.getTitle())
                  .append(t.getDueAt() != null ? " (due " + taskService.friendlyDate(t.getDueAt()) + ")" : "")
                  .append("\n"));
            if (overdue.size() > 3) sb.append("  ... and ").append(overdue.size() - 3).append(" more\n");
            sb.append("\n");
        }

        // 5. Today's tasks
        var todayTasks = taskService.getTodayTasks(userId);
        if (!todayTasks.isEmpty()) {
            sb.append("📋 Due Today\n");
            todayTasks.forEach(t -> sb.append("  • ").append(t.getTitle()).append("\n"));
            sb.append("\n");
        }

        // 6. Habits with streaks
        var habits = taskService.getHabits(userId);
        if (!habits.isEmpty()) {
            sb.append("🔄 Habits\n");
            habits.forEach(h -> sb.append("  • ").append(h.getTitle())
                    .append(" — 🔥 ").append(taskService.getHabitStreak(h.getId())).append(" day streak\n"));
            sb.append("\n");
        }

        // 7. Mood trend
        if (moodService != null) {
            String moodNote = moodService.getMoodTrendNote(userId);
            if (moodNote != null) sb.append(moodNote).append("\n\n");
        }

        // 8. Quick summary
        var active = taskService.getActiveTasks(userId);
        long high = active.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        sb.append("📊 ").append(active.size()).append(" active tasks");
        if (high > 0) sb.append(" (").append(high).append(" high priority)");
        sb.append("\n");

        return sb.toString().trim();
    }

    // ── Mood check-in ───────────────────────────────────────────────────────

    private final Set<String> sentMoodKeys = ConcurrentHashMap.newKeySet();

    private void sendMoodCheckin() {
        if (moodService == null) return;
        LocalDateTime now = LocalDateTime.now(zoneId);
        int windowSeconds = Math.max(300, intervalSeconds + 60);
        if (now.toLocalTime().isBefore(moodCheckinTime) ||
                now.toLocalTime().isAfter(moodCheckinTime.plusSeconds(windowSeconds))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            String key = uc.userId() + ":mood:" + LocalDate.now(zoneId);
            if (sentMoodKeys.contains(key)) continue;
            taskBot.sendText(uc.chatId(),
                    "How are you feeling today? Reply with two numbers (mood energy) from 1-5.\nExample: 4 3");
            sentMoodKeys.add(key);
            // Check for low mood streak
            if (moodService.isLowMoodStreak(uc.userId(), 3)) {
                taskBot.sendText(uc.chatId(),
                        "You've been feeling low for a few days. Take it easy — focus on just 1 important thing today. \uD83E\uDEF6");
            }
        }
    }

    // ── Google Tasks → Bot sync (every 5 minutes) ─────────────────────────

    private Instant lastGoogleTaskSync = Instant.EPOCH;

    private void syncGoogleTaskCompletions() {
        if (googleTasksService == null) return;
        Instant now = Instant.now();
        if (now.isBefore(lastGoogleTaskSync.plusSeconds(300))) return; // every 5 min
        lastGoogleTaskSync = now;

        try {
            // 1. Sync completions from Google → bot
            var completed = googleTasksService.fetchRecentlyCompleted();
            for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
                for (var item : completed) {
                    taskService.findByGoogleTaskId(uc.userId(), item.taskId()).ifPresent(task -> {
                        if (task.getStatus() == Task.Status.ACTIVE) {
                            taskService.markDone(uc.userId(), task.shortId());
                            taskBot.sendText(uc.chatId(), "✅ \"" + task.getTitle() + "\" completed in Google Tasks.");
                        }
                    });
                }

                // 2. Sync edits (title/due changes) from Google → bot
                try {
                    var allGoogleTasks = googleTasksService.fetchAllIncompleteTasks();
                    for (var item : allGoogleTasks) {
                        taskService.findByGoogleTaskId(uc.userId(), item.taskId()).ifPresent(task -> {
                            boolean changed = false;
                            String oldTitle = task.getTitle();
                            // Title changed in Google Tasks
                            if (item.title() != null && !item.title().equals(oldTitle)) {
                                taskService.updateTaskTitle(uc.userId(), task.shortId(), item.title());
                                changed = true;
                            }
                            // Due date changed in Google Tasks
                            if (item.due() != null) {
                                try {
                                    String gDue = item.due().substring(0, 10); // yyyy-MM-dd
                                    String localDue = task.getDueAt() != null ? task.getDueAt().toLocalDate().toString() : null;
                                    if (!gDue.equals(localDue)) {
                                        java.time.LocalDateTime newDue = java.time.LocalDate.parse(gDue).atTime(9, 0);
                                        taskService.updateTaskDueAtDirectly(uc.userId(), task.shortId(), newDue);
                                        changed = true;
                                    }
                                } catch (Exception ignored) {}
                            }
                            if (changed) {
                                String displayTitle = item.title() != null ? item.title() : oldTitle;
                                taskBot.sendText(uc.chatId(), "🔄 \"" + displayTitle + "\" updated from Google Tasks.");
                            }
                        });
                    }
                } catch (Exception e) {
                    System.err.println("Google Tasks edit sync error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Google Tasks sync error: " + e.getMessage());
        }
    }

    private void cleanupKeys() {
        LocalDate today = LocalDate.now(zoneId);
        sentMorningKeys.removeIf(k -> !k.contains(today.toString()));
        sentWeeklyKeys.removeIf(k -> !k.contains(today.toString()));
        sentConsolidateKeys.removeIf(k -> !k.contains(today.toString()));
        sentBriefKeys.removeIf(k -> !k.contains(today.toString()));
        sentMoodKeys.removeIf(k -> !k.contains(today.toString()));
    }
}