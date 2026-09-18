package com.easyvote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setString(2, serviceName);
                ps.setString(3, address);
                ps.setLong(4, timestamp);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    public int getPlayerVoteCount(String playerName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ?";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getServiceVoteCount(String serviceName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE service_name = ?";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serviceName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getTotalVotes() {
        String sql = "SELECT COUNT(*) FROM votes";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public Map<String, Integer> getPlayerVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT player_name, COUNT(*) FROM votes GROUP BY player_name";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public Map<String, Integer> getServiceVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT service_name, COUNT(*) FROM votes GROUP BY service_name";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public List<VoteRecord> getRecentVotes(int limit) {
        List<VoteRecord> result = new ArrayList<>();
        String sql = "SELECT player_name, service_name, address, timestamp FROM votes ORDER BY id DESC LIMIT ?";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public boolean isFirstVoteForService(String playerName, String serviceName) {
        String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ? AND service_name = ?";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setString(2, serviceName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 0;
                }
            }
        } catch (SQLException ignored) {}
        return false;
    }

    public void clear() {
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement votes = conn.prepareStatement("DELETE FROM votes");
                 PreparedStatement milestones = conn.prepareStatement("DELETE FROM milestones");
                 PreparedStatement pending = conn.prepareStatement("DELETE FROM pending_rewards")) {
                votes.executeUpdate();
                milestones.executeUpdate();
                pending.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    public int clearPlayerVotes(String playerName) {
        String playerLower = playerName.toLowerCase(Locale.ROOT);
        try {
            Connection conn = db.getConnection();
            int count;
            try (PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) FROM votes WHERE player_name = ?")) {
                countPs.setString(1, playerLower);
                try (ResultSet rs = countPs.executeQuery()) {
                    count = rs.next() ? rs.getInt(1) : 0;
                }
            }

            try (PreparedStatement deletePs = conn.prepareStatement("DELETE FROM votes WHERE player_name = ?")) {
                deletePs.setString(1, playerLower);
                deletePs.executeUpdate();
            }

            try (PreparedStatement deleteMilestones = conn.prepareStatement("DELETE FROM milestones WHERE player_name = ?")) {
                deleteMilestones.setString(1, playerLower);
                deleteMilestones.executeUpdate();
            }

            try (PreparedStatement deletePending = conn.prepareStatement("DELETE FROM pending_rewards WHERE player_name = ?")) {
                deletePending.setString(1, playerLower);
                deletePending.executeUpdate();
            }

            return count;
        } catch (SQLException ignored) {}
        return 0;
    }

    public void addPendingReward(String playerName, String serviceName, String address, long timestamp, boolean firstVote) {
        String sql = "INSERT INTO pending_rewards (player_name, service_name, address, timestamp, first_vote) VALUES (?, ?, ?, ?, ?)";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setString(2, serviceName);
                ps.setString(3, address);
                ps.setLong(4, timestamp);
                ps.setInt(5, firstVote ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    public List<PendingReward> getPendingRewards(String playerName) {
        List<PendingReward> result = new ArrayList<>();
        String sql = "SELECT id, service_name, address, timestamp, first_vote FROM pending_rewards WHERE player_name = ? ORDER BY id";
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new PendingReward(
                            rs.getInt(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getLong(4),
                            rs.getInt(5) == 1
                        ));
                    }
                }
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public void deletePendingReward(int id) {
        try {
            Connection conn = db.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM pending_rewards WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    public static class PendingReward {
        private final int id;
        private final String serviceName;
        private final String address;
        private final long timestamp;
        private final boolean firstVote;

        public PendingReward(int id, String serviceName, String address, long timestamp, boolean firstVote) {
            this.id = id;
            this.serviceName = serviceName;
            this.address = address;
            this.timestamp = timestamp;
            this.firstVote = firstVote;
        }

        public int getId() { return id; }
        public String getServiceName() { return serviceName; }
        public String getAddress() { return address; }
        public long getTimestamp() { return timestamp; }
        public boolean isFirstVote() { return firstVote; }
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
