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
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final int intervalSeconds;
    private final ScheduledExecutorService executor;
    private final Set<String> sentMorningKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> sentWeeklyKeys  = ConcurrentHashMap.newKeySet();

    public SchedulerService(TaskService taskService, TaskBot taskBot, ClaudeService claudeService,
                            ZoneId zoneId, LocalTime morningSummaryTime, int intervalSeconds) {
        this.taskService        = taskService;
        this.taskBot            = taskBot;
        this.claudeService      = claudeService;
        this.zoneId             = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.intervalSeconds    = intervalSeconds;
        this.executor           = Executors.newSingleThreadScheduledExecutor();
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
        checkFocusSessions();
        checkPomodoro();
        sendMorningSummaries();
        sendWeeklyDigests();
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
        if (now.toLocalTime().isBefore(morningSummaryTime) ||
                now.toLocalTime().isAfter(morningSummaryTime.plusMinutes(5))) return;
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
        if (now.toLocalTime().isBefore(morningSummaryTime) ||
                now.toLocalTime().isAfter(morningSummaryTime.plusMinutes(5))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            UserSettings settings = taskService.getUserSettings(uc.userId());
            if (!settings.isWeeklyDigest()) continue;
            String key = uc.userId() + ":weekly:" + LocalDate.now(zoneId);
            if (sentWeeklyKeys.contains(key)) continue;
            taskBot.sendText(uc.chatId(), taskService.buildWeeklyDigest(uc.userId()));
            sentWeeklyKeys.add(key);
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

    private void cleanupKeys() {
        LocalDate today = LocalDate.now(zoneId);
        sentMorningKeys.removeIf(k -> !k.contains(today.toString()));
        sentWeeklyKeys.removeIf(k -> !k.contains(today.toString()));
    }
}
