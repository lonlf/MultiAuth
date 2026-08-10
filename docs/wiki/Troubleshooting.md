# 常见问题

本页收录 MultiAuth 部署与使用中常见的问题及排查方法。

## 独立模式玩家无法进服

确认已安装 [PacketEvents](https://modrinth.com/plugin/packetevents) 且 `server.properties` 中 `online-mode=false`。未安装 PacketEvents 时插件回退到 API-only 验证模式，无法执行完整加密握手。

## 代理模式切换不生效

`proxy` 值切换后需**重启服务端**（reload 无法热加载 PacketEvents 依赖与外部转发配置），否则新旧模式混用会导致连接异常。

## 跨服数据分裂

确认 Velocity 与各子服指向**同一 MySQL** 且 `mysql-table-prefix` 相同。否则离线账号、登录历史、IP 限制等数据将按服务端分裂，跨服会话同步无法工作。

## 会话同步失败

确认两端 `session-sync-secret` 完全一致（含大小写）。密钥不一致时同步消息会被拒绝（fail-closed），跨服转移后需重新登录。

## 离线玩家无法登录

提示 `登录失败：无效会话（请尝试重启游戏及启动器）` 时，可能原因：

- 账户名与某正版玩家重名（Mojang 验证被同名正版账号占用）
- 启动器正版登录状态失效，导致 hasJoined 验证失败

## 修改语言文件不生效

已生成的 `plugins/MultiAuth/lang/` 目录不会自动更新。需删除该目录让插件重新生成，或手动编辑对应语言文件后执行 `/multiauth reload`。
