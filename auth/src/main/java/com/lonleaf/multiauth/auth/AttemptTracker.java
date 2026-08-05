package com.lonleaf.multiauth.auth;

/**
 * 失败尝试追踪器。
 */
public record AttemptTracker(int count, long lastTime, long cooldownUntil) {

    /** 是否在冷却期内 */
    public boolean isInCooldown() {
        return cooldownUntil > System.currentTimeMillis();
    }

    /** 剩余冷却秒数 */
    public long remainingCooldownSeconds() {
        long remaining = (cooldownUntil - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    /** 递增失败次数，如果上次失败已过 cooldown 则重置 */
    public AttemptTracker increment(int cooldownSeconds) {
        long now = System.currentTimeMillis();
        // 仅当 cooldownSeconds > 0 时才检查重置窗口，避免 cooldown=0 时计数永远重置
        if (cooldownSeconds > 0 && lastTime > 0 && (now - lastTime) > cooldownSeconds * 1000L) {
            return new AttemptTracker(1, now, 0);
        }
        return new AttemptTracker(count + 1, now, cooldownUntil);
    }

    /** 达到阈值后设置冷却 */
    public AttemptTracker withCooldown(int cooldownSeconds) {
        return new AttemptTracker(count, lastTime, System.currentTimeMillis() + cooldownSeconds * 1000L);
    }

    /** 重置 */
    public static AttemptTracker reset() {
        return new AttemptTracker(0, 0, 0);
    }

    /** 是否达到阈值 */
    public boolean reachedThreshold(int maxAttempts) {
        return count >= maxAttempts;
    }
}
