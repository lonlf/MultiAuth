package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.SpigotMojangVerifier;
import com.lonleaf.multiauth.auth.AuthFlow;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.db.PlayerRecord;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spigot 端认证监听器，根据配置支持两种模式：
 */
public class SpigotAuthListener implements Listener {

    private final Core core;
    private final SpigotConfig config;
    private final JavaPlugin plugin;
    private final Logger logger;
    // 非 final：/multiauth reload 切换 proxy 模式时可动态更新（proxy=false 时需要 PacketEvents 加密握手）
    // volatile：reload 时在主线程更新，验证回调在 PacketEvents 线程读取，需保证可见性（#7）
    private volatile SpigotPacketListener packetListener;

    /** AuthState 引用（用于 onPlayerJoin 时预填充 premiumCache，避免主线程查库） */
    private AuthState authState;

    /** 验证线程计数器（用于线程命名，提为字段避免每次创建新实例） */
    private final AtomicInteger verifyThreadCounter = new AtomicInteger();

    /**
     * 加密握手验证专用线程池（daemon 线程，有界）。
     */
    private final ExecutorService verificationExecutor = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "multiauth-verify-" + verifyThreadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * 预登录阶段（异步）缓存的登录摘要，供同步阶段（PlayerLoginEvent / PlayerJoinEvent）
     * 零数据库读取地应用决策（#5：避免主线程阻塞在 DB 操作上）。
     */
    private final ConcurrentMap<String, LoginSummary> loginSummaries = new ConcurrentHashMap<>();

    /** 预登录阶段缓存的登录摘要：玩家实际 UUID、是否正版、数据库记录快照 */
    private record LoginSummary(UUID uuid, boolean isPremium, PlayerRecord record) {}

    public SpigotAuthListener(Core core, SpigotConfig config, JavaPlugin plugin,
                              SpigotPacketListener packetListener) {
        this.core = core;
        this.config = config;
        this.plugin = plugin;
        // 使用 core.getLogger()（DebugLogger），保证 debug 配置对 fine 日志生效
        this.logger = core.getLogger();
        updatePacketListener(packetListener);
    }

    /**
     * 更新 PacketEvents 监听器引用（reload 切换 proxy 模式时调用）。
     */
    public void updatePacketListener(SpigotPacketListener newListener) {
        this.packetListener = newListener;
        // 方案 A：设置 LOGIN_START 回调，在 PacketEvents 拦截时异步执行验证
        if (newListener != null) {
            newListener.setLoginStartCallback(this::handleLoginStartAsync);
        }
    }

    /** 清理预登录缓存（reload 时调用，避免配置切换后使用过期摘要） */
    public void clearLoginSummaries() {
        loginSummaries.clear();
    }

    /**
     * 设置 AuthState 引用（onPlayerJoin 时预填充 premiumCache，避免主线程查库）。
     */
    public void setAuthState(AuthState state) {
        this.authState = state;
    }

    // ==================== proxy=false：PacketEvents LOGIN_START 拦截 → 异步验证 → 假包 ====================

