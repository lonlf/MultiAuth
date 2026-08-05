package com.lonleaf.multiauth.db;

import com.lonleaf.multiauth.Messages;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MySQLManager implements DatabaseManager {

    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int IDLE_TIMEOUT_MINUTES = 10;
    private static final int MAX_LIFETIME_MINUTES = 30;
    private static final int MAX_POOL_SIZE = 10;

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String tablePrefix;
    private final boolean useSsl;
    private final Logger logger;

    private volatile HikariDataSource dataSource;
    private final String tableName;
    private final String authTableName;
    private final String loginHistoryTableName;
    private final String ipStatsTableName;

    public MySQLManager(String host, int port, String database, String username, String password,
                        String tablePrefix, boolean useSsl, Logger logger) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
        this.useSsl = useSsl;
        this.logger = logger;
        this.tableName = this.tablePrefix + "players";
        this.authTableName = this.tablePrefix + "auth";
        this.loginHistoryTableName = this.tablePrefix + "login_history";
        this.ipStatsTableName = this.tablePrefix + "ip_stats";
    }

    private String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "username VARCHAR(16) PRIMARY KEY, " +
                "is_premium TINYINT(1) NOT NULL DEFAULT 0, " +
                "uuid VARCHAR(36) NOT NULL, " +
                "updated_at BIGINT NOT NULL, " +
                "last_world VARCHAR(64), " +
                "last_x DOUBLE, " +
                "last_y DOUBLE, " +
                "last_z DOUBLE, " +
                "last_yaw FLOAT, " +
                "last_pitch FLOAT)";
    }

    private String createIndexSql() {
        // MySQL 不支持 CREATE INDEX IF NOT EXISTS，使用 CREATE INDEX 仅在表新建时调用
        return "CREATE INDEX idx_" + tableName + "_premium ON " + tableName + " (is_premium, updated_at)";
    }

    private String getPlayerSql() {
        return "SELECT username, is_premium, uuid, updated_at, last_world, last_x, last_y, last_z, last_yaw, last_pitch FROM " + tableName + " WHERE username = ?";
    }

    private String savePlayerSql() {
        return "INSERT INTO " + tableName +
                " (username, is_premium, uuid, updated_at) VALUES (?, ?, ?, ?)" +
                " ON DUPLICATE KEY UPDATE is_premium = VALUES(is_premium), uuid = VALUES(uuid), updated_at = VALUES(updated_at)";
    }

    /** 条件 UPSERT：如果已有正版记录而新记录非正版，则不覆写 */
    private String savePlayerSafeSql() {
        return "INSERT INTO " + tableName +
                " (username, is_premium, uuid, updated_at) VALUES (?, ?, ?, ?)" +
                " ON DUPLICATE KEY UPDATE" +
                " is_premium = IF(is_premium = 1 AND VALUES(is_premium) = 0, is_premium, VALUES(is_premium))," +
                " uuid = IF(is_premium = 1 AND VALUES(is_premium) = 0, uuid, VALUES(uuid))," +
                " updated_at = IF(is_premium = 1 AND VALUES(is_premium) = 0, updated_at, VALUES(updated_at))";
    }

    private String existsSql() {
        return "SELECT 1 FROM " + tableName + " WHERE username = ?";
    }

    private String selectAllSql() {
        return "SELECT username, is_premium, uuid, updated_at FROM " + tableName;
    }

    private String updatePlayerLocationSql() {
        return "UPDATE " + tableName + " SET last_world = ?, last_x = ?, last_y = ?, last_z = ?, " +
               "last_yaw = ?, last_pitch = ? WHERE username = ?";
    }

    private String createAuthTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + authTableName + " (" +
                "username VARCHAR(16) PRIMARY KEY, " +
                "password_hash VARCHAR(255) NOT NULL, " +
                "register_time BIGINT NOT NULL, " +
                "last_login_time BIGINT NOT NULL DEFAULT 0, " +
                "last_ip VARCHAR(45))";
    }

    private String createAuthIndexSql() {
        // MySQL 不支持 CREATE INDEX IF NOT EXISTS，使用 CREATE INDEX 仅在表新建时调用
        return "CREATE INDEX idx_" + authTableName + "_username ON " + authTableName + " (username)";
    }

    private String getAuthAccountSql() {
        return "SELECT username, password_hash, register_time, last_login_time, last_ip FROM " + authTableName + " WHERE username = ?";
    }

    private String saveAuthAccountSql() {
        return "INSERT INTO " + authTableName +
                " (username, password_hash, register_time, last_login_time, last_ip) VALUES (?, ?, ?, ?, ?)" +
                " ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), register_time = VALUES(register_time)," +
                " last_login_time = VALUES(last_login_time), last_ip = VALUES(last_ip)";
    }

    private String updateAuthPasswordSql() {
        return "UPDATE " + authTableName + " SET password_hash = ? WHERE username = ?";
    }

    private String updateAuthLoginSql() {
        return "UPDATE " + authTableName + " SET last_login_time = ?, last_ip = ? WHERE username = ?";
    }

    private String deleteAuthAccountSql() {
        return "DELETE FROM " + authTableName + " WHERE username = ?";
    }

    private String authAccountExistsSql() {
        return "SELECT 1 FROM " + authTableName + " WHERE username = ?";
    }

    private String createLoginHistoryTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + loginHistoryTableName + " (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "username VARCHAR(16) NOT NULL, " +
                "ip VARCHAR(45) NOT NULL, " +
                "login_time BIGINT NOT NULL, " +
                "success TINYINT(1) NOT NULL DEFAULT 1, " +
                "country VARCHAR(64), " +
                "city VARCHAR(64))";
    }

    private String createLoginHistoryIndexUserTimeSql() {
        // MySQL 不支持 CREATE INDEX IF NOT EXISTS，使用 CREATE INDEX 仅在表新建时调用
        return "CREATE INDEX idx_" + loginHistoryTableName + "_user_time ON " + loginHistoryTableName + " (username, login_time DESC)";
    }

    private String createLoginHistoryIndexIpSql() {
        return "CREATE INDEX idx_" + loginHistoryTableName + "_ip ON " + loginHistoryTableName + " (ip)";
    }

    private String recordLoginHistorySql() {
        return "INSERT INTO " + loginHistoryTableName + " (username, ip, login_time, success, country, city) VALUES (?, ?, ?, ?, ?, ?)";
    }

    private String getRecentLoginHistorySql() {
        return "SELECT username, ip, login_time, success, country, city FROM " + loginHistoryTableName + " WHERE username = ? AND success = 1 ORDER BY login_time DESC LIMIT ?";
    }

    private String trimLoginHistorySql() {
        return "DELETE FROM " + loginHistoryTableName + " WHERE username = ? AND id NOT IN (SELECT id FROM (SELECT id FROM " + loginHistoryTableName + " WHERE username = ? ORDER BY login_time DESC LIMIT ?) AS t)";
    }

    private String createIpStatsTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + ipStatsTableName + " (" +
                "ip VARCHAR(45) PRIMARY KEY, " +
                "account_count INT NOT NULL DEFAULT 0, " +
                "failed_attempts INT NOT NULL DEFAULT 0, " +
                "last_failure_time BIGINT NOT NULL DEFAULT 0, " +
                "cooldown_until BIGINT NOT NULL DEFAULT 0)";
    }

    private String getIpStatsSql() {
        return "SELECT ip, account_count, failed_attempts, last_failure_time, cooldown_until FROM " + ipStatsTableName + " WHERE ip = ?";
    }

    private String upsertIpAccountCountSql() {
        return "INSERT INTO " + ipStatsTableName + " (ip, account_count, failed_attempts, last_failure_time, cooldown_until) VALUES (?, 1, 0, 0, 0) ON DUPLICATE KEY UPDATE account_count = account_count + 1";
    }

    private String decrementIpAccountCountSql() {
        return "UPDATE " + ipStatsTableName + " SET account_count = account_count - 1 WHERE ip = ? AND account_count > 0";
    }

    private String updateIpFailureStatsSql() {
        return "INSERT INTO " + ipStatsTableName + " (ip, account_count, failed_attempts, last_failure_time, cooldown_until) VALUES (?, 0, ?, ?, ?) ON DUPLICATE KEY UPDATE failed_attempts = VALUES(failed_attempts), last_failure_time = VALUES(last_failure_time), cooldown_until = VALUES(cooldown_until)";
    }

    @Override
    public synchronized void connect() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) {
            // 连接池存活，但如果 ping 失败则需要重建（heartbeat 重连对 MySQL 才会真正生效）
            if (ping()) {
                return;
            }
            // ping 失败，关闭旧池重建（不递归调用 connect，避免无限循环）
            try { dataSource.close(); } catch (Exception ignored) {}
            dataSource = null;
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl + "&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("MultiAuth-MySQL");
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS * 1000L);
        config.setIdleTimeout(IDLE_TIMEOUT_MINUTES * 60_000L);
        config.setMaxLifetime(MAX_LIFETIME_MINUTES * 60_000L);
        config.setKeepaliveTime(60_000L); // 每 60s 探活一次连接
        config.setLeakDetectionThreshold(60_000L);
        // MySQL 验证查询：连接借出前先用 isValid 检测，避免使用已断开的连接
        config.setConnectionTestQuery("SELECT 1");

        try {
            this.dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            throw new SQLException("Failed to initialize HikariCP: " + e.getMessage(), e);
        }

        // 建表与索引
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql());
            try {
                stmt.execute(createIndexSql());
            } catch (SQLException e) {
                logIndexCreationFailure(e);
            }
            stmt.execute(createAuthTableSql());
            try {
                stmt.execute(createAuthIndexSql());
            } catch (SQLException e) {
                logIndexCreationFailure(e);
            }
            stmt.execute(createLoginHistoryTableSql());
            try {
                stmt.execute(createLoginHistoryIndexUserTimeSql());
            } catch (SQLException e) {
                logIndexCreationFailure(e);
            }
            try {
                stmt.execute(createLoginHistoryIndexIpSql());
            } catch (SQLException e) {
                logIndexCreationFailure(e);
            }
            stmt.execute(createIpStatsTableSql());
            // 迁移：为旧表添加 last_location 列（如不存在）
            addMysqlColumnIfNotExists(conn, "last_world", "VARCHAR(64)");
            addMysqlColumnIfNotExists(conn, "last_x", "DOUBLE");
            addMysqlColumnIfNotExists(conn, "last_y", "DOUBLE");
            addMysqlColumnIfNotExists(conn, "last_z", "DOUBLE");
            addMysqlColumnIfNotExists(conn, "last_yaw", "FLOAT");
            addMysqlColumnIfNotExists(conn, "last_pitch", "FLOAT");
        }
    }

    @Override
    public synchronized void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, Messages.DB_CLOSE_FAILED, e);
            } finally {
                dataSource = null;
            }
        }
    }

    @Override
    public boolean isConnected() {
        HikariDataSource ds = dataSource;
        return ds != null && !ds.isClosed();
    }

    @Override
    public boolean ping() {
        if (!isConnected()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_PING_EXCEPTION, e);
            return false;
        }
    }

    /** 从池中借出连接。连接池关闭时返回 null。 */
    private Connection borrowConnection() throws SQLException {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.isClosed()) {
            return null;
        }
        return ds.getConnection();
    }

    @Override
    public PlayerRecord getPlayer(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(getPlayerSql())) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("username");
                        boolean isPremium = rs.getInt("is_premium") != 0;
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        long updatedAt = rs.getLong("updated_at");
                        String lastWorld = rs.getString("last_world");
                        double lastX = rs.getDouble("last_x");
                        double lastY = rs.getDouble("last_y");
                        double lastZ = rs.getDouble("last_z");
                        float lastYaw = rs.getFloat("last_yaw");
                        float lastPitch = rs.getFloat("last_pitch");
                        return new PlayerRecord(name, isPremium, uuid, updatedAt,
                                lastWorld, lastX, lastY, lastZ, lastYaw, lastPitch);
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_GET_PLAYER_FAILED, username), e);
        }
        return null;
    }

    @Override
    public void savePlayer(String username, boolean isPremium, UUID uuid) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(savePlayerSql())) {
                ps.setString(1, username);
                ps.setInt(2, isPremium ? 1 : 0);
                ps.setString(3, uuid.toString());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_SAVE_PLAYER_FAILED, username), e);
        }
    }

    @Override
    public void savePlayerSafe(String username, boolean isPremium, UUID uuid) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(savePlayerSafeSql())) {
                ps.setString(1, username);
                ps.setInt(2, isPremium ? 1 : 0);
                ps.setString(3, uuid.toString());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_SAVE_PLAYER_SAFE_FAILED, username), e);
        }
    }

    @Override
    public void updatePlayerLocation(String username, String world, double x, double y, double z, float yaw, float pitch) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(updatePlayerLocationSql())) {
                ps.setString(1, world);
                ps.setDouble(2, x);
                ps.setDouble(3, y);
                ps.setDouble(4, z);
                ps.setFloat(5, yaw);
                ps.setFloat(6, pitch);
                ps.setString(7, username);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update player location for " + username, e);
        }
    }

    /** 检查 MySQL 表中列是否存在，不存在则添加 */
    private void addMysqlColumnIfNotExists(Connection conn, String columnName, String columnType) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM " + tableName + " LIKE '" + columnName + "'")) {
            if (!rs.next()) {
                stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
            }
        } catch (SQLException ignored) {
        }
    }

    @Override
    public boolean exists(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(existsSql())) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_EXISTS_FAILED, username), e);
            return false;
        }
    }

    @Override
    public int countRecords() {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return -1;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_COUNT_FAILED, e);
            return -1;
        }
    }

    @Override
    public int countPremiumRecords() {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return -1;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName + " WHERE is_premium = 1")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_COUNT_PREMIUM_FAILED, e);
            return -1;
        }
    }

    @Override
    public int migrateTo(DatabaseManager target) {
        int count = 0;
        try (Connection conn = borrowConnection()) {
            if (conn == null) return 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(selectAllSql())) {
                while (rs.next()) {
                    String name = rs.getString("username");
                    boolean isPremium = rs.getInt("is_premium") != 0;
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    // savePlayerSafe：条件 UPSERT，避免目标库中已有的正版记录被离线记录覆盖（#9）
                    target.savePlayerSafe(name, isPremium, uuid);
                    count++;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_MIGRATION_EXCEPTION, e);
        }
        return count;
    }

    private String selectAllAuthSql() {
        return "SELECT username, password_hash, register_time, last_login_time, last_ip FROM " + authTableName;
    }

    @Override
    public void backup(Path target) throws SQLException, IOException {
        if (!isConnected()) {
            throw new SQLException("MySQL database is not connected");
        }
        try (Connection conn = borrowConnection()) {
            if (conn == null) {
                throw new SQLException("MySQL database is not connected");
            }
            try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                writer.write("-- MultiAuth MySQL backup\n");
                writer.write("-- Generated at: " + System.currentTimeMillis() + "\n\n");

                // --- 备份 players 表 ---
                writer.write("-- Table: " + tableName + "\n");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(selectAllSql())) {
                    while (rs.next()) {
                        String name = rs.getString("username");
                        String uuid = rs.getString("uuid");
                        if (!SQLiteManager.isValidUsername(name) || !SQLiteManager.isValidUuid(uuid)) {
                            logger.warning(Messages.get(Messages.DB_BACKUP_SKIP_INVALID_ROW, name, uuid));
                            continue;
                        }
                        int isPremium = rs.getInt("is_premium");
                        long updatedAt = rs.getLong("updated_at");
                        writer.write("INSERT INTO " + tableName +
                                " (username, is_premium, uuid, updated_at) VALUES ('" +
                                escapeSql(name) + "', " + isPremium + ", '" + escapeSql(uuid) + "', " + updatedAt + ");\n");
                    }
                }

                // --- 备份 auth 表 ---
                writer.write("\n-- Table: " + authTableName + "\n");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(selectAllAuthSql())) {
                    while (rs.next()) {
                        String name = rs.getString("username");
                        String passwordHash = rs.getString("password_hash");
                        long registerTime = rs.getLong("register_time");
                        long lastLoginTime = rs.getLong("last_login_time");
                        String lastIp = rs.getString("last_ip");
                        writer.write("INSERT INTO " + authTableName +
                                " (username, password_hash, register_time, last_login_time, last_ip) VALUES ('" +
                                escapeSql(name) + "', '" + escapeSql(passwordHash) + "', " + registerTime + ", " +
                                lastLoginTime + ", " + (lastIp != null ? "'" + escapeSql(lastIp) + "'" : "NULL") + ");\n");
                    }
                }
            }
        }
    }

    private static String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    /**
     * 处理建索引时的 SQLException：仅忽略"索引已存在"错误（MySQL 不支持 CREATE INDEX IF NOT EXISTS），
     * 其余异常记录 WARNING 日志，避免无差别吞掉所有错误。
     */
    private void logIndexCreationFailure(SQLException e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (!msg.contains("duplicate") && !msg.contains("exists")) {
            logger.log(Level.WARNING, "[DB] Failed to create index: " + e.getMessage(), e);
        }
    }

    @Override
    public void createAuthTable() {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createAuthTableSql());
                try {
                    stmt.execute(createAuthIndexSql());
                } catch (SQLException ignored) {
                    // 索引已存在则忽略
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to create auth table", e);
        }
    }

    @Override
    public AuthAccount getAuthAccount(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(getAuthAccountSql())) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("username");
                        String passwordHash = rs.getString("password_hash");
                        long registerTime = rs.getLong("register_time");
                        long lastLoginTime = rs.getLong("last_login_time");
                        String lastIp = rs.getString("last_ip");
                        return new AuthAccount(name, passwordHash, registerTime, lastLoginTime, lastIp);
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to get auth account for " + username, e);
        }
        return null;
    }

    @Override
    public void saveAuthAccount(AuthAccount account) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(saveAuthAccountSql())) {
                ps.setString(1, account.username());
                ps.setString(2, account.passwordHash());
                ps.setLong(3, account.registerTime());
                ps.setLong(4, account.lastLoginTime());
                ps.setString(5, account.lastIp());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to save auth account for " + account.username(), e);
        }
    }

    @Override
    public void updateAuthPassword(String username, String passwordHash) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(updateAuthPasswordSql())) {
                ps.setString(1, passwordHash);
                ps.setString(2, username);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update auth password for " + username, e);
        }
    }

    @Override
    public void updateAuthLogin(String username, long loginTime, String ip) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(updateAuthLoginSql())) {
                ps.setLong(1, loginTime);
                ps.setString(2, ip);
                ps.setString(3, username);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update auth login for " + username, e);
        }
    }

    @Override
    public boolean deleteAuthAccount(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(deleteAuthAccountSql())) {
                ps.setString(1, username);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to delete auth account for " + username, e);
            return false;
        }
    }

    @Override
    public boolean authAccountExists(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(authAccountExistsSql())) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to check auth account exists for " + username, e);
            return false;
        }
    }

    @Override
    public void createLoginHistoryTable() {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createLoginHistoryTableSql());
                try {
                    stmt.execute(createLoginHistoryIndexUserTimeSql());
                } catch (SQLException ignored) {
                    // 索引已存在则忽略
                }
                try {
                    stmt.execute(createLoginHistoryIndexIpSql());
                } catch (SQLException ignored) {
                    // 索引已存在则忽略
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to create login history table", e);
        }
    }

    @Override
    public void recordLoginHistory(String username, String ip, long loginTime, boolean success, String country, String city) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(recordLoginHistorySql())) {
                ps.setString(1, username);
                ps.setString(2, ip);
                ps.setLong(3, loginTime);
                ps.setInt(4, success ? 1 : 0);
                ps.setString(5, country);
                ps.setString(6, city);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to record login history for " + username, e);
        }
    }

    @Override
    public List<LoginHistoryRecord> getRecentLoginHistory(String username, int limit) {
        List<LoginHistoryRecord> result = new ArrayList<>();
        try (Connection conn = borrowConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(getRecentLoginHistorySql())) {
                ps.setString(1, username);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("username");
                        String ip = rs.getString("ip");
                        long loginTime = rs.getLong("login_time");
                        boolean success = rs.getInt("success") != 0;
                        String country = rs.getString("country");
                        String city = rs.getString("city");
                        result.add(new LoginHistoryRecord(name, ip, loginTime, success, country, city));
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to get recent login history for " + username, e);
        }
        return result;
    }

    @Override
    public void trimLoginHistory(String username, int maxRecords) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(trimLoginHistorySql())) {
                ps.setString(1, username);
                ps.setString(2, username);
                ps.setInt(3, maxRecords);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to trim login history for " + username, e);
        }
    }

    @Override
    public void createIpStatsTable() {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createIpStatsTableSql());
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to create ip stats table", e);
        }
    }

    @Override
    public IpStatsRecord getIpStats(String ip) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(getIpStatsSql())) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String ipAddr = rs.getString("ip");
                        int accountCount = rs.getInt("account_count");
                        int failedAttempts = rs.getInt("failed_attempts");
                        long lastFailureTime = rs.getLong("last_failure_time");
                        long cooldownUntil = rs.getLong("cooldown_until");
                        return new IpStatsRecord(ipAddr, accountCount, failedAttempts, lastFailureTime, cooldownUntil);
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to get ip stats for " + ip, e);
        }
        return null;
    }

    @Override
    public void incrementIpAccountCount(String ip) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(upsertIpAccountCountSql())) {
                ps.setString(1, ip);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to increment ip account count for " + ip, e);
        }
    }

    @Override
    public void decrementIpAccountCount(String ip) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(decrementIpAccountCountSql())) {
                ps.setString(1, ip);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to decrement ip account count for " + ip, e);
        }
    }

    @Override
    public void updateIpFailureStats(String ip, int failedAttempts, long lastFailureTime, long cooldownUntil) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(updateIpFailureStatsSql())) {
                ps.setString(1, ip);
                ps.setInt(2, failedAttempts);
                ps.setLong(3, lastFailureTime);
                ps.setLong(4, cooldownUntil);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update ip failure stats for " + ip, e);
        }
    }
}
