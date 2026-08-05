package com.lonleaf.multiauth.db;

/**
 * 登录历史记录。
 *
 * @param username  玩家名
 * @param ip        登录 IP
 * @param loginTime 登录时间戳（毫秒）
 * @param success   是否登录成功
 * @param country   国家名称
 * @param city      城市
 */
public record LoginHistoryRecord(String username, String ip, long loginTime,
                                  boolean success, String country, String city) {
}
