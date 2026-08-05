package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.db.AuthAccount;
import com.lonleaf.multiauth.db.PlayerRecord;
import org.bukkit.command.CommandSender;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * /multiauth info &lt;玩家&gt; —— 查看玩家账号信息。
 */
public class InfoCommand implements Command {

    private final Core core;
    private final AuthService authService;

    public InfoCommand(Core core, AuthService authService) {
        this.core = core;
        this.authService = authService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("info")) {
            return false;
        }
        if (!sender.hasPermission("multiauth.admin")) {
            sender.sendMessage(Messages.GENERIC_PERMISSION_DENIED);
            return true;
        }
        if (authService == null) {
            sender.sendMessage(Messages.AUTH_MODULE_DISABLED);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.CMD_INFO_USAGE);
            return true;
        }

        String targetName = args[1];
        AuthAccount account = authService.getAccountInfo(targetName);
        if (account != null) {
            // 离线玩家：显示 auth 账号信息
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String registerTime = sdf.format(new Date(account.registerTime()));
            String lastLogin = account.lastLoginTime() > 0
                    ? sdf.format(new Date(account.lastLoginTime()))
                    : Messages.AUTH_INFO_NEVER_LOGGED_IN;
            String lastIp = account.lastIp() != null ? account.lastIp() : Messages.GENERIC_UNKNOWN;
            sender.sendMessage(Messages.get(Messages.AUTH_INFO_FORMAT,
                    targetName, registerTime, lastLogin, lastIp));
            return true;
        }

        // 无 auth 账号：查询 PlayerRecord（正版玩家）
        if (core != null && core.getAuthManager() != null) {
            PlayerRecord record = core.getAuthManager().getPlayerRecord(targetName);
            if (record != null && record.isPremium()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String lastUpdate = sdf.format(new Date(record.updatedAt()));
                sender.sendMessage(Messages.get(Messages.AUTH_INFO_PREMIUM_FORMAT,
                        targetName, record.uuid().toString(), lastUpdate));
                return true;
            }
        }

        sender.sendMessage(Messages.get(Messages.AUTH_INFO_NOT_REGISTERED, targetName));
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return null;
        }
        return List.of();
    }
}
