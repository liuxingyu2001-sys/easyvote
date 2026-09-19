package com.easyvote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class MilestoneTracker {

    private final DatabaseManager db;

    public MilestoneTracker(DatabaseManager db) {
        this.db = db;
    }

    public boolean hasReceived(String playerName, int count) {
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT 1 FROM milestones WHERE player_name = ? AND count = ?")) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setInt(2, count);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询里程碑失败", e);
            }
        }
    }

    /**
     * Atomically check and mark a milestone. Returns true if this call
     * actually marked it (first time), false if it was already received.
     */
    public boolean markIfNotReceived(String playerName, int count, long timestamp) {
        synchronized (db) {
            String sql = "INSERT OR IGNORE INTO milestones (player_name, count, timestamp) VALUES (?, ?, ?)";
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                    ps.setInt(2, count);
                    ps.setLong(3, timestamp);
                    return ps.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("保存里程碑失败", e);
            }
        }
    }

    public int getPlayerHighestMilestone(String playerName) {
        synchronized (db) {
            String sql = "SELECT MAX(count) FROM milestones WHERE player_name = ?";
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int val = rs.getInt(1);
                            return rs.wasNull() ? 0 : val;
                        }
                    }
                }
                return 0;
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }
}
