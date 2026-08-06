package com.lonleaf.multiauth.mojang;

import com.lonleaf.multiauth.Messages;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MojangApiService {

    private final Logger logger;

    private static final String OFFICIAL_API_BASE = "https://api.mojang.com";
    private static final String OFFICIAL_API_PATH = "/users/profiles/minecraft/";

    /** 全局速率限制：最多 10 个并发 API 请求，避免触发 Mojang 429 */
    private static final int MAX_CONCURRENT_API_REQUESTS = 10;
    private static final long API_ACQUIRE_TIMEOUT_MS = 5000;

    /** 备用 API 连续失败次数阈值，超过则标记为不可用 */
    private static final int FAILURE_THRESHOLD = 3;

    /** 备用 API 失败冷却时间（毫秒） */
    private static final long FAILURE_COOLDOWN_MS = 30_000; // 30秒

    private final HttpClient httpClient;
    private final List<String> fallbackApiUrls;
    private final Cache<String, Optional<UUID>> premiumCache;
    private final Semaphore apiRateLimiter;

    /** 正版结果缓存时间（分钟） */
    private static final long PREMIUM_CACHE_MINUTES = 10;
    /** 非正版结果缓存时间（分钟）— 缩短以便新注册账号能较快被识别 */
    private static final long NON_PREMIUM_CACHE_MINUTES = 2;

    /** 当前正在使用的备用 API 索引（-1 表示使用官方 API） */
    private final AtomicInteger activeFallbackIndex = new AtomicInteger(-1);

    /** 记录每个备用 API 的连续失败次数（原子数组，避免并发下丢失计数） */
    private final java.util.concurrent.atomic.AtomicIntegerArray fallbackFailures;

    /** 记录每个备用 API 最后一次失败的时间戳（原子数组，保证并发可见性） */
    private final java.util.concurrent.atomic.AtomicLongArray fallbackLastFailureTime;

    /** 官方 API 连续失败次数 */
    private final AtomicInteger officialFailures = new AtomicInteger(0);

    /** 官方 API 最后一次失败的时间戳（冷却计时基准） */
    private volatile long officialLastFailureTime = 0;

    /** 所有 API 是否全部不可用 */
    private volatile boolean allApisDown = false;

    /**
     * 宕机快速失败后，恢复探测的最小间隔（毫秒）。
     */
    private static final long RECOVERY_PROBE_INTERVAL_MS = 10_000;

    /** 下次允许执行恢复探测的时间戳（仅 allApisDown=true 时有效）；恢复成功后重置为 0 */
    private final java.util.concurrent.atomic.AtomicLong nextRecoveryProbeTime = new java.util.concurrent.atomic.AtomicLong(0);

    public MojangApiService(List<String> fallbackApiUrls, Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.fallbackApiUrls = (fallbackApiUrls != null && !fallbackApiUrls.isEmpty())
                ? List.copyOf(fallbackApiUrls)
                : List.of();
        this.fallbackFailures = new java.util.concurrent.atomic.AtomicIntegerArray(this.fallbackApiUrls.size());
        this.fallbackLastFailureTime = new java.util.concurrent.atomic.AtomicLongArray(this.fallbackApiUrls.size());
        this.premiumCache = Caffeine.newBuilder()
                .expireAfter(new com.github.benmanes.caffeine.cache.Expiry<String, Optional<UUID>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<UUID> value, long currentTime) {
                        // 正版缓存 10 分钟，非正版缓存 2 分钟（便于新注册账号较快被识别）
                        return value.isPresent()
                                ? TimeUnit.MINUTES.toNanos(PREMIUM_CACHE_MINUTES)
                                : TimeUnit.MINUTES.toNanos(NON_PREMIUM_CACHE_MINUTES);
                    }
                    @Override
                    public long expireAfterUpdate(String key, Optional<UUID> value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }
                    @Override
                    public long expireAfterRead(String key, Optional<UUID> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(1000)
                .build();
        this.apiRateLimiter = new Semaphore(MAX_CONCURRENT_API_REQUESTS, true);
    }

    /** 关闭底层 HttpClient，释放资源 */
    public void close() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    /**
     * 检查用户名是否为正版账号（仅在玩家连接时按需调用）。
     *
     * @param username 用户名
     * @return 正版 UUID（Optional.empty() 表示非正版玩家）
     * @throws IOException 所有 API 都不可用时抛出
     */
    public Optional<UUID> checkPremium(String username) throws IOException {
        Optional<UUID> cached = premiumCache.getIfPresent(username.toLowerCase());
        if (cached != null) {
            return cached;
        }

        // 宕机快速失败：确认全部 API 宕机期间（非探测窗口）直接抛异常，由 AuthFlow 按宕机策略决策
        // （仅放行有离线历史记录的玩家）。避免每个登录都串行等待完整 API 失败链，登录延迟由
        // "5s × 源数" 降为接近 0（仅承担探测任务的单个登录需等待一次完整 failover）。
        if (allApisDown) {
            long now = System.currentTimeMillis();
            long nextProbe = nextRecoveryProbeTime.get();
            if (now < nextProbe) {
                logger.fine(Messages.get(Messages.API_FAST_FAIL_DOWNTIME, String.valueOf((nextProbe - now) / 1000)));
                throw new IOException("All Mojang APIs are unavailable (fast-fail during downtime)");
            }
            // 本线程承担恢复探测：CAS 抢占探测窗口，防止并发登录重复探测；
            // 探测走下方完整 failover，成功则自动清除 allApisDown 并恢复。
            if (!nextRecoveryProbeTime.compareAndSet(nextProbe, now + RECOVERY_PROBE_INTERVAL_MS)) {
                logger.fine(Messages.get(Messages.API_PROBE_IN_PROGRESS));
                throw new IOException("All Mojang APIs are unavailable (recovery probe in progress)");
            }
            logger.info(Messages.get(Messages.API_PROBE_START, String.valueOf(RECOVERY_PROBE_INTERVAL_MS / 1000)));
        }

        try {
            if (!apiRateLimiter.tryAcquire(API_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warning(Messages.API_RATE_LIMIT_REACHED);
                throw new IOException("Mojang API rate limit reached");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Thread interrupted while waiting for rate limiter", e);
        }

        try {
            // 每次都尝试 API（不因 allApisDown 提前返回），以便 API 恢复后自动检测到
            // 官方 API 未冷却 → 从官方开始尝试；冷却中 → 从上次成功的备用 API 开始
            int startIndex = isOfficialInCooldown() ? activeFallbackIndex.get() : -1;

            Optional<UUID> result = checkWithFailover(username, startIndex);
            premiumCache.put(username.toLowerCase(), result);
            return result;
        } catch (AllApisDownException e) {
            allApisDown = true;
            // 宕机开始：先冷却一个探测间隔再允许恢复探测，避免宕机刚确认时每个登录都立即承担探测
            nextRecoveryProbeTime.set(System.currentTimeMillis() + RECOVERY_PROBE_INTERVAL_MS);
            logger.severe(Messages.API_ALL_DOWN);
            throw new IOException("All Mojang APIs are currently unavailable", e);
        } finally {
            apiRateLimiter.release();
        }
    }

    /**
     * 带故障转移的 API 检查逻辑。
     * 从指定索引开始尝试，依次降级到下一个 API。
     */
    private Optional<UUID> checkWithFailover(String username, int startIndex) throws IOException, AllApisDownException {
        // 优先尝试官方 API：仅在冷却期内跳过，失败后冷却结束会自动恢复尝试，
        // 不会永久降级（修复：官方 API 一次失败后永不重试导致"所有 API 均不可达"的 bug）
        if (startIndex < 0 && !isOfficialInCooldown()) {
            try {
                long t0 = System.currentTimeMillis();
                Optional<UUID> result = checkOfficial(username);
                // 成功：重置官方失败计数与整体不可用标志
                resetOfficialFailure();
                if (allApisDown) {
                    allApisDown = false;
                    nextRecoveryProbeTime.set(0);
                    logger.info(Messages.get(Messages.API_RECOVERED, Messages.API_SOURCE_OFFICIAL));
                }
                // 过程细节：API 调用结果与耗时（仅 debug 可见）
                logger.fine(Messages.get(Messages.API_OFFICIAL_CHECK_COMPLETE, username,
                        result.map(u -> "PREMIUM " + u).orElse("NOT_PREMIUM"),
                        String.valueOf(System.currentTimeMillis() - t0)));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Thread interrupted while checking official API", e);
            } catch (IOException e) {
                markOfficialApiFailed(e);
                // 官方失败：继续尝试备用 API
            }
        } else if (startIndex < 0 && isOfficialInCooldown()) {
            long remainingSec = Math.max(0, (FAILURE_COOLDOWN_MS - (System.currentTimeMillis() - officialLastFailureTime)) / 1000);
            logger.warning(Messages.get(Messages.API_OFFICIAL_COOLDOWN, String.valueOf(remainingSec)));
        }

        // 依次尝试备用 API
        int triedCount = 0;
        for (int i = 0; i < fallbackApiUrls.size(); i++) {
            // 从当前活动索引开始循环
            int idx = (i + Math.max(0, startIndex)) % fallbackApiUrls.size();

            if (isFallbackInCooldown(idx)) {
                triedCount++;
                continue;
            }

            try {
                long t0 = System.currentTimeMillis();
                Optional<UUID> result = checkFallback(username, idx);
                // 成功
                resetFallbackFailure(idx);
                activeFallbackIndex.set(idx);
                if (allApisDown) {
                    allApisDown = false;
                    nextRecoveryProbeTime.set(0);
                    logger.info(Messages.get(Messages.API_RECOVERED,
                            Messages.get(Messages.API_SOURCE_FALLBACK, String.valueOf(idx + 1))));
                }
                // 过程细节：API 调用结果与耗时（仅 debug 可见）
                logger.fine(Messages.get(Messages.API_FALLBACK_CHECK_COMPLETE, String.valueOf(idx + 1), username,
                        result.map(u -> "PREMIUM " + u).orElse("NOT_PREMIUM"),
                        String.valueOf(System.currentTimeMillis() - t0)));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Thread interrupted while checking fallback API #" + (idx + 1), e);
            } catch (IOException e) {
                markFallbackFailed(idx, e);
                triedCount++;

                // 如果已经尝试了所有 API
                if (triedCount >= fallbackApiUrls.size()) {
                    // 检查是否所有 API 都在冷却中
                    boolean allInCooldown = true;
                    for (int j = 0; j < fallbackApiUrls.size(); j++) {
                        if (!isFallbackInCooldown(j)) {
                            allInCooldown = false;
                            break;
                        }
                    }
                    if (allInCooldown) {
                        logger.severe(Messages.API_ALL_DOWN);
                        throw new AllApisDownException("All APIs in cooldown");
                    }
                }
            }
        }

        // 所有 API 都失败了（或没有配置备用 API 且官方在冷却期）
        throw new AllApisDownException("All APIs failed");
    }

    /**
     * 检查官方 API
     */
    private Optional<UUID> checkOfficial(String username) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String url = OFFICIAL_API_BASE + OFFICIAL_API_PATH + encoded;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 200) {
            Optional<UUID> result = parseUuid(response.body());
            if (result == null) {
                throw new IOException("Failed to parse official API response");
            }
            return result;
        }
        if (status == 204 || status == 404) {
            return Optional.empty();
        }
        throw new IOException("Unexpected official API status: " + status);
    }

    /**
     * 检查指定索引的备用 API
     */
    private Optional<UUID> checkFallback(String username, int index) throws IOException, InterruptedException {
        String urlTemplate = fallbackApiUrls.get(index);
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String url = urlTemplate.replace("{username}", encoded);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 200) {
            Optional<UUID> result = parseUuid(response.body());
            if (result == null) {
                throw new IOException("Failed to parse fallback API #" + (index + 1) + " response");
            }
            return result;
        }
        if (status == 204 || status == 404) {
            return Optional.empty();
        }
        throw new IOException("Unexpected fallback API #" + (index + 1) + " status: " + status);
    }

    /**
     * 标记官方 API 失败（达到阈值后进入冷却期，冷却结束自动恢复尝试）。
     */
    private void markOfficialApiFailed(IOException e) {
        officialFailures.incrementAndGet();
        officialLastFailureTime = System.currentTimeMillis();
        logger.warning(Messages.get(Messages.API_OFFICIAL_UNAVAILABLE, e.getMessage()));

        if (officialFailures.get() >= FAILURE_THRESHOLD) {
            logger.severe(Messages.get(Messages.API_HIGH_FAILURE_RATE, String.valueOf(FAILURE_THRESHOLD)));
        }
    }

    /** 官方 API 成功：重置失败计数 */
    private void resetOfficialFailure() {
        if (officialFailures.get() > 0) {
            logger.info(Messages.get(Messages.API_OFFICIAL_AVAILABLE, Messages.API_STATUS_RECOVERED, "200"));
        }
        officialFailures.set(0);
        officialLastFailureTime = 0;
    }

    /** 官方 API 是否处于冷却期（连续失败达到阈值且未过冷却时间） */
    private boolean isOfficialInCooldown() {
        if (officialFailures.get() < FAILURE_THRESHOLD) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - officialLastFailureTime;
        return elapsed < FAILURE_COOLDOWN_MS;
    }

    /**
     * 标记备用 API 失败
     */
    private void markFallbackFailed(int index, IOException e) {
        fallbackFailures.incrementAndGet(index);
        fallbackLastFailureTime.set(index, System.currentTimeMillis());

        logger.warning(Messages.get(Messages.API_FALLBACK_UNAVAILABLE, String.valueOf(index + 1), e.getMessage()));

        if (fallbackFailures.get(index) >= FAILURE_THRESHOLD) {
            logger.severe(Messages.get(Messages.API_HIGH_FAILURE_RATE, String.valueOf(FAILURE_THRESHOLD)));
        }
    }

    /**
     * 重置备用 API 失败计数
     */
    private void resetFallbackFailure(int index) {
        if (fallbackFailures.get(index) > 0) {
            logger.info(Messages.get(Messages.API_FALLBACK_AVAILABLE, String.valueOf(index + 1),
                    Messages.API_STATUS_RECOVERED, "200"));
        }
        fallbackFailures.set(index, 0);
        fallbackLastFailureTime.set(index, 0);
    }

    /**
     * 检查备用 API 是否在冷却期
     */
    private boolean isFallbackInCooldown(int index) {
        if (fallbackFailures.get(index) < FAILURE_THRESHOLD) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - fallbackLastFailureTime.get(index);
        return elapsed < FAILURE_COOLDOWN_MS;
    }

    // ==================== 工具方法 ====================

    /**
     * 解析 Mojang API 返回的 JSON 为 UUID。
     * @return Optional.of(uuid) 表示正版；Optional.empty() 表示非正版（404/204）；
     *         null 表示解析失败（不缓存，调用方应抛 IOException）
     */
    private Optional<UUID> parseUuid(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String id = obj.get("id").getAsString();
            return Optional.of(formatMojangUuid(id));
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.API_PARSE_FAILED, e.getMessage(), json));
            return null;
        }
    }

    public static UUID formatMojangUuid(String hexId) {
        String formatted = hexId.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(formatted);
    }

    // ==================== 内部类 ====================

    /**
     * 所有 API 都不可用时抛出的异常
     */
    private static class AllApisDownException extends Exception {
        AllApisDownException(String message) {
            super(message);
        }
    }
}
