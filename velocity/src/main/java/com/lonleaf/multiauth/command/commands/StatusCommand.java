package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.mojang.MojangApiService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.plugin.Plugin;

import java.util.List;

/**
 * /vmultiauth status —— 查看插件运行状态（版本、数据库、模式、API、玩家统计）。
 */
public class StatusCommand implements Command {

    private final MultiAuth plugin;
    private final Core core;

    public StatusCommand(MultiAuth plugin, Core core) {
        this.plugin = plugin;
        this.core = core;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("status")) {
            return false;
        }
        if (core == null) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_CORE_NOT_INITIALIZED)));
            return true;
        }

        boolean dbHealthy = core.isDatabaseHealthy();
        String dbType = core.getConfig().getDatabaseType();
        String dbStatus = dbHealthy ? Messages.DB_STATUS_HEALTHY : Messages.DB_STATUS_UNHEALTHY;
        AuthManager authManager = core.getAuthManager();
        int premium = authManager != null ? authManager.getPremiumRecordCount() : -1;
        int total = authManager != null ? authManager.getRecordCount() : -1;
        String premiumCount = premium >= 0 ? String.valueOf(premium) : "?";
        String totalRecords = total >= 0 ? String.valueOf(total) : "?";

        // Velocity 端始终作为代理执行验证
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS,
                plugin.getClass().getAnnotation(Plugin.class).version(),
                dbType + " (" + dbStatus + ")",
                Messages.CMD_MODE_PROXY,
                resolveApiStatus(),
                totalRecords, premiumCount)));
        return true;
    }

    /** API 状态：未初始化/未启用/宕机/正常（基于真实验证调用的宕机跟踪，无额外网络请求） */
    private String resolveApiStatus() {
        MojangApiService api = core.getMojangApiService();
        if (api == null) {
            return Messages.get(Messages.API_STATUS_UNKNOWN);
        }
        if (!api.isEnabled()) {
            return Messages.get(Messages.API_STATUS_DISABLED);
        }
        return api.isAllDown()
                ? Messages.get(Messages.API_STATUS_DOWN)
                : Messages.get(Messages.API_STATUS_NORMAL);
    }

    @Override
    public List<String> suggest(String[] args) {
        return List.of();
    }
}
