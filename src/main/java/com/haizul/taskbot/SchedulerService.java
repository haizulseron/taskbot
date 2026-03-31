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
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final int intervalSeconds;
    private final ScheduledExecutorService executor;
    private final Set<String> sentMorningKeys   = ConcurrentHashMap.newKeySet();
    private final Set<String> sentWeeklyKeys    = ConcurrentHashMap.newKeySet();

    public SchedulerService(TaskService taskService, TaskBot taskBot, ZoneId zoneId,
                            LocalTime morningSummaryTime, int intervalSeconds) {
        this.taskService        = taskService;
        this.taskBot            = taskBot;
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
        sendMorningSummaries();
        sendWeeklyDigests();
        cleanupKeys();
    }

    private void sendReminders() {
        LocalTime now = LocalTime.now(zoneId);
        for (TaskService.ReminderDue reminder : taskService.findDueReminders()) {
            Task task = reminder.task();
            // Respect quiet hours
            UserSettings settings = taskService.getUserSettings(task.getUserId());
            if (settings.isQuietHour(now)) continue;

            String text = "⏰ Reminder: " + reminder.label() + "\n\n" + taskService.formatTask(task);
            taskBot.sendText(task.getChatId(), text);
            taskService.updateLastReminderAt(task.getId());
        }
    }

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

    private void sendMorningSummaries() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        if (now.toLocalTime().isBefore(morningSummaryTime) || now.toLocalTime().isAfter(morningSummaryTime.plusMinutes(5))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            String key = uc.userId() + ":morning:" + LocalDate.now(zoneId);
            if (sentMorningKeys.contains(key)) continue;
            taskBot.sendText(uc.chatId(), taskService.buildMorningSummary(uc.userId()));
            sentMorningKeys.add(key);
        }
    }

    private void sendWeeklyDigests() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        // Send on Sunday at morning summary time
        if (now.getDayOfWeek() != DayOfWeek.SUNDAY) return;
        if (now.toLocalTime().isBefore(morningSummaryTime) || now.toLocalTime().isAfter(morningSummaryTime.plusMinutes(5))) return;
        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            UserSettings settings = taskService.getUserSettings(uc.userId());
            if (!settings.isWeeklyDigest()) continue;
            String key = uc.userId() + ":weekly:" + LocalDate.now(zoneId);
            if (sentWeeklyKeys.contains(key)) continue;
            taskBot.sendText(uc.chatId(), taskService.buildWeeklyDigest(uc.userId()));
            sentWeeklyKeys.add(key);
        }
    }

    private void cleanupKeys() {
        LocalDate today = LocalDate.now(zoneId);
        sentMorningKeys.removeIf(k -> !k.contains(today.toString()));
        sentWeeklyKeys.removeIf(k -> !k.contains(today.toString()));
    }
}
