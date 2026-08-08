# MultiAuth

> Minecraft 玩家认证插件 · 支持正版/离线混合验证 · Spigot & Velocity 双平台

MultiAuth 是一个支持正版与离线玩家混合验证的 Minecraft 认证插件。核心能力包括 Mojang 两层验证、宕机降级、离线玩家注册登录、安全增强（失败计数/IP 限制/异地登录检测）、数据库迁移与备份、完整国际化。

## 核心特性

- **两层 Mojang 验证**：用户名检查 + 加密握手 hasJoined 验证
- **宕机降级**：Mojang API 不可达时自动放行有历史记录的离线玩家
- **离线注册登录**：Argon2id 密码哈希、会话恢复、未登录限制（移动/聊天/交互/命令）
- **跨服会话同步**：Velocity 作为会话中心，登录/登出状态经 HMAC-SHA256 签名通道（`multiauth:session`）同步到各子服，玩家跨服转移免重复登录
- **安全增强**：失败计数与冷却、单 IP 账号限制、IP 变更警告、异地登录检测（ip2region 离线查询）
- **双平台支持**：Spigot/Paper 独立模式 + Velocity 代理模式
- **数据库支持**：SQLite（默认）+ MySQL（HikariCP 连接池，可选 SSL）
- **数据库备份**：定时备份 + 旧备份自动清理 + SQLite↔MySQL 双向迁移
- **国际化**：zh_cn / en_gb 双语(未来更多)，140+ 消息键，热重载

## 快速开始

### 安装

##### 独立模式
1. 下载 `multiauth-spigot-<version>.jar` 放入 `plugins/` 目录
2. （独立模式需）安装 [PacketEvents](https://github.com/retrooper/packetevents) 插件
3. 启动服务器，编辑 `plugins/MultiAuth/config.yml`
4. 执行 `/multiauth reload` 或重启服务端
##### 代理模式
1. 下载`multiauth-spigot-<version>.jar`以及 `multiauth-velocity-<version>.jar` 分别放入各自 `plugins/` 目录
2. 启动服务端以及代理端，生成配置后关闭
3. 修改Spigot服务端目录下`plugins/MultiAuth/config.yml`配置文件,设置`proxy: true`;
4. 设置Velocity配置`online-mode = true`在`velocity.toml`中
5. 在Spigot与Velocity插件配置文件中配置相同MySQL数据库（推荐配置）
6. 配置 Velocity 的 `velocity.toml` 的 `player-info-forwarding-mode = "modern"`（或 `"bungeeguard"`），后端 `server.properties` 设 `online-mode=false`（Paper 需开启 `velocity-support`），否则后端拿不到 Velocity 转发的真实 UUID
7. 如需**跨服免登录**（子服间转移不重复登录），在两端配置相同密钥：
   - Spigot `config.yml`：`session-sync-secret: "随机密钥"`
   - Velocity `config.toml`：`session-sync-secret = "随机密钥"`（与 Spigot 完全一致）
   - 密钥留空 = 关闭跨服会话同步，玩家换服后需重新登录
8. （可选）`cross-server-use-mojang-uuid`（Velocity `config.toml`，默认 `true`）决定正版玩家使用正版 UUID 还是离线 UUID，需与 Spigot 端 `use-mojang-uuid` 保持一致

### 命令速查

| 命令                               | 用法                            | 说明            |
|----------------------------------|-------------------------------|---------------|
| `/register` `/reg`               | `/register <密码> <确认密码>`       | 注册账号（离线玩家）    |
| `/login` `/l`                    | `/login <密码>`                 | 登录（已注册离线玩家）   |
| `/changepassword`                | `/changepassword <旧密码> <新密码>` | 修改密码          |
| `/multiauth reload`              | —                             | 重载配置（管理员）     |
| `/multiauth status [player]`     | —                             | 查看状态          |
| `/multiauth backup`              | —                             | 手动备份（管理员）     |
| `/multiauth info <player>`       | —                             | 查看账号信息（管理员）   |
| `/multiauth unregister <player>` | —                             | 注销账号（管理员）     |
| `/vmultiauth *`                  | _                             | Velocity控制台命令 |

### 最小配置

```yaml
# config.yml
proxy: false                     # true=Velocity 代理模式 / false=Spigot 独立模式
session-sync-secret: ""          # proxy=true 时配置随机密钥，两端一致 = 启用跨服会话同步

database:
  type: sqlite                   # sqlite / mysql（跨服推荐 mysql）

auth:
  enabled: true
  login-timeout: 600             # 登录超时（秒）
  register-timeout: 180          # 注册超时（秒）

session:
  timeout: 0                    # 会话超时（分钟），0=禁用
```

> 代理模式跨服完整配置请参考上方「代理模式」安装步骤。

## 部署模式

| 模式 | 配置 | 说明 |
|---|---|---|
| **独立模式**（默认） | `proxy: false` | Spigot 自行执行 Mojang 验证，需安装 PacketEvents |
| **代理模式** | `proxy: true` | Velocity 端 `online-mode=true`，Spigot 仅校验转发的 UUID；配置 `session-sync-secret` 后可跨服免登录 |

## 环境要求

- JDK 21+
- Spigot/Paper 1.18.2+ 或 Velocity 3.4.0+
- [packetevents](https://modrinth.com/plugin/packetevents) (Spigot)

## 致谢

- [PacketEvents](https://github.com/retrooper/packetevents) — 数据包拦截
- [ip2region](https://github.com/lionsoul2014/ip2region) — 离线 IP 地理位置查询
- [FastLogin](https://github.com/games647/FastLogin) — 方案参考
- [MC_Protocol_Data](https://github.com/Nickid2018/MC_Protocol_Data) - 协议包参考
