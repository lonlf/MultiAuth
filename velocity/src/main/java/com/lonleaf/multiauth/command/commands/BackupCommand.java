package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.velocitypowered.api.command.CommandSource;

import java.util.List;

/**
 * /multiauth backup —— 立即执行一次数据库备份。
 */
public class BackupCommand implements Command {

    private final Core core;

    public BackupCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("backup")) {
            return false;
        }
        if (core == null) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_CORE_NOT_INITIALIZED)));
            return true;
        }
        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_BACKUP_IN_PROGRESS)));
        boolean ok = core.manualBackup();
        if (ok) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_BACKUP_SUCCESS)));
        } else {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_BACKUP_FAILED)));
        }
        return true;
    }

    @Override
    public List<String> suggest(String[] args) {
        return List.of();
    }
}
