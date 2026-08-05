package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /multiauth unregister &lt;玩家&gt; —— 强制删除离线玩家的账号。
 */
public class UnregisterCommand implements Command {

    private final MultiAuth plugin;
    private final AuthService authService;

    public UnregisterCommand(MultiAuth plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("unregister")) {
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
            sender.sendMessage(Messages.CMD_UNREGISTER_USAGE);
            return true;
        }

        String targetName = args[1];
        // unregister 内部已递减 IP 账号计数（使用账号 lastIp），此处无需再手动递减
        boolean deleted = authService.unregister(targetName);
        if (deleted) {
            // 如果玩家在线，清除其登录状态并踢出，避免注销后仍处于已登录状态
            Player target = plugin.getServer().getPlayerExact(targetName);
            if (target != null) {
                authService.getSessionManager().remove(target.getUniqueId());
                target.kickPlayer(Messages.AUTH_UNREGISTER_KICK);
            }
            sender.sendMessage(Messages.get(Messages.AUTH_UNREGISTER_SUCCESS, targetName));
        } else {
            sender.sendMessage(Messages.get(Messages.AUTH_UNREGISTER_NOT_FOUND, targetName));
        }
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
