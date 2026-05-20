package com.haizul.taskbot;

import java.time.*;
import java.util.List;
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
    private ComposioService composio;
    private GoogleTasksService googleTasksService;
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final int intervalSeconds;
    private final LocalTime morningBriefTime;
    private final LocalTime moodCheckinTime;
    private final List<LocalTime> auditTimes;
    private final String errLogPath;
    private final Database database;
    private final long allowedUserId;
    private final ScheduledExecutorService executor;
    private final Set<String> sentMorningKeys      = ConcurrentHashMap.newKeySet();
    private final Set<String> sentWeeklyKeys       = ConcurrentHashMap.newKeySet();
    private final Set<String> sentConsolidateKeys  = ConcurrentHashMap.newKeySet();

    public SchedulerService(TaskService taskService, TaskBot taskBot, ClaudeService claudeService,
                            ZoneId zoneId, LocalTime morningSummaryTime, int intervalSeconds,
                            LocalTime morningBriefTime, LocalTime moodCheckinTime,
                            List<LocalTime> auditTimes, String errLogPath, Database database,
                            long allowedUserId) {
        this.taskService        = taskService;
        this.taskBot            = taskBot;
        this.claudeService      = claudeService;
        this.zoneId             = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.intervalSeconds    = intervalSeconds;
        this.morningBriefTime   = morningBriefTime;
        this.moodCheckinTime    = moodCheckinTime;
        this.auditTimes         = List.copyOf(auditTimes);
        this.errLogPath         = errLogPath;
        this.database           = database;
        this.allowedUserId      = allowedUserId;
        this.executor           = Executors.newSingleThreadScheduledExecutor();
    }

    public void setExtraServices(MoodService mood, CountdownService countdown, GoalService goal,
                                  ComposioService composio, GoogleTasksService gtasks) {
        this.moodService = mood;
        this.countdownService = countdown;
        this.goalService = goal;
        this.composio = composio;
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
        runGoogleHealthCheck();
        runNightlyAudit();
        cleanupKeys();
    }

    // ── Scheduled self-audit (option 4) ─────────────────────────────────────
    // At each configured auditTimes slot (default 03:00 + 15:00), gather the
    // last 12h-or-24h of action_log + err log tail, hand it to Claude for a
    // one-shot summary, and post the verdict to the user's chat. Idempotent
    // per (user, date, slot) via sentAuditKeys.
    private final Set<String> sentAuditKeys = ConcurrentHashMap.newKeySet();

    private void runNightlyAudit() {
        if (claudeService == null || database == null || auditTimes.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now(zoneId);
        int windowSeconds = Math.max(300, intervalSeconds + 60);

        // Find which audit slot (if any) is currently in window
        LocalTime activeSlot = null;
        for (LocalTime slot : auditTimes) {
            if (!now.toLocalTime().isBefore(slot)
                    && !now.toLocalTime().isAfter(slot.plusSeconds(windowSeconds))) {
                activeSlot = slot;
                break;
            }
        }
        if (activeSlot == null) return;

        // Window for the audit narrative depends on cadence: if there's only one
        // slot per day, look back 24h; if multiple, look back ≈ time since the
        // previous slot (so audits don't double-cover the same period).
        int lookbackSeconds = computeLookbackSeconds(activeSlot);
        boolean isShortCadence = lookbackSeconds < 86400;
        String emoji = isShortCadence ? "🕒" : "🌙";
        String label = isShortCadence ? "12h Audit" : "Nightly Audit";

        for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
            String slotTag = activeSlot.toString().replace(':', '-');
            String key = uc.userId() + ":audit:" + LocalDate.now(zoneId) + ":" + slotTag;
            if (sentAuditKeys.contains(key)) continue;
            sentAuditKeys.add(key); // mark immediately so a slow API call doesn't double-post

            try {
                String stats     = buildAuditStats(uc.userId(), lookbackSeconds);
                String errTail   = readErrLogTail(isShortCadence ? 150 : 200);
                String googleHlt = (claudeService.isGoogleOffline() ? "🔴 OFFLINE (token revoked)" : "🟢 online");

                String summary = claudeService.runDailyAudit(stats, errTail, googleHlt);
                if (summary == null || summary.isBlank()) {
                    taskBot.sendText(uc.chatId(), emoji + " " + label + " unavailable — Claude API call failed. Next slot will retry.");
                    continue;
                }
                taskBot.sendText(uc.chatId(), summary);
            } catch (Exception e) {
                System.err.println("Audit error (" + activeSlot + ") for " + uc.userId() + ": " + e.getMessage());
            }
        }
    }

    /** How far back the audit should look. With multiple slots, we cover only
     *  the period since the previous slot to avoid duplicate findings; with a
     *  single slot, we cover a full 24h. */
    private int computeLookbackSeconds(LocalTime currentSlot) {
        if (auditTimes.size() <= 1) return 86400;
        // Find the slot that comes just BEFORE currentSlot (wrap around if needed)
        List<LocalTime> sorted = new java.util.ArrayList<>(auditTimes);
        java.util.Collections.sort(sorted);
        LocalTime prev = null;
        for (LocalTime t : sorted) {
            if (t.equals(currentSlot)) break;
            prev = t;
        }
        if (prev == null) prev = sorted.get(sorted.size() - 1); // wrap to yesterday's last slot
        long secs = java.time.Duration.between(prev, currentSlot).getSeconds();
        if (secs <= 0) secs += 86400; // wrapped past midnight
        // Add a bit of overlap so we don't miss events between scheduler ticks
        return (int) secs + 600;
    }

    /** Pull stats from action_log over the given window and format as a compact text block. */
    private String buildAuditStats(long userId, int withinSeconds) {
        List<Database.ActionLogEntry> rows = database.getRecentActions(userId, withinSeconds, 500);
        if (rows.isEmpty()) {
            int hours = Math.max(1, withinSeconds / 3600);
            return "(no tool calls in the last " + hours + "h)";
        }

        // Aggregate by tool + status
        java.util.Map<String, int[]> byTool = new java.util.LinkedHashMap<>(); // [success, error]
        java.util.Map<String, Integer> byErrCat = new java.util.LinkedHashMap<>();
        int totalSuccess = 0, totalError = 0;
        for (Database.ActionLogEntry r : rows) {
            int[] counts = byTool.computeIfAbsent(r.toolName(), k -> new int[2]);
            if ("error".equals(r.status())) {
                counts[1]++; totalError++;
                if (r.errorCategory() != null) {
                    byErrCat.merge(r.errorCategory(), 1, Integer::sum);
                }
            } else { counts[0]++; totalSuccess++; }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Total tool calls: ").append(rows.size())
          .append("  (").append(totalSuccess).append(" ok / ").append(totalError).append(" err)\n");
        sb.append("\nPer tool (ok / err):\n");
        byTool.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]))
                .limit(15)
                .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                        .append(e.getValue()[0]).append(" / ").append(e.getValue()[1]).append("\n"));
        if (!byErrCat.isEmpty()) {
            sb.append("\nError categories:\n");
            byErrCat.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }
        return sb.toString();
    }

    /** Read the last `lines` lines of the err log. Returns empty if unreadable. */
    private String readErrLogTail(int lines) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(errLogPath);
            if (!java.nio.file.Files.exists(p)) return "";
            // Read the whole file's last N lines. For a multi-MB file this is OK
            // because we only do it once a day.
            List<String> all = java.nio.file.Files.readAllLines(p);
            int from = Math.max(0, all.size() - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append("\n");
            // Truncate to ~6KB to keep the prompt small
            String s = sb.toString();
            return s.length() > 6000 ? s.substring(s.length() - 6000) : s;
        } catch (Exception e) {
            return "(could not read err log: " + e.getMessage() + ")";
        }
    }

    // ── Google health-check (item 8) ────────────────────────────────────────
    // Once every 6 hours we make ONE cheap Google call. If it fails with the
    // signature of a revoked refresh token, we mark Google offline + ping the
    // user — proactively, instead of waiting for them to discover the breakage
    // by trying to draft an email or check their calendar.
    private Instant lastHealthCheck = Instant.EPOCH;
    private boolean healthCheckNotifiedThisOutage = false;

    private void runGoogleHealthCheck() {
        if (googleTasksService == null && composio == null) return;
        Instant now = Instant.now();
        if (now.isBefore(lastHealthCheck.plus(Duration.ofHours(6)))) return;
        lastHealthCheck = now;

        // Already known offline — let the circuit breaker handle messaging
        if (claudeService != null && claudeService.isGoogleOffline()) return;

        try {
            // Cheapest call we have — list 1 calendar event via Composio (or fall back to tasks)
            if (composio != null) {
                String timeMin = java.time.LocalDateTime.now(zoneId).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                String timeMax = java.time.LocalDateTime.now(zoneId).plusHours(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                java.util.Map<String,Object> args = new java.util.LinkedHashMap<>();
                args.put("calendarId", "primary"); args.put("timeMin", timeMin); args.put("timeMax", timeMax); args.put("maxResults", 1);
                ComposioService.Result r = composio.execute("GOOGLECALENDAR_EVENTS_LIST", args);
                if (r.isError()) throw new RuntimeException(r.error());
            } else if (googleTasksService != null) {
                googleTasksService.fetchAllIncompleteTasks();
            }
            // Success — token alive
            healthCheckNotifiedThisOutage = false;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            Throwable cause = e.getCause();
            String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
            boolean authDead = msg.contains("invalid_grant") || causeMsg.contains("invalid_grant")
                    || msg.contains("token has been expired") || msg.contains("token has been revoked");
            if (authDead) {
                if (claudeService != null) claudeService.markGoogleOffline("health-check failed");
                if (!healthCheckNotifiedThisOutage) {
                    healthCheckNotifiedThisOutage = true;
                    for (TaskService.UserChat uc : taskService.getKnownUserChats()) {
                        taskBot.sendText(uc.chatId(),
                                "⚠️ Heads-up — your Google access token just expired. "
                              + "Run /authorize to re-link before I try Gmail / Calendar / Drive / Tasks again.");
                    }
                }
            } else {
                System.err.println("Google health-check failed (non-auth): " + e.getMessage());
            }
        }
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

        String baseText = prefix + "\n\n" + taskService.formatTaskHtml(task);
        return contextualMsg != null ? baseText + "\n\n💬 " + TaskService.esc(contextualMsg) : baseText;
    }

    // ── Focus sessions ───────────────────────────────────────────────────────

    private void checkFocusSessions() {
        for (FocusSession session : taskService.findUnnotifiedCompletedSessions()) {
            // Skip sessions managed by Pomodoro cycle
            UserSettings settings = taskService.getUserSettings(session.getUserId());
            if (settings.getPomodoroState() != null && settings.getPomodoroState().startsWith("POMODORO:")) continue;

            String msg = "🎯 <b>Focus session complete!</b>\n\n"
                    + "You focused on: <b>" + TaskService.esc(session.getTaskTitle()) + "</b>\n"
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
                    "🧊 <b>Stale task:</b>\n\n" + taskService.formatTaskHtml(task)
                    + "\n\nUse <code>/done " + task.shortId() + "</code> or <code>/delete " + task.shortId() + "</code>");
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
                        taskBot.sendText(uc.chatId(), "🍅 <b>Pomodoro complete!</b>\n\n"
                                + "You finished all " + totalRounds + " rounds for: <b>" + TaskService.esc(taskTitle) + "</b>\n\n"
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
        sb.append("<b>☀️ Good morning!</b> ")
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

        // 3. Calendar events — compact one-line format (brokered via Composio)
        if (composio != null) {
            try {
                String timeMin = java.time.LocalDate.now(zoneId).atStartOfDay().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                String timeMax = java.time.LocalDate.now(zoneId).atTime(23, 59, 59).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
                java.util.Map<String,Object> args = new java.util.LinkedHashMap<>();
                args.put("calendarId", "primary"); args.put("timeMin", timeMin); args.put("timeMax", timeMax);
                args.put("singleEvents", true); args.put("orderBy", "startTime"); args.put("maxResults", 20);
                ComposioService.Result r = composio.execute("GOOGLECALENDAR_EVENTS_LIST", args);
                if (!r.isError()) {
                    var items = r.data().path("items");
                    if (items.isArray() && items.size() > 0) {
                        sb.append("<b>📅 Calendar</b>\n");
                        for (var e : items) {
                            String start = e.path("start").path("dateTime").asText(e.path("start").path("date").asText(""));
                            sb.append("📅 ").append(TaskService.esc(start))
                              .append("  <b>").append(TaskService.esc(e.path("summary").asText(""))).append("</b>\n");
                        }
                        sb.append("\n");
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4. Overdue tasks (max 3)
        var overdue = taskService.getOverdueTasks(userId);
        if (!overdue.isEmpty()) {
            sb.append("<b>❗ Overdue</b>\n");
            overdue.stream().limit(3).forEach(t ->
                sb.append("  🔴 <b>").append(TaskService.esc(t.getTitle())).append("</b>")
                  .append(t.getDueAt() != null ? "  📅 " + taskService.friendlyDate(t.getDueAt()) : "")
                  .append("\n"));
            if (overdue.size() > 3) sb.append("  … and ").append(overdue.size() - 3).append(" more\n");
            sb.append("\n");
        }

        // 5. Today's tasks
        var todayTasks = taskService.getTodayTasks(userId);
        if (!todayTasks.isEmpty()) {
            sb.append("<b>📋 Due Today</b>\n");
            todayTasks.forEach(t -> sb.append("  ").append(taskService.dot(t))
                    .append(" <b>").append(TaskService.esc(t.getTitle())).append("</b>\n"));
            sb.append("\n");
        }

        // 6. Habits with streaks
        var habits = taskService.getHabits(userId);
        if (!habits.isEmpty()) {
            sb.append("<b>🔄 Habits</b>\n");
            habits.forEach(h -> sb.append("  🔵 <b>").append(TaskService.esc(h.getTitle()))
                    .append("</b> — 🔥 ").append(taskService.getHabitStreak(h.getId())).append("\n"));
            sb.append("\n");
        }

        // 7. Mood trend
        if (moodService != null) {
            String moodNote = moodService.getMoodTrendNote(userId);
            if (moodNote != null) sb.append(TaskService.esc(moodNote)).append("\n\n");
        }

        // 8. Quick summary
        var active = taskService.getActiveTasks(userId);
        long high = active.stream().filter(t -> t.getPriority() == Task.Priority.HIGH).count();
        sb.append("📊 <b>").append(active.size()).append("</b> active tasks");
        if (high > 0) sb.append(" (<b>").append(high).append("</b> high priority)");
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
                            taskBot.sendText(uc.chatId(), "✅ \"" + TaskService.esc(task.getTitle()) + "\" completed in Google Tasks.");
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
                                taskBot.sendText(uc.chatId(), "🔄 \"" + TaskService.esc(displayTitle) + "\" updated from Google Tasks.");
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
        sentAuditKeys.removeIf(k -> !k.contains(today.toString()));
    }
}