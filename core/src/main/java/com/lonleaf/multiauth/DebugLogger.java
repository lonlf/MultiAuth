package com.lonleaf.multiauth;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 包装 JUL Logger，使 FINE/FINER/FINEST 级别日志在 debug 模式下以 INFO 输出（带 [DEBUG] 前缀）。
 */
public class DebugLogger extends Logger {

    private final Logger delegate;
    private volatile boolean debugMode = false;

    public DebugLogger(Logger delegate) {
        // 使用与被包装 logger 相同的名称，保证日志前缀显示 [MultiAuth] 而非 [MultiAuth-debug]。
        // protected 构造器直接创建实例（不注册到 LogManager 全局表），与现有 logger 无冲突；
        // 所有输出均显式转发给 delegate，父子链被 useParentHandlers(false) 隔离。
        super(delegate.getName(), null);
        this.delegate = delegate;
        setUseParentHandlers(false);
        setLevel(Level.INFO); // 默认生产模式
    }

    @Override
    public void setLevel(Level newLevel) {
        super.setLevel(newLevel);
        // 同步 debugMode：Core.applyLogLevel() 通过 setLevel 控制级别时自动更新
        this.debugMode = (newLevel != null && newLevel.intValue() <= Level.FINE.intValue());
    }

    @Override
    public void log(LogRecord record) {
        Level level = record.getLevel();
        if (level.intValue() >= Level.INFO.intValue()) {
            // INFO/WARNING/SEVERE：原样转发给真实 logger
            delegate.log(record);
        } else if (debugMode) {
            // FINE/FINER/FINEST：debug 模式转 info 输出，绕过 log4j 默认级别过滤
            LogRecord forwarded = new LogRecord(Level.INFO, "[DEBUG] " + record.getMessage());
            forwarded.setParameters(record.getParameters());
            forwarded.setThrown(record.getThrown());
            forwarded.setLoggerName(delegate.getName());
            delegate.log(forwarded);
        }
        // debug 模式关闭：fine 日志丢弃
    }
}
