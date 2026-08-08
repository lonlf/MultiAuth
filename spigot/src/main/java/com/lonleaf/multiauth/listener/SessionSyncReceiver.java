package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.auth.SessionSyncProtocol;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Spigot 端跨服会话同步接收器。
 */
public class SessionSyncReceiver implements PluginMessageListener {

    private final MultiAuth plugin;
    private final AuthService authService;
    private final AuthState authState;
    private final Logger logger;
    /** 签名密钥提供者：实时从配置读取（reload 后立即生效），空则关闭验签 */
    private final Supplier<String> secretSupplier;

    public SessionSyncReceiver(MultiAuth plugin, AuthService authService, AuthState authState,
                               Logger logger, Supplier<String> secretSupplier) {
        this.plugin = plugin;
        this.authService = authService;
        this.authState = authState;
        this.logger = logger;
        this.secretSupplier = secretSupplier;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SessionSyncProtocol.CHANNEL_ID.equals(channel)) return;
        // 未配置密钥时拒绝一切会话同步消息（fail-closed）：直连后端场景（无 Velocity 拦截）下
        // 客户端可伪造该通道消息，空密钥等同无条件信任
        String secret = secretSupplier.get();
        if (secret == null || secret.isBlank()) {
            logger.warning(Messages.get(Messages.SESSION_SYNC_SECRET_MISSING));
            return;
        }
        try {
            SessionSyncProtocol.SessionSyncMessage msg = SessionSyncProtocol.parse(message, secret);
            UUID uuid = msg.uuid();
            String username = msg.username();

            if (SessionSyncProtocol.ACTION_LOGIN.equals(msg.action())) {
                // Velocity 同步登录状态：先确认玩家当前在线。跨服切换间隙/玩家已断开时
                // LOGIN_SYNC 可能迟到，若玩家已不在本服则不应写内存登录集或刷新持久会话
                //（残留登录标记会导致玩家离线期间处于"已登录"状态、重连时免认证），P1-11
                Player online = plugin.getServer().getPlayer(uuid);
                if (online == null) {
                    logger.fine(Messages.get(Messages.SESSION_SYNC_LOGIN, username, msg.ip(),
                            String.valueOf(msg.isPremium())) + " (skipped: player offline)");
                    return;
                }
                // 标记为已登录 + 恢复持久会话
                authService.markLoggedIn(uuid);
                authService.confirmSessionResume(username, msg.ip());
                // 取消超时踢出任务（如果已启动）
                if (authState != null) {
                    authState.cancelTimeout(uuid);
                }
                logger.fine(Messages.get(Messages.SESSION_SYNC_LOGIN, username, msg.ip(), String.valueOf(msg.isPremium())));
            } else if (SessionSyncProtocol.ACTION_LOGOUT.equals(msg.action())) {
                // Velocity 同步登出状态：清理登录状态
                authService.logout(uuid);
                if (authState != null) {
                    authState.clearPlayerState(uuid);
                }
                logger.fine(Messages.get(Messages.SESSION_SYNC_LOGOUT, username));
            }
        } catch (SessionSyncProtocol.InvalidSignatureException e) {
            // 验签失败：消息来源不可信（可能为直连后端伪造），拒绝处理并留痕
            logger.warning(Messages.get(Messages.SESSION_SYNC_BAD_SIGNATURE,
                    player != null ? player.getName() : "?",
                    player != null ? player.getUniqueId().toString() : "?"));
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.SESSION_SYNC_PARSE_ERROR, e.getMessage()));
        }
    }

    /**
     * 向 Velocity 上报玩家认证成功（离线玩家注册/登录成功后调用）。
     * Velocity 作为会话中心记录会话，并向当前服务器及后续跨服目标广播 LOGIN_SYNC，
     * 使已认证玩家在跨服切换后保持登录状态。
     */
    public void reportAuthSuccess(Player player) {
        String secret = secretSupplier.get();
        if (secret == null || secret.isBlank()) {
            return; // 未配置密钥 = 跨服会话同步关闭
        }
        String username = player.getName();
        UUID uuid = player.getUniqueId();
        String ip = getPlayerIp(player);
        byte[] data = SessionSyncProtocol.buildAuthUpMessage(username, uuid, ip, secret);
        player.sendPluginMessage(plugin, SessionSyncProtocol.CHANNEL_ID, data);
        logger.fine(Messages.get(Messages.SESSION_SYNC_AUTH_UP_LOG, username, uuid.toString(), ip));
    }

    /** 注册 Plugin Messaging 通道（入站接收 LOGIN_SYNC/LOGOUT_SYNC，出站发送 AUTH_UP 上报） */
    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID);
        logger.fine(Messages.get(Messages.SESSION_SYNC_RECEIVER_REGISTERED, SessionSyncProtocol.CHANNEL_ID));
    }

    /** 注销通道 */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID);
    }

    private static String getPlayerIp(Player player) {
        try {
            java.net.InetAddress addr = player.getAddress().getAddress();
            return addr != null ? addr.getHostAddress() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
