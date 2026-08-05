package com.lonleaf.multiauth.geo;

import com.lonleaf.multiauth.config.AuthConfig;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 ip2region 的离线 IP 地理位置查询服务。
 */
public class IpGeoService {

    private static final String V4_DOWNLOAD_URL =
            "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb";
    private static final String V6_DOWNLOAD_URL =
            "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v6.xdb";

    private static final int DOWNLOAD_MAX_RETRIES = 3;
    private static final long DOWNLOAD_RETRY_INTERVAL_MS = 5000L;

    private volatile Ip2Region ip2Region;
    private volatile boolean ready;
    private final boolean v4Enabled;
    private final boolean v6Enabled;
    private final boolean skipLan;
    private final Logger logger;

    public IpGeoService(AuthConfig config, Path dataDirectory, Logger logger) {
        this.logger = logger;
        this.v4Enabled = config.isSecGeoV4Enabled();
        this.v6Enabled = config.isSecGeoV6Enabled();
        this.skipLan = config.isSecGeoSkipLan();

        if (!config.isSecGeoEnabled()) {
            this.ready = false;
            return;
        }

        if (!v4Enabled && !v6Enabled) {
            logger.warning("[GEO] Both v4 and v6 query are disabled, geo service disabled");
            this.ready = false;
            return;
        }

        Path xdbDir = dataDirectory.resolve(config.getSecGeoXdbDir());
        String cachePolicy = config.getSecGeoCachePolicy();
        int searchers = config.getSecGeoSearchers();
        boolean autoDownload = config.isSecGeoAutoDownload();

        Path v4Path = v4Enabled ? xdbDir.resolve(config.getSecGeoV4File()) : null;
        Path v6Path = v6Enabled ? xdbDir.resolve(config.getSecGeoV6File()) : null;

        boolean v4Ready = !v4Enabled || Files.exists(v4Path);
        boolean v6Ready = !v6Enabled || Files.exists(v6Path);

        if (v4Ready && v6Ready) {
            try {
                initIp2Region(v4Path, v6Path, cachePolicy, searchers);
                this.ready = true;
                logger.info("[GEO] ip2region service initialized successfully");
            } catch (Exception e) {
                logger.log(Level.WARNING, "[GEO] Failed to initialize ip2region: " + e.getMessage(), e);
                this.ready = false;
            }
        } else if (autoDownload) {
            logger.info("[GEO] xdb file(s) missing, starting async download...");
            final Path finalV4Path = v4Path;
            final Path finalV6Path = v6Path;
            final boolean finalV4Ready = v4Ready;
            final boolean finalV6Ready = v6Ready;
            final String finalCachePolicy = cachePolicy;
            final int finalSearchers = searchers;
            CompletableFuture.runAsync(() -> {
                boolean v4Ok = finalV4Ready || downloadWithRetries(V4_DOWNLOAD_URL, finalV4Path);
                boolean v6Ok = finalV6Ready || !v6Enabled || downloadWithRetries(V6_DOWNLOAD_URL, finalV6Path);
                if (v4Ok && v6Ok) {
                    try {
                        initIp2Region(finalV4Path, finalV6Path, finalCachePolicy, finalSearchers);
                        this.ready = true;
                        logger.info("[GEO] ip2region service initialized successfully after download");
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "[GEO] Failed to initialize ip2region after download: " + e.getMessage(), e);
                    }
                } else {
                    logger.warning("[GEO] Failed to download xdb files, geo service remains disabled");
                }
            });
        } else {
            logger.warning("[GEO] xdb file(s) missing and auto-download disabled, geo service disabled");
            this.ready = false;
        }
    }

    /** 服务是否就绪可供查询 */
    public boolean isReady() {
        return ready;
    }

    /**
     * 查询 IP 地理位置信息。
     *
     * @param ip IPv4 或 IPv6 地址
     * @return {@link GeoInfo}，查询失败或未就绪时返回 null
     */
    public GeoInfo search(String ip) {
        Ip2Region local = ip2Region;
        if (!ready || local == null) {
            return null;
        }
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        if (skipLan && isLanIp(ip)) {
            return null;
        }

        boolean isV6 = ip.contains(":");
        if (isV6 && !v6Enabled) {
            logger.warning("[GEO] Player connected via IPv6 but v6 query is disabled, skipping");
            return null;
        }
        if (!isV6 && !v4Enabled) {
            return null;
        }

        try {
            String region = local.search(ip);
            if (region == null || region.isEmpty()) {
                return null;
            }
            return parseRegion(region);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[GEO] Failed to query IP " + ip + ": " + e.getMessage(), e);
            return null;
        }
    }

    /** 关闭服务，释放底层资源 */
    public void close() {
        ready = false;
        if (ip2Region != null) {
            try {
                ip2Region.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "[GEO] Failed to close ip2region: " + e.getMessage(), e);
            } finally {
                ip2Region = null;
            }
        }
    }

    private void initIp2Region(Path v4Path, Path v6Path, String cachePolicy, int searchers) throws Exception {
        int policy = resolveCachePolicy(cachePolicy);
        Config v4Config = null;
        Config v6Config = null;
        if (v4Enabled && v4Path != null && Files.exists(v4Path)) {
            v4Config = Config.custom()
                    .setCachePolicy(policy)
                    .setSearchers(searchers)
                    .setXdbPath(v4Path.toAbsolutePath().toString())
                    .asV4();
        }
        if (v6Enabled && v6Path != null && Files.exists(v6Path)) {
            v6Config = Config.custom()
                    .setCachePolicy(policy)
                    .setSearchers(searchers)
                    .setXdbPath(v6Path.toAbsolutePath().toString())
                    .asV6();
        }
        if (v4Config == null && v6Config == null) {
            throw new IllegalStateException("No xdb file available for ip2region");
        }
        this.ip2Region = Ip2Region.create(v4Config, v6Config);
    }

    private int resolveCachePolicy(String policy) {
        if (policy == null) {
            return Config.VIndexCache;
        }
        switch (policy.toLowerCase()) {
            case "file":
            case "nocache":
                return Config.NoCache;
            case "buffer":
            case "buffercache":
                return Config.BufferCache;
            case "vindex":
            case "vindexcache":
            default:
                return Config.VIndexCache;
        }
    }

    private GeoInfo parseRegion(String region) {
        // format: country|province|city|isp|countryCode
        String[] parts = region.split("\\|", -1);
        String country = parts.length > 0 ? normalize(parts[0]) : null;
        String province = parts.length > 1 ? normalize(parts[1]) : null;
        String city = parts.length > 2 ? normalize(parts[2]) : null;
        String countryCode = parts.length > 4 ? normalize(parts[4]) : null;
        return new GeoInfo(country, province, city, countryCode);
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty() || "0".equals(value)) {
            return null;
        }
        return value;
    }

    private boolean isLanIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (ip.contains(":")) {
            // IPv6 loopback / link-local / unique local address
            String lower = ip.toLowerCase();
            return lower.equals("::1")
                    || lower.startsWith("fe80:")
                    || lower.startsWith("fc")
                    || lower.startsWith("fd");
        }
        if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private boolean downloadWithRetries(String url, Path target) {
        for (int attempt = 1; attempt <= DOWNLOAD_MAX_RETRIES; attempt++) {
            try {
                downloadFile(url, target);
                logger.info("[GEO] Downloaded xdb file: " + target.getFileName());
                return true;
            } catch (Exception e) {
                logger.warning("[GEO] Download attempt " + attempt + "/" + DOWNLOAD_MAX_RETRIES
                        + " failed for " + target.getFileName() + ": " + e.getMessage());
                if (attempt < DOWNLOAD_MAX_RETRIES) {
                    try {
                        Thread.sleep(DOWNLOAD_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            try (InputStream in = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
                 OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
