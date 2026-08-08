package com.lonleaf.multiauth.auth;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.db.PlayerRecord;
import com.lonleaf.multiauth.mojang.MojangSessionService;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * 共享认证流程，供 Velocity 和 Spigot 端调用。
 */
public class AuthFlow {

    /** 认证决策 */
    public enum Decision { ALLOW, DENY }

    /** 认证结果 */
    public record Result(
            Decision decision,
            UUID uuid,
            boolean isPremium,
            MojangSessionService.MojangProfile profile,
            String denyMessage
    ) {
        public static Result allowPremium(UUID uuid, MojangSessionService.MojangProfile profile) {
            return new Result(Decision.ALLOW, uuid, true, profile, null);
        }

        public static Result allowOffline(UUID uuid) {
            return new Result(Decision.ALLOW, uuid, false, null, null);
        }

        public static Result deny(String message) {
            return new Result(Decision.DENY, null, false, null, message);
        }
    }

    /**
     * 平台验证器回调，执行 Mojang 加密握手。
     * Velocity 端通过反射访问 Netty 管线，Spigot 端通过 NMS 反射。
     */
    @FunctionalInterface
    public interface PremiumVerifier {
        /**
         * 执行 Mojang 加密握手验证。
         * @param username 玩家名
         * @return HasJoinedResult，若客户端未响应加密请求则返回 null（盗版客户端）
         */
        MojangSessionService.HasJoinedResult verify(String username);
    }

