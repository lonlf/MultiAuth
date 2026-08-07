package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.db.PlayerRecord;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 离线玩家认证的共享状态与辅助逻辑。
 */
public class AuthState {

    private final AuthService authService;
    private final SpigotConfig config;
    private final Core core;
    private final MultiAuth plugin;

    /** 未登录玩家的固定位置（freezePosition 配置项），玩家 UUID → 位置 */
    private final ConcurrentMap<UUID, Location> frozenLocations = new ConcurrentHashMap<>();

    /** 未登录/注册玩家的超时踢出任务，玩家 UUID → 任务 */
    private final ConcurrentMap<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();

    /** 玩家被强制冒险模式前的原始游戏模式，玩家 UUID → GameMode */
    private final ConcurrentMap<UUID, GameMode> originalGameModes = new ConcurrentHashMap<>();

    /** 玩家是否为离线玩家缓存（use-mojang-uuid=false 时避免每次事件查库），UUID → isOffline */
    private final ConcurrentMap<UUID, Boolean> premiumCache = new ConcurrentHashMap<>();

    /** 允许未登录玩家使用的命令（从配置加载，不区分大小写） */
    private volatile Set<String> allowedCommands = Set.of("register", "login", "reg", "l");

    public AuthState(AuthService authService, SpigotConfig config, Core core, MultiAuth plugin) {
        this.authService = authService;
        this.config = config;
        this.core = core;
        this.plugin = plugin;
        refreshAllowedCommands();
    }

    // ==================== 命令列表 ====================

    /** 从配置重新加载允许的命令列表（reload 时调用） */
    public void refreshAllowedCommands() {
        java.util.List<String> cmds = config.getConfig().getAuthAllowCommands();
        if (cmds != null && !cmds.isEmpty()) {
            allowedCommands = Set.copyOf(cmds.stream().map(String::toLowerCase).toList());
        }
    }

    /** 检查命令名是否在允许列表中 */
    public boolean isCommandAllowed(String cmdName) {
        return allowedCommands.contains(cmdName);
    }

    // ==================== 正版玩家缓存 ====================

    /**
     * 预填充 premiumCache（由 SpigotAuthListener.onPlayerJoin 调用，先于 AuthJoinListener.onPlayerJoin 执行）。
     * 利用预登录异步阶段已查到的 PlayerRecord 判断是否正版，避免主线程查库。
     */
    public void preFillPremiumCache(UUID uuid, boolean isPremium) {
        premiumCache.put(uuid, !isPremium);
    }

    // ==================== 位置冻结 ====================

    public Location getFrozenLocation(UUID uuid) {
        return frozenLocations.get(uuid);
    }

    public void putFrozenLocation(UUID uuid, Location loc) {
        frozenLocations.put(uuid, loc);
    }

    public void removeFrozenLocation(UUID uuid) {
        frozenLocations.remove(uuid);
    }

    // ==================== 游戏模式缓存 ====================

    public GameMode getOriginalGameMode(UUID uuid) {
        return originalGameModes.get(uuid);
    }

    public void putOriginalGameMode(UUID uuid, GameMode mode) {
        originalGameModes.put(uuid, mode);
    }

    public GameMode removeOriginalGameMode(UUID uuid) {
        return originalGameModes.remove(uuid);
    }

    // ==================== 玩家类型判断 ====================

    /**
     * 判断玩家是否不受限制（已登录或正版玩家）。
     *
     * @return true 表示玩家不受限制（已登录、正版玩家、或 auth 功能未启用）
     */
    public boolean isUnrestricted(Player player) {
        if (!config.getConfig().isAuthEnabled()) {
            return true;
        }
        if (authService.isLoggedIn(player.getUniqueId())) {
            return true;
        }
        if (!isOfflinePlayer(player.getName(), player.getUniqueId())) {
            return true;
        }
        return false;
    }

    /**
     * 判断玩家是否为离线玩家（非正版）。
     */
    public boolean isOfflinePlayer(String username, UUID uuid) {
        UUID offlineUuid = AuthManager.generateOfflineUuid(username);
        if (config.isUseMojangUuid()) {
            return uuid.equals(offlineUuid);
        }
        Boolean cached = premiumCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (core.getAuthManager() == null) {
            // Core 初始化失败（AuthManager 缺失）：无法判断玩家类型，fail-closed 视为离线玩家需认证（禁止放行）
            plugin.getLogger().warning(Messages.get(Messages.AUTH_STATE_AUTHMANAGER_MISSING_LOG, username));
            return true;
        }
        PlayerRecord record = core.getAuthManager().getPlayerRecord(username);
        boolean offline = record == null || !record.isPremium();
        premiumCache.put(uuid, offline);
        return offline;
    }

    // ==================== 超时踢出 ====================

    /** 取消玩家的超时踢出任务（登录/注册成功或退出时调用） */
    public void cancelTimeout(UUID uuid) {
        BukkitTask task = timeoutTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 安排超时踢出任务。
     *
     * @param player  玩家
     * @param timeout 超时秒数（<=0 表示不限制）
     * @param message 超时踢出消息
     */
    public void scheduleTimeout(Player player, int timeout, String message) {
        if (timeout <= 0) return;
        UUID uuid = player.getUniqueId();
        cancelTimeout(uuid);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            timeoutTasks.remove(uuid);
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.kickPlayer(message);
            }
        }, timeout * 20L);
        timeoutTasks.put(uuid, task);
    }

    // ==================== 清理 ====================

    /** 清理所有状态（插件禁用/reload 时调用） */
    public void clearAll() {
        frozenLocations.clear();
        originalGameModes.clear();
        premiumCache.clear();
        for (BukkitTask task : timeoutTasks.values()) {
            task.cancel();
        }
        timeoutTasks.clear();
    }

    /** 玩家退出时清理其个人状态 */
    public void clearPlayerState(UUID uuid) {
        originalGameModes.remove(uuid);
        frozenLocations.remove(uuid);
        premiumCache.remove(uuid);
        cancelTimeout(uuid);
    }
}
