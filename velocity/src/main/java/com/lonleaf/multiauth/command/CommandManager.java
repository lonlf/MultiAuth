package com.lonleaf.multiauth.command;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.VelocityConfig;
import com.lonleaf.multiauth.command.commands.BackupCommand;
import com.lonleaf.multiauth.command.commands.Command;
import com.lonleaf.multiauth.command.commands.InfoCommand;
import com.lonleaf.multiauth.command.commands.MigrateCommand;
import com.lonleaf.multiauth.command.commands.ReloadCommand;
import com.lonleaf.multiauth.command.commands.StatusCommand;
import com.lonleaf.multiauth.command.commands.UnregisterCommand;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Velocity 端命令管理器：注册 /multiauth 主命令，按 args[0] 分发到子命令。
 */
public class CommandManager {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("reload", "migrate", "backup", "status", "info", "unregister");

    private final Command reloadCommand;
    private final Command migrateCommand;
    private final Command backupCommand;
    private final Command statusCommand;
    private final Command infoCommand;
    private final Command unregisterCommand;
    private final ProxyServer server;

    public CommandManager(MultiAuth plugin, Core core, VelocityConfig config, Logger logger, ProxyServer server) {
        this.server = server;
        this.reloadCommand = new ReloadCommand(config, core, logger, plugin);
        this.migrateCommand = new MigrateCommand(core, logger);
        this.backupCommand = new BackupCommand(core);
        this.statusCommand = new StatusCommand(core, config);
        this.infoCommand = new InfoCommand(core);
        this.unregisterCommand = new UnregisterCommand(core, server, logger);
        registerCommand(plugin, server);
    }

    private void registerCommand(MultiAuth plugin, ProxyServer server) {
        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("vmultiauth").build(),
                new MultiAuthRootCommand()
        );
    }

    /** RawCommand 实现：提取 source + args，遍历子命令分发 */
    private class MultiAuthRootCommand implements RawCommand {

        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments().trim().split("\\s+");
            if (args.length == 0 || args[0].isEmpty()) {
                sendUsage(invocation);
                return;
            }
            // 管理员子命令需要 multiauth.admin 权限；status 需要 multiauth.status（默认 true）
            String sub = args[0].toLowerCase();
            boolean isAdmin = invocation.source().hasPermission("multiauth.admin");
            if (!sub.equals("status") && !isAdmin) {
                invocation.source().sendMessage(
                        Command.legacy(Messages.get(Messages.CMD_NO_PERMISSION)));
                return;
            }
            // 遍历子命令，首个匹配的执行并返回
            List<Boolean> results = new ArrayList<>();
            results.add(reloadCommand.execute(invocation.source(), args));
            results.add(migrateCommand.execute(invocation.source(), args));
            results.add(backupCommand.execute(invocation.source(), args));
            results.add(statusCommand.execute(invocation.source(), args));
            results.add(infoCommand.execute(invocation.source(), args));
            results.add(unregisterCommand.execute(invocation.source(), args));
            if (!results.contains(Boolean.TRUE)) {
                sendUsage(invocation);
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            // proxy=true 模式下玩家使用 Spigot 端 /multiauth 命令；Velocity 端命令仅控制台可用
            return invocation.source() instanceof ConsoleCommandSource;
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments().trim().split("\\s+", -1);
            boolean isAdmin = invocation.source().hasPermission("multiauth.admin");
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0];
                List<String> result = new ArrayList<>();
                for (String sub : SUBCOMMANDS) {
                    // 非管理员只能看到 status（与 Spigot 端 filterSubCommands 一致）
                    if (!sub.equals("status") && !isAdmin) continue;
                    if (sub.startsWith(prefix.toLowerCase())) {
                        result.add(sub);
                    }
                }
                return result;
            }
            // 非管理员无补全
            if (!isAdmin) return new ArrayList<>();
            // migrate 子命令补全 sqlite/mysql
            if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
                return migrateCommand.suggest(args);
            }
            // info / unregister 补全在线玩家名
            if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                    || args[0].equalsIgnoreCase("unregister"))) {
                String prefix = args[1].toLowerCase();
                List<String> names = new ArrayList<>();
                server.getAllPlayers().forEach(p -> {
                    if (p.getUsername().toLowerCase().startsWith(prefix)) {
                        names.add(p.getUsername());
                    }
                });
                return names;
            }
            return new ArrayList<>();
        }

        private void sendUsage(Invocation invocation) {
            invocation.source().sendMessage(Command.legacy(Messages.get(Messages.CMD_HELP)));
        }
    }
}
