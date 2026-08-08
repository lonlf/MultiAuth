package com.lonleaf.multiauth.auth;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.lonleaf.multiauth.Messages;
import org.slf4j.Logger;

import java.util.Map;
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

    /** LOGIN_SYNC 延迟补发时间（秒）：跨服切换时后端可能尚未完成加入流程导致首条消息丢失 */
    private static final long SYNC_RETRY_DELAY_SECONDS = 1L;

    private final Object plugin;
    private final ProxyServer server;
    private final Logger logger;
    private volatile boolean enabled;
    private final boolean debug;
    /** 签名密钥提供者：实时从配置读取（reload 后立即生效），空则关闭签名 */
    private final Supplier<String> secretSupplier;

    /** 内存会话表：UUID → SessionInfo（玩家通过 Velocity 认证后记录） */
    private final ConcurrentMap<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();

    /** 已登出墓碑：绑定旧连接的 Player 引用，防止后端在途的 AUTH_UP 乱序到达
     *  （Disconnect 先到、AUTH_UP 后到）复活已清理的会话；同时仅丢弃旧连接本身的
     *  AUTH_UP，玩家快速重连后的新连接（Player 实例不同）不被墓碑误吞（#12） */
    private final ConcurrentMap<UUID, Player> loggedOutUuids = new ConcurrentHashMap<>();

    /** 延迟补发任务表：玩家断开时取消挂起补发，避免对已断开连接无意义发送 */
    private final ConcurrentMap<UUID, ScheduledTask> retryTasks = new ConcurrentHashMap<>();

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
     * reload 后动态开关会话同步（enabled 由密钥是否非空决定）。
     * 关闭时立即清空会话表并取消兜底清理任务；开启时启动兜底清理任务。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            cancelCleanup();
            retryTasks.values().forEach(ScheduledTask::cancel);
            retryTasks.clear();
            sessions.clear();
            loggedOutUuids.clear();
        } else if (cleanupTask == null) {
            scheduleCleanup();
            debug(Messages.get(Messages.SESSION_SYNC_ENABLED, SessionSyncProtocol.CHANNEL_ID));
        }
    }

    /**
     * 拦截客户端伪造的会话同步消息：客户端（模组）发送的 multiauth:session 消息默认会被
     * Velocity 转发到后端（文档明确警告：转发后玩家可伪装成代理向后端发送消息），
     * 必须在此标记 handled() 阻止转发，否则盗版客户端可绕过注册/登录限制。
     * 后端无法通过 player 参数区分来源（Velocity 主动发送也是经玩家连接），故在代理层拦截。
     * 同时处理后端 → 客户端方向的 AUTH_UP 上报（离线玩家注册/登录成功后由 Spigot 上报），
     * 并阻止其继续转发到客户端。
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        if (event.getSource() instanceof Player) {
            // 客户端 → 后端方向：伪造消息，阻止转发
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            logger.warn(Messages.get(Messages.SESSION_SYNC_REJECT_CLIENT));
            return;
        }
        if (event.getSource() instanceof ServerConnection) {
            // 后端 → 客户端方向：处理后端上报的认证成功消息，阻止其到达客户端
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (!enabled) return;
            String secret = secretSupplier.get();
            if (secret == null || secret.isBlank()) {
                logger.warn(Messages.get(Messages.SESSION_SYNC_SECRET_MISSING));
                return;
            }
            try {
                SessionSyncProtocol.SessionSyncMessage msg = SessionSyncProtocol.parse(event.getData(), secret);
                if (SessionSyncProtocol.ACTION_AUTH_UP.equals(msg.action())) {
                    Player player = ((ServerConnection) event.getSource()).getPlayer();
                    if (player != null) {
                        handleAuthUp(player, msg.username(), msg.uuid(), msg.ip());
                    }
                }
            } catch (SessionSyncProtocol.InvalidSignatureException e) {
                logger.warn(Messages.get(Messages.SESSION_SYNC_BAD_SIGNATURE,
                        event.getSource() instanceof ServerConnection
                                ? ((ServerConnection) event.getSource()).getPlayer().getUsername() : "?",
                        event.getSource() instanceof ServerConnection
                                ? ((ServerConnection) event.getSource()).getPlayer().getUniqueId().toString() : "?"));
            } catch (Exception e) {
                logger.warn(Messages.get(Messages.SESSION_SYNC_PARSE_ERROR, e.getMessage()));
            }
        }
    }

    /** debug 日志：仅 debug=true 时输出，用 info 级别绕过 SLF4J 默认级别限制 */
    private void debug(String msg) {
        if (debug) {
            logger.info("[DEBUG] " + msg);
        }
    }

    /**
     * 玩家通过 Velocity 认证后记录会话（仅正版玩家，消息在 ServerConnectedEvent 时发送）。
     * 离线玩家未经过密码/注册认证，不在代理层记录会话，避免未注册/未登录玩家被跨服放行；
     * 其认证成功后由后端通过 AUTH_UP 上报（见 handleAuthUp）。
     */
    public void markLoggedIn(Player player, boolean isPremium) {
        if (!enabled) return;
        if (!isPremium) {
            debug(Messages.get(Messages.SESSION_SYNC_SKIP_OFFLINE_DEBUG, player.getUsername()));
            return;
        }
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String ip = player.getRemoteAddress() != null
                ? player.getRemoteAddress().getAddress().getHostAddress() : "?";
        long now = System.currentTimeMillis();
        sessions.put(uuid, new SessionInfo(username, ip, true, now, now));
    }

    /** 玩家连接到后端服务器时同步会话（首次连接和跨服转移都触发） */
    public void syncSessionToServer(Player player) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        // 跨服转移视为活跃活动，刷新 lastSeen 防止 TTL 误删（在线玩家的记录必须保留）
        SessionInfo info = sessions.computeIfPresent(uuid, (k, v) -> v.touch(System.currentTimeMillis()));
        if (info == null) return;
        sendLoginSyncToPlayer(player, info.username(), uuid, info.ip(), info.isPremium(), info.loginTime());
        // 跨服切换时后端可能尚未完成加入流程导致首条 LOGIN_SYNC 丢失，延迟补发一次，
        // 确保目标服务器在 PlayerJoin 后仍能收到同步（幂等，重复发送无副作用）。
        // 任务登记到 retryTasks：玩家断开时取消，避免对已断开连接无意义发送。
        ScheduledTask previous = retryTasks.put(uuid, server.getScheduler().buildTask(plugin, () -> {
            retryTasks.remove(uuid);
            if (!enabled || !player.isActive()) return;
            SessionInfo current = sessions.get(uuid);
            if (current == null) return;
            sendLoginSyncToPlayer(player, current.username(), uuid, current.ip(),
                    current.isPremium(), current.loginTime());
        }).delay(SYNC_RETRY_DELAY_SECONDS, TimeUnit.SECONDS).schedule());
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * 处理后端上报的认证成功消息（AUTH_UP）：记录离线玩家会话并立即同步到当前服务器。
     * 已有会话（正版玩家/重复上报）仅刷新 lastSeen，保持原有正版标记不变。
     * 玩家已断开，或上报来自已登出旧连接（墓碑绑定的 Player 实例）时丢弃，
     * 防止乱序 AUTH_UP 复活已清理的会话；玩家快速重连后的新连接上报不受墓碑影响（#12）。
     */
    private void handleAuthUp(Player player, String username, UUID uuid, String ip) {
        if (!player.isActive()) {
            debug(Messages.get(Messages.SESSION_SYNC_AUTH_UP_LOG, username, uuid.toString(), ip)
                    + " (dropped: disconnected)");
            return;
        }
        Player tombstonePlayer = loggedOutUuids.get(uuid);
        if (tombstonePlayer == player) {
            // 在途的旧连接 AUTH_UP（旧 Player 实例恰好仍活跃）：丢弃并清除墓碑
            loggedOutUuids.remove(uuid);
            debug(Messages.get(Messages.SESSION_SYNC_AUTH_UP_LOG, username, uuid.toString(), ip)
                    + " (dropped: tombstone)");
            return;
        }
        if (tombstonePlayer != null) {
            // 墓碑归属旧连接，本次上报来自重连后的新连接（Player 实例不同）：
            // 墓碑已过期，清除后按正常上报处理
            loggedOutUuids.remove(uuid, tombstonePlayer);
        }
        long now = System.currentTimeMillis();
        SessionInfo info = sessions.compute(uuid, (k, old) ->
                old != null ? old.touch(now) : new SessionInfo(username, ip, false, now, now));
        sendLoginSyncToPlayer(player, info.username(), uuid, info.ip(), info.isPremium(), info.loginTime());
        debug(Messages.get(Messages.SESSION_SYNC_AUTH_UP_LOG, username, uuid.toString(), ip));
    }

    /** 玩家断开连接时清理会话，并通知所有后端服务器 */
    public void removeSession(Player player) {
        if (!enabled) return;
        UUID uuid = player.getUniqueId();
        // 无条件设置登出墓碑（绑定本连接 Player 引用）：即使会话表无记录，也要拦截
        // 在途 AUTH_UP 乱序复活；重连后的新连接 Player 实例不同，不受本墓碑影响（#12）
        loggedOutUuids.put(uuid, player);
        // 取消挂起的延迟补发任务，避免对已断开连接无意义发送
        ScheduledTask retry = retryTasks.remove(uuid);
        if (retry != null) {
            retry.cancel();
        }
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
            // 清理玩家已不在代理上的登出墓碑（防止无界增长；在途 AUTH_UP 窗口远小于清理周期）
            loggedOutUuids.keySet().removeIf(uuid -> server.getPlayer(uuid).isEmpty());
        }).delay(CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS)
          .repeat(CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS)
          .schedule();
    }

    private void cancelCleanup() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /** 经玩家自身的当前服务器连接发送 LOGIN_SYNC（仅发送到该玩家，避免广播到同服所有玩家） */
    private void sendLoginSyncToPlayer(Player player, String username, UUID uuid, String ip, boolean isPremium, long loginTime) {
        byte[] data = SessionSyncProtocol.buildLoginMessage(username, uuid, ip, isPremium, loginTime, secretSupplier.get());
        player.getCurrentServer().ifPresent(conn -> {
            try {
                conn.sendPluginMessage(CHANNEL, data);
            } catch (IllegalStateException e) {
                // DisconnectEvent/切换间隙连接可能已断开：连接对象仍在 Optional 中但不可发送
                debug(Messages.get(Messages.SESSION_SYNC_SEND_FAILED_DEBUG, username, e.getMessage()));
            }
        });
    }

    private void sendLogoutSync(Player player, String username, UUID uuid) {
        byte[] data = SessionSyncProtocol.buildLogoutMessage(username, uuid, secretSupplier.get());
        // DisconnectEvent 时后端连接可能已断开：getCurrentServer() 仍返回非空 Optional，
        // 但连接处于非活跃状态，直接 sendPluginMessage 会抛 IllegalStateException
        player.getCurrentServer().ifPresent(conn -> {
            try {
                conn.sendPluginMessage(CHANNEL, data);
            } catch (IllegalStateException e) {
                debug(Messages.get(Messages.SESSION_SYNC_SEND_FAILED_DEBUG, username, e.getMessage()));
            }
        });
    }

    public void shutdown() {
        cancelCleanup();
        retryTasks.values().forEach(ScheduledTask::cancel);
        retryTasks.clear();
        server.getChannelRegistrar().unregister(CHANNEL);
        server.getEventManager().unregisterListener(plugin, this);
        sessions.clear();
        loggedOutUuids.clear();
    }

    private record SessionInfo(String username, String ip, boolean isPremium, long loginTime, long lastSeen) {
        SessionInfo touch(long now) {
            return new SessionInfo(username, ip, isPremium, loginTime, now);
        }
    }
}
