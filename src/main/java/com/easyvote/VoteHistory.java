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
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerName.toLowerCase());
            ps.setString(2, serviceName);
            ps.setString(3, address);
            ps.setLong(4, timestamp);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    public int getPlayerVoteCount(String playerName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ?";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerName.toLowerCase());
            ResultSet rs = ps.executeQuery();
            int result = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            ps.close();
            return result;
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getServiceVoteCount(String serviceName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE service_name = ?";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, serviceName.toLowerCase());
            ResultSet rs = ps.executeQuery();
            int result = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            ps.close();
            return result;
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getTotalVotes() {
        String sql = "SELECT COUNT(*) FROM votes";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            int result = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            ps.close();
            return result;
        } catch (SQLException ignored) {}
        return 0;
    }

    public Map<String, Integer> getPlayerVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT player_name, COUNT(*) FROM votes GROUP BY player_name";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
            rs.close();
            ps.close();
        } catch (SQLException ignored) {}
        return result;
    }

    public Map<String, Integer> getServiceVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT service_name, COUNT(*) FROM votes GROUP BY service_name";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
            rs.close();
            ps.close();
        } catch (SQLException ignored) {}
        return result;
    }

    public List<VoteRecord> getRecentVotes(int limit) {
        List<VoteRecord> result = new ArrayList<>();
        String sql = "SELECT player_name, service_name, address, timestamp FROM votes ORDER BY id DESC LIMIT ?";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new VoteRecord(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getLong(4)
                ));
            }
            rs.close();
            ps.close();
        } catch (SQLException ignored) {}
        return result;
    }

    public boolean isFirstVoteForService(String playerName, String serviceName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ? AND service_name = ?";
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerName.toLowerCase());
            ps.setString(2, serviceName);
            ResultSet rs = ps.executeQuery();
            boolean result = rs.next() && rs.getInt(1) == 0;
            rs.close();
            ps.close();
            return result;
        } catch (SQLException ignored) {}
        return false;
    }

    public void clear() {
        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement("DELETE FROM votes");
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    public int clearPlayerVotes(String playerName) {
        try {
            Connection conn = db.getConnection();
            PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) FROM votes WHERE player_name = ?");
            countPs.setString(1, playerName.toLowerCase());
            ResultSet rs = countPs.executeQuery();
            int count = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            countPs.close();

            PreparedStatement deletePs = conn.prepareStatement("DELETE FROM votes WHERE player_name = ?");
            deletePs.setString(1, playerName.toLowerCase());
            deletePs.executeUpdate();
            deletePs.close();
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
