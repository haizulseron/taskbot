package com.haizul.taskbot;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

public class Main {
    public static void main(String[] args) {
        BotConfig config = BotConfig.load();

        Database database = new Database(config.getDbPath());
        database.initialize();

        TaskService taskService = new TaskService(database, config.getZoneId(), config.getDefaultStaleDays());
        TaskBot taskBot = new TaskBot(config, taskService);
        SchedulerService schedulerService = new SchedulerService(
                taskService,
                taskBot,
                config.getZoneId(),
                config.getMorningSummaryTime(),
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
