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

    /**
     * Atomically check and mark a milestone. Returns true if this call
     * actually marked it (first time), false if it was already received.
     */
    public boolean markIfNotReceived(String playerName, int count, long timestamp) {
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
            return false;
        }
    }

    public int getPlayerHighestMilestone(String playerName) {
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
        } catch (SQLException ignored) {}
        return 0;
    }
}
