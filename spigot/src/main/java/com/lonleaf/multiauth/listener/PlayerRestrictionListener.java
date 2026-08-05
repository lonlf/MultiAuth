package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.SpigotConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;

/**
 * 未登录玩家行动限制监听器。
 */
public class PlayerRestrictionListener implements Listener {

    private final AuthState state;
    private final SpigotConfig config;

    public PlayerRestrictionListener(AuthState state, SpigotConfig config) {
        this.state = state;
        this.config = config;
    }

    /**
     * 禁止未登录玩家移动。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!config.getConfig().isAuthRestrictMove()) return;
        Player player = event.getPlayer();
        if (state.isUnrestricted(player)) return;

        UUID uuid = player.getUniqueId();
        // 固定位置：传送回原位
        if (config.getConfig().isAuthFreezePosition()) {
            Location frozen = state.getFrozenLocation(uuid);
            if (frozen != null && !isSameLocation(event.getTo(), frozen)) {
                event.setTo(frozen);
                return;
            }
        }
        // 阻止移动（仅当位置变化时）：用 setTo(getFrom) 代替 setCancelled，避免客户端回弹橡皮筋
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    /**
     * 禁止未登录玩家聊天。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!config.getConfig().isAuthRestrictChat()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Messages.AUTH_RESTRICTED);
    }

    /**
     * 禁止未登录玩家交互方块/物品。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!config.getConfig().isAuthRestrictInteract()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /**
     * 禁止未登录玩家交互实体。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!config.getConfig().isAuthRestrictInteract()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /**
     * 禁止未登录玩家受到伤害。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!config.getConfig().isAuthRestrictDamage()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    /**
     * 禁止未登录玩家造成伤害。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.getConfig().isAuthRestrictDamage()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    /**
     * 禁止未登录玩家使用除允许列表外的命令。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!config.getConfig().isAuthRestrictCommand()) return;
        Player player = event.getPlayer();
        if (state.isUnrestricted(player)) return;

        String message = event.getMessage();
        // 解析命令名（去掉 / 前缀和空格后的参数）
        String cmdName = message.startsWith("/") ? message.substring(1) : message;
        int spaceIdx = cmdName.indexOf(' ');
        if (spaceIdx > 0) {
            cmdName = cmdName.substring(0, spaceIdx);
        }
        // 去掉可能的插件前缀（如 "multiauth:register"）
        int colonIdx = cmdName.indexOf(':');
        if (colonIdx >= 0) {
            cmdName = cmdName.substring(colonIdx + 1);
        }
        cmdName = cmdName.toLowerCase();

        if (state.isCommandAllowed(cmdName)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(Messages.AUTH_RESTRICTED);
    }

    /** 比较两个位置是否相同（仅 x/y/z 坐标） */
    private static boolean isSameLocation(Location a, Location b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }
}
