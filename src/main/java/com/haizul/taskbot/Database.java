package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Database {
    private final String jdbcUrl;

    public Database(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void initialize() {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT PRIMARY KEY,
                        user_id INTEGER NOT NULL,
                        chat_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        category TEXT,
                        due_at TEXT,
                        status TEXT NOT NULL,
                        recurrence TEXT NOT NULL,
                        stale_after_days INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        reminder_stage INTEGER DEFAULT 0,
                        last_reminder_at TEXT,
                        stale_notified_at TEXT
                    )
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        UNIQUE(user_id, name)
                    )
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS user_settings (
                        user_id INTEGER PRIMARY KEY,
                        quiet_start TEXT,
                        quiet_end TEXT,
                        weekly_digest INTEGER DEFAULT 1,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        title TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        category TEXT NOT NULL,
                        recurrence TEXT NOT NULL,
                        notes TEXT,
                        created_at TEXT NOT NULL,
                        UNIQUE(user_id, name)
                    )
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS habit_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT NOT NULL,
                        user_id INTEGER NOT NULL,
                        completed_at TEXT NOT NULL
                    )
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS focus_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        chat_id INTEGER NOT NULL,
                        task_title TEXT NOT NULL,
                        duration_minutes INTEGER NOT NULL,
                        started_at TEXT NOT NULL,
                        ends_at TEXT NOT NULL,
                        completed INTEGER DEFAULT 0,
                        notified INTEGER DEFAULT 0
                    )
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS conversation_history (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id    INTEGER NOT NULL,
                        role       TEXT    NOT NULL,
                        content    TEXT    NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                    )
                    """);
            s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_conv_user ON conversation_history(user_id, id)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }

        // Safe migrations
        migrate("ALTER TABLE tasks ADD COLUMN notes TEXT");
        migrate("ALTER TABLE tasks ADD COLUMN reminder_interval_minutes INTEGER");
        migrate("ALTER TABLE tasks ADD COLUMN repeat_reminder INTEGER DEFAULT 0");
        migrate("ALTER TABLE tasks ADD COLUMN is_habit INTEGER DEFAULT 0");
        migrate("ALTER TABLE tasks ADD COLUMN reminder_ignored_count INTEGER DEFAULT 0");
        migrate("ALTER TABLE tasks ADD COLUMN reminder_lat REAL");
        migrate("ALTER TABLE user_settings ADD COLUMN pomodoro_state TEXT");
        migrate("ALTER TABLE tasks ADD COLUMN reminder_lng REAL");
        migrate("ALTER TABLE tasks ADD COLUMN reminder_radius_meters INTEGER DEFAULT 200");
    }

    // ── Conversation history ──────────────────────────────────────────────────

    /** Append a message to the persisted conversation history for a user. */
    public void appendConversation(long userId, String role, String content) {
        String sql = "INSERT INTO conversation_history (user_id, role, content) VALUES (?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to save conversation: " + e.getMessage());
        }
        // Keep only the last MAX rows per user (trim old history automatically)
        pruneConversation(userId, 40);
    }

    /** Load the most recent messages for a user, oldest-first, capped at limit. */
    public List<Map<String, Object>> loadConversation(long userId, int limit) {
        String sql = """
                SELECT role, content FROM conversation_history
                WHERE user_id = ?
                ORDER BY id DESC LIMIT ?
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role",    rs.getString("role"));
                row.put("content", rs.getString("content"));
                rows.add(row);
            }
        } catch (SQLException e) {
            System.err.println("Failed to load conversation: " + e.getMessage());
        }
        // Reverse so oldest message is first (Claude expects chronological order)
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** Delete all conversation history older than the newest keepCount rows for this user. */
    private void pruneConversation(long userId, int keepCount) {
        String sql = """
                DELETE FROM conversation_history
                WHERE user_id = ? AND id NOT IN (
                    SELECT id FROM conversation_history WHERE user_id = ? ORDER BY id DESC LIMIT ?
                )
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.setInt(3, keepCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to prune conversation: " + e.getMessage());
        }
    }

    // ── Migrations ────────────────────────────────────────────────────────────

    private void migrate(String sql) {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException ignored) {}
    }
}
