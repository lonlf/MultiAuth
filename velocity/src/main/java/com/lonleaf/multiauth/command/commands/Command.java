package com.lonleaf.multiauth.command.commands;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Velocity 端子命令接口，与 Spigot 端的 Command 接口结构一致。
 */
public interface Command {

    boolean execute(CommandSource source, String[] args);

    List<String> suggest(String[] args);

    default List<String> filter(List<String> list, String startsWith) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(startsWith.toLowerCase()))
                .collect(Collectors.toList());
    }

    /** 将含 § 颜色码的旧式文本解析为 Adventure Component */
    static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
