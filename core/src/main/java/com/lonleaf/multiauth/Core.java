package com.lonleaf.multiauth;

import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.db.DatabaseManager;
import com.lonleaf.multiauth.db.MySQLManager;
import com.lonleaf.multiauth.db.SQLiteManager;
import com.lonleaf.multiauth.geo.IpGeoService;
import com.lonleaf.multiauth.mojang.MojangApiService;
import com.lonleaf.multiauth.mojang.MojangSessionService;
import com.lonleaf.multiauth.update.UpdateChecker;

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
    private volatile IpGeoService ipGeoService;
    private volatile UpdateChecker updateChecker;
    private volatile String currentVersion = "";
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

        // 初始化数据库
        if (!initDatabase()) {
            databaseHealthy = false;
            logger.severe(Messages.DB_INIT_FAILED);
            return false;
        }

        // 初始化 Mojang 服务（仅创建对象，不发起任何 HTTP 请求）
        this.mojangService = new MojangSessionService(config.getMojangRequestLimit());

        boolean proxyMode = Boolean.TRUE.equals(config.isProxy());
        if (proxyMode) {
            // proxy=true：Mojang 验证完全由 Velocity 端执行，Spigot 端不做任何 API 调用
            logger.fine(Messages.get(Messages.CORE_PROXY_MODE_DEBUG));
            this.mojangApiService = new MojangApiService(false, java.util.Collections.emptyList(), logger, config.getMojangRequestLimit());
        } else {
            // proxy=false / Velocity 端：启用 Mojang API（仅在玩家连接时调用）
            this.mojangApiService = new MojangApiService(true, config.getFallbackApiUrls(), logger, config.getMojangRequestLimit());
            logger.fine(Messages.get(Messages.CORE_API_INIT_DEBUG));
        }

        // 初始化 AuthManager
        this.authManager = new AuthManager(database, mojangService, mojangApiService);

        // 初始化 IP 地理位置服务（供命令展示 geo 信息与安全模块复用；配置禁用时内部直接置为不可用）
        this.ipGeoService = new IpGeoService(config, dataDirectory, logger);

        // 初始化更新检查器（仅创建对象，首次检查由 startSchedulers 延迟触发）
        this.updateChecker = new UpdateChecker(logger);

        // 启动定时任务（仅数据库心跳 + 备份 + 更新检查，不含 API 心跳）
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
        scheduler = Executors.newScheduledThreadPool(3, r -> {
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

        // 更新检查：每次服务器启动 5 秒后进行一次检查，之后按配置间隔执行（interval-hours 小时）
        if (updateChecker != null && config.isUpdateCheckEnabled()) {
            int hours = config.getUpdateCheckIntervalHours();
            if (hours > 0) {
                scheduler.scheduleAtFixedRate(this::checkForUpdates, 5, hours * 3600L, TimeUnit.SECONDS);
                logger.fine(Messages.get(Messages.UPDATE_CHECK_ENABLED_LOG,
                        updateChecker.getRepository(), String.valueOf(hours)));
            }
        }
    }

    /** 提交异步更新检查（scheduler 线程仅负责提交，不阻塞等待网络） */
    private void checkForUpdates() {
        try {
            if (updateChecker != null && currentVersion != null && !currentVersion.isBlank()) {
                updateChecker.checkUpdateAsync(currentVersion);
            }
        } catch (Exception e) {
            logger.fine(Messages.get(Messages.UPDATE_CHECK_FAILED_LOG, e.getMessage()));
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

    public boolean manualBackup() {
        return performBackup();
    }

    /**
     * 执行数据库迁移。
     * @param targetType "sqlite" 或 "mysql"
     * @return 迁移的记录数，-1 表示失败
     */
    public int migrateDatabase(String targetType) {
        DatabaseManager target = null;
        try {
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

            logger.info(Messages.get(Messages.DB_MIGRATION_COMPLETE, String.valueOf(count), targetType));
            return count;
        } catch (Exception e) {
            logger.severe(Messages.get(Messages.DB_MIGRATION_FAILED, e.getMessage()));
            return -1;
        } finally {
            // 无论成功/失败都关闭目标连接，避免迁移中断时目标连接池泄漏
            if (target != null) {
                try {
                    target.disconnect();
                } catch (Exception e) {
                    logger.fine(Messages.get(Messages.CORE_CLEANUP_ERROR, e.getMessage()));
                }
            }
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

        if (dbChanged) {
            logger.info(Messages.DB_REBUILD_CONNECTION);
            if (database != null) {
                try {
                    database.disconnect();
                } catch (Exception e) {
                    logger.fine(Messages.get(Messages.CORE_CLEANUP_ERROR, e.getMessage()));
                }
            }
            databaseHealthy = false;
            if (!initDatabase()) {
                databaseHealthy = false;
                logger.severe(Messages.DB_INIT_FAILED);
            }
        }

        boolean proxyMode = Boolean.TRUE.equals(newConfig.isProxy());

        // 1. 先创建新服务（新 HttpClient）
        // mojangService：每次 reload 都重建（mojang-request-limit 配置变更后立即生效）
        MojangSessionService newMojangService = new MojangSessionService(newConfig.getMojangRequestLimit());

        // mojangApiService：每次 reload 都重建
        MojangApiService newMojangApiService;
        if (proxyMode) {
            newMojangApiService = new MojangApiService(false, java.util.Collections.emptyList(), logger, newConfig.getMojangRequestLimit());
        } else {
            newMojangApiService = new MojangApiService(true, newConfig.getFallbackApiUrls(), logger, newConfig.getMojangRequestLimit());
        }

        // 2. 创建新 authManager（引用新服务）
        AuthManager newAuthManager = new AuthManager(database, newMojangService, newMojangApiService);

        // 3. 切换引用（volatile 写）：auth 线程此后看到新服务
        this.mojangService = newMojangService;
        this.mojangApiService = newMojangApiService;
        this.authManager = newAuthManager;

        // 重建 IpGeoService：按新配置（geo 开关/xdb 文件/缓存策略）重新加载
        IpGeoService oldIpGeoService = this.ipGeoService;
        this.ipGeoService = new IpGeoService(newConfig, dataDirectory, logger);
        if (oldIpGeoService != null) {
            try {
                oldIpGeoService.close();
            } catch (Exception e) {
                logger.fine(Messages.get(Messages.CORE_CLEANUP_ERROR, e.getMessage()));
            }
        }

        // 更新检查器复用现有实例（保留 lastResult，避免 reload 后更新提示与 status 展示暂时丢失）；
        // 仅当尚未初始化时创建。仓库为固定默认值，无需随 reload 重建；间隔配置变更由下方 startSchedulers 重新调度生效
        if (this.updateChecker == null) {
            this.updateChecker = new UpdateChecker(logger);
        }

        // 4. 最后 close 旧服务（旧 HttpClient），此时 auth 线程已使用新 authManager
        if (oldMojangService != null) {
            try {
                oldMojangService.close();
            } catch (Exception e) {
                logger.fine(Messages.get(Messages.CORE_CLEANUP_ERROR, e.getMessage()));
            }
        }
        if (oldMojangApiService != null) {
            try {
                oldMojangApiService.close();
            } catch (Exception e) {
                logger.fine(Messages.get(Messages.CORE_CLEANUP_ERROR, e.getMessage()));
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

    private void applyLogLevel() {
        logger.setLevel(config.isDebug() ? Level.ALL : Level.INFO);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (updateChecker != null) {
            updateChecker.close();
        }
        if (ipGeoService != null) {
            try {
                ipGeoService.close();
            } catch (Exception e) {
                logger.fine(Messages.get(Messages.CORE_CLEANUP_ERROR, e.getMessage()));
            }
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

    public IpGeoService getIpGeoService() {
        return ipGeoService;
    }

    /** 设置当前插件版本（由平台模块在 init 前注入），供更新检查与 status 命令使用 */
    public void setCurrentVersion(String version) {
        this.currentVersion = version != null ? version : "";
    }

    /** 返回当前插件版本（平台模块未注入时为 ""） */
    public String getCurrentVersion() {
        return currentVersion;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}
