package com.lonleaf.multiauth.auth;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.config.AuthConfig;
import com.lonleaf.multiauth.db.DatabaseManager;
import com.lonleaf.multiauth.db.LoginHistoryRecord;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoginHistoryManager {

    // volatile：reload 切换数据库时更新引用
    private volatile DatabaseManager database;
    // volatile：reload 时由 AuthService.updateConfig 更新引用
    private volatile AuthConfig config;
    private final Logger logger;

    public LoginHistoryManager(DatabaseManager database, AuthConfig config, Logger logger) {
        this.database = database;
        this.config = config;
        this.logger = logger;
    }

    public void updateConfig(AuthConfig newConfig) {
        this.config = newConfig;
    }

    /** 切换数据库引用（reload 时由宿主注入新 DatabaseManager，避免持有死库） */
    public void setDatabase(DatabaseManager database) {
        this.database = database;
    }

    /**
     * 记录一次登录尝试，并在配置上限启用时裁剪历史。
     *
     * @param username 玩家名
     * @param ip       登录 IP
     * @param success  是否登录成功
     * @param country 国家名称
     * @param city     城市
     */
    public void recordLogin(String username, String ip, boolean success, String country, String city) {
        try {
            database.recordLoginHistory(username, ip, System.currentTimeMillis(), success, country, city);
            if (config.getSecLoginHistoryMaxRecords() > 0) {
                database.trimLoginHistory(username, config.getSecLoginHistoryMaxRecords());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, Messages.get(Messages.HISTORY_RECORD_FAILED,
                    username, e.getMessage()), e);
        }
    }

    /**
     * 获取最近一次登录记录，不存在返回 null。
     * 数据库查询异常时向上抛出（不吞异常），由调用方（如会话恢复安全检查）
     * 按 fail-closed 原则处理，避免查询失败被误判为"无历史"而放行。
     */
    public LoginHistoryRecord getLastSuccessfulLogin(String username) throws SQLException {
        List<LoginHistoryRecord> list = database.getLoginHistoryChecked(username, 1);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    public List<LoginHistoryRecord> getHistory(String username, int limit) {
        try {
            List<LoginHistoryRecord> list = database.getRecentLoginHistory(username, limit);
            return list != null ? list : Collections.emptyList();
        } catch (Exception e) {
            logger.log(Level.WARNING, Messages.get(Messages.HISTORY_GET_FAILED,
                    username, e.getMessage()), e);
            return Collections.emptyList();
        }
    }
}
