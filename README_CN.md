[English](README.md) | 简体中文

# MultiAuth

MultiAuth 是一个支持正版与离线玩家混合验证的 Minecraft 认证插件;支持Spigot以及Velocity。
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

**独立模式（Spigot）**

1. 下载 multiauth-spigot-<version>.jar 放入 plugins/ 目录 
2. 同时安装 [PacketEvents](https://modrinth.com/plugin/packetevents)
3. 启动服务器生成配置文件,编辑`plugins/MultiAuth/config.yml`并重启服务端


**代理模式（Spigot 子服 + Velocity）**

1. 下载 multiauth-spigot-<version>.jar（需安装 PacketEvents）与 multiauth-velocity-<version>.jar 分别放入各 Spigot 子服与 Velocity 的 plugins/ 目录
2. 启动服务端与代理端生成配置，然后关闭服务端
3. 按下方「配置」章节设置两端配置（proxy、velocity.toml、共享数据库、会话同步密钥等）
4. 重新启动服务端与代理端


### 配置

**Velocity（velocity.toml）**

```toml
online-mode = true
player-info-forwarding-mode = "modern"
forwarding.secret = "与各子服一致的转发密钥"
```

**Spigot 后端（server.properties）**

```properties
online-mode=false
# Paper 服务端需额外在 paper-global.yml 开启 Velocity 转发支持
```

```yaml
# paper-global.yml（仅 Paper 服务端）
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "与 velocity.toml 的 forwarding.secret 一致"
```

**跨服会话同步密钥（可选，启用后子服间转移免重复登录）**

```yaml
# Spigot plugins/MultiAuth/config.yml
proxy: true
session-sync-secret: "与 Velocity 端一致的长随机密钥"
```

```toml
# Velocity plugins/MultiAuth/config.toml
session-sync-secret = "与 Spigot 端一致的密钥"
```

> 密钥两端必须完全一致；留空 = 关闭跨服会话同步，玩家换服后需重新登录。

**共享数据库（跨服模式必须使用同一 MySQL）**

```yaml
# Spigot plugins/MultiAuth/config.yml
database:
  type: mysql
  mysql-host: localhost
  mysql-port: 3306
  mysql-database: multiauth
  mysql-username: root
  mysql-password: ""
  mysql-table-prefix: multiauth_
```

```toml
# Velocity plugins/MultiAuth/config.toml
[database]
type = "mysql"
mysql-host = "localhost"
mysql-port = 3306
mysql-database = "multiauth"
mysql-username = "root"
mysql-password = ""
mysql-table-prefix = "multiauth_"
```

> 两端必须指向**同一 database 且 table-prefix 相同**，否则离线账号/登录历史/IP 限制等数据将按服务端分裂。

**正版 UUID 策略（可选，两端需保持一致）**

```yaml
# Spigot config.yml
use-mojang-uuid: true
```

```toml
# Velocity config.toml
cross-server-use-mojang-uuid = true
```

### 命令速查

| 命令                               | 用法                            | 说明            |
|----------------------------------|-------------------------------|---------------|
| `/register` `/reg` `/r`          | `/register <密码> <确认密码>`       | 注册账号（离线玩家）    |
| `/login` `/l`                    | `/login <密码>`                 | 登录（已注册离线玩家）   |
| `/changepassword`                | `/changepassword <旧密码> <新密码>` | 修改密码          |
| `/multiauth reload`              | —                             | 重载配置（管理员）     |
| `/multiauth status`              | —                             | 查看插件状态（版本/数据库/模式/API/玩家统计，管理员） |
| `/multiauth backup`              | —                             | 手动备份（管理员）     |
| `/multiauth info [player]`       | —                             | 查看账号信息（玩家查自己，管理员可查任意玩家；含类型/UUID/首次进入/最后IP/地理位置/在线状态，管理员额外显示登出地点与关联账号） |
| `/multiauth unregister <player>` | —                             | 注销账号（管理员）     |
| `/vmultiauth *`                  | _                             | Velocity控制台命令 |

### 权限

| 权限节点 | 默认 | 说明 |
|---|---|---|
| `multiauth.admin` | OP | 管理命令（reload/status/backup/migrate/unregister）及 `info` 查询任意玩家 |
| `multiauth.info` | 所有玩家 | `info` 查询自己的账号信息 |

### 最小配置

**独立模式（单服直连，proxy: false）**

```yaml
# plugins/MultiAuth/config.yml
proxy: false                     # Spigot 独立模式，自行完成 Mojang 验证（需安装 PacketEvents）

database:
  type: sqlite                   # 单服可用默认 SQLite

auth:
  enabled: true
  login-timeout: 600             # 登录超时（秒）
  register-timeout: 180          # 注册超时（秒）

session:
  timeout: 0                     # 会话超时（分钟），0=禁用
```

**代理模式（Velocity + Spigot 子服，proxy: true）**

```yaml
# 各 Spigot 子服 plugins/MultiAuth/config.yml
proxy: true                      # 信任 Velocity 的验证结果
session-sync-secret: "kF8#vM2!qR7@sL9"   # 与 Velocity 端一致，启用跨服会话同步

database:
  type: mysql                    # 跨服必须共用同一 MySQL
  mysql-host: localhost
  mysql-port: 3306
  mysql-database: multiauth
  mysql-username: root
  mysql-password: ""
  mysql-table-prefix: multiauth_

auth:
  enabled: true
  login-timeout: 600             # 登录超时（秒）
  register-timeout: 180          # 注册超时（秒）
```

```toml
# Velocity plugins/MultiAuth/config.toml
session-sync-secret = "kF8#vM2!qR7@sL9"   # 与各 Spigot 子服完全一致

[database]
type = "mysql"                   # 与 Spigot 子服指向同一数据库
mysql-host = "localhost"
mysql-port = 3306
mysql-database = "multiauth"
mysql-username = "root"
mysql-password = ""
mysql-table-prefix = "multiauth_"
```

> 代理模式下还需在 `velocity.toml` 设置 `online-mode = true`、`player-info-forwarding-mode = "modern"`，并在各子服 `server.properties` 设 `online-mode=false`（见上方「配置」章节）。

## 环境要求

- JDK 21+
- Spigot/Paper 1.18.2+ 或 Velocity 3.4.0+
- [packetevents](https://modrinth.com/plugin/packetevents) (Spigot)

## 致谢

- [PacketEvents](https://github.com/retrooper/packetevents) — 数据包拦截
- [ip2region](https://github.com/lionsoul2014/ip2region) — 离线 IP 地理位置查询
- [FastLogin](https://github.com/games647/FastLogin) — 方案参考
- [MC_Protocol_Data](https://github.com/Nickid2018/MC_Protocol_Data) - 协议包参考
