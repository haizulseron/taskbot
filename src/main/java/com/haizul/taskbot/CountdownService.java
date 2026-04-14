package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class CountdownService {

    private final Database database;
    private final ZoneId zoneId;

    public CountdownService(Database database, ZoneId zoneId) {
        this.database = database;
        this.zoneId = zoneId;
    }

    public ZoneId getZoneId() { return zoneId; }

    public record Countdown(int id, String name, LocalDate targetDate, long daysRemaining) {}

    public Countdown addCountdown(long userId, String name, LocalDate targetDate) {
        String sql = "INSERT INTO countdowns (user_id, name, target_date, created_at) VALUES (?, ?, ?, ?)";
        String now = LocalDate.now(zoneId).toString();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, targetDate.toString());
            ps.setString(4, now);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(zoneId), targetDate);
                return new Countdown(id, name, targetDate, daysRemaining);
            }
        } catch (SQLException e) {
            System.err.println("Failed to add countdown: " + e.getMessage());
        }
        return null;
    }

    public List<Countdown> getCountdowns(long userId) {
        String sql = "SELECT id, name, target_date FROM countdowns WHERE user_id = ? ORDER BY target_date ASC";
        List<Countdown> results = new ArrayList<>();
        LocalDate today = LocalDate.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                LocalDate target = LocalDate.parse(rs.getString("target_date"));
                long daysRemaining = ChronoUnit.DAYS.between(today, target);
                results.add(new Countdown(rs.getInt("id"), rs.getString("name"), target, daysRemaining));
            }
        } catch (SQLException e) {
            System.err.println("Failed to get countdowns: " + e.getMessage());
        }
        return results;
    }

    public void deleteCountdown(int id) {
        String sql = "DELETE FROM countdowns WHERE id = ?";
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to delete countdown: " + e.getMessage());
        }
    }

    public List<Countdown> getUpcomingForBrief(long userId) {
        List<Countdown> all = getCountdowns(userId);
        if (all.isEmpty()) return all;

        List<Countdown> result = new ArrayList<>();
        Countdown nearest = null;

        for (Countdown c : all) {
            if (c.daysRemaining() <= 30) {
                result.add(c);
            }
            if (nearest == null || c.daysRemaining() < nearest.daysRemaining()) {
                nearest = c;
            }
        }

        // Add the nearest one if it wasn't already included (i.e. >30 days away)
        if (nearest != null && nearest.daysRemaining() > 30) {
            result.add(nearest);
        }

        return result;
    }

    public String formatCountdown(Countdown c) {
        if (c.daysRemaining() > 0) {
            return "⏳ " + c.name() + " — " + c.daysRemaining() + " days to go";
        } else if (c.daysRemaining() == 0) {
            return "🎉 " + c.name() + " — TODAY!";
        } else {
            return "✅ " + c.name() + " — completed " + Math.abs(c.daysRemaining()) + " days ago";
        }
    }
}
