package com.lonleaf.multiauth.auth;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.lonleaf.multiauth.Messages;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Velocity 端跨服会话同步管理器。
 */
public class SessionSyncManager {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(SessionSyncProtocol.CHANNEL_ID);

    /** 会话记录兜底清理周期（秒） */
    private static final long CLEANUP_INTERVAL_SECONDS = 60L;

    /** 会话 TTL：超过该时长未被刷新且玩家已不在代理上时移除（兜底 DisconnectEvent 丢失场景） */
    private static final long SESSION_TTL_MS = 5 * 60 * 1000L;

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final boolean enabled;
    private final boolean debug;
    /** 签名密钥提供者：实时从配置读取（reload 后立即生效），空则关闭签名 */
    private final Supplier<String> secretSupplier;

    /** 内存会话表：UUID → SessionInfo（玩家通过 Velocity 认证后记录） */
    private final ConcurrentMap<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();

    private ScheduledTask cleanupTask;

    public SessionSyncManager(Object plugin, ProxyServer server, Logger logger,
                              boolean enabled, boolean debug, Supplier<String> secretSupplier) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.enabled = enabled;
        this.debug = debug;
        this.secretSupplier = secretSupplier;
        // 安全拦截必须始终注册：即使会话同步功能被禁用（密钥留空），也要拦截客户端伪造的
        // multiauth:session 消息转发到后端，否则盗版客户端可伪造登录状态绕过后端认证限制
        server.getChannelRegistrar().register(CHANNEL);
        server.getEventManager().register(plugin, this);
        if (enabled) {
            scheduleCleanup();
            debug(Messages.get(Messages.SESSION_SYNC_ENABLED, SessionSyncProtocol.CHANNEL_ID));
            if (secretSupplier.get() == null || secretSupplier.get().isBlank()) {
                logger.warn(Messages.get(Messages.SESSION_SYNC_SECRET_MISSING));
            }
        }
    }

    /**
     * 拦截客户端伪造的会话同步消息：客户端（模组）发送的 multiauth:session 消息默认会被
     * Velocity 转发到后端（文档明确警告：转发后玩家可伪装成代理向后端发送消息），
     * 必须在此标记 handled() 阻止转发，否则盗版客户端可绕过注册/登录限制。
     * 后端无法通过 player 参数区分来源（Velocity 主动发送也是经玩家连接），故在代理层拦截。
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        if (event.getSource() instanceof Player) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            logger.warn(Messages.get(Messages.SESSION_SYNC_REJECT_CLIENT));
        }
    }

    /** debug 日志：仅 debug=true 时输出，用 info 级别绕过 SLF4J 默认级别限制 */
    private void debug(String msg) {
        if (debug) {
            logger.info("[DEBUG] " + msg);
        }
    }

    /** 玩家通过 Velocity 认证后记录会话（仅内存，消息在 ServerConnectedEvent 时发送） */
    public void markLoggedIn(Player player, boolean isPremium) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String ip = player.getRemoteAddress() != null
                ? player.getRemoteAddress().getAddress().getHostAddress() : "?";
        long now = System.currentTimeMillis();
        sessions.put(uuid, new SessionInfo(username, ip, isPremium, now, now));
    }

    /** 玩家连接到后端服务器时同步会话（首次连接和跨服转移都触发） */
    public void syncSessionToServer(Player player, RegisteredServer targetServer) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        // 跨服转移视为活跃活动，刷新 lastSeen 防止 TTL 误删（在线玩家的记录必须保留）
        SessionInfo info = sessions.computeIfPresent(uuid, (k, v) -> v.touch(System.currentTimeMillis()));
        if (info == null) return;
        sendLoginSyncToServer(targetServer, info.username(), uuid, info.ip(), info.isPremium(), info.loginTime());
    }

    /** 玩家断开连接时清理会话，并通知所有后端服务器 */
    public void removeSession(Player player) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        SessionInfo info = sessions.remove(uuid);
        if (info == null) return;
        sendLogoutSync(player, info.username(), uuid);
    }

    /**
     * 兜底清理任务：定期移除"超过 TTL 且玩家已不在代理上"的会话记录。
     * 正常断开由 DisconnectEvent 清理，此处仅兜底 DisconnectEvent 丢失的极端情况；
     * 在线玩家的记录即使超过 TTL 也保留，避免跨服转移时同步丢失。
     */
    private void scheduleCleanup() {
        cleanupTask = server.getScheduler().buildTask(plugin, () -> {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> {
                SessionInfo info = entry.getValue();
                if (now - info.lastSeen() < SESSION_TTL_MS) return false;
                if (server.getPlayer(entry.getKey()).isPresent()) return false;
                debug(Messages.get(Messages.SESSION_SYNC_TTL_REMOVED, info.username(), entry.getKey().toString()));
                return true;
            });
        }).delay(CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS)
          .repeat(CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS)
          .schedule();
    }

    private void sendLoginSyncToServer(RegisteredServer server, String username, UUID uuid, String ip, boolean isPremium, long loginTime) {
        byte[] data = SessionSyncProtocol.buildLoginMessage(username, uuid, ip, isPremium, loginTime, secretSupplier.get());
        server.sendPluginMessage(CHANNEL, data);
    }

    private void sendLogoutSync(Player player, String username, UUID uuid) {
        byte[] data = SessionSyncProtocol.buildLogoutMessage(username, uuid, secretSupplier.get());
        player.getCurrentServer().ifPresent(conn -> conn.sendPluginMessage(CHANNEL, data));
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        server.getChannelRegistrar().unregister(CHANNEL);
        server.getEventManager().unregisterListener(plugin, this);
        sessions.clear();
    }

    private record SessionInfo(String username, String ip, boolean isPremium, long loginTime, long lastSeen) {
        SessionInfo touch(long now) {
            return new SessionInfo(username, ip, isPremium, loginTime, now);
        }
    }
}
