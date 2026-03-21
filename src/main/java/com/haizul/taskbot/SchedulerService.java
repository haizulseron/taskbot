package com.haizul.taskbot;

import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
    private final ScheduledExecutorService executorService;
    private final Set<String> sentMorningSummaryKeys;

    public SchedulerService(TaskService taskService, TaskBot taskBot, ZoneId zoneId, LocalTime morningSummaryTime, int intervalSeconds) {
        this.taskService = taskService;
        this.taskBot = taskBot;
        this.zoneId = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.intervalSeconds = intervalSeconds;
        this.executorService = Executors.newSingleThreadScheduledExecutor();
        this.sentMorningSummaryKeys = ConcurrentHashMap.newKeySet();
    }

    public void start() {
        executorService.scheduleAtFixedRate(this::runSafely, 10, intervalSeconds, TimeUnit.SECONDS);
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    private void runSafely() {
        try {
            runCycle();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void runCycle() {
        sendReminderMessages();
        sendStaleTaskPings();
        sendMorningSummaries();
        cleanupMorningSummaryKeys();
    }

    private void sendReminderMessages() {
        for (TaskService.ReminderDue reminder : taskService.findDueReminders()) {
            Task task = reminder.task();
            String text = "⏰ Reminder: " + reminder.label() + "\n\n" + taskService.formatTask(task);
            taskBot.sendText(task.getChatId(), text);
            taskService.markReminderSent(task.getId(), reminder.stage());
        }
    }

    private void sendStaleTaskPings() {
        for (Task task : taskService.findStaleTasksNeedingPing()) {
            String text = "🧊 Stale task check\n\nThis task has gone stale:\n" + taskService.formatTask(task)
                    + "\n\nUse /done " + task.shortId() + " or /snooze " + task.shortId() + " 24";
            taskBot.sendText(task.getChatId(), text);
            taskService.markStalePinged(task.getId());
        }
    }

    private void sendMorningSummaries() {
        LocalDateTime now = LocalDateTime.now(zoneId);
        if (now.toLocalTime().isBefore(morningSummaryTime) || now.toLocalTime().isAfter(morningSummaryTime.plusMinutes(5))) {
            return;
        }

        for (TaskService.UserChat userChat : taskService.getKnownUserChats()) {
            String key = userChat.userId() + ":" + LocalDate.now(zoneId);
            if (sentMorningSummaryKeys.contains(key)) {
                continue;
            }
            String summary = taskService.buildMorningSummary(userChat.userId());
            taskBot.sendText(userChat.chatId(), summary);
            sentMorningSummaryKeys.add(key);
        }
    }

    private void cleanupMorningSummaryKeys() {
        LocalDate today = LocalDate.now(zoneId);
        sentMorningSummaryKeys.removeIf(key -> !key.endsWith(today.toString()));
    }
}
