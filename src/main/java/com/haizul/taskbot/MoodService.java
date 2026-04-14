package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public class MoodService {

    public record MoodEntry(String date, Integer mood, Integer energy) {}

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Database database;
    private final ZoneId zoneId;

    public MoodService(Database database, ZoneId zoneId) {
        this.database = database;
        this.zoneId = zoneId;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Upsert today's mood (and optional energy) for the given user.
     * Mood must be 1-5, energy must be 1-5 or null.
     */
    public MoodEntry logMood(long userId, int mood, Integer energy) {
        if (mood < 1 || mood > 5) {
            throw new IllegalArgumentException("Mood must be between 1 and 5, got " + mood);
        }
        if (energy != null && (energy < 1 || energy > 5)) {
            throw new IllegalArgumentException("Energy must be between 1 and 5, got " + energy);
        }

        String today = today();
        String now = Instant.now().toString();

        String sql = """
                INSERT INTO mood_log (user_id, date, mood, energy, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id, date) DO UPDATE
                    SET mood = excluded.mood,
                        energy = excluded.energy
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, today);
            ps.setInt(3, mood);
            if (energy != null) {
                ps.setInt(4, energy);
            } else {
                ps.setNull(4, java.sql.Types.INTEGER);
            }
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to log mood", e);
        }

        return new MoodEntry(today, mood, energy);
    }

    /** Get today's mood entry for the user, if one exists. */
    public Optional<MoodEntry> getToday(long userId) {
        String today = today();
        String sql = "SELECT date, mood, energy FROM mood_log WHERE user_id = ? AND date = ?";
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, today);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(extractEntry(rs));
            }
        } catch (SQLException e) {
            System.err.println("Failed to get today's mood: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Get the most recent N days of mood entries, ordered by date descending. */
    public List<MoodEntry> getRecent(long userId, int days) {
        String since = LocalDate.now(zoneId).minusDays(days - 1).format(DATE_FMT);
        String sql = """
                SELECT date, mood, energy FROM mood_log
                WHERE user_id = ? AND date >= ?
                ORDER BY date DESC
                """;
        List<MoodEntry> entries = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, since);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entries.add(extractEntry(rs));
            }
        } catch (SQLException e) {
            System.err.println("Failed to get recent mood entries: " + e.getMessage());
        }
        return entries;
    }

    /** Average mood over the last N days. */
    public OptionalDouble getAverageMood(long userId, int days) {
        String since = LocalDate.now(zoneId).minusDays(days - 1).format(DATE_FMT);
        String sql = """
                SELECT AVG(CAST(mood AS REAL)) AS avg_mood FROM mood_log
                WHERE user_id = ? AND date >= ? AND mood IS NOT NULL
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, since);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                double val = rs.getDouble("avg_mood");
                if (!rs.wasNull()) {
                    return OptionalDouble.of(val);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get average mood: " + e.getMessage());
        }
        return OptionalDouble.empty();
    }

    /** Average energy over the last N days. */
    public OptionalDouble getAverageEnergy(long userId, int days) {
        String since = LocalDate.now(zoneId).minusDays(days - 1).format(DATE_FMT);
        String sql = """
                SELECT AVG(CAST(energy AS REAL)) AS avg_energy FROM mood_log
                WHERE user_id = ? AND date >= ? AND energy IS NOT NULL
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, since);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                double val = rs.getDouble("avg_energy");
                if (!rs.wasNull()) {
                    return OptionalDouble.of(val);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get average energy: " + e.getMessage());
        }
        return OptionalDouble.empty();
    }

    /**
     * Returns true if the user has logged mood for the last N consecutive days
     * and every one of those entries has mood <= 2.
     */
    public boolean isLowMoodStreak(long userId, int consecutiveDays) {
        String since = LocalDate.now(zoneId).minusDays(consecutiveDays - 1).format(DATE_FMT);
        String sql = """
                SELECT COUNT(*) AS cnt, MIN(mood) AS min_mood, MAX(mood) AS max_mood
                FROM mood_log
                WHERE user_id = ? AND date >= ? AND mood IS NOT NULL
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, since);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int count = rs.getInt("cnt");
                int maxMood = rs.getInt("max_mood");
                return count >= consecutiveDays && maxMood <= 2;
            }
        } catch (SQLException e) {
            System.err.println("Failed to check low mood streak: " + e.getMessage());
        }
        return false;
    }

    /**
     * Returns a short note for the morning brief based on the last 3 days of mood,
     * or null if there is nothing noteworthy.
     */
    public String getMoodTrendNote(long userId) {
        OptionalDouble avg = getAverageMood(userId, 3);
        if (avg.isEmpty()) {
            return null;
        }
        double avgMood = avg.getAsDouble();
        if (avgMood <= 2.5) {
            return "You've been feeling low lately. Focus on what matters most today. \uD83E\uDEF6";
        }
        if (avgMood >= 4) {
            return "You're on a roll! Keep the momentum going. \uD83D\uDCAA";
        }
        return null;
    }

    /**
     * Format a MoodEntry as a user-facing string.
     * Energy part is omitted when energy is null.
     */
    public String formatMoodLog(MoodEntry e) {
        if (e.energy() != null) {
            return "\uD83D\uDCCA Mood: " + e.mood() + "/5 · Energy: " + e.energy() + "/5";
        }
        return "\uD83D\uDCCA Mood: " + e.mood() + "/5";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String today() {
        return LocalDate.now(zoneId).format(DATE_FMT);
    }

    private MoodEntry extractEntry(ResultSet rs) throws SQLException {
        String date = rs.getString("date");
        int mood = rs.getInt("mood");
        Integer moodVal = rs.wasNull() ? null : mood;
        int energy = rs.getInt("energy");
        Integer energyVal = rs.wasNull() ? null : energy;
        return new MoodEntry(date, moodVal, energyVal);
    }
}
