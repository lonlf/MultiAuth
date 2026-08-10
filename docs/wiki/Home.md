# MultiAuth

**中文** · [English](Home-EN.md)

MultiAuth 是一个支持**正版与离线玩家混合验证**的 Minecraft 认证插件，同时支持 **Spigot/Paper** 与 **Velocity** 平台。

## 核心特性

- **两层 Mojang 验证**：用户名检查（checkPremium）+ 加密握手 hasJoined 验证，官方 API 宕机时自动切换备用 API
- **宕机降级**：Mojang API 全部不可达时，仅放行有离线历史记录的玩家，其余登录一律拒绝
- **离线注册登录**：Argon2id 密码哈希、登录/注册命令、会话恢复、未登录状态限制（移动/聊天/交互/命令）
- **跨服会话同步**：Velocity 作为会话中心，登录状态经 HMAC-SHA256 签名通道（`multiauth:session`）同步到各子服，跨服转移免重复登录
- **安全增强**：失败计数与冷却、单 IP 账号数量限制、IP 变更警告、异地登录检测（ip2region 离线查询）
- **双平台支持**：Spigot 独立模式（`proxy=false`）与 Velocity 代理模式（`proxy=true`）
- **数据库**：SQLite（默认）+ MySQL（HikariCP 连接池、可选 SSL）、定时备份、SQLite↔MySQL 迁移
- **国际化**：内置 `zh_cn` / `en_gb` 语言文件，首次启动按系统语言自动选择

## 快速导航

| 页面 | 内容 |
|---|---|
| [安装](Installation.md) | 环境要求、独立/代理两种部署步骤 |
| [配置](Configuration.md) | 配置文件与主要配置项 |
| [常见问题](Troubleshooting.md) | 部署与使用中的常见问题 |
| [命令与权限](Commands.md) | 玩家/管理命令、权限节点、info 与 status 说明 |

