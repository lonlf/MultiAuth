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

## 进服被踢：XConomy UUID mismatch

正版玩家进服后被 XConomy 踢出，提示：

```
[XConomy] UUID mismatch
Username - ZZZZZZ
UUID[C] - XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
UUID[O] - YYYYYYYY-YYYY-YYYY-YYYY-YYYYYYYYYYYY
```

`UUID[C]` 为玩家当前连接携带的 UUID，`UUID[O]` 为 XConomy 按玩家名实时计算的标准离线 UUID（`MD5("OfflinePlayer:"+名字)`，version 3）。

**原因**：XConomy 配置 `UUID-mode: Offline` 时期望玩家 UUID 等于离线 UUID；MultiAuth 默认 `use-mojang-uuid: true` 保持正版 UUID（version 4）转发，两者不一致即被 XConomy 实时检测踢出。**该检测与数据库数据无关，清除 XConomy 数据库无效**。

**解决**（二选一）：

- 将 MultiAuth 配置 [use-mojang-uuid](Configuration.md) 改为 `false`，正版玩家统一使用离线 UUID 进服（与 FastLogin `premiumUuid: false` 行为一致）
- 将 XConomy 配置 `UUID-mode` 改为 `Online` 或 `SemiOnline`（正版 + 离线混合支持）

参考：[XConomy issue #86](https://github.com/YiC200333/XConomy/issues/86)
