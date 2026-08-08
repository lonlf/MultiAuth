package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.SpigotConfig;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /multiauth reload —— 重载插件配置（含语言、安全服务、PacketEvents 拦截器等）。
 */
public class ReloadCommand implements Command {

    private final MultiAuth plugin;
    private final SpigotConfig config;
    private final Core core;

    public ReloadCommand(MultiAuth plugin, SpigotConfig config, Core core) {
        this.plugin = plugin;
        this.config = config;
        this.core = core;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            return false;
        }
        if (!sender.hasPermission("multiauth.admin")) {
            sender.sendMessage(Messages.GENERIC_PERMISSION_DENIED);
            return true;
        }

        // 记录 reload 前的 proxy 值，用于检测代理模式切换（切换后需重启服务端才完全生效）
        boolean oldProxy = config.isProxy();
        config.reload();
        // 重载语言文件
        Messages.reload(config.getConfig().getLanguage());
        // 清理预登录缓存，避免配置切换后使用过期摘要
        plugin.clearLoginSummaries();
        // proxy 模式切换时同步 PacketEvents 拦截器（注册/注销）
        plugin.reloadProxyMode();
        // 刷新离线玩家限制监听器的配置（如允许命令列表）
        plugin.refreshAuthListener();
        // 先 reload core（可能切换数据库连接/重建服务），再重建安全增强服务，
        // 确保 AuthService/安全管理器注入的是新数据库引用而非已断开的旧实例
        if (core != null) {
            core.reload(config.getConfig());
        }
        plugin.reloadSecurityServices();
        // proxy 模式切换仅靠 reload 无法完全生效（PacketEvents 依赖、server.properties/spigot.yml 等
        // 外部转发配置无法热加载），提醒执行者重启服务端，避免新旧模式混用导致连接异常
        boolean newProxy = config.isProxy();
        if (oldProxy != newProxy) {
            String warn = Messages.get(Messages.CONFIG_PROXY_CHANGE_RESTART, String.valueOf(newProxy));
            sender.sendMessage(warn);
            (core != null ? core.getLogger() : plugin.getLogger()).warning(warn);
        }
        sender.sendMessage(Messages.CMD_RELOAD_SUCCESS);
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
