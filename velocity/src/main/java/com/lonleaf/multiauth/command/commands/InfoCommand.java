package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.db.AuthAccount;
import com.lonleaf.multiauth.db.PlayerRecord;
import com.velocitypowered.api.command.CommandSource;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * /multiauth info &lt;玩家&gt; —— 查看玩家账号信息。
 */
public class InfoCommand implements Command {

    private final Core core;

    public InfoCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("info")) {
            return false;
        }
        if (core == null) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_CORE_NOT_INITIALIZED)));
            return true;
        }
        if (args.length < 2) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_INFO_USAGE)));
            return true;
        }

        String targetName = args[1];
        AuthAccount account = core.getDatabase().getAuthAccount(targetName);
        if (account != null) {
            // 离线玩家：显示 auth 账号信息
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String registerTime = sdf.format(new Date(account.registerTime()));
            String lastLogin = account.lastLoginTime() > 0
                    ? sdf.format(new Date(account.lastLoginTime()))
                    : Messages.get(Messages.AUTH_INFO_NEVER_LOGGED_IN);
            String lastIp = account.lastIp() != null ? account.lastIp() : Messages.get(Messages.GENERIC_UNKNOWN);
            source.sendMessage(Command.legacy(Messages.get(Messages.AUTH_INFO_FORMAT,
                    targetName, registerTime, lastLogin, lastIp)));
            return true;
        }

        // 无 auth 账号：查询 PlayerRecord（正版玩家）
        PlayerRecord record = core.getAuthManager().getPlayerRecord(targetName);
        if (record != null && record.isPremium()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String lastUpdate = sdf.format(new Date(record.updatedAt()));
            source.sendMessage(Command.legacy(Messages.get(Messages.AUTH_INFO_PREMIUM_FORMAT,
                    targetName, record.uuid().toString(), lastUpdate)));
            return true;
        }

        source.sendMessage(Command.legacy(Messages.get(Messages.AUTH_INFO_NOT_REGISTERED, targetName)));
        return true;
    }

    @Override
    public List<String> suggest(String[] args) {
        // 由 CommandManager 统一处理在线玩家补全
        return List.of();
    }
}
