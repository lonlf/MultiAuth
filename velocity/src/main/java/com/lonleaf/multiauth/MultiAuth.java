package com.lonleaf.multiauth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.auth.SessionSyncManager;
import com.lonleaf.multiauth.command.CommandManager;
import com.lonleaf.multiauth.listener.VelocityAuthListener;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "multiauth", name = "multiauth", version = "0.4.1", description = "Login authenticated players and other players.", url = "https://github.com/lonlf", authors = {"lonlf"})
public class MultiAuth {

    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    // 保存为字段避免被 GC（bstats 内部弱引用，局部变量可能被回收导致指标停止上报）
    @SuppressWarnings("unused")
    private Metrics metrics;

    private Core core;
    private VelocityConfig config;
    private VelocityAuthListener authListener;
    private SessionSyncManager sessionSyncManager;

    @Inject
    public MultiAuth(
            ProxyServer server,
            Logger logger,
            Metrics.Factory metricsFactory,
            @DataDirectory Path dataDirectory
    ) {
        this.server = server;
        this.logger = logger;
        this.metricsFactory = metricsFactory;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        int pluginId = 33105;
        this.metrics = metricsFactory.make(this, pluginId);

        this.config = new VelocityConfig(dataDirectory, logger);
        this.config.load();

        // 加载语言文件（必须在任何日志之前）
        Messages.init(dataDirectory, config.getConfig().getLanguage());

        java.util.logging.Logger julLogger = new Slf4jLoggerAdapter(logger);
        this.core = new Core(config.getConfig(), dataDirectory, julLogger);
        boolean ok = core.init();
        if (!ok) {
            logger.error(Messages.get(Messages.DB_INIT_FAILED));
        }

        this.authListener = new VelocityAuthListener(core, config, logger, server, this);

        // 跨服会话同步：仅在配置了签名密钥时启用（密钥留空 = 关闭会话同步），Velocity 作为会话中心
        String sessionSecret = config.getConfig().getSessionSyncSecret();
        boolean syncEnabled = sessionSecret != null && !sessionSecret.isBlank();
        this.sessionSyncManager = new SessionSyncManager(this, server, logger, syncEnabled,
                config.getConfig().isDebug(), () -> config.getConfig().getSessionSyncSecret());
        this.authListener.setSessionSyncManager(sessionSyncManager);
        server.getEventManager().register(this, authListener);

        // CommandManager 内部完成 /multiauth 命令注册
        new CommandManager(this, core, config, logger, server);

        logger.info(Messages.get(Messages.PLUGIN_VELOCITY_INITIALIZED));
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        // synchronized 保证 config → Messages → core 整体重载原子性，避免登录事件线程
        // 读到中间不一致状态（reload 为低频管理操作，锁竞争可忽略；onPreLogin 不加锁，
        // 读取竞态由 config/core 内部字段可见性兜底，最坏情况为单次登录读到旧配置）
        synchronized (this) {
            if (config != null) {
                config.reload();
                // 重载语言文件
                Messages.reload(config.getConfig().getLanguage());
                if (core != null) {
                    core.reload(config.getConfig());
                }
                // 会话同步开关随密钥配置实时生效
                if (sessionSyncManager != null) {
                    String sessionSecret = config.getConfig().getSessionSyncSecret();
                    sessionSyncManager.setEnabled(sessionSecret != null && !sessionSecret.isBlank());
                }
                logger.info(Messages.get(Messages.CONFIG_RELOADED));
            }
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (authListener != null) {
            authListener.shutdownExecutor();
        }
        if (sessionSyncManager != null) {
            sessionSyncManager.shutdown();
        }
        if (core != null) {
            core.shutdown();
        }
        if (config != null) {
            config.close();
        }
        logger.info(Messages.get(Messages.PLUGIN_VELOCITY_SHUTDOWN));
    }

    public VelocityConfig getConfig() {
        return config;
    }

    public Core getCore() {
        return core;
    }
}
