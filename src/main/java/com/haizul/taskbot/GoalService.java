package com.haizul.taskbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GoalService {

    public record Goal(int id, String title, LocalDate targetDate, String status, LocalDateTime createdAt) {}

    private static final DateTimeFormatter STORE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Database database;
    private final ZoneId zoneId;

    public GoalService(Database database, ZoneId zoneId) {
        this.database = database;
        this.zoneId = zoneId;
    }

    // ── Create ───────────────────────────────────────────────────────────────

    public Goal createGoal(long userId, String title, LocalDate targetDate) {
        String sql = """
                INSERT INTO goals (user_id, title, target_date, status, created_at)
                VALUES (?, ?, ?, 'active', ?)
                """;
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, targetDate != null ? targetDate.format(DATE_FMT) : null);
            ps.setString(4, now.format(STORE_FMT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    return new Goal(id, title, targetDate, "active", now);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to create goal: " + e.getMessage());
        }
        return null;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<Goal> getActiveGoals(long userId) {
        String sql = """
                SELECT id, title, target_date, status, created_at
                FROM goals
                WHERE user_id = ? AND status = 'active'
                ORDER BY target_date IS NULL, target_date ASC
                """;
        List<Goal> goals = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    goals.add(mapGoal(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get active goals: " + e.getMessage());
        }
        return goals;
    }

    public Optional<Goal> findGoalByHint(long userId, String hint) {
        String sql = """
                SELECT id, title, target_date, status, created_at
                FROM goals
                WHERE user_id = ? AND status = 'active' AND LOWER(title) LIKE ?
                LIMIT 1
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, "%" + hint.toLowerCase() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapGoal(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to find goal by hint: " + e.getMessage());
        }
        return Optional.empty();
    }

    // ── Update ───────────────────────────────────────────────────────────────

    public void completeGoal(int goalId) {
        String sql = "UPDATE goals SET status = 'completed', completed_at = ? WHERE id = ?";
        LocalDateTime now = LocalDateTime.now(zoneId);
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, now.format(STORE_FMT));
            ps.setInt(2, goalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to complete goal: " + e.getMessage());
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void deleteGoal(int goalId) {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM goal_task_links WHERE goal_id = ?")) {
                    ps.setInt(1, goalId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM goals WHERE id = ?")) {
                    ps.setInt(1, goalId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                System.err.println("Failed to delete goal (rolled back): " + e.getMessage());
            }
        } catch (SQLException e) {
            System.err.println("Failed to open connection for deleteGoal: " + e.getMessage());
        }
    }

    // ── Task linking ─────────────────────────────────────────────────────────

    public void linkTask(int goalId, String taskId) {
        String sql = "INSERT OR IGNORE INTO goal_task_links (goal_id, task_id) VALUES (?, ?)";
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, goalId);
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to link task to goal: " + e.getMessage());
        }
    }

    public void unlinkTask(int goalId, String taskId) {
        String sql = "DELETE FROM goal_task_links WHERE goal_id = ? AND task_id = ?";
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, goalId);
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to unlink task from goal: " + e.getMessage());
        }
    }

    public List<String> getLinkedTaskIds(int goalId) {
        String sql = "SELECT task_id FROM goal_task_links WHERE goal_id = ?";
        List<String> ids = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, goalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("task_id"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get linked task IDs: " + e.getMessage());
        }
        return ids;
    }

    public Optional<Goal> getGoalForTask(String taskId) {
        String sql = """
                SELECT g.id, g.title, g.target_date, g.status, g.created_at
                FROM goals g
                JOIN goal_task_links l ON g.id = l.goal_id
                WHERE l.task_id = ?
                LIMIT 1
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapGoal(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get goal for task: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns {total, completed} counts for tasks linked to the given goal.
     * A task counts as completed if its status column equals 'DONE'.
     */
    public int[] countLinkedTasks(int goalId) {
        String sql = """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END) AS completed
                FROM goal_task_links l
                JOIN tasks t ON t.id = l.task_id
                WHERE l.goal_id = ?
                """;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, goalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new int[]{rs.getInt("total"), rs.getInt("completed")};
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to count linked tasks: " + e.getMessage());
        }
        return new int[]{0, 0};
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    public String formatGoal(Goal goal) {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83C\uDFAF ").append(goal.title());

        // Days until target
        if (goal.targetDate() != null) {
            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(zoneId), goal.targetDate());
            if (daysLeft > 0) {
                sb.append(" — ").append(daysLeft).append(" day").append(daysLeft == 1 ? "" : "s").append(" left");
            } else if (daysLeft == 0) {
                sb.append(" — due today");
            } else {
                sb.append(" — ").append(Math.abs(daysLeft)).append(" day").append(Math.abs(daysLeft) == 1 ? "" : "s").append(" overdue");
            }
        }

        // Linked task counts and progress bar
        int[] counts = countLinkedTasks(goal.id());
        int total = counts[0];
        int completed = counts[1];

        sb.append("\n   Tasks: ").append(completed).append("/").append(total);

        double progress = total > 0 ? (double) completed / total : 0.0;
        int filled = (int) Math.round(progress * 8);
        int empty = 8 - filled;
        sb.append("  ");
        sb.append("\u2588".repeat(filled));
        sb.append("\u2591".repeat(empty));
        sb.append(" ").append((int) Math.round(progress * 100)).append("%");

        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Goal mapGoal(ResultSet rs) throws SQLException {
        String targetStr = rs.getString("target_date");
        LocalDate targetDate = targetStr != null ? LocalDate.parse(targetStr, DATE_FMT) : null;

        String createdStr = rs.getString("created_at");
        LocalDateTime createdAt = LocalDateTime.parse(createdStr, STORE_FMT);

        return new Goal(
                rs.getInt("id"),
                rs.getString("title"),
                targetDate,
                rs.getString("status"),
                createdAt
        );
    }
}
