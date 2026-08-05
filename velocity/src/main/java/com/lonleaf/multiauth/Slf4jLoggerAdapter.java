package com.lonleaf.multiauth;

import org.slf4j.Logger;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * 将 SLF4J Logger 适配为 java.util.logging.Logger，
 * 使 Core 模块（使用 JUL）的日志能路由到 Velocity 的 SLF4J。
 */
public class Slf4jLoggerAdapter extends java.util.logging.Logger {

    private final Logger slf4j;
    private volatile boolean debugMode = false;

    public Slf4jLoggerAdapter(Logger slf4j) {
        super("MultiAuth", null);
        this.slf4j = slf4j;
        setUseParentHandlers(false);
        setLevel(Level.INFO); // 默认生产模式
    }

    @Override
    public void setLevel(Level newLevel) {
        super.setLevel(newLevel);
        // 同步 debugMode：Core.applyLogLevel() 通过 setLevel 控制级别时自动更新 debugMode
        this.debugMode = (newLevel != null && newLevel.intValue() <= Level.FINE.intValue());
    }

    @Override
    public void log(LogRecord record) {
        Level level = record.getLevel();
        String msg = record.getMessage();
        Throwable thrown = record.getThrown();
        if (level == Level.SEVERE) {
            slf4j.error(msg, thrown);
        } else if (level == Level.WARNING) {
            slf4j.warn(msg, thrown);
        } else if (level == Level.INFO) {
            slf4j.info(msg, thrown);
        } else {
            // FINE/FINER/FINEST：debug 模式下用 info 输出（带前缀），避免被 SLF4J 默认级别过滤
            if (debugMode) {
                slf4j.info("[DEBUG] " + msg, thrown);
            }
        }
    }
}