    /**
     * 异步处理 LOGIN_START（由 PacketEvents 触发）。
     */
    private void handleLoginStartAsync(String username, Channel channel, InetAddress address) {
        try {
            verificationExecutor.execute(() -> {
                try {
                    runVerification(username, channel, address);
                } catch (Throwable t) {
                    // 异常兜底：验证流程任何环节抛出未预期异常（含 reload 切换 proxy 竞态），
                    // 必须给玩家明确的失败结果，防止假 LOGIN_START 未发送导致玩家卡死登录界面
                    logger.log(Level.SEVERE, Messages.get(Messages.AUTH_VERIFY_UNEXPECTED_ERROR, username), t);
                    if (packetListener != null) {
                        if (channel.isActive()) {
                            packetListener.sendDisconnect(channel, formatKickMessage(Messages.AUTH_INVALID_SESSION));
                        }
                        packetListener.putVerificationResult(username, channel,
                                new SpigotPacketListener.VerificationResult(false, null,
                                        Messages.AUTH_INVALID_SESSION, channel));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池满载（服务器过载）：新验证任务被拒绝，无法完成加密握手，
            // 直接断开玩家（Disconnect 包可正常送达，LOGIN_START 已被取消不会卡死）
            logger.warning(Messages.get(Messages.AUTH_EXECUTOR_FULL, username));
            SpigotPacketListener listener = packetListener;
            if (listener != null && channel.isActive()) {
                listener.sendDisconnect(channel, formatKickMessage(Messages.AUTH_INVALID_SESSION));
            }
        }
    }

    private void runVerification(String username, Channel channel, InetAddress address) {
        // proxy=true：Spigot 端不参与加密握手（由 Velocity 完成），防御 reload 切换瞬时窗口
        if (config.isProxy()) {
            logger.fine(Messages.get(Messages.AUTH_PROXY_SKIP_SPIGOT));
            return;
        }
        // 插件禁用检查：避免访问已关闭的数据库 / AuthManager
        if (!plugin.isEnabled()) {
            logger.warning(Messages.get(Messages.AUTH_PLUGIN_DISABLED, username));
            if (packetListener != null) {
                packetListener.sendDisconnect(channel, formatKickMessage(Messages.AUTH_SERVICE_NOT_INITIALIZED));
            }
            return;
        }

        logger.fine(Messages.get(Messages.SESSION_START, username, "Spigot Mojang (proxy=false)"));

        SpigotMojangVerifier verifier = new SpigotMojangVerifier(
                core.getAuthManager(), config.isUseMojangUuid(), logger, packetListener);

        AuthFlow.Result result = AuthFlow.evaluate(core, config.getConfig(), username,
                (uname) -> verifier.verify(channel, uname), logger);

        switch (result.decision()) {
            case ALLOW -> {
                // 验证成功：标记已验证，缓存结果，发送假 LOGIN_START 包
                logger.fine(Messages.get(Messages.AUTH_PLAYER_VERIFIED_SEND_FAKE, username, String.valueOf(result.uuid())));
                // 聚合日志（生产必要）：正版/离线 + UUID + 连接信息，每个玩家 1 条
                // use-mojang-uuid=false 时正版玩家实际使用离线 UUID，标注以示区分
                String type = result.isPremium()
                        ? (config.isUseMojangUuid() ? Messages.LOGIN_TYPE_PREMIUM : Messages.LOGIN_TYPE_PREMIUM_OFFLINE_UUID)
                        : Messages.LOGIN_TYPE_OFFLINE;
                logger.info(Messages.get(Messages.LOGIN_SUCCESS, username, type,
                        String.valueOf(result.uuid()),
                        address != null ? address.getHostAddress() : "?"));
                // reload 竞态防御：proxy 切换过程中 packetListener 可能已被注销置空。
                // LOGIN_START 已被拦截且假包无法发送，直接关闭连接让玩家重连（reload 后走新模式），
                // 避免玩家卡死在登录界面。
                if (packetListener == null) {
                    logger.warning(Messages.get(Messages.AUTH_LISTENER_UNREGISTERED_CLOSE, username));
                    channel.close();
                    return;
                }
                packetListener.markVerified(channel);
                packetListener.putVerificationResult(username, channel,
                        new SpigotPacketListener.VerificationResult(true, result.uuid(), null, channel));
                // 发送假 LOGIN_START 包（包含正版 UUID）
                // 服务器收到后，检查 spoofedUUID，用正版 UUID 创建 gameProfile
                // 注入失败时 sendFakeLoginStart 内部已兜底踢出，防止玩家卡死在登录界面
                packetListener.sendFakeLoginStart(channel, username, result.uuid());
            }
            case DENY -> {
                // 盗版客户端收到 EncryptionRequest 后约 1 秒会自行断开（显示客户端默认消息），
                // 此时 Disconnect 包已无法送达，无需再发送。
                // 但拒绝必须输出生产可见的日志留痕：正版名离线登录（盗版客户端冒用正版名）时
                // 客户端自行断开，原先仅输出 fine（debug=false 下被过滤），且未发假 LOGIN_START
                // 不会触发 onAsyncPreLogin，导致"被拒却无日志"。此处统一输出 [AUTH]/[KICK] warning。
                if (packetListener == null) {
                    logger.warning(Messages.get(Messages.AUTH_LISTENER_UNREGISTERED_CLOSE, username));
                    channel.close();
                    return;
                }
                String denyMessage = formatKickMessage(result.denyMessage());
                if (!channel.isActive()) {
                    logger.warning(Messages.get(Messages.AUTH_DENY_CLIENT_DISCONNECTED, username));
                } else {
                    logger.warning(Messages.get(Messages.AUTH_DENY_SEND_DISCONNECT, username));
                    packetListener.sendDisconnect(channel, denyMessage);
                }
                // 拒绝留痕：无论客户端是否仍在线，都记录预期踢出消息（未送达时便于排查拒绝原因）
                logger.warning(Messages.get(Messages.KICK_REJECTED_MESSAGE, username, denyMessage.replace("\n", "\\n")));
                packetListener.putVerificationResult(username, channel,
                        new SpigotPacketListener.VerificationResult(false, null, result.denyMessage(), channel));
            }
        }
    }

    /**
     * 关闭验证线程池（插件禁用时调用，释放 daemon 线程）。
     */
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

    /**
     * proxy=false 模式：检查 PacketEvents 验证结果。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        // 其他插件已拒绝该玩家（黑名单/封禁等）→ 本插件不再验证
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        String username = event.getName();

        // proxy=true：异步阶段完成数据库校验（#5：避免 PlayerLoginEvent 主线程阻塞），
        // 决策与数据库写入均在此阶段完成，结果缓存在 loginSummaries
        if (config.isProxy()) {
            handleProxyPreLogin(event);
            return;
        }

        // PacketEvents 未安装（含 proxy reload 切换后暂未注册）：API-only 降级
        if (packetListener == null) {
            handleApiOnlyLogin(event);
            return;
        }

        // 方案 A：验证已在 PacketEvents LOGIN_START 拦截中完成
        SpigotPacketListener.VerificationResult result = packetListener.getAndRemoveVerificationResult(username);

        if (result == null) {
            // 未验证（异常情况：PacketEvents 未拦截或回调失败）
            logger.warning(Messages.get(Messages.AUTH_NO_PACKETEVENT_VERIFY, username));
            String kickMsg = formatKickMessage(Messages.AUTH_INVALID_SESSION);
            logger.warning(Messages.get(Messages.KICK_MESSAGE_SENT, username, kickMsg.replace("\n", "\\n")));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            return;
        }

        if (result.allowed()) {
            // 过程细节：玩家最终通过预登录检查（聚合结果日志已在 handleLoginStartAsync 输出）
            logger.fine(Messages.get(Messages.AUTH_PLAYER_VERIFIED_DEBUG, username, String.valueOf(result.uuid())));
            cacheLoginSummary(username, result.uuid(), null);
            event.allow();
        } else {
            logger.warning(Messages.get(Messages.AUTH_VERIFY_FAILED_DENY, username));
            String kickMsg = formatKickMessage(result.denyMessage());
            logger.warning(Messages.get(Messages.KICK_MESSAGE_SENT, username, kickMsg.replace("\n", "\\n")));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
        }
    }

    /**
     * proxy=true 预登录（异步阶段）：数据库健康检查 + 记录校验 + 必要的记录同步，
     * 全部在异步阶段完成，避免 PlayerLoginEvent 主线程执行阻塞式 DB 操作（#5）。
     */
    private void handleProxyPreLogin(AsyncPlayerPreLoginEvent event) {
        String username = event.getName();
        UUID playerUuid = event.getUniqueId();
        AuthManager authManager = core.getAuthManager();

        // 数据库不可用 → 拒绝登录
        if (!core.isDatabaseHealthy()) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_DB_UNAVAILABLE));
            denyAsync(event, Messages.AUTH_DATABASE_UNAVAILABLE);
            return;
        }
        if (authManager == null) {
            logger.warning(Messages.get(Messages.AUTH_PLAYER_DENIED, username, Messages.DENY_REASON_AUTHMANAGER_NOT_INITIALIZED));
            denyAsync(event, Messages.AUTH_SERVICE_NOT_INITIALIZED);
            return;
        }

        PlayerRecord record = authManager.getPlayerRecord(username);
        if (record == null) {
            // 无记录 → 首次登录，允许（代理端会写入记录；如数据库未共享则本机同步写入正版记录）
            logger.info(Messages.get(Messages.AUTH_PLAYER_ALLOWED, username,
                    Messages.ALLOW_REASON_NO_RECORD, playerUuid.toString()));
            record = syncRecordFromProxy(authManager, username, playerUuid, null);
        } else if (record.isPremium()) {
            // 正版记录：支持 use-mojang-uuid 配置切换时的 UUID 自动修正，无法解释的不匹配才视为会话劫持
            if (!handlePremiumRecord(authManager, username, playerUuid, record)) {
                logger.warning(Messages.get(Messages.AUTH_UUID_MISMATCH, username,
                        record.uuid().toString(), playerUuid.toString()));
                String kickMsg = Messages.get(Messages.SESSION_HIJACK_WARNING, username);
                logger.warning(Messages.get(Messages.KICK_MESSAGE_SENT, username, kickMsg.replace("\n", "\\n")));
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, formatKickMessage(kickMsg));
                return;
            }
        } else {
            // 离线记录 → 检查是否被正版 UUID 覆盖（之前验证失败留下的离线记录）
            UUID offlineUuid = AuthManager.generateOfflineUuid(username);
            if (offlineUuid.equals(playerUuid)) {
                // 玩家 UUID 仍为离线 UUID → 正常离线登录
                logger.info(Messages.get(Messages.AUTH_PLAYER_ALLOWED, username,
                        Messages.ALLOW_REASON_OFFLINE_RECORD, playerUuid.toString()));
            } else if (!config.isUseMojangUuid()) {
                // use-mojang-uuid=false：代理端转发了正版 UUID，但本地始终使用离线记录（正版与离线共享存档）
                logger.info(Messages.get(Messages.AUTH_PLAYER_ALLOWED, username,
                        Messages.ALLOW_REASON_PREMIUM_KEEP_OFFLINE_UUID, playerUuid.toString()));
                // 修正本地记录为离线记录，避免下次登录再次误判为升级
                syncRecordFromProxy(authManager, username, playerUuid, false);
            } else {
                // 玩家带着正版 UUID 来，但本地数据库还留着旧离线记录 → 升级为正版记录
                logger.info(Messages.get(Messages.AUTH_PLAYER_ALLOWED, username,
                        Messages.get(Messages.ALLOW_REASON_UPGRADE_OFFLINE_TO_PREMIUM, playerUuid.toString()), playerUuid.toString()));
                syncRecordFromProxy(authManager, username, playerUuid, true);
            }
        }

        cacheLoginSummary(username, playerUuid, record);
        event.allow();
    }

