# MultiAuth

MultiAuth is a Minecraft authentication plugin that supports **mixed verification of premium and offline players**, running on both **Spigot/Paper** and **Velocity**.

[简体中文](Home.md) · **English**

## Core Features

- **Two-stage Mojang verification**: username check (checkPremium) + encrypted handshake verification (hasJoined), with automatic fallback to alternative APIs when the official API is down
- **Downtime fallback**: when all Mojang APIs are unreachable, only players with an offline history record are allowed in; all other logins are rejected
- **Offline registration & login**: Argon2id password hashing, login/register commands, session resumption, and unauthenticated-state restrictions (movement/chat/interaction/commands)
- **Cross-server session sync**: Velocity acts as the session hub; login state is synced to backend servers over an HMAC-SHA256 signed channel (`multiauth:session`), so players do not need to re-login when transferring between servers
- **Security hardening**: failed-attempt counters and cooldowns, per-IP account limits, IP-change warnings, and geo-IP anomaly detection (offline ip2region lookup)
- **Dual-platform support**: Spigot standalone mode (`proxy=false`) and Velocity proxy mode (`proxy=true`)
- **Database**: SQLite (default) + MySQL (HikariCP connection pool, optional SSL), scheduled backups, SQLite↔MySQL migration
- **i18n**: bundled `zh_cn` / `en_gb` language files, automatically selected from the system locale on first startup

## Quick Navigation

| Page | Content |
|---|---|
| [Installation](Installation-EN.md) | Requirements, standalone & proxy deployment |
| [Configuration](Configuration-EN.md) | Config files and key options |
| [Troubleshooting](Troubleshooting-EN.md) | Common deployment & usage issues |
