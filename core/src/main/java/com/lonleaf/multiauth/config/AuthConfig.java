package com.lonleaf.multiauth.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证插件通用配置数据类。
 */
public class AuthConfig {

    /** 是否使用 Mojang 正版 UUID，false 则使用离线 UUID */
    private boolean useMojangUuid = true;

    /** 认证列表：验证失败的玩家若在此列表中则踢出，否则以离线模式登录 */
    private Set<String> authList = new HashSet<>();

    /** Spigot 专用：是否通过 Velocity 代理验证
     *  true  = Velocity 端安装 MultiAuth 插件，Spigot 仅校验转发的 UUID
     *  false = Spigot 端自行执行 Mojang 验证流程（与 Velocity 端完全相同） */
    private boolean proxy = false;

    /** Spigot 专用：玩家进服后是否在消息栏显示登录状态（正版/离线 + UUID） */
    private boolean notifyPlayerStatus = true;

    // ==================== 语言 ====================

    /** 语言代码（如 zh_cn, en_gb），默认 en_gb */
    private String language = "en_gb";

    // ==================== Mojang API ====================

    /** 备用 API 地址列表（含 {username} 占位符），官方 API 宕机时依次尝试 */
    private List<String> fallbackApiUrls = new ArrayList<>();

    /** 每个用户名每秒最多向 Mojang API 发起的请求数（0=不限制），防止重复请求触发 Mojang 429 */
    private int mojangRequestLimit = 2;

    // ==================== 数据库 ====================

    /** 数据库类型：sqlite 或 mysql */
    private String databaseType = "sqlite";

    /** SQLite 文件名 */
    private String sqliteFile = "multiauth.db";

    /** MySQL 主机 */
    private String mysqlHost = "localhost";

    /** MySQL 端口 */
    private int mysqlPort = 3306;

    /** MySQL 数据库名 */
    private String mysqlDatabase = "multiauth";

    /** MySQL 用户名 */
    private String mysqlUsername = "root";

    /** MySQL 密码 */
    private String mysqlPassword = "";

    /** MySQL 表前缀 */
    private String mysqlTablePrefix = "multiauth_";

    /** MySQL 是否使用 SSL 连接 */
    private boolean mysqlUseSsl = false;

    /** 心跳间隔（秒） */
    private int heartbeatInterval = 60;

    // ==================== 备份 ====================

    /** 是否启用定时备份 */
    private boolean backupEnabled = true;

    /** 备份间隔（小时） */
    private int backupIntervalHours = 24;

    /** 备份目录 */
    private String backupDir = "backups";

    /** 最大备份数量 */
    private int backupMaxCount = 7;

    // ==================== 更新检查 ====================

    /** 是否启用更新检查 */
    private boolean updateCheckEnabled = true;

    /** 检查间隔（小时） */
    private int updateCheckIntervalHours = 24;

    // ==================== 离线玩家注册登录 ====================

    /** 是否启用离线玩家注册登录 */
    private boolean authEnabled = true;

    /** 密码最小长度 */
    private int authPasswordMin = 4;

    /** 密码最大长度 */
    private int authPasswordMax = 32;

    /** 未登录玩家是否强制冒险模式 */
    private boolean authForceAdventure = false;

    /** 未登录玩家是否固定位置（禁止移动） */
    private boolean authFreezePosition = true;

    /** 未登录玩家限制项开关 */
    private boolean authRestrictMove = true;
    private boolean authRestrictChat = true;
    private boolean authRestrictInteract = true;
    private boolean authRestrictDamage = true;
    private boolean authRestrictCommand = true;

    /** 未登录玩家允许执行的命令列表（不区分大小写） */
    private List<String> authAllowCommands = new java.util.ArrayList<>(java.util.List.of("register", "login", "reg", "r", "l"));

    /** 登录超时时间（秒），超时未登录则踢出，默认 600 秒（10 分钟） */
    private int authLoginTimeout = 600;

    /** 注册超时时间（秒），超时未注册则踢出，默认 180 秒（3 分钟） */
    private int authRegisterTimeout = 180;