    /**
     * 缓存预登录阶段的登录摘要（异步线程调用，主线程零数据库读取）。
     *
     * @param record 已有的数据库记录快照；传 null 时在 use-mojang-uuid=false 场景下补查（异步安全）
     */
    private void cacheLoginSummary(String username, UUID uuid, PlayerRecord record) {
        boolean isPremium;
        if (config.isUseMojangUuid()) {
            // use-mojang-uuid=true：正版玩家携带正版 UUID（与离线 UUID 不同）
            isPremium = !AuthManager.generateOfflineUuid(username).equals(uuid);
        } else {
            // use-mojang-uuid=false：正版与离线共享离线 UUID，以数据库记录（is_premium）判断身份
            if (record == null) {
                AuthManager am = core.getAuthManager();
                record = (am != null) ? am.getPlayerRecord(username) : null;
            }
            isPremium = record != null && record.isPremium();
        }
        loginSummaries.put(username, new LoginSummary(uuid, isPremium, record));
    }

    /**
     * API-only 降级模式（PacketEvents 未安装时的 proxy=false 流程）。
     */
    private void handleApiOnlyLogin(AsyncPlayerPreLoginEvent event) {
        String username = event.getName();
        logger.warning(Messages.AUTH_API_ONLY_MODE);

        AuthFlow.Result result = AuthFlow.evaluateApiOnly(core, config.getConfig(), username, logger);
        switch (result.decision()) {
            case ALLOW -> {
                String mode = result.isPremium() ? Messages.LOGIN_TYPE_PREMIUM_API_ONLY : Messages.LOGIN_TYPE_OFFLINE_API_ONLY;
                logger.info(Messages.get(Messages.LOGIN_SUCCESS, username, mode,
                        String.valueOf(result.uuid()), ipOf(event)));
                cacheLoginSummary(username, result.uuid(), null);
                event.allow();
            }
            case DENY -> denyAsync(event, result.denyMessage());
        }
    }

