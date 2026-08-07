package com.lonleaf.multiauth;

import com.lonleaf.multiauth.bStats.MetricsManager;
import com.lonleaf.multiauth.command.CommandManager;
import com.lonleaf.multiauth.command.AuthCommandManager;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.auth.LoginHistoryManager;
import com.lonleaf.multiauth.auth.LoginSecurityManager;
import com.lonleaf.multiauth.geo.IpGeoService;
import com.lonleaf.multiauth.listener.AuthJoinListener;
import com.lonleaf.multiauth.listener.AuthState;
import com.lonleaf.multiauth.listener.PlayerRestrictionListener;
import com.lonleaf.multiauth.listener.SessionSyncReceiver;
import com.lonleaf.multiauth.listener.SpigotAuthListener;
import com.lonleaf.multiauth.listener.SpigotPacketListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.logging.Logger;

public final class MultiAuth extends JavaPlugin {

    private Core core;
    private SpigotConfig config;
    private SpigotAuthListener authListener;
    private SpigotPacketListener packetListener;
    private Logger julLogger;

    // 离线玩家注册登录
    private AuthService authService;
    private AuthState authState;
    private AuthJoinListener authJoinListener;
    private PlayerRestrictionListener restrictionListener;
    private SessionSyncReceiver sessionSyncReceiver;

    // 安全增强服务
    private LoginSecurityManager loginSecurityManager;
    private LoginHistoryManager loginHistoryManager;
    private IpGeoService ipGeoService;

