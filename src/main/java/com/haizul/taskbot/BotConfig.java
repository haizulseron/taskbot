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
    private final String claudeApiKey;
    private final String whisperApiKey;   // OpenAI key for voice transcription
    private final String notionApiKey;
    private final String notionDatabaseId;
    private final String googleCredentialsPath;
    private final String googleTokensPath;
    private final long allowedUserId;
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final int schedulerCheckIntervalSeconds;
    private final String dbPath;
    private final int defaultStaleDays;

    private BotConfig(String botUsername, String botToken, String claudeApiKey, String whisperApiKey,
                      String notionApiKey, String notionDatabaseId,
                      String googleCredentialsPath, String googleTokensPath,
                      long allowedUserId,
                      ZoneId zoneId, LocalTime morningSummaryTime,
                      int schedulerCheckIntervalSeconds, String dbPath, int defaultStaleDays) {
        this.botUsername = botUsername;
        this.botToken = botToken;
        this.claudeApiKey = claudeApiKey;
        this.whisperApiKey = whisperApiKey;
        this.notionApiKey = notionApiKey;
        this.notionDatabaseId = notionDatabaseId;
        this.googleCredentialsPath = googleCredentialsPath;
        this.googleTokensPath = googleTokensPath;
        this.allowedUserId = allowedUserId;
        this.zoneId = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.schedulerCheckIntervalSeconds = schedulerCheckIntervalSeconds;
        this.dbPath = dbPath;
        this.defaultStaleDays = defaultStaleDays;
    }

    public static BotConfig load() {
        Properties properties = new Properties();
        try (InputStream in = BotConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) properties.load(in);
        } catch (IOException e) { throw new RuntimeException("Failed to load application.properties", e); }

        String botUsername  = firstNonBlank(System.getenv("BOT_USERNAME"),      properties.getProperty("bot.username"));
        String botToken     = firstNonBlank(System.getenv("BOT_TOKEN"),         properties.getProperty("bot.token"));
        String claudeApiKey = firstNonBlank(System.getenv("CLAUDE_API_KEY"),    properties.getProperty("claude.api.key"));
        String whisperKey   = firstNonBlank(System.getenv("WHISPER_API_KEY"),   properties.getProperty("whisper.api.key"));
        String notionKey    = firstNonBlank(System.getenv("NOTION_API_KEY"),    properties.getProperty("notion.api.key"));
        String notionDbId   = firstNonBlank(System.getenv("NOTION_DATABASE_ID"), properties.getProperty("notion.database.id"));
        String googleCreds  = firstNonBlank(System.getenv("GOOGLE_CREDENTIALS_PATH"), properties.getProperty("google.credentials.path"));
        String googleTokens = firstNonBlank(System.getenv("GOOGLE_TOKENS_PATH"), properties.getProperty("google.tokens.path", "data/google_tokens"));
        String allowedUser  = firstNonBlank(System.getenv("ALLOWED_USER_ID"),     properties.getProperty("allowed.user.id", "0"));
        String timezone     = firstNonBlank(System.getenv("APP_TIMEZONE"),      properties.getProperty("app.timezone", "Asia/Singapore"));
        String morningTime  = firstNonBlank(System.getenv("APP_MORNING_SUMMARY_TIME"), properties.getProperty("app.morning.summary.time", "08:00"));
        String interval     = firstNonBlank(System.getenv("APP_SCHEDULER_INTERVAL_SECONDS"), properties.getProperty("app.scheduler.check.interval.seconds", "60"));
        String dbPath       = firstNonBlank(System.getenv("APP_DB_PATH"),       properties.getProperty("app.db.path", "data/taskbot.db"));
        String staleDays    = firstNonBlank(System.getenv("APP_DEFAULT_STALE_DAYS"), properties.getProperty("app.default.stale.days", "5"));

        if (isBlank(botUsername)) throw new IllegalStateException("bot.username or BOT_USERNAME must be set.");
        if (isBlank(botToken) || Objects.equals(botToken, "PUT_YOUR_BOT_TOKEN_HERE"))
            throw new IllegalStateException("bot.token or BOT_TOKEN must be set.");

        try {
            Path dbFile = Path.of(dbPath), parent = dbFile.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) { throw new RuntimeException("Failed to create database directory", e); }

        return new BotConfig(botUsername.trim(), botToken.trim(),
                claudeApiKey   != null ? claudeApiKey.trim()   : null,
                whisperKey     != null ? whisperKey.trim()     : null,
                notionKey      != null ? notionKey.trim()      : null,
                notionDbId     != null ? notionDbId.trim()     : null,
                googleCreds    != null ? googleCreds.trim()    : null,
                googleTokens   != null ? googleTokens.trim()   : "data/google_tokens",
                allowedUser    != null ? Long.parseLong(allowedUser.trim()) : 0L,
                ZoneId.of(timezone.trim()), LocalTime.parse(morningTime.trim()),
                Integer.parseInt(interval.trim()), dbPath.trim(), Integer.parseInt(staleDays.trim()));
    }

    private static String firstNonBlank(String a, String b) { return !isBlank(a) ? a : b; }
    private static boolean isBlank(String v) { return v == null || v.trim().isEmpty(); }

    public String getBotUsername()               { return botUsername; }
    public String getBotToken()                  { return botToken; }
    public String getClaudeApiKey()              { return claudeApiKey; }
    public String getWhisperApiKey()             { return whisperApiKey; }
    public String getNotionApiKey()              { return notionApiKey; }
    public String getNotionDatabaseId()          { return notionDatabaseId; }
    public String getGoogleCredentialsPath()     { return googleCredentialsPath; }
    public String getGoogleTokensPath()          { return googleTokensPath; }
    public long getAllowedUserId()                { return allowedUserId; }
    public ZoneId getZoneId()                    { return zoneId; }
    public LocalTime getMorningSummaryTime()      { return morningSummaryTime; }
    public int getSchedulerCheckIntervalSeconds() { return schedulerCheckIntervalSeconds; }
    public String getDbPath()                    { return dbPath; }
    public int getDefaultStaleDays()             { return defaultStaleDays; }
}