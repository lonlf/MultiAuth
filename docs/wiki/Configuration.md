# 配置

MultiAuth 有两份配置文件，均在**首次启动时自动生成**（注释为中文/英文双语）：

- Spigot 端：`plugins/MultiAuth/config.yml`
- Velocity 端：`plugins/multiauth/config.toml`

完整配置项见 [config.yml](../../spigot/src/main/resources/config.yml) 与 [config.toml](../../velocity/src/main/resources/config.toml)。

## 语言设置

`language` 项（默认 `en_gb`，支持 `zh_cn` / `en_gb`）控制插件消息语言。首次启动时按**系统区域**自动匹配语言文件：

| 系统 Locale | 自动选择 |
|-----------|---|
| 简体中文      | `zh_cn` |
| 英文        | `en_gb` |
| 其它        | `en_gb`（默认） |

已部署服务器不会自动改写配置，需手动编辑 `language` 或删除配置文件后重启才会触发检测。

## Spigot 端 config.yml

| 配置段 | 默认值 | 说明 |
|---|---|---|
| `language` | `en_gb` | 语言代码，首次启动自动检测 |
| `debug` | `false` | 调试模式（详细日志） |
| `proxy` | `false` | 代理模式（`true`=信任 Velocity 验证） |
| `use-mojang-uuid` | `true` | 验证通过后是否使用正版 UUID |
| `session-sync-secret` | `""` | 跨服会话同步密钥（需与 Velocity 端一致，留空=关闭） |
| `auth-list` | `[]` | 强制要求 Mojang 验证的玩家名列表 |
| `mojang-api` | — | 备用 API 地址、每用户名请求频率限制 |
| `database` | — | 数据库类型、连接参数、心跳间隔 |
| `backup` | — | 定时备份、备份目录、最大数量 |
| `auth` | — | 离线注册登录全部配置（见下） |
| `session` | — | 会话超时（分钟，0=禁用免登录恢复） |

### auth 段（离线注册登录）

- **注册/登录**：开关、密码长度范围、超时时间
- **未登录限制**：强制冒险模式、冻结位置、禁移动/聊天/交互/伤害/命令、命令白名单
- **出生点与位置**：`login-spawn-point`（未登录进服传送点）、`return-last-location`（登录后回上次下线点）
- **登录后行为**：`force-survival`（强制生存模式）
- **安全增强**（`auth.security`）：失败计数与冷却、单 IP 账号限制、IP 变更警告、异地登录检测、登录历史

## Velocity 端 config.toml

结构与 Spigot 端基本一致，主要差异：

| 配置段 | 说明 |
|---|---|
| `cross-server-use-mojang-uuid` | 跨服正版 UUID 策略（需与 Spigot `use-mojang-uuid` 一致） |
| `auth-list` | 认证列表（Velocity 侧） |
| `session-sync-secret` | 会话同步密钥（需与 Spigot 端一致） |
| `mojang-api` / `database` / `backup` | 与 Spigot 端同义 |

无 `auth` 段（离线注册登录由 Spigot 子服处理），无 `proxy` 选项（Velocity 始终为代理方）。

**数据库要求**：代理模式下 Velocity 与所有 Spigot 子服必须指向**同一 MySQL**（相同 database、相同 `mysql-table-prefix`），否则离线账号、登录历史、IP 限制等数据按服务端分裂。

## 热重载

执行 `/multiauth reload` 立即生效：

- 全部配置项与语言文件
- 代理模式切换（注册/注销 PacketEvents 拦截器）
- 安全增强服务（重建地理位置查询、清空失败计数）
- 未登录玩家允许命令列表

**不会清空**：在线玩家的登录状态与持久化会话（reload 不会踢出或强制重新登录玩家）。

**不立即生效**：`use-mojang-uuid`（已登录玩家需重新登录）、数据库类型切换（需重启服务端）。

>注意：随意更改 use-mojang-uuid 配置项可能会导致其他UUID相关插件报错，非必要请勿随意更改

## 语言文件

语言文件位于 `plugins/MultiAuth/lang/` 目录：

- `zh_cn.yml` — 简体中文
- `en_gb.yml` — 英语

修改后执行 `/multiauth reload` 即时生效；缺失的键自动回退硬编码默认值。注意：已生成的 `lang/` 目录不会自动更新，需删除目录让插件重新生成，或手动编辑对应文件。

