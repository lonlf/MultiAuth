package com.lonleaf.multiauth.db;

import com.lonleaf.multiauth.Messages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SQLiteManager implements DatabaseManager {

    private static final String TABLE_NAME = "multiauth_players";

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
            "username TEXT PRIMARY KEY, " +
            "is_premium INTEGER NOT NULL DEFAULT 0, " +
            "uuid TEXT NOT NULL, " +
            "updated_at INTEGER NOT NULL, " +
            "last_world TEXT, " +
            "last_x REAL, " +
            "last_y REAL, " +
            "last_z REAL, " +
            "last_yaw REAL, " +
            "last_pitch REAL)";

    private static final String CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_" + TABLE_NAME + "_premium ON " + TABLE_NAME + " (is_premium, updated_at)";

    private static final String GET_PLAYER_SQL =
            "SELECT username, is_premium, uuid, updated_at, last_world, last_x, last_y, last_z, last_yaw, last_pitch FROM " + TABLE_NAME + " WHERE username = ?";

    private static final String SAVE_PLAYER_SQL =
            "INSERT OR REPLACE INTO " + TABLE_NAME +
            " (username, is_premium, uuid, updated_at) VALUES (?, ?, ?, ?)";

    /** 条件 UPSERT：如果已有正版记录而新记录非正版，则不覆写 */
    private static final String SAVE_PLAYER_SAFE_SQL =
            "INSERT INTO " + TABLE_NAME +
            " (username, is_premium, uuid, updated_at) VALUES (?, ?, ?, ?)" +
            " ON CONFLICT(username) DO UPDATE SET" +
            " is_premium = CASE WHEN " + TABLE_NAME + ".is_premium = 1 AND excluded.is_premium = 0" +
            " THEN " + TABLE_NAME + ".is_premium ELSE excluded.is_premium END," +
            " uuid = CASE WHEN " + TABLE_NAME + ".is_premium = 1 AND excluded.is_premium = 0" +
            " THEN " + TABLE_NAME + ".uuid ELSE excluded.uuid END," +
            " updated_at = CASE WHEN " + TABLE_NAME + ".is_premium = 1 AND excluded.is_premium = 0" +
            " THEN " + TABLE_NAME + ".updated_at ELSE excluded.updated_at END";

    private static final String EXISTS_SQL =
            "SELECT 1 FROM " + TABLE_NAME + " WHERE username = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT username, is_premium, uuid, updated_at FROM " + TABLE_NAME;

    private static final String UPDATE_PLAYER_LOCATION_SQL =
            "UPDATE " + TABLE_NAME + " SET last_world = ?, last_x = ?, last_y = ?, last_z = ?, " +
            "last_yaw = ?, last_pitch = ? WHERE username = ?";

    private static final String CREATE_AUTH_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS multiauth_auth (" +
            "username TEXT PRIMARY KEY, " +
            "password_hash TEXT NOT NULL, " +
            "register_time INTEGER NOT NULL, " +
            "last_login_time INTEGER NOT NULL DEFAULT 0, " +
            "last_ip TEXT)";

    private static final String CREATE_AUTH_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_multiauth_auth_username ON multiauth_auth (username)";

    private static final String GET_AUTH_ACCOUNT_SQL =
            "SELECT username, password_hash, register_time, last_login_time, last_ip FROM multiauth_auth WHERE username = ?";

    private static final String SAVE_AUTH_ACCOUNT_SQL =
            "INSERT OR REPLACE INTO multiauth_auth (username, password_hash, register_time, last_login_time, last_ip) VALUES (?, ?, ?, ?, ?)";

    private static final String UPDATE_AUTH_PASSWORD_SQL =
            "UPDATE multiauth_auth SET password_hash = ? WHERE username = ?";

    private static final String UPDATE_AUTH_LOGIN_SQL =
            "UPDATE multiauth_auth SET last_login_time = ?, last_ip = ? WHERE username = ?";

    private static final String DELETE_AUTH_ACCOUNT_SQL =
            "DELETE FROM multiauth_auth WHERE username = ?";

    private static final String AUTH_ACCOUNT_EXISTS_SQL =
            "SELECT 1 FROM multiauth_auth WHERE username = ?";

    private static final String CREATE_LOGIN_HISTORY_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS multiauth_login_history (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "username TEXT NOT NULL, " +
            "ip TEXT NOT NULL, " +
            "login_time INTEGER NOT NULL, " +
            "success INTEGER NOT NULL DEFAULT 1, " +
            "country TEXT, " +
            "city TEXT)";

    private static final String CREATE_LOGIN_HISTORY_INDEX_USER_TIME_SQL =
            "CREATE INDEX IF NOT EXISTS idx_multiauth_login_history_user_time ON multiauth_login_history (username, login_time DESC)";

    private static final String CREATE_LOGIN_HISTORY_INDEX_IP_SQL =
            "CREATE INDEX IF NOT EXISTS idx_multiauth_login_history_ip ON multiauth_login_history (ip)";

    private static final String RECORD_LOGIN_HISTORY_SQL =
            "INSERT INTO multiauth_login_history (username, ip, login_time, success, country, city) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String GET_RECENT_LOGIN_HISTORY_SQL =
            "SELECT username, ip, login_time, success, country, city FROM multiauth_login_history WHERE username = ? AND success = 1 ORDER BY login_time DESC LIMIT ?";

    private static final String TRIM_LOGIN_HISTORY_SQL =
            "DELETE FROM multiauth_login_history WHERE username = ? AND id NOT IN (SELECT id FROM multiauth_login_history WHERE username = ? ORDER BY login_time DESC LIMIT ?)";

    private static final String CREATE_IP_STATS_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS multiauth_ip_stats (" +
            "ip TEXT PRIMARY KEY, " +
            "account_count INTEGER NOT NULL DEFAULT 0, " +
            "failed_attempts INTEGER NOT NULL DEFAULT 0, " +
            "last_failure_time INTEGER NOT NULL DEFAULT 0, " +
            "cooldown_until INTEGER NOT NULL DEFAULT 0)";

    private static final String GET_IP_STATS_SQL =
            "SELECT ip, account_count, failed_attempts, last_failure_time, cooldown_until FROM multiauth_ip_stats WHERE ip = ?";

    private static final String UPSERT_IP_ACCOUNT_COUNT_SQL =
            "INSERT INTO multiauth_ip_stats (ip, account_count, failed_attempts, last_failure_time, cooldown_until) VALUES (?, 1, 0, 0, 0) ON CONFLICT(ip) DO UPDATE SET account_count = account_count + 1";

    private static final String DECREMENT_IP_ACCOUNT_COUNT_SQL =
            "UPDATE multiauth_ip_stats SET account_count = account_count - 1 WHERE ip = ? AND account_count > 0";

    private static final String UPDATE_IP_FAILURE_STATS_SQL =
            "INSERT INTO multiauth_ip_stats (ip, account_count, failed_attempts, last_failure_time, cooldown_until) VALUES (?, 0, ?, ?, ?) ON CONFLICT(ip) DO UPDATE SET failed_attempts = excluded.failed_attempts, last_failure_time = excluded.last_failure_time, cooldown_until = excluded.cooldown_until";

    private final Path dbPath;
    private final Logger logger;
    private Connection connection;

    public SQLiteManager(Path dbPath, Logger logger) {
        this.dbPath = dbPath;
        this.logger = logger;
    }

    @Override
    public synchronized void connect() throws SQLException {
        // 重连前关闭旧连接，避免连接泄漏
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        Path parent = dbPath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("Could not create database directory: " + parent, e);
            }
        }
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString().replace('\\', '/');
        connection = DriverManager.getConnection(url);
        try (Statement stmt = connection.createStatement()) {
            // 启用 WAL 模式提升并发读性能，busy_timeout 避免锁竞争直接报错
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute(CREATE_TABLE_SQL);
            stmt.execute(CREATE_INDEX_SQL);
            stmt.execute(CREATE_AUTH_TABLE_SQL);
            stmt.execute(CREATE_AUTH_INDEX_SQL);
            stmt.execute(CREATE_LOGIN_HISTORY_TABLE_SQL);
            stmt.execute(CREATE_LOGIN_HISTORY_INDEX_USER_TIME_SQL);
            stmt.execute(CREATE_LOGIN_HISTORY_INDEX_IP_SQL);
            stmt.execute(CREATE_IP_STATS_TABLE_SQL);
            // 迁移：为旧表添加 last_location 列（如不存在）
            addColumnIfNotExists(stmt, "last_world", "TEXT");
            addColumnIfNotExists(stmt, "last_x", "REAL");
            addColumnIfNotExists(stmt, "last_y", "REAL");
            addColumnIfNotExists(stmt, "last_z", "REAL");
            addColumnIfNotExists(stmt, "last_yaw", "REAL");
            addColumnIfNotExists(stmt, "last_pitch", "REAL");
        }
    }

    @Override
    public synchronized void disconnect() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.log(Level.WARNING, Messages.DB_CLOSE_FAILED, e);
            } finally {
                connection = null;
            }
        }
    }

    @Override
    public synchronized boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_STATE_CHECK_FAILED, e);
            return false;
        }
    }

    @Override
    public synchronized boolean ping() {
        if (!isConnected()) {
            return false;
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_PING_EXCEPTION, e);
            return false;
        }
    }

    @Override
    public synchronized PlayerRecord getPlayer(String username) {
        if (!isConnected()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(GET_PLAYER_SQL)) {
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
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_GET_PLAYER_FAILED, username), e);
        }
        return null;
    }

    @Override
    public synchronized void savePlayer(String username, boolean isPremium, UUID uuid) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(SAVE_PLAYER_SQL)) {
            ps.setString(1, username);
            ps.setInt(2, isPremium ? 1 : 0);
            ps.setString(3, uuid.toString());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_SAVE_PLAYER_FAILED, username), e);
        }
    }

    @Override
    public synchronized void savePlayerSafe(String username, boolean isPremium, UUID uuid) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(SAVE_PLAYER_SAFE_SQL)) {
            ps.setString(1, username);
            ps.setInt(2, isPremium ? 1 : 0);
            ps.setString(3, uuid.toString());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_SAVE_PLAYER_SAFE_FAILED, username), e);
        }
    }

    @Override
    public synchronized void updatePlayerLocation(String username, String world, double x, double y, double z, float yaw, float pitch) {
        if (!isConnected()) return;
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_PLAYER_LOCATION_SQL)) {
            ps.setString(1, world);
            ps.setDouble(2, x);
            ps.setDouble(3, y);
            ps.setDouble(4, z);
            ps.setFloat(5, yaw);
            ps.setFloat(6, pitch);
            ps.setString(7, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update player location for " + username, e);
        }
    }

    @Override
    public synchronized boolean exists(String username) {
        if (!isConnected()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(EXISTS_SQL)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_EXISTS_FAILED, username), e);
            return false;
        }
    }

    @Override
    public synchronized int countRecords() {
        if (!isConnected()) {
            return -1;
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_COUNT_FAILED, e);
            return -1;
        }
    }

    @Override
    public synchronized int countPremiumRecords() {
        if (!isConnected()) {
            return -1;
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE is_premium = 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_COUNT_PREMIUM_FAILED, e);
            return -1;
        }
    }

    @Override
    public synchronized int migrateTo(DatabaseManager target) {
        if (!isConnected()) {
            return 0;
        }
        int count = 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_ALL_SQL)) {
            while (rs.next()) {
                String name = rs.getString("username");
                boolean isPremium = rs.getInt("is_premium") != 0;
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                // savePlayerSafe：条件 UPSERT，避免目标库中已有的正版记录被离线记录覆盖（#9）
                target.savePlayerSafe(name, isPremium, uuid);
                count++;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.DB_MIGRATION_EXCEPTION, e);
        }
        return count;
    }

    @Override
    public synchronized void backup(Path target) throws SQLException, IOException {
        if (!isConnected()) {
            throw new SQLException("SQLite database is not connected");
        }
        // 执行 WAL checkpoint，将 WAL 日志合并到主数据库文件，确保备份文件包含所有数据
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(FULL);");
        }
        // 复制数据库文件到目标路径
        Files.copy(dbPath, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Minecraft 用户名格式：1-16 字符，[A-Za-z0-9_] */
    static boolean isValidUsername(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    /** 检查表中列是否存在，不存在则添加（用于旧表迁移） */
    private void addColumnIfNotExists(Statement stmt, String columnName, String columnType) {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + TABLE_NAME + ")")) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) return;
            }
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + columnName + " " + columnType);
        } catch (SQLException ignored) {
            // 列已存在
        }
    }

    /** UUID 格式：8-4-4-4-12 hex */
    static boolean isValidUuid(String uuid) {
        return uuid != null && uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    @Override
    public synchronized void createAuthTable() {
        if (!isConnected()) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_AUTH_TABLE_SQL);
            stmt.execute(CREATE_AUTH_INDEX_SQL);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to create auth table", e);
        }
    }

    @Override
    public synchronized AuthAccount getAuthAccount(String username) {
        if (!isConnected()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(GET_AUTH_ACCOUNT_SQL)) {
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
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to get auth account for " + username, e);
        }
        return null;
    }

    @Override
    public synchronized void saveAuthAccount(AuthAccount account) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(SAVE_AUTH_ACCOUNT_SQL)) {
            ps.setString(1, account.username());
            ps.setString(2, account.passwordHash());
            ps.setLong(3, account.registerTime());
            ps.setLong(4, account.lastLoginTime());
            ps.setString(5, account.lastIp());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to save auth account for " + account.username(), e);
        }
    }

    @Override
    public synchronized void updateAuthPassword(String username, String passwordHash) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_AUTH_PASSWORD_SQL)) {
            ps.setString(1, passwordHash);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update auth password for " + username, e);
        }
    }

    @Override
    public synchronized void updateAuthLogin(String username, long loginTime, String ip) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_AUTH_LOGIN_SQL)) {
            ps.setLong(1, loginTime);
            ps.setString(2, ip);
            ps.setString(3, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update auth login for " + username, e);
        }
    }

    @Override
    public synchronized boolean deleteAuthAccount(String username) {
        if (!isConnected()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(DELETE_AUTH_ACCOUNT_SQL)) {
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to delete auth account for " + username, e);
            return false;
        }
    }

    @Override
    public synchronized boolean authAccountExists(String username) {
        if (!isConnected()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(AUTH_ACCOUNT_EXISTS_SQL)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to check auth account exists for " + username, e);
            return false;
        }
    }

    @Override
    public synchronized void createLoginHistoryTable() {
        if (!isConnected()) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_LOGIN_HISTORY_TABLE_SQL);
            stmt.execute(CREATE_LOGIN_HISTORY_INDEX_USER_TIME_SQL);
            stmt.execute(CREATE_LOGIN_HISTORY_INDEX_IP_SQL);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to create login history table", e);
        }
    }

    @Override
    public synchronized void recordLoginHistory(String username, String ip, long loginTime, boolean success, String country, String city) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(RECORD_LOGIN_HISTORY_SQL)) {
            ps.setString(1, username);
            ps.setString(2, ip);
            ps.setLong(3, loginTime);
            ps.setInt(4, success ? 1 : 0);
            ps.setString(5, country);
            ps.setString(6, city);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to record login history for " + username, e);
        }
    }

    @Override
    public synchronized List<LoginHistoryRecord> getRecentLoginHistory(String username, int limit) {
        if (!isConnected()) {
            return new ArrayList<>();
        }
        List<LoginHistoryRecord> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(GET_RECENT_LOGIN_HISTORY_SQL)) {
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
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to get recent login history for " + username, e);
        }
        return result;
    }

    @Override
    public synchronized void trimLoginHistory(String username, int maxRecords) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(TRIM_LOGIN_HISTORY_SQL)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setInt(3, maxRecords);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to trim login history for " + username, e);
        }
    }

    @Override
    public synchronized void createIpStatsTable() {
        if (!isConnected()) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_IP_STATS_TABLE_SQL);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to create ip stats table", e);
        }
    }

    @Override
    public synchronized IpStatsRecord getIpStats(String ip) {
        if (!isConnected()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(GET_IP_STATS_SQL)) {
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
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to get ip stats for " + ip, e);
        }
        return null;
    }

    @Override
    public synchronized void incrementIpAccountCount(String ip) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_IP_ACCOUNT_COUNT_SQL)) {
            ps.setString(1, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to increment ip account count for " + ip, e);
        }
    }

    @Override
    public synchronized void decrementIpAccountCount(String ip) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(DECREMENT_IP_ACCOUNT_COUNT_SQL)) {
            ps.setString(1, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to decrement ip account count for " + ip, e);
        }
    }

    @Override
    public synchronized void updateIpFailureStats(String ip, int failedAttempts, long lastFailureTime, long cooldownUntil) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_IP_FAILURE_STATS_SQL)) {
            ps.setString(1, ip);
            ps.setInt(2, failedAttempts);
            ps.setLong(3, lastFailureTime);
            ps.setLong(4, cooldownUntil);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to update ip failure stats for " + ip, e);
        }
    }
}
