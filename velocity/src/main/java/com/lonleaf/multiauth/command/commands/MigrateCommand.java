package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import org.slf4j.Logger;
import com.velocitypowered.api.command.CommandSource;

import java.util.Arrays;
import java.util.List;

/**
 * /multiauth migrate &lt;sqlite|mysql&gt; —— 在 SQLite 与 MySQL 之间迁移数据。
 */
public class MigrateCommand implements Command {

    private final Core core;
    private final Logger logger;

    public MigrateCommand(Core core, Logger logger) {
        this.core = core;
        this.logger = logger;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("migrate")) {
            return false;
        }
        if (core == null) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_CORE_NOT_INITIALIZED)));
            return true;
        }
        if (args.length < 2) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_MIGRATE_USAGE)));
            return true;
        }

        String target = args[1].toLowerCase();
        if (!target.equals("sqlite") && !target.equals("mysql")) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_MIGRATE_INVALID_TYPE)));
            return true;
        }

        String currentType = core.getConfig().getDatabaseType().toLowerCase();
        if (currentType.equals(target)) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_MIGRATE_SAME_TYPE)));
            return true;
        }

        source.sendMessage(Command.legacy(Messages.get(Messages.CMD_MIGRATE_IN_PROGRESS, target)));
        int count = core.migrateDatabase(target);
        if (count >= 0) {
            source.sendMessage(Command.legacy(
                    Messages.get(Messages.CMD_MIGRATE_SUCCESS, String.valueOf(count), target)));
            logger.info(Messages.get(Messages.DB_MIGRATION_COMPLETE, String.valueOf(count), target));
        } else {
            source.sendMessage(Command.legacy(
                    Messages.get(Messages.CMD_MIGRATE_FAILED, Messages.CMD_CHECK_CONSOLE)));
        }
        return true;
    }

    @Override
    public List<String> suggest(String[] args) {
        if (args.length == 2) {
            return filter(Arrays.asList("sqlite", "mysql"), args[1]);
        }
        return List.of();
    }
}
