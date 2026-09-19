package com.easyvote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.logging.Logger;

public class DatabaseManager {

    private final Logger logger;
    private final File dataFolder;
    private final File dbFile;
    private Connection connection;

    public DatabaseManager(EasyVotePlugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    DatabaseManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.dbFile = new File(dataFolder, "data.db");
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
        return connection;
    }

    public synchronized void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite 驱动加载失败", e);
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
            boolean hasReceivedAt = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(votes)")) {
                while (columns.next()) {
                    if ("received_at".equals(columns.getString("name"))) hasReceivedAt = true;
                }
            }
            if (!hasReceivedAt) stmt.execute("ALTER TABLE votes ADD COLUMN received_at INTEGER");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_votes_player_received ON votes(player_name, received_at)");

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS milestones (" +
                "  player_name TEXT NOT NULL," +
                "  count INTEGER NOT NULL," +
                "  timestamp INTEGER NOT NULL," +
                "  PRIMARY KEY (player_name, count)" +
                ")"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pending_rewards (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  player_name TEXT NOT NULL," +
                "  service_name TEXT NOT NULL," +
                "  address TEXT," +
                "  timestamp INTEGER NOT NULL," +
                "  first_vote INTEGER NOT NULL DEFAULT 0" +
                ")"
            );
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_pending_player ON pending_rewards(player_name)"
            );
            stmt.execute("CREATE TABLE IF NOT EXISTS reward_progress (" +
                "player_name TEXT NOT NULL, reward_key TEXT NOT NULL, commands TEXT NOT NULL," +
                "next_command INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (player_name, reward_key))");

            stmt.close();
            migrateCsvIfExists();
            // Older records have only the site's timestamp (seconds or milliseconds).
            try (Statement migration = conn.createStatement()) {
                migration.executeUpdate("UPDATE votes SET received_at = CASE " +
                    "WHEN timestamp BETWEEN 0 AND 99999999999 THEN timestamp * 1000 ELSE timestamp END " +
                    "WHERE received_at IS NULL");
            }

            logger.info("SQLite 数据库已就绪: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("数据库初始化失败", e);
        }
    }

    private void migrateCsvIfExists() {
        File votesCsv = new File(dataFolder, "votes.csv");
        File milestonesCsv = new File(dataFolder, "milestones.csv");

        if (votesCsv.exists()) {
            logger.info("检测到旧 votes.csv，正在迁移...");
            int count = migrateVotesCsv(votesCsv);
            logger.info("已迁移 " + count + " 条投票记录");
            votesCsv.delete();
        }

        if (milestonesCsv.exists()) {
            logger.info("检测到旧 milestones.csv，正在迁移...");
            int count = migrateMilestonesCsv(milestonesCsv);
            logger.info("已迁移 " + count + " 条里程碑记录");
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
                    ps.setString(1, parts[0].toLowerCase(Locale.ROOT));
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
            try {
                getConnection().rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("迁移 votes.csv 失败，保留原文件", e);
        } finally {
            try {
                getConnection().setAutoCommit(true);
            } catch (SQLException e) {
                throw new IllegalStateException("恢复数据库自动提交失败", e);
            }
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
                    ps.setString(1, parts[0].toLowerCase(Locale.ROOT));
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
            try {
                getConnection().rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("迁移 milestones.csv 失败，保留原文件", e);
        } finally {
            try {
                getConnection().setAutoCommit(true);
            } catch (SQLException e) {
                throw new IllegalStateException("恢复数据库自动提交失败", e);
            }
        }
        return count;
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.warning("关闭数据库连接失败: " + e.getMessage());
        }
    }
}
