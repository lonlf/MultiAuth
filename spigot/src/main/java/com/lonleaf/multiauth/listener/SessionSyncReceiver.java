package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.auth.SessionSyncProtocol;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Spigot 端跨服会话同步接收器。
 */
public class SessionSyncReceiver implements PluginMessageListener {

    private final MultiAuth plugin;
    private final AuthService authService;
    private final AuthState authState;
    private final Logger logger;

    public SessionSyncReceiver(MultiAuth plugin, AuthService authService, AuthState authState, Logger logger) {
        this.plugin = plugin;
        this.authService = authService;
        this.authState = authState;
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SessionSyncProtocol.CHANNEL_ID.equals(channel)) return;
        try {
            SessionSyncProtocol.SessionSyncMessage msg = SessionSyncProtocol.parse(message);
            UUID uuid = msg.uuid();
            String username = msg.username();

            if (SessionSyncProtocol.ACTION_LOGIN.equals(msg.action())) {
                // Velocity 同步登录状态：标记为已登录 + 恢复持久会话
                authService.markLoggedIn(uuid);
                authService.confirmSessionResume(username, msg.ip());
                // 取消超时踢出任务（如果已启动）
                if (authState != null) {
                    authState.cancelTimeout(uuid);
                }
                logger.info("[AUTH] " + username + " 通过 Velocity 跨服会话同步登录（IP: " + msg.ip()
                        + ", 正版: " + msg.isPremium() + "）");
            } else if (SessionSyncProtocol.ACTION_LOGOUT.equals(msg.action())) {
                // Velocity 同步登出状态：清理登录状态
                authService.logout(uuid);
                if (authState != null) {
                    authState.clearPlayerState(uuid);
                }
                logger.fine("[AUTH] " + username + " 通过 Velocity 跨服会话同步登出");
            }
        } catch (Exception e) {
            logger.warning("[AUTH] 解析跨服会话同步消息失败: " + e.getMessage());
        }
    }

    /** 注册 Plugin Messaging 通道 */
    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID, this);
        logger.info("[MultiAuth] 跨服会话同步接收器已注册（通道: " + SessionSyncProtocol.CHANNEL_ID + "）");
    }

    /** 注销通道 */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID, this);
    }
}
