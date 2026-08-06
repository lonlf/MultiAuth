package com.lonleaf.multiauth;

import com.lonleaf.multiauth.auth.AuthCrypto;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.db.DatabaseManager;
import com.lonleaf.multiauth.db.MySQLManager;
import com.lonleaf.multiauth.db.SQLiteManager;
import com.lonleaf.multiauth.mojang.MojangApiService;
import com.lonleaf.multiauth.mojang.MojangSessionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Core {

    private volatile AuthConfig config;
    private volatile DatabaseManager database;
    private volatile MojangSessionService mojangService;
    private volatile MojangApiService mojangApiService;
    private volatile AuthManager authManager;
    private AuthCrypto globalCrypto;
    private final Logger logger;
    private final Path dataDirectory;

    private ScheduledExecutorService scheduler;
    private volatile boolean databaseHealthy = true;

    public Core(AuthConfig config, Path dataDirectory, Logger logger) {
        this.config = config;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    /**
     * 初始化所有服务：数据库、备份。
     * @return true 如果初始化成功，false 如果数据库连接失败
     */
    public boolean init() {
        // 根据配置设置日志级别：debug=false 仅 INFO+，debug=true 输出 FINE 调试日志
        applyLogLevel();

        // 初始化全局 RSA 密钥对（2048 位，复用于所有加密握手）
        this.globalCrypto = new AuthCrypto();
        logger.fine(Messages.get(Messages.CORE_RSA_KEY_INIT));

        // 初始化数据库
        if (!initDatabase()) {
            databaseHealthy = false;
            logger.severe(Messages.DB_INIT_FAILED);
            return false;
        }

        // 初始化 Mojang 服务（仅创建对象，不发起任何 HTTP 请求）
        this.mojangService = new MojangSessionService();

        boolean proxyMode = Boolean.TRUE.equals(config.isProxy());
        if (proxyMode) {
            // proxy=true：Mojang 验证完全由 Velocity 端执行，Spigot 端不做任何 API 调用
            logger.fine(Messages.get(Messages.CORE_PROXY_MODE_DEBUG));
            this.mojangApiService = new MojangApiService(java.util.Collections.emptyList(), logger);
        } else {
            // proxy=false / Velocity 端：启用 Mojang API（仅在玩家连接时调用）
            this.mojangApiService = new MojangApiService(config.getFallbackApiUrls(), logger);
            logger.fine(Messages.get(Messages.CORE_API_INIT_DEBUG));
        }

        // 初始化 AuthManager
        this.authManager = new AuthManager(database, mojangService, mojangApiService);

        // 启动定时任务（仅数据库心跳 + 备份，不含 API 心跳）
        startSchedulers();

        logger.info(proxyMode ? Messages.CORE_INIT_PROXY : Messages.CORE_INIT_STANDALONE);
        return true;
    }

    private boolean initDatabase() {
        try {
            String type = config.getDatabaseType().toLowerCase();
            if ("mysql".equals(type)) {
                database = new MySQLManager(
                        config.getMysqlHost(), config.getMysqlPort(),
                        config.getMysqlDatabase(), config.getMysqlUsername(),
                        config.getMysqlPassword(), config.getMysqlTablePrefix(),
                        config.isMysqlUseSsl(), logger
                );
            } else {
                Path dbPath = dataDirectory.resolve(config.getSqliteFile());
                database = new SQLiteManager(dbPath, logger);
            }

            database.connect();
            logger.info(Messages.get(Messages.DB_CONNECTED, config.getDatabaseType()));

            if (!database.ping()) {
                logger.severe(Messages.DB_PING_FAILED);
                return false;
            }
            return true;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.get(Messages.DB_CONNECTION_FAILED, e.getMessage()), e);
            return false;
        }
    }

    /**
     * 启动定时任务：仅数据库心跳 + 备份。
     * 不再启动 API 健康检查——Mojang API 仅在玩家连接时按需调用。
     */
    private void startSchedulers() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "multiauth-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 数据库心跳（无论 proxy 与否，数据库始终需要保活）
        int heartbeatSec = config.getHeartbeatInterval();
        if (heartbeatSec > 0) {
            scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);
            logger.fine(Messages.get(Messages.CORE_HEARTBEAT_SCHEDULED, String.valueOf(heartbeatSec)));
        }

        // 备份
        if (config.isBackupEnabled()) {
            int hours = config.getBackupIntervalHours();
            if (hours > 0) {
                long initialDelay = hours;
                scheduler.scheduleAtFixedRate(this::performBackup, initialDelay, hours, TimeUnit.HOURS);
                logger.fine(Messages.get(Messages.CORE_BACKUP_SCHEDULED, String.valueOf(hours)));
            }
        }
    }

    private void heartbeat() {
        try {
            if (database != null && database.isConnected()) {
                if (!database.ping()) {
                    logger.warning(Messages.DB_HEARTBEAT_PING_FAILED);
                    try {
                        database.connect();
                        if (database.ping()) {
                            databaseHealthy = true;
                            logger.info(Messages.DB_RECONNECTED);
                        } else {
                            databaseHealthy = false;
                            logger.severe(Messages.DB_RECONNECT_FAILED);
                        }
                    } catch (SQLException e) {
                        databaseHealthy = false;
                        logger.severe(Messages.get(Messages.DB_RECONNECT_FAILED, e.getMessage()));
                    }
                } else {
                    databaseHealthy = true;
                }
            } else {
                databaseHealthy = false;
                logger.warning(Messages.DB_HEARTBEAT_NOT_CONNECTED);
                try {
                    database.connect();
                    if (database.ping()) {
                        databaseHealthy = true;
                        logger.info(Messages.DB_RECONNECTED);
                    } else {
                        databaseHealthy = false;
                        logger.severe(Messages.DB_RECONNECT_FAILED);
                    }
                } catch (SQLException e) {
                    databaseHealthy = false;
                    logger.severe(Messages.get(Messages.DB_RECONNECT_FAILED, e.getMessage()));
                }
            }
        } catch (Exception e) {
            databaseHealthy = false;
            logger.warning(Messages.get(Messages.DB_HEARTBEAT_ERROR, e.getMessage()));
        }
    }

    private boolean performBackup() {
        try {
            Path backupDir = dataDirectory.resolve(config.getBackupDir());
            Files.createDirectories(backupDir);

            // 文件名格式：yyyy-MM-dd_HH-mm-ss_databaseBackup（日期在前，字母排序即时间排序）
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String ext = config.getDatabaseType().equalsIgnoreCase("mysql") ? ".sql" : ".db";
            Path backupFile = backupDir.resolve(timestamp + "_databaseBackup" + ext);

            database.backup(backupFile);
            logger.info(Messages.get(Messages.DB_BACKUP_CREATED, backupFile.toString()));

            // 清理旧备份
            cleanOldBackups(backupDir);
            return true;
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.DB_BACKUP_FAILED, e.getMessage()));
            return false;
        }
    }

    private void cleanOldBackups(Path backupDir) {
        try (var stream = Files.list(backupDir)) {
            var files = stream
                    .filter(f -> f.getFileName().toString().endsWith("_databaseBackup.db")
                              || f.getFileName().toString().endsWith("_databaseBackup.sql"))
                    .sorted()
                    .toList();

            int maxCount = config.getBackupMaxCount();
            if (files.size() > maxCount) {
                for (int i = 0; i < files.size() - maxCount; i++) {
                    Files.deleteIfExists(files.get(i));
                    logger.fine(Messages.get(Messages.DB_BACKUP_DELETED_OLD, files.get(i).getFileName().toString()));
                }
            }
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.DB_BACKUP_CLEAN_FAILED, e.getMessage()));
        }
    }

    /**
     * 执行手动备份。
     */
    public boolean manualBackup() {
        return performBackup();
    }

    /**
     * 执行数据库迁移。
     * @param targetType "sqlite" 或 "mysql"
     * @return 迁移的记录数，-1 表示失败
     */
    public int migrateDatabase(String targetType) {
        try {
            DatabaseManager target;
            String type = targetType.toLowerCase();

            if ("mysql".equals(type)) {
                target = new MySQLManager(
                        config.getMysqlHost(), config.getMysqlPort(),
                        config.getMysqlDatabase(), config.getMysqlUsername(),
                        config.getMysqlPassword(), config.getMysqlTablePrefix(),
                        config.isMysqlUseSsl(), logger
                );
            } else {
                Path dbPath = dataDirectory.resolve("migration_" + config.getSqliteFile());
                target = new SQLiteManager(dbPath, logger);
            }

            target.connect();
            int count = database.migrateTo(target);
            target.disconnect();

            logger.info(Messages.get(Messages.DB_MIGRATION_COMPLETE, String.valueOf(count), targetType));
            return count;
        } catch (Exception e) {
            logger.severe(Messages.get(Messages.DB_MIGRATION_FAILED, e.getMessage()));
            return -1;
        }
    }

    /**
     * 重新加载配置（不发起 API 检查，API 仅在玩家连接时按需调用）。
     */
    public void reload(AuthConfig newConfig) {
        boolean dbChanged = !dbConfigSignature(this.config).equals(dbConfigSignature(newConfig));
        this.config = newConfig;
        applyLogLevel(); // 重载后可能切换 debug 模式

        // 保存旧服务引用，待新 authManager 切换后再关闭，避免 auth 线程使用已 close 的 HttpClient
        MojangSessionService oldMojangService = this.mojangService;
        MojangApiService oldMojangApiService = this.mojangApiService;

        // 先停止定时任务（heartbeat/backup），避免在 database 断开/重建期间
        // heartbeat 在旧实例上调用 connect() 导致 SQLite 文件锁冲突或连接泄漏
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        boolean dbRebuilt = false;
        if (dbChanged) {
            logger.info(Messages.DB_REBUILD_CONNECTION);
            if (database != null) {
                try {
                    database.disconnect();
                } catch (Exception e) {
                    logger.fine("Cleanup error: " + e.getMessage());
                }
            }
            databaseHealthy = false;
            if (!initDatabase()) {
                databaseHealthy = false;
                logger.severe(Messages.DB_INIT_FAILED);
            } else {
                dbRebuilt = true;
            }
        }

        boolean proxyMode = Boolean.TRUE.equals(newConfig.isProxy());

        // 1. 先创建新服务（新 HttpClient）
        // mojangService：仅数据库变更时重建，否则复用旧实例
        MojangSessionService newMojangService;
        if (dbRebuilt) {
            newMojangService = new MojangSessionService();
        } else {
            newMojangService = oldMojangService;
            oldMojangService = null; // 复用旧实例，无需关闭
        }

        // mojangApiService：每次 reload 都重建
        MojangApiService newMojangApiService;
        if (proxyMode) {
            newMojangApiService = new MojangApiService(java.util.Collections.emptyList(), logger);
        } else {
            newMojangApiService = new MojangApiService(newConfig.getFallbackApiUrls(), logger);
        }

        // 2. 创建新 authManager（引用新服务）
        AuthManager newAuthManager = new AuthManager(database, newMojangService, newMojangApiService);

        // 3. 切换引用（volatile 写）：auth 线程此后看到新服务
        if (dbRebuilt) {
            this.mojangService = newMojangService;
        }
        this.mojangApiService = newMojangApiService;
        this.authManager = newAuthManager;

        // 4. 最后 close 旧服务（旧 HttpClient），此时 auth 线程已使用新 authManager
        if (oldMojangService != null) {
            try {
                oldMojangService.close();
            } catch (Exception e) {
                logger.fine("Cleanup error: " + e.getMessage());
            }
        }
        if (oldMojangApiService != null) {
            try {
                oldMojangApiService.close();
            } catch (Exception e) {
                logger.fine("Cleanup error: " + e.getMessage());
            }
        }

        // 心跳/备份间隔等配置可能已变更：重启定时任务使其生效（#9）
        // scheduler 已在上方提前 shutdown，此处直接启动新的
        startSchedulers();

        logger.fine(proxyMode
                ? Messages.get(Messages.CORE_RELOADED_PROXY)
                : Messages.get(Messages.CORE_RELOADED_STANDALONE));
    }

    /** 数据库配置签名：任一字段变化即视为配置变更，需重建连接 */
    private static String dbConfigSignature(AuthConfig c) {
        return c.getDatabaseType().toLowerCase(java.util.Locale.ROOT)
                + "|" + c.getSqliteFile()
                + "|" + c.getMysqlHost()
                + "|" + c.getMysqlPort()
                + "|" + c.getMysqlDatabase()
                + "|" + c.getMysqlUsername()
                + "|" + c.getMysqlPassword()
                + "|" + c.getMysqlTablePrefix()
                + "|" + c.isMysqlUseSsl();
    }

    /**
     * 根据配置的 debug 标志设置 logger 级别。
     */
    private void applyLogLevel() {
        logger.setLevel(config.isDebug() ? Level.ALL : Level.INFO);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // 关闭 HTTP 服务，释放 HttpClient 资源
        if (mojangService != null) {
            try {
                mojangService.close();
            } catch (Exception e) {
                logger.warning(Messages.get(Messages.CORE_CLOSE_MOJANG_SESSION_FAILED, e.getMessage()));
            }
        }
        if (mojangApiService != null) {
            try {
                mojangApiService.close();
            } catch (Exception e) {
                logger.warning(Messages.get(Messages.CORE_CLOSE_MOJANG_API_FAILED, e.getMessage()));
            }
        }
        try {
            if (database != null) database.disconnect();
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.CORE_DISCONNECT_DB_FAILED, e.getMessage()));
        }
        logger.info(Messages.CORE_SHUTDOWN_COMPLETE);
    }

    // ==================== Getters ====================

    public AuthManager getAuthManager() {
        return authManager;
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    public AuthConfig getConfig() {
        return config;
    }

    /** 返回 Core 使用的 logger（供平台模块统一日志级别控制） */
    public Logger getLogger() {
        return logger;
    }

    public boolean isDatabaseHealthy() {
        return databaseHealthy;
    }

    public MojangApiService getMojangApiService() {
        return mojangApiService;
    }
}