    /** 未登录玩家进入服务器时是否传送到世界出生点 */
    private boolean authLoginSpawnPoint = false;

    /** 自定义出生点：世界名（留空则使用当前世界出生点，支持 Multiverse 世界） */
    private String authSpawnPointWorld = "";
    /** 自定义出生点坐标 */
    private double authSpawnPointX = 0.0;
    private double authSpawnPointY = 64.0;
    private double authSpawnPointZ = 0.0;
    private float authSpawnPointYaw = 0.0f;
    private float authSpawnPointPitch = 0.0f;

    /** 登录成功后是否回到上次下线地点 */
    private boolean authReturnLastLocation = false;

    /** 登录成功后是否提示该 IP 关联的其他账号（多账号检测，按最近一次登录 IP 归因） */
    private boolean authNotifyOtherAccounts = false;

    /** 未登录/未注册玩家提示提醒间隔（秒），0=关闭定时提醒，默认 6 秒 */
    private int authReminderInterval = 6;

    /** 登录后是否强制生存模式 */
    private boolean authForceSurvival = false;

    /** 会话超时时间（分钟），0=禁用。启用后玩家在超时时间内重连且 IP 相同则无需重新登录 */
    private int sessionTimeout = 0;

    /** 跨服会话同步签名密钥（Velocity 与 Spigot 端必须配置相同的值，空则关闭验签） */
    private String sessionSyncSecret = "";

    // ==================== 安全增强 ====================

    /** 失败登录计数与冷却：是否启用 */
    private boolean secFailedLoginEnabled = true;
    /** 账户级最大失败次数 */
    private int secAccountMaxAttempts = 5;
    /** 账户级冷却时间（秒） */
    private int secAccountCooldown = 300;
    /** 账户级登录成功后重置计数 */
    private boolean secAccountResetOnSuccess = true;
    /** IP 级最大失败次数 */
    private int secIpMaxAttempts = 10;
    /** IP 级冷却时间（秒） */
    private int secIpCooldown = 600;
    /** IP 级登录成功后重置计数 */
    private boolean secIpResetOnSuccess = false;

    /** 单 IP 账号数量限制：是否启用 */
    private boolean secIpLimitsEnabled = true;
    /** 单 IP 最多注册账号数（0=不限制） */
    private int secMaxAccountsPerIp = 3;
    /** 单 IP 最多同时在线账号数（0=不限制） */
    private int secMaxOnlinePerIp = 2;

    /** IP 变更警告：是否启用 */
    private boolean secIpChangeEnabled = true;
    /** IP 变更时警告玩家 */
    private boolean secIpChangeWarnPlayer = true;
    /** IP 变更时通知管理员 */
    private boolean secIpChangeNotifyAdmin = false;

    /** IP 地理位置检测：是否启用 */
    private boolean secGeoEnabled = false;
    /** xdb 文件存放目录（相对插件数据目录） */
    private String secGeoXdbDir = "ip2region";
    /** 自动下载 xdb 文件 */
    private boolean secGeoAutoDownload = true;
    /** 启用 IPv4 查询 */
    private boolean secGeoV4Enabled = true;
    /** IPv4 xdb 文件名 */
    private String secGeoV4File = "ip2region_v4.xdb";
    /** 启用 IPv6 查询 */
    private boolean secGeoV6Enabled = false;
    /** IPv6 xdb 文件名 */
    private String secGeoV6File = "ip2region_v6.xdb";
    /** 缓存策略: file / vIndexCache / bufferCache */
    private String secGeoCachePolicy = "vIndexCache";
    /** 查询线程池大小 */
    private int secGeoSearchers = 15;
    /** 跨国变化行为: warn / kick / require-login */
    private String secGeoCrossCountryAction = "warn";
    /** 跨城市变化行为: warn / kick / require-login */
    private String secGeoCrossCityAction = "warn";
    /** 局域网 IP 跳过地理检测 */
    private boolean secGeoSkipLan = true;

