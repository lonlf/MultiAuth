package com.lonleaf.multiauth.bStats;

import com.lonleaf.multiauth.Core;
import com.lonleaf.multiauth.db.DatabaseManager;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * bStats 统计管理：初始化 Metrics 实例并注册自定义图表。
 */
public final class MetricsManager {

    private static final int PLUGIN_ID = 33103;

    private final Metrics metrics;

    public MetricsManager(JavaPlugin plugin, Core core) {
        this.metrics = new Metrics(plugin, PLUGIN_ID);
        registerCharts(core);
    }

    private void registerCharts(Core core) {
        // 正版 / 离线 用户分布（基于数据库记录）
        metrics.addCustomChart(new AdvancedPie("user_types", () -> {
            Map<String, Integer> data = new HashMap<>();
            DatabaseManager db = core == null ? null : core.getDatabase();
            if (db == null) {
                return data;
            }
            int total = db.countRecords();
            int premium = db.countPremiumRecords();
            if (total < 0 || premium < 0) {
                return data;
            }
            if (premium > 0) {
                data.put("Premium", premium);
            }
            int offline = total - premium;
            if (offline > 0) {
                data.put("Offline", offline);
            }
            return data;
        }));
    }
}
