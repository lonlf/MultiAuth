# 安装

本页介绍 MultiAuth 的完整安装步骤，包括独立模式与代理模式两种部署方式。配置细节可参考 [配置](Configuration.md)。

## 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 编译与运行要求 |
| Spigot / Paper | 1.18.2+ | 后端服务端 |
| Velocity | 3.4.0+ | 仅代理模式需要 |
| PacketEvents | 2.7.0+ | **仅独立模式需要**，代理模式无需 |
| Multiverse-Core | 4.3.12+ | 可选，用于 `login-spawn-point.world` 自定义世界传送 |
| MySQL | 5.7+ / 8.0+ | 可选，多服共享数据库时使用 |

## 获取插件

从构建产物或 Release 获取两个 JAR：

- `multiauth-spigot-<version>.jar` — Spigot/Paper 端
- `multiauth-velocity-<version>.jar` — Velocity 端（仅代理模式需要）

---

## 模式一：独立模式（proxy=false，默认）

单个 Spigot 服务端自行完成全部 Mojang 验证。

### 安装步骤

1. 将 `multiauth-spigot-<version>.jar` 放入服务端 `plugins/` 目录
2. 安装 [PacketEvents](https://modrinth.com/plugin/packetevents) 插件（独立模式的加密握手依赖）
3. 编辑 `server.properties`：

```properties
online-mode=false
```

4. 启动服务器，自动生成 `plugins/MultiAuth/config.yml`、`lang/` 语言文件与数据库
5. 编辑 `plugins/MultiAuth/config.yml`，最小配置如下：

```yaml
# plugins/MultiAuth/config.yml
proxy: false                     # Spigot 独立模式，自行完成 Mojang 验证

database:
  type: sqlite                   # 单服使用默认 SQLite 即可

auth:
  enabled: true
  login-timeout: 600             # 登录超时（秒）
  register-timeout: 180          # 注册超时（秒）

session:
  timeout: 0                     # 会话超时（分钟），0=禁用
```

6. 执行 `/multiauth reload` 或重启服务端生效

> **注意**：若未安装 PacketEvents，插件自动回退到 API-only 验证模式（仅做用户名检查，离线玩家放行、正版玩家与 auth-list 玩家拒绝），无法执行完整加密握手。

---

## 模式二：代理模式（proxy=true）

Velocity 端对每个连接显式设置验证结果，Spigot 子服仅校验 Velocity 转发的 UUID 与数据库记录是否一致。此模式**不依赖** Velocity 全局 `online-mode` 设置(不过仍旧建议使用online-mode)。

```
客户端 ──→ Velocity (MultiAuth 验证) ──→ Spigot 子服 (MultiAuth, proxy=true)
                                              │
                                              └──→ 共享 MySQL 数据库
```

### 1. Velocity 端安装

1. 将 `multiauth-velocity-<version>.jar` 放入 Velocity 的 `plugins/` 目录
2. 编辑 Velocity 根目录的 `velocity.toml`：

```toml
online-mode = true
player-info-forwarding-mode = "modern"
forwarding.secret = "设置一个足够长的随机密钥"
```

| 配置项 | 说明 |
|---|---|
| `online-mode` | 建议保持 `true`，避免影响其他依赖全局配置的插件（本插件按连接强制验证，不受此值影响） |
| `player-info-forwarding-mode` | 必须为 `"modern"` 或 `"bungeeguard"`，用于向后端转发已验证的 UUID |
| `forwarding.secret` | 转发密钥，**所有 Spigot 子服必须配置相同值** |

3. 启动 Velocity，自动生成 `plugins/multiauth/config.toml`，编辑 `session-sync-secret` 与数据库配置（见下）

### 2. Spigot 子服端安装

1. 将 `multiauth-spigot-<version>.jar` 放入每个子服的 `plugins/` 目录
2. 编辑 `server.properties`：

```properties
online-mode=false
```

3. 若使用 **Paper** 服务端，还需编辑 `paper-global.yml` 开启 Velocity 转发：

```yaml
# paper-global.yml（仅 Paper 服务端需要）
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "与 velocity.toml 的 forwarding.secret 一致"
```

4. 编辑 `plugins/MultiAuth/config.yml`：

```yaml
# 各 Spigot 子服 plugins/MultiAuth/config.yml
proxy: true                      # 信任 Velocity 的验证结果
```

### 3. 跨服会话同步（可选）

启用后，玩家在子服间转移时**免重复登录**；关闭则换服后需重新登录。

两端配置**完全一致**的密钥字符串：

```yaml
# Spigot plugins/MultiAuth/config.yml
proxy: true
session-sync-secret: "kF8#vM2!qR7@sL9"   # 与 Velocity 端一致
```

```toml
# Velocity plugins/MultiAuth/config.toml
session-sync-secret = "kF8#vM2!qR7@sL9"  # 与 Spigot 端一致
```

> 密钥留空 = 关闭跨服会话同步。两端密钥不一致时，同步消息会被拒绝（fail-closed）。

### 4. 共享数据库（必须）

代理模式下，Velocity 与所有 Spigot 子服必须指向**同一 MySQL**（相同 database 与 `mysql-table-prefix`）：

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

> 两端未共享同一数据库时，离线账号、登录历史、IP 限制等数据会按服务端分裂，跨服会话同步无法工作。

### 5. 正版 UUID 策略（两端需保持一致）

```yaml
# Spigot config.yml
use-mojang-uuid: true
```

```toml
# Velocity config.toml
cross-server-use-mojang-uuid = true
```

> 两端取值不一致会导致数据库中的 UUID 记录被反复改写。

### 最小配置汇总

```yaml
# 各 Spigot 子服 plugins/MultiAuth/config.yml
proxy: true
session-sync-secret: "kF8#vM2!qR7@sL9"   # 与 Velocity 端一致，启用跨服会话同步

database:
  type: mysql
  mysql-host: localhost
  mysql-port: 3306
  mysql-database: multiauth
  mysql-username: root
  mysql-password: ""
  mysql-table-prefix: multiauth_
```

---

## 验证安装

启动后以管理员身份执行：

```text
/multiauth status
```

输出应包含：
- 插件版本
- 数据库连接状态
- 当前模式（独立 / 代理）
- Mojang API 状态（正常 / 宕机 / 未启用）
- 总历史玩家数
- 正版玩家数

## 相关文档

- [配置](Configuration.md) — 配置文件详解
- [常见问题](Troubleshooting.md) — 部署与使用中的常见问题
