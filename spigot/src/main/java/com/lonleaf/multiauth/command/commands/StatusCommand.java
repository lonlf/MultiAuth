package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.mojang.MojangApiService;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /multiauth status —— 查看插件运行状态（版本、数据库、模式、API、玩家统计）。
 */
public class StatusCommand implements Command {

    private final MultiAuth plugin;
    private final Core core;
    private final SpigotConfig config;

    public StatusCommand(MultiAuth plugin, Core core, SpigotConfig config) {
        this.plugin = plugin;
        this.core = core;
        this.config = config;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("status")) {
            return false;
        }
        if (!sender.hasPermission("multiauth.admin")) {
            sender.sendMessage(Messages.GENERIC_PERMISSION_DENIED);
            return true;
        }
        if (core == null) {
            sender.sendMessage(Messages.AUTH_SERVICE_NOT_INITIALIZED);
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

        sender.sendMessage(Messages.get(Messages.CMD_STATUS,
                plugin.getDescription().getVersion(),
                dbType + " (" + dbStatus + ")",
                config.isProxy() ? Messages.CMD_MODE_PROXY : Messages.CMD_MODE_DIRECT,
                resolveApiStatus(),
                totalRecords, premiumCount));

        // 更新检查信息：当前版本 + 最新版本（未检查/检查失败时为"未知"）
        com.lonleaf.multiauth.update.UpdateChecker updateChecker = core.getUpdateChecker();
        String latestVersion = updateChecker != null && updateChecker.getLastResult() != null
                ? updateChecker.getLastResult().latestVersion()
                : Messages.get(Messages.GENERIC_UNKNOWN);
        sender.sendMessage(Messages.get(Messages.UPDATE_STATUS_CURRENT, plugin.getDescription().getVersion()));
        sender.sendMessage(Messages.get(Messages.UPDATE_STATUS_LATEST, latestVersion));
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
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
