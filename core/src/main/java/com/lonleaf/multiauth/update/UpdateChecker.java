package com.lonleaf.multiauth.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lonleaf.multiauth.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 更新检查：通过 GitHub Releases API 查询最新版本。
 * 仅提示版本信息，不执行任何下载或自动更新。
 * 仅将 master 分支的 release 视为更新来源（dev 分支的 release 不参与判断）。
 */
public class UpdateChecker {

    /** 发布列表（按发布时间倒序），用于筛选 master 分支的 release；releases/latest 不区分分支，故用列表接口 */
    private static final String RELEASES_API = "https://api.github.com/repos/%s/releases?per_page=100";

    /** 更新来源仓库*/
    private static final String DEFAULT_REPOSITORY = "lonlf/MultiAuth";

    private final Logger logger;
    private final String repository;
    private final HttpClient client;
    private final AtomicBoolean checkRunning = new AtomicBoolean(false);
    /** 检查专用单线程池：避免占用 ForkJoinPool.commonPool（单核服务器仅 1 线程，被网络阻塞会拖慢其他异步任务） */
    private final ExecutorService executor;

    // 检查结果缓存（volatile：读取与检查在不同线程）
    private volatile UpdateInfo lastResult;

    public UpdateChecker(Logger logger) {
        this.logger = logger;
        this.repository = DEFAULT_REPOSITORY;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "multiauth-update-check");
            t.setDaemon(true);
            return t;
        });
    }

    /** 最新一次检查结果（尚未检查或检查失败时为 null） */
    public UpdateInfo getLastResult() {
        return lastResult;
    }

    public boolean isNewerAvailable() {
        return lastResult != null && lastResult.newer();
    }

    public String getRepository() {
        return repository;
    }

    /**
     * 异步检查一次；已有检查进行中则跳过。
     * @param currentVersion 当前插件版本
     */
    public void checkUpdateAsync(String currentVersion) {
        if (!checkRunning.compareAndSet(false, true)) {
            return;
        }
        executor.submit(() -> {
            try {
                checkUpdate(currentVersion);
            } catch (Exception e) {
                // 兜底：checkUpdate 内部已捕获，此处防未预期异常
                logger.log(Level.FINE, "Update check failure", e);
            } finally {
                checkRunning.set(false);
            }
        });
    }

    /** 关闭检查线程池（插件停用/关服时调用） */
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 同步检查并更新缓存。
     * 结果仅供展示：有新版本打 info，无更新打 fine；失败打一条 warning 告知无法检测（请自行检查），不影响任何功能。
     */
    public void checkUpdate(String currentVersion) {
        try {
            UpdateInfo fetched = fetchLatestRelease();
            if (fetched == null) {
                this.lastResult = null;
                // 生产一条 warning 告知无法检测更新（网络/GitHub 不可达等），请管理员自行检查
                logger.warning(Messages.get(Messages.UPDATE_CHECK_FAILED_LOG, "empty response"));
                return;
            }
            boolean newer = compareVersions(fetched.latestVersion(), currentVersion) > 0;
            this.lastResult = new UpdateInfo(fetched.latestVersion(), fetched.publishedAt(), fetched.releaseUrl(), newer);
            if (newer) {
                logger.info(Messages.get(Messages.UPDATE_AVAILABLE_LOG,
                        fetched.latestVersion(), currentVersion, fetched.releaseUrl()));
            } else {
                logger.fine(Messages.get(Messages.UPDATE_UP_TO_DATE_LOG, currentVersion));
            }
        } catch (Exception e) {
            // 检查失败静默降级：仅影响更新提示，不影响任何功能；生产打一条 warning 告知无法检测，请自行检查
            this.lastResult = null;
            logger.warning(Messages.get(Messages.UPDATE_CHECK_FAILED_LOG, e.getMessage()));
            logger.log(Level.FINE, "Update check failure", e);
        }
    }

    /** 调用 GitHub Releases API 获取 master 分支最新的 release 信息（dev 分支不参与更新判断） */
    private UpdateInfo fetchLatestRelease() throws IOException, InterruptedException {
        String url = String.format(RELEASES_API, repository);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MultiAuth-UpdateChecker")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            // 非 200（404/403/5xx）：丢弃错误响应体后抛异常
            try (InputStream err = response.body()) {
                err.transferTo(java.io.OutputStream.nullOutputStream());
            }
            throw new IOException("HTTP " + response.statusCode() + " from GitHub API");
        }
        try (InputStream in = response.body()) {
            // 遍历发布列表（按发布时间倒序），仅取 target_commitish 为 master 的正式 release，
            // dev 分支的 release 不参与更新判断；列表第一项即 master 分支最新的 release
            JsonArray releases = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : releases) {
                JsonObject json = el.getAsJsonObject();
                if (json.get("draft") != null && json.get("draft").getAsBoolean()) {
                    continue;
                }
                String target = json.has("target_commitish") ? json.get("target_commitish").getAsString() : "";
                if (!"master".equals(target)) {
                    continue;
                }
                if (!json.has("tag_name")) {
                    continue;
                }
                String tag = json.get("tag_name").getAsString();
                String publishedAt = json.has("published_at") ? json.get("published_at").getAsString() : "";
                String htmlUrl = json.has("html_url") ? json.get("html_url").getAsString() : "";
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                return new UpdateInfo(tag, publishedAt, htmlUrl, false);
            }
            return null; // master 分支尚无 release
        }
    }

    /**
     * 比较两个版本号：去 v 前缀后按数字段逐段比较，位数不足补 0；
     * 主版本数字段相等时，正式版视为更新于任何带预发布标记的版本。
     * 预发布后缀后的数字不参与主版本比较（如 1.0.0-rc.1 应视为旧于 1.0.0）。
     * 例：v1.10.0 > v1.9.0；v1.0.0 > v1.0.0-beta.1。
     */
    public static int compareVersions(String a, String b) {
        VersionParts pa = parseParts(stripPrefix(a));
        VersionParts pb = parseParts(stripPrefix(b));
        List<Integer> an = pa.major();
        List<Integer> bn = pb.major();
        int len = Math.max(an.size(), bn.size());
        for (int i = 0; i < len; i++) {
            int x = i < an.size() ? an.get(i) : 0;
            int y = i < bn.size() ? bn.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        if (pa.pre() != pb.pre()) {
            return pa.pre() ? -1 : 1;
        }
        return 0;
    }

    private record VersionParts(List<Integer> major, boolean pre) {}

    /** 解析版本号：'-' 前的数字段为主版本；'-' 后含预发布标记（snapshot/alpha/beta/rc/pre）时 pre=true */
    private static VersionParts parseParts(String v) {
        String main = v;
        boolean pre = false;
        int dash = v.indexOf('-');
        if (dash >= 0) {
            main = v.substring(0, dash);
            pre = hasPreRelease(v);
        }
        List<Integer> major = new ArrayList<>();
        for (String seg : main.split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            try {
                major.add(Integer.parseInt(seg));
            } catch (NumberFormatException ignored) {
                // 非数字段：跳过
            }
        }
        return new VersionParts(major, pre);
    }

    private static String stripPrefix(String v) {
        String s = v == null ? "" : v.trim();
        while (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1);
        }
        return s;
    }

    private static boolean hasPreRelease(String v) {
        String lower = v.toLowerCase();
        return lower.contains("snapshot") || lower.contains("alpha")
                || lower.contains("beta") || lower.contains("rc")
                || lower.contains("pre");
    }
}
