package com.lonleaf.multiauth.auth;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.db.DatabaseManager;
import com.lonleaf.multiauth.db.IpStatsRecord;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 登录安全管理器。
 */
public class LoginSecurityManager {

    private final ConcurrentHashMap<String, AttemptTracker> accountAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AttemptTracker> ipAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> ipOnlineAccounts = new ConcurrentHashMap<>();
    /** 正在注册中的 IP 集合（防止同 IP 并发注册绕过账号数量限制的 TOCTOU 竞态） */
    private final Set<String> registeringIps = ConcurrentHashMap.newKeySet();
    // volatile：reload 时由 AuthService.updateConfig 更新引用
    private volatile AuthConfig config;
    private final DatabaseManager database;
    private final Logger logger;

    public LoginSecurityManager(AuthConfig config, DatabaseManager database, Logger logger) {
        this.config = config;
        this.database = database;
        this.logger = logger;
    }

    /** 更新配置引用（reload 时由 AuthService 调用） */
    public void updateConfig(AuthConfig newConfig) {
        this.config = newConfig;
    }

    /** 冷却检查结果 */
    public record CheckResult(boolean canProceed, long remainingSeconds) {}

    /** 失败尝试记录结果 */
    public record FailureResult(boolean shouldKick, int remainingAttempts,
                                 boolean accountCooldown, boolean ipCooldown) {}

    // ==================== 冷却检查 ====================

    /** 检查账户是否处于冷却期 */
    public CheckResult checkAccountCooldown(String username) {
        AttemptTracker tracker = accountAttempts.get(username);
        if (tracker == null || !tracker.isInCooldown()) {
            return new CheckResult(true, 0);
        }
        return new CheckResult(false, tracker.remainingCooldownSeconds());
    }

    /** 检查 IP 是否处于冷却期 */
    public CheckResult checkIpCooldown(String ip) {
        AttemptTracker tracker = ipAttempts.get(ip);
        if (tracker == null || !tracker.isInCooldown()) {
            return new CheckResult(true, 0);
        }
        return new CheckResult(false, tracker.remainingCooldownSeconds());
    }

    // ==================== 失败尝试 ====================

    /**
     * 记录一次失败的登录尝试，递增账户与 IP 计数器，并在达到阈值时设置冷却。
     *
     * @param username 玩家名
     * @param ip       登录 IP
     * @return 失败结果，包含是否应踢出、剩余尝试次数、冷却触发标志
     */
    public FailureResult recordFailedAttempt(String username, String ip) {
        int accountMax = config.getSecAccountMaxAttempts();
        int accountCooldown = config.getSecAccountCooldown();
        int ipMax = config.getSecIpMaxAttempts();
        int ipCooldown = config.getSecIpCooldown();

        AtomicBoolean accountCooldownTriggered = new AtomicBoolean(false);
        AtomicBoolean ipCooldownTriggered = new AtomicBoolean(false);
        AtomicInteger accountCount = new AtomicInteger(0);

        if (username != null) {
            accountAttempts.compute(username, (k, prev) -> {
                AttemptTracker cur = (prev == null) ? AttemptTracker.reset() : prev;
                cur = cur.increment(accountCooldown);
                if (cur.reachedThreshold(accountMax)) {
                    cur = cur.withCooldown(accountCooldown);
                    accountCooldownTriggered.set(true);
                }
                accountCount.set(cur.count());
                return cur;
            });
        }

        if (ip != null) {
            ipAttempts.compute(ip, (k, prev) -> {
                AttemptTracker cur = (prev == null) ? AttemptTracker.reset() : prev;
                cur = cur.increment(ipCooldown);
                if (cur.reachedThreshold(ipMax)) {
                    cur = cur.withCooldown(ipCooldown);
                    ipCooldownTriggered.set(true);
                }
                return cur;
            });
        }

        boolean shouldKick = accountCooldownTriggered.get() || ipCooldownTriggered.get();
        int remaining = Math.max(0, accountMax - accountCount.get());
        return new FailureResult(shouldKick, remaining,
                accountCooldownTriggered.get(), ipCooldownTriggered.get());
    }

