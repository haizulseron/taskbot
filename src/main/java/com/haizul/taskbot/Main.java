package com.haizul.taskbot;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

public class Main {
    public static void main(String[] args) {
        BotConfig config = BotConfig.load();

        Database database = new Database(config.getDbPath());
        database.initialize();

        TaskService taskService = new TaskService(database, config.getZoneId(), config.getDefaultStaleDays());

        String claudeKey = config.getClaudeApiKey();
        ClaudeService claudeService = (claudeKey != null && !claudeKey.isBlank() && !claudeKey.equals("YOUR_CLAUDE_API_KEY"))
                ? new ClaudeService(claudeKey, config.getZoneId(), database) : null;

        String whisperKey = config.getWhisperApiKey();
        WhisperService whisperService = (whisperKey != null && !whisperKey.isBlank() && !whisperKey.equals("YOUR_WHISPER_API_KEY"))
                ? new WhisperService(whisperKey, config.getBotToken()) : null;

        String notionKey  = config.getNotionApiKey();
        String notionDbId = config.getNotionDatabaseId();
        NotionService notionService = (notionKey != null && !notionKey.isBlank() && !notionKey.equals("YOUR_NOTION_API_KEY"))
                ? new NotionService(notionKey, notionDbId) : null;

        NoteService noteService = (claudeKey != null && !claudeKey.isBlank())
                ? new NoteService(claudeKey) : null;

        String googleCredsPath = config.getGoogleCredentialsPath();
        GoogleAuthService googleAuthService = null;
        if (googleCredsPath != null && !googleCredsPath.isBlank()) {
            try {
                googleAuthService = new GoogleAuthService(googleCredsPath, config.getGoogleTokensPath());
                System.out.println("Google auth initialized. Authorized: " + googleAuthService.isAuthorized());
            } catch (Exception e) {
                System.err.println("Google auth init failed: " + e.getMessage());
            }
        }

        // ── New services ─────────────────────────────────────────────────────
        MoodService moodService = new MoodService(database, config.getZoneId());
        CountdownService countdownService = new CountdownService(database, config.getZoneId());
        GoalService goalService = new GoalService(database, config.getZoneId());

        // Journal — uses its own Notion API key + database ID
        String notionJournalApiKey = config.getNotionJournalApiKey();
        String notionJournalDbId = config.getNotionJournalDbId();
        JournalService journalService = (notionJournalApiKey != null && !notionJournalApiKey.isBlank()
                && notionJournalDbId != null && !notionJournalDbId.isBlank()
                && claudeKey != null && !claudeKey.isBlank())
                ? new JournalService(notionJournalApiKey, notionJournalDbId, claudeKey) : null;

        // Inbox — needs Claude API key and bot token
        InboxService inboxService = (claudeKey != null && !claudeKey.isBlank())
                ? new InboxService(claudeKey, config.getBotToken()) : null;

        // Google Tasks & Calendar — need Google auth
        GoogleTasksService googleTasksService = null;
        GoogleCalendarService googleCalendarService = null;
        if (googleAuthService != null && googleAuthService.isAuthorized()) {
            try {
                googleTasksService = new GoogleTasksService(googleAuthService);
                System.out.println("Google Tasks service initialized.");
            } catch (Exception e) {
                System.err.println("Google Tasks init failed: " + e.getMessage());
            }
            try {
                googleCalendarService = new GoogleCalendarService(googleAuthService, config.getZoneId());
                System.out.println("Google Calendar (v2) service initialized.");
            } catch (Exception e) {
                System.err.println("Google Calendar (v2) init failed: " + e.getMessage());
            }
        }

        // ── Wire services into ClaudeService ─────────────────────────────────
        if (claudeService != null) {
            claudeService.setExtraServices(moodService, countdownService, goalService);
            if (googleTasksService != null) claudeService.setGoogleTasksService(googleTasksService);
        }

        // ── Create bot ───────────────────────────────────────────────────────
        TaskBot taskBot = new TaskBot(config, taskService, claudeService, whisperService, notionService, noteService,
                googleAuthService);
        taskBot.setExtraServices(googleCalendarService, googleTasksService, journalService,
                inboxService, moodService, countdownService, goalService);

        // ── Scheduler ────────────────────────────────────────────────────────
        SchedulerService schedulerService = new SchedulerService(
                taskService, taskBot, claudeService,
                config.getZoneId(), config.getMorningSummaryTime(),
                config.getSchedulerCheckIntervalSeconds(),
                config.getMorningBriefTime(), config.getMoodCheckinTime(),
                config.getAllowedUserId()
        );
        schedulerService.setExtraServices(moodService, countdownService, goalService,
                googleCalendarService, googleTasksService);

        try (TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication()) {
            application.registerBot(config.getBotToken(), taskBot);
            schedulerService.start();
            System.out.println("Task bot started as @" + config.getBotUsername());
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
            schedulerService.shutdown();
            System.exit(1);
        }
    }
}
