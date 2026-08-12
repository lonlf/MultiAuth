package com.lonleaf.multiauth;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 国际化消息管理类。
 */
public class Messages {

    private static final Logger LOGGER = Logger.getLogger(Messages.class.getName());

    /** 当前语言代码 */
    private static volatile String currentLang = "en_gb";

    /** 语言文件目录 */
    private static Path langDir;

    /** 默认资源路径前缀（打包在 JAR 中的内置语言文件） */
    private static final String RESOURCE_PREFIX = "/lang/";

    /** 内置语言文件清单（新增语言需在此登记，同时作为语言族回退的候选） */
    private static final String[] BUILTIN_LANGS = {"zh_cn", "en_gb"};

    /** 消息存储（ConcurrentHashMap 保证 reload 时并发读取线程安全） */
    private static final Map<String, String> messages = new ConcurrentHashMap<>();

    /**
     * 消息版本号：每次 loadMessages 完成后递增，作为 volatile 写屏障，
     * 保证后续读取线程能看到所有静态 String 字段的最新值。
     */
    private static volatile long messagesVersion = 0;

    /** 是否初始化 */
    private static volatile boolean initialized = false;

    // ==================== 所有消息键常量 ====================

    // --- 数据库相关 ---
    public static volatile String DB_INIT_FAILED;
    public static volatile String DB_CONNECTED;
    public static volatile String DB_PING_FAILED;
    public static volatile String DB_HEARTBEAT_PING_FAILED;
    public static volatile String DB_HEARTBEAT_NOT_CONNECTED;
    public static volatile String DB_RECONNECTED;
    public static volatile String DB_RECONNECT_FAILED;
    public static volatile String DB_HEARTBEAT_ERROR;
    public static volatile String DB_BACKUP_CREATED;
    public static volatile String DB_BACKUP_FAILED;
    public static volatile String DB_BACKUP_DELETED_OLD;
    public static volatile String DB_MIGRATION_COMPLETE;
    public static volatile String DB_MIGRATION_FAILED;
    public static volatile String DB_CONNECTION_FAILED;
    public static volatile String DB_REBUILD_CONNECTION;
    public static volatile String DB_BACKUP_CLEAN_FAILED;
    public static volatile String DB_BACKUP_SKIP_INVALID_ROW;
    public static volatile String DB_CLOSE_FAILED;
    public static volatile String DB_STATE_CHECK_FAILED;
    public static volatile String DB_PING_EXCEPTION;
    public static volatile String DB_GET_PLAYER_FAILED;
    public static volatile String DB_SAVE_PLAYER_FAILED;
    public static volatile String DB_SAVE_PLAYER_SAFE_FAILED;
    public static volatile String DB_EXISTS_FAILED;
    public static volatile String DB_COUNT_FAILED;
    public static volatile String DB_COUNT_PREMIUM_FAILED;
    public static volatile String DB_MIGRATION_EXCEPTION;
    public static volatile String DB_UPDATE_LOCATION_FAILED;
    public static volatile String DB_CREATE_AUTH_TABLE_FAILED;
    public static volatile String DB_GET_AUTH_ACCOUNT_FAILED;
    public static volatile String DB_SAVE_AUTH_ACCOUNT_FAILED;
    public static volatile String DB_UPDATE_AUTH_PASSWORD_FAILED;
    public static volatile String DB_UPDATE_AUTH_LOGIN_FAILED;
    public static volatile String DB_DELETE_AUTH_ACCOUNT_FAILED;
    public static volatile String DB_AUTH_ACCOUNT_EXISTS_FAILED;
    public static volatile String DB_CREATE_LOGIN_HISTORY_TABLE_FAILED;
    public static volatile String DB_RECORD_LOGIN_HISTORY_FAILED;
    public static volatile String DB_GET_LOGIN_HISTORY_FAILED;
    public static volatile String DB_TRIM_LOGIN_HISTORY_FAILED;
    public static volatile String DB_CREATE_IP_STATS_TABLE_FAILED;
    public static volatile String DB_GET_IP_STATS_FAILED;
    public static volatile String DB_INCREMENT_IP_ACCOUNT_FAILED;
    public static volatile String DB_DECREMENT_IP_ACCOUNT_FAILED;
    public static volatile String DB_GET_ACCOUNTS_BY_IP_FAILED;
    public static volatile String DB_CREATE_INDEX_FAILED;
    public static volatile String DB_PARSE_UUID_FAILED;
    public static volatile String CORE_INIT_PROXY;
    public static volatile String CORE_INIT_STANDALONE;
    public static volatile String CORE_SHUTDOWN_COMPLETE;
    public static volatile String CORE_PROXY_MODE_DEBUG;
    public static volatile String CORE_API_INIT_DEBUG;
    public static volatile String CORE_HEARTBEAT_SCHEDULED;
    public static volatile String CORE_BACKUP_SCHEDULED;
    public static volatile String CORE_RELOADED_PROXY;
    public static volatile String CORE_RELOADED_STANDALONE;
    public static volatile String CORE_CLOSE_MOJANG_SESSION_FAILED;
    public static volatile String CORE_CLOSE_MOJANG_API_FAILED;
    public static volatile String CORE_DISCONNECT_DB_FAILED;

    // --- API 相关 ---
    public static volatile String API_OFFICIAL_AVAILABLE;
    public static volatile String API_OFFICIAL_UNAVAILABLE;
    public static volatile String API_FALLBACK_AVAILABLE;
    public static volatile String API_FALLBACK_UNAVAILABLE;
    public static volatile String API_ALL_DOWN;
    public static volatile String API_OFFICIAL_COOLDOWN;
    public static volatile String API_RATE_LIMIT_REACHED;
    public static volatile String API_HIGH_FAILURE_RATE;
    public static volatile String API_RECOVERED;
    public static volatile String API_PROBE_START;
    public static volatile String API_PARSE_FAILED;
    public static volatile String API_FAST_FAIL_DOWNTIME;
    public static volatile String API_PROBE_IN_PROGRESS;
    public static volatile String API_OFFICIAL_CHECK_COMPLETE;
    public static volatile String API_FALLBACK_CHECK_COMPLETE;
    /** 备用 API URL 模板非法（不含 {username} 占位符或无法解析），启动时剔除并警告 */
    public static volatile String API_FALLBACK_INVALID_TEMPLATE;

    // --- 认证流程 ---
    public static volatile String AUTH_DATABASE_UNAVAILABLE;
    public static volatile String AUTH_SERVICE_NOT_INITIALIZED;
    public static volatile String AUTH_CONCURRENT_LOGIN_BLOCKED;
    public static volatile String AUTH_SERVER_BUSY;
    public static volatile String AUTH_USERNAME_CHECK_FAILED;
    /** 用户名正版检查发生内部错误（配置/编程错误），fail-closed 拒绝而非按宕机降级放行 */
    public static volatile String AUTH_CHECK_INTERNAL_ERROR;
    public static volatile String AUTH_PREMIUM_DETECTED;
    public static volatile String AUTH_PREMIUM_IN_AUTHLIST;
    public static volatile String AUTH_OFFLINE_ALLOWED;
    public static volatile String AUTH_DOWNTIME_FLOW;
    public static volatile String AUTH_PLAYER_ALLOWED;
    public static volatile String AUTH_PLAYER_DENIED;
    public static volatile String AUTH_HANDSHAKE_FAILED;
    public static volatile String AUTH_INVALID_SESSION;
    public static volatile String AUTH_MOJANG_VERIFY_PASSED;
    public static volatile String AUTH_MOJANG_VERIFY_FAILED_PIRATE;
    public static volatile String AUTH_MOJANG_UNREACHABLE;
    public static volatile String AUTH_DOWNTIME_DENY;
    public static volatile String AUTH_DOWNTIME_ALLOW_OFFLINE;
    public static volatile String AUTH_UUID_MISMATCH;
    public static volatile String AUTH_EXECUTOR_FULL;
    public static volatile String AUTH_PLUGIN_DISABLED;
    public static volatile String AUTH_VERIFY_UNEXPECTED_ERROR;
    public static volatile String AUTH_LISTENER_UNREGISTERED_CLOSE;
    public static volatile String AUTH_DENY_CLIENT_DISCONNECTED;
    public static volatile String AUTH_DENY_SEND_DISCONNECT;
    public static volatile String AUTH_NO_PACKETEVENT_VERIFY;
    public static volatile String AUTH_VERIFY_FAILED_DENY;
    public static volatile String AUTH_API_ONLY_MODE;
    public static volatile String AUTH_NO_LOGIN_SUMMARY;
    public static volatile String AUTH_ALLOW_WINS_DENY_IGNORED;
    public static volatile String AUTH_CONCURRENCY_FULL;
    public static volatile String AUTH_API_RATE_LIMITED;

    // --- 离线玩家注册登录 ---
    public static volatile String AUTH_REGISTER_PROMPT;
    public static volatile String AUTH_LOGIN_PROMPT;
    public static volatile String AUTH_REGISTER_SUCCESS;
    public static volatile String AUTH_REGISTER_FAILED;
    public static volatile String AUTH_REGISTER_ALREADY;
    public static volatile String AUTH_REGISTER_PASSWORD_MISMATCH;
    public static volatile String AUTH_REGISTER_PASSWORD_TOO_SHORT;
    public static volatile String AUTH_REGISTER_PASSWORD_TOO_LONG;
    public static volatile String AUTH_REGISTER_PASSWORD_EMPTY;
    public static volatile String AUTH_LOGIN_SUCCESS;
    public static volatile String AUTH_LOGIN_FAILED;
    public static volatile String AUTH_LOGIN_NOT_REGISTERED;
    public static volatile String AUTH_LOGIN_PROCESSING;
    public static volatile String AUTH_LOGIN_ALREADY;
    public static volatile String AUTH_CHANGEPASSWORD_SUCCESS;
    public static volatile String AUTH_CHANGEPASSWORD_FAILED;
    public static volatile String AUTH_CHANGEPASSWORD_WRONG_OLD;
    public static volatile String AUTH_NOT_LOGGED_IN;
    public static volatile String AUTH_RESTRICTED;
    public static volatile String AUTH_LOGIN_TIMEOUT;
    public static volatile String AUTH_REGISTER_TIMEOUT;
    public static volatile String AUTH_UNREGISTER_SUCCESS;
    public static volatile String AUTH_UNREGISTER_NOT_FOUND;
    public static volatile String AUTH_INFO_BASE;
    public static volatile String AUTH_INFO_OFFLINE_EXTRA;
    public static volatile String AUTH_INFO_PREMIUM_EXTRA;
    public static volatile String AUTH_INFO_NEVER_LOGGED_IN;
    public static volatile String AUTH_INFO_NOT_REGISTERED;
    public static volatile String AUTH_INFO_STATUS_ONLINE;
    public static volatile String AUTH_INFO_STATUS_OFFLINE;
    public static volatile String AUTH_INFO_LOCATION;
    public static volatile String AUTH_INFO_OTHER_ACCOUNTS;
    public static volatile String AUTH_INFO_OTHER_ACCOUNTS_NONE;
    public static volatile String AUTH_OTHER_ACCOUNTS_NOTIFY;
    public static volatile String AUTH_MODULE_DISABLED;
    public static volatile String AUTH_UNREGISTER_KICK;

    // --- 安全增强消息 ---
    public static volatile String AUTH_ACCOUNT_COOLDOWN;
    public static volatile String AUTH_IP_COOLDOWN;
    public static volatile String AUTH_LOGIN_TOO_MANY_FAILURES;
    public static volatile String AUTH_ATTEMPTS_REMAINING;
    public static volatile String AUTH_IP_ACCOUNT_LIMIT;
    public static volatile String AUTH_IP_ONLINE_LIMIT;
    public static volatile String AUTH_IP_CHANGE_WARNING;
    public static volatile String AUTH_GEO_CROSS_COUNTRY;
    public static volatile String AUTH_GEO_CROSS_CITY;
    public static volatile String AUTH_GEO_REQUIRE_LOGIN;
    public static volatile String SEC_QUERY_IP_STATS_FAILED;
    public static volatile String SEC_INCREMENT_IP_ACCOUNT_FAILED;
    public static volatile String SEC_DECREMENT_IP_ACCOUNT_FAILED;
    public static volatile String SEC_GEO_CHECK_SKIPPED_LOG;
    public static volatile String SEC_GEO_DB_FAILED_DENY_LOG;
    public static volatile String AUTH_STATE_AUTHMANAGER_MISSING_LOG;
    /** PlayerJoinEvent 处理出现未预期异常（防护日志） */
    public static volatile String AUTH_PLAYER_JOIN_ERROR;

    // --- 日志参数：拒绝/放行原因与登录类型标签（可配置） ---
    public static volatile String DENY_REASON_DB_UNAVAILABLE;
    public static volatile String DENY_REASON_AUTHMANAGER_NOT_INITIALIZED;
    public static volatile String DENY_REASON_CONCURRENT_LOGIN;
    public static volatile String DENY_REASON_NO_ENC_RESPONSE;
    public static volatile String ALLOW_REASON_NO_RECORD;
    public static volatile String ALLOW_REASON_OFFLINE_RECORD;
    public static volatile String ALLOW_REASON_PREMIUM_KEEP_OFFLINE_UUID;
    public static volatile String ALLOW_REASON_UPGRADE_OFFLINE_TO_PREMIUM;
    public static volatile String ALLOW_REASON_UUID_AUTOCORRECT_OFFLINE_TO_PREMIUM;
    public static volatile String ALLOW_REASON_UUID_AUTOCORRECT_PREMIUM_TO_OFFLINE;
    public static volatile String LOGIN_TYPE_PREMIUM;
    public static volatile String LOGIN_TYPE_PREMIUM_OFFLINE_UUID;
    public static volatile String LOGIN_TYPE_OFFLINE;
    public static volatile String LOGIN_TYPE_PREMIUM_API_ONLY;
    public static volatile String LOGIN_TYPE_OFFLINE_API_ONLY;
    public static volatile String API_STATUS_RECOVERED;
    public static volatile String API_SOURCE_OFFICIAL;
    public static volatile String API_SOURCE_FALLBACK;
    public static volatile String KICK_REJECTED_MESSAGE;
    public static volatile String KICK_MESSAGE_SENT;
    public static volatile String LOGIN_SUCCESS;
    public static volatile String LOGIN_SUCCESS_PREMIUM;
    public static volatile String LOGIN_SUCCESS_OFFLINE;
    public static volatile String STATE_MISS;
    public static volatile String PACKET_FAKE_LOGIN_START_KICK;

    // --- 认证流程：宕机 / API-only 审计日志 ---
    public static volatile String AUTH_AUDIT_HASJOINED_UNREACHABLE;
    public static volatile String AUTH_AUDIT_DOWNTIME_NO_RECORD;
    public static volatile String AUTH_AUDIT_DOWNTIME_PREMIUM_HISTORY;
    public static volatile String AUTH_AUDIT_API_ONLY_AUTHLIST;
    public static volatile String AUTH_AUDIT_API_ONLY_PREMIUM;
    public static volatile String AUTH_AUDIT_HASJOINED_RATE_LIMITED;

    // --- 配置相关 ---
    public static volatile String CONFIG_LOADED;
    public static volatile String CONFIG_RELOADED;
    public static volatile String CONFIG_RELOAD_FAILED;
    public static volatile String CONFIG_ONLINE_MODE_INCOMPATIBLE_WARN;
    public static volatile String CONFIG_PROXY_CHANGE_RESTART;
    public static volatile String CONFIG_DEFAULT_CREATED;
    public static volatile String CONFIG_LOAD_FAILED;
    public static volatile String CONFIG_UPGRADE_DONE;
    public static volatile String CONFIG_UPGRADE_FAILED;

    // --- 插件相关 ---
    public static volatile String PLUGIN_VELOCITY_INITIALIZED;
    public static volatile String PLUGIN_VELOCITY_SHUTDOWN;
    public static volatile String PLUGIN_PROXY_SWITCH_TRUE;
    public static volatile String PLUGIN_PROXY_SWITCH_FALSE;
    public static volatile String PLUGIN_API_ONLY_WARNING;
    public static volatile String PLUGIN_PACKETEVENT_INSTALL_HINT;

    // --- 数据包 / 加密握手（Spigot PacketEvents 模式） ---
    public static volatile String PACKET_NO_VERIFY_CALLBACK;
    public static volatile String PACKET_LOGIN_START_PARSE_FAILED;
    public static volatile String PACKET_ENC_RESPONSE_PARSE_FAILED;
    public static volatile String PACKET_ENC_REQUEST_WRAPPER_FAILED;
    /** 已拒绝/失败连接的迟到 ENCRYPTION_RESPONSE 被取消（#17） */
    public static volatile String PACKET_ENC_RESPONSE_LATE_DENIED;
    public static volatile String PACKET_FAKE_LOGIN_START_FAILED;
    public static volatile String PACKET_FAKE_LOGIN_START_FALLBACK_FAILED;
    public static volatile String PACKET_DISCONNECT_SEND_FAILED;
    public static volatile String VERIFY_NO_PACKETEVENT;
    public static volatile String VERIFY_HANDSHAKE_TIMEOUT;
    public static volatile String VERIFY_ENC_RESPONSE_PARSE_FAILED;
    public static volatile String VERIFY_ENC_RESPONSE_INTERRUPTED;
    public static volatile String VERIFY_DECRYPT_FAILED;
    public static volatile String VERIFY_AES_ANCHOR_MISSING;
    public static volatile String VERIFY_HASJOINED_FAILED;
    public static volatile String VERIFY_INBOUND_ANCHOR_MISSING;
    public static volatile String VERIFY_OUTBOUND_ANCHOR_MISSING;
    public static volatile String VERIFY_AES_ENABLE_FAILED;
    public static volatile String VERIFY_SPOOFED_CONNECTION_MISSING;
    public static volatile String VERIFY_SPOOFED_FAILED;
    public static volatile String VERIFY_PACKET_HANDLER_MISSING;
    public static volatile String VERIFY_CONNECTION_FAILED;
    public static volatile String VERIFY_HANDLER_ROLLBACK_DEBUG;

    // --- 命令相关 ---
    public static volatile String CMD_HELP;
    public static volatile String CMD_NO_PERMISSION;
    public static volatile String CMD_STATUS;
    public static volatile String CMD_RELOAD_SUCCESS;
    public static volatile String CMD_MIGRATE_USAGE;
    public static volatile String CMD_MIGRATE_SUCCESS;
    public static volatile String CMD_MIGRATE_FAILED;
    public static volatile String CMD_MIGRATE_INVALID_TYPE;
    public static volatile String CMD_BACKUP_SUCCESS;
    public static volatile String CMD_BACKUP_FAILED;
    public static volatile String CMD_PLUGIN_INFO;
    public static volatile String CMD_INFO_USAGE;
    public static volatile String CMD_INFO_SELF_ONLY;
    public static volatile String CMD_UNREGISTER_USAGE;
    public static volatile String CMD_CHANGEPASSWORD_USAGE;
    public static volatile String CMD_MIGRATE_SAME_TYPE;
    public static volatile String CMD_CHECK_CONSOLE;
    public static volatile String CMD_MODE_PROXY;
    public static volatile String CMD_MODE_DIRECT;
    public static volatile String DB_STATUS_HEALTHY;
    public static volatile String DB_STATUS_UNHEALTHY;
    public static volatile String API_STATUS_NORMAL;
    public static volatile String API_STATUS_DOWN;
    public static volatile String API_STATUS_DISABLED;
    public static volatile String API_STATUS_UNKNOWN;
    public static volatile String CMD_CORE_NOT_INITIALIZED;
    public static volatile String CMD_MIGRATE_IN_PROGRESS;
    public static volatile String CMD_BACKUP_IN_PROGRESS;

    // --- 会话相关 ---
    public static volatile String SESSION_START;
    public static volatile String SESSION_COMPLETE;
    public static volatile String SESSION_DISCONNECT;
    public static volatile String SESSION_JOIN_NOTIFY;
    public static volatile String SESSION_HIJACK_WARNING;
    public static volatile String SESSION_STATUS_NOTIFY;
    public static volatile String SESSION_STATUS_LOG;
    public static volatile String SESSION_SYNC_ENABLED;
    public static volatile String SESSION_SYNC_RECEIVER_REGISTERED;
    public static volatile String SESSION_SYNC_LOGIN;
    public static volatile String SESSION_SYNC_LOGOUT;
    public static volatile String SESSION_SYNC_PARSE_ERROR;
    public static volatile String SESSION_SYNC_REJECT_CLIENT;
    public static volatile String SESSION_SYNC_TTL_REMOVED;
    public static volatile String SESSION_SYNC_SECRET_MISSING;
    public static volatile String SESSION_SYNC_BAD_SIGNATURE;
    public static volatile String SESSION_SYNC_AUTH_UP_LOG;
    public static volatile String SESSION_SYNC_SKIP_OFFLINE_DEBUG;
    public static volatile String SESSION_SYNC_SEND_FAILED_DEBUG;
    public static volatile String SEC_SESSION_RESUME_CHECK_FAILED;
    // 硬编码日志消息配置化
    public static volatile String CONFIG_LOADED_DEBUG;
    public static volatile String CONFIG_RELOADING;
    public static volatile String PACKETEVENTS_LOADED;
    public static volatile String PACKETEVENTS_NOT_INSTALLED;
    public static volatile String PACKETEVENTS_INSTALL_REQUIRED;
    public static volatile String AUTH_MODULE_ENABLED;
    public static volatile String SESSION_CLEAN_FAILED;
    public static volatile String DB_SAVE_FAILED;
    public static volatile String AUTH_ACCOUNT_UNREGISTERED_LOG;
    public static volatile String PACKET_LISTENER_REGISTERED;
    public static volatile String PACKET_LOGIN_START_VERIFIED;
    public static volatile String PACKET_LOGIN_START_USERNAME;
    public static volatile String PACKET_LOGIN_START_DUPLICATE;
    public static volatile String PACKET_REGISTER_HANDSHAKE_EXISTS;
    public static volatile String PACKET_ENC_RESPONSE_RECEIVED;
    public static volatile String PACKET_ENC_REQUEST_SENT;
    public static volatile String PACKET_VERIFY_CHANNEL_CLOSED;
    public static volatile String PACKET_FAKE_LOGIN_START_SENT;
    public static volatile String PACKET_DISCONNECT_CHANNEL_CLOSED;
    public static volatile String PACKET_DISCONNECT_ALREADY_SENT;
    public static volatile String PACKET_DISCONNECT_SENT;
    public static volatile String AUTH_PLAYER_VERIFIED_DEBUG;
    public static volatile String AUTH_NO_LOGIN_SUMMARY_DEBUG;

