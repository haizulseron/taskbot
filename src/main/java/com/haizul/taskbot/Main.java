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

        TaskBot taskBot = new TaskBot(config, taskService, claudeService, whisperService, notionService, noteService,
                googleAuthService);

        SchedulerService schedulerService = new SchedulerService(
                taskService, taskBot, claudeService,
                config.getZoneId(), config.getMorningSummaryTime(),
                config.getSchedulerCheckIntervalSeconds()
        );

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
