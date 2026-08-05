package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.db.PlayerRecord;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * /multiauth status [玩家] —— 查看插件状态或指定玩家的认证状态。
 */
public class StatusCommand implements Command {

    private final Core core;
    private final SpigotConfig config;

    public StatusCommand(Core core, SpigotConfig config) {
        this.core = core;
        this.config = config;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("status")) {
            return false;
        }
        if (!sender.hasPermission("multiauth.status") && !sender.hasPermission("multiauth.admin")) {
            sender.sendMessage(Messages.GENERIC_PERMISSION_DENIED);
            return true;
        }

        if (args.length >= 2) {
            String username = args[1];
            Player target = sender.getServer().getPlayerExact(username);
            if (target != null) {
                sendPlayerStatus(sender, target.getName(), target.getUniqueId());
            } else {
                AuthManager authManager = core != null ? core.getAuthManager() : null;
                PlayerRecord record = authManager != null ? authManager.getPlayerRecord(username) : null;
                if (record != null) {
                    sendPlayerStatus(sender, record.username(), record.uuid());
                } else {
                    sender.sendMessage(Messages.get(Messages.GENERIC_PLAYER_NOT_FOUND, username));
                }
            }
        } else if (sender instanceof Player player) {
            sendPlayerStatus(sender, player.getName(), player.getUniqueId());
        } else {
            if (core == null) {
                sender.sendMessage(Messages.AUTH_SERVICE_NOT_INITIALIZED);
                return true;
            }
            boolean dbHealthy = core.isDatabaseHealthy();
            String dbType = core.getConfig().getDatabaseType();
            String dbStatus = dbHealthy ? Messages.DB_STATUS_HEALTHY : Messages.DB_STATUS_UNHEALTHY;
            AuthManager authManager = core.getAuthManager();
            int premium = authManager != null ? authManager.getPremiumRecordCount() : -1;
            int total = authManager != null ? authManager.getRecordCount() : -1;
            String premiumCount = premium >= 0 ? String.valueOf(premium) : "?";
            String totalRecords = total >= 0 ? String.valueOf(total) : "?";
            sender.sendMessage(Messages.get(Messages.CMD_STATUS, dbType + " (" + dbStatus + ")",
                    config.isProxy() ? Messages.CMD_MODE_PROXY : Messages.CMD_MODE_DIRECT,
                    premiumCount, totalRecords));
        }
        return true;
    }

    private void sendPlayerStatus(CommandSender sender, String username, UUID uuid) {
        AuthManager authManager = core != null ? core.getAuthManager() : null;
        PlayerRecord record = authManager != null ? authManager.getPlayerRecord(username) : null;
        boolean verified = record != null && record.isPremium();
        UUID offlineUuid = AuthManager.generateOfflineUuid(username);

        sender.sendMessage(Messages.get(Messages.CMD_STATUS_PLAYER_TITLE, username));
        sender.sendMessage(Messages.get(Messages.SESSION_JOIN_NOTIFY, username, uuid.toString(), verified ? "true" : "false"));

        // UUID 不匹配检查：仅在实际 UUID 与预期不符时报警告
        // use-mojang-uuid=true：正版玩家用 Mojang UUID（与离线 UUID 不同是正常的），离线玩家应用离线 UUID
        // use-mojang-uuid=false：所有玩家都应用离线 UUID
        boolean expectOfflineUuid = !verified || !config.isUseMojangUuid();
        if (expectOfflineUuid && !uuid.equals(offlineUuid)) {
            sender.sendMessage(Messages.get(Messages.AUTH_UUID_MISMATCH, username, offlineUuid.toString(), uuid.toString()));
        }
    }

    @Override
    public List<String> completeList(String[] args) {
        // args.length == 2 时需要玩家名补全，返回 null 使用 Bukkit 默认在线玩家列表
        if (args.length == 2) {
            return null;
        }
        return List.of();
    }
}
