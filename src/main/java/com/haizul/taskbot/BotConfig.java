package com.haizul.taskbot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Properties;

public class BotConfig {
    private final String botUsername;
    private final String botToken;
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final int schedulerCheckIntervalSeconds;
    private final String dbPath;
    private final int defaultStaleDays;

    private BotConfig(String botUsername,
                      String botToken,
                      ZoneId zoneId,
                      LocalTime morningSummaryTime,
                      int schedulerCheckIntervalSeconds,
                      String dbPath,
                      int defaultStaleDays) {
        this.botUsername = botUsername;
        this.botToken = botToken;
        this.zoneId = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.schedulerCheckIntervalSeconds = schedulerCheckIntervalSeconds;
        this.dbPath = dbPath;
        this.defaultStaleDays = defaultStaleDays;
    }

    public static BotConfig load() {
        Properties properties = new Properties();
        try (InputStream inputStream = BotConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }

        String botUsername = firstNonBlank(
                System.getenv("BOT_USERNAME"),
                properties.getProperty("bot.username")
        );
        String botToken = firstNonBlank(
                System.getenv("BOT_TOKEN"),
                properties.getProperty("bot.token")
        );
        String timezone = firstNonBlank(
                System.getenv("APP_TIMEZONE"),
                properties.getProperty("app.timezone", "Asia/Singapore")
        );
        String morningTime = firstNonBlank(
                System.getenv("APP_MORNING_SUMMARY_TIME"),
                properties.getProperty("app.morning.summary.time", "08:00")
        );
        String checkInterval = firstNonBlank(
                System.getenv("APP_SCHEDULER_INTERVAL_SECONDS"),
                properties.getProperty("app.scheduler.check.interval.seconds", "60")
        );
        String dbPath = firstNonBlank(
                System.getenv("APP_DB_PATH"),
                properties.getProperty("app.db.path", "data/taskbot.db")
        );
        String staleDays = firstNonBlank(
                System.getenv("APP_DEFAULT_STALE_DAYS"),
                properties.getProperty("app.default.stale.days", "5")
        );

        if (isBlank(botUsername)) {
            throw new IllegalStateException("bot.username or BOT_USERNAME must be set.");
        }
        if (isBlank(botToken) || Objects.equals(botToken, "PUT_YOUR_BOT_TOKEN_HERE")) {
            throw new IllegalStateException("bot.token or BOT_TOKEN must be set with your real Telegram bot token.");
        }

        try {
            Path dbFile = Path.of(dbPath);
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create database directory for path: " + dbPath, e);
        }

        return new BotConfig(
                botUsername.trim(),
                botToken.trim(),
                ZoneId.of(timezone.trim()),
                LocalTime.parse(morningTime.trim()),
                Integer.parseInt(checkInterval.trim()),
                dbPath.trim(),
                Integer.parseInt(staleDays.trim())
        );
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getBotToken() {
        return botToken;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public LocalTime getMorningSummaryTime() {
        return morningSummaryTime;
    }

    public int getSchedulerCheckIntervalSeconds() {
        return schedulerCheckIntervalSeconds;
    }

    public String getDbPath() {
        return dbPath;
    }

    public int getDefaultStaleDays() {
        return defaultStaleDays;
    }
}
