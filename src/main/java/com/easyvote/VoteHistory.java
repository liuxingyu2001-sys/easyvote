package com.easyvote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VoteHistory {

    private final DatabaseManager db;
    private final Clock clock;

    public VoteHistory(DatabaseManager db) {
        this(db, Clock.systemDefaultZone());
    }

    VoteHistory(DatabaseManager db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Record the vote and its reward together, before any scheduler can be cancelled. */
    public boolean recordVoteAndQueue(String playerName, String serviceName, String address, long timestamp) {
        return recordVoteAndQueue(playerName, serviceName, address, timestamp, 0) == VoteResult.FIRST_VOTE;
    }

    public enum VoteResult { FIRST_VOTE, ACCEPTED, DAILY_LIMIT_REACHED }

    public VoteResult recordVoteAndQueue(String playerName, String serviceName, String address,
                                        long timestamp, int dailyLimit) {
        if (dailyLimit < 0) throw new IllegalArgumentException("dailyLimit must be non-negative");
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                conn.setAutoCommit(false);
                try {
                    var received = clock.instant();
                    var day = received.atZone(clock.getZone()).toLocalDate();
                    long start = day.atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
                    long end = day.plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
                    if (dailyLimit > 0 && getPlayerVoteCountBetween(playerName, start, end) >= dailyLimit) {
                        conn.rollback();
                        return VoteResult.DAILY_LIMIT_REACHED;
                    }
                    boolean firstVote = getPlayerVoteCount(playerName) == 0;
                    addVote(playerName, serviceName, address, timestamp, received.toEpochMilli());
                    addPendingReward(playerName, serviceName, address, timestamp, firstVote);
                    conn.commit();
                    return firstVote ? VoteResult.FIRST_VOTE : VoteResult.ACCEPTED;
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("保存投票及待发奖励失败", e);
            }
        }
    }

    private int getPlayerVoteCountBetween(String playerName, long start, long end) throws SQLException {
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM votes WHERE player_name = ? AND received_at >= ? AND received_at < ?")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            ps.setLong(2, start);
            ps.setLong(3, end);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int getPendingRewardCount(String playerName) {
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM pending_rewards WHERE player_name = ?")) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询待发奖励失败", e);
            }
        }
    }

    public RewardProgress getRewardProgress(String playerName, String rewardKey, List<String> commands) {
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO reward_progress (player_name, reward_key, commands) VALUES (?, ?, ?)")) {
                    ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                    ps.setString(2, rewardKey);
                    ps.setString(3, org.json.simple.JSONValue.toJSONString(commands));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT commands, next_command FROM reward_progress WHERE player_name = ? AND reward_key = ?")) {
                    ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                    ps.setString(2, rewardKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("奖励进度不存在");
                        var saved = (org.json.simple.JSONArray) new org.json.simple.parser.JSONParser().parse(rs.getString(1));
                        List<String> snapshot = new ArrayList<>();
                        for (Object command : saved) snapshot.add((String) command);
                        int nextCommand = rs.getInt(2);
                        // Allow an admin to repair an unexecuted command without replaying the completed prefix.
                        if (!snapshot.equals(commands) && nextCommand <= commands.size()
                                && snapshot.subList(0, nextCommand).equals(commands.subList(0, nextCommand))) {
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE reward_progress SET commands = ? WHERE player_name = ? AND reward_key = ?")) {
                                update.setString(1, org.json.simple.JSONValue.toJSONString(commands));
                                update.setString(2, playerName.toLowerCase(Locale.ROOT));
                                update.setString(3, rewardKey);
                                update.executeUpdate();
                            }
                            snapshot = commands;
                        }
                        return new RewardProgress(List.copyOf(snapshot), nextCommand);
                    }
                }
            } catch (SQLException | org.json.simple.parser.ParseException e) {
                throw new IllegalStateException("读取奖励进度失败", e);
            }
        }
    }

    public void saveRewardProgress(String playerName, String rewardKey, int nextCommand) {
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE reward_progress SET next_command = ? WHERE player_name = ? AND reward_key = ?")) {
                ps.setInt(1, nextCommand);
                ps.setString(2, playerName.toLowerCase(Locale.ROOT));
                ps.setString(3, rewardKey);
                if (ps.executeUpdate() != 1) throw new SQLException("奖励进度不存在");
            } catch (SQLException e) {
                throw new IllegalStateException("保存奖励进度失败", e);
            }
        }
    }

    public record RewardProgress(List<String> commands, int nextCommand) {}

    public void addVote(String playerName, String serviceName, String address, long timestamp) {
        addVote(playerName, serviceName, address, timestamp, clock.millis());
    }

    private void addVote(String playerName, String serviceName, String address, long timestamp, long receivedAt) {
        synchronized (db) {
            String sql = "INSERT INTO votes (player_name, service_name, address, timestamp, received_at) VALUES (?, ?, ?, ?, ?)";
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                    ps.setString(2, serviceName);
                    ps.setString(3, address);
                    ps.setLong(4, timestamp);
                    ps.setLong(5, receivedAt);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public int getPlayerVoteCount(String playerName) {
        synchronized (db) {
            String sql = "SELECT COUNT(*) FROM votes WHERE player_name = ?";
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public int getServiceVoteCount(String serviceName) {
        synchronized (db) {
            String sql = "SELECT COUNT(*) FROM votes WHERE service_name = ?";
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, serviceName);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public int getTotalVotes() {
        synchronized (db) {
            String sql = "SELECT COUNT(*) FROM votes";
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public Map<String, Integer> getPlayerVoteCounts() {
        synchronized (db) {
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
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
            return result;
        }
    }

    public Map<String, Integer> getServiceVoteCounts() {
        synchronized (db) {
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
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
            return result;
        }
    }

    public List<VoteRecord> getRecentVotes(int limit) {
        synchronized (db) {
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
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
            return result;
        }
    }

    public boolean isFirstVoteForService(String playerName, String serviceName) {
        synchronized (db) {
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
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public void clear() {
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement votes = conn.prepareStatement("DELETE FROM votes");
                     PreparedStatement milestones = conn.prepareStatement("DELETE FROM milestones");
                     PreparedStatement pending = conn.prepareStatement("DELETE FROM pending_rewards");
                     PreparedStatement progress = conn.prepareStatement("DELETE FROM reward_progress")) {
                    votes.executeUpdate();
                    milestones.executeUpdate();
                    pending.executeUpdate();
                    progress.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public int clearPlayerVotes(String playerName) {
        synchronized (db) {
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

                try (PreparedStatement deleteProgress = conn.prepareStatement("DELETE FROM reward_progress WHERE player_name = ?")) {
                    deleteProgress.setString(1, playerLower);
                    deleteProgress.executeUpdate();
                }

                return count;
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public void addPendingReward(String playerName, String serviceName, String address, long timestamp, boolean firstVote) {
        synchronized (db) {
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
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public List<PendingReward> getPendingRewards(String playerName) {
        synchronized (db) {
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
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
            return result;
        }
    }

    public void deletePendingReward(int id) {
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM pending_rewards WHERE id = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM reward_progress WHERE reward_key = ?")) {
                    ps.setString(1, "vote:" + id);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
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
