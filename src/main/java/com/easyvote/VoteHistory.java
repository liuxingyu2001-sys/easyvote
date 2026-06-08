package com.easyvote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VoteHistory {

    private final DatabaseManager db;

    public VoteHistory(DatabaseManager db) {
        this.db = db;
    }

    public void addVote(String playerName, String serviceName, String address, long timestamp) {
        String sql = "INSERT INTO votes (player_name, service_name, address, timestamp) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName.toLowerCase());
            ps.setString(2, serviceName);
            ps.setString(3, address);
            ps.setLong(4, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            // silently ignore — vote is already counted in-memory via old path, or will be caught later
        }
    }

    public int getPlayerVoteCount(String playerName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getServiceVoteCount(String serviceName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE service_name = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serviceName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getTotalVotes() {
        String sql = "SELECT COUNT(*) FROM votes";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public Map<String, Integer> getPlayerVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT player_name, COUNT(*) FROM votes GROUP BY player_name";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public Map<String, Integer> getServiceVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT service_name, COUNT(*) FROM votes GROUP BY service_name";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public List<VoteRecord> getRecentVotes(int limit) {
        List<VoteRecord> result = new ArrayList<>();
        String sql = "SELECT player_name, service_name, address, timestamp FROM votes ORDER BY id DESC LIMIT ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new VoteRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4)
                    ));
                }
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public boolean isFirstVoteForService(String playerName, String serviceName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ? AND service_name = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName.toLowerCase());
            ps.setString(2, serviceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) == 0;
            }
        } catch (SQLException ignored) {}
        return true;
    }

    public void clear() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM votes")) {
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public int clearPlayerVotes(String playerName) {
        String countSql = "SELECT COUNT(*) FROM votes WHERE player_name = ?";
        String deleteSql = "DELETE FROM votes WHERE player_name = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement countPs = conn.prepareStatement(countSql)) {
            countPs.setString(1, playerName.toLowerCase());
            int count = 0;
            try (ResultSet rs = countPs.executeQuery()) {
                if (rs.next()) count = rs.getInt(1);
            }
            try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                deletePs.setString(1, playerName.toLowerCase());
                deletePs.executeUpdate();
            }
            return count;
        } catch (SQLException ignored) {}
        return 0;
    }

    public static class VoteRecord {
        private final String playerName;
        private final String serviceName;
        private final String address;
        private final long timestamp;

        public VoteRecord(String playerName, String serviceName, String address, long timestamp) {
            this.playerName = playerName;
            this.serviceName = serviceName;
            this.address = address;
            this.timestamp = timestamp;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getAddress() {
            return address;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