    // --- 硬编码日志消息配置化（Spigot 模块补充） ---
    public static volatile String VERIFY_HANDSHAKE_START;
    public static volatile String VERIFY_ENC_REQUEST_SENT_WAITING;
    public static volatile String VERIFY_ENC_RESPONSE_RECEIVED_DEBUG;
    public static volatile String VERIFY_HASJOINED_PASSED;
    public static volatile String VERIFY_SKIP_SPOOFED_UUID;
    public static volatile String VERIFY_AES_ENABLED;
    public static volatile String VERIFY_SPOOFED_UUID_SET;
    public static volatile String MULTIVERSE_LOAD_WORLD_FAILED;
    public static volatile String AUTH_PROXY_SKIP_SPIGOT;
    public static volatile String AUTH_CREATE_AUTH_TABLE_FAILED_LOG;
    public static volatile String AUTH_CREATE_SECURITY_TABLES_FAILED_LOG;
    public static volatile String SEC_REGISTRATION_BLOCKED_LOG;
    public static volatile String AUTH_HASH_SUBMISSION_FAILED_LOG;
    public static volatile String AUTH_REGISTER_SUCCESS_LOG;
    public static volatile String AUTH_SAVE_ACCOUNT_FAILED_LOG;
    public static volatile String AUTH_HASH_FAILED_LOG;
    public static volatile String AUTH_REGISTER_FAILED_LOG;
    public static volatile String AUTH_GEO_HISTORY_QUERY_FAILED_LOG;
    public static volatile String SEC_IP_CHANGED_LOG;
    public static volatile String AUTH_LOGIN_SUCCESS_LOG;
    public static volatile String SEC_TOO_MANY_FAILURES_KICK_LOG;
    public static volatile String AUTH_LOGIN_WRONG_PASSWORD_LOG;
    public static volatile String AUTH_LOGIN_UPDATE_FAILED_LOG;
    public static volatile String AUTH_PASSWORD_VERIFY_FAILED_LOG;
    public static volatile String AUTH_LOGIN_FAILED_LOG;
    public static volatile String SEC_GEO_KICK_LOG;
    public static volatile String SEC_GEO_WARN_LOG;
    public static volatile String SEC_SESSION_RESUME_KICK_LOG;
    public static volatile String SEC_SESSION_RESUME_REQUIRE_LOGIN_LOG;
    public static volatile String SEC_SESSION_RESUME_WARN_LOG;
    public static volatile String AUTH_CHANGE_PASSWORD_SUCCESS_LOG;
    public static volatile String AUTH_PASSWORD_UPDATE_FAILED_LOG;
    public static volatile String AUTH_CHANGE_PASSWORD_FAILED_LOG;
    public static volatile String AUTH_UNREGISTER_SUCCESS_LOG;
    public static volatile String AUTH_UNREGISTER_FAILED_LOG;
    public static volatile String AUTH_PLAYER_VERIFIED_SEND_FAKE;
    public static volatile String MSG_STATUS_NOTIFY_SENT;
    public static volatile String PACKET_FAKE_LOGIN_START_FALLBACK_SENT;
    public static volatile String PACKET_ENC_REQUEST_RAW_SENT;

    // --- GEO / HISTORY / MOJANG / HASH 硬编码日志配置化（批次 B） ---
    public static volatile String GEO_V4V6_DISABLED_WARN;
    public static volatile String GEO_INIT_SUCCESS;
    public static volatile String GEO_INIT_FAILED;
    public static volatile String GEO_XDB_MISSING_DOWNLOAD;
    public static volatile String GEO_INIT_SUCCESS_AFTER_DOWNLOAD;
    public static volatile String GEO_INIT_FAILED_AFTER_DOWNLOAD;
    public static volatile String GEO_DOWNLOAD_FAILED_DISABLED;
    public static volatile String GEO_XDB_MISSING_NO_DOWNLOAD;
    public static volatile String GEO_PARTIAL_MISSING_WARN;
    public static volatile String GEO_IPV6_SKIPPED;
    public static volatile String GEO_IPV4_SKIPPED;
    public static volatile String GEO_QUERY_FAILED;
    public static volatile String GEO_CLOSE_FAILED;
    public static volatile String GEO_DOWNLOADED_FILE;
    public static volatile String GEO_DOWNLOAD_ATTEMPT_FAILED;
    public static volatile String HISTORY_RECORD_FAILED;
    public static volatile String HISTORY_GET_LAST_FAILED;
    public static volatile String HISTORY_GET_FAILED;
    public static volatile String AUTH_INVALID_PASSWORD_HASH_FORMAT;
    public static volatile String AUTH_PASSWORD_VERIFY_ERROR;
    public static volatile String MOJANG_MALFORMED_HASJOINED_WARN;

    // --- 其余硬编码日志配置化（批次 C） ---
    public static volatile String SEC_SERVICES_RELOADED_LOG;
    public static volatile String AUTH_CHANGEPASSWORD_COMMAND_FAILED_LOG;
    public static volatile String AUTH_LOGIN_COMMAND_FAILED_LOG;
    public static volatile String AUTH_REGISTER_COMMAND_FAILED_LOG;
    public static volatile String MULTIVERSE_REFLECTION_FAILED_LOG;
    public static volatile String CORE_CLEANUP_ERROR;
    public static volatile String DB_CLOSE_OLD_CONNECTION_FAILED;
    public static volatile String DB_CLOSE_DATA_SOURCE_FAILED;
    public static volatile String DB_COLUMN_EXISTS;
    public static volatile String DB_INDEX_EXISTS;
    public static volatile String SEC_JOIN_ONLINE_LIMIT_KICK_LOG;
    public static volatile String AUTH_SAVE_LOCATION_FAILED_LOG;
    public static volatile String AUTH_LOAD_LOCATION_FAILED_LOG;
    public static volatile String SPAWN_WORLD_MISSING_LOG;

    // --- 硬编码日志消息配置化（Velocity 模块补充） ---
    public static volatile String VELOCITY_CONFIG_DEBUG;
    public static volatile String PRELOGIN_OTHER_PLUGIN_DENIED;
    public static volatile String PRELOGIN_VERIFY_EXCEPTION;
    public static volatile String LOGIN_PREMIUM_DECISION;
    public static volatile String LOGIN_OFFLINE_DECISION;
    public static volatile String STATE_CLEANUP_REMOVED;
    public static volatile String REWRITE_PREMIUM_UUID_OFFLINE;
    public static volatile String DISCONNECT_BEFORE_HASJOINED;

    // --- 通用 ---
    public static volatile String GENERIC_PERMISSION_DENIED;
    public static volatile String GENERIC_PLAYER_ONLY;
    public static volatile String GENERIC_PLAYER_NOT_FOUND;
    public static volatile String GENERIC_UNKNOWN;

    // --- 更新检查 ---
    public static volatile String UPDATE_CHECK_ENABLED_LOG;
    public static volatile String UPDATE_AVAILABLE_LOG;
    public static volatile String UPDATE_UP_TO_DATE_LOG;
    public static volatile String UPDATE_CHECK_FAILED_LOG;
    public static volatile String UPDATE_NOTIFY_PLAYER;
    public static volatile String UPDATE_STATUS_CURRENT;
    public static volatile String UPDATE_STATUS_LATEST;

    // ==================== 初始化 ====================

    /**
     * 初始化消息系统。
     *
     * @param dataDirectory 插件数据目录
     * @param lang          语言代码（如 "zh_cn"、"en_gb"）
     */
    public static void init(Path dataDirectory, String lang) {
        langDir = dataDirectory.resolve("lang");
        currentLang = (lang != null && !lang.isBlank()) ? lang : "en_gb";

        try {
            Files.createDirectories(langDir);
            extractBuiltinLanguages();
            loadMessages(currentLang);
            initialized = true;
            LOGGER.info("Messages system initialized (lang=" + currentLang + ", dir=" + langDir + ")");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize messages system", e);
        }
    }

    /**
     * 重新加载指定语言的消息。
     */
    public static void reload(String lang) {
        if (lang != null && !lang.isBlank()) {
            currentLang = lang;
        }
        loadMessages(currentLang);
        LOGGER.info("Messages reloaded (lang=" + currentLang + ")");
    }

    /**
     * 获取指定键的消息，参数化替换 {0}, {1} 等占位符。
     */
    public static String get(String key, String... args) {
        if (key == null) return "";
        String template = messages.getOrDefault(key, key);
        if (args.length == 0) {
            return template;
        }
        StringBuilder result = new StringBuilder(template);
        for (int i = 0; i < args.length; i++) {
            result = new StringBuilder(result.toString().replace("{" + i + "}", String.valueOf(args[i])));
        }
        return result.toString();
    }

    /**
     * 获取当前语言代码。
     */
    public static String getCurrentLang() {
        return currentLang;
    }

    /**
     * 判断语言代码是否受支持：外部 lang/ 目录或 JAR 内置资源中存在对应语言文件。
     */
    public static boolean isSupportedLanguage(String lang) {
        if (lang == null || lang.isBlank()) {
            return false;
        }
        if (langDir != null && Files.exists(langDir.resolve(lang + ".yml"))) {
            return true;
        }
        try (InputStream in = Messages.class.getResourceAsStream(RESOURCE_PREFIX + lang + ".yml")) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 按系统 Locale 选择语言（i18n 父子匹配链）：
     * 完整 Locale（zh_CN→zh_cn, en_GB→en_gb）→ 语言主码（zh/en）→ 语言族回退（zh_tw→zh_cn, en_us→en_gb）→ 默认 en_gb。
     * 语言代码与 lang/ 目录文件命名约定一致（{@code <lang>_<country>} 小写），无需映射表。
     */
    public static String detectSystemLanguage() {
        Locale def = Locale.getDefault();
        // 完整 Locale（如 zh_CN、en_GB、en_US）小写后与语言文件命名一致
        String full = def.toString().toLowerCase(Locale.ROOT);
        if (isSupportedLanguage(full)) {
            return full;
        }
        // 语言主码（如 zh、en），仅当存在对应语言文件时使用
        String main = def.getLanguage().toLowerCase(Locale.ROOT);
        if (isSupportedLanguage(main)) {
            return main;
        }
        // 语言族回退：在已有语言文件中找 <主码>_ 前缀（如 zh_tw → zh_cn、en_us → en_gb）
        String family = findLanguageFamily(main);
        if (family != null) {
            return family;
        }
        return "en_gb";
    }

    /**
     * 语言族回退：在外部 lang/ 目录与内置语言中查找以语言主码开头（{@code <main>_}）的语言文件。
     * @return 匹配的语言代码，无匹配返回 null
     */
    private static String findLanguageFamily(String main) {
        if (main == null || main.isEmpty()) {
            return null;
        }
        // 外部 lang/ 目录优先（管理员新增的语言文件可参与语言族匹配）
        if (langDir != null && Files.isDirectory(langDir)) {
            try (var stream = Files.list(langDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(main + "_") && name.endsWith(".yml")) {
                        return name.substring(0, name.length() - 4);
                    }
                }
            } catch (IOException e) {
                LOGGER.fine("Failed to list lang dir for language family: " + e.getMessage());
            }
        }
        // 内置语言兜底
        for (String builtin : BUILTIN_LANGS) {
            if (builtin.startsWith(main + "_")) {
                return builtin;
            }
        }
        return null;
    }

    // ==================== 内部方法 ====================

