package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.VelocityConfig;
import com.velocitypowered.api.command.CommandSource;

import java.util.List;

/**
 * /multiauth status —— 查看插件运行状态（数据库类型/健康度、UUID 模式、认证列表、备用 API）。
 */
public class StatusCommand implements Command {

    private final Core core;
    private final VelocityConfig config;

    public StatusCommand(Core core, VelocityConfig config) {
        this.core = core;
        this.config = config;
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
        boolean useMojangUuid = config.isUseMojangUuid();
        String dbStatusText = dbHealthy
                ? "§a" + Messages.get(Messages.DB_STATUS_HEALTHY)
                : "§c" + Messages.get(Messages.DB_STATUS_UNHEALTHY);

        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS_TITLE)));
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS_DB_TYPE, dbType)));
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS_DB_STATUS, dbStatusText)));
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS_USE_MOJANG_UUID, String.valueOf(useMojangUuid))));
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS_AUTH_LIST, String.join(", ", config.getConfig().getAuthList()))));

        List<String> fallbackUrls = core.getConfig().getFallbackApiUrls();
        String fallbackDisplay = fallbackUrls.isEmpty()
                ? Messages.get(Messages.CMD_STATUS_FALLBACK_NOT_CONFIGURED)
                : String.join(", ", fallbackUrls);
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_STATUS_FALLBACK_API, fallbackDisplay)));
        return true;
    }

    @Override
    public List<String> suggest(String[] args) {
        return List.of();
    }
}
