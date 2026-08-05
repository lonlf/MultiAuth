# MultiAuth

> Minecraft 玩家认证插件 · 支持正版/离线混合验证 · Spigot & Velocity 双平台

MultiAuth 是一个支持正版与离线玩家混合验证的 Minecraft 认证插件。核心能力包括 Mojang 两层验证、宕机降级、离线玩家注册登录、安全增强（失败计数/IP 限制/异地登录检测）、数据库迁移与备份、完整国际化。

## 核心特性

- **两层 Mojang 验证**：用户名检查 + 加密握手 hasJoined 验证
- **宕机降级**：Mojang API 不可达时自动放行有历史记录的离线玩家
- **离线注册登录**：Argon2id 密码哈希、会话恢复、未登录限制（移动/聊天/交互/命令）
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

database:
  type: sqlite                   # sqlite / mysql

auth:
  enabled: true
  login-timeout: 600             # 登录超时（秒）
  register-timeout: 180          # 注册超时（秒）

session:
  timeout: 0                    # 会话超时（分钟），0=禁用
```

## 部署模式

| 模式 | 配置 | 说明 |
|---|---|---|
| **独立模式**（默认） | `proxy: false` | Spigot 自行执行 Mojang 验证，需安装 PacketEvents |
| **代理模式** | `proxy: true` | Velocity 端 `online-mode=true`，Spigot 仅校验转发的 UUID |

## 环境要求

- JDK 21+
- Spigot/Paper 1.18.2+ 或 Velocity 3.4.0+
- [packetevents](https://modrinth.com/plugin/packetevents) (Spigot)

## 致谢

- [PacketEvents](https://github.com/retrooper/packetevents) — 数据包拦截
- [ip2region](https://github.com/lionsoul2014/ip2region) — 离线 IP 地理位置查询
- [FastLogin](https://github.com/games647/FastLogin) — 方案参考
- [MC_Protocol_Data](https://github.com/Nickid2018/MC_Protocol_Data) - 协议包参考

## License

详见各依赖库的 LICENSE。

## 作者

**lonlf** — [https://github.com/lonlf](https://github.com/lonlf)
