package com.lonleaf.multiauth;

import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.Messages;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Spigot 端配置管理器，使用 YAML 格式。
 */
public class SpigotConfig {

    private final JavaPlugin plugin;
    private final java.util.logging.Logger logger;
    // volatile：reload 在主线程修改，其他线程（事件监听器/异步任务）读取，保证可见性
    private volatile AuthConfig config = new AuthConfig();

    public SpigotConfig(JavaPlugin plugin, java.util.logging.Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * 加载配置文件；saveDefaultConfig 会自动释放资源中的 config.yml。
     */
    public void load() {
        // 首次启动（config.yml 不存在）：saveDefaultConfig 复制模板后按系统语言自动设置 language 项
        boolean firstStart = !new java.io.File(plugin.getDataFolder(), "config.yml").exists();
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration fileConfig = plugin.getConfig();
        if (firstStart) {
            applySystemLanguage(fileConfig);
        }
        applyConfig(fileConfig);

        logger.fine(Messages.get(Messages.CONFIG_LOADED_DEBUG, String.valueOf(config.isProxy()), String.valueOf(config.isUseMojangUuid()), String.valueOf(config.isDebug())));
    }

    /**
     * 首次启动时根据系统语言自动设置 language 项（仅当检测结果与默认 en_gb 不同时写入）。
     */
    private void applySystemLanguage(FileConfiguration fileConfig) {
        String detected = Messages.detectSystemLanguage();
        if (!"en_gb".equals(detected)) {
            fileConfig.set("language", detected);
            plugin.saveConfig();
        }
    }

    /**
     * 重新加载配置文件。
     */
    public void reload() {
        logger.fine(Messages.get(Messages.CONFIG_RELOADING));
        load();
    }

    @SuppressWarnings("unchecked")
    private void applyConfig(FileConfiguration f) {
        // 构建新的 AuthConfig 对象，所有 setter 完成后原子替换（volatile 写），避免 reload 期间读到半更新状态
        AuthConfig newConfig = new AuthConfig();
        // 语言
        newConfig.setLanguage(f.getString("language", "en_gb"));

        // 调试模式
        newConfig.setDebug(f.getBoolean("debug", false));

        // 代理模式与验证配置
        newConfig.setProxy(f.getBoolean("proxy", false));
        newConfig.setUseMojangUuid(f.getBoolean("use-mojang-uuid", true));
        // 玩家进服后是否在消息栏显示登录状态
        newConfig.setNotifyPlayerStatus(f.getBoolean("notify-player-status", true));
        // 跨服会话同步签名密钥（必须与 Velocity 端配置一致，空则关闭验签）
        newConfig.setSessionSyncSecret(f.getString("session-sync-secret", ""));

        List<String> list = f.getStringList("auth-list");
        if (list.isEmpty()) {
            // 兼容旧格式
            list = (List<String>) f.getList("auth-list", new ArrayList<>());
        }
        newConfig.setAuthList(new HashSet<>(list));

        // Mojang API - 加载备用 API 列表（新格式 fallback-urls，旧格式 fallback-url 兼容）
        List<String> fallbackUrls = f.getStringList("mojang-api.fallback-urls");
        if (fallbackUrls == null || fallbackUrls.isEmpty()) {
            // 兼容旧格式：单个 fallback-url
            String oldUrl = f.getString("mojang-api.fallback-url", "");
            if (oldUrl != null && !oldUrl.isBlank()) {
                fallbackUrls = new ArrayList<>();
                fallbackUrls.add(oldUrl);
            }
        }
        newConfig.setFallbackApiUrls(fallbackUrls);
        // 每用户名每秒 Mojang API 请求上限（0=不限制），防止重复请求触发 Mojang 429
        newConfig.setMojangRequestLimit(f.getInt("mojang-api.request-limit", 2));

        // 数据库
        newConfig.setDatabaseType(f.getString("database.type", "sqlite"));
        newConfig.setSqliteFile(f.getString("database.sqlite-file", "multiauth.db"));
        newConfig.setMysqlHost(f.getString("database.mysql-host", "localhost"));
        newConfig.setMysqlPort(f.getInt("database.mysql-port", 3306));
        newConfig.setMysqlDatabase(f.getString("database.mysql-database", "multiauth"));
        newConfig.setMysqlUsername(f.getString("database.mysql-username", "root"));
        newConfig.setMysqlPassword(f.getString("database.mysql-password", ""));
        newConfig.setMysqlTablePrefix(f.getString("database.mysql-table-prefix", "multiauth_"));
        newConfig.setMysqlUseSsl(f.getBoolean("database.mysql-use-ssl", false));
        newConfig.setHeartbeatInterval(f.getInt("database.heartbeat-interval", 60));

        // 备份
        newConfig.setBackupEnabled(f.getBoolean("backup.enabled", true));
        newConfig.setBackupIntervalHours(f.getInt("backup.interval-hours", 24));
        newConfig.setBackupDir(f.getString("backup.dir", "backups"));
        newConfig.setBackupMaxCount(f.getInt("backup.max-count", 7));

        // 离线玩家注册登录
        newConfig.setAuthEnabled(f.getBoolean("auth.enabled", true));
        newConfig.setAuthPasswordMin(f.getInt("auth.password-min-length", 4));
        newConfig.setAuthPasswordMax(f.getInt("auth.password-max-length", 32));
        newConfig.setAuthForceAdventure(f.getBoolean("auth.force-adventure", false));
        newConfig.setAuthFreezePosition(f.getBoolean("auth.freeze-position", true));
        newConfig.setAuthRestrictMove(f.getBoolean("auth.restrict-move", true));
        newConfig.setAuthRestrictChat(f.getBoolean("auth.restrict-chat", true));
        newConfig.setAuthRestrictInteract(f.getBoolean("auth.restrict-interact", true));
        newConfig.setAuthRestrictDamage(f.getBoolean("auth.restrict-damage", true));
        newConfig.setAuthRestrictCommand(f.getBoolean("auth.restrict-command", true));
        newConfig.setAuthAllowCommands(f.getStringList("auth.allow-commands"));
        newConfig.setAuthLoginTimeout(f.getInt("auth.login-timeout", 600));
        newConfig.setAuthRegisterTimeout(f.getInt("auth.register-timeout", 180));
        newConfig.setAuthLoginSpawnPoint(f.getBoolean("auth.login-spawn-point.enable", false));
        newConfig.setAuthSpawnPointWorld(f.getString("auth.login-spawn-point.world", ""));
        newConfig.setAuthSpawnPointX(f.getDouble("auth.login-spawn-point.x", 0.0));
        newConfig.setAuthSpawnPointY(f.getDouble("auth.login-spawn-point.y", 64.0));
        newConfig.setAuthSpawnPointZ(f.getDouble("auth.login-spawn-point.z", 0.0));
        newConfig.setAuthSpawnPointYaw((float) f.getDouble("auth.login-spawn-point.yaw", 0.0));
        newConfig.setAuthSpawnPointPitch((float) f.getDouble("auth.login-spawn-point.pitch", 0.0));
        newConfig.setAuthReturnLastLocation(f.getBoolean("auth.return-last-location", false));
        newConfig.setAuthForceSurvival(f.getBoolean("auth.force-survival", false));
        newConfig.setSessionTimeout(f.getInt("session.timeout", 0));

        // 安全增强配置
        newConfig.setSecFailedLoginEnabled(f.getBoolean("auth.security.failed-login.enabled", true));
        newConfig.setSecAccountMaxAttempts(f.getInt("auth.security.failed-login.account.max-attempts", 5));
        newConfig.setSecAccountCooldown(f.getInt("auth.security.failed-login.account.cooldown", 300));
        newConfig.setSecAccountResetOnSuccess(f.getBoolean("auth.security.failed-login.account.reset-on-success", true));
        newConfig.setSecIpMaxAttempts(f.getInt("auth.security.failed-login.ip.max-attempts", 10));
        newConfig.setSecIpCooldown(f.getInt("auth.security.failed-login.ip.cooldown", 600));
        newConfig.setSecIpResetOnSuccess(f.getBoolean("auth.security.failed-login.ip.reset-on-success", false));

        newConfig.setSecIpLimitsEnabled(f.getBoolean("auth.security.ip-limits.enabled", true));
        newConfig.setSecMaxAccountsPerIp(f.getInt("auth.security.ip-limits.max-accounts-per-ip", 3));
        newConfig.setSecMaxOnlinePerIp(f.getInt("auth.security.ip-limits.max-online-per-ip", 2));

        newConfig.setSecIpChangeEnabled(f.getBoolean("auth.security.ip-change.enabled", true));
        newConfig.setSecIpChangeWarnPlayer(f.getBoolean("auth.security.ip-change.warn-player", true));
        newConfig.setSecIpChangeNotifyAdmin(f.getBoolean("auth.security.ip-change.notify-admin", false));

        newConfig.setSecGeoEnabled(f.getBoolean("auth.security.geo-detection.enabled", false));
        newConfig.setSecGeoXdbDir(f.getString("auth.security.geo-detection.xdb-dir", "ip2region"));
        newConfig.setSecGeoAutoDownload(f.getBoolean("auth.security.geo-detection.auto-download", true));
        newConfig.setSecGeoV4Enabled(f.getBoolean("auth.security.geo-detection.v4.enabled", true));
        newConfig.setSecGeoV4File(f.getString("auth.security.geo-detection.v4.file", "ip2region_v4.xdb"));
        newConfig.setSecGeoV6Enabled(f.getBoolean("auth.security.geo-detection.v6.enabled", false));
        newConfig.setSecGeoV6File(f.getString("auth.security.geo-detection.v6.file", "ip2region_v6.xdb"));
        newConfig.setSecGeoCachePolicy(f.getString("auth.security.geo-detection.cache-policy", "vIndexCache"));
        newConfig.setSecGeoSearchers(f.getInt("auth.security.geo-detection.searchers", 15));
        newConfig.setSecGeoCrossCountryAction(f.getString("auth.security.geo-detection.cross-country.action", "warn"));
        newConfig.setSecGeoCrossCityAction(f.getString("auth.security.geo-detection.cross-city.action", "warn"));
        newConfig.setSecGeoSkipLan(f.getBoolean("auth.security.geo-detection.skip-lan", true));

        newConfig.setSecLoginHistoryEnabled(f.getBoolean("auth.security.login-history.enabled", true));
        newConfig.setSecLoginHistoryMaxRecords(f.getInt("auth.security.login-history.max-records-per-player", 20));
        // 原子发布：volatile 写，确保其他线程读到完整的新配置
        this.config = newConfig;
    }

    public AuthConfig getConfig() {
        return config;
    }

    public boolean isProxy() {
        return config.isProxy();
    }

    public boolean isUseMojangUuid() {
        return config.isUseMojangUuid();
    }

    public boolean isNotifyPlayerStatus() {
        return config.isNotifyPlayerStatus();
    }

    public boolean isInAuthList(String username) {
        return config.isInAuthList(username);
    }
}
