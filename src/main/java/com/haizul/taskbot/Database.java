package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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

    private void migrate(String sql) {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException ignored) {}
    }
}
