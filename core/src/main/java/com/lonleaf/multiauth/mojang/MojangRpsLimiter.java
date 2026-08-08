package com.lonleaf.multiauth.mojang;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mojang API 按用户名速率限制（RPS）：每个用户名每秒最多发起 N 次请求。
 * 与并发信号量（Semaphore，管并发峰值）分层互补：RPS 管单位时间总速率，
 * 防止同一用户名反复重连/脚本循环触发 Mojang 429（premiumCache 已缓存重复结果，此处兜底真实请求）。
 */
public class MojangRpsLimiter {

    /** 固定窗口长度（毫秒） */
    private static final long WINDOW_MS = 1000;

    /** 每窗口（1 秒）每用户名最大请求数；<=0 表示不限制 */
    private final int limit;

    /** 窗口计数器：用户名（小写）→ 当前窗口内请求计数，窗口结束后自动过期重置 */
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW_MS, TimeUnit.MILLISECONDS)
            .maximumSize(10_000)
            .build();

    public MojangRpsLimiter(int limit) {
        this.limit = Math.max(0, limit);
    }

    /**
     * 尝试占用一次请求配额。
     *
     * @param key 用户名（内部统一小写规范化，与 H1 账号键规范一致）
     * @return true 允许发起请求；false 超限拒绝（调用方须 fail-closed）
     */
    public boolean tryAcquire(String key) {
        if (limit <= 0) return true;
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        AtomicInteger counter = counters.get(k, x -> new AtomicInteger());
        return counter.incrementAndGet() <= limit;
    }
}
