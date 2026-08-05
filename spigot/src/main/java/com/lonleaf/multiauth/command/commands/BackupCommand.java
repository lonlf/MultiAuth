package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /multiauth backup —— 强制备份数据库。
 */
public class BackupCommand implements Command {

    private final Core core;

    public BackupCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("backup")) {
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

        boolean ok = core.manualBackup();
        if (ok) {
            sender.sendMessage(Messages.CMD_BACKUP_SUCCESS);
        } else {
            sender.sendMessage(Messages.CMD_BACKUP_FAILED);
        }
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
