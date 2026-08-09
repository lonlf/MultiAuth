package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.db.AuthAccount;
import com.lonleaf.multiauth.db.PlayerRecord;
import com.lonleaf.multiauth.geo.GeoInfo;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * /multiauth info &lt;玩家&gt; —— 查看玩家账号信息及认证状态。
 */
public class InfoCommand implements Command {

    private final JavaPlugin plugin;
    private final Core core;
    private final AuthService authService;
    private final SpigotConfig config;

    public InfoCommand(JavaPlugin plugin, Core core, AuthService authService, SpigotConfig config) {
        this.plugin = plugin;
        this.core = core;
        this.authService = authService;
        this.config = config;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("info")) {
            return false;
        }
        // 权限：管理员可查询任意玩家；普通玩家（multiauth.info，默认 true）仅能查询自己
        boolean isAdmin = sender.hasPermission("multiauth.admin");
        if (!isAdmin) {
            if (!(sender instanceof Player player) || !player.hasPermission("multiauth.info")) {
                sender.sendMessage(Messages.GENERIC_PERMISSION_DENIED);
                return true;
            }
            if (args.length >= 2 && !args[1].equalsIgnoreCase(player.getName())) {
                sender.sendMessage(Messages.get(Messages.CMD_INFO_SELF_ONLY));
                return true;
            }
        }
        if (authService == null) {
            sender.sendMessage(Messages.AUTH_MODULE_DISABLED);
            return true;
        }
        // 无目标参数：玩家（含管理员玩家）默认查自己；控制台必须指定玩家名
        if (args.length < 2) {
            if (sender instanceof Player p) {
                args = new String[]{args[0], p.getName()};
            } else {
                sender.sendMessage(Messages.CMD_INFO_USAGE);
                return true;
            }
        }

        String targetName = args[1];
        Player online = sender.getServer().getPlayerExact(targetName);
        String status = online != null
                ? Messages.get(Messages.AUTH_INFO_STATUS_ONLINE)
                : Messages.get(Messages.AUTH_INFO_STATUS_OFFLINE);
        AuthAccount account = authService.getAccountInfo(targetName);
        if (account != null) {
            // 离线账号：公共信息（UUID/状态/最后IP/地理位置）+ 离线专属信息
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String registerTime = sdf.format(new Date(account.registerTime()));
            String lastLogin = account.lastLoginTime() > 0
                    ? sdf.format(new Date(account.lastLoginTime()))
                    : Messages.AUTH_INFO_NEVER_LOGGED_IN;
            String lastIp = account.lastIp() != null ? account.lastIp() : Messages.get(Messages.GENERIC_UNKNOWN);
            String geo = formatGeo(account.lastIp());
            // 离线账号无存储 UUID，使用离线算法生成（离线 UUID 固定且可复现）
            String location = isAdmin ? formatLocation(getLocationRecord(targetName)) : null;
            String otherAccounts = formatOtherAccounts(account.lastIp(), isAdmin);
            sender.sendMessage(buildInfo(Messages.AUTH_INFO_OFFLINE_EXTRA,
                    targetName, AuthManager.generateOfflineUuid(targetName).toString(), status, lastIp, geo,
                    location, otherAccounts, registerTime, lastLogin));
            // 在线玩家用实时 UUID 校验（会话劫持检测）
            if (online != null) {
                checkUuidMismatch(sender, targetName, online.getUniqueId(), false);
            }
            return true;
        }

        // 正版玩家
        if (core != null && core.getAuthManager() != null) {
            PlayerRecord record = core.getAuthManager().getPlayerRecord(targetName);
            if (record != null && record.isPremium()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String lastUpdate = sdf.format(new Date(record.updatedAt()));
                String firstJoin = record.createdAt() > 0
                        ? sdf.format(new Date(record.createdAt()))
                        : Messages.get(Messages.GENERIC_UNKNOWN);
                String lastIp = record.lastIp() != null ? record.lastIp() : Messages.get(Messages.GENERIC_UNKNOWN);
                String geo = formatGeo(record.lastIp());
                String location = isAdmin ? formatLocation(record) : null;
                String otherAccounts = formatOtherAccounts(record.lastIp(), isAdmin);
                sender.sendMessage(buildInfo(Messages.AUTH_INFO_PREMIUM_EXTRA,
                        targetName, record.uuid().toString(), status, lastIp, geo,
                        location, otherAccounts, lastUpdate, firstJoin));
                // 校验记录 UUID 与预期是否一致（会话劫持检测）
                checkUuidMismatch(sender, targetName, record.uuid(), true);
                return true;
            }
        }

