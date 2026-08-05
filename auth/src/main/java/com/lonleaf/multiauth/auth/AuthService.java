package com.lonleaf.multiauth.auth;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.geo.GeoInfo;
import com.lonleaf.multiauth.geo.IpGeoService;
import com.lonleaf.multiauth.db.AuthAccount;
import com.lonleaf.multiauth.db.DatabaseManager;
import com.lonleaf.multiauth.db.LoginHistoryRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 离线玩家注册/登录/改密服务。
 */
public class AuthService {

    private final DatabaseManager database;
    private final PasswordHasher passwordHasher;
    private final AuthSessionManager sessionManager;
    // volatile：reload 时更新引用，保证 auth 线程立即可见新配置
    private volatile AuthConfig config;
    private final Logger logger;

    // 安全增强服务（可选，由外部注入；为 null 表示该功能未启用）
    private volatile LoginSecurityManager securityManager;
    private volatile LoginHistoryManager historyManager;
    private volatile IpGeoService geoService;

    // 正在注册中的用户名集合（防止注册竞态条件：两个线程同时通过存在性检查）
    private final Set<String> registeringUsernames = ConcurrentHashMap.newKeySet();

    public AuthService(DatabaseManager database, AuthConfig config, Logger logger) {
        this.database = database;
        this.config = config;
        this.logger = logger;
        this.passwordHasher = new PasswordHasher();
        this.sessionManager = new AuthSessionManager();
        // 确保 auth 表存在
        try {
            database.createAuthTable();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[AUTH] Failed to create auth table: " + e.getMessage(), e);
        }
    }

    /**
     * 注入安全增强服务，并创建相关数据库表。
     *
     * @param securityManager 登录安全管理器（账户/IP 失败计数、冷却、IP 限制）
     * @param historyManager  登录历史管理器
     * @param geoService      IP 地理位置查询服务
     */
    public void setSecurityServices(LoginSecurityManager securityManager,
                                     LoginHistoryManager historyManager,
                                     IpGeoService geoService) {
        this.securityManager = securityManager;
        this.historyManager = historyManager;
        this.geoService = geoService;
        // 确保安全相关表存在
        try {
            database.createLoginHistoryTable();
            database.createIpStatsTable();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[AUTH] Failed to create security tables: " + e.getMessage(), e);
        }
    }

    /**
     * 注册操作结果。
     *
     * @param success   操作是否成功
     * @param message   发送给玩家的消息（已格式化，含 § 颜色代码）
     * @param shouldKick 是否应踢出玩家（达到失败阈值/IP 限制等）
     * @param warnings  成功时附加的警告消息列表（IP 变更/异地登录等），可为空
     */
    public record AuthResult(boolean success, String message,
                              boolean shouldKick, List<String> warnings) {
        /** 简化构造：成功/失败且无踢出与警告 */
        public AuthResult(boolean success, String message) {
            this(success, message, false, Collections.emptyList());
        }
    }

    /** 会话恢复安全检查结果（用于异地登录/跨地区检测） */
    public record SessionResumeCheck(boolean allowResume, boolean shouldKick,
                                      List<String> warnings) {
        public static SessionResumeCheck allow() {
            return new SessionResumeCheck(true, false, Collections.emptyList());
        }
    }

    /** 登录时异步查询的地理/历史上下文（供 thenCombine 合并密码验证结果使用） */
    private record GeoContext(String prevIp, GeoInfo prevGeo, GeoInfo currGeo) {}

    /** 登录预检结果：异步查询账号 + 冷却检查后的中间结果 */
    private record LoginPreflightResult(AuthAccount account, String message, boolean canProceed) {}

    // ==================== 注册 ====================

