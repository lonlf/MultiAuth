package com.lonleaf.multiauth.command.commands;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.db.AuthAccount;
import com.lonleaf.multiauth.db.PlayerRecord;
import com.lonleaf.multiauth.geo.GeoInfo;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * /vmultiauth info &lt;玩家&gt; —— 查看玩家账号信息。
 */
public class InfoCommand implements Command {

    private final Core core;
    private final ProxyServer server;

    public InfoCommand(Core core, ProxyServer server) {
        this.core = core;
        this.server = server;
    }

    @Override
    public boolean execute(CommandSource source, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("info")) {
            return false;
        }
        if (core == null) {
            source.sendMessage(Command.legacy(Messages.get(Messages.CMD_CORE_NOT_INITIALIZED)));
            return true;
        }
        // 权限：管理员可查询任意玩家；普通玩家（multiauth.info，默认 true）仅能查询自己
        boolean isAdmin = source.hasPermission("multiauth.admin");
        if (!isAdmin) {
            if (!(source instanceof Player player) || !player.hasPermission("multiauth.info")) {
                source.sendMessage(Command.legacy(Messages.get(Messages.CMD_NO_PERMISSION)));
                return true;
            }
            if (args.length >= 2 && !args[1].equalsIgnoreCase(player.getUsername())) {
                source.sendMessage(Command.legacy(Messages.get(Messages.CMD_INFO_SELF_ONLY)));
                return true;
            }
        }
        // 无目标参数：玩家（含管理员玩家）默认查自己；控制台必须指定玩家名
        if (args.length < 2) {
            if (source instanceof Player p) {
                args = new String[]{args[0], p.getUsername()};
            } else {
                source.sendMessage(Command.legacy(Messages.get(Messages.CMD_INFO_USAGE)));
                return true;
            }
        }

        String targetName = args[1];
        String status = server.getPlayer(targetName).isPresent()
                ? Messages.get(Messages.AUTH_INFO_STATUS_ONLINE)
                : Messages.get(Messages.AUTH_INFO_STATUS_OFFLINE);
        AuthAccount account = core.getDatabase().getAuthAccount(targetName);
        if (account != null) {
            // 离线账号：公共信息（UUID/状态/最后IP/地理位置）+ 离线专属信息
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String registerTime = sdf.format(new Date(account.registerTime()));
            String lastLogin = account.lastLoginTime() > 0
                    ? sdf.format(new Date(account.lastLoginTime()))
                    : Messages.get(Messages.AUTH_INFO_NEVER_LOGGED_IN);
            String lastIp = account.lastIp() != null ? account.lastIp() : Messages.get(Messages.GENERIC_UNKNOWN);
            String geo = formatGeo(account.lastIp());
            // 离线账号无存储 UUID，使用离线算法生成（离线 UUID 固定且可复现）
            String location = isAdmin ? formatLocation(core.getAuthManager().getPlayerRecord(targetName)) : null;
            String otherAccounts = formatOtherAccounts(account.lastIp(), isAdmin);
            source.sendMessage(Command.legacy(buildInfo(Messages.AUTH_INFO_OFFLINE_EXTRA,
                    targetName, AuthManager.generateOfflineUuid(targetName).toString(), status, lastIp, geo,
                    location, otherAccounts, registerTime, lastLogin)));
            return true;
        }

        // 正版玩家
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
            source.sendMessage(Command.legacy(buildInfo(Messages.AUTH_INFO_PREMIUM_EXTRA,
                    targetName, record.uuid().toString(), status, lastIp, geo,
                    location, otherAccounts, lastUpdate, firstJoin)));
            return true;
        }

        source.sendMessage(Command.legacy(Messages.get(Messages.AUTH_INFO_NOT_REGISTERED, targetName)));
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
     * 可见性与登出地点同级：管理员始终可见；普通玩家可见性由 Spigot 端
     * auth.notify-other-accounts 决定（该开关唯一真源在 Spigot config.yml，
     * 控制登录提示与 Spigot 端 info，此处仅从共享 AuthConfig 读取同一字段）。
     * 玩家更换 IP 后，新关联集合即按新 IP 反查得到的列表。
     */
    private String formatOtherAccounts(String lastIp, boolean isAdmin) {
        boolean show = isAdmin || core.getConfig().isAuthNotifyOtherAccounts();
        if (!show || lastIp == null || core.getAuthManager() == null) {
            return null;
        }
        List<String> accounts = core.getAuthManager().getAccountsByLastIp(lastIp);
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        if (accounts.size() <= 1) {
            return Messages.get(Messages.AUTH_INFO_OTHER_ACCOUNTS_NONE);
        }
        // 在线账号显示绿色，离线保持白色
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
        for (Player online : server.getAllPlayers()) {
            if (online.getUsername().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 格式化登出地点（世界名 + 坐标），无记录或未记录位置时显示"未知" */
    private String formatLocation(PlayerRecord record) {
        if (record == null || record.lastWorld() == null) {
            return Messages.get(Messages.AUTH_INFO_LOCATION, Messages.get(Messages.GENERIC_UNKNOWN));
        }
        String coords = String.format("%.1f, %.1f, %.1f", record.lastX(), record.lastY(), record.lastZ());
        return Messages.get(Messages.AUTH_INFO_LOCATION, record.lastWorld() + " (" + coords + ")");
    }

    /** 查询 IP 地理位置（国家/省份/城市），geo 服务未就绪或无结果时返回"未知" */
    private String formatGeo(String ip) {
        if (ip == null || core.getIpGeoService() == null || !core.getIpGeoService().isReady()) {
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
    public List<String> suggest(String[] args) {
        // 由 CommandManager 统一处理在线玩家补全
        return List.of();
    }
}
