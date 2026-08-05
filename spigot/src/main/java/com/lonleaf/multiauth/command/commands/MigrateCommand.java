package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * /multiauth migrate &lt;sqlite|mysql&gt; —— 迁移数据库。
 */
public class MigrateCommand implements Command {

    private final Core core;

    public MigrateCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("migrate")) {
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
        if (args.length < 2) {
            sender.sendMessage(Messages.CMD_MIGRATE_USAGE);
            return true;
        }

        String target = args[1].toLowerCase();
        if (!target.equals("sqlite") && !target.equals("mysql")) {
            sender.sendMessage(Messages.CMD_MIGRATE_INVALID_TYPE);
            return true;
        }

        String currentType = core.getConfig().getDatabaseType().toLowerCase();
        if (currentType.equals(target)) {
            sender.sendMessage(Messages.get(Messages.CMD_MIGRATE_FAILED,
                    Messages.CMD_MIGRATE_SAME_TYPE));
            return true;
        }

        int count = core.migrateDatabase(target);
        if (count >= 0) {
            sender.sendMessage(Messages.get(Messages.CMD_MIGRATE_SUCCESS, String.valueOf(count), target));
        } else {
            sender.sendMessage(Messages.get(Messages.CMD_MIGRATE_FAILED, Messages.CMD_CHECK_CONSOLE));
        }
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return filter(Arrays.asList("sqlite", "mysql"), args[1]);
        }
        return List.of();
    }
}
