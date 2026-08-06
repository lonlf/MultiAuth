package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * /changepassword &lt;旧密码&gt; &lt;新密码&gt; —— 修改离线玩家密码。
 */
public class ChangePasswordCommand implements Command {

    private final AuthService authService;
    private final MultiAuth plugin;

    public ChangePasswordCommand(AuthService authService, MultiAuth plugin) {
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

        if (!authService.isLoggedIn(uuid)) {
            player.sendMessage(Messages.AUTH_NOT_LOGGED_IN);
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Messages.CMD_CHANGEPASSWORD_USAGE);
            return true;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        player.sendMessage(Messages.AUTH_LOGIN_PROCESSING);

        authService.changePassword(username, oldPassword, newPassword, uuid)
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(result.message())))
                .exceptionally(e -> {
                    plugin.getLogger().warning(Messages.get(Messages.AUTH_CHANGEPASSWORD_COMMAND_FAILED_LOG, username, e.getMessage()));
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.sendMessage(Messages.AUTH_CHANGEPASSWORD_FAILED));
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
