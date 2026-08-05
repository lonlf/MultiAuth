package com.lonleaf.multiauth.db;

/**
 * 离线玩家注册账号记录。
 *
 * @param username       玩家名（主键）
 * @param passwordHash   Argon2id 哈希后的密码
 * @param registerTime   注册时间戳（毫秒）
 * @param lastLoginTime  最后登录时间戳（毫秒），0 表示从未登录
 * @param lastIp         最后登录 IP
 */
public record AuthAccount(String username, String passwordHash, long registerTime,
                          long lastLoginTime, String lastIp) {
}
