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
                // Velocity 同步登录状态：标记为已登录 + 恢复持久会话
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

    /** 注册 Plugin Messaging 通道 */
    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID, this);
        logger.fine(Messages.get(Messages.SESSION_SYNC_RECEIVER_REGISTERED, SessionSyncProtocol.CHANNEL_ID));
    }

    /** 注销通道 */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, SessionSyncProtocol.CHANNEL_ID, this);
    }
}
