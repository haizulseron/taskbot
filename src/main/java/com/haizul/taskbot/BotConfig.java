package com.haizul.taskbot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Properties;

/**
 * Environment variables / application.properties configuration.
 *
 * Required:
 *   BOT_USERNAME               - Telegram bot username (without @)
 *   BOT_TOKEN                  - Telegram bot token from BotFather
 *
 * Optional — AI & Integrations:
 *   CLAUDE_API_KEY              - Anthropic Claude API key
 *   WHISPER_API_KEY             - OpenAI Whisper key for voice-to-text
 *   NOTION_API_KEY              - Notion integration API key
 *   NOTION_DATABASE_ID          - Notion database ID for Quick Notes
 *   NOTION_JOURNAL_DB_ID        - Notion database ID for Journal entries
 *   GOOGLE_CREDENTIALS_PATH     - Path to Google OAuth2 credentials JSON
 *   GOOGLE_TOKENS_PATH          - Directory for persisted OAuth tokens (default: data/google_tokens)
 *
 * Optional — Feature config:
 *   ALLOWED_USER_ID             - Restrict bot to this Telegram user ID (0 = unrestricted)
 *   GROUP_CHAT_ID               - Telegram group chat ID for the Forwarded Media Inbox
 *   MOOD_CHECKIN_TIME            - Daily mood check-in time HH:mm (default: 21:00)
 *   MORNING_BRIEF_TIME           - Morning brief send time HH:mm (default: 08:00)
 *   APP_TIMEZONE                - IANA timezone (default: Asia/Singapore)
 *   APP_MORNING_SUMMARY_TIME     - Legacy morning summary time (default: 08:00)
 *   APP_SCHEDULER_INTERVAL_SECONDS - Scheduler tick interval (default: 60)
 *   APP_DB_PATH                 - SQLite database path (default: data/taskbot.db)
 *   APP_DEFAULT_STALE_DAYS      - Days before a task is considered stale (default: 5)
 */
public class BotConfig {
    private final String botUsername;
    private final String botToken;
    private final String claudeApiKey;
    private final String whisperApiKey;
    private final String notionApiKey;
    private final String notionDatabaseId;
    private final String notionJournalApiKey;
    private final String notionJournalDbId;
    private final String googleCredentialsPath;
    private final String googleTokensPath;
    private final long allowedUserId;
    private final long groupChatId;
    private final ZoneId zoneId;
    private final LocalTime morningSummaryTime;
    private final LocalTime morningBriefTime;
    private final LocalTime moodCheckinTime;
    private final int schedulerCheckIntervalSeconds;
    private final String dbPath;
    private final int defaultStaleDays;

    private BotConfig(String botUsername, String botToken, String claudeApiKey, String whisperApiKey,
                      String notionApiKey, String notionDatabaseId,
                      String notionJournalApiKey, String notionJournalDbId,
                      String googleCredentialsPath, String googleTokensPath,
                      long allowedUserId, long groupChatId,
                      ZoneId zoneId, LocalTime morningSummaryTime,
                      LocalTime morningBriefTime, LocalTime moodCheckinTime,
                      int schedulerCheckIntervalSeconds, String dbPath, int defaultStaleDays) {
        this.botUsername = botUsername;
        this.botToken = botToken;
        this.claudeApiKey = claudeApiKey;
        this.whisperApiKey = whisperApiKey;
        this.notionApiKey = notionApiKey;
        this.notionDatabaseId = notionDatabaseId;
        this.notionJournalApiKey = notionJournalApiKey;
        this.notionJournalDbId = notionJournalDbId;
        this.googleCredentialsPath = googleCredentialsPath;
        this.googleTokensPath = googleTokensPath;
        this.allowedUserId = allowedUserId;
        this.groupChatId = groupChatId;
        this.zoneId = zoneId;
        this.morningSummaryTime = morningSummaryTime;
        this.morningBriefTime = morningBriefTime;
        this.moodCheckinTime = moodCheckinTime;
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
        String notionJrnlKey = firstNonBlank(System.getenv("NOTION_JOURNAL_API_KEY"), properties.getProperty("notion.journal.api.key"));
        String notionJrnlId = firstNonBlank(System.getenv("NOTION_JOURNAL_DB_ID"), properties.getProperty("notion.journal.db.id"));
        String googleCreds  = firstNonBlank(System.getenv("GOOGLE_CREDENTIALS_PATH"), properties.getProperty("google.credentials.path"));
        String googleTokens = firstNonBlank(System.getenv("GOOGLE_TOKENS_PATH"), properties.getProperty("google.tokens.path", "data/google_tokens"));
        String allowedUser  = firstNonBlank(System.getenv("ALLOWED_USER_ID"),     properties.getProperty("allowed.user.id", "0"));
        String groupChat    = firstNonBlank(System.getenv("GROUP_CHAT_ID"),     properties.getProperty("group.chat.id", "0"));
        String timezone     = firstNonBlank(System.getenv("APP_TIMEZONE"),      properties.getProperty("app.timezone", "Asia/Singapore"));
        String morningTime  = firstNonBlank(System.getenv("APP_MORNING_SUMMARY_TIME"), properties.getProperty("app.morning.summary.time", "08:00"));
        String briefTime    = firstNonBlank(System.getenv("MORNING_BRIEF_TIME"), properties.getProperty("morning.brief.time", "08:00"));
        String moodTime     = firstNonBlank(System.getenv("MOOD_CHECKIN_TIME"), properties.getProperty("mood.checkin.time", "21:00"));
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
                notionJrnlKey  != null ? notionJrnlKey.trim()  : null,
                notionJrnlId   != null ? notionJrnlId.trim()   : null,
                googleCreds    != null ? googleCreds.trim()    : null,
                googleTokens   != null ? googleTokens.trim()   : "data/google_tokens",
                allowedUser    != null ? Long.parseLong(allowedUser.trim()) : 0L,
                groupChat      != null ? Long.parseLong(groupChat.trim())  : 0L,
                ZoneId.of(timezone.trim()), LocalTime.parse(morningTime.trim()),
                LocalTime.parse(briefTime.trim()), LocalTime.parse(moodTime.trim()),
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
    public String getNotionJournalApiKey()        { return notionJournalApiKey; }
    public String getNotionJournalDbId()         { return notionJournalDbId; }
    public String getGoogleCredentialsPath()     { return googleCredentialsPath; }
    public String getGoogleTokensPath()          { return googleTokensPath; }
    public long getAllowedUserId()                { return allowedUserId; }
    public long getGroupChatId()                 { return groupChatId; }
    public ZoneId getZoneId()                    { return zoneId; }
    public LocalTime getMorningSummaryTime()      { return morningSummaryTime; }
    public LocalTime getMorningBriefTime()        { return morningBriefTime; }
    public LocalTime getMoodCheckinTime()         { return moodCheckinTime; }
    public int getSchedulerCheckIntervalSeconds() { return schedulerCheckIntervalSeconds; }
    public String getDbPath()                    { return dbPath; }
    public int getDefaultStaleDays()             { return defaultStaleDays; }
}
