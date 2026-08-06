package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.db.AuthAccount;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.List;

/**
 * /multiauth unregister &lt;玩家&gt; —— 强制删除离线玩家的账号。
 */
public class UnregisterCommand implements Command {

    private final Core core;
    private final ProxyServer server;
    private final Logger logger;

    public UnregisterCommand(Core core, ProxyServer server, Logger logger) {
        this.core = core;
        this.server = server;
        this.logger = logger;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("unregister")) {
            return false;
        }
        if (core == null) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_CORE_NOT_INITIALIZED)));
            return true;
        }
        if (args.length < 2) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_UNREGISTER_USAGE)));
            return true;
        }

        String targetName = args[1];
        // 先获取账号（用于 IP 计数递减），再删除
        AuthAccount account = core.getDatabase().getAuthAccount(targetName);
        boolean deleted = core.getDatabase().deleteAuthAccount(targetName);
        if (deleted) {
            // 递减 IP 账号计数
            if (account != null && account.lastIp() != null) {
                core.getDatabase().decrementIpAccountCount(account.lastIp());
            }
            // 如果玩家在线，踢出
            server.getPlayer(targetName).ifPresent(player ->
                    player.disconnect(Command.legacy(Messages.get(Messages.AUTH_UNREGISTER_KICK))));
            logger.info(Messages.get(Messages.AUTH_ACCOUNT_UNREGISTERED_LOG, targetName));
            source.sendMessage(Command.legacy(Messages.get(Messages.AUTH_UNREGISTER_SUCCESS, targetName)));
        } else {
            source.sendMessage(Command.legacy(Messages.get(Messages.AUTH_UNREGISTER_NOT_FOUND, targetName)));
        }
        return true;
    }

    @Override
    public List<String> suggest(String[] args) {
        // 由 CommandManager 统一处理在线玩家补全
        return List.of();
    }
}
