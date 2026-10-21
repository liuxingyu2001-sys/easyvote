package com.easyvote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** 里程碑领取状态；INSERT IGNORE / INSERT OR IGNORE 保证跨服只成功一次。 */
public class MilestoneTracker {

    private final DatabaseManager db;

    public MilestoneTracker(DatabaseManager db) {
        this.db = db;
    }

    public boolean hasReceived(String playerName, int count) {
        synchronized (db) {
            try (Connection conn = db.openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT 1 FROM " + db.table("milestones") + " WHERE player_name = ? AND count = ?")) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setInt(2, count);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException error) {
                throw new IllegalStateException("查询里程碑失败", error);
            }
        }
    }

    /**
     * Atomically check and mark a milestone. Returns true if this call
     * actually marked it (first time), false if it was already received.
     */
    public boolean markIfNotReceived(String playerName, int count, long timestamp) {
        synchronized (db) {
            String sql = db.isMySQL()
                    ? "INSERT IGNORE INTO " + db.table("milestones") + " (player_name, count, timestamp) VALUES (?, ?, ?)"
                    : "INSERT OR IGNORE INTO " + db.table("milestones") + " (player_name, count, timestamp) VALUES (?, ?, ?)";
            try (Connection conn = db.openConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setInt(2, count);
                ps.setLong(3, timestamp);
                return ps.executeUpdate() > 0;
            } catch (SQLException error) {
                throw new IllegalStateException("保存里程碑失败", error);
            }
        }
    }

    /** 发奖失败时回滚本次占位，让玩家可以重试；只删除本次插入的档位。 */
    public void unmark(String playerName, int count) {
        synchronized (db) {
            try (Connection conn = db.openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM " + db.table("milestones") + " WHERE player_name = ? AND count = ?")) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setInt(2, count);
                ps.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException("回滚里程碑失败", error);
            }
        }
    }

    public int getPlayerHighestMilestone(String playerName) {
        synchronized (db) {
            try (Connection conn = db.openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT MAX(count) FROM " + db.table("milestones") + " WHERE player_name = ?")) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int value = rs.getInt(1);
                        return rs.wasNull() ? 0 : value;
                    }
                }
                return 0;
            } catch (SQLException error) {
                throw new IllegalStateException("数据库操作失败", error);
            }
        }
    }
}
