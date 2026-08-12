package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.MultiAuth;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 更新通知监听器：在线管理员进服时提示有新版本可用。
 */
public class UpdateNotifyListener implements Listener {

    /** 单玩家通知冷却（毫秒），默认 6 小时 */
    private static final long NOTIFY_COOLDOWN_MS = 6 * 60 * 60 * 1000L;

    /** 通知记录上限，防止被频繁进服的玩家刷内存 */
    private static final int MAX_RECORDS = 10000;

    private final Core core;
    private final MultiAuth plugin;

    /** 上次通知时间（UUID → epochMillis），仅记录有 multiauth.admin 权限的玩家 */
    private final Map<UUID, Long> lastNotified = new ConcurrentHashMap<>();

    public UpdateNotifyListener(Core core, MultiAuth plugin) {
        this.core = core;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            if (core == null || core.getUpdateChecker() == null || !core.getUpdateChecker().isNewerAvailable()) {
                return;
            }
            if (!player.hasPermission("multiauth.admin")) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = lastNotified.get(player.getUniqueId());
            if (last != null && now - last < NOTIFY_COOLDOWN_MS) {
                return;
            }
            // 先记录再发送，避免玩家秒退重进触发重复提示
            lastNotified.put(player.getUniqueId(), now);
            com.lonleaf.multiauth.update.UpdateInfo info = core.getUpdateChecker().getLastResult();
            player.sendMessage(Messages.get(Messages.UPDATE_NOTIFY_PLAYER,
                    info.latestVersion(), core.getCurrentVersion(), info.releaseUrl()));
            trimRecords();
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.get(Messages.AUTH_PLAYER_JOIN_ERROR,
                    event.getPlayer().getName(), e.getMessage()));
        }
    }

    /** 通知记录超出上限时清理过期项；仍超则整体清空 */
    private void trimRecords() {
        if (lastNotified.size() < MAX_RECORDS) {
            return;
        }
        long now = System.currentTimeMillis();
        lastNotified.entrySet().removeIf(entry -> now - entry.getValue() > NOTIFY_COOLDOWN_MS);
        if (lastNotified.size() >= MAX_RECORDS) {
            lastNotified.clear();
        }
    }
}
