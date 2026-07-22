package com.easyvote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final EasyVotePlugin plugin;
    private final File dbFile;
    private Connection connection;

    public DatabaseManager(EasyVotePlugin plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "data.db");
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
        return connection;
    }

    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite 驱动加载失败: " + e.getMessage());
            return;
        }

        try {
            Connection conn = getConnection();
            Statement stmt = conn.createStatement();
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS votes (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  player_name TEXT NOT NULL," +
                "  service_name TEXT NOT NULL," +
                "  address TEXT," +
                "  timestamp INTEGER NOT NULL" +
                ")"
            );
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_votes_player ON votes(player_name)"
            );
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_votes_service ON votes(service_name)"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS milestones (" +
                "  player_name TEXT NOT NULL," +
                "  count INTEGER NOT NULL," +
                "  timestamp INTEGER NOT NULL," +
                "  PRIMARY KEY (player_name, count)" +
                ")"
            );

            stmt.close();
            migrateCsvIfExists();

            plugin.getLogger().info("SQLite 数据库已就绪: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库初始化失败: " + e.getMessage());
        }
    }

    private void migrateCsvIfExists() {
        File votesCsv = new File(plugin.getDataFolder(), "votes.csv");
        File milestonesCsv = new File(plugin.getDataFolder(), "milestones.csv");

        if (votesCsv.exists()) {
            plugin.getLogger().info("检测到旧 votes.csv，正在迁移...");
            int count = migrateVotesCsv(votesCsv);
            plugin.getLogger().info("已迁移 " + count + " 条投票记录");
            votesCsv.delete();
        }

        if (milestonesCsv.exists()) {
            plugin.getLogger().info("检测到旧 milestones.csv，正在迁移...");
            int count = migrateMilestonesCsv(milestonesCsv);
            plugin.getLogger().info("已迁移 " + count + " 条里程碑记录");
            milestonesCsv.delete();
        }
    }

    private int migrateVotesCsv(File file) {
        int count = 0;
        String sql = "INSERT INTO votes (player_name, service_name, address, timestamp) VALUES (?, ?, ?, ?)";
        try (BufferedReader reader = new BufferedReader(
                 new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            conn.setAutoCommit(false);
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    ps.setString(1, parts[0].toLowerCase());
                    ps.setString(2, parts[1]);
                    ps.setString(3, parts.length >= 3 ? parts[2] : "");
                    ps.setLong(4, Long.parseLong(parts[3]));
                    ps.addBatch();
                    count++;
                }
            }
            ps.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("迁移 votes.csv 失败: " + e.getMessage());
        }
        return count;
    }

    private int migrateMilestonesCsv(File file) {
        int count = 0;
        String sql = "INSERT OR IGNORE INTO milestones (player_name, count, timestamp) VALUES (?, ?, ?)";
        try (BufferedReader reader = new BufferedReader(
                 new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            conn.setAutoCommit(false);
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    ps.setString(1, parts[0].toLowerCase());
                    ps.setInt(2, Integer.parseInt(parts[1]));
                    ps.setLong(3, parts.length >= 3 ? Long.parseLong(parts[2]) : System.currentTimeMillis());
                    ps.addBatch();
                    count++;
                }
            }
            ps.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("迁移 milestones.csv 失败: " + e.getMessage());
        }
        return count;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("关闭数据库连接失败: " + e.getMessage());
        }
    }
}
