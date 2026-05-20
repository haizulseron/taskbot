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

        // ── Composio (brokers Gmail / Calendar / Drive) ──────────────────────
        String composioKey = config.getComposioApiKey();
        ComposioService composioService = (composioKey != null && !composioKey.isBlank())
                ? new ComposioService(composioKey, config.getComposioUserId()) : null;
        if (composioService != null) {
            System.out.println("Composio initialized for user_id=" + config.getComposioUserId());
        } else {
            System.err.println("Composio not configured — Gmail/Calendar/Drive tools will be disabled.");
        }

        // ── Moonshot/Kimi (Memory summarization + Subconscious routing slots) ─
        String moonshotKey = config.getMoonshotApiKey();
        KimiService kimiService = (moonshotKey != null && !moonshotKey.isBlank())
                ? new KimiService(moonshotKey) : null;
        if (kimiService != null) {
            System.out.println("Kimi (Moonshot) initialized. Memory summarization → " + ModelRouting.MEMORY_SUMMARIZATION);
        } else {
            System.err.println("Moonshot key missing — Memory summarization will fall back to " + ModelRouting.REFLECTIONS + " on Anthropic.");
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

        // Google Tasks — still hand-rolled (SQLite mirror, not migrated to Composio)
        GoogleTasksService googleTasksService = null;
        if (googleAuthService != null && googleAuthService.isAuthorized()) {
            try {
                googleTasksService = new GoogleTasksService(googleAuthService);
                System.out.println("Google Tasks service initialized.");
            } catch (Exception e) {
                System.err.println("Google Tasks init failed: " + e.getMessage());
            }
        }

        // ── Wire services into ClaudeService ─────────────────────────────────
        if (claudeService != null) {
            claudeService.setExtraServices(moodService, countdownService, goalService);
            if (googleTasksService != null) claudeService.setGoogleTasksService(googleTasksService);
            if (composioService != null) claudeService.setComposioService(composioService);
            if (kimiService != null) claudeService.setKimiService(kimiService);
        }

        // ── Create bot ───────────────────────────────────────────────────────
        TaskBot taskBot = new TaskBot(config, taskService, claudeService, whisperService, notionService, noteService,
                googleAuthService);
        taskBot.setExtraServices(googleTasksService, journalService,
                inboxService, moodService, countdownService, goalService, composioService);

        // ── Scheduler ────────────────────────────────────────────────────────
        SchedulerService schedulerService = new SchedulerService(
                taskService, taskBot, claudeService,
                config.getZoneId(), config.getMorningSummaryTime(),
                config.getSchedulerCheckIntervalSeconds(),
                config.getMorningBriefTime(), config.getMoodCheckinTime(),
                config.getAuditTimes(), config.getErrLogPath(), database,
                config.getAllowedUserId()
        );
        schedulerService.setExtraServices(moodService, countdownService, goalService,
                composioService, googleTasksService);

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
