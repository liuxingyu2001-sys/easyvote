package com.easyvote;

import com.liuxingyu.liulib.storage.MySql;
import com.liuxingyu.liulib.storage.SqlConfig;
import com.liuxingyu.liulib.storage.Sqlite;
import com.liuxingyu.liulib.storage.SqliteConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 投票数据存储。
 *
 * <p>默认 SQLite；{@code database.type: mysql} 时切换为 MySQL，多台子服共享票数、
 * 待发奖励、里程碑领取状态和奖励命令进度。连接池、WAL、驱动加载全部交给 LiuLib，
 * 插件 jar 不再打包 sqlite-jdbc / HikariCP。</p>
 *
 * <p>{@link #getConnection()} 保留给旧 JDBC 代码与测试；MySQL 下调用方必须关闭连接，
 * 事务内所有语句必须复用同一个 Connection（见 {@link #openConnection()}）。</p>
 */
public class DatabaseManager {

    private final Logger logger;
    private final File dataFolder;
    private final File dbFile;
    private final boolean mysqlType;
    private final String tablePrefix;
    private final Sqlite sqlite;
    private final MySql mysql;

    public DatabaseManager(EasyVotePlugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger(), plugin);
    }

    DatabaseManager(File dataFolder, Logger logger) {
        this(dataFolder, logger, null);
    }

    private DatabaseManager(File dataFolder, Logger logger, JavaPlugin plugin) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.dbFile = new File(dataFolder, "data.db");
        if (plugin != null) {
            this.mysqlType = "mysql".equalsIgnoreCase(plugin.getConfig().getString("database.type", "sqlite"));
            this.tablePrefix = plugin.getConfig().getString("database.table-prefix", "");
        } else {
            this.mysqlType = false;
            this.tablePrefix = "";
        }

        if (mysqlType) {
            this.sqlite = null;
            this.mysql = new MySql(SqlConfig.builder()
                    .enabled(true)
                    .host(plugin.getConfig().getString("database.mysql.host", "127.0.0.1"))
                    .port(plugin.getConfig().getInt("database.mysql.port", 3306))
                    .database(plugin.getConfig().getString("database.mysql.database", "easyvote"))
                    .username(plugin.getConfig().getString("database.mysql.username", "root"))
                    .password(plugin.getConfig().getString("database.mysql.password", ""))
                    .tablePrefix(tablePrefix)
                    .poolSize(Math.max(2, plugin.getConfig().getInt("database.mysql.pool-size", 8)))
                    .connectionTimeoutMs(5000)
                    .useSsl(plugin.getConfig().getBoolean("database.mysql.use-ssl", false))
                    .autoCreateDatabase(true)
                    .build(), logger);
        } else {
            this.mysql = null;
            this.sqlite = new Sqlite(plugin, SqliteConfig.builder()
                    .enabled(true)
                    .file(dbFile.getAbsolutePath())
                    .tablePrefix(tablePrefix)
                    .build(), logger);
        }
    }

    public boolean isMySQL() {
        return mysqlType;
    }

    Logger logger() {
        return logger;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    public String table(String name) {
        return tablePrefix + name;
    }

    /** 兼容旧代码：SQLite 返回新连接（调用方无需关闭），MySQL 返回池连接（调用方必须关闭）。 */
    public synchronized Connection getConnection() throws SQLException {
        if (mysqlType) {
            return mysql.getConnection();
        }
        return sqlite.getConnection();
    }

    /** 事务/多语句操作统一入口；调用方用 try-with-resources 关闭。 */
    public Connection openConnection() throws SQLException {
        return getConnection();
    }

    public synchronized void initialize() {
        boolean ready = mysqlType ? mysql.init() : sqlite.init();
        if (!ready) {
            throw new IllegalStateException("数据库初始化失败: " + (mysqlType ? "MySQL" : dbFile.getAbsolutePath()));
        }
        try {
            if (mysqlType) {
                createMySqlSchema();
            } else {
                createSqliteSchema();
            }
            migrateCsvIfExists();
            backfillReceivedAt();
            logger.info("数据库已就绪: " + (mysqlType ? "MySQL/" + tablePrefix : dbFile.getAbsolutePath()));
        } catch (SQLException error) {
            throw new IllegalStateException("数据库初始化失败", error);
        }
    }

    private void createSqliteSchema() throws SQLException {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("votes") + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "player_name TEXT NOT NULL," +
                    "service_name TEXT NOT NULL," +
                    "address TEXT," +
                    "timestamp INTEGER NOT NULL," +
                    "received_at INTEGER" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_votes_player ON " + table("votes") + "(player_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_votes_service ON " + table("votes") + "(service_name)");
            if (!hasColumn(conn, table("votes"), "received_at")) {
                stmt.execute("ALTER TABLE " + table("votes") + " ADD COLUMN received_at INTEGER");
            }
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_votes_player_received ON " + table("votes")
                    + "(player_name, received_at)");
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("milestones") + " (" +
                    "player_name TEXT NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "timestamp INTEGER NOT NULL," +
                    "PRIMARY KEY (player_name, count)" +
                    ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("pending_rewards") + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "player_name TEXT NOT NULL," +
                    "service_name TEXT NOT NULL," +
                    "address TEXT," +
                    "timestamp INTEGER NOT NULL," +
                    "first_vote INTEGER NOT NULL DEFAULT 0," +
                    "claimed_by VARCHAR(64) NULL," +
                    "claimed_at BIGINT NULL" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_player ON " + table("pending_rewards") + "(player_name)");
            if (!hasColumn(conn, table("pending_rewards"), "claimed_by")) {
                stmt.execute("ALTER TABLE " + table("pending_rewards") + " ADD COLUMN claimed_by VARCHAR(64) NULL");
            }
            if (!hasColumn(conn, table("pending_rewards"), "claimed_at")) {
                stmt.execute("ALTER TABLE " + table("pending_rewards") + " ADD COLUMN claimed_at BIGINT NULL");
            }
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("reward_progress") + " (" +
                    "player_name TEXT NOT NULL, reward_key TEXT NOT NULL, commands TEXT NOT NULL," +
                    "next_command INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (player_name, reward_key))");
        }
    }

    private void createMySqlSchema() throws SQLException {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            String suffix = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("votes") + " (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                    "player_name VARCHAR(64) NOT NULL," +
                    "service_name VARCHAR(128) NOT NULL," +
                    "address VARCHAR(64)," +
                    "timestamp BIGINT NOT NULL," +
                    "received_at BIGINT NULL" +
                    ")" + suffix);
            createIndex(stmt, "idx_votes_player", table("votes"), "player_name");
            createIndex(stmt, "idx_votes_service", table("votes"), "service_name");
            if (!hasColumn(conn, table("votes"), "received_at")) {
                stmt.execute("ALTER TABLE " + table("votes") + " ADD COLUMN received_at BIGINT NULL");
            }
            createIndex(stmt, "idx_votes_player_received", table("votes"), "player_name, received_at");
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("milestones") + " (" +
                    "player_name VARCHAR(64) NOT NULL," +
                    "count INT NOT NULL," +
                    "timestamp BIGINT NOT NULL," +
                    "PRIMARY KEY (player_name, count)" +
                    ")" + suffix);
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("pending_rewards") + " (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                    "player_name VARCHAR(64) NOT NULL," +
                    "service_name VARCHAR(128) NOT NULL," +
                    "address VARCHAR(64)," +
                    "timestamp BIGINT NOT NULL," +
                    "first_vote TINYINT NOT NULL DEFAULT 0," +
                    "claimed_by VARCHAR(64) NULL," +
                    "claimed_at BIGINT NULL" +
                    ")" + suffix);
            createIndex(stmt, "idx_pending_player", table("pending_rewards"), "player_name");
            if (!hasColumn(conn, table("pending_rewards"), "claimed_by")) {
                stmt.execute("ALTER TABLE " + table("pending_rewards") + " ADD COLUMN claimed_by VARCHAR(64) NULL");
            }
            if (!hasColumn(conn, table("pending_rewards"), "claimed_at")) {
                stmt.execute("ALTER TABLE " + table("pending_rewards") + " ADD COLUMN claimed_at BIGINT NULL");
            }
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table("reward_progress") + " (" +
                    "player_name VARCHAR(64) NOT NULL, reward_key VARCHAR(128) NOT NULL, commands LONGTEXT NOT NULL," +
                    "next_command INT NOT NULL DEFAULT 0, PRIMARY KEY (player_name, reward_key))" + suffix);
        }
    }

    private void createIndex(Statement stmt, String name, String table, String columns) {
        try {
            stmt.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        } catch (SQLException ignored) {
            // 已存在
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        if (mysqlType) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = ? AND column_name = ?")) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    /** 兼容旧版本只有网站时间戳的 votes 表。 */
    private void backfillReceivedAt() throws SQLException {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE " + table("votes") + " SET received_at = CASE " +
                    "WHEN timestamp BETWEEN 0 AND 99999999999 THEN timestamp * 1000 ELSE timestamp END " +
                    "WHERE received_at IS NULL");
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
        try (Connection conn = openConnection();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + table("votes") + " (player_name, service_name, address, timestamp) VALUES (?, ?, ?, ?)")) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",");
                    if (parts.length >= 4) {
                        ps.setString(1, parts[0].toLowerCase(Locale.ROOT));
                        ps.setString(2, parts[1]);
                        ps.setString(3, parts[2]);
                        ps.setLong(4, Long.parseLong(parts[3]));
                        ps.addBatch();
                        count++;
                    }
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(true);
            return count;
        } catch (Exception error) {
            throw new IllegalStateException("迁移 votes.csv 失败，保留原文件", error);
        }
    }

    private int migrateMilestonesCsv(File file) {
        int count = 0;
        try (Connection conn = openConnection();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            conn.setAutoCommit(false);
            String insert = mysqlType
                    ? "INSERT IGNORE INTO " + table("milestones") + " (player_name, count, timestamp) VALUES (?, ?, ?)"
                    : "INSERT OR IGNORE INTO " + table("milestones") + " (player_name, count, timestamp) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
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
            }
            conn.commit();
            conn.setAutoCommit(true);
            return count;
        } catch (Exception error) {
            throw new IllegalStateException("迁移 milestones.csv 失败，保留原文件", error);
        }
    }

    public synchronized void close() {
        if (mysql != null) {
            mysql.close();
        }
        if (sqlite != null) {
            sqlite.close();
        }
    }
}
