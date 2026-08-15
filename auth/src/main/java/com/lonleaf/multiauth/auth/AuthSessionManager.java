package com.lonleaf.multiauth.auth;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthSessionManager {

    private final Set<UUID> loggedInPlayers = ConcurrentHashMap.newKeySet();

    private final Set<UUID> processingPlayers = ConcurrentHashMap.newKeySet();

    /** 持久化会话信息：玩家用户名 → SessionInfo（退出后保留，用于重连免登录） */
    private final Map<String, SessionInfo> persistentSessions = new ConcurrentHashMap<>();

    /** 规范化用户名：统一小写（与 DAO 层一致，避免大小写变体产生重复会话记录） */
    private static String normName(String username) {
        return username == null ? null : username.toLowerCase(Locale.ROOT);
    }

    /**
     * 会话信息记录。
     *
     * @param username   玩家用户名
     * @param loginTime  登录成功时间戳（毫秒）
     * @param ip         登录时 IP
     */
    public record SessionInfo(String username, long loginTime, String ip) {}

    public void setLoggedIn(UUID uuid) {
        loggedInPlayers.add(uuid);
        processingPlayers.remove(uuid);
    }

    /**
     * 标记玩家已登录，同时记录持久化会话信息。
     *
     * @param uuid     玩家 UUID
     * @param username 玩家用户名
     * @param ip       登录 IP
     */
    public void setLoggedIn(UUID uuid, String username, String ip) {
        loggedInPlayers.add(uuid);
        processingPlayers.remove(uuid);
        persistentSessions.put(normName(username), new SessionInfo(username, System.currentTimeMillis(), ip));
    }

    /**
     * 标记玩家正在验证中（防止并发登录命令）。
     *
     * @param uuid 玩家 UUID
     * @return 如果玩家不在验证中则占用并返回 true，已在验证中返回 false
     */
    public boolean beginProcessing(UUID uuid) {
        return processingPlayers.add(uuid);
    }

    public void endProcessing(UUID uuid) {
        processingPlayers.remove(uuid);
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedInPlayers.contains(uuid);
    }

    /**
     * 尝试恢复会话：检查玩家上次登录是否在会话超时时间内且 IP 相同。
     *
     * @param username     玩家用户名
     * @param ip           当前 IP
     * @param uuid         玩家 UUID
     * @param timeoutMinutes 会话超时时间（分钟），0=禁用
     * @return true 表示会话有效并已恢复（玩家标记为已登录，但未更新时间戳）
     */
    public boolean tryResumeSession(String username, String ip, UUID uuid, int timeoutMinutes) {
        if (timeoutMinutes <= 0) {
            return false; // 会话超时禁用
        }
        SessionInfo info = persistentSessions.get(normName(username));
        if (info == null) {
            return false; // 无历史会话
        }
        // 检查 IP 是否相同
        if (!Objects.equals(info.ip(), ip)) {
            return false; // IP 不匹配
        }
        // 检查是否在超时时间内
        long elapsed = System.currentTimeMillis() - info.loginTime();
        if (elapsed > timeoutMinutes * 60_000L) {
            persistentSessions.remove(normName(username)); // 超时，清除会话
            return false;
        }
        // 会话有效，恢复登录状态（不更新时间戳，由调用方在安全检查通过后调用 confirmSessionResume）
        loggedInPlayers.add(uuid);
        return true;
    }

    /**
     * 获取持久会话的 IP（不检查超时，仅用于判断是否需要异地安全检查）。
     *
     * @param username 玩家用户名
     * @return 持久会话 IP，无会话返回 null
     */
    public String getPersistentSessionIp(String username) {
        SessionInfo info = persistentSessions.get(normName(username));
        return info != null ? info.ip() : null;
    }

    /**
     * 确认会话恢复：更新持久会话时间戳。
     *
     * @param username 玩家用户名
     * @param ip       当前 IP
     */
    public void confirmSessionResume(String username, String ip) {
        persistentSessions.put(normName(username), new SessionInfo(username, System.currentTimeMillis(), ip));
    }

    /**
     * 移除玩家登录状态（玩家退出时调用）。
     * 注意：不清除持久化会话信息，以便重连时恢复。
     *
     * @param uuid 玩家 UUID
     */
    public void remove(UUID uuid) {
        loggedInPlayers.remove(uuid);
        processingPlayers.remove(uuid);
    }

    /**
     * 清除指定玩家的持久化会话（管理员注销账号时调用）。
     *
     * @param username 玩家用户名
     */
    public void removePersistentSession(String username) {
        persistentSessions.remove(normName(username));
    }

    /**
     * 清理已超时的持久化会话（定时调用），避免内存泄漏。
     *
     * @param timeoutMinutes 会话超时时间（分钟），0 或负数表示禁用
     */
    public void cleanExpiredSessions(int timeoutMinutes) {
        if (timeoutMinutes <= 0) return;
        long threshold = System.currentTimeMillis() - timeoutMinutes * 60_000L;
        persistentSessions.entrySet().removeIf(e -> e.getValue().loginTime() < threshold);
    }

    /** 清除所有登录状态与持久化会话（插件停用/关服时调用） */
    public void clear() {
        loggedInPlayers.clear();
        processingPlayers.clear();
        persistentSessions.clear();
    }

    /**
     * 清除验证中的瞬时状态（reload 时调用）：仅清理正在登录/注册验证中的占位，
     * 保留已登录玩家与持久化会话，避免 reload 静默清会话导致在线玩家被当作未登录
     * 限制/踢出，或强制已认证玩家重新登录（#8）。
     */
    public void clearProcessing() {
        processingPlayers.clear();
    }
}
