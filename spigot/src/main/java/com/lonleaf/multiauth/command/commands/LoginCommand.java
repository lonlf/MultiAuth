package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * /login &lt;密码&gt; —— 离线玩家登录。
 */
public class LoginCommand implements Command {

    private final AuthService authService;
    private final MultiAuth plugin;

    public LoginCommand(AuthService authService, MultiAuth plugin) {
        this.authService = authService;
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.GENERIC_PLAYER_ONLY);
            return true;
        }

        String username = player.getName();
        UUID uuid = player.getUniqueId();

        if (authService.isLoggedIn(uuid)) {
            player.sendMessage(Messages.AUTH_LOGIN_ALREADY);
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Messages.AUTH_LOGIN_PROMPT);
            return true;
        }

        String password = args[0];
        String ip = RegisterCommand.getPlayerIp(player);

        player.sendMessage(Messages.AUTH_LOGIN_PROCESSING);

        authService.login(username, password, ip, uuid)
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (result.shouldKick()) {
                        // 达到失败阈值/IP 限制/异地踢出 → 踢出玩家
                        player.kickPlayer(result.message());
                        plugin.cancelAuthTimeout(uuid);
                        return;
                    }
                    player.sendMessage(result.message());
                    if (result.success()) {
                        // 发送附加警告（IP 变更/异地登录等）
                        for (String w : result.warnings()) {
                            player.sendMessage(w);
                        }
                        plugin.onAuthLoginSuccess(player);
                        plugin.cancelAuthTimeout(uuid);
                    }
                }))
                .exceptionally(e -> {
                    plugin.getLogger().warning(Messages.get(Messages.AUTH_LOGIN_COMMAND_FAILED_LOG, username, e.getMessage()));
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.sendMessage(Messages.AUTH_LOGIN_FAILED));
                    return null;
                });
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        // 密码命令不做参数补全（安全考虑）
        return List.of();
    }
}
