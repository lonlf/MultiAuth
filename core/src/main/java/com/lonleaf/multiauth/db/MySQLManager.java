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
    /** JDBC 驱动 TCP 建连超时（毫秒）：防火墙 DROP 等场景快速失败，避免全局卡顿 */
    private static final int JDBC_CONNECT_TIMEOUT_MS = 5_000;
    /** JDBC socket 读超时（毫秒）：防止已建立连接读挂起拖住调用线程 */
    private static final int JDBC_SOCKET_TIMEOUT_MS = 30_000;

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String tablePrefix;
    private final boolean useSsl;
    private final Logger logger;

    private volatile HikariDataSource dataSource;
    /** 已关闭标志：disconnect() 置位后禁止再 connect()，防止 reload/shutdown 后旧心跳线程
     *  在已弃用的实例上重建连接池造成泄漏（#9） */
    private volatile boolean closed;
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

    @Override
    public synchronized void connect() throws SQLException {
        // 已显式关闭（reload 切换数据库 / 插件关服）的实例禁止重建连接池：
        // 旧心跳线程可能仍在运行并持有本引用，此时新建的池无人关闭，形成连接池泄漏（#9）
        if (closed) {
            throw new SQLException("MySQLManager has been closed");
        }
        if (dataSource != null && !dataSource.isClosed()) {
            // 连接池存活，但如果 ping 失败则需要重建（heartbeat 重连对 MySQL 才会真正生效）
            if (ping()) {
                return;
            }
            // ping 失败，关闭旧池重建（不递归调用 connect，避免无限循环）
            try { dataSource.close(); } catch (Exception e) {
                logger.fine(Messages.get(Messages.DB_CLOSE_DATA_SOURCE_FAILED, e.getMessage()));
            }
            dataSource = null;
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl + "&serverTimezone=UTC&characterEncoding=utf8"
                + "&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + JDBC_CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + JDBC_SOCKET_TIMEOUT_MS);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("MultiAuth-MySQL");
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS * 1000L);
        config.setIdleTimeout(IDLE_TIMEOUT_MINUTES * 60_000L);
        config.setMaxLifetime(MAX_LIFETIME_MINUTES * 60_000L);
        // 不启用 HikariCP keepalive（默认 0=禁用）：插件心跳（heartbeat-interval，默认 60s）每个周期
        // 都会借连接执行 SELECT 1，配合下方 connectionTestQuery 的借出前校验，池中连接始终被探活，
        // 与 keepalive 周期重叠只会造成重复 ping（#16）
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
        } catch (SQLException e) {
            // 建表/迁移失败（如账号无 DDL 权限）：关闭刚创建的连接池并置空，
            // 避免"已连接但表缺失"的半健康状态与连接池泄漏
            HikariDataSource ds = dataSource;
            dataSource = null;
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception closeEx) {
                    logger.fine(Messages.get(Messages.DB_CLOSE_DATA_SOURCE_FAILED, closeEx.getMessage()));
                }
            }
            throw new SQLException("Failed to initialize MySQL tables: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void disconnect() {
        closed = true;
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
                ps.setString(1, normName(username));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("username");
                        boolean isPremium = rs.getInt("is_premium") != 0;
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(rs.getString("uuid"));
                        } catch (IllegalArgumentException e) {
                            logger.warning(Messages.get(Messages.DB_PARSE_UUID_FAILED, username, e.getMessage()));
                            return null;
                        }
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
                ps.setString(1, normName(username));
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
                ps.setString(1, normName(username));
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
                ps.setString(7, normName(username));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_UPDATE_LOCATION_FAILED, username), e);
        }
    }

    /** 检查 MySQL 表中列是否存在，不存在则添加 */
    private void addMysqlColumnIfNotExists(Connection conn, String columnName, String columnType) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM " + tableName + " LIKE '" + columnName + "'")) {
            if (!rs.next()) {
                stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
            }
        } catch (SQLException e) {
            logger.fine(Messages.get(Messages.DB_COLUMN_EXISTS, e.getMessage()));
        }
    }

    @Override
    public boolean exists(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(existsSql())) {
                ps.setString(1, normName(username));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_EXISTS_FAILED, username), e);
            return false;
        }
    }

    /** 规范化用户名：统一小写（Minecraft 用户名不区分大小写，DAO 层统一存储与查询语义） */
    private static String normName(String username) {
        return username == null ? null : username.toLowerCase(java.util.Locale.ROOT);
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
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString("uuid"));
                    } catch (IllegalArgumentException e) {
                        logger.warning(Messages.get(Messages.DB_PARSE_UUID_FAILED, name, e.getMessage()));
                        continue;
                    }
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_CREATE_INDEX_FAILED, e.getMessage()), e);
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
                } catch (SQLException e) {
                    logger.fine(Messages.get(Messages.DB_INDEX_EXISTS, e.getMessage()));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_CREATE_AUTH_TABLE_FAILED), e);
        }
    }

    @Override
    public AuthAccount getAuthAccount(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(getAuthAccountSql())) {
                ps.setString(1, normName(username));
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_GET_AUTH_ACCOUNT_FAILED, username), e);
        }
        return null;
    }

    @Override
    public boolean saveAuthAccount(AuthAccount account) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(saveAuthAccountSql())) {
                ps.setString(1, normName(account.username()));
                ps.setString(2, account.passwordHash());
                ps.setLong(3, account.registerTime());
                ps.setLong(4, account.lastLoginTime());
                ps.setString(5, account.lastIp());
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_SAVE_AUTH_ACCOUNT_FAILED, account.username()), e);
            return false;
        }
    }

    @Override
    public boolean updateAuthPassword(String username, String passwordHash) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(updateAuthPasswordSql())) {
                ps.setString(1, passwordHash);
                ps.setString(2, normName(username));
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_UPDATE_AUTH_PASSWORD_FAILED, username), e);
            return false;
        }
    }

    @Override
    public void updateAuthLogin(String username, long loginTime, String ip) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(updateAuthLoginSql())) {
                ps.setLong(1, loginTime);
                ps.setString(2, ip);
                ps.setString(3, normName(username));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_UPDATE_AUTH_LOGIN_FAILED, username), e);
        }
    }

    @Override
    public boolean deleteAuthAccount(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(deleteAuthAccountSql())) {
                ps.setString(1, normName(username));
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_DELETE_AUTH_ACCOUNT_FAILED, username), e);
            return false;
        }
    }

    @Override
    public boolean authAccountExists(String username) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(authAccountExistsSql())) {
                ps.setString(1, normName(username));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_AUTH_ACCOUNT_EXISTS_FAILED, username), e);
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
                } catch (SQLException e) {
                    logger.fine(Messages.get(Messages.DB_INDEX_EXISTS, e.getMessage()));
                }
                try {
                    stmt.execute(createLoginHistoryIndexIpSql());
                } catch (SQLException e) {
                    logger.fine(Messages.get(Messages.DB_INDEX_EXISTS, e.getMessage()));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_CREATE_LOGIN_HISTORY_TABLE_FAILED), e);
        }
    }

    @Override
    public void recordLoginHistory(String username, String ip, long loginTime, boolean success, String country, String city) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(recordLoginHistorySql())) {
                ps.setString(1, normName(username));
                ps.setString(2, ip);
                ps.setLong(3, loginTime);
                ps.setInt(4, success ? 1 : 0);
                ps.setString(5, country);
                ps.setString(6, city);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_RECORD_LOGIN_HISTORY_FAILED, username), e);
        }
    }

    @Override
    public List<LoginHistoryRecord> getRecentLoginHistory(String username, int limit) {
        List<LoginHistoryRecord> result = new ArrayList<>();
        try (Connection conn = borrowConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(getRecentLoginHistorySql())) {
                ps.setString(1, normName(username));
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_GET_LOGIN_HISTORY_FAILED, username), e);
        }
        return result;
    }

    @Override
    public List<LoginHistoryRecord> getLoginHistoryChecked(String username, int limit) throws SQLException {
        List<LoginHistoryRecord> result = new ArrayList<>();
        try (Connection conn = borrowConnection()) {
            if (conn == null) throw new SQLException("no connection available");
            try (PreparedStatement ps = conn.prepareStatement(getRecentLoginHistorySql())) {
                ps.setString(1, normName(username));
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
        }
        return result;
    }

    @Override
    public void trimLoginHistory(String username, int maxRecords) {
        try (Connection conn = borrowConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(trimLoginHistorySql())) {
                ps.setString(1, normName(username));
                ps.setString(2, normName(username));
                ps.setInt(3, maxRecords);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, Messages.get(Messages.DB_TRIM_LOGIN_HISTORY_FAILED, username), e);
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_CREATE_IP_STATS_TABLE_FAILED), e);
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_GET_IP_STATS_FAILED, ip), e);
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_INCREMENT_IP_ACCOUNT_FAILED, ip), e);
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
            logger.log(Level.WARNING, Messages.get(Messages.DB_DECREMENT_IP_ACCOUNT_FAILED, ip), e);
        }
    }
}
