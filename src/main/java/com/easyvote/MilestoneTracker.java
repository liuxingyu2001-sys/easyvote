package com.easyvote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerName.toLowerCase());
            ps.setInt(2, count);
            ps.setLong(3, timestamp);
            boolean result = ps.executeUpdate() > 0;
            ps.close();
            return result;
        } catch (SQLException e) {
            return false;
        }
    }

    public int getPlayerHighestMilestone(String playerName) {
        String sql = "SELECT MAX(count) FROM milestones WHERE player_name = ?";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerName.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int val = rs.getInt(1);
                boolean wasNull = rs.wasNull();
                rs.close();
                ps.close();
                return wasNull ? 0 : val;
            }
            rs.close();
            ps.close();
        } catch (SQLException ignored) {}
        return 0;
    }
}
