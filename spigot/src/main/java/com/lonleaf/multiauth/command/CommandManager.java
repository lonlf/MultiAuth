package com.lonleaf.multiauth.command;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.command.commands.BackupCommand;
import com.lonleaf.multiauth.command.commands.Command;
import com.lonleaf.multiauth.command.commands.InfoCommand;
import com.lonleaf.multiauth.command.commands.MigrateCommand;
import com.lonleaf.multiauth.command.commands.ReloadCommand;
import com.lonleaf.multiauth.command.commands.StatusCommand;
import com.lonleaf.multiauth.command.commands.UnregisterCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令管理器：注册主命令 /multiauth，按 args[0] 分发到各子命令。
 */
public class CommandManager {

    private final JavaPlugin plugin;
    private final Command reloadCommand;
    private final Command migrateCommand;
    private final Command backupCommand;
    private final Command statusCommand;
    private final Command infoCommand;
    private final Command unregisterCommand;

    /** 所有子命令（按注册顺序遍历） */
    private final List<Command> subCommands;

    public CommandManager(JavaPlugin plugin, Core core, SpigotConfig config, AuthService authService) {
        this.plugin = plugin;
        this.reloadCommand = new ReloadCommand((MultiAuth) plugin, config, core);
        this.migrateCommand = new MigrateCommand(plugin, core);
        this.backupCommand = new BackupCommand(core);
        this.statusCommand = new StatusCommand((MultiAuth) plugin, core, config);
        this.infoCommand = new InfoCommand(core, authService, config);
        this.unregisterCommand = new UnregisterCommand((MultiAuth) plugin, authService);

        this.subCommands = List.of(
                reloadCommand, migrateCommand, backupCommand,
                statusCommand, infoCommand, unregisterCommand
        );

        registerCommands();
    }

    public void registerCommands() {
        PluginCommand multiauthCommand = plugin.getCommand("multiauth");
        if (multiauthCommand == null) {
            return;
        }

        multiauthCommand.setExecutor((sender, command, label, args) -> {
            if (args.length == 0) {
                sender.sendMessage(Messages.CMD_HELP);
                return true;
            }
            // 遍历子命令，首个匹配（返回 true）的负责处理
            for (Command sub : subCommands) {
                if (sub.execute(sender, args)) {
                    return true;
                }
            }
            // 无匹配：显示帮助
            sender.sendMessage(Messages.CMD_HELP);
            return true;
        });

        multiauthCommand.setTabCompleter((sender, command, label, args) -> {
            if (args.length <= 1) {
                // 补全子命令名（按权限过滤）
                return filterSubCommands(sender, args.length == 1 ? args[0] : "");
            }
            // 按 args[0] 路由到对应子命令的补全
            return switch (args[0].toLowerCase()) {
                case "reload" -> reloadCommand.completeList(args);
                case "migrate" -> migrateCommand.completeList(args);
                case "backup" -> backupCommand.completeList(args);
                case "status" -> statusCommand.completeList(args);
                case "info" -> {
                    // 非管理员玩家仅补全自己的名字
                    if (!sender.hasPermission("multiauth.admin")) {
                        yield List.of(sender.getName());
                    }
                    yield infoCommand.completeList(args);
                }
                case "unregister" -> unregisterCommand.completeList(args);
                default -> List.of();
            };
        });
    }

    /** 按权限和前缀过滤可用子命令 */
    private List<String> filterSubCommands(org.bukkit.command.CommandSender sender, String prefix) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission("multiauth.admin")) {
            subs.add("reload");
            subs.add("migrate");
            subs.add("backup");
            subs.add("status");
            subs.add("info");
            subs.add("unregister");
        } else if (sender.hasPermission("multiauth.info")) {
            // 普通玩家仅可查询自己的信息
            subs.add("info");
        }
        // 复用 Command 接口的 filter 默认方法逻辑
        if (prefix == null || prefix.isEmpty()) {
            return subs;
        }
        List<String> filtered = new ArrayList<>();
        for (String s : subs) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) {
                filtered.add(s);
            }
        }
        return filtered;
    }
}
