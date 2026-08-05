package com.lonleaf.multiauth.db;

/**
 * 单 IP 统计记录。
 *
 * @param ip              IP 地址
 * @param accountCount    该 IP 已注册的账号数量
 * @param failedAttempts  失败尝试次数
 * @param lastFailureTime 最后一次失败时间戳（毫秒）
 * @param cooldownUntil   冷却截止时间戳（毫秒）
 */
public record IpStatsRecord(String ip, int accountCount, int failedAttempts,
                            long lastFailureTime, long cooldownUntil) {
}