    /**
     * 执行完整认证流程。
     *
     * @param core     Core 实例
     * @param config   认证配置
     * @param username 玩家名
     * @param verifier 平台验证器回调
     * @param logger   日志器
     * @return 认证结果
     */
    public static Result evaluate(Core core, AuthConfig config, String username,
                                  PremiumVerifier verifier, Logger logger) {
        AuthManager authManager = core.getAuthManager();

        // 1. 数据库健康检查
        if (!core.isDatabaseHealthy()) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_DB_UNAVAILABLE));
            return Result.deny(Messages.AUTH_DATABASE_UNAVAILABLE);
        }

        if (authManager == null) {
            logger.severe(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_AUTHMANAGER_NOT_INITIALIZED));
            return Result.deny(Messages.AUTH_SERVICE_NOT_INITIALIZED);
        }

        // 3. 并发登录保护
        if (!authManager.beginVerification(username)) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_CONCURRENT_LOGIN));
            return Result.deny(Messages.AUTH_CONCURRENT_LOGIN_BLOCKED);
        }

        try {
            // 4. 用户名正版检查
            AuthManager.UsernameCheckResult ucr;
            try {
                ucr = authManager.checkUsername(username);
            } catch (RuntimeException e) {
                // 配置/编程错误（checkUsername 已映射 IOException→宕机、RateLimitException→限流，
                // 此处捕获未预期的 RuntimeException）：fail-closed 拒绝，禁止按宕机降级放行
                logger.severe(Messages.get(Messages.AUTH_CHECK_INTERNAL_ERROR));
                return Result.deny(Messages.AUTH_CHECK_INTERNAL_ERROR);
            }

            // 5. 根据检查结果分流
            return switch (ucr.status()) {
                case PREMIUM -> {
                    logger.fine(Messages.get(Messages.AUTH_PREMIUM_DETECTED, username, ucr.uuid().toString()));
                    yield handlePremium(authManager, config, username, verifier, logger);
                }
                case NOT_PREMIUM -> {
                    if (config.isInAuthList(username)) {
                        logger.fine(Messages.get(Messages.AUTH_PREMIUM_IN_AUTHLIST, username));
                        yield handlePremium(authManager, config, username, verifier, logger);
                    } else {
                        // 过程细节：允许离线登录（最终结果由平台层聚合日志输出）
                        logger.fine(Messages.get(Messages.AUTH_OFFLINE_ALLOWED, username));
                        yield allowOffline(authManager, username, logger);
                    }
                }
                case API_UNREACHABLE -> {
                    logger.warning(Messages.get(Messages.AUTH_DOWNTIME_FLOW, username));
                    yield handleDowntime(authManager, username, logger);
                }
                case RATE_LIMITED -> {
                    // 本地 API 限流（并发洪峰触发）：fail-closed 拒绝，禁止走宕机放行路径
                    logger.warning(Messages.get(Messages.AUTH_API_RATE_LIMITED, username));
                    yield Result.deny(Messages.AUTH_API_RATE_LIMITED);
                }
                case INTERNAL_ERROR -> {
                    // 配置/编程错误（checkUsername 内部抛出 RuntimeException）：fail-closed 拒绝，
                    // 禁止按宕机策略放行离线玩家（防配置错误诱导降级放行）
                    logger.severe(Messages.get(Messages.AUTH_CHECK_INTERNAL_ERROR));
                    yield Result.deny(Messages.AUTH_CHECK_INTERNAL_ERROR);
                }
            };
        } finally {
            authManager.endVerification(username);
        }
    }

    /**
     * 处理正版用户名：调用平台验证器执行加密握手 + hasJoined。
     */
    private static Result handlePremium(AuthManager authManager, AuthConfig config, String username,
                                        PremiumVerifier verifier, Logger logger) {
        MojangSessionService.HasJoinedResult result = verifier.verify(username);

        if (result == null) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_NO_ENC_RESPONSE));
            return Result.deny(Messages.AUTH_INVALID_SESSION);
        }

        return switch (result.status()) {
            case SUCCESS -> {
                UUID premiumUuid = result.profile().uuid();
                // use-mojang-uuid=false：正版玩家也用离线 UUID（正版与盗版共享存档/权限）
                UUID effectiveUuid = config.isUseMojangUuid()
                        ? premiumUuid
                        : AuthManager.generateOfflineUuid(username);
                // 过程细节：hasJoined 验证通过（最终结果由平台层聚合日志输出）
                logger.fine(Messages.get(Messages.AUTH_MOJANG_VERIFY_PASSED, username, effectiveUuid.toString()));
                authManager.savePlayerRecord(username, true, effectiveUuid);
                yield Result.allowPremium(effectiveUuid, result.profile());
            }
            case NOT_PREMIUM -> {
                logger.warning(Messages.get(Messages.AUTH_MOJANG_VERIFY_FAILED_PIRATE, username));
                yield Result.deny(Messages.AUTH_INVALID_SESSION);
            }
            case MOJANG_UNREACHABLE -> {
                // 第二层 hasJoined 验证失败（仅 Mojang sessionserver，无备用 API）：
                // 正版验证无法完成，与第一层宕机语义不同，拒绝登录并记录审计日志
                logger.warning(Messages.get(Messages.AUTH_MOJANG_UNREACHABLE, username));
                logger.warning(Messages.get(Messages.AUTH_AUDIT_HASJOINED_UNREACHABLE, username));
                yield Result.deny(Messages.AUTH_DOWNTIME_DENY);
            }
            case RATE_LIMITED -> {
                // 第二层 hasJoined 本地限流（并发洪峰触发 Semaphore 超时，非宕机）：
                // fail-closed 拒绝，审计语义与宕机区分，禁止落入宕机降级路径
                logger.warning(Messages.get(Messages.AUTH_AUDIT_HASJOINED_RATE_LIMITED, username));
                yield Result.deny(Messages.AUTH_API_RATE_LIMITED);
            }
        };
    }

    /**
     * 第一层宕机流程（API_UNREACHABLE）：用户名正版检查的官方与备用 API 全部不可达。
     */
    private static Result handleDowntime(AuthManager authManager, String username, Logger logger) {
        PlayerRecord record = authManager.getPlayerRecord(username);
        if (record == null) {
            logger.warning(Messages.get(Messages.AUTH_AUDIT_DOWNTIME_NO_RECORD, username));
            return Result.deny(Messages.AUTH_DOWNTIME_DENY);
        }
        if (record.isPremium()) {
            logger.warning(Messages.get(Messages.AUTH_AUDIT_DOWNTIME_PREMIUM_HISTORY, username, record.uuid().toString()));
            return Result.deny(Messages.AUTH_DOWNTIME_DENY);
        }
        // 有离线历史记录 → 确认其为离线玩家，宕机期间允许离线登录
        logger.info(Messages.get(Messages.AUTH_DOWNTIME_ALLOW_OFFLINE, username));
        return allowOffline(authManager, username, logger);
    }

    /**
     * 允许离线登录并持久化记录。
     * 防降级保护：数据库中已有正版记录（is_premium=1）时拒绝写入离线记录，
     * 防止 API 宕机降级/异常判定把正版玩家覆盖为离线（P1-14）。
     */
    private static Result allowOffline(AuthManager authManager, String username, Logger logger) {
        PlayerRecord existing = authManager.getPlayerRecord(username);
        if (existing != null && existing.isPremium()) {
            logger.warning(Messages.get(Messages.AUTH_AUDIT_DOWNTIME_PREMIUM_HISTORY,
                    username, existing.uuid().toString()));
            return Result.deny(Messages.AUTH_DOWNTIME_DENY);
        }
        UUID offlineUuid = AuthManager.generateOfflineUuid(username);
        authManager.savePlayerRecord(username, false, offlineUuid);
        return Result.allowOffline(offlineUuid);
    }

    /**
     * API-only 降级认证流程（Spigot proxy=false 且 PacketEvents 不可用时使用）。
     */
    public static Result evaluateApiOnly(Core core, AuthConfig config, String username, Logger logger) {
        AuthManager authManager = core.getAuthManager();

        if (!core.isDatabaseHealthy()) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_DB_UNAVAILABLE));
            return Result.deny(Messages.AUTH_DATABASE_UNAVAILABLE);
        }

        if (authManager == null) {
            logger.severe(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_AUTHMANAGER_NOT_INITIALIZED));
            return Result.deny(Messages.AUTH_SERVICE_NOT_INITIALIZED);
        }

        if (!authManager.beginVerification(username)) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_CONCURRENT_LOGIN));
            return Result.deny(Messages.AUTH_CONCURRENT_LOGIN_BLOCKED);
        }

        try {
            AuthManager.UsernameCheckResult ucr;
            try {
                ucr = authManager.checkUsername(username);
            } catch (RuntimeException e) {
                // 配置/编程错误（checkUsername 已映射 IOException→宕机、RateLimitException→限流，
                // 此处捕获未预期的 RuntimeException）：fail-closed 拒绝，禁止按宕机降级放行
                logger.severe(Messages.get(Messages.AUTH_CHECK_INTERNAL_ERROR));
                return Result.deny(Messages.AUTH_CHECK_INTERNAL_ERROR);
            }

            return switch (ucr.status()) {
                case NOT_PREMIUM -> {
                    if (config.isInAuthList(username)) {
                        logger.warning(Messages.get(Messages.AUTH_AUDIT_API_ONLY_AUTHLIST, username));
                        yield Result.deny(Messages.AUTH_INVALID_SESSION);
                    }
                    yield allowOffline(authManager, username, logger);
                }
                case PREMIUM -> {
                    logger.warning(Messages.get(Messages.AUTH_AUDIT_API_ONLY_PREMIUM, username));
                    yield Result.deny(Messages.AUTH_INVALID_SESSION);
                }
                case API_UNREACHABLE -> {
                    logger.warning(Messages.get(Messages.AUTH_DOWNTIME_FLOW, username));
                    yield handleDowntime(authManager, username, logger);
                }
                case RATE_LIMITED -> {
                    // 本地 API 限流（并发洪峰触发）：fail-closed 拒绝，禁止走宕机放行路径
                    logger.warning(Messages.get(Messages.AUTH_API_RATE_LIMITED, username));
                    yield Result.deny(Messages.AUTH_API_RATE_LIMITED);
                }
                case INTERNAL_ERROR -> {
                    // 配置/编程错误（checkUsername 内部抛出 RuntimeException）：fail-closed 拒绝，
                    // 禁止按宕机策略放行离线玩家（防配置错误诱导降级放行）
                    logger.severe(Messages.get(Messages.AUTH_CHECK_INTERNAL_ERROR));
                    yield Result.deny(Messages.AUTH_CHECK_INTERNAL_ERROR);
                }
            };
        } finally {
            authManager.endVerification(username);
        }
    }

    // ==================== Velocity 代理端专用流程 ====================

    /** Velocity 代理端认证决策 */
    public enum ProxyDecision { ALLOW_PREMIUM, ALLOW_OFFLINE, DENY }

    /** Velocity 代理端认证结果 */
    public record ProxyAuthResult(
            ProxyDecision decision,
            UUID uuid,
            String denyMessage
    ) {
        public static ProxyAuthResult allowPremium(UUID uuid) {
            return new ProxyAuthResult(ProxyDecision.ALLOW_PREMIUM, uuid, null);
        }

        public static ProxyAuthResult allowOffline() {
            return new ProxyAuthResult(ProxyDecision.ALLOW_OFFLINE, null, null);
        }

        public static ProxyAuthResult deny(String message) {
            return new ProxyAuthResult(ProxyDecision.DENY, null, message);
        }
    }

    /**
     * Velocity 代理端认证流程。
     */
    public static ProxyAuthResult evaluateForProxy(Core core, AuthConfig config,
                                                    String username, Logger logger) {
        AuthManager authManager = core.getAuthManager();

        // 1. 数据库健康检查
        if (!core.isDatabaseHealthy()) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_DB_UNAVAILABLE));
            return ProxyAuthResult.deny(Messages.AUTH_DATABASE_UNAVAILABLE);
        }

        if (authManager == null) {
            logger.severe(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_AUTHMANAGER_NOT_INITIALIZED));
            return ProxyAuthResult.deny(Messages.AUTH_SERVICE_NOT_INITIALIZED);
        }

        // 3. 并发登录保护
        if (!authManager.beginVerification(username)) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_CONCURRENT_LOGIN));
            return ProxyAuthResult.deny(Messages.AUTH_CONCURRENT_LOGIN_BLOCKED);
        }

        try {
            // 4. 用户名正版检查
            AuthManager.UsernameCheckResult ucr;
            try {
                ucr = authManager.checkUsername(username);
            } catch (RuntimeException e) {
                // 配置/编程错误（checkUsername 已映射 IOException→宕机、RateLimitException→限流，
                // 此处捕获未预期的 RuntimeException）：fail-closed 拒绝，禁止按宕机降级放行
                logger.severe(Messages.get(Messages.AUTH_CHECK_INTERNAL_ERROR));
                return ProxyAuthResult.deny(Messages.AUTH_CHECK_INTERNAL_ERROR);
            }

            // 5. 根据检查结果分流（不含加密握手，由 Velocity 内置处理）
            //    注意：此处只决策"是否走 online-mode 加密握手"，不代表 hasJoined 验证通过。
            //    真正的 Mojang 验证通过日志在 Velocity 的 GameProfileRequestEvent（hasJoined 之后）打印。
            return switch (ucr.status()) {
                case PREMIUM -> {
                    logger.fine(Messages.get(Messages.AUTH_PREMIUM_DETECTED, username, ucr.uuid().toString()));
                    yield ProxyAuthResult.allowPremium(ucr.uuid());
                }
                case NOT_PREMIUM -> {
                    if (config.isInAuthList(username)) {
                        logger.fine(Messages.get(Messages.AUTH_PREMIUM_IN_AUTHLIST, username));
                        // 注意：NOT_PREMIUM 时 ucr.uuid() 恒为 null——auth-list 强制正版验证，
                        // 但该用户名并无正版 UUID。调用方（VelocityAuthListener.onPreLogin）不使用
                        // ProxyAuthResult.uuid()，仅依据 decision 分支处理，null 无实际影响。
                        yield ProxyAuthResult.allowPremium(ucr.uuid());
                    } else {
                        // 过程细节：允许离线登录（最终结果由平台层聚合日志输出）
                        logger.fine(Messages.get(Messages.AUTH_OFFLINE_ALLOWED, username));
                        yield ProxyAuthResult.allowOffline();
                    }
                }
                case API_UNREACHABLE -> {
                    logger.warning(Messages.get(Messages.AUTH_DOWNTIME_FLOW, username));
                    yield handleDowntimeForProxy(authManager, username, logger);
                }
                case RATE_LIMITED -> {
                    // 本地 API 限流（并发洪峰触发）：fail-closed 拒绝，禁止走宕机放行路径
                    logger.warning(Messages.get(Messages.AUTH_API_RATE_LIMITED, username));
                    yield ProxyAuthResult.deny(Messages.AUTH_API_RATE_LIMITED);
                }
                case INTERNAL_ERROR -> {
                    // 配置/编程错误（checkUsername 内部抛出 RuntimeException）：fail-closed 拒绝，
                    // 禁止按宕机策略放行离线玩家（防配置错误诱导降级放行）
                    logger.severe(Messages.get(Messages.AUTH_CHECK_INTERNAL_ERROR));
                    yield ProxyAuthResult.deny(Messages.AUTH_CHECK_INTERNAL_ERROR);
                }
            };
        } finally {
            authManager.endVerification(username);
        }
    }

    /**
     * 第一层宕机流程（代理端，API_UNREACHABLE）：用户名正版检查的官方与备用 API 全部不可达。
     */
    private static ProxyAuthResult handleDowntimeForProxy(AuthManager authManager,
                                                           String username, Logger logger) {
        PlayerRecord record = authManager.getPlayerRecord(username);
        if (record == null) {
            logger.warning(Messages.get(Messages.AUTH_AUDIT_DOWNTIME_NO_RECORD, username));
            return ProxyAuthResult.deny(Messages.AUTH_DOWNTIME_DENY);
        }
        if (record.isPremium()) {
            logger.warning(Messages.get(Messages.AUTH_AUDIT_DOWNTIME_PREMIUM_HISTORY, username, record.uuid().toString()));
            return ProxyAuthResult.deny(Messages.AUTH_DOWNTIME_DENY);
        }
        // 有离线历史记录 → 确认其为离线玩家，宕机期间允许离线登录
        logger.info(Messages.get(Messages.AUTH_DOWNTIME_ALLOW_OFFLINE, username));
        return ProxyAuthResult.allowOffline();
    }
}
