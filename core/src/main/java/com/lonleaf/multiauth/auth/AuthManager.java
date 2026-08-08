package com.lonleaf.multiauth.auth;

import com.lonleaf.multiauth.db.DatabaseManager;
import com.lonleaf.multiauth.db.PlayerRecord;
import com.lonleaf.multiauth.mojang.MojangApiService;
import com.lonleaf.multiauth.mojang.MojangSessionService;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final DatabaseManager database;
    private final MojangSessionService mojangService;
    private final MojangApiService mojangApiService;

    /** 并发登录保护：正在验证中的用户名集合，防止同一账号并发验证 */
    private final Set<String> pendingVerifications = ConcurrentHashMap.newKeySet();

    public AuthManager(DatabaseManager database, MojangSessionService mojangService,
                       MojangApiService mojangApiService) {
        this.database = database;
        this.mojangService = mojangService;
        this.mojangApiService = mojangApiService;
    }

    // ==================== 并发登录保护 ====================

    /**
     * 尝试占用验证槽位。如果该用户名正在验证中，则返回 false。
     * 验证完成后必须调用 {@link #endVerification} 释放槽位。
     */
    public boolean beginVerification(String username) {
        return pendingVerifications.add(username.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 释放验证槽位。
     */
    public void endVerification(String username) {
        pendingVerifications.remove(username.toLowerCase(java.util.Locale.ROOT));
    }

    // ==================== 用户名验证 ====================

    /**
     * 检查用户名是否为正版账号，返回可区分"非正版"与"API 不可达"的结果。
     */
    public UsernameCheckResult checkUsername(String username) {
        try {
            Optional<UUID> opt = mojangApiService.checkPremium(username);
            if (opt.isPresent()) {
                return new UsernameCheckResult(UsernameCheckResult.Status.PREMIUM, opt.get());
            }
            return new UsernameCheckResult(UsernameCheckResult.Status.NOT_PREMIUM, null);
        } catch (MojangApiService.RateLimitException e) {
            // 本地速率限制（并发信号量超时 / 线程中断），非 API 宕机：
            // fail-closed 拒绝，禁止走宕机放行路径（防限流混淆绕过加密握手）
            return new UsernameCheckResult(UsernameCheckResult.Status.RATE_LIMITED, null);
        } catch (IOException e) {
            // 网络/超时：视为 API 宕机，交由 AuthFlow 按宕机策略决策
            return new UsernameCheckResult(UsernameCheckResult.Status.API_UNREACHABLE, null);
        } catch (RuntimeException e) {
            // 配置/编程错误（如备用 API URL 模板非法触发 IllegalArgumentException）：
            // 不等同于 API 宕机，fail-closed 拒绝，禁止落入宕机降级放行路径
            return new UsernameCheckResult(UsernameCheckResult.Status.INTERNAL_ERROR, null);
        }
    }

    /** 用户名检查结果 */
    public record UsernameCheckResult(Status status, UUID uuid) {
        public enum Status { PREMIUM, NOT_PREMIUM, API_UNREACHABLE, RATE_LIMITED, INTERNAL_ERROR }
    }

    // ==================== Mojang 加密验证 ====================

    /**
     * 基于已解密的 sharedSecret 计算 serverId 并调用 hasJoined，返回详细结果（区分盗版与 Mojang 宕机）。
     */
    public MojangSessionService.HasJoinedResult verifyWithMojangDetailed(String username, AuthCrypto crypto,
                                                                         byte[] sharedSecret, String ip) {
        try {
            String serverId = crypto.computeServerId(sharedSecret);
            return mojangService.hasJoinedDetailed(username, serverId, ip);
        } catch (Exception e) {
            return new MojangSessionService.HasJoinedResult(
                    MojangSessionService.HasJoinedResult.Status.MOJANG_UNREACHABLE, null);
        }
    }

    // ==================== 数据库操作 ====================

    public boolean isDatabaseAvailable() {
        return database != null && database.isConnected();
    }

    public PlayerRecord getPlayerRecord(String username) {
        if (database == null) return null;
        return database.getPlayer(username);
    }

    public void savePlayerRecord(String username, boolean isPremium, UUID uuid) {
        if (database == null) return;
        // 使用数据库层面的条件 UPSERT，避免"先查后写"竞态条件
        database.savePlayerSafe(username, isPremium, uuid);
    }

    public boolean wasPremiumPlayer(String username) {
        PlayerRecord record = getPlayerRecord(username);
        return record != null && record.isPremium();
    }

    /**
     * 数据库记录总数（/multiauth status 使用）。
     *
     * @return 记录总数；-1 表示数据库不可用或查询失败
     */
    public int getRecordCount() {
        if (database == null) return -1;
        return database.countRecords();
    }

    /**
     * 正版记录数（is_premium=1，/multiauth status 使用）。
     *
     * @return 正版记录数；-1 表示数据库不可用或查询失败
     */
    public int getPremiumRecordCount() {
        if (database == null) return -1;
        return database.countPremiumRecords();
    }

    // ==================== 工具方法 ====================

    public static UUID generateOfflineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