        sender.sendMessage(Messages.get(Messages.AUTH_INFO_NOT_REGISTERED, targetName));
        return true;
    }

    /** 组装信息：公共部分（玩家名/UUID/状态/最后IP/地理位置）+ 类型专属 + 登出地点 + 关联账号（可见性由调用方决定） */
    private String buildInfo(String extraKey, String name, String uuid, String status,
                             String lastIp, String geo, String location, String otherAccounts, String... extraArgs) {
        String base = Messages.get(Messages.AUTH_INFO_BASE, name, uuid, status, lastIp, geo);
        String msg = base + Messages.get(extraKey, extraArgs);
        if (location != null) {
            msg += location;
        }
        if (otherAccounts != null) {
            msg += otherAccounts;
        }
        return msg;
    }

    /**
     * 格式化多账号信息（按最近一次登录 IP 归因，参考 AuthMe 归属逻辑）。
     * 可见性与登出地点同级：管理员始终可见；普通玩家仅当 auth.notify-other-accounts 开启时可见。
     * 玩家更换 IP 后，新关联集合即按新 IP 反查得到的列表。在线账号显示绿色，离线保持白色。
     */
    private String formatOtherAccounts(String lastIp, boolean isAdmin) {
        boolean show = isAdmin || (config != null && config.getConfig().isAuthNotifyOtherAccounts());
        if (!show || lastIp == null || core == null || core.getAuthManager() == null) {
            return null;
        }
        List<String> accounts = core.getAuthManager().getAccountsByLastIp(lastIp);
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        if (accounts.size() <= 1) {
            return Messages.get(Messages.AUTH_INFO_OTHER_ACCOUNTS_NONE);
        }
        StringBuilder colored = new StringBuilder();
        for (int i = 0; i < accounts.size(); i++) {
            if (i > 0) {
                colored.append(", ");
            }
            String name = accounts.get(i);
            colored.append(isOnline(name) ? "§a" + name : "§f" + name);
        }
        return Messages.get(Messages.AUTH_INFO_OTHER_ACCOUNTS,
                String.valueOf(accounts.size()), colored.toString());
    }

    /** 账号是否在线（在线显示绿色，离线保持默认色） */
    private boolean isOnline(String name) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 查询位置记录（仅管理员查询登出地点时调用，无记录返回 null） */
    private PlayerRecord getLocationRecord(String username) {
        if (core == null || core.getAuthManager() == null) {
            return null;
        }
        return core.getAuthManager().getPlayerRecord(username);
    }

    /** 格式化登出地点（世界名 + 坐标），无记录或未记录位置时显示"未知" */
    private String formatLocation(PlayerRecord record) {
        if (record == null || record.lastWorld() == null) {
            return Messages.get(Messages.AUTH_INFO_LOCATION, Messages.get(Messages.GENERIC_UNKNOWN));
        }
        String coords = String.format("%.1f, %.1f, %.1f", record.lastX(), record.lastY(), record.lastZ());
        return Messages.get(Messages.AUTH_INFO_LOCATION, record.lastWorld() + " (" + coords + ")");
    }

    /**
     * 校验 UUID 与预期是否一致（合并自 /multiauth status 的玩家状态检查）。
     * use-mojang-uuid=true：正版玩家用 Mojang UUID（与离线 UUID 不同属正常）；
     * 离线账号与 use-mojang-uuid=false 时预期为离线 UUID，不一致则提示可能的会话劫持。
     */
    private void checkUuidMismatch(CommandSender sender, String username, UUID uuid, boolean verified) {
        boolean expectOfflineUuid = !verified || !config.isUseMojangUuid();
        UUID offlineUuid = AuthManager.generateOfflineUuid(username);
        if (expectOfflineUuid && !uuid.equals(offlineUuid)) {
            sender.sendMessage(Messages.get(Messages.AUTH_UUID_MISMATCH, username, offlineUuid.toString(), uuid.toString()));
        }
    }

    /** 查询 IP 地理位置（国家/省份/城市），geo 服务未就绪或无结果时返回"未知" */
    private String formatGeo(String ip) {
        if (ip == null || core == null || core.getIpGeoService() == null || !core.getIpGeoService().isReady()) {
            return Messages.get(Messages.GENERIC_UNKNOWN);
        }
        GeoInfo info = core.getIpGeoService().search(ip);
        if (info == null) {
            return Messages.get(Messages.GENERIC_UNKNOWN);
        }
        StringBuilder sb = new StringBuilder();
        if (info.country() != null) sb.append(info.country());
        if (info.province() != null) sb.append('/').append(info.province());
        if (info.city() != null) sb.append('/').append(info.city());
        return sb.length() == 0 ? Messages.get(Messages.GENERIC_UNKNOWN) : sb.toString();
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return null;
        }
        return List.of();
    }
}
