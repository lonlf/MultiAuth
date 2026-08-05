package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.VelocityConfig;
import com.velocitypowered.api.command.CommandSource;
import org.slf4j.Logger;

import java.util.List;

/**
 * /multiauth reload —— 重载 Velocity 端配置、语言文件和 Core。
 */
public class ReloadCommand implements Command {

    private final VelocityConfig config;
    private final Core core;
    private final Logger logger;
    private final MultiAuth plugin;

    public ReloadCommand(VelocityConfig config, Core core, Logger logger, MultiAuth plugin) {
        this.config = config;
        this.core = core;
        this.logger = logger;
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            return false;
        }
        // synchronized 保证 config → Messages → core 整体重载原子性，与 onProxyReload 一致，
        // 避免登录事件线程读到中间不一致状态
        synchronized (plugin) {
            config.reload();
            Messages.reload(config.getConfig().getLanguage());
            if (core != null) {
                core.reload(config.getConfig());
            }
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_RELOAD_SUCCESS)));
            logger.info(Messages.get(Messages.CONFIG_RELOADED));
        }
        return true;
    }

    @Override
    public List<String> suggest(String[] args) {
        return List.of();
    }
}