    /** 登录成功后按配置重置账户与 IP 计数器 */
    public void recordSuccessfulLogin(String username, String ip) {
        if (config.isSecAccountResetOnSuccess() && username != null) {
            accountAttempts.remove(username);
        }
        if (config.isSecIpResetOnSuccess() && ip != null) {
            ipAttempts.remove(ip);
        }
    }

    // ==================== IP 账号数量限制 ====================

    /**
     * 尝试获取 IP 注册锁（防止同 IP 并发注册绕过账号数量限制的 TOCTOU 竞态）。
     *
     * @return true = 获取成功（注册完成后必须调用 releaseIpRegistration）；false = 该 IP 已有注册在进行中
     */
    public boolean tryAcquireIpRegistration(String ip) {
        if (ip == null) return true;
        return registeringIps.add(ip);
    }

    /** 释放 IP 注册锁（注册成功/失败/异常后必须调用） */
    public void releaseIpRegistration(String ip) {
        if (ip != null) registeringIps.remove(ip);
    }

    /** 该 IP 是否还可以注册新账号 */
    public boolean canRegister(String ip) {
        if (!config.isSecIpLimitsEnabled()) {
            return true;
        }
        int max = config.getSecMaxAccountsPerIp();
        if (max <= 0) {
            return true;
        }
        if (ip == null) {
            return true;
        }
        try {
            IpStatsRecord stats = database.getIpStats(ip);
            if (stats == null) {
                return true;
            }
            return stats.accountCount() < max;
        } catch (Exception e) {
            logger.log(Level.WARNING, Messages.get(Messages.SEC_QUERY_IP_STATS_FAILED, ip, e.getMessage()), e);
            return false;
        }
    }

    /** 注册成功后递增 IP 账号计数 */
    public void onRegisterSuccess(String ip) {
        if (ip == null) {
            return;
        }
        try {
            database.incrementIpAccountCount(ip);
        } catch (Exception e) {
            logger.log(Level.WARNING, Messages.get(Messages.SEC_INCREMENT_IP_ACCOUNT_FAILED, ip, e.getMessage()), e);
        }
    }

    /** 注销账号后递减 IP 账号计数 */
    public void onUnregister(String ip) {
        if (ip == null) {
            return;
        }
        try {
            database.decrementIpAccountCount(ip);
        } catch (Exception e) {
            logger.log(Level.WARNING, Messages.get(Messages.SEC_DECREMENT_IP_ACCOUNT_FAILED, ip, e.getMessage()), e);
        }
    }

    // ==================== IP 在线限制 ====================

    /** 该 IP 是否还可以有账号加入服务器 */
    public boolean canJoin(String ip) {
        if (!config.isSecIpLimitsEnabled()) {
            return true;
        }
        int max = config.getSecMaxOnlinePerIp();
        if (max <= 0) {
            return true;
        }
        if (ip == null) {
            return true;
        }
        Set<String> accounts = ipOnlineAccounts.get(ip);
        if (accounts == null) {
            return true;
        }
        return accounts.size() < max;
    }

    /** 玩家加入服务器时记录在线账号 */
    public void onPlayerJoin(String ip, String username) {
        if (ip == null || username == null) {
            return;
        }
        ipOnlineAccounts.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet()).add(username);
    }

    /** 玩家退出服务器时移除在线账号 */
    public void onPlayerQuit(String ip, String username) {
        if (ip == null || username == null) {
            return;
        }
        ipOnlineAccounts.computeIfPresent(ip, (k, set) -> {
            set.remove(username);
            return set.isEmpty() ? null : set;
        });
    }

    // ==================== 清理 ====================

    /** 清空所有内存数据（reload 时调用） */
    public void clear() {
        accountAttempts.clear();
        ipAttempts.clear();
        ipOnlineAccounts.clear();
        registeringIps.clear();
    }
}
