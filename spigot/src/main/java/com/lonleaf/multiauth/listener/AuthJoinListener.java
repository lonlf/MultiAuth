package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import com.lonleaf.multiauth.MultiverseHook;
import com.lonleaf.multiauth.SpigotConfig;
import com.lonleaf.multiauth.auth.AuthService;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.db.PlayerRecord;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuthJoinListener implements Listener {

    private final AuthState state;
    private final AuthService authService;
    private final SpigotConfig config;
    private final Core core;
    private final MultiAuth plugin;
    private final MultiverseHook multiverseHook;

    /** 跨服切换时等待 Velocity LOGIN_SYNC 到达的宽限 tick 数（2 秒） */
    private static final int LOGIN_SYNC_GRACE_TICKS = 40;

    public AuthJoinListener(AuthState state, AuthService authService, SpigotConfig config,
                            Core core, MultiAuth plugin) {
        this.state = state;
        this.authService = authService;
        this.config = config;
        this.core = core;
        this.plugin = plugin;
        this.multiverseHook = new MultiverseHook(plugin, plugin.getLogger());
    }

    // ==================== 玩家加入 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();
        UUID uuid = player.getUniqueId();
        String ip = getPlayerIp(player);

        AuthConfig authConfig = config.getConfig();

        // 安全检查：单 IP 在线账号数限制（正版和离线玩家均受限）。
        // canJoinAndRegister 原子完成检查+登记，避免 TOCTOU 竞态超限
        if (!authService.canJoinAndRegister(ip, username)) {
            player.kickPlayer(Messages.AUTH_IP_ONLINE_LIMIT);
            plugin.getLogger().warning(Messages.get(Messages.SEC_JOIN_ONLINE_LIMIT_KICK_LOG,
                    username, ip, String.valueOf(authConfig.getSecMaxOnlinePerIp())));
            return;
        }

        // 正版玩家标记为已登录，离线玩家为未登录
        if (!state.isOfflinePlayer(username, uuid)) {
            authService.markLoggedIn(uuid);
            if (authConfig.isAuthReturnLastLocation()) {
                onLoginSuccess(player);
            } else {
                // return-last-location=false 时不走 onLoginSuccess，仍需补发多账号提示
                if (authConfig.isAuthLoginSpawnPoint()) {
                    player.teleport(resolveSpawnPoint(player));
                }
                notifyOtherAccounts(player);
            }
            if (authConfig.isAuthForceSurvival()) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            return;
        }

        // 离线玩家但已通过 Velocity 跨服会话同步登录 → 跳过限制
        if (authService.isLoggedIn(uuid)) {
            if (authConfig.isAuthReturnLastLocation()) {
                onLoginSuccess(player);
            } else {
                if (authConfig.isAuthLoginSpawnPoint()) {
                    player.teleport(resolveSpawnPoint(player));
                }
                notifyOtherAccounts(player);
            }
            if (authConfig.isAuthForceSurvival()) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            return;
        }

        // 离线玩家 → 未登录 → 应用限制
        if (authConfig.isAuthLoginSpawnPoint()) {
            player.teleport(resolveSpawnPoint(player));
        }
        if (authConfig.isAuthForceAdventure()) {
            state.putOriginalGameMode(uuid, player.getGameMode());
            player.setGameMode(GameMode.ADVENTURE);
        }
        if (authConfig.isAuthFreezePosition()) {
            state.putFrozenLocation(uuid, player.getLocation().clone());
        }

        // 提示注册或登录，并启动超时踢出任务
        // isRegistered 查库 + geo 安全检查均在异步线程执行，避免主线程阻塞
        scheduleTimeout(player, authConfig.getAuthLoginTimeout(), Messages.AUTH_LOGIN_TIMEOUT);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final boolean registered = authService.isRegistered(username);
            final String sessionIp = authService.getPersistentSessionIp(username);
            final boolean ipChanged = sessionIp != null && !sessionIp.equals(ip);
            final AuthService.SessionResumeCheck secCheck = (registered && ipChanged)
                    ? authService.checkSessionResumeSecurity(username, ip) : null;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                state.cancelTimeout(uuid);
                if (authService.isLoggedIn(uuid)) {
                    return;
                }
                if (!registered) {
                    // 未注册玩家不会被 LOGIN_SYNC 标记，直接提示注册并定时提醒
                    player.sendMessage(Messages.AUTH_REGISTER_PROMPT);
                    state.startReminder(player, Messages.AUTH_REGISTER_PROMPT);
                    scheduleTimeout(player, authConfig.getAuthRegisterTimeout(), Messages.AUTH_REGISTER_TIMEOUT);
                    return;
                }
                // 已注册但未登录：跨服会话同步场景下，Velocity 的 LOGIN_SYNC 可能晚于本检查到达
                //（后端加入完成时机差异），短暂宽限等待，避免切换服务器后误报需要登录
                if (isSessionSyncActive()) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (authService.isLoggedIn(uuid)) {
                            return; // 宽限期内 LOGIN_SYNC 已到达
                        }
                        handleRegisteredPlayer(player, username, uuid, ip, authConfig, secCheck);
                    }, LOGIN_SYNC_GRACE_TICKS);
                    return;
                }
                handleRegisteredPlayer(player, username, uuid, ip, authConfig, secCheck);
            });
        });
    }

    /**
     * 处理已注册离线玩家的加入流程：尝试恢复持久会话，必要时提示登录。
     * 跨服切换时由宽限期延迟调用，等待 Velocity LOGIN_SYNC 到达。
     */
    private void handleRegisteredPlayer(Player player, String username, UUID uuid, String ip,
                                        AuthConfig authConfig, AuthService.SessionResumeCheck secCheck) {
        if (!authService.tryResumeSession(username, ip, uuid)) {
            player.sendMessage(Messages.AUTH_LOGIN_PROMPT);
            state.startReminder(player, Messages.AUTH_LOGIN_PROMPT);
            scheduleTimeout(player, authConfig.getAuthLoginTimeout(), Messages.AUTH_LOGIN_TIMEOUT);
            return;
        }
        if (secCheck != null) {
            if (secCheck.shouldKick()) {
                player.kickPlayer(secCheck.warnings().isEmpty()
                        ? Messages.AUTH_GEO_REQUIRE_LOGIN : secCheck.warnings().get(0));
                return;
            }
            if (!secCheck.allowResume()) {
                // 异地登录安全检查要求重新登录：先撤销 tryResumeSession 已写入的登录标记，
                // 否则玩家处于"已登录"状态却被告知需要登录，且绕过登录限制（P1-10）
                authService.logout(uuid);
                for (String w : secCheck.warnings()) {
                    player.sendMessage(w);
                }
                player.sendMessage(Messages.AUTH_GEO_REQUIRE_LOGIN);
                player.sendMessage(Messages.AUTH_LOGIN_PROMPT);
                state.startReminder(player, Messages.AUTH_LOGIN_PROMPT);
                scheduleTimeout(player, authConfig.getAuthLoginTimeout(), Messages.AUTH_LOGIN_TIMEOUT);
                return;
            }
            for (String w : secCheck.warnings()) {
                player.sendMessage(w);
            }
        }
        authService.confirmSessionResume(username, ip);
        player.sendMessage(Messages.AUTH_LOGIN_SUCCESS);
        onLoginSuccess(player);
    }

    /** 跨服会话同步是否启用：代理模式且已配置签名密钥 */
    private boolean isSessionSyncActive() {
        if (!config.isProxy()) return false;
        String secret = config.getConfig().getSessionSyncSecret();
        return secret != null && !secret.isBlank();
    }

    // ==================== 玩家退出 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = getPlayerIp(player);
        String username = player.getName();
        // 无论 return-last-location 开关如何，都记录登出位置（供 /multiauth info 管理员查询）
        savePlayerLocation(player);
        authService.onPlayerQuit(uuid, ip, username);
        state.clearPlayerState(uuid);
    }

    // ==================== 登录成功 ====================

    /**
     * 登录成功后的统一处理（正版玩家进服时、离线玩家 /login 或 /register 成功时调用）。
     */
    public void onLoginSuccess(Player player) {
        UUID uuid = player.getUniqueId();
        state.removeFrozenLocation(uuid);
        AuthConfig authConfig = config.getConfig();
        if (authConfig.isAuthForceSurvival()) {
            player.setGameMode(GameMode.SURVIVAL);
        } else {
            GameMode original = state.removeOriginalGameMode(uuid);
            if (original != null) {
                player.setGameMode(original);
            }
        }
        if (authConfig.isAuthReturnLastLocation()) {
            returnToLastLocation(player);
        }
        // 向 Velocity 会话中心上报认证成功（离线玩家注册/登录成功后），
        // 使跨服切换时目标服务器能收到 LOGIN_SYNC 保持登录状态
        plugin.notifySessionAuthUp(player);
        // 登录成功后提示该 IP 关联的其他账号（多账号检测）
        notifyOtherAccounts(player);
    }

    /**
     * 登录成功后按最近一次登录 IP 提示玩家关联的其他账号（多账号检测）。
     * 参考 AuthMe 归属逻辑：仅用最后登录 IP 归因，玩家更换 IP 后按新 IP 反查新关联集合。
     * 需配置 auth.notify-other-accounts 开启；查询失败静默跳过（非安全功能，不拒绝登录）。
     */
    private void notifyOtherAccounts(Player player) {
        AuthConfig authConfig = config.getConfig();
        if (!authConfig.isAuthNotifyOtherAccounts()
                || core == null || core.getAuthManager() == null) {
            return;
        }
        final String ip = getPlayerIp(player);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> accounts = core.getAuthManager().getAccountsByLastIp(ip);
                if (accounts.size() <= 1) {
                    return; // 该 IP 下仅当前账号，无需提示
                }
                List<String> others = new ArrayList<>();
                for (String name : accounts) {
                    if (!name.equalsIgnoreCase(player.getName())) {
                        others.add(name);
                    }
                }
                if (others.isEmpty()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Messages.get(Messages.AUTH_OTHER_ACCOUNTS_NOTIFY,
                                String.join(", ", others)));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.get(Messages.DB_GET_ACCOUNTS_BY_IP_FAILED,
                        ip, e.getMessage()));
            }
        });
    }

    // ==================== 位置相关 ====================

    /** 保存玩家当前位置到数据库（异步写入） */
    private void savePlayerLocation(Player player) {
        final String name = player.getName();
        final Location loc = player.getLocation();
        final String worldName = loc.getWorld().getName();
        final double x = loc.getX();
        final double y = loc.getY();
        final double z = loc.getZ();
        final float yaw = loc.getYaw();
        final float pitch = loc.getPitch();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                core.getDatabase().updatePlayerLocation(name, worldName, x, y, z, yaw, pitch);
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.get(Messages.AUTH_SAVE_LOCATION_FAILED_LOG, name, e.getMessage()));
            }
        });
    }

    /** 将玩家传送到上次下线地点，含可达性检查（DB 查询异步，传送回主线程） */
    private void returnToLastLocation(Player player) {
        final String name = player.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerRecord record = core.getAuthManager().getPlayerRecord(name);
                if (record == null || record.lastWorld() == null) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    World world = plugin.getServer().getWorld(record.lastWorld());
                    if (world == null) return;
                    Location target = new Location(world, record.lastX(), record.lastY(), record.lastZ(),
                            record.lastYaw(), record.lastPitch());
                    if (isLocationSafe(target)) {
                        player.teleport(target);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.get(Messages.AUTH_LOAD_LOCATION_FAILED_LOG, name, e.getMessage()));
            }
        });
    }

    /** 检查目标位置是否安全（玩家头部和脚部没有固体方块阻挡） */
    private static boolean isLocationSafe(Location loc) {
        try {
            Block feet = loc.getBlock();
            Block head = loc.clone().add(0, 1, 0).getBlock();
            Block ground = loc.clone().add(0, -1, 0).getBlock();
            return !feet.getType().isSolid() && !head.getType().isSolid() && ground.getType().isSolid();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析登录出生点位置。
     * 优先用配置的 spawn-point.world，若未加载则尝试通过 Multiverse-Core 加载；
     * 世界不存在时回退到玩家当前世界出生点。
     */
    private Location resolveSpawnPoint(Player player) {
        AuthConfig authConfig = config.getConfig();
        String worldName = authConfig.getAuthSpawnPointWorld();
        if (worldName != null && !worldName.isBlank()) {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                world = multiverseHook.loadWorld(worldName);
            }
            if (world != null) {
                return new Location(world,
                        authConfig.getAuthSpawnPointX(),
                        authConfig.getAuthSpawnPointY(),
                        authConfig.getAuthSpawnPointZ(),
                        authConfig.getAuthSpawnPointYaw(),
                        authConfig.getAuthSpawnPointPitch());
            }
            plugin.getLogger().warning(Messages.get(Messages.SPAWN_WORLD_MISSING_LOG, worldName));
        }
        return player.getWorld().getSpawnLocation();
    }

    // ==================== 辅助 ====================

    private void scheduleTimeout(Player player, int timeout, String message) {
        state.scheduleTimeout(player, timeout, message);
    }

    private static String getPlayerIp(Player player) {
        try {
            java.net.InetAddress addr = player.getAddress().getAddress();
            return addr != null ? addr.getHostAddress() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
