package com.lonleaf.multiauth;

import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.Messages;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

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
     * 配置加载失败（YAML 语法错误/IO 异常）时保留旧配置继续运行，不让插件加载失败（与 Velocity 端一致）。
     */
    public void load() {
        try {
            // 首次启动（config.yml 不存在）：saveDefaultConfig 复制模板后按系统语言自动设置 language 项
            boolean firstStart = !new java.io.File(plugin.getDataFolder(), "config.yml").exists();
            plugin.saveDefaultConfig();
            plugin.reloadConfig();

            // 自动升级：config-version 落后时把新增配置项以默认值追加到文件末尾（保留用户已有键与注释）
            upgradeConfigFile();
            // 重新加载升级后的配置
            plugin.reloadConfig();

            FileConfiguration fileConfig = plugin.getConfig();
            if (firstStart) {
                applySystemLanguage();
                // 文本级修改 language 后重新加载，让内存配置反映新值
                plugin.reloadConfig();
                fileConfig = plugin.getConfig();
            }
            applyConfig(fileConfig);

            logger.fine(Messages.get(Messages.CONFIG_LOADED_DEBUG, String.valueOf(config.isProxy()), String.valueOf(config.isUseMojangUuid()), String.valueOf(config.isDebug())));
        } catch (Exception e) {
            logger.severe(Messages.get(Messages.CONFIG_LOAD_FAILED, e.getMessage()));
            logger.log(java.util.logging.Level.SEVERE, "Config load failure, keeping previous config", e);
        }
    }

    /**
     * 配置自动升级：以 jar 内模板为准，若文件中 config-version 低于模板版本，
     * 先执行链式结构迁移（重命名/删除/调整键，文本级修改保留注释），
     * 再将模板中缺失的键以扁平键形式追加到文件末尾（不重写已有内容，保留注释），
     * 最后更新 config-version 并原子写回（tmp + ATOMIC_MOVE，避免写入中断损坏文件）。
     * 文件内容不匹配模板时由 applyConfig 的默认值兜底。
     */
    private void upgradeConfigFile() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        FileConfiguration template;
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return;
            }
            template = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        int latestVersion = template.getInt("config-version", 1);

        String raw;
        try {
            raw = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        FileConfiguration current = YamlConfiguration.loadConfiguration(new java.io.StringReader(raw));
        int currentVersion = current.getInt("config-version", 0);
        if (currentVersion >= latestVersion) {
            return; // 已是最新或无需升级
        }

        // D：链式结构迁移，从 currentVersion 逐版本应用到 latestVersion（文本级修改，保留注释）
        for (int v = currentVersion + 1; v <= latestVersion; v++) {
            raw = applyMigration(raw, v);
        }

        // 基于迁移后的文本重新解析，收集模板中用户配置缺失的叶键（config-version 单独处理）
        FileConfiguration migrated = YamlConfiguration.loadConfiguration(new java.io.StringReader(raw));
        List<String> missing = new ArrayList<>();
        for (String key : template.getKeys(true)) {
            if (!"config-version".equals(key) && !migrated.contains(key)) {
                missing.add(key);
            }
        }

        StringBuilder append = new StringBuilder();
        if (!missing.isEmpty()) {
            append.append("\n# --- MultiAuth config upgrade to v").append(latestVersion)
                    .append(": newly added options (defaults) ---\n");
            for (String key : missing) {
                append.append(flatYaml(key, template.get(key)));
            }
        }

        // 更新文件中的 config-version：已存在则替换值，否则追加
        if (Pattern.compile("(?m)^config-version:.*$").matcher(raw).find()) {
            raw = raw.replaceAll("(?m)^config-version:.*$", "config-version: " + latestVersion);
        } else {
            append.append("config-version: ").append(latestVersion).append("\n");
        }

        try {
            writeConfigAtomic(configFile, raw + append);
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        logger.info(Messages.get(Messages.CONFIG_UPGRADE_DONE, String.valueOf(missing.size()), String.valueOf(latestVersion)));
    }

    /**
     * 应用从 v-1 升级到 v 的结构迁移。迁移可能涉及键重命名/删除/结构调整，
     * 必须在原始文本上做行级替换（保留注释），并返回修改后的文本。
     * 当前 config-version=1，暂无历史迁移，仅预留框架：未来新增配置版本时，
     * 在 switch 中添加对应 case，并把模板 config-version 递增。
     */
    private String applyMigration(String raw, int v) {
        switch (v) {
            // 示例（新增版本时按此模式添加）：
            // case 2:
            //     // 键重命名：auth.old-key → auth.new-key（行级替换保留注释）
            //     raw = raw.replaceAll("(?m)^auth\\.old-key:.*$", "auth.new-key:");
            //     // 键删除：整行连同上方注释一起删除
            //     raw = raw.replaceAll("(?m)^(?:#[^\\n]*\\n)*auth\\.removed-key:.*$", "");
            //     break;
            default:
                break;
        }
        return raw;
    }

    /**
     * 原子写回配置文件：先写入同名 .tmp 文件，再原子替换目标文件，
     * 避免写入中断（磁盘满/进程崩溃）导致配置文件损坏。
     * 文件系统不支持原子移动时降级为普通替换。
     */
    private void writeConfigAtomic(File configFile, String content) throws java.io.IOException {
        java.nio.file.Path path = configFile.toPath();
        java.nio.file.Path tmp = path.resolveSibling(configFile.getName() + ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // 清理失败不影响主流程
            }
        }
    }

    /**
     * 将单个键序列化为 YAML 扁平键形式（如 {@code auth.return-last-location: false}，
     * 列表值输出顶格块序列），便于追加到现有配置末尾而不破坏原有嵌套结构。
     */
    private String flatYaml(String key, Object value) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("x", value);
        String s = tmp.saveToString();
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        String rest = nl >= 0 ? s.substring(nl) : "\n";
        return key + head.substring("x:".length()) + rest;
    }

    /**
     * 首次启动时根据系统语言自动设置 language 项（仅当检测结果与默认 en_gb 不同时写入）。
     * 采用文本级替换 + 原子写回，避免 saveConfig() 重写整个文件导致注释丢失（B）。
     */
    private void applySystemLanguage() {
        String detected = Messages.detectSystemLanguage();
        if ("en_gb".equals(detected)) {
            return;
        }
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            String raw = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            if (Pattern.compile("(?m)^language:.*$").matcher(raw).find()) {
                raw = raw.replaceAll("(?m)^language:.*$", "language: " + detected);
                writeConfigAtomic(configFile, raw);
            }
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
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

        // 更新检查
        newConfig.setUpdateCheckEnabled(f.getBoolean("update-check.enabled", true));
        newConfig.setUpdateCheckIntervalHours(f.getInt("update-check.interval-hours", 24));

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
        newConfig.setAuthNotifyOtherAccounts(f.getBoolean("auth.notify-other-accounts", false));
        newConfig.setAuthReminderInterval(f.getInt("auth.reminder-interval", 6));
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
        // 默认值与 config.yml 模板保持一致（warn-player=false, notify-admin=true）
        newConfig.setSecIpChangeWarnPlayer(f.getBoolean("auth.security.ip-change.warn-player", false));
        newConfig.setSecIpChangeNotifyAdmin(f.getBoolean("auth.security.ip-change.notify-admin", true));

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
