package com.lonleaf.multiauth;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multiverse-Core 5.x 集成钩子。
 */
public class MultiverseHook {

    private final Plugin plugin;
    private final Logger logger;
    private final Plugin mvPlugin;

    public MultiverseHook(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.mvPlugin = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core");
    }

    /** Multiverse-Core 是否存在 */
    public boolean isPresent() {
        return mvPlugin != null;
    }

    /**
     * 尝试获取或加载 Multiverse 管理的世界。
     *
     * @param worldName 世界名
     * @return 加载成功返回 Bukkit World，否则 null
     */
    public World loadWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        // 优先用 Bukkit API 获取已加载的世界（含 Multiverse 启动时加载的世界）
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) {
            return world;
        }
        if (!isPresent()) {
            return null;
        }
        // 通过 MV5 API 加载：mvPlugin.getApi().getWorldManager().loadWorld(worldName)
        try {
            Method getApi = mvPlugin.getClass().getMethod("getApi");
            Object api = getApi.invoke(mvPlugin);
            Method getWorldManager = api.getClass().getMethod("getWorldManager");
            Object worldManager = getWorldManager.invoke(api);
            Method loadWorld = worldManager.getClass().getMethod("loadWorld", String.class);
            Object attempt = loadWorld.invoke(worldManager, worldName);
            if (attempt == null) {
                return null;
            }
            // Attempt.isSuccess()
            Method isSuccess = attempt.getClass().getMethod("isSuccess");
            boolean success = (boolean) isSuccess.invoke(attempt);
            if (!success) {
                logger.fine("[MultiAuth] Multiverse loadWorld failed for '" + worldName + "'");
                return null;
            }
            // Attempt.getOrNull() → LoadedMultiverseWorld
            Method getOrNull = attempt.getClass().getMethod("getOrNull");
            Object loadedWorld = getOrNull.invoke(attempt);
            if (loadedWorld == null) {
                return null;
            }
            // LoadedMultiverseWorld.getBukkitWorld() → Option<World>
            Method getBukkitWorld = loadedWorld.getClass().getMethod("getBukkitWorld");
            Object option = getBukkitWorld.invoke(loadedWorld);
            if (option == null) {
                return null;
            }
            // Option.isEmpty()
            Method isEmpty = option.getClass().getMethod("isEmpty");
            boolean empty = (boolean) isEmpty.invoke(option);
            if (empty) {
                return null;
            }
            // Option.get() → World
            Method get = option.getClass().getMethod("get");
            return (World) get.invoke(option);
        } catch (Exception e) {
            logger.log(Level.FINE, "[MultiAuth] Multiverse loadWorld reflection failed for '"
                    + worldName + "': " + e.getMessage(), e);
            return null;
        }
    }
}
