package com.lonleaf.multiauth;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.lonleaf.multiauth.config.AuthConfig;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Velocity 端配置管理器，使用 TOML 格式。
 */
public class VelocityConfig {

    private static final String CONFIG_FILE = "config.toml";

    private final Path configPath;
    private final Logger logger;
    private final AuthConfig config = new AuthConfig();

    private CommentedFileConfig fileConfig;

    public VelocityConfig(Path dataDirectory, Logger logger) {
        this.configPath = dataDirectory.resolve(CONFIG_FILE);
        this.logger = logger;
    }

    public void load() {
        CommentedFileConfig newConfig = null;
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                try (InputStream in = getClass().getResourceAsStream("/" + CONFIG_FILE)) {
                    if (in != null) {
                        Files.copy(in, configPath);
                        logger.info(Messages.get(Messages.CONFIG_DEFAULT_CREATED));
                    }
                }
            }

            newConfig = CommentedFileConfig.builder(configPath).build();
            newConfig.load();

            // 先在新配置上 applyConfig，成功后再替换 fileConfig，避免 applyConfig 失败后
            // config 处于半更新状态但旧 fileConfig 已关闭（无法回退）
            applyConfig(newConfig);

            // applyConfig 成功后才替换 fileConfig（关闭旧的，保留新的供后续读取）
            CommentedFileConfig old = this.fileConfig;
            this.fileConfig = newConfig;
            newConfig = null; // 已交接，避免被 catch 误关闭
            if (old != null) {
                try { old.close(); } catch (Exception ignored) {}
            }

            debug(Messages.get(Messages.VELOCITY_CONFIG_DEBUG,
                    String.valueOf(config.isUseMojangUuid()),
                    String.valueOf(config.getAuthList()),
                    config.getDatabaseType()));

        } catch (Exception e) {
            // 配置加载失败（TOML 格式错误 / IO 异常等）：关闭新建的 fileConfig 避免资源泄漏，
            // 保留旧配置继续运行，不让插件加载失败
            if (newConfig != null) {
                try { newConfig.close(); } catch (Exception ignored) {}
            }
            logger.error(Messages.get(Messages.CONFIG_LOAD_FAILED, e.getMessage()), e);
        }
    }

    public void reload() {
        debug(Messages.get(Messages.CONFIG_RELOADING));
        load();
    }

    /** debug 日志：仅 debug=true 时输出 */
    private void debug(String msg) {
        if (config.isDebug()) {
            logger.info("[DEBUG] " + msg);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyConfig(CommentedFileConfig source) {
        // 语言
        config.setLanguage(source.getOrElse("language", "zh_cn"));

        // 调试模式
        config.setDebug(source.getOrElse("debug", false));

        // 基本配置
        config.setUseMojangUuid(source.getOrElse("use-mojang-uuid", true));
        // 跨服会话同步签名密钥（必须与 Spigot 端配置一致，空则关闭验签）
        config.setSessionSyncSecret(source.getOrElse("session-sync-secret", ""));

        List<String> list = source.getOrElse("auth-list", new ArrayList<>());
        config.setAuthList(new HashSet<>(list));

        // Mojang API - 加载备用 API 列表（新格式 fallback-urls，旧格式 fallback-url 兼容）
        List<String> fallbackUrls = source.getOrElse("mojang-api.fallback-urls", new ArrayList<>());
        if (fallbackUrls == null || fallbackUrls.isEmpty()) {
            // 兼容旧格式：单个 fallback-url
            String oldUrl = source.getOrElse("mojang-api.fallback-url", "");
            if (oldUrl != null && !oldUrl.isBlank()) {
                fallbackUrls = new ArrayList<>();
                fallbackUrls.add(oldUrl);
            }
        }
        config.setFallbackApiUrls(fallbackUrls);

        // 数据库
        config.setDatabaseType(source.getOrElse("database.type", "sqlite"));
        config.setSqliteFile(source.getOrElse("database.sqlite-file", "multiauth.db"));
        config.setMysqlHost(source.getOrElse("database.mysql-host", "localhost"));
        config.setMysqlPort(source.getIntOrElse("database.mysql-port", 3306));
        config.setMysqlDatabase(source.getOrElse("database.mysql-database", "multiauth"));
        config.setMysqlUsername(source.getOrElse("database.mysql-username", "root"));
        config.setMysqlPassword(source.getOrElse("database.mysql-password", ""));
        config.setMysqlTablePrefix(source.getOrElse("database.mysql-table-prefix", "multiauth_"));
        config.setMysqlUseSsl(source.getOrElse("database.mysql-use-ssl", false));
        config.setHeartbeatInterval(source.getIntOrElse("database.heartbeat-interval", 60));

        // 备份
        config.setBackupEnabled(source.getOrElse("backup.enabled", true));
        config.setBackupIntervalHours(source.getIntOrElse("backup.interval-hours", 24));
        config.setBackupDir(source.getOrElse("backup.dir", "backups"));
        config.setBackupMaxCount(source.getIntOrElse("backup.max-count", 7));
    }

    public AuthConfig getConfig() {
        return config;
    }

    public boolean isUseMojangUuid() {
        return config.isUseMojangUuid();
    }

    public boolean isInAuthList(String username) {
        return config.isInAuthList(username);
    }

    public void close() {
        if (fileConfig != null) {
            fileConfig.close();
        }
    }
}
