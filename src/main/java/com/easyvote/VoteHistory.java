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

/** 投票、待发奖励与奖励命令进度。所有多语句操作复用同一个连接/事务。 */
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

    public enum VoteResult { FIRST_VOTE, ACCEPTED, DAILY_LIMIT_REACHED }

    /** 兼容旧调用：返回本次是否为该玩家的第一次投票。 */
    public boolean recordVoteAndQueue(String playerName, String serviceName, String address, long timestamp) {
        return recordVoteAndQueue(playerName, serviceName, address, timestamp, 0) == VoteResult.FIRST_VOTE;
    }

    /**
     * 记录投票并写入待发奖励。MySQL 下用命名锁串行化同一玩家的并发请求，
     * 保证“首次投票”和“每日上限”在多个子服同时收到投票时仍然只成立一次。
     */
    public VoteResult recordVoteAndQueue(String playerName, String serviceName, String address,
                                        long timestamp, int dailyLimit) {
        if (dailyLimit < 0) throw new IllegalArgumentException("dailyLimit must be non-negative");
        String playerKey = playerName.toLowerCase(Locale.ROOT);
        synchronized (db) {
            try (Connection conn = db.openConnection()) {
                boolean locked = false;
                try {
                    if (db.isMySQL()) {
                        acquireLock(conn, playerKey);
                        locked = true;
                    }
                    conn.setAutoCommit(false);
                    try {
                        var received = clock.instant();
                        var day = received.atZone(clock.getZone()).toLocalDate();
                        long start = day.atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
                        long end = day.plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
                        if (dailyLimit > 0 && getPlayerVoteCountBetween(conn, playerKey, start, end) >= dailyLimit) {
                            conn.rollback();
                            return VoteResult.DAILY_LIMIT_REACHED;
                        }
                        boolean firstVote = count(conn,
                                "SELECT COUNT(*) FROM " + db.table("votes") + " WHERE player_name = ?",
                                playerKey) == 0;
                        insertVote(conn, playerKey, serviceName, address, timestamp, received.toEpochMilli());
                        insertPending(conn, playerKey, serviceName, address, timestamp, firstVote);
                        conn.commit();
                        return firstVote ? VoteResult.FIRST_VOTE : VoteResult.ACCEPTED;
                    } catch (Exception error) {
                        conn.rollback();
                        throw error;
                    } finally {
                        restoreAutoCommit(conn);
                    }
                } finally {
                    if (locked) {
                        // Hikari 会把连接归还池中复用；不显式释放命名锁会让锁一直挂在该
                        // 物理连接上，后续投票最终全部超时。
                        releaseLock(conn, playerKey);
                    }
                }
            } catch (SQLException error) {
                throw new IllegalStateException("保存投票及待发奖励失败", error);
            }
        }
    }

    private boolean acquireLock(Connection conn, String playerKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, 10)")) {
            ps.setString(1, "easyvote:" + playerKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new SQLException("获取玩家投票锁超时: " + playerKey);
                }
                return true;
            }
        }
    }

    private void releaseLock(Connection conn, String playerKey) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, "easyvote:" + playerKey);
            ps.executeQuery();
        } catch (SQLException error) {
            // 连接失效时 MySQL 会在物理关闭时自动释放；这里只记录，不覆盖业务结果。
            db.logger().warning("释放投票命名锁失败: " + error.getMessage());
        }
    }

    private int getPlayerVoteCountBetween(Connection conn, String playerName, long start, long end)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + db.table("votes")
                        + " WHERE player_name = ? AND received_at >= ? AND received_at < ?")) {
            ps.setString(1, playerName);
            ps.setLong(2, start);
            ps.setLong(3, end);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int getPendingRewardCount(String playerName) {
        return countQuery("SELECT COUNT(*) FROM " + db.table("pending_rewards") + " WHERE player_name = ?",
                playerName.toLowerCase(Locale.ROOT));
    }

    public RewardProgress getRewardProgress(String playerName, String rewardKey, List<String> commands) {
        String playerKey = playerName.toLowerCase(Locale.ROOT);
        synchronized (db) {
            try (Connection conn = db.openConnection()) {
                String insert = db.isMySQL()
                        ? "INSERT IGNORE INTO " + db.table("reward_progress")
                        + " (player_name, reward_key, commands) VALUES (?, ?, ?)"
                        : "INSERT OR IGNORE INTO " + db.table("reward_progress")
                        + " (player_name, reward_key, commands) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, playerKey);
                    ps.setString(2, rewardKey);
                    ps.setString(3, org.json.simple.JSONValue.toJSONString(commands));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT commands, next_command FROM " + db.table("reward_progress")
                                + " WHERE player_name = ? AND reward_key = ?")) {
                    ps.setString(1, playerKey);
                    ps.setString(2, rewardKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("奖励进度不存在");
                        var saved = (org.json.simple.JSONArray) new org.json.simple.parser.JSONParser()
                                .parse(rs.getString(1));
                        List<String> snapshot = new ArrayList<>();
                        for (Object command : saved) snapshot.add((String) command);
                        int nextCommand = rs.getInt(2);
                        if (!snapshot.equals(commands) && nextCommand <= commands.size()
                                && snapshot.subList(0, nextCommand).equals(commands.subList(0, nextCommand))) {
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE " + db.table("reward_progress")
                                            + " SET commands = ? WHERE player_name = ? AND reward_key = ?")) {
                                update.setString(1, org.json.simple.JSONValue.toJSONString(commands));
                                update.setString(2, playerKey);
                                update.setString(3, rewardKey);
                                update.executeUpdate();
                            }
                            snapshot = commands;
                        }
                        return new RewardProgress(List.copyOf(snapshot), nextCommand);
                    }
                }
            } catch (SQLException | org.json.simple.parser.ParseException error) {
                throw new IllegalStateException("读取奖励进度失败", error);
            }
        }
    }

    public void saveRewardProgress(String playerName, String rewardKey, int nextCommand) {
        String playerKey = playerName.toLowerCase(Locale.ROOT);
        synchronized (db) {
            try (Connection conn = db.openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE " + db.table("reward_progress") + " SET next_command = ?"
                                 + " WHERE player_name = ? AND reward_key = ?")) {
                ps.setInt(1, nextCommand);
                ps.setString(2, playerKey);
                ps.setString(3, rewardKey);
                if (ps.executeUpdate() != 1) throw new SQLException("奖励进度不存在");
            } catch (SQLException error) {
                throw new IllegalStateException("保存奖励进度失败", error);
            }
        }
    }

    public record RewardProgress(List<String> commands, int nextCommand) {}

    public void addVote(String playerName, String serviceName, String address, long timestamp) {
        try (Connection conn = db.openConnection()) {
            insertVote(conn, playerName.toLowerCase(Locale.ROOT), serviceName, address, timestamp, clock.millis());
        } catch (SQLException error) {
            throw new IllegalStateException("数据库操作失败", error);
        }
    }

    private void insertVote(Connection conn, String playerName, String serviceName, String address,
                            long timestamp, long receivedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + db.table("votes")
                        + " (player_name, service_name, address, timestamp, received_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, playerName);
            ps.setString(2, serviceName);
            ps.setString(3, address);
            ps.setLong(4, timestamp);
            ps.setLong(5, receivedAt);
            ps.executeUpdate();
        }
    }

    public int getPlayerVoteCount(String playerName) {
        return countQuery("SELECT COUNT(*) FROM " + db.table("votes") + " WHERE player_name = ?",
                playerName.toLowerCase(Locale.ROOT));
    }

    public int getServiceVoteCount(String serviceName) {
        return countQuery("SELECT COUNT(*) FROM " + db.table("votes") + " WHERE service_name = ?", serviceName);
    }

    public int getTotalVotes() {
        return countQuery("SELECT COUNT(*) FROM " + db.table("votes"));
    }

    private int countQuery(String sql, Object... params) {
        synchronized (db) {
            try (Connection conn = db.openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }

    public Map<String, Integer> getPlayerVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_name, COUNT(*) FROM " + db.table("votes") + " GROUP BY player_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
        } catch (SQLException error) {
            throw new IllegalStateException("数据库操作失败", error);
        }
        return result;
    }

    public Map<String, Integer> getServiceVoteCounts() {
        Map<String, Integer> result = new HashMap<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT service_name, COUNT(*) FROM " + db.table("votes") + " GROUP BY service_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
        } catch (SQLException error) {
            throw new IllegalStateException("数据库操作失败", error);
        }
        return result;
    }

    /** One-based vote ordinal, ordered by server insertion rather than the site's timestamp. */
    VoteRecord getPlayerVoteAt(String playerName, int ordinal) {
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT service_name, address, timestamp FROM " + db.table("votes")
                             + " WHERE player_name = ? ORDER BY id LIMIT 1 OFFSET ?")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            ps.setInt(2, ordinal - 1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("里程碑对应投票不存在");
                return new VoteRecord(playerName, rs.getString(1), rs.getString(2), rs.getLong(3));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("查询里程碑投票失败", error);
        }
    }

    public List<VoteRecord> getRecentVotes(int limit) {
        List<VoteRecord> result = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_name, service_name, address, timestamp FROM " + db.table("votes")
                             + " ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new VoteRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("数据库操作失败", error);
        }
        return result;
    }

    public boolean isFirstVoteForService(String playerName, String serviceName) {
        synchronized (db) {
            try (Connection conn = db.openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COUNT(*) FROM " + db.table("votes") + " WHERE player_name = ? AND service_name = ?")) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setString(2, serviceName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 0;
                }
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }

    public void clear() {
        synchronized (db) {
            try (Connection conn = db.openConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement votes = conn.prepareStatement("DELETE FROM " + db.table("votes"));
                     PreparedStatement milestones = conn.prepareStatement("DELETE FROM " + db.table("milestones"));
                     PreparedStatement pending = conn.prepareStatement("DELETE FROM " + db.table("pending_rewards"));
                     PreparedStatement progress = conn.prepareStatement("DELETE FROM " + db.table("reward_progress"))) {
                    votes.executeUpdate();
                    milestones.executeUpdate();
                    pending.executeUpdate();
                    progress.executeUpdate();
                }
                conn.commit();
                restoreAutoCommit(conn);
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }

    public int clearPlayerVotes(String playerName) {
        String playerLower = playerName.toLowerCase(Locale.ROOT);
        synchronized (db) {
            try (Connection conn = db.openConnection()) {
                conn.setAutoCommit(false);
                try {
                    int count = count(conn,
                            "SELECT COUNT(*) FROM " + db.table("votes") + " WHERE player_name = ?", playerLower);
                    String[] tables = {"votes", "milestones", "pending_rewards", "reward_progress"};
                    for (String table : tables) {
                        try (PreparedStatement delete = conn.prepareStatement(
                                "DELETE FROM " + db.table(table) + " WHERE player_name = ?")) {
                            delete.setString(1, playerLower);
                            delete.executeUpdate();
                        }
                    }
                    conn.commit();
                    return count;
                } catch (SQLException error) {
                    conn.rollback();
                    throw error;
                } finally {
                    restoreAutoCommit(conn);
                }
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }

    public void addPendingReward(String playerName, String serviceName, String address, long timestamp,
                                 boolean firstVote) {
        try (Connection conn = db.openConnection()) {
            insertPending(conn, playerName.toLowerCase(Locale.ROOT), serviceName, address, timestamp, firstVote);
        } catch (SQLException error) {
            throw new IllegalStateException("数据库操作失败", error);
        }
    }

    private void insertPending(Connection conn, String playerName, String serviceName, String address,
                               long timestamp, boolean firstVote) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + db.table("pending_rewards")
                        + " (player_name, service_name, address, timestamp, first_vote) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, playerName);
            ps.setString(2, serviceName);
            ps.setString(3, address);
            ps.setLong(4, timestamp);
            ps.setInt(5, firstVote ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public List<PendingReward> getPendingRewards(String playerName) {
        List<PendingReward> result = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, service_name, address, timestamp, first_vote FROM " + db.table("pending_rewards")
                             + " WHERE player_name = ? ORDER BY id")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PendingReward(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getInt(5) == 1));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("数据库操作失败", error);
        }
        return result;
    }

    /**
     * 跨服占用一条待发奖励（带租约）。返回 true 的子服才允许执行命令；
     * 占用者崩溃时，超过 staleMs 的租约会被其它子服接管。
     */
    public boolean claimPendingReward(int id, String owner, long now, long staleMs) {
        synchronized (db) {
            String sql = "UPDATE " + db.table("pending_rewards")
                    + " SET claimed_by = ?, claimed_at = ? WHERE id = ?"
                    + " AND (claimed_by IS NULL OR claimed_at IS NULL OR claimed_at < ?)";
            try (Connection conn = db.openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, owner);
                ps.setLong(2, now);
                ps.setInt(3, id);
                ps.setLong(4, now - Math.max(1000L, staleMs));
                return ps.executeUpdate() > 0;
            } catch (SQLException error) {
                throw new IllegalStateException("占用待发奖励失败", error);
            }
        }
    }

    /** 发奖失败/玩家不在线时释放租约，保留 pending_rewards 与 reward_progress 供重试。 */
    public void releasePendingReward(int id, String owner) {
        synchronized (db) {
            try (Connection conn = db.openConnection(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + db.table("pending_rewards")
                            + " SET claimed_by = NULL, claimed_at = NULL WHERE id = ? AND claimed_by = ?")) {
                ps.setInt(1, id);
                ps.setString(2, owner);
                ps.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException("释放待发奖励失败", error);
            }
        }
    }

    /** 奖励全部命令执行成功后删除；带 owner 防止误删其它子服接管的记录。 */
    public void deletePendingReward(int id, String owner) {
        synchronized (db) {
            try (Connection conn = db.openConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + db.table("pending_rewards") + " WHERE id = ? AND claimed_by = ?");
                     PreparedStatement progress = conn.prepareStatement(
                             "DELETE FROM " + db.table("reward_progress") + " WHERE reward_key = ?")) {
                    ps.setInt(1, id);
                    ps.setString(2, owner);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        return;
                    }
                    progress.setString(1, "vote:" + id);
                    progress.executeUpdate();
                }
                conn.commit();
                restoreAutoCommit(conn);
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }

    public void deletePendingReward(int id) {
        synchronized (db) {
            try (Connection conn = db.openConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + db.table("pending_rewards") + " WHERE id = ?");
                     PreparedStatement progress = conn.prepareStatement(
                             "DELETE FROM " + db.table("reward_progress") + " WHERE reward_key = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                    progress.setString(1, "vote:" + id);
                    progress.executeUpdate();
                }
                conn.commit();
                restoreAutoCommit(conn);
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }

    /**
     * 恢复连接 autoCommit。提交已经成功时，恢复失败不能把业务结果改判为失败：
     * Hikari 在连接归还池时会重置 autoCommit，而调用方若收到异常可能重试并重复发奖。
     */
    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接即将归还连接池，由池重置状态。
        }
    }

    private int count(Connection conn, String sql, String playerName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
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

        public String getPlayerName() { return playerName; }
        public String getServiceName() { return serviceName; }
        public String getAddress() { return address; }
        public long getTimestamp() { return timestamp; }
    }
}
