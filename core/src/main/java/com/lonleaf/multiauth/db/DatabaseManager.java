package com.lonleaf.multiauth.db;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

public interface DatabaseManager {

    void connect() throws SQLException;

    void disconnect();

    boolean isConnected();

    boolean ping();

    /** 获取玩家记录；username 统一按小写匹配（Minecraft 用户名不区分大小写） */
    PlayerRecord getPlayer(String username);

    void savePlayer(String username, boolean isPremium, UUID uuid);

    /**
     * 安全保存玩家记录：如果数据库中已有正版记录（is_premium=1）而新记录为非正版，
     * 则不覆写，避免离线登录覆写正版记录。在数据库层面实现，无竞态条件。
     */
    void savePlayerSafe(String username, boolean isPremium, UUID uuid);

    /** 更新玩家最后下线位置 */
    void updatePlayerLocation(String username, String world, double x, double y, double z, float yaw, float pitch);

    /** 更新玩家最后登录 IP（正版记录，/multiauth info 使用）；ip 为 null 时忽略 */
    void updatePlayerLastIp(String username, String ip);

    boolean exists(String username);

    /**
     * 记录总数（/multiauth status 使用）。
     *
     * @return 记录总数；-1 表示查询失败
     */
    int countRecords();

    /**
     * 正版记录数（is_premium=1，/multiauth status 使用）。
     *
     * @return 正版记录数；-1 表示查询失败
     */
    int countPremiumRecords();

    int migrateTo(DatabaseManager target);

    void backup(Path target) throws SQLException, IOException;

    // ==================== 离线玩家注册账号 ====================
    // 注意：本组方法及登录历史组的 username 参数统一按小写存储/查询，
    // 与 players 表语义一致（Minecraft 用户名不区分大小写），调用方无需自行规范化。

    /** 创建 auth 账号表（如不存在） */
    void createAuthTable();

    /** 获取 auth 账号记录，不存在返回 null */
    AuthAccount getAuthAccount(String username);

    /** 保存（插入或更新）auth 账号记录，返回是否保存成功 */
    boolean saveAuthAccount(AuthAccount account);

    /** 更新密码哈希，返回是否更新成功 */
    boolean updateAuthPassword(String username, String passwordHash);

    /** 更新最后登录时间和 IP */
    void updateAuthLogin(String username, long loginTime, String ip);

    /** 删除 auth 账号记录，返回是否删除成功 */
    boolean deleteAuthAccount(String username);

    /** auth 账号是否存在 */
    boolean authAccountExists(String username);

    // ==================== 登录历史 ====================

    /** 创建登录历史表（如不存在） */
    void createLoginHistoryTable();

    /** 记录登录历史（成功或失败） */
    void recordLoginHistory(String username, String ip, long loginTime, boolean success, String country, String city);

    /** 获取玩家最近 N 条成功登录记录，按时间倒序 */
    java.util.List<LoginHistoryRecord> getRecentLoginHistory(String username, int limit);

    /**
     * 获取玩家最近 N 条登录记录（按时间倒序），与 getRecentLoginHistory 不同：
     * 内部不吞异常，数据库异常时直接向上抛出（供安全检查 fail-closed 使用）。
     *
     * @throws SQLException 数据库查询异常
     */
    java.util.List<LoginHistoryRecord> getLoginHistoryChecked(String username, int limit) throws SQLException;

    /** 删除玩家多余的历史记录，保留 maxRecords 条 */
    void trimLoginHistory(String username, int maxRecords);

    // ==================== IP 统计 ====================

    /** 创建 IP 统计表（如不存在） */
    void createIpStatsTable();

    /** 获取 IP 统计记录，不存在返回 null */
    IpStatsRecord getIpStats(String ip);

    /** 递增 IP 注册账号数 */
    void incrementIpAccountCount(String ip);

    /** 递减 IP 注册账号数（注销账号时） */
    void decrementIpAccountCount(String ip);
}
