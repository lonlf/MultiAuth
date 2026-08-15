package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.SpigotConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.UUID;

public class PlayerRestrictionListener implements Listener {

    private final AuthState state;
    private final SpigotConfig config;

    public PlayerRestrictionListener(AuthState state, SpigotConfig config) {
        this.state = state;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!config.getConfig().isAuthRestrictMove()) return;
        Player player = event.getPlayer();
        if (state.isUnrestricted(player)) return;

        Location to = event.getTo();
        if (to == null) return; // 防御：getTo() 理论非 null，边界情况安全跳过

        UUID uuid = player.getUniqueId();
        // 固定位置：传送回原位
        if (config.getConfig().isAuthFreezePosition()) {
            Location frozen = state.getFrozenLocation(uuid);
            if (frozen != null && !isSameLocation(to, frozen)) {
                event.setTo(frozen);
                return;
            }
        }
        // 阻止移动（仅当位置变化时）：用 setTo(getFrom) 代替 setCancelled，避免客户端回弹橡皮筋
        if (event.getFrom().getX() != to.getX()
                || event.getFrom().getY() != to.getY()
                || event.getFrom().getZ() != to.getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!config.getConfig().isAuthRestrictChat()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Messages.AUTH_RESTRICTED);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!config.getConfig().isAuthRestrictInteract()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!config.getConfig().isAuthRestrictInteract()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /**
     * 显式处理 BlockBreakEvent：不依赖 PlayerInteractEvent(LEFT_CLICK_BLOCK) 的事件传播实现，
     * 保证不同服务端/版本上行为一致（防止未登录玩家挖掘出生点周围方块逃生）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.getConfig().isAuthRestrictBreakPlace()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!config.getConfig().isAuthRestrictBreakPlace()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!config.getConfig().isAuthRestrictDamage()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.getConfig().isAuthRestrictDamage()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!config.getConfig().isAuthRestrictInventory()) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!config.getConfig().isAuthRestrictInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!config.getConfig().isAuthRestrictInventory()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!config.getConfig().isAuthRestrictInventory()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (state.isUnrestricted(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (!config.getConfig().isAuthRestrictInventory()) return;
        if (state.isUnrestricted(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!config.getConfig().isAuthRestrictCommand()) return;
        Player player = event.getPlayer();
        if (state.isUnrestricted(player)) return;

        String message = event.getMessage();
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
