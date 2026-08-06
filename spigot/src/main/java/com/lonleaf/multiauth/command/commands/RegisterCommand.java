package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

/**
 * /register &lt;密码&gt; &lt;确认密码&gt; —— 离线玩家注册账号。
 */
public class RegisterCommand implements Command {

    private final AuthService authService;
    private final MultiAuth plugin;

    public RegisterCommand(AuthService authService, MultiAuth plugin) {
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
        if (args.length < 2) {
            player.sendMessage(Messages.AUTH_REGISTER_PROMPT);
            return true;
        }

        String password = args[0];
        String confirmPassword = args[1];
        String ip = getPlayerIp(player);

        player.sendMessage(Messages.AUTH_LOGIN_PROCESSING);

        authService.register(username, password, confirmPassword, ip)
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(result.message());
                    if (result.success()) {
                        for (String w : result.warnings()) {
                            player.sendMessage(w);
                        }
                        // 注册成功后标记已登录，否则玩家永久卡在未登录状态
                        authService.markLoggedIn(uuid);
                        plugin.onAuthLoginSuccess(player);
                        plugin.cancelAuthTimeout(uuid);
                    }
                }))
                .exceptionally(e -> {
                    plugin.getLogger().warning(Messages.get(Messages.AUTH_REGISTER_COMMAND_FAILED_LOG, username, e.getMessage()));
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.sendMessage(Messages.AUTH_REGISTER_FAILED));
                    return null;
                });
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        // 密码命令不做参数补全（安全考虑）
        return List.of();
    }

    /** 获取玩家 IP 地址，失败时返回 unknown */
    static String getPlayerIp(Player player) {
        try {
            InetAddress addr = player.getAddress().getAddress();
            return addr != null ? addr.getHostAddress() : Messages.GENERIC_UNKNOWN;
        } catch (Exception e) {
            return Messages.GENERIC_UNKNOWN;
        }
    }
}
