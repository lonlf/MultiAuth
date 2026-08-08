package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;

/**
 * /multiauth migrate &lt;sqlite|mysql&gt; —— 迁移数据库。
 */
public class MigrateCommand implements Command {

    private final Core core;
    private final JavaPlugin plugin;

    public MigrateCommand(JavaPlugin plugin, Core core) {
        this.plugin = plugin;
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

        // 异步执行迁移，避免大库全表读写阻塞主线程导致服务器 tick 停滞（#15）
        sender.sendMessage(Messages.get(Messages.CMD_MIGRATE_IN_PROGRESS, target));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int count = core.migrateDatabase(target);
            // 回到主线程回发结果，保证命令发送线程安全
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (count >= 0) {
                    sender.sendMessage(Messages.get(Messages.CMD_MIGRATE_SUCCESS, String.valueOf(count), target));
                } else {
                    sender.sendMessage(Messages.get(Messages.CMD_MIGRATE_FAILED, Messages.CMD_CHECK_CONSOLE));
                }
            });
        });
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
