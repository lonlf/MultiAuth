package com.lonleaf.multiauth.auth;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Velocity 端跨服会话同步管理器。
 */
public class SessionSyncManager {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(SessionSyncProtocol.CHANNEL_ID);

    private final ProxyServer server;
    private final Logger logger;
    private final boolean enabled;

    /** 内存会话表：UUID → SessionInfo（玩家通过 Velocity 认证后记录） */
    private final ConcurrentMap<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();

    public SessionSyncManager(ProxyServer server, Logger logger, boolean enabled) {
        this.server = server;
        this.logger = logger;
        this.enabled = enabled;
        if (enabled) {
            server.getChannelRegistrar().register(CHANNEL);
            logger.info("[MultiAuth] 跨服会话同步已启用（通道: {}）", SessionSyncProtocol.CHANNEL_ID);
        }
    }

    /** 玩家通过 Velocity 认证后记录会话（仅内存，消息在 ServerConnectedEvent 时发送） */
    public void markLoggedIn(Player player, boolean isPremium) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String ip = player.getRemoteAddress() != null
                ? player.getRemoteAddress().getAddress().getHostAddress() : "?";
        long loginTime = System.currentTimeMillis();
        sessions.put(uuid, new SessionInfo(username, ip, isPremium, loginTime));
    }

    /** 玩家连接到后端服务器时同步会话（首次连接和跨服转移都触发） */
    public void syncSessionToServer(Player player, RegisteredServer targetServer) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        SessionInfo info = sessions.get(uuid);
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

    private void sendLoginSyncToServer(RegisteredServer server, String username, UUID uuid, String ip, boolean isPremium, long loginTime) {
        byte[] data = SessionSyncProtocol.buildLoginMessage(username, uuid, ip, isPremium, loginTime);
        server.sendPluginMessage(CHANNEL, data);
    }

    private void sendLogoutSync(Player player, String username, UUID uuid) {
        byte[] data = SessionSyncProtocol.buildLogoutMessage(username, uuid);
        player.getCurrentServer().ifPresent(conn -> conn.sendPluginMessage(CHANNEL, data));
    }

    public void shutdown() {
        if (enabled) {
            server.getChannelRegistrar().unregister(CHANNEL);
            sessions.clear();
        }
    }

    private record SessionInfo(String username, String ip, boolean isPremium, long loginTime) {}
}
