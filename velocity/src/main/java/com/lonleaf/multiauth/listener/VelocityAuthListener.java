package com.lonleaf.multiauth.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.VelocityConfig;
import com.lonleaf.multiauth.auth.AuthFlow;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.auth.SessionSyncManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Velocity 端认证监听器。
 */
public class VelocityAuthListener {

    private final Core core;
    private final VelocityConfig config;
    private final Logger logger;
    private final ProxyServer server;
    private final Object plugin;
    private SessionSyncManager sessionSyncManager;

    /**
     * PreLogin 阶段的决策缓存（绑定连接引用，用于兜底清理判断连接是否仍活跃）：
     * true  = 决定走 Velocity online-mode 加密握手（还没真正 hasJoined 通过）
     * false = 决定走 forceOfflineMode（无加密）
     * null  = 无缓存
     */
    private final Map<String, HandshakeState> handshakeStates = new ConcurrentHashMap<>();

    private record HandshakeState(boolean premiumDecision, boolean hasJoinedPassed,
                                  InboundConnection connection) {}

    /**
     * 并发登录验证槽位：限制同时阻塞在 Velocity 异步事件线程上的验证流程数量。
     */
    private static final int MAX_CONCURRENT_LOGIN_FLOWS = 16;
    private final java.util.concurrent.Semaphore loginFlowSlots =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_LOGIN_FLOWS, true);

    /** 验证线程计数器（用于线程命名，便于线程 dump 中区分） */
    private final AtomicInteger velocityThreadCounter = new AtomicInteger();

    /**
     * 验证专用有界线程池（daemon 线程）。
     */
    private final ExecutorService verificationExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> { Thread t = new Thread(r, "multiauth-velocity-verify-" + velocityThreadCounter.incrementAndGet()); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 单次预登录验证的硬超时（秒）：兜底防止 evaluateForProxy 内部异常/死循环导致事件线程长期挂起 */
    private static final long VERIFY_TIMEOUT_SECONDS = 12L;

    public VelocityAuthListener(Core core, VelocityConfig config, Logger logger,
                                 ProxyServer server, Object plugin) {
        this.core = core;
        this.config = config;
        this.logger = logger;
        this.server = server;
        this.plugin = plugin;
    }

    /** 注入跨服会话同步管理器 */
    public void setSessionSyncManager(SessionSyncManager sessionSyncManager) {
        this.sessionSyncManager = sessionSyncManager;
    }

    /** 关闭验证线程池（插件卸载时调用，释放 daemon 线程） */
    public void shutdownExecutor() {
        verificationExecutor.shutdown();
        try {
            if (!verificationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                verificationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            verificationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** debug 日志：仅 debug=true 时输出，用 info 级别绕过 SLF4J 默认级别限制 */
    private void debug(String msg) {
        if (core.getConfig().isDebug()) {
            logger.info("[DEBUG] " + msg);
        }
    }

    /** 获取连接远程地址（IP），用于聚合登录日志 */
    private static String getRemoteIp(InboundConnection connection) {
        try {
            java.net.SocketAddress addr = connection.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress inet) {
                return inet.getAddress().getHostAddress();
            }
            return addr != null ? addr.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== PreLogin：认证决策 ====================

    @Subscribe(order = PostOrder.LAST)
    public void onPreLogin(PreLoginEvent event) {
        // 其他插件已拒绝该玩家（封禁/黑名单/踢出等）→ 本插件不再处理，
        // 与 Spigot 端 HIGHEST 优先级 + getLoginResult() 检查语义一致（#8）。
        // 注意：Velocity 3.4 API 的 PreLoginComponentResult 无 isDenied()，
        // 但只有 denied(Component) 会设置 reason，故用 getReasonComponent().isPresent() 判定"已被拒绝"。
        // 未设置 result 时默认是 forceOfflineMode（无 reason），不影响本插件正常决策。
        if (event.getResult().getReasonComponent().isPresent()) {
            debug(Messages.get(Messages.PRELOGIN_OTHER_PLUGIN_DENIED, event.getUsername()));
            return;
        }

        String username = event.getUsername();
        debug(Messages.get(Messages.SESSION_START, username, "authentication"));

        // 并发槽位限制：防止登录洪峰占满验证线程池（evaluateForProxy 同步 HTTP）
        if (!loginFlowSlots.tryAcquire()) {
            String busyMsg = Messages.get(Messages.AUTH_SERVER_BUSY).replace("\\n", "\n");
            logger.warn(Messages.get(Messages.AUTH_CONCURRENCY_FULL, username));
            logger.warn(Messages.get(Messages.KICK_MESSAGE_SENT, username, busyMsg.replace("\n", "\\n")));
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    LegacyComponentSerializer.legacySection().deserialize(busyMsg)));
            return;
        }

        try {
            InboundConnection connection = event.getConnection();
            // PreLoginEvent 需同步设置结果，无法完全异步化。将阻塞 HTTP 调度到独立有界线程池执行，
            // event 线程通过 future.get(timeout) 等待结果，硬超时兜底防止验证异常导致事件线程长期挂起。
            Future<AuthFlow.ProxyAuthResult> future = verificationExecutor.submit(() ->
                    AuthFlow.evaluateForProxy(core, config.getConfig(), username, core.getLogger()));
            AuthFlow.ProxyAuthResult result;
            try {
                result = future.get(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                logger.warn(Messages.get(Messages.AUTH_CONCURRENCY_FULL, username));
                String busyMsg = Messages.get(Messages.AUTH_SERVER_BUSY).replace("\\n", "\n");
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        LegacyComponentSerializer.legacySection().deserialize(busyMsg)));
                return;
            } catch (ExecutionException ee) {
                // 验证线程抛出未预期异常：拒绝登录，避免误放行
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                logger.warn(Messages.get(Messages.PRELOGIN_VERIFY_EXCEPTION, username, cause.getMessage()), cause);
                String kickMsg = Messages.get(Messages.AUTH_INVALID_SESSION, username).replace("\\n", "\n");
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        LegacyComponentSerializer.legacySection().deserialize(kickMsg)));
                return;
            } catch (InterruptedException ie) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                // 中断时必须设 denied 结果，否则玩家可能被默认放行
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        LegacyComponentSerializer.legacySection().deserialize(
                                Messages.get(Messages.AUTH_SERVER_BUSY).replace("\\n", "\n"))));
                return;
            }

            switch (result.decision()) {
                case ALLOW_PREMIUM -> {
                    // 正版用户名：让 Velocity 自己执行加密握手 + hasJoined
                    handshakeStates.put(username, new HandshakeState(true, false, connection));
                    // 过程细节：hasJoined 尚未通过，聚合登录日志在 onGameProfileRequest 输出
                    debug(Messages.get(Messages.LOGIN_PREMIUM_DECISION, username, getRemoteIp(connection)));
                    // 状态清理兜底：防止极端情况下 handshakeStates 残留（不执行任何抢先踢出）
                    scheduleStateCleanup(username, connection);
                    event.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
                }
                case ALLOW_OFFLINE -> {
                    // 非正版：强制离线模式，Velocity 跳过加密
                    handshakeStates.put(username, new HandshakeState(false, false, connection));
                    // 状态清理兜底：与正版分支一致，防止离线玩家在 PreLogin 与
                    // GameProfileRequest 之间断开（onDisconnect 早退不清理）导致状态永久残留
                    scheduleStateCleanup(username, connection);
                    // 过程细节：聚合登录日志在 onGameProfileRequest 输出
                    debug(Messages.get(Messages.LOGIN_OFFLINE_DECISION, username, getRemoteIp(connection)));
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                }
                case DENY -> {
                    logger.warn(Messages.get(Messages.AUTH_PLAYER_DENIED, username, result.denyMessage()));
                    // 记录实际发送给玩家的踢出消息内容
                    // 语言文件已自带 § 颜色码，通过 LegacyComponentSerializer 解析渲染（#3）
                    String kickMsg = result.denyMessage() != null
                            ? result.denyMessage().replace("\\n", "\n")
                            : "";
                    logger.warn(Messages.get(Messages.KICK_MESSAGE_SENT, username, kickMsg.replace("\n", "\\n")));
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                            LegacyComponentSerializer.legacySection().deserialize(kickMsg)));
                }
            }
        } finally {
            loginFlowSlots.release();
        }
    }

    /**
     * 状态清理兜底：3 秒后若该玩家的握手状态仍存在（LoginEvent/DisconnectEvent
     * 均未触发的极端情况）则清理，避免 handshakeStates 内存泄漏。
     */
    private void scheduleStateCleanup(String username, InboundConnection conn) {
        server.getScheduler().buildTask(plugin, () -> {
            HandshakeState state = handshakeStates.get(username);
            // 仅当状态归属本连接、未通过 hasJoined 且连接已断开时清理
            if (state != null && state.connection() == conn
                    && !state.hasJoinedPassed() && !conn.isActive()) {
                debug(Messages.get(Messages.STATE_CLEANUP_REMOVED, username));
                handshakeStates.remove(username, state);
            }
            // 连接仍活跃（hasJoined 响应慢）或已被同名新连接覆盖 → 保留状态
        }).delay(3, TimeUnit.SECONDS).schedule();
    }

    /**
     * 清理握手状态缓存。
     */
    private void cleanupHandshakeState(String username) {
        handshakeStates.remove(username);
    }

    // ==================== GameProfileRequest：Velocity hasJoined 真正通过 ====================

    /**
     * 异步保存玩家记录到数据库，避免在事件线程同步阻塞 DB UPSERT。
     * 参数按值传递，调用前的重写结果（如 use-mojang-uuid=false 改写的 uuid）会被正确捕获。
     */
    private void savePlayerRecordAsync(String username, boolean premium, UUID uuid) {
        server.getScheduler().buildTask(plugin, () -> {
            try {
                core.getAuthManager().savePlayerRecord(username, premium, uuid);
            } catch (Exception e) {
                logger.warn(Messages.get(Messages.DB_SAVE_FAILED, e.getMessage()));
            }
        }).schedule();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        HandshakeState state = handshakeStates.get(username);
        // 同名玩家竞态保护：PreLogin 与 GameProfileRequest 之间同名玩家可能覆盖状态，
        // 校验连接一致性，不匹配则走兜底路径，避免误用他连接的状态改写 UUID / 写库
        if (state != null && state.connection() != event.getConnection()) {
            state = null;
        }

        GameProfile profile = event.getOriginalProfile();
        UUID uuid = profile.getId();

        if (state == null) {
            // 状态缺失（罕见：插件整体重载竞态等）：Velocity 已自行完成认证（正版 hasJoined 通过 /
            // 离线 profile 生成），此处按 profile UUID 推断身份并补写数据库记录，
            // 避免该次登录的记录缺失影响后续宕机决策 / 会话完整性校验。
            boolean inferredPremium;
            if (uuid == null) {
                // profile UUID 缺失（异常情况）：按离线处理，生成离线 UUID 写库，
                // 避免 generateOfflineUuid(username).equals(null) 误判为正版
                uuid = AuthManager.generateOfflineUuid(username);
                inferredPremium = false;
            } else {
                inferredPremium = !AuthManager.generateOfflineUuid(username).equals(uuid);
            }
            logger.warn(Messages.get(Messages.STATE_MISS, username,
                    inferredPremium ? Messages.LOGIN_TYPE_PREMIUM : Messages.LOGIN_TYPE_OFFLINE));
            if (inferredPremium && !config.isUseMojangUuid()) {
                UUID offlineUuid = AuthManager.generateOfflineUuid(username);
                if (!offlineUuid.equals(uuid)) {
                    uuid = offlineUuid;
                    profile = new GameProfile(offlineUuid, profile.getName(), profile.getProperties());
                    event.setGameProfile(profile);
                    debug(Messages.get(Messages.REWRITE_PREMIUM_UUID_OFFLINE, String.valueOf(offlineUuid)));
                }
            }
            savePlayerRecordAsync(username, inferredPremium, uuid);
            return;
        }

        if (state.premiumDecision) {
            // use-mojang-uuid=false：正版玩家也用离线 UUID（正版与盗版共享存档/权限）
            if (!config.isUseMojangUuid()) {
                UUID offlineUuid = AuthManager.generateOfflineUuid(username);
                if (!offlineUuid.equals(uuid)) {
                    uuid = offlineUuid;
                    profile = new GameProfile(offlineUuid, profile.getName(), profile.getProperties());
                    event.setGameProfile(profile);
                    debug(Messages.get(Messages.REWRITE_PREMIUM_UUID_OFFLINE, String.valueOf(offlineUuid)));
                }
            }
            handshakeStates.put(username, new HandshakeState(true, true, event.getConnection()));
            savePlayerRecordAsync(username, true, uuid);
            // 聚合日志（生产必要）：hasJoined 验证通过后才输出登录成功，避免 onPreLogin 过早打印误导
            logger.info(Messages.get(Messages.LOGIN_SUCCESS_PREMIUM, username,
                    uuid.toString(), getRemoteIp(event.getConnection())));
            // 过程细节：hasJoined 验证通过（最终结果已由聚合日志输出）
            debug(Messages.get(Messages.AUTH_MOJANG_VERIFY_PASSED, username, uuid.toString()));
            debug(Messages.get(Messages.SESSION_JOIN_NOTIFY, username, uuid.toString(), "true"));
        } else {
            handshakeStates.put(username, new HandshakeState(false, true, event.getConnection()));
            savePlayerRecordAsync(username, false, uuid);
            // 聚合日志（生产必要）：离线玩家 profile 生成后输出登录成功
            logger.info(Messages.get(Messages.LOGIN_SUCCESS_OFFLINE, username,
                    uuid.toString(), getRemoteIp(event.getConnection())));
            debug(Messages.get(Messages.SESSION_JOIN_NOTIFY, username, uuid.toString(), "false"));
        }
    }

    // ==================== LoginEvent：认证最终完成 ====================

    @Subscribe
    public void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        cleanupHandshakeState(username);
        // 记录会话到 Velocity 内存，并同步到目标服务器
        if (sessionSyncManager != null) {
            HandshakeState state = handshakeStates.get(username);
            boolean isPremium = state != null && state.premiumDecision;
            sessionSyncManager.markLoggedIn(event.getPlayer(), isPremium);
        }
        debug(Messages.get(Messages.SESSION_COMPLETE, username, "login completed"));
    }

    // ==================== ServerConnectedEvent：跨服转移同步 ====================

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        // 首次连接和跨服转移都同步会话到目标服务器
        if (sessionSyncManager != null) {
            sessionSyncManager.syncSessionToServer(event.getPlayer(), event.getServer());
        }
    }

    // ==================== DisconnectEvent：检测 Velocity 握手失败 ====================

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Velocity 的 DisconnectEvent 仅对已建立连接的玩家触发（pre-login 阶段断开走
        // PreLoginDisconnectEvent），getPlayer().getUsername() 不会抛异常。
        String username = event.getPlayer().getUsername();

        // 清理 Velocity 端会话，并通知后端服务器清理
        if (sessionSyncManager != null) {
            sessionSyncManager.removeSession(event.getPlayer());
        }

        HandshakeState state = handshakeStates.get(username);
        if (state != null && state.premiumDecision && !state.hasJoinedPassed) {
            // 正版玩家在 hasJoined 通过前断开，两种情况无法通过事件区分：
            //   a) 盗版客户端冒用正版名，加密握手/hasJoined 验证失败被 Velocity 断开（真实拒绝）
            //   b) 正版玩家在登录过程中主动取消/网络波动断开（误报）
            // 因无法确认是验证失败，故仅 debug 输出，避免生产环境误报"盗版客户端或无效会话"
            // （踢出本身由 Velocity 协议层执行，功能不受影响；真实拒绝的审计见 Spigot 端
            //   AUTH_MOJANG_VERIFY_FAILED_PIRATE 或 API-only 模式的对应审计）
            debug(Messages.get(Messages.DISCONNECT_BEFORE_HASJOINED, username));
            logger.warn(Messages.get(Messages.AUTH_HANDSHAKE_FAILED, username));
        }

        handshakeStates.remove(username);
        debug(Messages.get(Messages.SESSION_DISCONNECT, username));
    }
}
