package com.lonleaf.multiauth;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.lonleaf.multiauth.config.AuthConfig;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Velocity 端配置管理器，使用 TOML 格式。
 */
public class VelocityConfig {

    private static final String CONFIG_FILE = "config.toml";

    private final Path configPath;
    private final Logger logger;
    // volatile + 新对象原子发布：reload 时其他线程读到完整的新配置，避免半更新状态（与 Spigot 端一致）
    private volatile AuthConfig config = new AuthConfig();

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
                // 首次启动：按系统语言自动设置 language 项
                applySystemLanguageToFile();
            }

            newConfig = CommentedFileConfig.builder(configPath).build();
            newConfig.load();

            // 自动升级：config-version 落后时把模板中缺失的配置项补充进来并写回（保留注释）
            upgradeConfigFile(newConfig);

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

    /**
     * 首次启动时根据系统语言自动设置 config.toml 的 language 项（仅当检测结果与默认 en_gb 不同时写入）。
     * 文本级替换 + 原子写回：night-config 的 TomlWriter 重写整个文件会打乱键序（内部 HashMap 迭代顺序），
     * 因此不能通过 CommentedFileConfig.save() 写回。
     */
    private void applySystemLanguageToFile() {
        String detected = Messages.detectSystemLanguage();
        if ("en_gb".equals(detected)) {
            return;
        }
        try {
            String raw = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            if (Pattern.compile("(?m)^language\\s*=.*$").matcher(raw).find()) {
                raw = raw.replaceAll("(?m)^language\\s*=.*$", "language = \"" + detected + "\"");
                writeTextAtomic(raw);
            }
        } catch (Exception e) {
            logger.warn(Messages.get(Messages.CONFIG_LOAD_FAILED, e.getMessage()));
        }
    }

    /**
     * 配置自动升级：以 jar 内模板为准，若文件中 config-version 低于模板版本，
     * 将模板中缺失的键补充进来并更新 config-version，随后原子写回。
     * 全部为文本级修改，保留已有注释与键序：
     * - 顶层缺失键追加到文件末尾；
     * - 表内缺失键插入到对应 [表] 表头之后（night-config 的 TomlParser 静默忽略向已定义表
     *   追加的 dotted key，且其 TomlWriter 重写会打乱键序、TomlParser 禁止重复表头）。
     */
    private void upgradeConfigFile(CommentedFileConfig newConfig) {
        Config template;
        try (InputStream in = getClass().getResourceAsStream("/" + CONFIG_FILE)) {
            if (in == null) {
                return;
            }
            template = new TomlParser().parse(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        int latestVersion = template.getIntOrElse("config-version", 1);
        int currentVersion = newConfig.getIntOrElse("config-version", 0);
        if (currentVersion >= latestVersion) {
            return; // 已是最新或无需升级
        }

        List<String> leafKeys = new ArrayList<>();
        collectLeafKeys(template, "", leafKeys);
        List<String> missing = new ArrayList<>();
        for (String key : leafKeys) {
            if (!"config-version".equals(key) && !newConfig.contains(key)) {
                missing.add(key);
            }
        }

        String raw;
        try {
            raw = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        StringBuilder appendedTop = new StringBuilder();
        for (String key : missing) {
            Object value = template.get(key);
            int dot = key.lastIndexOf('.');
            if (dot < 0) {
                // 顶层键：追加到文件末尾
                appendedTop.append(key).append(" = ").append(tomlValue(value)).append("\n");
            } else {
                // 表内键：插入到 [parent] 表头之后（表不存在则追加 [parent] 表头 + 键）
                String parent = key.substring(0, dot);
                String leaf = key.substring(dot + 1);
                raw = insertIntoTable(raw, parent, leaf + " = " + tomlValue(value));
            }
            // 同步到内存配置，供后续 applyConfig 读取（文件层面由文本级修改保证键序不变）
            newConfig.set(key, value);
        }

        // 更新文件中的 config-version：已存在则替换值，否则追加
        if (Pattern.compile("(?m)^config-version\\s*=.*$").matcher(raw).find()) {
            raw = raw.replaceAll("(?m)^config-version\\s*=.*$", "config-version = " + latestVersion);
        } else {
            appendedTop.append("config-version = ").append(latestVersion).append("\n");
        }

        StringBuilder append = new StringBuilder();
        if (appendedTop.length() > 0) {
            append.append("\n# --- MultiAuth config upgrade to v").append(latestVersion)
                    .append(": newly added options (defaults) ---\n")
                    .append(appendedTop);
        }

        try {
            writeTextAtomic(raw + append);
        } catch (Exception e) {
            logger.warn(Messages.get(Messages.CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }
        logger.info(Messages.get(Messages.CONFIG_UPGRADE_DONE, String.valueOf(missing.size()), String.valueOf(latestVersion)));
    }

    /**
     * 将一行键值插入到 TOML 表头（如 [mojang-api]）之后；表头不存在时在文件末尾追加 [parent] 表头 + 键。
     * 返回修改后的文本。
     */
    private static String insertIntoTable(String raw, String parent, String line) {
        String regex = "(?m)^\\[" + Pattern.quote(parent) + "\\]\\s*$";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(raw);
        if (m.find()) {
            int pos = m.end();
            return raw.substring(0, pos) + "\n" + line + raw.substring(pos);
        }
        return raw + "\n[" + parent + "]\n" + line;
    }

    /** 将配置值序列化为 TOML 字面量（字符串加引号并转义，列表输出内联数组） */
    private static String tomlValue(Object value) {
        if (value instanceof String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '\\' -> sb.append("\\\\");
                    case '"' -> sb.append("\\\"");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(tomlValue(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    /**
     * 原子写回 config.toml：先写同名 .tmp 再原子替换，避免写入中断（磁盘满/进程崩溃）损坏文件；
     * 文件系统不支持原子移动时降级为普通替换。仅做文本级写入，不改动其他内容与顺序。
     */
    private void writeTextAtomic(String content) throws java.io.IOException {
        Path tmpPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try {
            Files.write(tmpPath, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmpPath, configPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {
            }
        }
    }

    /** 递归收集配置的叶节点全路径键（点分路径，nightconfig 的 Config 无 getKeys） */
    private void collectLeafKeys(Config node, String prefix, List<String> out) {
        for (Map.Entry<String, Object> e : node.valueMap().entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Config sub) {
                collectLeafKeys(sub, path, out);
            } else {
                out.add(path);
            }
        }
    }

    /** debug 日志：仅 debug=true 时输出 */
    private void debug(String msg) {
        if (config.isDebug()) {
            logger.info("[DEBUG] " + msg);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyConfig(CommentedFileConfig source) {
        // 构建新的 AuthConfig 对象，所有 setter 完成后原子替换（volatile 写），避免 reload 期间读到半更新状态
        AuthConfig newConfig = new AuthConfig();
        // 语言
        newConfig.setLanguage(source.getOrElse("language", "en_gb"));

        // 调试模式
        newConfig.setDebug(source.getOrElse("debug", false));

        // 基本配置
        newConfig.setUseMojangUuid(source.getOrElse("cross-server-use-mojang-uuid", true));
        // 跨服会话同步签名密钥（必须与 Spigot 端配置一致，空则关闭验签）
        newConfig.setSessionSyncSecret(source.getOrElse("session-sync-secret", ""));

        List<String> list = source.getOrElse("auth-list", new ArrayList<>());
        newConfig.setAuthList(new HashSet<>(list));

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
        newConfig.setFallbackApiUrls(fallbackUrls);
        // 每用户名每秒 Mojang API 请求上限（0=不限制），防止重复请求触发 Mojang 429
        newConfig.setMojangRequestLimit(source.getIntOrElse("mojang-api.request-limit", 2));

        // 数据库
        newConfig.setDatabaseType(source.getOrElse("database.type", "sqlite"));
        newConfig.setSqliteFile(source.getOrElse("database.sqlite-file", "multiauth.db"));
        newConfig.setMysqlHost(source.getOrElse("database.mysql-host", "localhost"));
        newConfig.setMysqlPort(source.getIntOrElse("database.mysql-port", 3306));
        newConfig.setMysqlDatabase(source.getOrElse("database.mysql-database", "multiauth"));
        newConfig.setMysqlUsername(source.getOrElse("database.mysql-username", "root"));
        newConfig.setMysqlPassword(source.getOrElse("database.mysql-password", ""));
        newConfig.setMysqlTablePrefix(source.getOrElse("database.mysql-table-prefix", "multiauth_"));
        newConfig.setMysqlUseSsl(source.getOrElse("database.mysql-use-ssl", false));
        newConfig.setHeartbeatInterval(source.getIntOrElse("database.heartbeat-interval", 60));

        // 备份
        newConfig.setBackupEnabled(source.getOrElse("backup.enabled", true));
        newConfig.setBackupIntervalHours(source.getIntOrElse("backup.interval-hours", 24));
        newConfig.setBackupDir(source.getOrElse("backup.dir", "backups"));
        newConfig.setBackupMaxCount(source.getIntOrElse("backup.max-count", 7));

        // 原子发布：volatile 写，确保其他线程读到完整的新配置
        this.config = newConfig;
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