    /** 登录历史记录：是否启用 */
    private boolean secLoginHistoryEnabled = true;
    /** 每玩家保留历史记录条数 */
    private int secLoginHistoryMaxRecords = 20;

    // ==================== 调试 ====================

    /** 是否启用 debug 日志（true 输出详细调试日志，false 仅输出生产必要日志） */
    private boolean debug = false;

    // ==================== Getters / Setters ====================

    public boolean isUseMojangUuid() { return useMojangUuid; }
    public void setUseMojangUuid(boolean useMojangUuid) { this.useMojangUuid = useMojangUuid; }

    public boolean isProxy() { return proxy; }
    public void setProxy(boolean proxy) { this.proxy = proxy; }

    public boolean isNotifyPlayerStatus() { return notifyPlayerStatus; }
    public void setNotifyPlayerStatus(boolean notifyPlayerStatus) { this.notifyPlayerStatus = notifyPlayerStatus; }

    public Set<String> getAuthList() { return authList; }
    public void setAuthList(Set<String> authList) {
        this.authList = authList != null ? authList : Collections.emptySet();
    }
    public boolean isInAuthList(String username) {
        if (username == null) return false;
        return authList.stream().anyMatch(name -> name.equalsIgnoreCase(username));
    }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = (language != null && !language.isBlank()) ? language : "en_gb"; }

    /** 获取备用 API URL 列表，返回不可变副本 */
    public List<String> getFallbackApiUrls() {
        return Collections.unmodifiableList(fallbackApiUrls);
    }

    /** 设置备用 API URL 列表 */
    public void setFallbackApiUrls(List<String> urls) {
        this.fallbackApiUrls = urls != null ? new ArrayList<>(urls) : new ArrayList<>();
    }

    /** 添加单个备用 API URL */
    public void addFallbackApiUrl(String url) {
        if (url != null && !url.isBlank()) {
            this.fallbackApiUrls.add(url.trim());
        }
    }

    /** @deprecated 使用 {@link #getFallbackApiUrls()} 替代，保持向后兼容 */
    @Deprecated
    public String getFallbackApiUrl() {
        return fallbackApiUrls.isEmpty() ? "" : fallbackApiUrls.get(0);
    }

    public int getMojangRequestLimit() { return mojangRequestLimit; }
    public void setMojangRequestLimit(int mojangRequestLimit) { this.mojangRequestLimit = Math.max(0, mojangRequestLimit); }

    /** @deprecated 使用 {@link #setFallbackApiUrls(List)} 替代，保持向后兼容 */
    @Deprecated
    public void setFallbackApiUrl(String fallbackApiUrl) {
        if (fallbackApiUrl != null && !fallbackApiUrl.isBlank()) {
            this.fallbackApiUrls = new ArrayList<>();
            this.fallbackApiUrls.add(fallbackApiUrl.trim());
        }
    }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public String getSqliteFile() { return sqliteFile; }
    public void setSqliteFile(String sqliteFile) { this.sqliteFile = sqliteFile; }

    public String getMysqlHost() { return mysqlHost; }
    public void setMysqlHost(String mysqlHost) { this.mysqlHost = mysqlHost; }

    public int getMysqlPort() { return mysqlPort; }
    public void setMysqlPort(int mysqlPort) { this.mysqlPort = mysqlPort; }

    public String getMysqlDatabase() { return mysqlDatabase; }
    public void setMysqlDatabase(String mysqlDatabase) { this.mysqlDatabase = mysqlDatabase; }

    public String getMysqlUsername() { return mysqlUsername; }
    public void setMysqlUsername(String mysqlUsername) { this.mysqlUsername = mysqlUsername; }

    public String getMysqlPassword() { return mysqlPassword; }
    public void setMysqlPassword(String mysqlPassword) { this.mysqlPassword = mysqlPassword; }

    public String getMysqlTablePrefix() { return mysqlTablePrefix; }
    public void setMysqlTablePrefix(String mysqlTablePrefix) { this.mysqlTablePrefix = mysqlTablePrefix; }

    public boolean isMysqlUseSsl() { return mysqlUseSsl; }
    public void setMysqlUseSsl(boolean mysqlUseSsl) { this.mysqlUseSsl = mysqlUseSsl; }

    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }

    public boolean isBackupEnabled() { return backupEnabled; }
    public void setBackupEnabled(boolean backupEnabled) { this.backupEnabled = backupEnabled; }

    public int getBackupIntervalHours() { return backupIntervalHours; }
    public void setBackupIntervalHours(int backupIntervalHours) { this.backupIntervalHours = backupIntervalHours; }

    public String getBackupDir() { return backupDir; }
    public void setBackupDir(String backupDir) { this.backupDir = backupDir; }

    public int getBackupMaxCount() { return backupMaxCount; }
    public void setBackupMaxCount(int backupMaxCount) { this.backupMaxCount = backupMaxCount; }

    // ==================== 更新检查 ====================

    public boolean isUpdateCheckEnabled() { return updateCheckEnabled; }
    public void setUpdateCheckEnabled(boolean updateCheckEnabled) { this.updateCheckEnabled = updateCheckEnabled; }

    public int getUpdateCheckIntervalHours() { return updateCheckIntervalHours; }
    public void setUpdateCheckIntervalHours(int updateCheckIntervalHours) { this.updateCheckIntervalHours = updateCheckIntervalHours; }

    public boolean isDebug() { return debug; }
    public void setDebug(boolean debug) { this.debug = debug; }

    // ==================== Auth Getters / Setters ====================

    public boolean isAuthEnabled() { return authEnabled; }
    public void setAuthEnabled(boolean authEnabled) { this.authEnabled = authEnabled; }

    public int getAuthPasswordMin() { return authPasswordMin; }
    public void setAuthPasswordMin(int authPasswordMin) { this.authPasswordMin = authPasswordMin; }

    public int getAuthPasswordMax() { return authPasswordMax; }
    public void setAuthPasswordMax(int authPasswordMax) { this.authPasswordMax = authPasswordMax; }

    public boolean isAuthForceAdventure() { return authForceAdventure; }
    public void setAuthForceAdventure(boolean authForceAdventure) { this.authForceAdventure = authForceAdventure; }

    public boolean isAuthFreezePosition() { return authFreezePosition; }
    public void setAuthFreezePosition(boolean authFreezePosition) { this.authFreezePosition = authFreezePosition; }

    public boolean isAuthRestrictMove() { return authRestrictMove; }
    public void setAuthRestrictMove(boolean authRestrictMove) { this.authRestrictMove = authRestrictMove; }

    public boolean isAuthRestrictChat() { return authRestrictChat; }
    public void setAuthRestrictChat(boolean authRestrictChat) { this.authRestrictChat = authRestrictChat; }

    public boolean isAuthRestrictInteract() { return authRestrictInteract; }
    public void setAuthRestrictInteract(boolean authRestrictInteract) { this.authRestrictInteract = authRestrictInteract; }

    public boolean isAuthRestrictDamage() { return authRestrictDamage; }
    public void setAuthRestrictDamage(boolean authRestrictDamage) { this.authRestrictDamage = authRestrictDamage; }

    public boolean isAuthRestrictCommand() { return authRestrictCommand; }
    public void setAuthRestrictCommand(boolean authRestrictCommand) { this.authRestrictCommand = authRestrictCommand; }

    public List<String> getAuthAllowCommands() { return Collections.unmodifiableList(authAllowCommands); }
    public void setAuthAllowCommands(List<String> authAllowCommands) {
        if (authAllowCommands != null) {
            this.authAllowCommands = new ArrayList<>();
            for (String cmd : authAllowCommands) {
                if (cmd != null && !cmd.isBlank()) {
                    this.authAllowCommands.add(cmd.trim().toLowerCase());
                }
            }
        }
    }

    public int getAuthLoginTimeout() { return authLoginTimeout; }
    public void setAuthLoginTimeout(int authLoginTimeout) { this.authLoginTimeout = authLoginTimeout; }

    public int getAuthRegisterTimeout() { return authRegisterTimeout; }
    public void setAuthRegisterTimeout(int authRegisterTimeout) { this.authRegisterTimeout = authRegisterTimeout; }

    public boolean isAuthLoginSpawnPoint() { return authLoginSpawnPoint; }
    public void setAuthLoginSpawnPoint(boolean authLoginSpawnPoint) { this.authLoginSpawnPoint = authLoginSpawnPoint; }

    public String getAuthSpawnPointWorld() { return authSpawnPointWorld; }
    public void setAuthSpawnPointWorld(String authSpawnPointWorld) { this.authSpawnPointWorld = authSpawnPointWorld != null ? authSpawnPointWorld : ""; }
    public double getAuthSpawnPointX() { return authSpawnPointX; }
    public void setAuthSpawnPointX(double authSpawnPointX) { this.authSpawnPointX = authSpawnPointX; }
    public double getAuthSpawnPointY() { return authSpawnPointY; }
    public void setAuthSpawnPointY(double authSpawnPointY) { this.authSpawnPointY = authSpawnPointY; }
    public double getAuthSpawnPointZ() { return authSpawnPointZ; }
    public void setAuthSpawnPointZ(double authSpawnPointZ) { this.authSpawnPointZ = authSpawnPointZ; }
    public float getAuthSpawnPointYaw() { return authSpawnPointYaw; }
    public void setAuthSpawnPointYaw(float authSpawnPointYaw) { this.authSpawnPointYaw = authSpawnPointYaw; }
    public float getAuthSpawnPointPitch() { return authSpawnPointPitch; }
    public void setAuthSpawnPointPitch(float authSpawnPointPitch) { this.authSpawnPointPitch = authSpawnPointPitch; }

    public boolean isAuthReturnLastLocation() { return authReturnLastLocation; }
    public void setAuthReturnLastLocation(boolean authReturnLastLocation) { this.authReturnLastLocation = authReturnLastLocation; }

    public boolean isAuthNotifyOtherAccounts() { return authNotifyOtherAccounts; }
    public void setAuthNotifyOtherAccounts(boolean authNotifyOtherAccounts) { this.authNotifyOtherAccounts = authNotifyOtherAccounts; }

    public int getAuthReminderInterval() { return authReminderInterval; }
    public void setAuthReminderInterval(int authReminderInterval) { this.authReminderInterval = authReminderInterval; }

    public boolean isAuthForceSurvival() { return authForceSurvival; }
    public void setAuthForceSurvival(boolean authForceSurvival) { this.authForceSurvival = authForceSurvival; }

    public int getSessionTimeout() { return sessionTimeout; }
    public void setSessionTimeout(int sessionTimeout) { this.sessionTimeout = sessionTimeout; }

    public String getSessionSyncSecret() { return sessionSyncSecret; }
    public void setSessionSyncSecret(String sessionSyncSecret) {
        this.sessionSyncSecret = sessionSyncSecret != null ? sessionSyncSecret : "";
    }

    // ==================== 安全增强 Getters / Setters ====================

    public boolean isSecFailedLoginEnabled() { return secFailedLoginEnabled; }
    public void setSecFailedLoginEnabled(boolean v) { this.secFailedLoginEnabled = v; }
    public int getSecAccountMaxAttempts() { return secAccountMaxAttempts; }
    public void setSecAccountMaxAttempts(int v) { this.secAccountMaxAttempts = v; }
    public int getSecAccountCooldown() { return secAccountCooldown; }
    public void setSecAccountCooldown(int v) { this.secAccountCooldown = v; }
    public boolean isSecAccountResetOnSuccess() { return secAccountResetOnSuccess; }
    public void setSecAccountResetOnSuccess(boolean v) { this.secAccountResetOnSuccess = v; }
    public int getSecIpMaxAttempts() { return secIpMaxAttempts; }
    public void setSecIpMaxAttempts(int v) { this.secIpMaxAttempts = v; }
    public int getSecIpCooldown() { return secIpCooldown; }
    public void setSecIpCooldown(int v) { this.secIpCooldown = v; }
    public boolean isSecIpResetOnSuccess() { return secIpResetOnSuccess; }
    public void setSecIpResetOnSuccess(boolean v) { this.secIpResetOnSuccess = v; }

    public boolean isSecIpLimitsEnabled() { return secIpLimitsEnabled; }
    public void setSecIpLimitsEnabled(boolean v) { this.secIpLimitsEnabled = v; }
    public int getSecMaxAccountsPerIp() { return secMaxAccountsPerIp; }
    public void setSecMaxAccountsPerIp(int v) { this.secMaxAccountsPerIp = v; }
    public int getSecMaxOnlinePerIp() { return secMaxOnlinePerIp; }
    public void setSecMaxOnlinePerIp(int v) { this.secMaxOnlinePerIp = v; }

    public boolean isSecIpChangeEnabled() { return secIpChangeEnabled; }
    public void setSecIpChangeEnabled(boolean v) { this.secIpChangeEnabled = v; }
    public boolean isSecIpChangeWarnPlayer() { return secIpChangeWarnPlayer; }
    public void setSecIpChangeWarnPlayer(boolean v) { this.secIpChangeWarnPlayer = v; }
    public boolean isSecIpChangeNotifyAdmin() { return secIpChangeNotifyAdmin; }
    public void setSecIpChangeNotifyAdmin(boolean v) { this.secIpChangeNotifyAdmin = v; }

    public boolean isSecGeoEnabled() { return secGeoEnabled; }
    public void setSecGeoEnabled(boolean v) { this.secGeoEnabled = v; }
    public String getSecGeoXdbDir() { return secGeoXdbDir; }
    public void setSecGeoXdbDir(String v) { this.secGeoXdbDir = v != null ? v : "ip2region"; }
    public boolean isSecGeoAutoDownload() { return secGeoAutoDownload; }
    public void setSecGeoAutoDownload(boolean v) { this.secGeoAutoDownload = v; }
    public boolean isSecGeoV4Enabled() { return secGeoV4Enabled; }
    public void setSecGeoV4Enabled(boolean v) { this.secGeoV4Enabled = v; }
    public String getSecGeoV4File() { return secGeoV4File; }
    public void setSecGeoV4File(String v) { this.secGeoV4File = v != null ? v : "ip2region_v4.xdb"; }
    public boolean isSecGeoV6Enabled() { return secGeoV6Enabled; }
    public void setSecGeoV6Enabled(boolean v) { this.secGeoV6Enabled = v; }
    public String getSecGeoV6File() { return secGeoV6File; }
    public void setSecGeoV6File(String v) { this.secGeoV6File = v != null ? v : "ip2region_v6.xdb"; }
    public String getSecGeoCachePolicy() { return secGeoCachePolicy; }
    public void setSecGeoCachePolicy(String v) { this.secGeoCachePolicy = v != null ? v : "vIndexCache"; }
    public int getSecGeoSearchers() { return secGeoSearchers; }
    public void setSecGeoSearchers(int v) { this.secGeoSearchers = v; }
    public String getSecGeoCrossCountryAction() { return secGeoCrossCountryAction; }
    public void setSecGeoCrossCountryAction(String v) { this.secGeoCrossCountryAction = v != null ? v : "warn"; }
    public String getSecGeoCrossCityAction() { return secGeoCrossCityAction; }
    public void setSecGeoCrossCityAction(String v) { this.secGeoCrossCityAction = v != null ? v : "warn"; }
    public boolean isSecGeoSkipLan() { return secGeoSkipLan; }
    public void setSecGeoSkipLan(boolean v) { this.secGeoSkipLan = v; }

    public boolean isSecLoginHistoryEnabled() { return secLoginHistoryEnabled; }
    public void setSecLoginHistoryEnabled(boolean v) { this.secLoginHistoryEnabled = v; }
    public int getSecLoginHistoryMaxRecords() { return secLoginHistoryMaxRecords; }
    public void setSecLoginHistoryMaxRecords(int v) { this.secLoginHistoryMaxRecords = v; }
}
