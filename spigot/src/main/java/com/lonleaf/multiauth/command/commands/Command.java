package com.lonleaf.multiauth.command.commands;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 子命令接口：每个子命令负责匹配自己的触发关键字（args[0]），
 * 不匹配时返回 false，由 {@link com.lonleaf.multiauth.command.CommandManager} 遍历分发。
 */
public interface Command {

    /**
     * 执行子命令。
     *
     * @param sender 命令发送者
     * @param args   完整参数数组（含子命令名 args[0]）
     * @return true=已处理（匹配且执行）；false=未匹配，交给下一个子命令
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * Tab 补全列表。
     *
     * @param args 完整参数数组
     * @return 补全候选；返回 null 表示使用 Bukkit 默认在线玩家补全
     */
    List<String> completeList(String[] args);

    /** 辅助方法：按前缀过滤补全列表（大小写不敏感） */
    default List<String> filter(List<String> list, String startsWith) {
        if (startsWith == null || startsWith.isEmpty()) {
            return list;
        }
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(startsWith.toLowerCase()))
                .collect(Collectors.toList());
    }
}