    /**
     * 从 JAR 内置资源提取默认语言文件到数据目录
     */
    private static void extractBuiltinLanguages() throws IOException {
        for (String lang : BUILTIN_LANGS) {
            Path target = langDir.resolve(lang + ".yml");
            if (!Files.exists(target)) {
                String resourcePath = RESOURCE_PREFIX + lang + ".yml";
                try (InputStream in = Messages.class.getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        Files.copy(in, target);
                        LOGGER.info("Extracted builtin language: " + lang + " → " + target);
                    } else {
                        writeDefaultLanguageFile(lang, target);
                    }
                }
            }
        }
    }

    /**
     * 从外部文件加载消息
     */
    private static void loadMessages(String lang) {
        messages.clear();
        Path langFile = langDir.resolve(lang + ".yml");

        // 尝试从文件加载
        if (Files.exists(langFile)) {
            loadFromYamlFile(langFile);
        } else {
            // 回退到内置资源
            String resourcePath = RESOURCE_PREFIX + lang + ".yml";
            try (InputStream in = Messages.class.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    loadFromStream(in);
                }
            } catch (IOException e) {
                LOGGER.warning("Failed to load resource " + resourcePath + ": " + e.getMessage());
            }
        }

        // 验证所有键是否已加载
        verifyKeys();

        // volatile 写作为内存屏障：保证上方 verifyKeys() 中所有静态 String 字段赋值
        // 对后续读取线程可见（reload 线程写、认证线程读的可见性问题）
        messagesVersion++;
    }

    /**
     * 从 YAML 文件加载（简化解析，支持 key: value 格式）
     * 强制使用 UTF-8 编码读取，避免中文乱码。
     */
    private static void loadFromYamlFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            loadFromString(content);
            LOGGER.fine("Loaded " + messages.size() + " messages from " + file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load language file: " + file, e);
        }
    }

    /**
     * 从 InputStream 加载（UTF-8 编码）
     */
    private static void loadFromStream(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, len);
                }
            }
            loadFromString(sb.toString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load language from stream", e);
        }
    }

    /**
     * 从字符串加载消息（简易 YAML key: value 解析）
     * 跳过注释行（以 # 开头）和空行。
     */
    private static void loadFromString(String content) {
        try (Reader reader = new StringReader(content)) {
            Properties props = new Properties();
            props.load(reader);
            props.forEach((key, value) -> messages.put(key.toString(), value.toString()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to parse language content", e);
        }
    }

    /**
     * 验证所有静态字段对应的键是否已加载
     */
    private static void verifyKeys() {
        // 自动将所有 static String 字段的值（作为键）从 messages map 赋回
        // 这样 Messages.DB_INIT_FAILED 等字段自动获得对应翻译
        DB_INIT_FAILED = messages.getOrDefault("db_init_failed", "[DB] Database initialization failed! All logins will be rejected.");
        DB_CONNECTED = messages.getOrDefault("db_connected", "[DB] Database connected: {0}");
        DB_PING_FAILED = messages.getOrDefault("db_ping_failed", "[DB] Database ping failed after connection!");
        DB_HEARTBEAT_PING_FAILED = messages.getOrDefault("db_heartbeat_ping_failed", "[DB] Database heartbeat: ping failed, attempting reconnect...");
        DB_HEARTBEAT_NOT_CONNECTED = messages.getOrDefault("db_heartbeat_not_connected", "[DB] Database heartbeat: not connected, attempting reconnect...");
        DB_RECONNECTED = messages.getOrDefault("db_reconnected", "[DB] Database reconnected successfully");
        DB_RECONNECT_FAILED = messages.getOrDefault("db_reconnect_failed", "[DB] Database reconnect failed: {0}");
        DB_HEARTBEAT_ERROR = messages.getOrDefault("db_heartbeat_error", "[DB] Database heartbeat error: {0}");
        DB_BACKUP_CREATED = messages.getOrDefault("db_backup_created", "[DB] Database backup created: {0}");
        DB_BACKUP_FAILED = messages.getOrDefault("db_backup_failed", "[DB] Database backup failed: {0}");
        DB_BACKUP_DELETED_OLD = messages.getOrDefault("db_backup_deleted_old", "[DB] Deleted old backup: {0}");
        DB_MIGRATION_COMPLETE = messages.getOrDefault("db_migration_complete", "[DB] Migration complete: {0} records migrated to {1}");
        DB_MIGRATION_FAILED = messages.getOrDefault("db_migration_failed", "[DB] Migration failed: {0}");
        DB_CONNECTION_FAILED = messages.getOrDefault("db_connection_failed", "[DB] Database connection failed: {0}");
        DB_REBUILD_CONNECTION = messages.getOrDefault("db_rebuild_connection", "[DB] Database config changed, rebuilding connection");
        DB_BACKUP_CLEAN_FAILED = messages.getOrDefault("db_backup_clean_failed", "[DB] Failed to clean old backups: {0}");
        DB_BACKUP_SKIP_INVALID_ROW = messages.getOrDefault("db_backup_skip_invalid_row", "[DB] Skipped invalid row during backup: username={0} uuid={1}");
        DB_CLOSE_FAILED = messages.getOrDefault("db_close_failed", "[DB] Failed to close database connection");
        DB_STATE_CHECK_FAILED = messages.getOrDefault("db_state_check_failed", "[DB] Database connection state check failed");
        DB_PING_EXCEPTION = messages.getOrDefault("db_ping_exception", "[DB] Database ping failed");
        DB_GET_PLAYER_FAILED = messages.getOrDefault("db_get_player_failed", "[DB] Failed to get player {0}");
        DB_SAVE_PLAYER_FAILED = messages.getOrDefault("db_save_player_failed", "[DB] Failed to save player {0}");
        DB_SAVE_PLAYER_SAFE_FAILED = messages.getOrDefault("db_save_player_safe_failed", "[DB] Failed to save player (safe) {0}");
        DB_EXISTS_FAILED = messages.getOrDefault("db_exists_failed", "[DB] Failed to check existence for player {0}");
        DB_COUNT_FAILED = messages.getOrDefault("db_count_failed", "[DB] Failed to count records");
        DB_COUNT_PREMIUM_FAILED = messages.getOrDefault("db_count_premium_failed", "[DB] Failed to count premium records");
        DB_MIGRATION_EXCEPTION = messages.getOrDefault("db_migration_exception", "[DB] Database migration failed");
        DB_UPDATE_LOCATION_FAILED = messages.getOrDefault("db_update_location_failed", "[DB] Failed to update player location for {0}");
        DB_CREATE_AUTH_TABLE_FAILED = messages.getOrDefault("db_create_auth_table_failed", "[DB] Failed to create auth table");
        DB_GET_AUTH_ACCOUNT_FAILED = messages.getOrDefault("db_get_auth_account_failed", "[DB] Failed to get auth account for {0}");
        DB_SAVE_AUTH_ACCOUNT_FAILED = messages.getOrDefault("db_save_auth_account_failed", "[DB] Failed to save auth account for {0}");
        DB_UPDATE_AUTH_PASSWORD_FAILED = messages.getOrDefault("db_update_auth_password_failed", "[DB] Failed to update auth password for {0}");
        DB_UPDATE_AUTH_LOGIN_FAILED = messages.getOrDefault("db_update_auth_login_failed", "[DB] Failed to update auth login for {0}");
        DB_DELETE_AUTH_ACCOUNT_FAILED = messages.getOrDefault("db_delete_auth_account_failed", "[DB] Failed to delete auth account for {0}");
        DB_AUTH_ACCOUNT_EXISTS_FAILED = messages.getOrDefault("db_auth_account_exists_failed", "[DB] Failed to check auth account exists for {0}");
        DB_CREATE_LOGIN_HISTORY_TABLE_FAILED = messages.getOrDefault("db_create_login_history_table_failed", "[DB] Failed to create login history table");
        DB_RECORD_LOGIN_HISTORY_FAILED = messages.getOrDefault("db_record_login_history_failed", "[DB] Failed to record login history for {0}");
        DB_GET_LOGIN_HISTORY_FAILED = messages.getOrDefault("db_get_login_history_failed", "[DB] Failed to get recent login history for {0}");
        DB_TRIM_LOGIN_HISTORY_FAILED = messages.getOrDefault("db_trim_login_history_failed", "[DB] Failed to trim login history for {0}");
        DB_CREATE_IP_STATS_TABLE_FAILED = messages.getOrDefault("db_create_ip_stats_table_failed", "[DB] Failed to create ip stats table");
        DB_GET_IP_STATS_FAILED = messages.getOrDefault("db_get_ip_stats_failed", "[DB] Failed to get ip stats for {0}");
        DB_INCREMENT_IP_ACCOUNT_FAILED = messages.getOrDefault("db_increment_ip_account_failed", "[DB] Failed to increment ip account count for {0}");
        DB_DECREMENT_IP_ACCOUNT_FAILED = messages.getOrDefault("db_decrement_ip_account_failed", "[DB] Failed to decrement ip account count for {0}");
        DB_GET_ACCOUNTS_BY_IP_FAILED = messages.getOrDefault("db_get_accounts_by_ip_failed", "[DB] Failed to query accounts by last IP {0}: {1}");
        DB_CREATE_INDEX_FAILED = messages.getOrDefault("db_create_index_failed", "[DB] Failed to create index: {0}");
        DB_PARSE_UUID_FAILED = messages.getOrDefault("db_parse_uuid_failed", "[DB] Failed to parse UUID for player {0}: {1}, skipped");
        CORE_INIT_PROXY = messages.getOrDefault("core_init_proxy", "Core initialized in PROXY mode (UUID verification via shared DB)");
        CORE_INIT_STANDALONE = messages.getOrDefault("core_init_standalone", "Core initialized successfully (premium verification by this plugin)");
        CORE_SHUTDOWN_COMPLETE = messages.getOrDefault("core_shutdown_complete", "Core shutdown complete");
        CORE_PROXY_MODE_DEBUG = messages.getOrDefault("core_proxy_mode_debug", "Running in PROXY mode (via Velocity forwarding) - Mojang API services disabled");
        CORE_API_INIT_DEBUG = messages.getOrDefault("core_api_init_debug", "Mojang API initialized (on-demand: official first, fallback on failure)");
        CORE_HEARTBEAT_SCHEDULED = messages.getOrDefault("core_heartbeat_scheduled", "Database heartbeat scheduled every {0}s");
        CORE_BACKUP_SCHEDULED = messages.getOrDefault("core_backup_scheduled", "Database backup scheduled every {0}h");
        CORE_RELOADED_PROXY = messages.getOrDefault("core_reloaded_proxy", "Core reloaded in PROXY mode (Mojang API disabled)");
        CORE_RELOADED_STANDALONE = messages.getOrDefault("core_reloaded_standalone", "Core reloaded (standalone mode, API on-demand)");
        CORE_CLOSE_MOJANG_SESSION_FAILED = messages.getOrDefault("core_close_mojang_session_failed", "Error closing mojang session service: {0}");
        CORE_CLOSE_MOJANG_API_FAILED = messages.getOrDefault("core_close_mojang_api_failed", "Error closing mojang api service: {0}");
        CORE_DISCONNECT_DB_FAILED = messages.getOrDefault("core_disconnect_db_failed", "Error disconnecting database: {0}");

        API_OFFICIAL_AVAILABLE = messages.getOrDefault("api_official_available", "[API] Mojang official API availability: {0} (HTTP {1})");
        API_OFFICIAL_UNAVAILABLE = messages.getOrDefault("api_official_unavailable", "[API] Mojang official API unreachable: {0}");
        API_FALLBACK_AVAILABLE = messages.getOrDefault("api_fallback_available", "[API] Fallback API #{0}: {1} (HTTP {2})");
        API_FALLBACK_UNAVAILABLE = messages.getOrDefault("api_fallback_unavailable", "[API] Fallback API #{0} unreachable: {1}");
        API_ALL_DOWN = messages.getOrDefault("api_all_down", "[API] ALL Mojang APIs are unreachable! Premium verification will be rejected for premium players.");
        API_OFFICIAL_COOLDOWN = messages.getOrDefault("api_official_cooldown", "[API] Mojang official API in cooldown ({0}s) after repeated failures, using fallback");
        API_RATE_LIMIT_REACHED = messages.getOrDefault("api_rate_limit_reached", "[API] Mojang API rate limit reached (too many concurrent requests)");
        API_HIGH_FAILURE_RATE = messages.getOrDefault("api_high_failure_rate", "[API] Mojang API high failure rate detected ({0}%), failing over to next available API...");
        API_RECOVERED = messages.getOrDefault("api_recovered", "[API] Mojang API is back online - now using {0}");
        API_PROBE_START = messages.getOrDefault("api_probe_start", "[API] Downtime recovery probe started (next probe window +{0}s)");
        API_PARSE_FAILED = messages.getOrDefault("api_parse_failed", "[API] Failed to parse Mojang API response: {0} body={1}");
        API_FAST_FAIL_DOWNTIME = messages.getOrDefault("api_fast_fail_downtime", "[API] Fast-fail during downtime ({0}s until next recovery probe), skipping API call");
        API_PROBE_IN_PROGRESS = messages.getOrDefault("api_probe_in_progress", "[API] Recovery probe in progress by another thread, fast-failing");
        API_OFFICIAL_CHECK_COMPLETE = messages.getOrDefault("api_official_check_complete", "[API][OFFICIAL] Username {0} check complete: {1} (took {2}ms)");
        API_FALLBACK_CHECK_COMPLETE = messages.getOrDefault("api_fallback_check_complete", "[API][FALLBACK#{0}] Username {1} check complete: {2} (took {3}ms)");
        API_FALLBACK_INVALID_TEMPLATE = messages.getOrDefault("api_fallback_invalid_template",
                "[API] Invalid fallback API URL template (must contain {username} placeholder), skipping: {0}");

        AUTH_DATABASE_UNAVAILABLE = messages.getOrDefault("auth_database_unavailable", "§cMultiAuth 数据库当前不可用，无法登录。");
        AUTH_SERVICE_NOT_INITIALIZED = messages.getOrDefault("auth_service_not_initialized", "§c认证服务未初始化，请联系管理员。");
        AUTH_CONCURRENT_LOGIN_BLOCKED = messages.getOrDefault("auth_concurrent_login_blocked", "§c该账号正在验证中，请稍后再试。");
        AUTH_SERVER_BUSY = messages.getOrDefault("auth_server_busy", "§c服务器认证繁忙，请稍后重试。");
        AUTH_USERNAME_CHECK_FAILED = messages.getOrDefault("auth_username_check_failed", "[AUTH] Player {0} username check failed: {1}");
        AUTH_CHECK_INTERNAL_ERROR = messages.getOrDefault("auth_check_internal_error",
                "§c认证检查发生内部错误，请稍后重试或联系管理员。");
        AUTH_PREMIUM_DETECTED = messages.getOrDefault("auth_premium_detected", "[AUTH] Player {0} username is premium (UUID={1}) - requiring Mojang verification");
        AUTH_PREMIUM_IN_AUTHLIST = messages.getOrDefault("auth_premium_in_authlist", "[AUTH] Player {0} not premium but in auth-list - requiring verification");
        AUTH_OFFLINE_ALLOWED = messages.getOrDefault("auth_offline_allowed", "[AUTH] Player {0} not premium - allowing offline login");
        AUTH_DOWNTIME_FLOW = messages.getOrDefault("auth_downtime_flow", "[AUTH] Player {0} - Mojang API unreachable, applying downtime flow");
        AUTH_PLAYER_ALLOWED = messages.getOrDefault("auth_player_allowed", "[AUDIT] Player {0} ALLOWED - {1} (UUID: {2})");
        AUTH_PLAYER_DENIED = messages.getOrDefault("auth_player_denied", "[AUDIT] Player {0} DENIED - {1}");
        AUTH_HANDSHAKE_FAILED = messages.getOrDefault("auth_handshake_failed", "[AUDIT] Player {0} DENIED - Velocity handshake failed (pirate client or invalid session, GameProfileRequest never reached)");
        AUTH_INVALID_SESSION = messages.getOrDefault("auth_invalid_session", "§c无效会话（可能是盗版客户端或正版未登录）\n§7请确认已通过正版启动器登录 Minecraft 账号，\n§7若仍失败请重启游戏及启动器后重试。");
        AUTH_MOJANG_VERIFY_PASSED = messages.getOrDefault("auth_mojang_verify_passed", "[AUDIT] Player {0} Mojang verification PASSED - UUID: {1}");
        AUTH_MOJANG_VERIFY_FAILED_PIRATE = messages.getOrDefault("auth_mojang_verify_failed_pirate", "[AUDIT] Player {0} Mojang verification FAILED - pirate client");
        AUTH_MOJANG_UNREACHABLE = messages.getOrDefault("auth_mojang_unreachable", "[AUDIT] Player {0} - Mojang session server unreachable");
        AUTH_DOWNTIME_DENY = messages.getOrDefault("auth_downtime_deny", "§6Mojang 认证服务器当前不可用。\n§7为保障账号安全，宕机期间已暂停所有登录。\n§7请稍后重试。");
        AUTH_DOWNTIME_ALLOW_OFFLINE = messages.getOrDefault("auth_downtime_allow_offline", "[AUTH] Player {0} allowed offline login during Mojang downtime (offline history)");
        AUTH_UUID_MISMATCH = messages.getOrDefault("auth_uuid_mismatch", "[AUDIT] Player {0} UUID mismatch! Expected: {1}, Got: {2} - possible session hijack!");
        AUTH_EXECUTOR_FULL = messages.getOrDefault("auth_executor_full", "[AUTH] Verification thread pool full, rejecting player {0} (server overloaded, try again later)");
        AUTH_PLUGIN_DISABLED = messages.getOrDefault("auth_plugin_disabled", "[AUTH] Plugin disabled, rejecting player {0}");
        AUTH_VERIFY_UNEXPECTED_ERROR = messages.getOrDefault("auth_verify_unexpected_error", "[AUTH] Unexpected exception during verification of player {0}");
        AUTH_LISTENER_UNREGISTERED_CLOSE = messages.getOrDefault("auth_listener_unregistered_close", "[AUTH] PacketEvents listener unregistered (proxy switch), closing connection of player {0}");
        AUTH_DENY_CLIENT_DISCONNECTED = messages.getOrDefault("auth_deny_client_disconnected", "[AUTH] Player {0} verification failed and rejected (client already disconnected: offline login with premium name / pirate client), Disconnect packet cannot be delivered");
        AUTH_DENY_SEND_DISCONNECT = messages.getOrDefault("auth_deny_send_disconnect", "[AUTH] Player {0} verification failed and rejected, sending Disconnect packet");
        AUTH_NO_PACKETEVENT_VERIFY = messages.getOrDefault("auth_no_packetevent_verify", "[AUTH] Player {0} not verified by PacketEvents, rejecting login");
        AUTH_VERIFY_FAILED_DENY = messages.getOrDefault("auth_verify_failed_deny", "[AUTH] Player {0} verification failed, rejecting login");
        AUTH_API_ONLY_MODE = messages.getOrDefault("auth_api_only_mode", "[AUTH] PacketEvents not installed, proxy=false degraded to API-only (LAYER-1 username check only, premium players cannot complete encrypted verification)");
        AUTH_NO_LOGIN_SUMMARY = messages.getOrDefault("auth_no_login_summary", "[AUTH] Player {0} has no pre-login summary (reload race), treating as passed");
        AUTH_ALLOW_WINS_DENY_IGNORED = messages.getOrDefault("auth_allow_wins_deny_ignored", "[AUTH] Player {0} already has another connection verified (ALLOW), ignoring this DENY result from another connection");
        AUTH_CONCURRENCY_FULL = messages.getOrDefault("auth_concurrency_full", "[AUTH] Login verification concurrency limit reached, rejecting player {0} (try again later)");
        AUTH_API_RATE_LIMITED = messages.getOrDefault("auth_api_rate_limited", "[AUTH] Mojang API rate limit reached for {0}, please try again later");

        AUTH_REGISTER_PROMPT = messages.getOrDefault("auth_register_prompt", "§e请先注册账号！使用 §f/register <密码> <确认密码> §e注册");
        AUTH_LOGIN_PROMPT = messages.getOrDefault("auth_login_prompt", "§e请先登录！使用 §f/login <密码> §e登录");
        AUTH_REGISTER_SUCCESS = messages.getOrDefault("auth_register_success", "§a注册成功！请使用 §f/login <密码> §a登录");
        AUTH_REGISTER_FAILED = messages.getOrDefault("auth_register_failed", "§c注册失败，请稍后重试或联系管理员。");
        AUTH_REGISTER_ALREADY = messages.getOrDefault("auth_register_already", "§c您已注册，请使用 §f/login <密码> §c登录。");
        AUTH_REGISTER_PASSWORD_MISMATCH = messages.getOrDefault("auth_register_password_mismatch", "§c两次输入的密码不一致。");
        AUTH_REGISTER_PASSWORD_TOO_SHORT = messages.getOrDefault("auth_register_password_too_short", "§c密码长度不能少于 {0} 个字符。");
        AUTH_REGISTER_PASSWORD_TOO_LONG = messages.getOrDefault("auth_register_password_too_long", "§c密码长度不能超过 {0} 个字符。");
        AUTH_REGISTER_PASSWORD_EMPTY = messages.getOrDefault("auth_register_password_empty", "§c密码不能为空。");
        AUTH_LOGIN_SUCCESS = messages.getOrDefault("auth_login_success", "§a登录成功！");
        AUTH_LOGIN_FAILED = messages.getOrDefault("auth_login_failed", "§c登录失败：密码错误。");
        AUTH_LOGIN_NOT_REGISTERED = messages.getOrDefault("auth_login_not_registered", "§c您尚未注册，请先使用 §f/register §c注册。");
        AUTH_LOGIN_PROCESSING = messages.getOrDefault("auth_login_processing", "§e正在验证中，请稍候...");
        AUTH_LOGIN_ALREADY = messages.getOrDefault("auth_login_already", "§a您已登录。");
        AUTH_CHANGEPASSWORD_SUCCESS = messages.getOrDefault("auth_changepassword_success", "§a密码修改成功！");
        AUTH_CHANGEPASSWORD_FAILED = messages.getOrDefault("auth_changepassword_failed", "§c密码修改失败，请稍后重试。");
        AUTH_CHANGEPASSWORD_WRONG_OLD = messages.getOrDefault("auth_changepassword_wrong_old", "§c旧密码错误。");
        AUTH_NOT_LOGGED_IN = messages.getOrDefault("auth_not_logged_in", "§c您尚未登录，请先使用 §f/login §c登录。");
        AUTH_RESTRICTED = messages.getOrDefault("auth_restricted", "§c请先登录后再进行操作。");
        AUTH_LOGIN_TIMEOUT = messages.getOrDefault("auth_login_timeout", "§c登录超时，您已被踢出服务器。");
        AUTH_REGISTER_TIMEOUT = messages.getOrDefault("auth_register_timeout", "§c注册超时，您已被踢出服务器。");
        AUTH_UNREGISTER_SUCCESS = messages.getOrDefault("auth_unregister_success", "§a已删除玩家 {0} 的账号。");
        AUTH_UNREGISTER_NOT_FOUND = messages.getOrDefault("auth_unregister_not_found", "§c未找到玩家 {0} 的注册信息。");
        AUTH_INFO_BASE = messages.getOrDefault("auth_info_base", "§6玩家 {0} 的信息：\n§7UUID: §f{1}\n§7状态: §f{2}\n§7最后IP: §f{3}\n§7地理位置: §f{4}");
        AUTH_INFO_OFFLINE_EXTRA = messages.getOrDefault("auth_info_offline_extra", "\n§7类型: §b离线账号\n§7注册时间: §f{0}\n§7最后登录: §f{1}");
        AUTH_INFO_PREMIUM_EXTRA = messages.getOrDefault("auth_info_premium_extra", "\n§7类型: §b正版\n§7最后登录: §f{0}\n§7第一次进入: §f{1}");
        AUTH_INFO_NEVER_LOGGED_IN = messages.getOrDefault("auth_info_never_logged_in", "从未登录");
        AUTH_INFO_NOT_REGISTERED = messages.getOrDefault("auth_info_not_registered", "§c该玩家尚未注册。");
        AUTH_INFO_STATUS_ONLINE = messages.getOrDefault("auth_info_status_online", "在线");
        AUTH_INFO_STATUS_OFFLINE = messages.getOrDefault("auth_info_status_offline", "离线");
        AUTH_INFO_LOCATION = messages.getOrDefault("auth_info_location", "\n§7登出地点: §f{0}");
        AUTH_INFO_OTHER_ACCOUNTS = messages.getOrDefault("auth_info_other_accounts", "\n§7关联账号({0}): §f{1}");
        AUTH_INFO_OTHER_ACCOUNTS_NONE = messages.getOrDefault("auth_info_other_accounts_none", "\n§7关联账号: §f无");
        AUTH_OTHER_ACCOUNTS_NOTIFY = messages.getOrDefault("auth_other_accounts_notify", "§e该IP关联的其他账号：§f{0}");
        AUTH_MODULE_DISABLED = messages.getOrDefault("auth_module_disabled", "§c认证模块已禁用。");
        AUTH_UNREGISTER_KICK = messages.getOrDefault("auth_unregister_kick", "§c您的账号已被管理员删除，请重新注册。");
        // 安全增强消息
        AUTH_ACCOUNT_COOLDOWN = messages.getOrDefault("auth_account_cooldown", "§c账户冷却中，请等待 §f{0} §c秒后再试。");
        AUTH_IP_COOLDOWN = messages.getOrDefault("auth_ip_cooldown", "§c该 IP 被临时封禁，请等待 §f{0} §c秒。");
        AUTH_LOGIN_TOO_MANY_FAILURES = messages.getOrDefault("auth_login_too_many_failures", "§c登录失败次数过多，已被踢出。");
        AUTH_ATTEMPTS_REMAINING = messages.getOrDefault("auth_attempts_remaining", "§c密码错误。剩余尝试次数：§f{0}");
        AUTH_IP_ACCOUNT_LIMIT = messages.getOrDefault("auth_ip_account_limit", "§c该 IP 注册的账号数已达上限。");
        AUTH_IP_ONLINE_LIMIT = messages.getOrDefault("auth_ip_online_limit", "§c该 IP 的在线账号数已达上限。");
        AUTH_IP_CHANGE_WARNING = messages.getOrDefault("auth_ip_change_warning", "§e警告：检测到 IP 变更（上次：§f{0}§e，当前：§f{1}§e）。");
        AUTH_GEO_CROSS_COUNTRY = messages.getOrDefault("auth_geo_cross_country", "§e警告：检测到跨国登录（上次：§f{0}§e，当前：§f{1}§e）。");
        AUTH_GEO_CROSS_CITY = messages.getOrDefault("auth_geo_cross_city", "§e警告：检测到跨城市登录（上次：§f{0}§e，当前：§f{1}§e）。");
        AUTH_GEO_REQUIRE_LOGIN = messages.getOrDefault("auth_geo_require_login", "§e检测到异地登录，需重新输入密码验证。");
        SEC_QUERY_IP_STATS_FAILED = messages.getOrDefault("sec_query_ip_stats_failed", "[SEC] Failed to query ip stats for {0}: {1}");
        SEC_GEO_CHECK_SKIPPED_LOG = messages.getOrDefault("sec_geo_check_skipped_log", "[SEC] Geo/history query failed for {0}, geo security checks skipped (fail-open)");
        SEC_GEO_DB_FAILED_DENY_LOG = messages.getOrDefault("sec_geo_db_failed_deny_log",
                "[AUDIT] Login denied for {0} - geolocation security check data query failed (fail-closed), error {1}");
        AUTH_STATE_AUTHMANAGER_MISSING_LOG = messages.getOrDefault("auth_state_authmanager_missing_log",
                "[AUTH] AuthManager not initialized, cannot determine player type, {0} treated as offline player (fail-closed)");
        AUTH_PLAYER_JOIN_ERROR = messages.getOrDefault("auth_player_join_error",
                "[AUTH] Unexpected error while handling PlayerJoinEvent for {0}: {1}");
        SEC_INCREMENT_IP_ACCOUNT_FAILED = messages.getOrDefault("sec_increment_ip_account_failed", "[SEC] Failed to increment ip account count for {0}: {1}");
        SEC_DECREMENT_IP_ACCOUNT_FAILED = messages.getOrDefault("sec_decrement_ip_account_failed", "[SEC] Failed to decrement ip account count for {0}: {1}");
        DENY_REASON_DB_UNAVAILABLE = messages.getOrDefault("deny_reason_db_unavailable", "database unavailable");
        DENY_REASON_AUTHMANAGER_NOT_INITIALIZED = messages.getOrDefault("deny_reason_authmanager_not_initialized", "AuthManager not initialized");
        DENY_REASON_CONCURRENT_LOGIN = messages.getOrDefault("deny_reason_concurrent_login", "concurrent login blocked");
        DENY_REASON_NO_ENC_RESPONSE = messages.getOrDefault("deny_reason_no_enc_response", "no encryption response (pirate client?)");
        ALLOW_REASON_NO_RECORD = messages.getOrDefault("allow_reason_no_record", "no record (first join via proxy)");
        ALLOW_REASON_OFFLINE_RECORD = messages.getOrDefault("allow_reason_offline_record", "offline record");
        ALLOW_REASON_PREMIUM_KEEP_OFFLINE_UUID = messages.getOrDefault("allow_reason_premium_keep_offline_uuid", "premium UUID forwarded but use-mojang-uuid=false (keep offline record)");
        ALLOW_REASON_UPGRADE_OFFLINE_TO_PREMIUM = messages.getOrDefault("allow_reason_upgrade_offline_to_premium", "upgrading offline record to premium (UUID={0})");
        ALLOW_REASON_UUID_AUTOCORRECT_OFFLINE_TO_PREMIUM = messages.getOrDefault("allow_reason_uuid_autocorrect_offline_to_premium", "UUID auto-correct (offline to premium): {0}");
        ALLOW_REASON_UUID_AUTOCORRECT_PREMIUM_TO_OFFLINE = messages.getOrDefault("allow_reason_uuid_autocorrect_premium_to_offline", "UUID auto-correct (premium to offline): {0}");
        LOGIN_TYPE_PREMIUM = messages.getOrDefault("login_type_premium", "premium");
        LOGIN_TYPE_PREMIUM_OFFLINE_UUID = messages.getOrDefault("login_type_premium_offline_uuid", "premium(offline UUID)");
        LOGIN_TYPE_OFFLINE = messages.getOrDefault("login_type_offline", "offline");
        LOGIN_TYPE_PREMIUM_API_ONLY = messages.getOrDefault("login_type_premium_api_only", "premium(API-only)");
        LOGIN_TYPE_OFFLINE_API_ONLY = messages.getOrDefault("login_type_offline_api_only", "offline(API-only)");
        API_STATUS_RECOVERED = messages.getOrDefault("api_status_recovered", "recovered");
        API_SOURCE_OFFICIAL = messages.getOrDefault("api_source_official", "official");
        API_SOURCE_FALLBACK = messages.getOrDefault("api_source_fallback", "fallback #{0}");
        KICK_REJECTED_MESSAGE = messages.getOrDefault("kick_rejected_message", "[KICK] Player {0} rejected, message: {1}");
        KICK_MESSAGE_SENT = messages.getOrDefault("kick_message_sent", "[KICK] Player {0} received message: {1}");
        LOGIN_SUCCESS = messages.getOrDefault("login_success", "[LOGIN] Player {0} login success [{1}] UUID={2} IP={3}");
        LOGIN_SUCCESS_PREMIUM = messages.getOrDefault("login_success_premium", "[LOGIN] Player {0} login success [premium] UUID={1} IP={2}");
        LOGIN_SUCCESS_OFFLINE = messages.getOrDefault("login_success_offline", "[LOGIN] Player {0} login success [offline] UUID={1} IP={2}");
        STATE_MISS = messages.getOrDefault("state_miss", "[STATE-MISS] Player {0} has no PreLogin decision cache (plugin reload race?), fail-closed fallback: no identity inference, no UUID rewrite, no DB record written");
        PACKET_FAKE_LOGIN_START_KICK = messages.getOrDefault("packet_fake_login_start_kick", "§cLogin verification succeeded, but login packet injection failed, please reconnect");

        AUTH_AUDIT_HASJOINED_UNREACHABLE = messages.getOrDefault("auth_audit_hasjoined_unreachable",
                "[AUDIT] Player {0} DENIED - hasJoined verification failed (Mojang session server unreachable), premium verification cannot complete");
        AUTH_AUDIT_DOWNTIME_NO_RECORD = messages.getOrDefault("auth_audit_downtime_no_record",
                "[AUDIT] Player {0} DENIED - all LAYER-1 APIs unreachable with no history record, cannot determine premium status, login paused during downtime");
        AUTH_AUDIT_DOWNTIME_PREMIUM_HISTORY = messages.getOrDefault("auth_audit_downtime_premium_history",
                "[AUDIT] Player {0} DENIED - all LAYER-1 APIs unreachable and account has premium history (UUID={1}), login paused during downtime");
        AUTH_AUDIT_API_ONLY_AUTHLIST = messages.getOrDefault("auth_audit_api_only_authlist",
                "[AUDIT] Player {0} DENIED - auth-list forced verification cannot be satisfied in API-only mode");
        AUTH_AUDIT_API_ONLY_PREMIUM = messages.getOrDefault("auth_audit_api_only_premium",
                "[AUDIT] Player {0} DENIED - premium username cannot complete encrypted verification in API-only mode");
        AUTH_AUDIT_HASJOINED_RATE_LIMITED = messages.getOrDefault("auth_audit_hasjoined_rate_limited",
                "[AUDIT] Player {0} DENIED - hasJoined verification rate-limited (local concurrency, not outage), premium verification cannot complete");

        CONFIG_LOADED = messages.getOrDefault("config_loaded", "Config loaded: proxy={0}, db-type={1}");
        CONFIG_RELOADED = messages.getOrDefault("config_reloaded", "Reloading config...");
        CONFIG_RELOAD_FAILED = messages.getOrDefault("config_reload_failed", "Failed to reload config: {0}");
        CONFIG_ONLINE_MODE_INCOMPATIBLE_WARN = messages.getOrDefault("config_online_mode_incompatible_warn",
                "[MultiAuth] proxy=false requires online-mode=false in server.properties (Spigot Mojang verification will not work)");
        CONFIG_PROXY_CHANGE_RESTART = messages.getOrDefault("config_proxy_change_restart",
                "§cProxy mode changed to {0} - please restart the server for the change to fully take effect!");
        CONFIG_DEFAULT_CREATED = messages.getOrDefault("config_default_created", "Default config.toml created");
        CONFIG_LOAD_FAILED = messages.getOrDefault("config_load_failed", "Failed to load config.toml, using defaults: {0}");
        CONFIG_UPGRADE_DONE = messages.getOrDefault("config_upgrade_done",
                "Config auto-upgraded to v{1}: {0} new options appended with defaults");
        CONFIG_UPGRADE_FAILED = messages.getOrDefault("config_upgrade_failed",
                "Failed to auto-upgrade config: {0}");

        PLUGIN_VELOCITY_INITIALIZED = messages.getOrDefault("plugin_velocity_initialized", "MultiAuth Velocity plugin initialized");
        PLUGIN_VELOCITY_SHUTDOWN = messages.getOrDefault("plugin_velocity_shutdown", "MultiAuth Velocity plugin shutdown");
        PLUGIN_PROXY_SWITCH_TRUE = messages.getOrDefault("plugin_proxy_switch_true", "[MultiAuth] proxy switched to true, PacketEvents interception unregistered (verification handled by Velocity)");
        PLUGIN_PROXY_SWITCH_FALSE = messages.getOrDefault("plugin_proxy_switch_false", "[MultiAuth] proxy switched to false, PacketEvents interception enabled");
        PLUGIN_API_ONLY_WARNING = messages.getOrDefault("plugin_api_only_warning", "[MultiAuth] PacketEvents not installed, proxy=false degraded to API-only (LAYER-1 username check only, premium players cannot login)");
        PLUGIN_PACKETEVENT_INSTALL_HINT = messages.getOrDefault("plugin_packetevent_install_hint", "[MultiAuth] Please install the PacketEvents plugin to enable encrypted handshake verification");

        PACKET_NO_VERIFY_CALLBACK = messages.getOrDefault("packet_no_verify_callback", "[PACKET][LOGIN_START] No verification callback set, user {0} cannot login");
        PACKET_LOGIN_START_PARSE_FAILED = messages.getOrDefault("packet_login_start_parse_failed", "[PACKET][LOGIN_START] Parse failed: {0}");
        PACKET_ENC_RESPONSE_PARSE_FAILED = messages.getOrDefault("packet_enc_response_parse_failed", "[PACKET][ENCRYPTION_RESPONSE] Parse failed: {0}");
        PACKET_ENC_REQUEST_WRAPPER_FAILED = messages.getOrDefault("packet_enc_request_wrapper_failed", "[PACKET][ENCRYPTION_REQUEST] Wrapper failed, falling back to raw write: {0}");
        PACKET_ENC_RESPONSE_LATE_DENIED = messages.getOrDefault("packet_enc_response_late_denied",
                "[PACKET][ENCRYPTION_RESPONSE] Late response from a denied channel, cancelled (user already rejected)");
        PACKET_FAKE_LOGIN_START_FAILED = messages.getOrDefault("packet_fake_login_start_failed", "[PACKET][FAKE_LOGIN_START] Send failed: {0}");
        PACKET_FAKE_LOGIN_START_FALLBACK_FAILED = messages.getOrDefault("packet_fake_login_start_fallback_failed", "[PACKET][FAKE_LOGIN_START] Fallback also failed, kicking player {0}: {1}");
        PACKET_DISCONNECT_SEND_FAILED = messages.getOrDefault("packet_disconnect_send_failed", "[PACKET][DISCONNECT] Send failed, closing channel directly: {0}");
        VERIFY_NO_PACKETEVENT = messages.getOrDefault("verify_no_packetevent", "[VERIFY][{0}] PacketEvents unavailable, cannot complete encrypted handshake verification, rejecting");
        VERIFY_HANDSHAKE_TIMEOUT = messages.getOrDefault("verify_handshake_timeout", "[VERIFY][{0}] Encryption handshake timeout (no EncryptionResponse within 5s, likely pirate client)");
        VERIFY_ENC_RESPONSE_PARSE_FAILED = messages.getOrDefault("verify_enc_response_parse_failed", "[VERIFY][{0}] EncryptionResponse parse failed (client sent invalid encryption response): {1}");
        VERIFY_ENC_RESPONSE_INTERRUPTED = messages.getOrDefault("verify_enc_response_interrupted", "[VERIFY][{0}] Waiting for EncryptionResponse was interrupted");
        VERIFY_DECRYPT_FAILED = messages.getOrDefault("verify_decrypt_failed", "[VERIFY][{0}] sharedSecret decryption failed (verifyToken mismatch): {1}");
        VERIFY_AES_ANCHOR_MISSING = messages.getOrDefault("verify_aes_anchor_missing", "[VERIFY][{0}] AES encryption enable failed (pipeline anchor not found), rejecting login: sending LoginSuccess unencrypted would disconnect the client");
        VERIFY_HASJOINED_FAILED = messages.getOrDefault("verify_hasjoined_failed", "[VERIFY][{0}] hasJoined failed: {1}");
        VERIFY_INBOUND_ANCHOR_MISSING = messages.getOrDefault("verify_inbound_anchor_missing", "[VERIFY] Inbound anchor handler not found (splitter/decompress/decoder), AES encryption enable failed");
        VERIFY_OUTBOUND_ANCHOR_MISSING = messages.getOrDefault("verify_outbound_anchor_missing", "[VERIFY] Outbound anchor handler not found (prepender/compress/encoder), AES encryption enable failed");
        VERIFY_AES_ENABLE_FAILED = messages.getOrDefault("verify_aes_enable_failed", "[VERIFY] Netty AES encryption enable failed: {0}");
        VERIFY_SPOOFED_CONNECTION_MISSING = messages.getOrDefault("verify_spoofed_connection_missing", "[VERIFY] Cannot set spoofedUUID: Connection not found");
        VERIFY_SPOOFED_FAILED = messages.getOrDefault("verify_spoofed_failed", "[VERIFY] Setting spoofedUUID failed: {0}");
        VERIFY_PACKET_HANDLER_MISSING = messages.getOrDefault("verify_packet_handler_missing", "[VERIFY] packet_handler not found");
        VERIFY_CONNECTION_FAILED = messages.getOrDefault("verify_connection_failed", "[VERIFY] Failed to get Connection: {0}");
        VERIFY_HANDLER_ROLLBACK_DEBUG = messages.getOrDefault("verify_handler_rollback_debug",
                "[DEBUG] Failed to rollback {0} handler: {1}");

        CMD_HELP = messages.getOrDefault("cmd_help", "MultiAuth Commands:\n  /multiauth reload  - Reload config\n  /multiauth status  - Show plugin status\n  /multiauth backup  - Force database backup\n  /multiauth migrate <type> - Migrate database (sqlite|mysql)");
        CMD_NO_PERMISSION = messages.getOrDefault("cmd_no_permission", "§cYou don't have permission to use this command.");
        CMD_STATUS = messages.getOrDefault("cmd_status", "MultiAuth Status:\n  Version: {0}\n  Database: {1}\n  Mode: {2}\n  Mojang API: {3}\n  Total Historic Players: {4}\n  Premium Players: {5}");
        CMD_RELOAD_SUCCESS = messages.getOrDefault("cmd_reload_success", "§aConfig reloaded successfully");
        CMD_MIGRATE_USAGE = messages.getOrDefault("cmd_migrate_usage", "§cUsage: /multiauth migrate <sqlite|mysql>");
        CMD_MIGRATE_SUCCESS = messages.getOrDefault("cmd_migrate_success", "§aMigration complete: {0} records migrated to {1}");
        CMD_MIGRATE_FAILED = messages.getOrDefault("cmd_migrate_failed", "§cMigration failed: {0}");
        CMD_MIGRATE_INVALID_TYPE = messages.getOrDefault("cmd_migrate_invalid_type", "§cInvalid database type. Use: sqlite, mysql");
        CMD_BACKUP_SUCCESS = messages.getOrDefault("cmd_backup_success", "§a数据库备份已成功创建");
        CMD_BACKUP_FAILED = messages.getOrDefault("cmd_backup_failed", "§c数据库备份失败，请查看控制台了解详情");
        CMD_PLUGIN_INFO = messages.getOrDefault("cmd_plugin_info", "§6MultiAuth v{0} - Player authentication plugin");
        CMD_INFO_USAGE = messages.getOrDefault("cmd_info_usage", "§e用法：/multiauth info <玩家>");
        CMD_INFO_SELF_ONLY = messages.getOrDefault("cmd_info_self_only", "§c您只能查询自己的信息。");
        CMD_UNREGISTER_USAGE = messages.getOrDefault("cmd_unregister_usage", "§e用法：/multiauth unregister <玩家>");
        CMD_CHANGEPASSWORD_USAGE = messages.getOrDefault("cmd_changepassword_usage", "§e用法：/changepassword <旧密码> <新密码>");
        CMD_MIGRATE_SAME_TYPE = messages.getOrDefault("cmd_migrate_same_type", "目标类型与当前相同，无需迁移");
        CMD_CHECK_CONSOLE = messages.getOrDefault("cmd_check_console", "请查看控制台了解详情");
        CMD_MODE_PROXY = messages.getOrDefault("cmd_mode_proxy", "代理模式");
        CMD_MODE_DIRECT = messages.getOrDefault("cmd_mode_direct", "直连模式");
        DB_STATUS_HEALTHY = messages.getOrDefault("db_status_healthy", "健康");
        DB_STATUS_UNHEALTHY = messages.getOrDefault("db_status_unhealthy", "异常");
        API_STATUS_NORMAL = messages.getOrDefault("api_status_normal", "正常");
        API_STATUS_DOWN = messages.getOrDefault("api_status_down", "宕机");
        API_STATUS_DISABLED = messages.getOrDefault("api_status_disabled", "未启用");
        API_STATUS_UNKNOWN = messages.getOrDefault("api_status_unknown", "未知");
        CMD_CORE_NOT_INITIALIZED = messages.getOrDefault("cmd_core_not_initialized", "§cCore 未初始化，无法执行此命令。");
        CMD_MIGRATE_IN_PROGRESS = messages.getOrDefault("cmd_migrate_in_progress", "§7正在迁移数据到 {0} ...");
        CMD_BACKUP_IN_PROGRESS = messages.getOrDefault("cmd_backup_in_progress", "§7正在创建数据库备份...");

        SESSION_START = messages.getOrDefault("session_start", "[SESSION] Player {0} connecting - starting {1} verification");
        SESSION_COMPLETE = messages.getOrDefault("session_complete", "[SESSION] Player {0} authentication complete - {1}");
        SESSION_DISCONNECT = messages.getOrDefault("session_disconnect", "[SESSION] Player {0} disconnected - cleaning up auth state");
        SESSION_JOIN_NOTIFY = messages.getOrDefault("session_join_notify", "[SESSION] Player {0} joined the server (UUID: {1}, Premium: {2})");
        SESSION_HIJACK_WARNING = messages.getOrDefault("session_hijack_warning", "§c[SECURITY WARNING] UUID mismatch for {0}! Session hijack suspected!");
        SESSION_STATUS_NOTIFY = messages.getOrDefault("session_status_notify",
                "§e您当前为{0}登录状态");
        SESSION_STATUS_LOG = messages.getOrDefault("session_status_log",
                "[SESSION] Player {0} login status: {1} (UUID: {2})");
        SESSION_SYNC_ENABLED = messages.getOrDefault("session_sync_enabled",
                "[MultiAuth] Cross-server session sync enabled (channel: {0})");
        SESSION_SYNC_RECEIVER_REGISTERED = messages.getOrDefault("session_sync_receiver_registered",
                "[MultiAuth] Cross-server session sync receiver registered (channel: {0})");
        SESSION_SYNC_LOGIN = messages.getOrDefault("session_sync_login",
                "[AUTH] Player {0} logged in via Velocity cross-server sync (IP: {1}, Premium: {2})");
        SESSION_SYNC_LOGOUT = messages.getOrDefault("session_sync_logout",
                "[AUTH] Player {0} logged out via Velocity cross-server sync");
        SESSION_SYNC_PARSE_ERROR = messages.getOrDefault("session_sync_parse_error",
                "[AUTH] Failed to parse cross-server session sync message: {0}");
        SESSION_SYNC_REJECT_CLIENT = messages.getOrDefault("session_sync_reject_client",
                "[AUTH] Blocked client-forged cross-server session sync message (forward to backend rejected)");
        SESSION_SYNC_TTL_REMOVED = messages.getOrDefault("session_sync_ttl_removed",
                "[SESSION] Removed expired session record (TTL): {0} (UUID: {1})");
        SESSION_SYNC_SECRET_MISSING = messages.getOrDefault("session_sync_secret_missing",
                "[AUTH] session-sync-secret is not configured on Velocity side; cross-server session sync messages are NOT authenticated. Set the same secret in Velocity and Spigot configs.");
        SESSION_SYNC_BAD_SIGNATURE = messages.getOrDefault("session_sync_bad_signature",
                "[AUTH] Rejected cross-server session sync message from player {0} (UUID: {1}): invalid signature");
        SESSION_SYNC_AUTH_UP_LOG = messages.getOrDefault("session_sync_auth_up_log",
                "[SESSION] Backend reported auth success: {0} (UUID: {1}, IP: {2})");
        SESSION_SYNC_SKIP_OFFLINE_DEBUG = messages.getOrDefault("session_sync_skip_offline_debug",
                "[DEBUG] Offline player {0} is not recorded in proxy session (waiting for backend auth report)");
        SESSION_SYNC_SEND_FAILED_DEBUG = messages.getOrDefault("session_sync_send_failed_debug",
                "[DEBUG] Failed to send session sync message to {0}: {1}");
        SEC_SESSION_RESUME_CHECK_FAILED = messages.getOrDefault("sec_session_resume_check_failed",
                "[SEC] Failed to check session resume security for {0}: {1}");
        CONFIG_LOADED_DEBUG = messages.getOrDefault("config_loaded_debug",
                "[Config] Loaded: proxy={0}, useMojangUuid={1}, debug={2}");
        CONFIG_RELOADING = messages.getOrDefault("config_reloading",
                "[Config] Reloading config...");
        PACKETEVENTS_LOADED = messages.getOrDefault("packetevents_loaded",
                "[MultiAuth] PacketEvents loaded, encryption handshake mode enabled");
        PACKETEVENTS_NOT_INSTALLED = messages.getOrDefault("packetevents_not_installed",
                "[MultiAuth] PacketEvents not installed, proxy=false mode degraded to API-only (username check only, premium players cannot login)");
        PACKETEVENTS_INSTALL_REQUIRED = messages.getOrDefault("packetevents_install_required",
                "[MultiAuth] Please install PacketEvents to enable encryption handshake verification");
        AUTH_MODULE_ENABLED = messages.getOrDefault("auth_module_enabled",
                "[MultiAuth] Offline player register/login module enabled (with security enhancements)");
        SESSION_CLEAN_FAILED = messages.getOrDefault("session_clean_failed",
                "Failed to clean expired sessions: {0}");
        DB_SAVE_FAILED = messages.getOrDefault("db_save_failed",
                "Failed to save player record: {0}");
        AUTH_ACCOUNT_UNREGISTERED_LOG = messages.getOrDefault("auth_account_unregistered_log",
                "[AUTH] Account unregistered: {0}");
        PACKET_LISTENER_REGISTERED = messages.getOrDefault("packet_listener_registered",
                "[PACKET] PacketEvents listener registered (Plan A: LOGIN_START interception + fake packet)");
        PACKET_LOGIN_START_VERIFIED = messages.getOrDefault("packet_login_start_verified",
                "[PACKET][LOGIN_START] Verified user {0}, passing fake packet");
        PACKET_LOGIN_START_USERNAME = messages.getOrDefault("packet_login_start_username",
                "[PACKET][LOGIN_START] username={0}, not in verifiedChannels, processing");
        PACKET_LOGIN_START_DUPLICATE = messages.getOrDefault("packet_login_start_duplicate",
                "[PACKET][LOGIN_START] Duplicate LOGIN_START for user {0}, dropping re-entry");
        PACKET_REGISTER_HANDSHAKE_EXISTS = messages.getOrDefault("packet_register_handshake_exists",
                "[PACKET][HANDSHAKE] Pending handshake already registered for channel (user={0}), reusing existing");
        PACKET_ENC_RESPONSE_RECEIVED = messages.getOrDefault("packet_enc_response_received",
                "[PACKET][ENCRYPTION_RESPONSE] Received encryption response, user={0}");
        PACKET_ENC_REQUEST_SENT = messages.getOrDefault("packet_enc_request_sent",
                "[PACKET][ENCRYPTION_REQUEST] Sent (PacketEvents Wrapper)");
        PACKET_VERIFY_CHANNEL_CLOSED = messages.getOrDefault("packet_verify_channel_closed",
                "[AUTH] Player {0} channel closed during verification, skipping cache");
        PACKET_FAKE_LOGIN_START_SENT = messages.getOrDefault("packet_fake_login_start_sent",
                "[PACKET][FAKE_LOGIN_START] Sent fake packet username={0} uuid={1}");
        PACKET_DISCONNECT_CHANNEL_CLOSED = messages.getOrDefault("packet_disconnect_channel_closed",
                "[PACKET][DISCONNECT] Channel already disconnected, skipping Disconnect packet");
        PACKET_DISCONNECT_ALREADY_SENT = messages.getOrDefault("packet_disconnect_already_sent",
                "[PACKET][DISCONNECT] Disconnect packet already sent, skipping duplicate");
        PACKET_DISCONNECT_SENT = messages.getOrDefault("packet_disconnect_sent",
                "[PACKET][DISCONNECT] Sent disconnect packet: {0}");
        AUTH_PLAYER_VERIFIED_DEBUG = messages.getOrDefault("auth_player_verified_debug",
                "[AUTH] Player {0} verified, allowing login (UUID={1})");
        AUTH_NO_LOGIN_SUMMARY_DEBUG = messages.getOrDefault("auth_no_login_summary_debug",
                "[AUTH] Player {0} has no pre-login summary (reload race), determining by UUID");

        // 硬编码日志消息配置化（Spigot 模块补充）
        VERIFY_HANDSHAKE_START = messages.getOrDefault("verify_handshake_start",
                "[VERIFY][{0}] Starting encrypted handshake (PacketEvents + NMS)");
        VERIFY_ENC_REQUEST_SENT_WAITING = messages.getOrDefault("verify_enc_request_sent_waiting",
                "[VERIFY][{0}] EncryptionRequest sent, waiting for response (5s timeout)...");
        VERIFY_ENC_RESPONSE_RECEIVED_DEBUG = messages.getOrDefault("verify_enc_response_received_debug",
                "[VERIFY][{0}] EncryptionResponse received, starting verification...");
        VERIFY_HASJOINED_PASSED = messages.getOrDefault("verify_hasjoined_passed",
                "[VERIFY][{0}] hasJoined verification passed, UUID={1}");
        VERIFY_SKIP_SPOOFED_UUID = messages.getOrDefault("verify_skip_spoofed_uuid",
                "[VERIFY][{0}] use-mojang-uuid=false, skipping spoofedUUID (keeping offline UUID)");
        VERIFY_AES_ENABLED = messages.getOrDefault("verify_aes_enabled",
                "[VERIFY] AES encryption enabled (Netty Handler, anchors: {0} / {1})");
        VERIFY_SPOOFED_UUID_SET = messages.getOrDefault("verify_spoofed_uuid_set",
                "[VERIFY] spoofedUUID set: {0}");
        MULTIVERSE_LOAD_WORLD_FAILED = messages.getOrDefault("multiverse_load_world_failed",
                "[MultiAuth] Multiverse loadWorld failed for '{0}'");
        AUTH_PROXY_SKIP_SPIGOT = messages.getOrDefault("auth_proxy_skip_spigot",
                "[AUTH] proxy=true, skipping Spigot-side verification (handled by Velocity proxy)");
        AUTH_CREATE_AUTH_TABLE_FAILED_LOG = messages.getOrDefault("auth_create_auth_table_failed_log",
                "[AUTH] Failed to create auth table: {0}");
        AUTH_CREATE_SECURITY_TABLES_FAILED_LOG = messages.getOrDefault("auth_create_security_tables_failed_log",
                "[AUTH] Failed to create security tables: {0}");
        SEC_REGISTRATION_BLOCKED_LOG = messages.getOrDefault("sec_registration_blocked_log",
                "[SEC] Registration blocked for {0}: IP {1} reached account limit ({2})");
        AUTH_HASH_SUBMISSION_FAILED_LOG = messages.getOrDefault("auth_hash_submission_failed_log",
                "[AUTH] Password hash submission failed for {0}: {1}");
        AUTH_REGISTER_SUCCESS_LOG = messages.getOrDefault("auth_register_success_log",
                "[AUTH] Player {0} registered successfully (IP={1})");
        AUTH_SAVE_ACCOUNT_FAILED_LOG = messages.getOrDefault("auth_save_account_failed_log",
                "[AUTH] Failed to save auth account for {0}: {1}");
        AUTH_HASH_FAILED_LOG = messages.getOrDefault("auth_hash_failed_log",
                "[AUTH] Password hash failed for {0}: {1}");
        AUTH_REGISTER_FAILED_LOG = messages.getOrDefault("auth_register_failed_log",
                "[AUTH] Register failed for {0}: {1}");
        AUTH_GEO_HISTORY_QUERY_FAILED_LOG = messages.getOrDefault("auth_geo_history_query_failed_log",
                "[AUTH] Geo/history query failed for {0}: {1}");
        SEC_IP_CHANGED_LOG = messages.getOrDefault("sec_ip_changed_log",
                "[SEC] Player {0} IP changed: {1} -> {2}");
        AUTH_LOGIN_SUCCESS_LOG = messages.getOrDefault("auth_login_success_log",
                "[AUTH] Player {0} logged in successfully (IP={1})");
        SEC_TOO_MANY_FAILURES_KICK_LOG = messages.getOrDefault("sec_too_many_failures_kick_log",
                "[SEC] Player {0} kicked for too many failed attempts (IP={1})");
        AUTH_LOGIN_WRONG_PASSWORD_LOG = messages.getOrDefault("auth_login_wrong_password_log",
                "[AUTH] Player {0} login failed: wrong password (IP={1})");
        AUTH_LOGIN_UPDATE_FAILED_LOG = messages.getOrDefault("auth_login_update_failed_log",
                "[AUTH] Failed to update login for {0}: {1}");
        AUTH_PASSWORD_VERIFY_FAILED_LOG = messages.getOrDefault("auth_password_verify_failed_log",
                "[AUTH] Password verify failed for {0}: {1}");
        AUTH_LOGIN_FAILED_LOG = messages.getOrDefault("auth_login_failed_log",
                "[AUTH] Login failed for {0}: {1}");
        SEC_GEO_KICK_LOG = messages.getOrDefault("sec_geo_kick_log",
                "[SEC] Player {0} {1} login detected, will be kicked");
        SEC_GEO_WARN_LOG = messages.getOrDefault("sec_geo_warn_log",
                "[SEC] Player {0} {1} login warning sent");
        SEC_SESSION_RESUME_KICK_LOG = messages.getOrDefault("sec_session_resume_kick_log",
                "[SEC] Player {0} {1} login detected during session resume, will be kicked");
        SEC_SESSION_RESUME_REQUIRE_LOGIN_LOG = messages.getOrDefault("sec_session_resume_require_login_log",
                "[SEC] Player {0} {1} login detected during session resume, requiring fresh login");
        SEC_SESSION_RESUME_WARN_LOG = messages.getOrDefault("sec_session_resume_warn_log",
                "[SEC] Player {0} {1} login warning sent during session resume");
        AUTH_CHANGE_PASSWORD_SUCCESS_LOG = messages.getOrDefault("auth_change_password_success_log",
                "[AUTH] Player {0} changed password successfully");
        AUTH_PASSWORD_UPDATE_FAILED_LOG = messages.getOrDefault("auth_password_update_failed_log",
                "[AUTH] Failed to update password for {0}: {1}");
        AUTH_CHANGE_PASSWORD_FAILED_LOG = messages.getOrDefault("auth_change_password_failed_log",
                "[AUTH] Change password failed for {0}: {1}");
        AUTH_UNREGISTER_SUCCESS_LOG = messages.getOrDefault("auth_unregister_success_log",
                "[AUTH] Account unregistered: {0}");
        AUTH_UNREGISTER_FAILED_LOG = messages.getOrDefault("auth_unregister_failed_log",
                "[AUTH] Failed to unregister {0}: {1}");
        AUTH_PLAYER_VERIFIED_SEND_FAKE = messages.getOrDefault("auth_player_verified_send_fake",
                "[AUTH] Player {0} verified, sending fake LOGIN_START packet (UUID={1})");
        MSG_STATUS_NOTIFY_SENT = messages.getOrDefault("msg_status_notify_sent",
                "[MSG] Player {0} received status notification: {1}");
        PACKET_FAKE_LOGIN_START_FALLBACK_SENT = messages.getOrDefault("packet_fake_login_start_fallback_sent",
                "[PACKET][FAKE_LOGIN_START] Sent fake packet (receivePacket fallback)");
        PACKET_ENC_REQUEST_RAW_SENT = messages.getOrDefault("packet_enc_request_raw_sent",
                "[PACKET][ENCRYPTION_REQUEST] Sent (raw binary)");
        GEO_V4V6_DISABLED_WARN = messages.getOrDefault("geo_v4v6_disabled_warn",
                "[GEO] Both v4 and v6 query are disabled, geo service disabled");
        GEO_INIT_SUCCESS = messages.getOrDefault("geo_init_success",
                "[GEO] ip2region service initialized successfully");
        GEO_INIT_FAILED = messages.getOrDefault("geo_init_failed",
                "[GEO] Failed to initialize ip2region: {0}");
        GEO_XDB_MISSING_DOWNLOAD = messages.getOrDefault("geo_xdb_missing_download",
                "[GEO] xdb file(s) missing, starting async download...");
        GEO_INIT_SUCCESS_AFTER_DOWNLOAD = messages.getOrDefault("geo_init_success_after_download",
                "[GEO] ip2region service initialized successfully after download");
        GEO_INIT_FAILED_AFTER_DOWNLOAD = messages.getOrDefault("geo_init_failed_after_download",
                "[GEO] Failed to initialize ip2region after download: {0}");
        GEO_DOWNLOAD_FAILED_DISABLED = messages.getOrDefault("geo_download_failed_disabled",
                "[GEO] Failed to download xdb files, geo service remains disabled");
        GEO_XDB_MISSING_NO_DOWNLOAD = messages.getOrDefault("geo_xdb_missing_no_download",
                "[GEO] xdb file(s) missing and auto-download disabled, geo service disabled");
        GEO_PARTIAL_MISSING_WARN = messages.getOrDefault("geo_partial_missing_warn",
                "[GEO] Missing xdb file(s): {0}; initializing with available parts");
        GEO_IPV6_SKIPPED = messages.getOrDefault("geo_ipv6_skipped",
                "[GEO] Player connected via IPv6 but v6 query is disabled, skipping");
        GEO_IPV4_SKIPPED = messages.getOrDefault("geo_ipv4_skipped",
                "[GEO] Player connected via IPv4 but v4 query is disabled, skipping");
        GEO_QUERY_FAILED = messages.getOrDefault("geo_query_failed",
                "[GEO] Failed to query IP {0}: {1}");
        GEO_CLOSE_FAILED = messages.getOrDefault("geo_close_failed",
                "[GEO] Failed to close ip2region: {0}");
        GEO_DOWNLOADED_FILE = messages.getOrDefault("geo_downloaded_file",
                "[GEO] Downloaded xdb file: {0}");
        GEO_DOWNLOAD_ATTEMPT_FAILED = messages.getOrDefault("geo_download_attempt_failed",
                "[GEO] Download attempt {0}/{1} failed for {2}: {3}");
        HISTORY_RECORD_FAILED = messages.getOrDefault("history_record_failed",
                "[HISTORY] Failed to record login history for {0}: {1}");
        HISTORY_GET_LAST_FAILED = messages.getOrDefault("history_get_last_failed",
                "[HISTORY] Failed to get last login for {0}: {1}");
        HISTORY_GET_FAILED = messages.getOrDefault("history_get_failed",
                "[HISTORY] Failed to get login history for {0}: {1}");
        AUTH_INVALID_PASSWORD_HASH_FORMAT = messages.getOrDefault("auth_invalid_password_hash_format",
                "[AUTH] Invalid password hash format: {0}");
        AUTH_PASSWORD_VERIFY_ERROR = messages.getOrDefault("auth_password_verify_error",
                "[AUTH] Password verification error: {0}");
        MOJANG_MALFORMED_HASJOINED_WARN = messages.getOrDefault("mojang_malformed_hasjoined_warn",
                "[MOJANG] Malformed response from hasJoined: {0}");
        SEC_SERVICES_RELOADED_LOG = messages.getOrDefault("sec_services_reloaded_log",
                "[MultiAuth] Security services reloaded with new config");
        AUTH_CHANGEPASSWORD_COMMAND_FAILED_LOG = messages.getOrDefault("auth_changepassword_command_failed_log",
                "[AUTH] Change password command failed for {0}: {1}");
        AUTH_LOGIN_COMMAND_FAILED_LOG = messages.getOrDefault("auth_login_command_failed_log",
                "[AUTH] Login command failed for {0}: {1}");
        AUTH_REGISTER_COMMAND_FAILED_LOG = messages.getOrDefault("auth_register_command_failed_log",
                "[AUTH] Register command failed for {0}: {1}");
        MULTIVERSE_REFLECTION_FAILED_LOG = messages.getOrDefault("multiverse_reflection_failed_log",
                "[MultiAuth] Multiverse loadWorld reflection failed for '{0}': {1}");
        CORE_CLEANUP_ERROR = messages.getOrDefault("core_cleanup_error",
                "Cleanup error: {0}");
        DB_CLOSE_OLD_CONNECTION_FAILED = messages.getOrDefault("db_close_old_connection_failed",
                "Failed to close old connection: {0}");
        DB_CLOSE_DATA_SOURCE_FAILED = messages.getOrDefault("db_close_data_source_failed",
                "Failed to close data source: {0}");
        DB_COLUMN_EXISTS = messages.getOrDefault("db_column_exists",
                "Column already exists or migration not needed: {0}");
        DB_INDEX_EXISTS = messages.getOrDefault("db_index_exists",
                "Index already exists: {0}");
        SEC_JOIN_ONLINE_LIMIT_KICK_LOG = messages.getOrDefault("sec_join_online_limit_kick_log",
                "[SEC] Player {0} kicked: IP {1} reached online limit ({2})");
        AUTH_SAVE_LOCATION_FAILED_LOG = messages.getOrDefault("auth_save_location_failed_log",
                "[AUTH] Failed to save location for {0}: {1}");
        AUTH_LOAD_LOCATION_FAILED_LOG = messages.getOrDefault("auth_load_location_failed_log",
                "[AUTH] Failed to load last location for {0}: {1}");
        SPAWN_WORLD_MISSING_LOG = messages.getOrDefault("spawn_world_missing_log",
                "[MultiAuth] Spawn-point world '{0}' not found, falling back to current world spawn");

        // 硬编码日志消息配置化（Velocity 模块补充）
        VELOCITY_CONFIG_DEBUG = messages.getOrDefault("velocity_config_debug",
                "Config loaded: use-mojang-uuid={0}, auth-list={1}, db-type={2}");
        PRELOGIN_OTHER_PLUGIN_DENIED = messages.getOrDefault("prelogin_other_plugin_denied",
                "[PRELOGIN] Player {0} already denied by another plugin, skipping verification");
        PRELOGIN_VERIFY_EXCEPTION = messages.getOrDefault("prelogin_verify_exception",
                "[PRELOGIN] Player {0} verification exception: {1}");
        LOGIN_PREMIUM_DECISION = messages.getOrDefault("login_premium_decision",
                "[LOGIN] Player {0} premium decision, waiting for hasJoined (IP={1})");
        LOGIN_OFFLINE_DECISION = messages.getOrDefault("login_offline_decision",
                "[LOGIN] Player {0} offline decision (IP={1})");
        STATE_CLEANUP_REMOVED = messages.getOrDefault("state_cleanup_removed",
                "[STATE-CLEANUP][{0}] 3s elapsed without hasJoined pass and connection disconnected, cleaning residual state");
        REWRITE_PREMIUM_UUID_OFFLINE = messages.getOrDefault("rewrite_premium_uuid_offline",
                "use-mojang-uuid=false: rewritten premium profile UUID -> {0}");
        DISCONNECT_BEFORE_HASJOINED = messages.getOrDefault("disconnect_before_hasjoined",
                "[DISCONNECT] Player {0} disconnected before hasJoined passed (pirate client verification failure or player-initiated disconnect)");

        GENERIC_PERMISSION_DENIED = messages.getOrDefault("generic_permission_denied", "§cYou don't have permission to use this command.");
        GENERIC_PLAYER_ONLY = messages.getOrDefault("generic_player_only", "§cThis command can only be used by players.");
        GENERIC_PLAYER_NOT_FOUND = messages.getOrDefault("generic_player_not_found", "§cPlayer not found: {0}");
        GENERIC_UNKNOWN = messages.getOrDefault("generic_unknown", "未知");
        UPDATE_CHECK_ENABLED_LOG = messages.getOrDefault("update_check_enabled_log",
                "[Update] Update check enabled (repository: {0}, interval: {1}h)");
        UPDATE_AVAILABLE_LOG = messages.getOrDefault("update_available_log",
                "[Update] New version available: {0} (current: {1}) | {2}");
        UPDATE_UP_TO_DATE_LOG = messages.getOrDefault("update_up_to_date_log",
                "[Update] You are running the latest version {0}");
        UPDATE_CHECK_FAILED_LOG = messages.getOrDefault("update_check_failed_log",
                "[Update] Unable to check for updates: {0}, please check manually");
        UPDATE_NOTIFY_PLAYER = messages.getOrDefault("update_notify_player",
                "§e[MultiAuth] §aNew version available: §f{0} §a(current: {1}) §7§o{2}");
        UPDATE_STATUS_CURRENT = messages.getOrDefault("update_status_current", "§7Current version: §f{0}");
        UPDATE_STATUS_LATEST = messages.getOrDefault("update_status_latest", "§7Latest version: §f{0}");

        LOGGER.info("Messages verified: " + messages.size() + " entries loaded for lang=" + currentLang);
    }

    /**
     * 当语言文件不存在时，写入默认内容
     */
    private static void writeDefaultLanguageFile(String lang, Path target) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MultiAuth Language File\n");
        sb.append("# Language: ").append(lang).append("\n");
        sb.append("# Auto-generated by MultiAuth plugin\n\n");

        if ("zh_cn".equals(lang)) {
            sb.append("# 数据库相关\n");
            sb.append("db_init_failed=[DB] 数据库初始化失败！所有登录将被拒绝。\n");
            sb.append("db_connected=[DB] 数据库已连接：{0}\n");
            sb.append("db_ping_failed=[DB] 数据库连接后 Ping 失败！\n");
            sb.append("db_heartbeat_ping_failed=[DB] 数据库心跳：Ping 失败，尝试重连...\n");
            sb.append("db_heartbeat_not_connected=[DB] 数据库心跳：未连接，尝试重连...\n");
            sb.append("db_reconnected=[DB] 数据库重连成功\n");
            sb.append("db_reconnect_failed=[DB] 数据库重连失败：{0}\n");
            sb.append("db_heartbeat_error=[DB] 数据库心跳错误：{0}\n");
            sb.append("db_backup_created=[DB] 数据库备份已创建：{0}\n");
            sb.append("db_backup_failed=[DB] 数据库备份失败：{0}\n");
            sb.append("db_backup_deleted_old=[DB] 已删除旧备份：{0}\n");
            sb.append("db_migration_complete=[DB] 迁移完成：{0} 条记录已迁移到 {1}\n");
            sb.append("db_migration_failed=[DB] 迁移失败：{0}\n");
            sb.append("db_connection_failed=[DB] 数据库连接失败：{0}\n");
            sb.append("db_rebuild_connection=[DB] 数据库配置已变更，重建数据库连接\n");
            sb.append("db_backup_clean_failed=[DB] 清理旧备份失败：{0}\n");
            sb.append("db_backup_skip_invalid_row=[DB] 备份时跳过无效记录: username={0} uuid={1}\n");
            sb.append("db_close_failed=[DB] 关闭数据库连接失败\n");
            sb.append("db_state_check_failed=[DB] 数据库连接状态检查失败\n");
            sb.append("db_ping_exception=[DB] 数据库 Ping 失败\n");
            sb.append("db_get_player_failed=[DB] 获取玩家 {0} 失败\n");
            sb.append("db_save_player_failed=[DB] 保存玩家 {0} 失败\n");
            sb.append("db_save_player_safe_failed=[DB] 条件保存玩家 {0} 失败\n");
            sb.append("db_exists_failed=[DB] 检查玩家 {0} 是否存在失败\n");
            sb.append("db_count_failed=[DB] 统计记录数失败\n");
            sb.append("db_count_premium_failed=[DB] 统计正版记录数失败\n");
            sb.append("db_migration_exception=[DB] 数据库迁移失败\n");
            sb.append("db_update_location_failed=[DB] 更新玩家 {0} 的位置失败\n");
            sb.append("db_create_auth_table_failed=[DB] 创建认证表失败\n");
            sb.append("db_get_auth_account_failed=[DB] 获取玩家 {0} 的认证账号失败\n");
            sb.append("db_save_auth_account_failed=[DB] 保存玩家 {0} 的认证账号失败\n");
            sb.append("db_update_auth_password_failed=[DB] 更新玩家 {0} 的密码失败\n");
            sb.append("db_update_auth_login_failed=[DB] 更新玩家 {0} 的登录信息失败\n");
            sb.append("db_delete_auth_account_failed=[DB] 删除玩家 {0} 的认证账号失败\n");
            sb.append("db_auth_account_exists_failed=[DB] 检查玩家 {0} 的认证账号是否存在失败\n");
            sb.append("db_create_login_history_table_failed=[DB] 创建登录历史表失败\n");
            sb.append("db_record_login_history_failed=[DB] 记录玩家 {0} 的登录历史失败\n");
            sb.append("db_get_login_history_failed=[DB] 获取玩家 {0} 的登录历史失败\n");
            sb.append("db_trim_login_history_failed=[DB] 清理玩家 {0} 的登录历史失败\n");
            sb.append("db_create_ip_stats_table_failed=[DB] 创建 IP 统计表失败\n");
            sb.append("db_get_ip_stats_failed=[DB] 获取 IP {0} 的统计信息失败\n");
            sb.append("db_increment_ip_account_failed=[DB] 递增 IP {0} 的账号计数失败\n");
            sb.append("db_decrement_ip_account_failed=[DB] 递减 IP {0} 的注册账号数失败\n");
            sb.append("db_create_index_failed=[DB] 创建索引失败: {0}\n");
            sb.append("db_parse_uuid_failed=[DB] 解析玩家 {0} 的 UUID 失败: {1}，已跳过该记录\n");
            sb.append("core_init_proxy=Core 以 PROXY 模式初始化（通过共享数据库校验 UUID）\n");
            sb.append("core_init_standalone=Core 初始化成功（本插件进行正版验证）\n");
            sb.append("core_shutdown_complete=Core 关闭完成\n");
            sb.append("core_proxy_mode_debug=以 PROXY 模式运行（通过 Velocity 转发）- Mojang API 服务已禁用\n");
            sb.append("core_api_init_debug=Mojang API 已初始化（按需调用：官方优先，失败后备用）\n");
            sb.append("core_heartbeat_scheduled=数据库心跳已调度，每 {0}s 执行一次\n");
            sb.append("core_backup_scheduled=数据库备份已调度，每 {0}h 执行一次\n");
            sb.append("core_reloaded_proxy=Core 已在 PROXY 模式下重载（Mojang API 已禁用）\n");
            sb.append("auth_create_auth_table_failed_log=[AUTH] 创建 auth 数据表失败: {0}\n");
            sb.append("auth_create_security_tables_failed_log=[AUTH] 创建安全数据表失败: {0}\n");
            sb.append("sec_registration_blocked_log=[SEC] 注册被阻止: 玩家 {0} 的 IP {1} 已达账号数量上限 ({2})\n");
            sb.append("auth_hash_submission_failed_log=[AUTH] 提交 {0} 的密码哈希失败: {1}\n");
            sb.append("auth_register_success_log=[AUTH] 玩家 {0} 注册成功 (IP={1})\n");
            sb.append("auth_save_account_failed_log=[AUTH] 保存玩家 {0} 的账号失败: {1}\n");
            sb.append("auth_hash_failed_log=[AUTH] {0} 的密码哈希失败: {1}\n");
            sb.append("auth_register_failed_log=[AUTH] {0} 注册失败: {1}\n");
            sb.append("auth_geo_history_query_failed_log=[AUTH] 查询 {0} 的地理/历史信息失败: {1}\n");
            sb.append("sec_ip_changed_log=[SEC] 玩家 {0} 的 IP 变更: {1} -> {2}\n");
            sb.append("auth_login_success_log=[AUTH] 玩家 {0} 登录成功 (IP={1})\n");
            sb.append("sec_too_many_failures_kick_log=[SEC] 玩家 {0} 因失败次数过多被踢出 (IP={1})\n");
            sb.append("auth_login_wrong_password_log=[AUTH] 玩家 {0} 登录失败: 密码错误 (IP={1})\n");
            sb.append("auth_login_update_failed_log=[AUTH] 更新 {0} 的登录信息失败: {1}\n");
            sb.append("auth_password_verify_failed_log=[AUTH] {0} 的密码验证失败: {1}\n");
            sb.append("auth_login_failed_log=[AUTH] {0} 登录失败: {1}\n");
            sb.append("sec_geo_kick_log=[SEC] 检测到玩家 {0} 异地{1}登录，将踢出\n");
            sb.append("sec_geo_warn_log=[SEC] 已向玩家 {0} 发送异地{1}登录警告\n");
            sb.append("sec_session_resume_kick_log=[SEC] 会话恢复时检测到玩家 {0} 异地{1}登录，将踢出\n");
            sb.append("sec_session_resume_require_login_log=[SEC] 会话恢复时检测到玩家 {0} 异地{1}登录，要求重新登录\n");
            sb.append("sec_session_resume_warn_log=[SEC] 会话恢复时已向玩家 {0} 发送异地{1}登录警告\n");
            sb.append("auth_change_password_success_log=[AUTH] 玩家 {0} 修改密码成功\n");
            sb.append("auth_password_update_failed_log=[AUTH] 更新 {0} 的密码失败: {1}\n");
            sb.append("auth_change_password_failed_log=[AUTH] {0} 修改密码失败: {1}\n");
            sb.append("auth_unregister_success_log=[AUTH] 账号已注销: {0}\n");
            sb.append("auth_unregister_failed_log=[AUTH] 注销 {0} 失败: {1}\n");
            sb.append("geo_v4v6_disabled_warn=[GEO] v4 与 v6 查询均被禁用，地理位置服务已停用\n");
            sb.append("geo_init_success=[GEO] ip2region 服务初始化成功\n");
            sb.append("geo_init_failed=[GEO] 初始化 ip2region 失败: {0}\n");
            sb.append("geo_xdb_missing_download=[GEO] xdb 文件缺失，开始异步下载...\n");
            sb.append("geo_init_success_after_download=[GEO] 下载后 ip2region 服务初始化成功\n");
            sb.append("geo_init_failed_after_download=[GEO] 下载后初始化 ip2region 失败: {0}\n");
            sb.append("geo_download_failed_disabled=[GEO] 下载 xdb 文件失败，地理位置服务保持停用\n");
            sb.append("geo_xdb_missing_no_download=[GEO] xdb 文件缺失且自动下载已禁用，地理位置服务已停用\n");
            sb.append("geo_partial_missing_warn=[GEO] 缺少 xdb 文件：{0}；使用可用部分初始化\n");
            sb.append("geo_ipv6_skipped=[GEO] 玩家通过 IPv6 连接但 v6 查询已禁用，跳过\n");
            sb.append("geo_ipv4_skipped=[GEO] 玩家通过 IPv4 连接但 v4 查询已禁用，跳过\n");
            sb.append("geo_query_failed=[GEO] 查询 IP {0} 失败: {1}\n");
            sb.append("geo_close_failed=[GEO] 关闭 ip2region 失败: {0}\n");
            sb.append("geo_downloaded_file=[GEO] 已下载 xdb 文件: {0}\n");
            sb.append("geo_download_attempt_failed=[GEO] 下载第 {0}/{1} 次尝试失败（文件: {2}）: {3}\n");
            sb.append("history_record_failed=[HISTORY] 记录 {0} 的登录历史失败: {1}\n");
            sb.append("history_get_last_failed=[HISTORY] 获取 {0} 的最近登录记录失败: {1}\n");
            sb.append("history_get_failed=[HISTORY] 获取 {0} 的登录历史失败: {1}\n");
            sb.append("auth_invalid_password_hash_format=[AUTH] 密码哈希格式无效: {0}\n");
            sb.append("auth_password_verify_error=[AUTH] 密码验证错误: {0}\n");
            sb.append("mojang_malformed_hasjoined_warn=[MOJANG] hasJoined 返回格式异常: {0}\n");
            sb.append("sec_services_reloaded_log=[MultiAuth] 安全增强服务已按新配置重新加载\n");
            sb.append("auth_changepassword_command_failed_log=[AUTH] {0} 执行修改密码命令失败: {1}\n");
            sb.append("auth_login_command_failed_log=[AUTH] {0} 执行登录命令失败: {1}\n");
            sb.append("auth_register_command_failed_log=[AUTH] {0} 执行注册命令失败: {1}\n");
            sb.append("multiverse_reflection_failed_log=[MultiAuth] Multiverse loadWorld 反射调用失败（{0}）: {1}\n");
            sb.append("core_cleanup_error=清理出错: {0}\n");
            sb.append("db_close_old_connection_failed=关闭旧连接失败: {0}\n");
            sb.append("db_close_data_source_failed=关闭数据源失败: {0}\n");
            sb.append("db_column_exists=列已存在或无需迁移: {0}\n");
            sb.append("db_index_exists=索引已存在: {0}\n");
            sb.append("sec_join_online_limit_kick_log=[SEC] 玩家 {0} 被踢出: IP {1} 已达在线账号上限 ({2})\n");
            sb.append("auth_save_location_failed_log=[AUTH] 保存 {0} 的位置失败: {1}\n");
            sb.append("auth_load_location_failed_log=[AUTH] 加载 {0} 的最后位置失败: {1}\n");
            sb.append("spawn_world_missing_log=[MultiAuth] 出生点世界 '{0}' 不存在，回退到当前世界出生点\n");
            sb.append("core_reloaded_standalone=Core 已重载（独立模式，API 按需调用）\n");
            sb.append("core_close_mojang_session_failed=关闭 Mojang 会话服务出错: {0}\n");
            sb.append("core_close_mojang_api_failed=关闭 Mojang API 服务出错: {0}\n");
            sb.append("core_disconnect_db_failed=断开数据库连接出错: {0}\n\n");

            sb.append("# API 相关\n");
            sb.append("api_official_available=[API] Mojang 官方 API 可用性：{0} (HTTP {1})\n");
            sb.append("api_official_unavailable=[API] Mojang 官方 API 不可达：{0}\n");
            sb.append("api_fallback_available=[API] 备用 API #{0}：{1} (HTTP {2})\n");
            sb.append("api_fallback_unavailable=[API] 备用 API #{0} 不可达：{1}\n");
            sb.append("api_all_down=[API] 所有 Mojang API 均不可达！正版验证将被拒绝。\n");
            sb.append("api_official_cooldown=[API] Mojang 官方 API 冷却中（{0}s），使用备用 API\n");
            sb.append("api_rate_limit_reached=[API] Mojang API 已达速率限制（并发请求过多）\n");
            sb.append("api_high_failure_rate=[API] Mojang API 高失败率（{0}%），切换到下一个可用 API...\n");
            sb.append("api_recovered=[API] Mojang API 已恢复 - 现在使用 {0}\n");
            sb.append("api_probe_start=[API] 宕机恢复探测开始（下一次探测窗口 +{0}s）\n");
            sb.append("api_parse_failed=[API] Mojang API 响应解析失败: {0} body={1}\n");
            sb.append("api_fast_fail_downtime=[API] 宕机快速失败（距下次恢复探测 {0}s），跳过 API 调用\n");
            sb.append("api_probe_in_progress=[API] 其他线程正在执行恢复探测，本请求快速失败\n");
            sb.append("api_official_check_complete=[API][OFFICIAL] 用户名 {0} 检查完成: {1} (耗时 {2}ms)\n");
            sb.append("api_fallback_check_complete=[API][FALLBACK#{0}] 用户名 {1} 检查完成: {2} (耗时 {3}ms)\n\n");

            sb.append("# 认证流程\n");
            sb.append("auth_database_unavailable=§cMultiAuth 数据库当前不可用，无法登录。\n");
            sb.append("auth_service_not_initialized=§c认证服务未初始化，请联系管理员。\n");
            sb.append("auth_concurrent_login_blocked=§c该账号正在验证中，请稍后再试。\n");
            sb.append("auth_server_busy=§c服务器认证繁忙，请稍后重试。\n");
            sb.append("auth_username_check_failed=[AUTH] 玩家 {0} 用户名检查失败：{1}\n");
            sb.append("auth_premium_detected=[AUTH] 玩家 {0} 用户名为正版（UUID={1}）- 需要 Mojang 验证\n");
            sb.append("auth_premium_in_authlist=[AUTH] 玩家 {0} 非正版但在验证列表中 - 需要验证\n");
            sb.append("auth_offline_allowed=[AUTH] 玩家 {0} 非正版 - 允许离线登录\n");
            sb.append("auth_downtime_flow=[AUTH] 玩家 {0} - Mojang API 不可达，执行宕机流程\n");
            sb.append("auth_player_allowed=[AUDIT] 玩家 {0} 放行 - {1}（UUID: {2}）\n");
            sb.append("auth_player_denied=[AUDIT] 玩家 {0} 拒绝 - {1}\n");
            sb.append("auth_handshake_failed=[AUDIT] 玩家 {0} 拒绝 - Velocity 握手失败（盗版客户端或无效会话，GameProfileRequest 未触发）\n");
            sb.append("auth_invalid_session=§c无效会话（可能是盗版客户端或正版未登录）\\n§7请确认已通过正版启动器登录 Minecraft 账号，\\n§7若仍失败请重启游戏及启动器后重试。\n");
            sb.append("auth_mojang_verify_passed=[AUDIT] 玩家 {0} Mojang 验证通过 - UUID: {1}\n");
            sb.append("auth_mojang_verify_failed_pirate=[AUDIT] 玩家 {0} Mojang 验证失败 - 盗版客户端\n");
            sb.append("auth_mojang_unreachable=[AUDIT] 玩家 {0} - Mojang 会话服务器不可达\n");
            sb.append("auth_downtime_deny=§6Mojang 会话服务器当前不可用。\\n§7为保障账号安全，宕机期间已暂停所有登录。\\n§7请稍后重试。\n");
            sb.append("auth_downtime_allow_offline=[AUTH] 玩家 {0} 在 Mojang 宕机期间以离线登录\n");
            sb.append("auth_uuid_mismatch=[AUDIT] 玩家 {0} UUID 不匹配！预期：{1}，实际：{2} - 可能的会话劫持！\n");
            sb.append("auth_executor_full=[AUTH] 验证线程池已满，拒绝玩家 {0}（服务器过载，请稍后重试）\n");
            sb.append("auth_plugin_disabled=[AUTH] 插件已禁用，拒绝玩家 {0} 的登录\n");
            sb.append("auth_verify_unexpected_error=[AUTH] 玩家 {0} 验证过程中发生未预期异常\n");
            sb.append("auth_listener_unregistered_close=[AUTH] PacketEvents 监听器已注销（proxy 切换），关闭玩家 {0} 连接\n");
            sb.append("auth_deny_client_disconnected=[AUTH] 玩家 {0} 验证失败被拒（客户端已断开：正版名离线登录/盗版客户端），Disconnect 包无法送达\n");
            sb.append("auth_deny_send_disconnect=[AUTH] 玩家 {0} 验证失败被拒，发送 Disconnect 包\n");
            sb.append("auth_no_packetevent_verify=[AUTH] 玩家 {0} 未经 PacketEvents 验证，拒绝登录\n");
            sb.append("auth_verify_failed_deny=[AUTH] 玩家 {0} 验证失败，拒绝登录\n");
            sb.append("auth_api_only_mode=[AUTH] PacketEvents 未安装，proxy=false 降级为 API-only（仅 LAYER-1 用户名检查，正版玩家无法完成加密验证）\n");
            sb.append("auth_no_login_summary=[AUTH] 玩家 {0} 无预登录摘要（reload 竞态），视为通过\n");
            sb.append("auth_allow_wins_deny_ignored=[AUTH] 玩家 {0} 已有其他连接验证通过（ALLOW），忽略来自其他连接的本次 DENY 结果\n");
            sb.append("auth_concurrency_full=[AUTH] 登录验证并发已满，拒绝玩家 {0}（请稍后重试）\n");
            sb.append("auth_api_rate_limited=[AUTH] Mojang API 请求过于频繁（{0}），请稍后再试\n");
            sb.append("# 离线玩家注册登录\n");
            sb.append("auth_register_prompt=§e请先注册账号！使用 §f/register <密码> <确认密码> §e注册\n");
            sb.append("auth_login_prompt=§e请先登录！使用 §f/login <密码> §e登录\n");
            sb.append("auth_register_success=§a注册成功！请使用 §f/login <密码> §a登录\n");
            sb.append("auth_register_failed=§c注册失败，请稍后重试或联系管理员。\n");
            sb.append("auth_register_already=§c您已注册，请使用 §f/login <密码> §c登录。\n");
            sb.append("auth_register_password_mismatch=§c两次输入的密码不一致。\n");
            sb.append("auth_register_password_too_short=§c密码长度不能少于 {0} 个字符。\n");
            sb.append("auth_register_password_too_long=§c密码长度不能超过 {0} 个字符。\n");
            sb.append("auth_register_password_empty=§c密码不能为空。\n");
            sb.append("auth_login_success=§a登录成功！\n");
            sb.append("auth_login_failed=§c登录失败：密码错误。\n");
            sb.append("auth_login_not_registered=§c您尚未注册，请先使用 §f/register §c注册。\n");
            sb.append("auth_login_processing=§e正在验证中，请稍候...\n");
            sb.append("auth_login_already=§a您已登录。\n");
            sb.append("auth_changepassword_success=§a密码修改成功！\n");
            sb.append("auth_changepassword_failed=§c密码修改失败，请稍后重试。\n");
            sb.append("auth_changepassword_wrong_old=§c旧密码错误。\n");
            sb.append("auth_not_logged_in=§c您尚未登录，请先使用 §f/login §c登录。\n");
            sb.append("auth_restricted=§c请先登录后再进行操作。\n");
            sb.append("auth_login_timeout=§c登录超时，您已被踢出服务器。\n");
            sb.append("auth_register_timeout=§c注册超时，您已被踢出服务器。\n");
            sb.append("auth_unregister_success=§a已删除玩家 {0} 的账号。\n");
            sb.append("auth_unregister_not_found=§c未找到玩家 {0} 的注册信息。\n");
            sb.append("auth_info_base=§6玩家 {0} 的信息：\\n§7UUID: §f{1}\\n§7状态: §f{2}\\n§7最后IP: §f{3}\\n§7地理位置: §f{4}\n");
            sb.append("auth_info_offline_extra=\\n§7类型: §b离线账号\\n§7注册时间: §f{0}\\n§7最后登录: §f{1}\n");
            sb.append("auth_info_premium_extra=\\n§7类型: §b正版\\n§7最后登录: §f{0}\\n§7第一次进入: §f{1}\n");
            sb.append("auth_info_never_logged_in=从未登录\n");
            sb.append("auth_info_not_registered=§c该玩家尚未注册。\n");
            sb.append("auth_info_status_online=在线\n");
            sb.append("auth_info_status_offline=离线\n");
            sb.append("auth_module_disabled=§c认证模块已禁用。\n");
            sb.append("auth_unregister_kick=§c您的账号已被管理员删除，请重新注册。\n");
            sb.append("auth_player_join_error=[AUTH] 处理玩家 {0} 的加入事件时发生未预期异常: {1}\n\n");

            sb.append("# 安全增强（SEC）\n");
            sb.append("sec_query_ip_stats_failed=[SEC] 查询 IP {0} 的统计信息失败: {1}\n");
            sb.append("sec_geo_check_skipped_log=[SEC] 玩家 {0} 的地理/历史查询失败，异地登录安全检测已跳过\n");
            sb.append("sec_increment_ip_account_failed=[SEC] 递增 IP {0} 的账号计数失败: {1}\n");
            sb.append("sec_decrement_ip_account_failed=[SEC] 递减 IP {0} 的账号计数失败: {1}\n\n");

            sb.append("deny_reason_db_unavailable=数据库不可用\n");
            sb.append("deny_reason_authmanager_not_initialized=AuthManager 未初始化\n");
            sb.append("deny_reason_concurrent_login=并发登录被阻止\n");
            sb.append("deny_reason_no_enc_response=无加密响应（盗版客户端？）\n");
            sb.append("allow_reason_no_record=无记录（首次经代理加入）\n");
            sb.append("allow_reason_offline_record=离线记录\n");
            sb.append("allow_reason_premium_keep_offline_uuid=转发的正版 UUID 但 use-mojang-uuid=false（保留离线记录）\n");
            sb.append("allow_reason_upgrade_offline_to_premium=升级离线记录为正版（UUID={0}）\n");
            sb.append("allow_reason_uuid_autocorrect_offline_to_premium=UUID 自动修正（离线→正版）：{0}\n");
            sb.append("allow_reason_uuid_autocorrect_premium_to_offline=UUID 自动修正（正版→离线）：{0}\n");
            sb.append("login_type_premium=正版\n");
            sb.append("login_type_premium_offline_uuid=正版(离线UUID)\n");
            sb.append("login_type_offline=离线\n");
            sb.append("login_type_premium_api_only=正版(API-only)\n");
            sb.append("login_type_offline_api_only=离线(API-only)\n");
            sb.append("api_status_recovered=已恢复\n");
            sb.append("api_source_official=官方\n");
            sb.append("api_source_fallback=备用 #{0}\n");
            sb.append("kick_rejected_message=[KICK] 玩家 {0} 被拒，消息：{1}\n");
            sb.append("kick_message_sent=[KICK] 玩家 {0} 收到消息：{1}\n");
            sb.append("login_success=[LOGIN] 玩家 {0} 登录成功 [{1}] UUID={2} IP={3}\n");
            sb.append("login_success_premium=[LOGIN] 玩家 {0} 登录成功 [正版] UUID={1} IP={2}\n");
            sb.append("login_success_offline=[LOGIN] 玩家 {0} 登录成功 [离线] UUID={1} IP={2}\n");
            sb.append("state_miss=[STATE-MISS] 玩家 {0} 无 PreLogin 决策缓存（插件 reload 竞态？），走 fail-closed 兜底：不推断身份、不重写 UUID、不补写数据库记录\n");
            sb.append("packet_fake_login_start_kick=§c登录验证成功，但登录包注入失败，请重新连接\n\n");

            sb.append("# 宕机 / API-only 审计日志\n");
            sb.append("auth_audit_hasjoined_unreachable=[AUDIT] 拒绝玩家 {0}：第二层 hasJoined 验证失败（Mojang 会话服务器不可达），正版验证无法完成\n");
            sb.append("auth_audit_downtime_no_record=[AUDIT] 拒绝玩家 {0}：第一层 API 全部不可达且无历史记录，无法判断正盗版身份，宕机期间禁止登录\n");
            sb.append("auth_audit_downtime_premium_history=[AUDIT] 拒绝玩家 {0}：第一层 API 全部不可达，账号有正版历史记录（UUID={1}），宕机期间禁止登录\n");
            sb.append("auth_audit_api_only_authlist=[AUDIT] 拒绝玩家 {0}：auth-list 强制验证在 API-only 模式下无法满足\n");
            sb.append("auth_audit_api_only_premium=[AUDIT] 拒绝玩家 {0}：API-only 降级模式下正版用户名无法完成加密验证\n\n");

            sb.append("# 配置相关\n");
            sb.append("config_loaded=配置已加载：proxy={0}, 数据库类型={1}\n");
            sb.append("config_reloaded=正在重新加载配置...\n");
            sb.append("config_reload_failed=重新加载配置失败：{0}\n");
            sb.append("config_online_mode_incompatible_warn=[MultiAuth] proxy=false 模式要求 server.properties 中 online-mode=false（Spigot Mojang 验证将无法工作）\n");
            sb.append("config_proxy_change_restart=§c代理模式已变更为 {0}，请重启服务端使配置完全生效！\n");
            sb.append("config_default_created=默认 config.toml 已创建\n");
            sb.append("config_load_failed=config.toml 加载失败，使用默认配置：{0}\n\n");

            sb.append("# 插件相关\n");
            sb.append("plugin_velocity_initialized=MultiAuth Velocity 插件已初始化\n");
            sb.append("plugin_velocity_shutdown=MultiAuth Velocity 插件已关闭\n");
            sb.append("plugin_proxy_switch_true=[MultiAuth] proxy 已切换为 true，PacketEvents 拦截已注销（验证由 Velocity 完成）\n");
            sb.append("plugin_proxy_switch_false=[MultiAuth] proxy 已切换为 false，PacketEvents 拦截已启用\n");
            sb.append("plugin_api_only_warning=[MultiAuth] PacketEvents 未安装，proxy=false 降级为 API-only（仅 LAYER-1 用户名检查，正版玩家无法登录）\n");
            sb.append("plugin_packetevent_install_hint=[MultiAuth] 请安装 PacketEvents 插件以启用加密握手验证\n\n");

            sb.append("# 数据包 / 加密握手\n");
            sb.append("packet_no_verify_callback=[PACKET][LOGIN_START] 未设置验证回调，用户 {0} 将无法登录\n");
            sb.append("packet_login_start_parse_failed=[PACKET][LOGIN_START] 解析失败：{0}\n");
            sb.append("packet_enc_response_parse_failed=[PACKET][ENCRYPTION_RESPONSE] 解析失败：{0}\n");
            sb.append("packet_enc_request_wrapper_failed=[PACKET][ENCRYPTION_REQUEST] Wrapper 失败，回退到原始写入：{0}\n");
            sb.append("packet_fake_login_start_failed=[PACKET][FAKE_LOGIN_START] 发送失败：{0}\n");
            sb.append("packet_fake_login_start_fallback_failed=[PACKET][FAKE_LOGIN_START] 回退也失败，踢出玩家 {0}：{1}\n");
            sb.append("packet_disconnect_send_failed=[PACKET][DISCONNECT] 发送失败，直接关闭 channel：{0}\n");
            sb.append("verify_no_packetevent=[VERIFY][{0}] PacketEvents 不可用，无法完成加密握手验证，拒绝\n");
            sb.append("verify_handshake_timeout=[VERIFY][{0}] 加密握手超时（5s 内未收到 EncryptionResponse，疑似盗版客户端）\n");
            sb.append("verify_enc_response_parse_failed=[VERIFY][{0}] EncryptionResponse 解析失败（客户端发送了非法加密响应）：{1}\n");
            sb.append("verify_enc_response_interrupted=[VERIFY][{0}] 等待 EncryptionResponse 被中断\n");
            sb.append("verify_decrypt_failed=[VERIFY][{0}] 解密 sharedSecret 失败（verifyToken 不匹配）：{1}\n");
            sb.append("verify_aes_anchor_missing=[VERIFY][{0}] 启用 AES 加密失败（找不到 pipeline 锚点），拒绝登录：未加密状态下发送 LoginSuccess 会导致客户端断开\n");
            sb.append("verify_hasjoined_failed=[VERIFY][{0}] hasJoined 失败：{1}\n");
            sb.append("verify_inbound_anchor_missing=[VERIFY] 找不到入站锚点 handler（splitter/decompress/decoder），AES 加密启用失败\n");
            sb.append("verify_outbound_anchor_missing=[VERIFY] 找不到出站锚点 handler（prepender/compress/encoder），AES 加密启用失败\n");
            sb.append("verify_aes_enable_failed=[VERIFY] Netty AES 加密启用失败：{0}\n");
            sb.append("verify_spoofed_connection_missing=[VERIFY] 无法设置 spoofedUUID：Connection 未找到\n");
            sb.append("verify_spoofed_failed=[VERIFY] 设置 spoofedUUID 失败：{0}\n");
            sb.append("verify_packet_handler_missing=[VERIFY] packet_handler 未找到\n");
            sb.append("verify_connection_failed=[VERIFY] 获取 Connection 失败：{0}\n\n");

            sb.append("# 命令相关\n");
            sb.append("cmd_help=MultiAuth 命令：\\n  /multiauth reload  - 重载配置\\n  /multiauth status  - 查看插件状态\\n  /multiauth backup  - 强制备份数据库\\n  /multiauth migrate <type> - 迁移数据库 (sqlite|mysql)\\n  /multiauth info <玩家> - 查看离线玩家账号信息\\n  /multiauth unregister <玩家> - 删除离线玩家账号\n");
            sb.append("cmd_no_permission=§c你没有权限执行此命令。\n");
            sb.append("cmd_status=MultiAuth 状态：\\n  版本: {0}\\n  数据库: {1}\\n  模式: {2}\\n  Mojang API: {3}\\n  总历史玩家数: {4}\\n  正版玩家数: {5}\n");
            sb.append("cmd_reload_success=§a配置重新加载成功\n");
            sb.append("cmd_migrate_usage=§c用法：/multiauth migrate <sqlite|mysql>\n");
            sb.append("cmd_migrate_success=§a迁移完成：{0} 条记录已迁移到 {1}\n");
            sb.append("cmd_migrate_failed=§c迁移失败：{0}\n");
            sb.append("cmd_migrate_invalid_type=§c无效的数据库类型。请使用：sqlite, mysql\n");
            sb.append("cmd_backup_success=§a数据库备份已成功创建\n");
            sb.append("cmd_backup_failed=§c数据库备份失败，请查看控制台了解详情\n");
            sb.append("cmd_plugin_info=§6MultiAuth v{0} - 玩家认证插件\n");
            sb.append("cmd_info_usage=§e用法：/multiauth info <玩家>\n");
            sb.append("cmd_info_self_only=§c您只能查询自己的信息。\n");
            sb.append("cmd_unregister_usage=§e用法：/multiauth unregister <玩家>\n");
            sb.append("cmd_changepassword_usage=§e用法：/changepassword <旧密码> <新密码>\n");
            sb.append("cmd_status_player_title=§6===== 认证状态 - {0} =====\n");
            sb.append("cmd_migrate_same_type=目标类型与当前相同，无需迁移\n");
            sb.append("cmd_check_console=请查看控制台了解详情\n");
            sb.append("cmd_mode_proxy=代理模式\n");
            sb.append("cmd_mode_direct=直连模式\n");
            sb.append("db_status_healthy=健康\n");
            sb.append("db_status_unhealthy=异常\n");
            sb.append("api_status_normal=正常\n");
            sb.append("api_status_down=宕机\n");
            sb.append("api_status_disabled=未启用\n");
            sb.append("api_status_unknown=未知\n");
            sb.append("cmd_core_not_initialized=§cCore 未初始化，无法执行此命令。\n");
            sb.append("cmd_migrate_in_progress=§7正在迁移数据到 {0} ...\n");
            sb.append("cmd_backup_in_progress=§7正在创建数据库备份...\n");
            sb.append("cmd_status_title=§6=== MultiAuth 状态 ===\n");
            sb.append("cmd_status_db_type=§7数据库类型: §f{0}\n");
            sb.append("cmd_status_db_status=§7数据库状态: {0}\n");
            sb.append("cmd_status_use_mojang_uuid=§7使用 Mojang UUID: §f{0}\n");
            sb.append("cmd_status_auth_list=§7认证列表: §f{0}\n");
            sb.append("cmd_status_fallback_api=§7备用 API: §f{0}\n");
            sb.append("cmd_status_fallback_not_configured=(未配置)\n\n");

            sb.append("# 会话相关\n");
            sb.append("session_start=[SESSION] 玩家 {0} 连接中 - 开始 {1} 验证\n");
            sb.append("session_complete=[SESSION] 玩家 {0} 认证完成 - {1}\n");
            sb.append("session_disconnect=[SESSION] 玩家 {0} 断开连接 - 清理认证状态\n");
            sb.append("session_join_notify=[SESSION] 玩家 {0} 加入服务器（UUID: {1}，正版: {2}）\n");
            sb.append("session_hijack_warning=§c[安全警告] {0} 的 UUID 不匹配！怀疑会话劫持！\n");
            sb.append("session_status_notify=§e您当前为{0}登录状态\\n§7UUID: {1}\n");
            sb.append("session_status_log=[SESSION] 玩家 {0} 登录状态：{1}（UUID: {2}）\n");
            sb.append("session_sync_enabled=[MultiAuth] 跨服会话同步已启用（通道: {0}）\n");
            sb.append("session_sync_receiver_registered=[MultiAuth] 跨服会话同步接收器已注册（通道: {0}）\n");
            sb.append("session_sync_login=[AUTH] 玩家 {0} 通过 Velocity 跨服会话同步登录（IP: {1}, 正版: {2}）\n");
            sb.append("session_sync_logout=[AUTH] 玩家 {0} 通过 Velocity 跨服会话同步登出\n");
            sb.append("session_sync_parse_error=[AUTH] 解析跨服会话同步消息失败: {0}\n");
            sb.append("session_sync_reject_client=[AUTH] 拒绝客户端伪造的跨服会话同步消息（仅接受 Velocity 代理消息）\n");
            sb.append("session_sync_ttl_removed=[SESSION] 清理过期会话记录（TTL）: {0} (UUID: {1})\n");
            sb.append("session_sync_secret_missing=[AUTH] Velocity 端未配置 session-sync-secret，跨服会话同步消息未做身份认证。请在 Velocity 与 Spigot 配置中设置相同的密钥。\n");
            sb.append("session_sync_bad_signature=[AUTH] 拒绝玩家 {0}（UUID: {1}）的跨服会话同步消息：签名无效\n");
            sb.append("sec_session_resume_check_failed=[SEC] 检查 {0} 的会话恢复安全检查失败: {1}\n");
            sb.append("config_loaded_debug=[Config] 已加载: proxy={0}, useMojangUuid={1}, debug={2}\n");
            sb.append("config_reloading=[Config] 正在重载配置...\n");
            sb.append("packetevents_loaded=[MultiAuth] PacketEvents 已加载，加密握手模式已启用\n");
            sb.append("packetevents_not_installed=[MultiAuth] PacketEvents 未安装，proxy=false 模式降级为 API-only（仅第一层用户名检查，正版玩家无法登录）\n");
            sb.append("packetevents_install_required=[MultiAuth] 请安装 PacketEvents 插件以启用加密握手验证\n");
            sb.append("auth_module_enabled=[MultiAuth] 离线玩家注册登录模块已启用（含安全增强）\n");
            sb.append("session_clean_failed=清理过期会话失败: {0}\n");
            sb.append("db_save_failed=保存玩家记录失败: {0}\n");
            sb.append("auth_account_unregistered_log=[AUTH] 账号已注销: {0}\n");
            sb.append("packet_listener_registered=[PACKET] PacketEvents 监听器已注册（方案 A：LOGIN_START 拦截 + 假包）\n");
            sb.append("packet_login_start_verified=[PACKET][LOGIN_START] 已验证用户 {0}，放行假包\n");
            sb.append("packet_login_start_username=[PACKET][LOGIN_START] 用户名={0}，不在 verifiedChannels 中，处理中\n");
            sb.append("packet_enc_response_received=[PACKET][ENCRYPTION_RESPONSE] 收到加密响应，用户={0}\n");
            sb.append("packet_enc_response_late_denied=[PACKET][ENCRYPTION_RESPONSE] 已拒绝连接的迟到响应，已取消（该用户已被拒绝登录）\n");
            sb.append("packet_enc_request_sent=[PACKET][ENCRYPTION_REQUEST] 已发送 (PacketEvents Wrapper)\n");
            sb.append("packet_verify_channel_closed=[AUTH] 玩家 {0} 验证完成时 channel 已关闭，跳过缓存验证结果\n");
            sb.append("packet_fake_login_start_sent=[PACKET][FAKE_LOGIN_START] 已发送假包 username={0} uuid={1}\n");
            sb.append("packet_disconnect_channel_closed=[PACKET][DISCONNECT] Channel 已断开（客户端自行离开），跳过 Disconnect 包\n");
            sb.append("packet_disconnect_already_sent=[PACKET][DISCONNECT] 已发送过 Disconnect 包，跳过重复发送\n");
            sb.append("packet_disconnect_sent=[PACKET][DISCONNECT] 已发送踢出包: {0}\n");
            sb.append("auth_player_verified_debug=[AUTH] 玩家 {0} 已通过验证，允许登录（UUID={1}）\n");
            sb.append("auth_no_login_summary_debug=[AUTH] 玩家 {0} 无预登录摘要（reload 竞态），按 UUID 判断身份\n");
            sb.append("verify_handshake_start=[VERIFY][{0}] 开始加密握手（PacketEvents + NMS）\n");
            sb.append("verify_enc_request_sent_waiting=[VERIFY][{0}] EncryptionRequest 已发送，等待响应（5s 超时）...\n");
            sb.append("verify_enc_response_received_debug=[VERIFY][{0}] 收到 EncryptionResponse，开始验证...\n");
            sb.append("verify_hasjoined_passed=[VERIFY][{0}] hasJoined 验证通过，UUID={1}\n");
            sb.append("verify_skip_spoofed_uuid=[VERIFY][{0}] use-mojang-uuid=false，跳过 spoofedUUID（保留离线 UUID）\n");
            sb.append("verify_aes_enabled=[VERIFY] AES 加密已启用（Netty Handler，锚点: {0} / {1}）\n");
            sb.append("verify_spoofed_uuid_set=[VERIFY] spoofedUUID 已设置: {0}\n");
            sb.append("multiverse_load_world_failed=[MultiAuth] Multiverse loadWorld 失败: '{0}'\n");
            sb.append("auth_proxy_skip_spigot=[AUTH] proxy=true，跳过 Spigot 端验证（由 Velocity 代理完成）\n");
            sb.append("auth_player_verified_send_fake=[AUTH] 玩家 {0} 验证通过，发送假 LOGIN_START 包（UUID={1}）\n");
            sb.append("msg_status_notify_sent=[MSG] 玩家 {0} 收到状态通知: {1}\n");
            sb.append("packet_fake_login_start_fallback_sent=[PACKET][FAKE_LOGIN_START] 已发送假包（receivePacket 回退）\n");
            sb.append("packet_enc_request_raw_sent=[PACKET][ENCRYPTION_REQUEST] 已发送（原始二进制）\n");
            sb.append("velocity_config_debug=配置已加载: use-mojang-uuid={0}, auth-list={1}, db-type={2}\n");
            sb.append("prelogin_other_plugin_denied=[PRELOGIN] 其他插件已拒绝玩家 {0}，本插件跳过验证\n");
            sb.append("prelogin_verify_exception=[PRELOGIN] 玩家 {0} 验证异常: {1}\n");
            sb.append("login_premium_decision=[LOGIN] 玩家 {0} 正版决策，等待 hasJoined（IP={1}）\n");
            sb.append("login_offline_decision=[LOGIN] 玩家 {0} 离线决策（IP={1}）\n");
            sb.append("state_cleanup_removed=[STATE-CLEANUP][{0}] 3 秒后仍未通过 hasJoined 且连接已断开，清理残留状态\n");
            sb.append("rewrite_premium_uuid_offline=use-mojang-uuid=false: 已重写正版 profile UUID -> {0}\n");
            sb.append("disconnect_before_hasjoined=[DISCONNECT] 玩家 {0} 在 hasJoined 通过前断开（可能是盗版客户端验证失败，也可能是玩家主动断开）\n\n");

            sb.append("# 通用\n");
            sb.append("generic_permission_denied=§c你没有权限使用此命令。\n");
            sb.append("generic_player_only=§c此命令只能由玩家使用。\n");
            sb.append("generic_player_not_found=§c未找到玩家：{0}\n");
            sb.append("generic_unknown=未知\n");
            sb.append("update_check_enabled_log=[Update] 更新检查已启用（仓库: {0}, 间隔: {1}小时）\n");
            sb.append("update_available_log=[Update] 发现新版本: {0}（当前: {1}）| {2}\n");
            sb.append("update_up_to_date_log=[Update] 当前已是最新版本 {0}\n");
            sb.append("update_check_failed_log=[Update] 无法检测更新：{0}，请自行检查\n");
            sb.append("update_notify_player=§e[MultiAuth] §a发现新版本 §f{0} §a(当前: {1}) §7§o{2}\n");
            sb.append("update_status_current=§7当前版本: §f{0}\n");
            sb.append("update_status_latest=§7最新版本: §f{0}\n");
        } else {
            // en_gb
            sb.append("# Database\n");
            sb.append("db_init_failed=[DB] Database initialization failed! All logins will be rejected.\n");
            sb.append("db_connected=[DB] Database connected: {0}\n");
            sb.append("db_ping_failed=[DB] Database ping failed after connection!\n");
            sb.append("db_heartbeat_ping_failed=[DB] Database heartbeat: ping failed, attempting reconnect...\n");
            sb.append("db_heartbeat_not_connected=[DB] Database heartbeat: not connected, attempting reconnect...\n");
            sb.append("db_reconnected=[DB] Database reconnected successfully\n");
            sb.append("db_reconnect_failed=[DB] Database reconnect failed: {0}\n");
            sb.append("db_heartbeat_error=[DB] Database heartbeat error: {0}\n");
            sb.append("db_backup_created=[DB] Database backup created: {0}\n");
            sb.append("db_backup_failed=[DB] Database backup failed: {0}\n");
            sb.append("db_backup_deleted_old=[DB] Deleted old backup: {0}\n");
            sb.append("db_migration_complete=[DB] Migration complete: {0} records migrated to {1}\n");
            sb.append("db_migration_failed=[DB] Migration failed: {0}\n");
            sb.append("db_connection_failed=[DB] Database connection failed: {0}\n");
            sb.append("db_rebuild_connection=[DB] Database config changed, rebuilding connection\n");
            sb.append("db_backup_clean_failed=[DB] Failed to clean old backups: {0}\n");
            sb.append("db_backup_skip_invalid_row=[DB] Skipped invalid row during backup: username={0} uuid={1}\n");
            sb.append("db_close_failed=[DB] Failed to close database connection\n");
            sb.append("db_state_check_failed=[DB] Database connection state check failed\n");
            sb.append("db_ping_exception=[DB] Database ping failed\n");
            sb.append("db_get_player_failed=[DB] Failed to get player {0}\n");
            sb.append("db_save_player_failed=[DB] Failed to save player {0}\n");
            sb.append("db_save_player_safe_failed=[DB] Failed to save player (safe) {0}\n");
            sb.append("db_exists_failed=[DB] Failed to check existence for player {0}\n");
            sb.append("db_count_failed=[DB] Failed to count records\n");
            sb.append("db_count_premium_failed=[DB] Failed to count premium records\n");
            sb.append("db_migration_exception=[DB] Database migration failed\n");
            sb.append("db_update_location_failed=[DB] Failed to update player location for {0}\n");
            sb.append("db_create_auth_table_failed=[DB] Failed to create auth table\n");
            sb.append("db_get_auth_account_failed=[DB] Failed to get auth account for {0}\n");
            sb.append("db_save_auth_account_failed=[DB] Failed to save auth account for {0}\n");
            sb.append("db_update_auth_password_failed=[DB] Failed to update auth password for {0}\n");
            sb.append("db_update_auth_login_failed=[DB] Failed to update auth login for {0}\n");
            sb.append("db_delete_auth_account_failed=[DB] Failed to delete auth account for {0}\n");
            sb.append("db_auth_account_exists_failed=[DB] Failed to check auth account exists for {0}\n");
            sb.append("db_create_login_history_table_failed=[DB] Failed to create login history table\n");
            sb.append("db_record_login_history_failed=[DB] Failed to record login history for {0}\n");
            sb.append("db_get_login_history_failed=[DB] Failed to get recent login history for {0}\n");
            sb.append("db_trim_login_history_failed=[DB] Failed to trim login history for {0}\n");
            sb.append("db_create_ip_stats_table_failed=[DB] Failed to create ip stats table\n");
            sb.append("db_get_ip_stats_failed=[DB] Failed to get ip stats for {0}\n");
            sb.append("db_increment_ip_account_failed=[DB] Failed to increment ip account count for {0}\n");
            sb.append("db_decrement_ip_account_failed=[DB] Failed to decrement ip account count for {0}\n");
            sb.append("db_create_index_failed=[DB] Failed to create index: {0}\n");
            sb.append("db_parse_uuid_failed=[DB] Failed to parse UUID for player {0}: {1}, skipped\n");
            sb.append("core_init_proxy=Core initialized in PROXY mode (UUID verification via shared DB)\n");
            sb.append("core_init_standalone=Core initialized successfully (premium verification by this plugin)\n");
            sb.append("core_shutdown_complete=Core shutdown complete\n");
            sb.append("core_proxy_mode_debug=Running in PROXY mode (via Velocity forwarding) - Mojang API services disabled\n");
            sb.append("core_api_init_debug=Mojang API initialized (on-demand: official first, fallback on failure)\n");
            sb.append("core_heartbeat_scheduled=Database heartbeat scheduled every {0}s\n");
            sb.append("core_backup_scheduled=Database backup scheduled every {0}h\n");
            sb.append("core_reloaded_proxy=Core reloaded in PROXY mode (Mojang API disabled)\n");
            sb.append("auth_create_auth_table_failed_log=[AUTH] Failed to create auth table: {0}\n");
            sb.append("auth_create_security_tables_failed_log=[AUTH] Failed to create security tables: {0}\n");
            sb.append("sec_registration_blocked_log=[SEC] Registration blocked for {0}: IP {1} reached account limit ({2})\n");
            sb.append("auth_hash_submission_failed_log=[AUTH] Password hash submission failed for {0}: {1}\n");
            sb.append("auth_register_success_log=[AUTH] Player {0} registered successfully (IP={1})\n");
            sb.append("auth_save_account_failed_log=[AUTH] Failed to save auth account for {0}: {1}\n");
            sb.append("auth_hash_failed_log=[AUTH] Password hash failed for {0}: {1}\n");
            sb.append("auth_register_failed_log=[AUTH] Register failed for {0}: {1}\n");
            sb.append("auth_geo_history_query_failed_log=[AUTH] Geo/history query failed for {0}: {1}\n");
            sb.append("sec_ip_changed_log=[SEC] Player {0} IP changed: {1} -> {2}\n");
            sb.append("auth_login_success_log=[AUTH] Player {0} logged in successfully (IP={1})\n");
            sb.append("sec_too_many_failures_kick_log=[SEC] Player {0} kicked for too many failed attempts (IP={1})\n");
            sb.append("auth_login_wrong_password_log=[AUTH] Player {0} login failed: wrong password (IP={1})\n");
            sb.append("auth_login_update_failed_log=[AUTH] Failed to update login for {0}: {1}\n");
            sb.append("auth_password_verify_failed_log=[AUTH] Password verify failed for {0}: {1}\n");
            sb.append("auth_login_failed_log=[AUTH] Login failed for {0}: {1}\n");
            sb.append("sec_geo_kick_log=[SEC] Player {0} {1} login detected, will be kicked\n");
            sb.append("sec_geo_warn_log=[SEC] Player {0} {1} login warning sent\n");
            sb.append("sec_session_resume_kick_log=[SEC] Player {0} {1} login detected during session resume, will be kicked\n");
            sb.append("sec_session_resume_require_login_log=[SEC] Player {0} {1} login detected during session resume, requiring fresh login\n");
            sb.append("sec_session_resume_warn_log=[SEC] Player {0} {1} login warning sent during session resume\n");
            sb.append("auth_change_password_success_log=[AUTH] Player {0} changed password successfully\n");
            sb.append("auth_password_update_failed_log=[AUTH] Failed to update password for {0}: {1}\n");
            sb.append("auth_change_password_failed_log=[AUTH] Change password failed for {0}: {1}\n");
            sb.append("auth_unregister_success_log=[AUTH] Account unregistered: {0}\n");
            sb.append("auth_unregister_failed_log=[AUTH] Failed to unregister {0}: {1}\n");
            sb.append("geo_v4v6_disabled_warn=[GEO] Both v4 and v6 query are disabled, geo service disabled\n");
            sb.append("geo_init_success=[GEO] ip2region service initialized successfully\n");
            sb.append("geo_init_failed=[GEO] Failed to initialize ip2region: {0}\n");
            sb.append("geo_xdb_missing_download=[GEO] xdb file(s) missing, starting async download...\n");
            sb.append("geo_init_success_after_download=[GEO] ip2region service initialized successfully after download\n");
            sb.append("geo_init_failed_after_download=[GEO] Failed to initialize ip2region after download: {0}\n");
            sb.append("geo_download_failed_disabled=[GEO] Failed to download xdb files, geo service remains disabled\n");
            sb.append("geo_xdb_missing_no_download=[GEO] xdb file(s) missing and auto-download disabled, geo service disabled\n");
            sb.append("geo_partial_missing_warn=[GEO] Missing xdb file(s): {0}; initializing with available parts\n");
            sb.append("geo_ipv6_skipped=[GEO] Player connected via IPv6 but v6 query is disabled, skipping\n");
            sb.append("geo_ipv4_skipped=[GEO] Player connected via IPv4 but v4 query is disabled, skipping\n");
            sb.append("geo_query_failed=[GEO] Failed to query IP {0}: {1}\n");
            sb.append("geo_close_failed=[GEO] Failed to close ip2region: {0}\n");
            sb.append("geo_downloaded_file=[GEO] Downloaded xdb file: {0}\n");
            sb.append("geo_download_attempt_failed=[GEO] Download attempt {0}/{1} failed for {2}: {3}\n");
            sb.append("history_record_failed=[HISTORY] Failed to record login history for {0}: {1}\n");
            sb.append("history_get_last_failed=[HISTORY] Failed to get last login for {0}: {1}\n");
            sb.append("history_get_failed=[HISTORY] Failed to get login history for {0}: {1}\n");
            sb.append("auth_invalid_password_hash_format=[AUTH] Invalid password hash format: {0}\n");
            sb.append("auth_password_verify_error=[AUTH] Password verification error: {0}\n");
            sb.append("mojang_malformed_hasjoined_warn=[MOJANG] Malformed response from hasJoined: {0}\n");
            sb.append("sec_services_reloaded_log=[MultiAuth] Security services reloaded with new config\n");
            sb.append("auth_changepassword_command_failed_log=[AUTH] Change password command failed for {0}: {1}\n");
            sb.append("auth_login_command_failed_log=[AUTH] Login command failed for {0}: {1}\n");
            sb.append("auth_register_command_failed_log=[AUTH] Register command failed for {0}: {1}\n");
            sb.append("multiverse_reflection_failed_log=[MultiAuth] Multiverse loadWorld reflection failed for '{0}': {1}\n");
            sb.append("core_cleanup_error=Cleanup error: {0}\n");
            sb.append("db_close_old_connection_failed=Failed to close old connection: {0}\n");
            sb.append("db_close_data_source_failed=Failed to close data source: {0}\n");
            sb.append("db_column_exists=Column already exists or migration not needed: {0}\n");
            sb.append("db_index_exists=Index already exists: {0}\n");
            sb.append("sec_join_online_limit_kick_log=[SEC] Player {0} kicked: IP {1} reached online limit ({2})\n");
            sb.append("auth_save_location_failed_log=[AUTH] Failed to save location for {0}: {1}\n");
            sb.append("auth_load_location_failed_log=[AUTH] Failed to load last location for {0}: {1}\n");
            sb.append("spawn_world_missing_log=[MultiAuth] Spawn-point world '{0}' not found, falling back to current world spawn\n");
            sb.append("core_reloaded_standalone=Core reloaded (standalone mode, API on-demand)\n");
            sb.append("core_close_mojang_session_failed=Error closing mojang session service: {0}\n");
            sb.append("core_close_mojang_api_failed=Error closing mojang api service: {0}\n");
            sb.append("core_disconnect_db_failed=Error disconnecting database: {0}\n\n");

            sb.append("# API\n");
            sb.append("api_official_available=[API] Mojang official API: {0} (HTTP {1})\n");
            sb.append("api_official_unavailable=[API] Mojang official API unreachable: {0}\n");
            sb.append("api_fallback_available=[API] Fallback API #{0}: {1} (HTTP {2})\n");
            sb.append("api_fallback_unavailable=[API] Fallback API #{0} unreachable: {1}\n");
            sb.append("api_all_down=[API] ALL Mojang APIs are unreachable! Premium verification will be rejected.\n");
            sb.append("api_official_cooldown=[API] Mojang official API in cooldown ({0}s) after repeated failures, using fallback\n");
            sb.append("api_rate_limit_reached=[API] Mojang API rate limit reached (too many concurrent requests)\n");
            sb.append("api_high_failure_rate=[API] Mojang API high failure rate ({0}%), failing over to next available API...\n");
            sb.append("api_recovered=[API] Mojang API recovered - now using {0}\n");
            sb.append("api_probe_start=[API] Downtime recovery probe started (next probe window +{0}s)\n");
            sb.append("api_parse_failed=[API] Failed to parse Mojang API response: {0} body={1}\n");
            sb.append("api_fast_fail_downtime=[API] Fast-fail during downtime ({0}s until next recovery probe), skipping API call\n");
            sb.append("api_probe_in_progress=[API] Recovery probe in progress by another thread, fast-failing\n");
            sb.append("api_official_check_complete=[API][OFFICIAL] Username {0} check complete: {1} (took {2}ms)\n");
            sb.append("api_fallback_check_complete=[API][FALLBACK#{0}] Username {1} check complete: {2} (took {3}ms)\n\n");

            sb.append("# Auth\n");
            sb.append("auth_database_unavailable=§cMultiAuth database is currently unavailable, login not possible.\n");
            sb.append("auth_service_not_initialized=§cAuthentication service not initialized, please contact admin.\n");
            sb.append("auth_concurrent_login_blocked=§cThis account is being verified, please try again later.\n");
            sb.append("auth_server_busy=§cAuthentication is busy, please try again later.\n");
            sb.append("auth_username_check_failed=[AUTH] Player {0} username check failed: {1}\n");
            sb.append("auth_premium_detected=[AUTH] Player {0} username is premium (UUID={1}) - requiring Mojang verification\n");
            sb.append("auth_premium_in_authlist=[AUTH] Player {0} not premium but in auth-list - requiring verification\n");
            sb.append("auth_offline_allowed=[AUTH] Player {0} not premium - allowing offline login\n");
            sb.append("auth_downtime_flow=[AUTH] Player {0} - Mojang API unreachable, applying downtime flow\n");
            sb.append("auth_player_allowed=[AUDIT] Player {0} ALLOWED - {1} (UUID: {2})\n");
            sb.append("auth_player_denied=[AUDIT] Player {0} DENIED - {1}\n");
            sb.append("auth_handshake_failed=[AUDIT] Player {0} DENIED - Velocity handshake failed (pirate client or invalid session, GameProfileRequest never reached)\n");
            sb.append("auth_invalid_session=§cInvalid session (pirate client or not logged into Microsoft account)\n§7Please ensure you are logged into your Minecraft account via the official launcher,\n§7if still failing please restart your game and launcher.\n");
            sb.append("auth_mojang_verify_passed=[AUDIT] Player {0} Mojang verification PASSED - UUID: {1}\n");
            sb.append("auth_mojang_verify_failed_pirate=[AUDIT] Player {0} Mojang verification FAILED - pirate client\n");
            sb.append("auth_mojang_unreachable=[AUDIT] Player {0} - Mojang session server unreachable\n");
            sb.append("auth_downtime_deny=§6Mojang authentication servers are currently unavailable.\n§7For account security, all logins have been temporarily paused during the downtime.\n§7Please try again later.\n");
            sb.append("auth_downtime_allow_offline=[AUTH] Player {0} allowed offline login during Mojang downtime (offline history)\n");
            sb.append("auth_uuid_mismatch=[AUDIT] Player {0} UUID mismatch! Expected: {1}, Got: {2} - possible session hijack!\n");
            sb.append("auth_executor_full=[AUTH] Verification thread pool full, rejecting player {0} (server overloaded, try again later)\n");
            sb.append("auth_plugin_disabled=[AUTH] Plugin disabled, rejecting player {0}\n");
            sb.append("auth_verify_unexpected_error=[AUTH] Unexpected exception during verification of player {0}\n");
            sb.append("auth_listener_unregistered_close=[AUTH] PacketEvents listener unregistered (proxy switch), closing connection of player {0}\n");
            sb.append("auth_deny_client_disconnected=[AUTH] Player {0} verification failed and rejected (client already disconnected: offline login with premium name / pirate client), Disconnect packet cannot be delivered\n");
            sb.append("auth_deny_send_disconnect=[AUTH] Player {0} verification failed and rejected, sending Disconnect packet\n");
            sb.append("auth_no_packetevent_verify=[AUTH] Player {0} not verified by PacketEvents, rejecting login\n");
            sb.append("auth_verify_failed_deny=[AUTH] Player {0} verification failed, rejecting login\n");
            sb.append("auth_api_only_mode=[AUTH] PacketEvents not installed, proxy=false degraded to API-only (LAYER-1 username check only, premium players cannot complete encrypted verification)\n");
            sb.append("auth_no_login_summary=[AUTH] Player {0} has no pre-login summary (reload race), treating as passed\n");
            sb.append("auth_allow_wins_deny_ignored=[AUTH] Player {0} already has another connection verified (ALLOW), ignoring this DENY result from another connection\n");
            sb.append("auth_concurrency_full=[AUTH] Login verification concurrency limit reached, rejecting player {0} (try again later)\n");
            sb.append("auth_api_rate_limited=[AUTH] Mojang API rate limit reached for {0}, please try again later\n");
            sb.append("# Offline player register/login\n");
            sb.append("auth_register_prompt=§ePlease register first! Use §f/register <password> <confirm> §eto register\n");
            sb.append("auth_login_prompt=§ePlease login first! Use §f/login <password> §eto login\n");
            sb.append("auth_register_success=§aRegistered successfully! Use §f/login <password> §ato login\n");
            sb.append("auth_register_failed=§cRegistration failed, please try again later or contact admin.\n");
            sb.append("auth_register_already=§cYou are already registered, use §f/login <password> §cto login.\n");
            sb.append("auth_register_password_mismatch=§cThe two passwords do not match.\n");
            sb.append("auth_register_password_too_short=§cPassword must be at least {0} characters long.\n");
            sb.append("auth_register_password_too_long=§cPassword must not exceed {0} characters.\n");
            sb.append("auth_register_password_empty=§cPassword cannot be empty.\n");
            sb.append("auth_login_success=§aLogin successful!\n");
            sb.append("auth_login_failed=§cLogin failed: wrong password.\n");
            sb.append("auth_login_not_registered=§cYou are not registered, use §f/register §cto register first.\n");
            sb.append("auth_login_processing=§eVerifying, please wait...\n");
            sb.append("auth_login_already=§aYou are already logged in.\n");
            sb.append("auth_changepassword_success=§aPassword changed successfully!\n");
            sb.append("auth_changepassword_failed=§cFailed to change password, please try again later.\n");
            sb.append("auth_changepassword_wrong_old=§cWrong old password.\n");
            sb.append("auth_not_logged_in=§cYou are not logged in, use §f/login §cto login first.\n");
            sb.append("auth_restricted=§cPlease login before performing any actions.\n");
            sb.append("auth_login_timeout=§cLogin timeout, you have been kicked from the server.\n");
            sb.append("auth_register_timeout=§cRegistration timeout, you have been kicked from the server.\n");
            sb.append("auth_unregister_success=§aAccount deleted for player {0}.\n");
            sb.append("auth_unregister_not_found=§cNo registration found for player {0}.\n");
            sb.append("auth_info_base=§6Player {0} info:\\n§7UUID: §f{1}\\n§7Status: §f{2}\\n§7Last IP: §f{3}\\n§7Geo: §f{4}\n");
            sb.append("auth_info_offline_extra=\\n§7Type: §bOffline\\n§7Registered: §f{0}\\n§7Last login: §f{1}\n");
            sb.append("auth_info_premium_extra=\\n§7Type: §bPremium\\n§7Last login: §f{0}\\n§7First join: §f{1}\n");
            sb.append("auth_info_never_logged_in=Never logged in\n");
            sb.append("auth_info_not_registered=§cThis player is not registered.\n");
            sb.append("auth_info_status_online=Online\n");
            sb.append("auth_info_status_offline=Offline\n");
            sb.append("auth_module_disabled=§cAuth module is disabled.\n");
            sb.append("auth_unregister_kick=§cYour account has been deleted by an admin. Please re-register.\n");
            sb.append("auth_player_join_error=[AUTH] Unexpected error while handling PlayerJoinEvent for {0}: {1}\n\n");

            sb.append("# Security (SEC)\n");
            sb.append("sec_query_ip_stats_failed=[SEC] Failed to query ip stats for {0}: {1}\n");
            sb.append("sec_geo_check_skipped_log=[SEC] Geo/history query failed for {0}, geo security checks skipped (fail-open)\n");
            sb.append("sec_increment_ip_account_failed=[SEC] Failed to increment ip account count for {0}: {1}\n");
            sb.append("sec_decrement_ip_account_failed=[SEC] Failed to decrement ip account count for {0}: {1}\n\n");

            sb.append("deny_reason_db_unavailable=database unavailable\n");
            sb.append("deny_reason_authmanager_not_initialized=AuthManager not initialized\n");
            sb.append("deny_reason_concurrent_login=concurrent login blocked\n");
            sb.append("deny_reason_no_enc_response=no encryption response (pirate client?)\n");
            sb.append("allow_reason_no_record=no record (first join via proxy)\n");
            sb.append("allow_reason_offline_record=offline record\n");
            sb.append("allow_reason_premium_keep_offline_uuid=premium UUID forwarded but use-mojang-uuid=false (keep offline record)\n");
            sb.append("allow_reason_upgrade_offline_to_premium=upgrading offline record to premium (UUID={0})\n");
            sb.append("allow_reason_uuid_autocorrect_offline_to_premium=UUID auto-correct (offline to premium): {0}\n");
            sb.append("allow_reason_uuid_autocorrect_premium_to_offline=UUID auto-correct (premium to offline): {0}\n");
            sb.append("login_type_premium=premium\n");
            sb.append("login_type_premium_offline_uuid=premium(offline UUID)\n");
            sb.append("login_type_offline=offline\n");
            sb.append("login_type_premium_api_only=premium(API-only)\n");
            sb.append("login_type_offline_api_only=offline(API-only)\n");
            sb.append("api_status_recovered=recovered\n");
            sb.append("api_source_official=official\n");
            sb.append("api_source_fallback=fallback #{0}\n");
            sb.append("kick_rejected_message=[KICK] Player {0} rejected, message: {1}\n");
            sb.append("kick_message_sent=[KICK] Player {0} received message: {1}\n");
            sb.append("login_success=[LOGIN] Player {0} login success [{1}] UUID={2} IP={3}\n");
            sb.append("login_success_premium=[LOGIN] Player {0} login success [premium] UUID={1} IP={2}\n");
            sb.append("login_success_offline=[LOGIN] Player {0} login success [offline] UUID={1} IP={2}\n");
            sb.append("state_miss=[STATE-MISS] Player {0} has no PreLogin decision cache (plugin reload race?), fail-closed fallback: no identity inference, no UUID rewrite, no DB record written\n");
            sb.append("packet_fake_login_start_kick=§cLogin verification succeeded, but login packet injection failed, please reconnect\n\n");

            sb.append("# Downtime / API-only audit logs\n");
            sb.append("auth_audit_hasjoined_unreachable=[AUDIT] Player {0} DENIED - hasJoined verification failed (Mojang session server unreachable), premium verification cannot complete\n");
            sb.append("auth_audit_downtime_no_record=[AUDIT] Player {0} DENIED - all LAYER-1 APIs unreachable with no history record, cannot determine premium status, login paused during downtime\n");
            sb.append("auth_audit_downtime_premium_history=[AUDIT] Player {0} DENIED - all LAYER-1 APIs unreachable and account has premium history (UUID={1}), login paused during downtime\n");
            sb.append("auth_audit_api_only_authlist=[AUDIT] Player {0} DENIED - auth-list forced verification cannot be satisfied in API-only mode\n");
            sb.append("auth_audit_api_only_premium=[AUDIT] Player {0} DENIED - premium username cannot complete encrypted verification in API-only mode\n\n");

            sb.append("# Config\n");
            sb.append("config_loaded=Config loaded: proxy={0}, db-type={1}\n");
            sb.append("config_reloaded=Reloading config...\n");
            sb.append("config_reload_failed=Failed to reload config: {0}\n");
            sb.append("config_online_mode_incompatible_warn=[MultiAuth] proxy=false requires online-mode=false in server.properties (Spigot Mojang verification will not work)\n");
            sb.append("config_proxy_change_restart=§cProxy mode changed to {0} - please restart the server for the change to fully take effect!\n");
            sb.append("config_default_created=Default config.toml created\n");
            sb.append("config_load_failed=Failed to load config.toml, using defaults: {0}\n\n");

            sb.append("# Plugin\n");
            sb.append("plugin_velocity_initialized=MultiAuth Velocity plugin initialized\n");
            sb.append("plugin_velocity_shutdown=MultiAuth Velocity plugin shutdown\n");
            sb.append("plugin_proxy_switch_true=[MultiAuth] proxy switched to true, PacketEvents interception unregistered (verification handled by Velocity)\n");
            sb.append("plugin_proxy_switch_false=[MultiAuth] proxy switched to false, PacketEvents interception enabled\n");
            sb.append("plugin_api_only_warning=[MultiAuth] PacketEvents not installed, proxy=false degraded to API-only (LAYER-1 username check only, premium players cannot login)\n");
            sb.append("plugin_packetevent_install_hint=[MultiAuth] Please install the PacketEvents plugin to enable encrypted handshake verification\n\n");

            sb.append("# Packet / Encryption handshake\n");
            sb.append("packet_no_verify_callback=[PACKET][LOGIN_START] No verification callback set, user {0} cannot login\n");
            sb.append("packet_login_start_parse_failed=[PACKET][LOGIN_START] Parse failed: {0}\n");
            sb.append("packet_enc_response_parse_failed=[PACKET][ENCRYPTION_RESPONSE] Parse failed: {0}\n");
            sb.append("packet_enc_request_wrapper_failed=[PACKET][ENCRYPTION_REQUEST] Wrapper failed, falling back to raw write: {0}\n");
            sb.append("packet_fake_login_start_failed=[PACKET][FAKE_LOGIN_START] Send failed: {0}\n");
            sb.append("packet_fake_login_start_fallback_failed=[PACKET][FAKE_LOGIN_START] Fallback also failed, kicking player {0}: {1}\n");
            sb.append("packet_disconnect_send_failed=[PACKET][DISCONNECT] Send failed, closing channel directly: {0}\n");
            sb.append("verify_no_packetevent=[VERIFY][{0}] PacketEvents unavailable, cannot complete encrypted handshake verification, rejecting\n");
            sb.append("verify_handshake_timeout=[VERIFY][{0}] Encryption handshake timeout (no EncryptionResponse within 5s, likely pirate client)\n");
            sb.append("verify_enc_response_parse_failed=[VERIFY][{0}] EncryptionResponse parse failed (client sent invalid encryption response): {1}\n");
            sb.append("verify_enc_response_interrupted=[VERIFY][{0}] Waiting for EncryptionResponse was interrupted\n");
            sb.append("verify_decrypt_failed=[VERIFY][{0}] sharedSecret decryption failed (verifyToken mismatch): {1}\n");
            sb.append("verify_aes_anchor_missing=[VERIFY][{0}] AES encryption enable failed (pipeline anchor not found), rejecting login: sending LoginSuccess unencrypted would disconnect the client\n");
            sb.append("verify_hasjoined_failed=[VERIFY][{0}] hasJoined failed: {1}\n");
            sb.append("verify_inbound_anchor_missing=[VERIFY] Inbound anchor handler not found (splitter/decompress/decoder), AES encryption enable failed\n");
            sb.append("verify_outbound_anchor_missing=[VERIFY] Outbound anchor handler not found (prepender/compress/encoder), AES encryption enable failed\n");
            sb.append("verify_aes_enable_failed=[VERIFY] Netty AES encryption enable failed: {0}\n");
            sb.append("verify_spoofed_connection_missing=[VERIFY] Cannot set spoofedUUID: Connection not found\n");
            sb.append("verify_spoofed_failed=[VERIFY] Setting spoofedUUID failed: {0}\n");
            sb.append("verify_packet_handler_missing=[VERIFY] packet_handler not found\n");
            sb.append("verify_connection_failed=[VERIFY] Failed to get Connection: {0}\n\n");

            sb.append("# Commands\n");
            sb.append("cmd_help=MultiAuth Commands:\\n  /multiauth reload  - Reload config\\n  /multiauth status  - Show plugin status\\n  /multiauth backup  - Force database backup\\n  /multiauth migrate <type> - Migrate database (sqlite|mysql)\\n  /multiauth info <player> - View offline player account info\\n  /multiauth unregister <player> - Delete offline player account\n");
            sb.append("cmd_no_permission=§cYou don't have permission to use this command.\n");
            sb.append("cmd_status=MultiAuth Status:\\n  Version: {0}\\n  Database: {1}\\n  Mode: {2}\\n  Mojang API: {3}\\n  Total Historic Players: {4}\\n  Premium Players: {5}\n");
            sb.append("cmd_reload_success=§aConfig reloaded successfully\n");
            sb.append("cmd_migrate_usage=§cUsage: /multiauth migrate <sqlite|mysql>\n");
            sb.append("cmd_migrate_success=§aMigration complete: {0} records migrated to {1}\n");
            sb.append("cmd_migrate_failed=§cMigration failed: {0}\n");
            sb.append("cmd_migrate_invalid_type=§cInvalid database type. Use: sqlite, mysql\n");
            sb.append("cmd_backup_success=§aDatabase backup created successfully\n");
            sb.append("cmd_backup_failed=§cDatabase backup failed. Check console for details.\n");
            sb.append("cmd_plugin_info=§6MultiAuth v{0} - Player authentication plugin\n");
            sb.append("cmd_info_usage=§eUsage: /multiauth info <player>\n");
            sb.append("cmd_info_self_only=§cYou can only query your own information.\n");
            sb.append("cmd_unregister_usage=§eUsage: /multiauth unregister <player>\n");
            sb.append("cmd_changepassword_usage=§eUsage: /changepassword <oldPassword> <newPassword>\n");
            sb.append("cmd_status_player_title=§6===== Auth Status - {0} =====\n");
            sb.append("cmd_migrate_same_type=Target type is the same as current, no migration needed\n");
            sb.append("cmd_check_console=check console for details\n");
            sb.append("cmd_mode_proxy=Proxy mode\n");
            sb.append("cmd_mode_direct=Direct\n");
            sb.append("db_status_healthy=Healthy\n");
            sb.append("db_status_unhealthy=Unhealthy\n");
            sb.append("api_status_normal=Normal\n");
            sb.append("api_status_down=Down\n");
            sb.append("api_status_disabled=Disabled\n");
            sb.append("api_status_unknown=Unknown\n");
            sb.append("cmd_core_not_initialized=§cCore not initialized, cannot execute this command.\n");
            sb.append("cmd_migrate_in_progress=§7Migrating data to {0} ...\n");
            sb.append("cmd_backup_in_progress=§7Creating database backup...\n");
            sb.append("cmd_status_title=§6=== MultiAuth Status ===\n");
            sb.append("cmd_status_db_type=§7Database type: §f{0}\n");
            sb.append("cmd_status_db_status=§7Database status: {0}\n");
            sb.append("cmd_status_use_mojang_uuid=§7Use Mojang UUID: §f{0}\n");
            sb.append("cmd_status_auth_list=§7Auth list: §f{0}\n");
            sb.append("cmd_status_fallback_api=§7Fallback API: §f{0}\n");
            sb.append("cmd_status_fallback_not_configured=(not configured)\n\n");

            sb.append("# Session\n");
            sb.append("session_start=[SESSION] Player {0} connecting - starting {1} verification\n");
            sb.append("session_complete=[SESSION] Player {0} authentication complete - {1}\n");
            sb.append("session_disconnect=[SESSION] Player {0} disconnected - cleaning up auth state\n");
            sb.append("session_join_notify=[SESSION] Player {0} joined the server (UUID: {1}, Premium: {2})\n");
            sb.append("session_hijack_warning=§c[SECURITY WARNING] UUID mismatch for {0}! Session hijack suspected!\n");
            sb.append("session_status_notify=§eYour current login status: {0}\\n§7UUID: {1}\n");
            sb.append("session_status_log=[SESSION] Player {0} login status: {1} (UUID: {2})\n");
            sb.append("session_sync_enabled=[MultiAuth] Cross-server session sync enabled (channel: {0})\n");
            sb.append("session_sync_receiver_registered=[MultiAuth] Cross-server session sync receiver registered (channel: {0})\n");
            sb.append("session_sync_login=[AUTH] Player {0} logged in via Velocity cross-server sync (IP: {1}, Premium: {2})\n");
            sb.append("session_sync_logout=[AUTH] Player {0} logged out via Velocity cross-server sync\n");
            sb.append("session_sync_parse_error=[AUTH] Failed to parse cross-server session sync message: {0}\n");
            sb.append("session_sync_reject_client=[AUTH] Blocked client-forged cross-server session sync message (forward to backend rejected)\n");
            sb.append("session_sync_ttl_removed=[SESSION] Removed expired session record (TTL): {0} (UUID: {1})\n");
            sb.append("session_sync_secret_missing=[AUTH] session-sync-secret is not configured on Velocity side; cross-server session sync messages are NOT authenticated. Set the same secret in Velocity and Spigot configs.\n");
            sb.append("session_sync_bad_signature=[AUTH] Rejected cross-server session sync message from player {0} (UUID: {1}): invalid signature\n");
            sb.append("sec_session_resume_check_failed=[SEC] Failed to check session resume security for {0}: {1}\n");
            sb.append("config_loaded_debug=[Config] Loaded: proxy={0}, useMojangUuid={1}, debug={2}\n");
            sb.append("config_reloading=[Config] Reloading config...\n");
            sb.append("packetevents_loaded=[MultiAuth] PacketEvents loaded, encryption handshake mode enabled\n");
            sb.append("packetevents_not_installed=[MultiAuth] PacketEvents not installed, proxy=false mode degraded to API-only (username check only, premium players cannot login)\n");
            sb.append("packetevents_install_required=[MultiAuth] Please install PacketEvents to enable encryption handshake verification\n");
            sb.append("auth_module_enabled=[MultiAuth] Offline player register/login module enabled (with security enhancements)\n");
            sb.append("session_clean_failed=Failed to clean expired sessions: {0}\n");
            sb.append("db_save_failed=Failed to save player record: {0}\n");
            sb.append("auth_account_unregistered_log=[AUTH] Account unregistered: {0}\n");
            sb.append("packet_listener_registered=[PACKET] PacketEvents listener registered (Plan A: LOGIN_START interception + fake packet)\n");
            sb.append("packet_login_start_verified=[PACKET][LOGIN_START] Verified user {0}, passing fake packet\n");
            sb.append("packet_login_start_username=[PACKET][LOGIN_START] username={0}, not in verifiedChannels, processing\n");
            sb.append("packet_enc_response_received=[PACKET][ENCRYPTION_RESPONSE] Received encryption response, user={0}\n");
            sb.append("packet_enc_response_late_denied=[PACKET][ENCRYPTION_RESPONSE] Late response from a denied channel, cancelled (user already rejected)\n");
            sb.append("packet_enc_request_sent=[PACKET][ENCRYPTION_REQUEST] Sent (PacketEvents Wrapper)\n");
            sb.append("packet_verify_channel_closed=[AUTH] Player {0} channel closed during verification, skipping cache\n");
            sb.append("packet_fake_login_start_sent=[PACKET][FAKE_LOGIN_START] Sent fake packet username={0} uuid={1}\n");
            sb.append("packet_disconnect_channel_closed=[PACKET][DISCONNECT] Channel already disconnected, skipping Disconnect packet\n");
            sb.append("packet_disconnect_already_sent=[PACKET][DISCONNECT] Disconnect packet already sent, skipping duplicate\n");
            sb.append("packet_disconnect_sent=[PACKET][DISCONNECT] Sent disconnect packet: {0}\n");
            sb.append("auth_player_verified_debug=[AUTH] Player {0} verified, allowing login (UUID={1})\n");
            sb.append("auth_no_login_summary_debug=[AUTH] Player {0} has no pre-login summary (reload race), determining by UUID\n");
            sb.append("verify_handshake_start=[VERIFY][{0}] Starting encrypted handshake (PacketEvents + NMS)\n");
            sb.append("verify_enc_request_sent_waiting=[VERIFY][{0}] EncryptionRequest sent, waiting for response (5s timeout)...\n");
            sb.append("verify_enc_response_received_debug=[VERIFY][{0}] EncryptionResponse received, starting verification...\n");
            sb.append("verify_hasjoined_passed=[VERIFY][{0}] hasJoined verification passed, UUID={1}\n");
            sb.append("verify_skip_spoofed_uuid=[VERIFY][{0}] use-mojang-uuid=false, skipping spoofedUUID (keeping offline UUID)\n");
            sb.append("verify_aes_enabled=[VERIFY] AES encryption enabled (Netty Handler, anchors: {0} / {1})\n");
            sb.append("verify_spoofed_uuid_set=[VERIFY] spoofedUUID set: {0}\n");
            sb.append("multiverse_load_world_failed=[MultiAuth] Multiverse loadWorld failed for '{0}'\n");
            sb.append("auth_proxy_skip_spigot=[AUTH] proxy=true, skipping Spigot-side verification (handled by Velocity proxy)\n");
            sb.append("auth_player_verified_send_fake=[AUTH] Player {0} verified, sending fake LOGIN_START packet (UUID={1})\n");
            sb.append("msg_status_notify_sent=[MSG] Player {0} received status notification: {1}\n");
            sb.append("packet_fake_login_start_fallback_sent=[PACKET][FAKE_LOGIN_START] Sent fake packet (receivePacket fallback)\n");
            sb.append("packet_enc_request_raw_sent=[PACKET][ENCRYPTION_REQUEST] Sent (raw binary)\n");
            sb.append("velocity_config_debug=Config loaded: use-mojang-uuid={0}, auth-list={1}, db-type={2}\n");
            sb.append("prelogin_other_plugin_denied=[PRELOGIN] Player {0} already denied by another plugin, skipping verification\n");
            sb.append("prelogin_verify_exception=[PRELOGIN] Player {0} verification exception: {1}\n");
            sb.append("login_premium_decision=[LOGIN] Player {0} premium decision, waiting for hasJoined (IP={1})\n");
            sb.append("login_offline_decision=[LOGIN] Player {0} offline decision (IP={1})\n");
            sb.append("state_cleanup_removed=[STATE-CLEANUP][{0}] 3s elapsed without hasJoined pass and connection disconnected, cleaning residual state\n");
            sb.append("rewrite_premium_uuid_offline=use-mojang-uuid=false: rewritten premium profile UUID -> {0}\n");
            sb.append("disconnect_before_hasjoined=[DISCONNECT] Player {0} disconnected before hasJoined passed (pirate client verification failure or player-initiated disconnect)\n\n");

            sb.append("# Generic\n");
            sb.append("generic_permission_denied=§cYou don't have permission to use this command.\n");
            sb.append("generic_player_only=§cThis command can only be used by players.\n");
            sb.append("generic_player_not_found=§cPlayer not found: {0}\n");
            sb.append("generic_unknown=unknown\n");
            sb.append("update_check_enabled_log=[Update] Update check enabled (repository: {0}, interval: {1}h)\n");
            sb.append("update_available_log=[Update] New version available: {0} (current: {1}) | {2}\n");
            sb.append("update_up_to_date_log=[Update] You are running the latest version {0}\n");
            sb.append("update_check_failed_log=[Update] Unable to check for updates: {0}, please check manually\n");
            sb.append("update_notify_player=§e[MultiAuth] §aNew version available: §f{0} §a(current: {1}) §7§o{2}\n");
            sb.append("update_status_current=§7Current version: §f{0}\n");
            sb.append("update_status_latest=§7Latest version: §f{0}\n");
        }

        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        LOGGER.info("Created default language file: " + target);
    }
}