    /**
     * 异步注册新账号。
     *
     * @param username        玩家名
     * @param password        明文密码
     * @param confirmPassword 确认密码
     * @param ip              注册 IP
     * @return CompletableFuture，完成后返回 AuthResult
     */
    public CompletableFuture<AuthResult> register(String username, String password,
                                                   String confirmPassword, String ip) {
        // 同步校验：密码长度、一致性（不涉及 IO，主线程执行）
        if (password == null || password.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.AUTH_REGISTER_PASSWORD_EMPTY));
        }
        if (!password.equals(confirmPassword)) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.AUTH_REGISTER_PASSWORD_MISMATCH));
        }
        int min = config.getAuthPasswordMin();
        int max = config.getAuthPasswordMax();
        if (password.length() < min) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.get(Messages.AUTH_REGISTER_PASSWORD_TOO_SHORT, String.valueOf(min))));
        }
        if (password.length() > max) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.get(Messages.AUTH_REGISTER_PASSWORD_TOO_LONG, String.valueOf(max))));
        }

        final String finalIp = (ip != null) ? ip : "unknown";

        // 异步执行数据库检查 + IP 锁 + 哈希 + 保存（避免阻塞主线程）
        return CompletableFuture.supplyAsync(() -> {
            // 检查是否已注册
            if (database.authAccountExists(username)) {
                return new RegisterPreflight(false, Messages.AUTH_REGISTER_ALREADY, false);
            }

            // IP 注册锁：防止同 IP 并发注册绕过账号数量限制（TOCTOU 竞态）
            boolean ipLockAcquired = false;
            if (securityManager != null) {
                if (!securityManager.tryAcquireIpRegistration(finalIp)) {
                    return new RegisterPreflight(false, Messages.AUTH_LOGIN_PROCESSING, false);
                }
                ipLockAcquired = true;
                // 安全检查：单 IP 账号数量限制
                if (config.isSecIpLimitsEnabled() && !securityManager.canRegister(finalIp)) {
                    logger.warning("[SEC] Registration blocked for " + username + ": IP " + finalIp
                            + " reached account limit (" + config.getSecMaxAccountsPerIp() + ")");
                    securityManager.releaseIpRegistration(finalIp);
                    return new RegisterPreflight(false, Messages.AUTH_IP_ACCOUNT_LIMIT, false);
                }
            }

            // 防止注册竞态：占用用户名，避免两个线程同时通过存在性检查后重复写入
            if (!registeringUsernames.add(username)) {
                if (ipLockAcquired) securityManager.releaseIpRegistration(finalIp);
                return new RegisterPreflight(false, Messages.AUTH_LOGIN_PROCESSING, false);
            }

            return new RegisterPreflight(true, null, ipLockAcquired);
        }).thenCompose(preflight -> {
            if (!preflight.canProceed()) {
                return CompletableFuture.completedFuture(new AuthResult(false, preflight.message()));
            }
            // 异步哈希密码（不阻塞 supplyAsync 线程），然后保存到数据库
            final boolean ipLockAcquired = preflight.ipLockAcquired();
            CompletableFuture<String> hashFuture;
            try {
                hashFuture = passwordHasher.hash(password);
            } catch (Exception syncEx) {
                // 同步异常（如 executor 已关闭）：直接释放锁，避免泄漏
                registeringUsernames.remove(username);
                if (ipLockAcquired) securityManager.releaseIpRegistration(finalIp);
                logger.log(Level.WARNING, "[AUTH] Password hash submission failed for " + username + ": " + syncEx.getMessage(), syncEx);
                return CompletableFuture.completedFuture(new AuthResult(false, Messages.AUTH_REGISTER_FAILED));
            }
            return hashFuture.thenApply(hash -> {
                try {
                    long now = System.currentTimeMillis();
                    // lastLoginTime = now：注册即自动登录，记录登录时间供 /multiauth info 查询
                    database.saveAuthAccount(new AuthAccount(username, hash, now, now, finalIp));
                    if (securityManager != null) {
                        securityManager.onRegisterSuccess(finalIp);
                    }
                    logger.info("[AUTH] Player " + username + " registered successfully (IP=" + finalIp + ")");
                    return new AuthResult(true, Messages.AUTH_REGISTER_SUCCESS);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "[AUTH] Failed to save auth account for " + username + ": " + e.getMessage(), e);
                    return new AuthResult(false, Messages.AUTH_REGISTER_FAILED);
                } finally {
                    registeringUsernames.remove(username);
                    if (ipLockAcquired) securityManager.releaseIpRegistration(finalIp);
                }
            }).exceptionally(e -> {
                // 哈希失败：释放锁
                registeringUsernames.remove(username);
                if (ipLockAcquired) securityManager.releaseIpRegistration(finalIp);
                logger.log(Level.WARNING, "[AUTH] Password hash failed for " + username + ": " + e.getMessage(), e);
                return new AuthResult(false, Messages.AUTH_REGISTER_FAILED);
            });
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "[AUTH] Register failed for " + username + ": " + e.getMessage(), e);
            return new AuthResult(false, Messages.AUTH_REGISTER_FAILED);
        });
    }

    /** 注册预检结果（携带锁状态跨异步边界） */
    private record RegisterPreflight(boolean canProceed, String message, boolean ipLockAcquired) {}

    // ==================== 登录 ====================

    /**
     * 异步验证密码并标记玩家已登录。
     *
     * @param username 玩家名
     * @param password 明文密码
     * @param ip       登录 IP
     * @param uuid     玩家 UUID
     * @return CompletableFuture，完成后返回 AuthResult
     */
    public CompletableFuture<AuthResult> login(String username, String password,
                                                String ip, UUID uuid) {
        // 已登录玩家无需重复走完整流程（success=false 避免重复触发 onAuthLoginSuccess）
        if (sessionManager.isLoggedIn(uuid)) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.AUTH_LOGIN_ALREADY));
        }

        final String finalIp = (ip != null) ? ip : "unknown";

        // 异步查询账号 + 冷却检查（避免阻塞主线程）
        return CompletableFuture.supplyAsync(() -> {
            AuthAccount account = database.getAuthAccount(username);
            if (account == null) {
                return new LoginPreflightResult(null, Messages.AUTH_LOGIN_NOT_REGISTERED, false);
            }
            if (!sessionManager.beginProcessing(uuid)) {
                return new LoginPreflightResult(null, Messages.AUTH_LOGIN_PROCESSING, false);
            }
            // 安全检查：账户/IP 冷却期（命中冷却直接拒绝，不消耗密码验证线程）
            if (securityManager != null && config.isSecFailedLoginEnabled()) {
                LoginSecurityManager.CheckResult acctCooldown =
                        securityManager.checkAccountCooldown(username);
                if (!acctCooldown.canProceed()) {
                    sessionManager.endProcessing(uuid);
                    return new LoginPreflightResult(null,
                            Messages.get(Messages.AUTH_ACCOUNT_COOLDOWN,
                                    String.valueOf(acctCooldown.remainingSeconds())), false);
                }
                LoginSecurityManager.CheckResult ipCooldown =
                        securityManager.checkIpCooldown(finalIp);
                if (!ipCooldown.canProceed()) {
                    sessionManager.endProcessing(uuid);
                    return new LoginPreflightResult(null,
                            Messages.get(Messages.AUTH_IP_COOLDOWN,
                                    String.valueOf(ipCooldown.remainingSeconds())), false);
                }
            }
            return new LoginPreflightResult(account, null, true);
        }).thenCompose(preflight -> {
            if (!preflight.canProceed()) {
                return CompletableFuture.completedFuture(new AuthResult(false, preflight.message()));
            }
            AuthAccount account = preflight.account();

            // 异步执行 geo 与历史查询（避免阻塞主线程）：与密码验证并行执行
            final CompletableFuture<GeoContext> geoFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    LoginHistoryRecord lastLogin = historyManager != null
                            ? historyManager.getLastSuccessfulLogin(username) : null;
                    String pi = (lastLogin != null) ? lastLogin.ip() : null;
                    GeoInfo pg = geoService != null && pi != null ? geoService.search(pi) : null;
                    GeoInfo cg = geoService != null ? geoService.search(finalIp) : null;
                    return new GeoContext(pi, pg, cg);
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "[AUTH] Geo/history query failed for " + username + ": " + ex.getMessage(), ex);
                    return new GeoContext(null, null, null);
                }
            });

            return passwordHasher.verify(password, account.passwordHash())
                    .thenCombine(geoFuture, (match, ctx) -> {
                    String prevIp = ctx.prevIp();
                    GeoInfo prevGeo = ctx.prevGeo();
                    GeoInfo currGeo = ctx.currGeo();
                    try {
                        if (match) {
                            long now = System.currentTimeMillis();
                            String country = currGeo != null ? currGeo.country() : null;
                            String city = currGeo != null ? currGeo.city() : null;

                            // IP 变更时转移 IP 账号计数：先递增新 IP 再递减旧 IP，
                            // 若递增失败则不递减（避免计数偏低导致限制绕过）；
                            // 若递减失败则旧 IP 计数偏高（更严格，安全可接受）
                            String prevAccountIp = account.lastIp();
                            if (securityManager != null && prevAccountIp != null
                                    && !prevAccountIp.equals(finalIp)) {
                                securityManager.onRegisterSuccess(finalIp);
                                securityManager.onUnregister(prevAccountIp);
                            }

                            database.updateAuthLogin(username, now, finalIp);
                            sessionManager.setLoggedIn(uuid, username, finalIp);

                            // 登录成功后：重置失败计数
                            if (securityManager != null) {
                                securityManager.recordSuccessfulLogin(username, finalIp);
                            }
                            // 记录登录历史
                            if (historyManager != null && config.isSecLoginHistoryEnabled()) {
                                historyManager.recordLogin(username, finalIp, true, country, city);
                            }

                            // 检测 IP 变更与异地登录
                            List<String> warnings = new ArrayList<>();
                            boolean geoKick = false;
                            if (prevIp != null && !prevIp.equals(finalIp)
                                    && config.isSecIpChangeEnabled()) {
                                if (config.isSecIpChangeWarnPlayer()) {
                                    warnings.add(Messages.get(Messages.AUTH_IP_CHANGE_WARNING,
                                            prevIp, finalIp));
                                }
                                logger.info("[SEC] Player " + username + " IP changed: "
                                        + prevIp + " -> " + finalIp);
                            }
                            // 地理位置变化检测
                            if (geoService != null && geoService.isReady()
                                    && prevGeo != null && currGeo != null) {
                                geoKick = checkGeoChange(prevGeo, currGeo, warnings, username);
                            }

                            logger.info("[AUTH] Player " + username + " logged in successfully (IP=" + finalIp + ")");
                            return new AuthResult(true, Messages.AUTH_LOGIN_SUCCESS, geoKick, warnings);
                        } else {
                            // 密码错误：记录失败尝试
                            String failMsg = Messages.AUTH_LOGIN_FAILED;
                            boolean shouldKick = false;
                            if (securityManager != null && config.isSecFailedLoginEnabled()) {
                                LoginSecurityManager.FailureResult fr =
                                        securityManager.recordFailedAttempt(username, finalIp);
                                if (fr.shouldKick()) {
                                    shouldKick = true;
                                    failMsg = Messages.AUTH_LOGIN_TOO_MANY_FAILURES;
                                    logger.warning("[SEC] Player " + username + " kicked for too many failed attempts (IP=" + finalIp + ")");
                                } else if (fr.remainingAttempts() >= 0
                                        && fr.remainingAttempts() != Integer.MAX_VALUE) {
                                    failMsg = Messages.get(Messages.AUTH_ATTEMPTS_REMAINING,
                                            String.valueOf(fr.remainingAttempts()));
                                }
                            }
                            logger.warning("[AUTH] Player " + username + " login failed: wrong password (IP=" + finalIp + ")");
                            // 记录失败的登录历史
                            if (historyManager != null && config.isSecLoginHistoryEnabled()) {
                                String country = currGeo != null ? currGeo.country() : null;
                                String city = currGeo != null ? currGeo.city() : null;
                                historyManager.recordLogin(username, finalIp, false, country, city);
                            }
                            return new AuthResult(false, failMsg, shouldKick, Collections.emptyList());
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "[AUTH] Failed to update login for " + username + ": " + e.getMessage(), e);
                        return new AuthResult(false, Messages.AUTH_LOGIN_FAILED);
                    } finally {
                        sessionManager.endProcessing(uuid);
                    }
                })
                .exceptionally(e -> {
                    sessionManager.endProcessing(uuid);
                    logger.log(Level.WARNING, "[AUTH] Password verify failed for " + username + ": " + e.getMessage(), e);
                    return new AuthResult(false, Messages.AUTH_LOGIN_FAILED);
                });
        })
        .exceptionally(e -> {
            logger.log(Level.WARNING, "[AUTH] Login failed for " + username + ": " + e.getMessage(), e);
            return new AuthResult(false, Messages.AUTH_LOGIN_FAILED);
        });
    }

    /**
     * 检测跨国/跨城市变化，按配置行为加入警告列表。
     */
    private boolean checkGeoChange(GeoInfo prev, GeoInfo curr, List<String> warnings, String username) {
        // 跨国家检测
        if (prev.country() != null && curr.country() != null
                && !prev.country().equalsIgnoreCase(curr.country())) {
            String action = config.getSecGeoCrossCountryAction();
            String msg = Messages.get(Messages.AUTH_GEO_CROSS_COUNTRY,
                    formatGeo(prev), formatGeo(curr));
            return applyGeoAction(action, msg, warnings, username, "cross-country");
        } else if (prev.city() != null && curr.city() != null
                && !prev.city().equalsIgnoreCase(curr.city())) {
            // 同国家不同城市 → 跨城市检测
            String action = config.getSecGeoCrossCityAction();
            String msg = Messages.get(Messages.AUTH_GEO_CROSS_CITY,
                    formatGeo(prev), formatGeo(curr));
            return applyGeoAction(action, msg, warnings, username, "cross-city");
        }
        return false;
    }

    /** 应用地理位置变化的行为（warn / kick / require-login），返回 true 表示应踢出 */
    private boolean applyGeoAction(String action, String msg, List<String> warnings,
                                    String username, String type) {
        if (action == null) action = "warn";
        switch (action.toLowerCase()) {
            case "kick":
                // 将消息加入 warnings，并返回 true 表示应踢出
                warnings.add(msg);
                logger.warning("[SEC] Player " + username + " " + type
                        + " login detected, will be kicked");
                return true;
            case "require-login":
            case "warn":
            default:
                warnings.add(msg);
                logger.info("[SEC] Player " + username + " " + type + " login warning sent");
                return false;
        }
    }

    /** 格式化地理位置为可读字符串 */
    private static String formatGeo(GeoInfo g) {
        if (g == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        if (g.country() != null) sb.append(g.country());
        if (g.province() != null) sb.append("/").append(g.province());
        if (g.city() != null) sb.append("/").append(g.city());
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    /**
     * 检查会话恢复时的异地登录安全策略。
     *
     * @param username    玩家名
     * @param currentIp   当前 IP
     * @return 检查结果（allowResume=false 时阻止恢复）
     */
    public SessionResumeCheck checkSessionResumeSecurity(String username, String currentIp) {
        if (currentIp == null) {
            return SessionResumeCheck.allow();
        }
        if (geoService == null || !geoService.isReady()) {
            return SessionResumeCheck.allow();
        }
        if (historyManager == null || !config.isSecLoginHistoryEnabled()) {
            return SessionResumeCheck.allow();
        }
        try {
            LoginHistoryRecord last = historyManager.getLastSuccessfulLogin(username);
            if (last == null || last.ip() == null) {
                return SessionResumeCheck.allow();
            }
            GeoInfo prev = geoService.search(last.ip());
            GeoInfo curr = geoService.search(currentIp);
            if (prev == null || curr == null) {
                return SessionResumeCheck.allow();
            }
            List<String> warnings = new ArrayList<>();

            // 跨国家检测
            if (prev.country() != null && curr.country() != null
                    && !prev.country().equalsIgnoreCase(curr.country())) {
                String action = config.getSecGeoCrossCountryAction();
                String msg = Messages.get(Messages.AUTH_GEO_CROSS_COUNTRY,
                        formatGeo(prev), formatGeo(curr));
                return applySessionResumeAction(action, msg, username, "cross-country");
            }
            // 跨城市检测
            if (prev.city() != null && curr.city() != null
                    && !prev.city().equalsIgnoreCase(curr.city())) {
                String action = config.getSecGeoCrossCityAction();
                String msg = Messages.get(Messages.AUTH_GEO_CROSS_CITY,
                        formatGeo(prev), formatGeo(curr));
                return applySessionResumeAction(action, msg, username, "cross-city");
            }
            return SessionResumeCheck.allow();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[SEC] Failed to check session resume security for "
                    + username + ": " + e.getMessage(), e);
            return SessionResumeCheck.allow();
        }
    }

    /** 应用会话恢复时的异地登录行为 */
    private SessionResumeCheck applySessionResumeAction(String action, String msg,
                                                          String username, String type) {
        if (action == null) action = "warn";
        switch (action.toLowerCase()) {
            case "kick":
                logger.warning("[SEC] Player " + username + " " + type
                        + " login detected during session resume, will be kicked");
                return new SessionResumeCheck(false, true, List.of(msg));
            case "require-login":
                logger.info("[SEC] Player " + username + " " + type
                        + " login detected during session resume, requiring fresh login");
                return new SessionResumeCheck(false, false, List.of(msg));
            case "warn":
            default:
                logger.info("[SEC] Player " + username + " " + type
                        + " login warning sent during session resume");
                return new SessionResumeCheck(true, false, List.of(msg));
        }
    }

    // ==================== 修改密码 ====================

    /**
     * 异步修改密码：先验证旧密码，再哈希新密码并保存。
     *
     * @param username    玩家名
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return CompletableFuture，完成后返回 AuthResult
     */
    public CompletableFuture<AuthResult> changePassword(String username, String oldPassword,
                                                         String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.AUTH_REGISTER_PASSWORD_EMPTY));
        }
        int min = config.getAuthPasswordMin();
        int max = config.getAuthPasswordMax();
        if (newPassword.length() < min) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.get(Messages.AUTH_REGISTER_PASSWORD_TOO_SHORT, String.valueOf(min))));
        }
        if (newPassword.length() > max) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.get(Messages.AUTH_REGISTER_PASSWORD_TOO_LONG, String.valueOf(max))));
        }

        // 异步查库，避免阻塞调用线程（主线程）
        return CompletableFuture.supplyAsync(() -> database.getAuthAccount(username))
                .thenCompose(account -> {
                    if (account == null) {
                        return CompletableFuture.completedFuture(
                                new AuthResult(false, Messages.AUTH_LOGIN_NOT_REGISTERED));
                    }
                    return passwordHasher.verify(oldPassword, account.passwordHash())
                            .thenCompose(match -> {
                                if (!match) {
                                    return CompletableFuture.completedFuture(
                                            new AuthResult(false, Messages.AUTH_CHANGEPASSWORD_WRONG_OLD));
                                }
                                return passwordHasher.hash(newPassword)
                                        .thenApply(hash -> {
                                            try {
                                                database.updateAuthPassword(username, hash);
                                                logger.info("[AUTH] Player " + username + " changed password successfully");
                                                return new AuthResult(true, Messages.AUTH_CHANGEPASSWORD_SUCCESS);
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "[AUTH] Failed to update password for " + username + ": " + e.getMessage(), e);
                                                return new AuthResult(false, Messages.AUTH_CHANGEPASSWORD_FAILED);
                                            }
                                        });
                            });
                })
                .exceptionally(e -> {
                    logger.log(Level.WARNING, "[AUTH] Change password failed for " + username + ": " + e.getMessage(), e);
                    return new AuthResult(false, Messages.AUTH_CHANGEPASSWORD_FAILED);
                });
    }

    /**
     * 异步修改密码（带并发保护）：先验证旧密码，再哈希新密码并保存。
     *
     * @param username    玩家名
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @param uuid        玩家 UUID（用于并发保护，可为 null 表示不加保护）
     * @return CompletableFuture，完成后返回 AuthResult
     */
    public CompletableFuture<AuthResult> changePassword(String username, String oldPassword,
                                                         String newPassword, UUID uuid) {
        if (uuid != null && !sessionManager.beginProcessing(uuid)) {
            return CompletableFuture.completedFuture(
                    new AuthResult(false, Messages.AUTH_LOGIN_PROCESSING));
        }
        return changePassword(username, oldPassword, newPassword)
                .whenComplete((r, e) -> {
                    if (uuid != null) sessionManager.endProcessing(uuid);
                });
    }

    // ==================== 管理员操作 ====================

    /**
     * 删除玩家账号（管理员操作，同步）。
     *
     * @param username 玩家名
     * @return true 表示删除成功
     */
    public boolean unregister(String username) {
        try {
            AuthAccount account = database.getAuthAccount(username);
            boolean deleted = database.deleteAuthAccount(username);
            if (deleted) {
                sessionManager.removePersistentSession(username);
                if (securityManager != null && account != null && account.lastIp() != null) {
                    securityManager.onUnregister(account.lastIp());
                }
                logger.info("[AUTH] Account unregistered: " + username);
            }
            return deleted;
        } catch (Exception e) {
            logger.log(Level.WARNING, "[AUTH] Failed to unregister " + username + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取玩家账号信息（管理员操作，同步）。
     *
     * @param username 玩家名
     * @return AuthAccount，不存在返回 null
     */
    public AuthAccount getAccountInfo(String username) {
        return database.getAuthAccount(username);
    }

    // ==================== 会话管理 ====================

    /** 玩家是否已登录 */
    public boolean isLoggedIn(UUID uuid) {
        return sessionManager.isLoggedIn(uuid);
    }

    /** 直接标记玩家为已登录（供正版玩家使用，不走密码验证流程） */
    public void markLoggedIn(UUID uuid) {
        sessionManager.setLoggedIn(uuid);
    }

    /** 管理员注销账号时清除在线玩家登录状态 */
    public void logout(UUID uuid) {
        sessionManager.remove(uuid);
    }

    /** 注销玩家：清除在线状态与持久化会话（供踢出/封禁等需要彻底清除会话的场景使用） */
    public void logout(UUID uuid, String username) {
        sessionManager.remove(uuid);
        if (username != null) {
            sessionManager.removePersistentSession(username);
        }
    }

    /**
     * 尝试恢复会话（玩家重连时调用）。
     * 如果会话超时配置启用且玩家上次登录在超时时间内且 IP 相同，则恢复登录状态。
     *
     * @param username 玩家用户名
     * @param ip       当前 IP
     * @param uuid     玩家 UUID
     * @return true 表示会话有效并已恢复
     */
    public boolean tryResumeSession(String username, String ip, UUID uuid) {
        return sessionManager.tryResumeSession(username, ip, uuid, config.getSessionTimeout());
    }

    /** 获取持久会话的 IP（用于判断是否需要异地安全检查，避免 IP 不变时浪费 geo 查询） */
    public String getPersistentSessionIp(String username) {
        return sessionManager.getPersistentSessionIp(username);
    }

    /** 确认会话恢复：更新持久会话时间戳（安全检查通过后调用） */
    public void confirmSessionResume(String username, String ip) {
        sessionManager.confirmSessionResume(username, ip);
    }

    /** 玩家是否已注册 */
    public boolean isRegistered(String username) {
        return database.authAccountExists(username);
    }

    /** 玩家退出时清理会话；同时通知安全管理器移除 IP 在线计数 */
    public void onPlayerQuit(UUID uuid, String ip, String username) {
        sessionManager.remove(uuid);
        if (securityManager != null) {
            securityManager.onPlayerQuit(ip, username);
        }
    }

    /** 清除所有会话与安全状态（reload 时调用） */
    public void clearSessions() {
        sessionManager.clear();
        if (securityManager != null) {
            securityManager.clear();
        }
    }

    /** 清理过期的持久化会话（定时调用），避免内存泄漏 */
    public void cleanExpiredSessions() {
        int timeout = config != null ? config.getSessionTimeout() : 0;
        sessionManager.cleanExpiredSessions(timeout);
    }

    /**
     * 检查 IP 是否允许新玩家加入（基于单 IP 在线账号数限制）。
     * 由 AuthListener 在玩家加入时调用。
     *
     * @param ip 玩家 IP
     * @return true 表示允许加入
     */
    public boolean canJoin(String ip) {
        if (securityManager == null || !config.isSecIpLimitsEnabled()) {
            return true;
        }
        return securityManager.canJoin(ip);
    }

    /**
     * 通知安全管理器玩家已加入服务器（用于 IP 在线账号计数）。
     * 由 AuthListener 在玩家加入时调用。
     */
    public void onPlayerJoin(String ip, String username) {
        if (securityManager != null) {
            securityManager.onPlayerJoin(ip, username);
        }
    }

    // ==================== 关闭 ====================

    /** 关闭服务，释放资源 */
    public void shutdown() {
        passwordHasher.shutdown();
        sessionManager.clear();
    }

    // ==================== Getter ====================

    public AuthSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * 更新配置引用（reload 时调用）。
     *
     * @param newConfig 新的 AuthConfig
     */
    public void updateConfig(AuthConfig newConfig) {
        this.config = newConfig;
        if (securityManager != null) {
            securityManager.updateConfig(newConfig);
        }
        if (historyManager != null) {
            historyManager.updateConfig(newConfig);
        }
    }

    public LoginSecurityManager getSecurityManager() {
        return securityManager;
    }

    public LoginHistoryManager getHistoryManager() {
        return historyManager;
    }
}
