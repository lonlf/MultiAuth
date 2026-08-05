package com.lonleaf.multiauth.db;

import java.util.UUID;

public record PlayerRecord(String username, boolean isPremium, UUID uuid, long updatedAt,
                           String lastWorld, double lastX, double lastY, double lastZ,
                           float lastYaw, float lastPitch) {

    /** 兼容旧代码的构造方法（无位置信息） */
    public PlayerRecord(String username, boolean isPremium, UUID uuid, long updatedAt) {
        this(username, isPremium, uuid, updatedAt, null, 0, 0, 0, 0, 0);
    }
}
