package com.lonleaf.multiauth.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lonleaf.multiauth.Messages;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MojangSessionService {

    private static final Logger logger = Logger.getLogger(MojangSessionService.class.getName());

    private static final String SESSION_SERVER = "https://sessionserver.mojang.com";

    /**
     * hasJoined 并发控制：与 checkPremium 一致的 Semaphore(10)，
     * 防止大量玩家同时登录时触发 Mojang 速率限制（429）。
     */
    private static final java.util.concurrent.Semaphore HAS_JOINED_SEMAPHORE = new java.util.concurrent.Semaphore(10);

    private final HttpClient httpClient;

    public MojangSessionService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 关闭底层 HttpClient，释放资源 */
    public void close() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    public HasJoinedResult hasJoinedDetailed(String username, String serverId) {
        try {
            // tryAcquire 带超时：并发槽位长时间无法获取时按宕机处理，避免无限阻塞验证线程（#6）
            if (!HAS_JOINED_SEMAPHORE.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return new HasJoinedResult(HasJoinedResult.Status.MOJANG_UNREACHABLE, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HasJoinedResult(HasJoinedResult.Status.MOJANG_UNREACHABLE, null);
        }
        try {
            return doHasJoined(username, serverId);
        } finally {
            HAS_JOINED_SEMAPHORE.release();
        }
    }

    private HasJoinedResult doHasJoined(String username, String serverId) {
        try {
            String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
            String url = SESSION_SERVER + "/session/minecraft/hasJoined?username=" + encodedUsername + "&serverId=" + serverId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                Optional<MojangProfile> profile = parseProfile(response.body());
                if (profile.isPresent()) {
                    return new HasJoinedResult(HasJoinedResult.Status.SUCCESS, profile.get());
                }
                return new HasJoinedResult(HasJoinedResult.Status.NOT_PREMIUM, null);
            }
            if (status == 204 || status == 404) {
                return new HasJoinedResult(HasJoinedResult.Status.NOT_PREMIUM, null);
            }
            return new HasJoinedResult(HasJoinedResult.Status.MOJANG_UNREACHABLE, null);
        } catch (IOException e) {
            return new HasJoinedResult(HasJoinedResult.Status.MOJANG_UNREACHABLE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HasJoinedResult(HasJoinedResult.Status.MOJANG_UNREACHABLE, null);
        }
    }

    private Optional<MojangProfile> parseProfile(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String id = obj.get("id").getAsString();
            String name = obj.get("name").getAsString();

            UUID uuid = formatMojangUuid(id);

            java.util.List<MojangProfile.Property> properties = new java.util.ArrayList<>();
            if (obj.has("properties")) {
                for (com.google.gson.JsonElement propEl : obj.getAsJsonArray("properties")) {
                    JsonObject propObj = propEl.getAsJsonObject();
                    String propName = propObj.get("name").getAsString();
                    String propValue = propObj.get("value").getAsString();
                    String propSignature = propObj.has("signature") ? propObj.get("signature").getAsString() : null;
                    properties.add(new MojangProfile.Property(propName, propValue, propSignature));
                }
            }

            return Optional.of(new MojangProfile(uuid, name, properties));
        } catch (Exception e) {
            logger.log(Level.WARNING, Messages.get(Messages.MOJANG_MALFORMED_HASJOINED_WARN, e.getMessage()), e);
            return Optional.empty();
        }
    }

    private UUID formatMojangUuid(String mojangId) {
        String formatted = mojangId.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(formatted);
    }

    public record MojangProfile(UUID uuid, String username, java.util.List<Property> properties) {
        public MojangProfile {
            properties = properties != null ? properties : java.util.List.of();
        }

        public record Property(String name, String value, String signature) {
        }
    }

    public record HasJoinedResult(Status status, MojangProfile profile) {
        public enum Status { SUCCESS, NOT_PREMIUM, MOJANG_UNREACHABLE }
    }
}
