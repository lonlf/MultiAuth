package com.lonleaf.multiauth.command;

import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.command.commands.ChangePasswordCommand;
import com.lonleaf.multiauth.command.commands.Command;
import com.lonleaf.multiauth.command.commands.LoginCommand;
import com.lonleaf.multiauth.command.commands.RegisterCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 玩家认证命令管理器：注册 /register、/login、/changepassword 三个独立顶级命令，
 * 按命令名分发到对应 {@link Command} 实现。
 */
public class AuthCommandManager {

    private final JavaPlugin plugin;
    private final Command registerCommand;
    private final Command loginCommand;
    private final Command changePasswordCommand;

    public AuthCommandManager(JavaPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        MultiAuth multiauth = (MultiAuth) plugin;
        this.registerCommand = new RegisterCommand(authService, multiauth);
        this.loginCommand = new LoginCommand(authService, multiauth);
        this.changePasswordCommand = new ChangePasswordCommand(authService, multiauth);

        registerCommands();
    }

    public void registerCommands() {
        registerAuthCommand("register", registerCommand);
        registerAuthCommand("login", loginCommand);
        registerAuthCommand("changepassword", changePasswordCommand);
    }

    /** 注册单个 auth 命令：设置 executor 和 tabCompleter */
    private void registerAuthCommand(String name, Command handler) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd == null) {
            return;
        }
        cmd.setExecutor((sender, command, label, args) -> handler.execute(sender, args));
        cmd.setTabCompleter((sender, command, label, args) -> handler.completeList(args));
    }
}