    @Override
    public void onEnable() {
        // 使用 DebugLogger 包装插件 logger：debug=true 时 FINE 日志转 info（[DEBUG] 前缀）输出，
        // 绕过 Paper log4j 默认 INFO 级别对 fine 日志的过滤
        this.julLogger = new DebugLogger(getLogger());

        this.config = new SpigotConfig(this, julLogger);
        this.config.load();

        Path dataDirectory = getDataFolder().toPath();

        // 加载语言文件（必须在任何日志之前）
        Messages.init(dataDirectory, config.getConfig().getLanguage());

        this.core = new Core(config.getConfig(), dataDirectory, julLogger);
        boolean ok = core.init();
        if (!ok) {
            julLogger.severe(Messages.DB_INIT_FAILED);
        }

        //bstats（在 core 初始化后注册，图表数据按需查询数据库）
        new MetricsManager(this, core);

        // proxy=false 模式：初始化 PacketEvents 监听器（用于加密握手）
        if (!config.isProxy()) {
            if (SpigotPacketListener.isAvailable()) {
                this.packetListener = new SpigotPacketListener(julLogger);
                this.packetListener.register();
                julLogger.info(Messages.get(Messages.PACKETEVENTS_LOADED));
            } else {
                julLogger.warning(Messages.get(Messages.PACKETEVENTS_NOT_INSTALLED));
                julLogger.warning(Messages.get(Messages.PACKETEVENTS_INSTALL_REQUIRED));
            }
        }

        this.authListener = new SpigotAuthListener(core, config, this, packetListener);
        getServer().getPluginManager().registerEvents(authListener, this);

        // 初始化离线玩家注册登录服务
        if (config.getConfig().isAuthEnabled()) {
            this.authService = new AuthService(core.getDatabase(), config.getConfig(), julLogger);
            // 初始化安全增强服务：登录安全管理器（失败计数/冷却/IP限制）、登录历史、地理位置查询
            this.loginSecurityManager = new LoginSecurityManager(config.getConfig(), core.getDatabase(), julLogger);
            this.loginHistoryManager = new LoginHistoryManager(core.getDatabase(), config.getConfig(), julLogger);
            this.ipGeoService = new IpGeoService(config.getConfig(), dataDirectory, julLogger);
            // 注入到 AuthService
            this.authService.setSecurityServices(loginSecurityManager, loginHistoryManager, ipGeoService);
            // 初始化共享状态 + 限制监听器 + 登录流程监听器（拆分自原 AuthListener）
            this.authState = new AuthState(authService, config, core, this);
            this.authJoinListener = new AuthJoinListener(authState, authService, config, core, this);
            this.restrictionListener = new PlayerRestrictionListener(authState, config);
            getServer().getPluginManager().registerEvents(authJoinListener, this);
            getServer().getPluginManager().registerEvents(restrictionListener, this);
            // 建立 SpigotAuthListener → AuthState 的引用，
            // 使 SpigotAuthListener.onPlayerJoin 能预填充 offlinePlayerCache
            this.authListener.setAuthState(this.authState);
            // 注册 register / login / changepassword 命令（AuthCommandManager 内部完成注册）
            new AuthCommandManager(this, authService);
            julLogger.info(Messages.get(Messages.AUTH_MODULE_ENABLED));

            // proxy=true 模式下注册跨服会话同步接收器
            if (config.isProxy()) {
                this.sessionSyncReceiver = new SessionSyncReceiver(this, authService, authState, julLogger,
                        () -> config.getConfig().getSessionSyncSecret());
                this.sessionSyncReceiver.register();
            }

            // 定期清理过期的持久化会话，避免 persistentSessions 内存泄漏
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    authService.cleanExpiredSessions();
                } catch (Exception e) {
                    julLogger.warning(Messages.get(Messages.SESSION_CLEAN_FAILED, e.getMessage()));
                }
            }, 6000L, 6000L); // 5分钟 = 6000 ticks
        } else {
            julLogger.info(Messages.get(Messages.AUTH_MODULE_DISABLED));
        }

        // proxy=false 模式检查：要求 online-mode=false
        if (!config.isProxy() && getServer().getOnlineMode()) {
            getLogger().warning(Messages.get(Messages.CONFIG_ONLINE_MODE_INCOMPATIBLE_WARN));
        }

        // 注册命令（CommandManager 内部完成 executor/tabCompleter 注册）
        new CommandManager(this, core, config, authService);

        getLogger().info(Messages.get(Messages.CONFIG_LOADED, String.valueOf(config.isProxy()),
                core != null && core.getConfig() != null ? core.getConfig().getDatabaseType() : "unknown"));
        getLogger().info(Messages.get(Messages.CMD_PLUGIN_INFO, getDescription().getVersion()));
    }

    /**
     * reload 后同步 proxy 模式的运行时组件（PacketEvents 拦截器）。
     */
    public void reloadProxyMode() {
        boolean newProxy = config.isProxy();
        if (newProxy && packetListener != null) {
            // proxy=false → true：注销 PacketEvents 拦截，避免对已通过 Velocity 验证的玩家强行加密握手
            packetListener.unregister();
            packetListener = null;
            authListener.updatePacketListener(null);
            getLogger().info(Messages.PLUGIN_PROXY_SWITCH_TRUE);
        } else if (!newProxy && packetListener == null) {
            // proxy=true → false：启用 PacketEvents 加密握手
            if (SpigotPacketListener.isAvailable()) {
                packetListener = new SpigotPacketListener(julLogger);
                packetListener.register();
                authListener.updatePacketListener(packetListener);
                getLogger().info(Messages.PLUGIN_PROXY_SWITCH_FALSE);
            } else {
                getLogger().warning(Messages.PLUGIN_API_ONLY_WARNING);
                getLogger().warning(Messages.PLUGIN_PACKETEVENT_INSTALL_HINT);
            }
        }
    }

    @Override
    public void onDisable() {
        if (packetListener != null) {
            packetListener.unregister();
        }
        if (sessionSyncReceiver != null) {
            sessionSyncReceiver.unregister();
        }
        if (authListener != null) {
            authListener.shutdownExecutor();
        }
        if (authState != null) {
            authState.clearAll();
        }
        if (ipGeoService != null) {
            ipGeoService.close();
        }
        if (authService != null) {
            authService.shutdown();
        }
        if (core != null) {
            core.shutdown();
        }
        getLogger().info(Messages.get(Messages.SESSION_DISCONNECT, "Spigot plugin"));
    }

    /** reload 后刷新离线玩家限制监听器的配置（如允许命令列表） */
    public void refreshAuthListener() {
        if (authState != null) {
            authState.refreshAllowedCommands();
        }
    }

    /** reload 后清理预登录缓存（避免配置切换后使用过期摘要） */
    public void clearLoginSummaries() {
        if (authListener != null) {
            authListener.clearLoginSummaries();
        }
    }

    /**
     * reload 后重建安全增强服务（地理位置查询需要按新配置重启 xdb 加载，
     * 安全管理器需要清空内存中的失败计数/IP在线计数）。
     */
    public void reloadSecurityServices() {
        // 关闭旧的 IpGeoService（释放 xdb 句柄）
        if (ipGeoService != null) {
            ipGeoService.close();
            ipGeoService = null;
        }
        // 清空旧的安全管理器内存数据
        if (loginSecurityManager != null) {
            loginSecurityManager.clear();
        }
        // 清空会话与安全状态
        if (authService != null) {
            authService.clearSessions();
        }
        // 若 auth 模块启用，则按新配置重建
        if (config.getConfig().isAuthEnabled() && authService != null) {
            Path dataDirectory = getDataFolder().toPath();
            this.ipGeoService = new IpGeoService(config.getConfig(), dataDirectory, julLogger);
            this.authService.setSecurityServices(loginSecurityManager, loginHistoryManager, ipGeoService);
            julLogger.info(Messages.get(Messages.SEC_SERVICES_RELOADED_LOG));
        }
        // 无论 auth 是否启用，只要 authService 存在就更新配置引用，
        // 确保 session.timeout 等配置在 reload 后立即生效（auth 从启用切到禁用时也保持引用一致）
        if (authService != null) {
            authService.updateConfig(config.getConfig());
        }
    }

    /** 取消玩家的超时踢出任务（登录/注册成功时调用） */
    public void cancelAuthTimeout(java.util.UUID uuid) {
        if (authState != null) {
            authState.cancelTimeout(uuid);
        }
    }

    /** 登录成功后的统一处理（恢复游戏模式、回到上次下线地点） */
    public void onAuthLoginSuccess(org.bukkit.entity.Player player) {
        if (authJoinListener != null) {
            authJoinListener.onLoginSuccess(player);
        }
    }

    public Core getCore() {
        return core;
    }

    public SpigotConfig getConfigManager() {
        return config;
    }
}