    /** 拒绝登录并记录踢出消息 */
    private void denyAsync(AsyncPlayerPreLoginEvent event, String message) {
        String kickMsg = formatKickMessage(message);
        logger.warning(Messages.get(Messages.KICK_MESSAGE_SENT, event.getName(), kickMsg.replace("\n", "\\n")));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
    }

    /** 获取预登录连接的远程 IP（用于聚合登录日志） */
    private static String ipOf(AsyncPlayerPreLoginEvent event) {
        try {
            java.net.InetAddress addr = event.getAddress();
            return addr != null ? addr.getHostAddress() : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    // ==================== proxy=true：登录阶段应用预登录缓存的决策 ====================

    /**
     * 登录阶段（主线程）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        // 其他插件已拒绝该玩家（封禁/白名单/满员等）→ 本插件不再处理
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }

        String username = event.getPlayer().getName();

        // proxy=false：验证已在异步预登录阶段完成（AuthFlow.evaluate），
        // 会话完整性校验由 onPlayerJoin 负责，此处零数据库操作
        if (!config.isProxy()) {
            return;
        }

        // proxy=true：应用异步阶段缓存的决策（peek 不移除，onPlayerJoin 消费）
        LoginSummary summary = loginSummaries.get(username);
        if (summary == null) {
            // 预登录阶段未缓存（reload 竞态等异常情况）：Velocity 已完成验证并转发 UUID，
            // 保守放行（数据库校验在异步阶段已通过，否则不会到达登录事件）
            logger.warning(Messages.get(Messages.AUTH_NO_LOGIN_SUMMARY, username));
            event.setResult(PlayerLoginEvent.Result.ALLOWED);
            return;
        }
        event.setResult(PlayerLoginEvent.Result.ALLOWED);
    }

    /**
     * 处理正版记录，支持 use-mojang-uuid 配置切换时的 UUID 自动修正（无需清理数据库）。
     *
     * @return true = 允许登录（可能已自动修正数据库记录）；false = 拒绝（会话劫持/冒用）
     */
    private boolean handlePremiumRecord(AuthManager authManager, String username,
                                         UUID playerUuid, PlayerRecord record) {
        UUID offlineUuid = AuthManager.generateOfflineUuid(username);
        if (config.isUseMojangUuid()) {
            // use-mojang-uuid=true：正版记录应匹配玩家携带的正版 UUID
            if (record.uuid().equals(playerUuid)) {
                authManager.savePlayerRecord(username, true, playerUuid);
                return true;
            }
            if (record.uuid().equals(offlineUuid)) {
                // 旧配置遗留（use-mojang-uuid=false 时期）的正版+离线UUID 记录：
                // 玩家带正版 UUID（代理端已验证）→ 升级记录为正版 UUID，放行
                logger.info(Messages.get(Messages.AUTH_PLAYER_ALLOWED, username,
                        Messages.get(Messages.ALLOW_REASON_UUID_AUTOCORRECT_OFFLINE_TO_PREMIUM, playerUuid.toString()), playerUuid.toString()));
                authManager.savePlayerRecord(username, true, playerUuid);
                return true;
            }
            // 正版记录与玩家 UUID 均不匹配：玩家带离线/未知 UUID，非正版身份 → 拒绝
            return false;
        }
        // use-mojang-uuid=false：玩家实际 UUID 应为离线 UUID（代理端已改写）
        if (playerUuid.equals(offlineUuid)) {
            if (!record.uuid().equals(offlineUuid)) {
                // 旧配置遗留（use-mojang-uuid=true 时期）的正版 UUID 记录 → 降级为离线 UUID
                logger.info(Messages.get(Messages.AUTH_PLAYER_ALLOWED, username,
                        Messages.get(Messages.ALLOW_REASON_UUID_AUTOCORRECT_PREMIUM_TO_OFFLINE, offlineUuid.toString()), playerUuid.toString()));
                authManager.savePlayerRecord(username, true, offlineUuid);
            } else {
                // UUID 匹配，更新最后登录时间
                authManager.savePlayerRecord(username, true, offlineUuid);
            }
            return true;
        }
        // 玩家带非离线 UUID：仅当与记录一致（两端 use-mojang-uuid 配置不一致场景）时放行
        return record.uuid().equals(playerUuid);
    }

    /**
     * proxy=true 模式下，将代理端传来的 UUID 同步写入本地数据库。
     *
     * @param isPremium 传入 null 表示自动判断：与离线 UUID 相同则为离线，否则视为正版
     */
    private PlayerRecord syncRecordFromProxy(AuthManager authManager, String username,
                                              UUID playerUuid, Boolean isPremium) {
        boolean premium;
        if (isPremium != null) {
            premium = isPremium;
        } else {
            UUID offlineUuid = AuthManager.generateOfflineUuid(username);
            premium = !offlineUuid.equals(playerUuid);
        }
        // use-mojang-uuid=false：即使代理端转发了正版 UUID，本地也应保存为离线记录
        // （正版与离线共享同一离线 UUID/存档），避免误存正版记录导致后续 UUID 校验失败
        UUID recordUuid;
        if (!config.isUseMojangUuid()) {
            premium = false;
            recordUuid = AuthManager.generateOfflineUuid(username);
        } else {
            recordUuid = playerUuid;
        }
        authManager.savePlayerRecord(username, premium, recordUuid);
        return new PlayerRecord(username, premium, recordUuid, System.currentTimeMillis());
    }

    // ==================== 玩家加入通知 ====================

    /**
     * 玩家加入通知（主线程）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();
        UUID playerUuid = player.getUniqueId();

        LoginSummary summary = loginSummaries.remove(username);
        if (summary == null) {
            // 异常情况（reload 竞态/摘要被覆盖）：无法读取缓存快照，退化为按 UUID 判断身份
            logger.fine(Messages.get(Messages.AUTH_NO_LOGIN_SUMMARY_DEBUG, username));
            boolean prem = config.isUseMojangUuid()
                    && !AuthManager.generateOfflineUuid(username).equals(playerUuid);
            // 预填充 premiumCache（即使无摘要，也尽量避免 AuthState 主线程查库）
            if (authState != null) {
                authState.preFillPremiumCache(playerUuid, prem);
            }
            notifyLoginStatus(player, username, playerUuid, prem ? "正版" : "离线");
            return;
        }

        boolean isActualPremium = summary.isPremium();
        PlayerRecord record = summary.record();

        // 预填充 AuthState 的 premiumCache（use-mojang-uuid=false 时避免主线程查库）
        if (authState != null) {
            authState.preFillPremiumCache(playerUuid, isActualPremium);
        }

        // 代理模式 + 实际是正版 UUID：记录同步已在预登录异步阶段完成
        if (config.isProxy() && isActualPremium) {
            notifyLoginStatus(player, username, playerUuid, "正版");
            return;
        }

        if (record != null && record.isPremium() && record.uuid().equals(playerUuid)) {
            // 正版记录与玩家 UUID 一致 → 正版登录
            notifyLoginStatus(player, username, playerUuid, "正版");
        } else if (record != null && record.isPremium() && !record.uuid().equals(playerUuid)) {
            // 会话完整性校验：正版记录的 UUID 与当前 UUID 不一致 → 踢出（防止盗版冒用正版用户名）
            logger.warning(Messages.get(Messages.AUTH_UUID_MISMATCH, username, record.uuid().toString(), playerUuid.toString()));
            String kickMsg = formatKickMessage(Messages.AUTH_INVALID_SESSION);
            logger.warning(Messages.get(Messages.KICK_MESSAGE_SENT, username, kickMsg.replace("\n", "\\n")));
            player.kickPlayer(kickMsg);
        } else {
            // 离线登录（或 proxy=false / use-mojang-uuid=false 下正版玩家共享离线 UUID 的场景，
            // 按预登录阶段缓存的摘要判定真实身份）
            notifyLoginStatus(player, username, playerUuid, isActualPremium ? "正版" : "离线");
        }
    }

    /**
     * 玩家退出时清理预登录摘要缓存，避免玩家在登录前断开导致 loginSummaries 残留。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        loginSummaries.remove(event.getPlayer().getName());
    }

    /**
     * 发送登录状态通知（专门的玩家消息，替代原 session_join_notify/session_complete 进服通知）。
     */
    private void notifyLoginStatus(Player player, String username, UUID playerUuid, String status) {
        if (config.isNotifyPlayerStatus()) {
            String notifyMsg = Messages.get(Messages.SESSION_STATUS_NOTIFY, status, playerUuid.toString());
            player.sendMessage(notifyMsg);
            logger.fine(Messages.get(Messages.MSG_STATUS_NOTIFY_SENT, username, notifyMsg.replace("\n", "\\n")));
        }
        // 生产必要日志：登录状态留痕，不受 notify-player-status 开关影响
        logger.info(Messages.get(Messages.SESSION_STATUS_LOG, username, status, playerUuid.toString()));
    }

    /**
     * 格式化踢出消息：将语言文件中的字面 "\n" 转为实际换行符，
     * 保留已有的 § 颜色代码（AUTH_INVALID_SESSION 已自带 §c 前缀）。
     */
    private static String formatKickMessage(String message) {
        if (message == null) return "";
        // 转换字面 "\n" 为实际换行（日志文件存储的是反斜杠+n 字面量）
        return message.replace("\\n", "\n");
    }
}
