English | [简体中文](README_CN.md)

# MultiAuth

MultiAuth is a Minecraft authentication plugin supporting mixed verification of premium (online-mode) and offline players; supports both Spigot and Velocity.

## Core Features

- **Two-layer Mojang verification**: username check + encrypted handshake hasJoined verification
- **Downtime degradation**: automatically allows offline players with recorded history when the Mojang API is unreachable
- **Offline register/login**: Argon2id password hashing, session resume, unauthenticated restrictions (movement/chat/interaction/commands)
- **Cross-server session sync**: Velocity acts as the session center; login/logout states are synced to backend servers via an HMAC-SHA256 signed channel (`multiauth:session`), so players do not need to re-login when transferring between servers
- **Security enhancements**: failure counting and cooldowns, per-IP account limits, IP change warnings, geo login detection (ip2region offline lookup)
- **Dual platform support**: Spigot/Paper standalone mode + Velocity proxy mode
- **Database support**: SQLite (default) + MySQL (HikariCP connection pool, optional SSL)
- **Database backups**: scheduled backups + automatic cleanup of old backups + SQLite↔MySQL bidirectional migration
- **Internationalization**: zh_cn / en_gb bilingual (more in the future), 140+ message keys, hot reload

## Quick Start

### Installation

**Standalone Mode (Spigot)**

1. Download `multiauth-spigot-<version>.jar` into the `plugins/` directory
2. Also install [PacketEvents](https://modrinth.com/plugin/packetevents)
3. Start the server to generate the config file, edit `plugins/MultiAuth/config.yml`, and restart the server

**Proxy Mode (Spigot backend servers + Velocity)**

1. Download `multiauth-spigot-<version>.jar` (PacketEvents required) and `multiauth-velocity-<version>.jar` into each Spigot backend server's and Velocity's `plugins/` directory respectively
2. Start the backend servers and proxy to generate configs, then shut them down
3. Set up both ends following the "Configuration" section below (`proxy`, `velocity.toml`, shared database, session sync secret, etc.)
4. Restart the backend servers and proxy

### Configuration

**Velocity (velocity.toml)**

```toml
online-mode = true
player-info-forwarding-mode = "modern"
forwarding.secret = "forwarding secret shared with all backend servers"
```

**Spigot Backend (server.properties)**

```properties
online-mode=false
# Paper servers additionally need to enable Velocity forwarding in paper-global.yml
```

```yaml
# paper-global.yml (Paper servers only)
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "must match forwarding.secret in velocity.toml"
```

**Cross-server Session Sync Secret (optional — enables transfer between servers without re-login)**

```yaml
# Spigot plugins/MultiAuth/config.yml
proxy: true
session-sync-secret: "a long random secret shared with Velocity"
```

```toml
# Velocity plugins/MultiAuth/config.toml
session-sync-secret = "the same secret as on the Spigot side"
```

> The secret must be identical on both ends; leave it empty to disable cross-server session sync (players must re-login after switching servers).

**Shared Database (cross-server mode must use the same MySQL)**

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

> Both ends must point to the **same database with the same table-prefix**, otherwise data such as offline accounts, login history, and IP limits will be split across servers.

**Premium UUID Policy (optional — must be consistent on both ends)**

```yaml
# Spigot config.yml
use-mojang-uuid: true
```

```toml
# Velocity config.toml
cross-server-use-mojang-uuid = true
```

### Command Reference

| Command | Usage | Description |
|---|---|---|
| `/register` `/reg` `/r` | `/register <password> <confirm>` | Register an account (offline player) |
| `/login` `/l` | `/login <password>` | Login (registered offline player) |
| `/changepassword` | `/changepassword <old> <new>` | Change password |
| `/multiauth reload` | — | Reload configuration (admin) |
| `/multiauth status` | — | View plugin status (version, database, mode, Mojang API, player statistics) (admin) |
| `/multiauth backup` | — | Manual backup (admin) |
| `/multiauth info [player]` | — | View account info (no arg = self; others require admin; shows type/UUID/first join/last IP/geo/online status) |
| `/multiauth unregister <player>` | — | Unregister account (admin) |
| `/vmultiauth *` | — | Velocity console command |

### Permissions

| Permission Node | Default | Description |
|---|---|---|
| `multiauth.admin` | OP | Admin commands (reload/status/backup/migrate/unregister) and `info` for any player |
| `multiauth.info` | All players | `info` to view your own account info |

### Minimal Configuration

**Standalone Mode (single server, proxy: false)**

```yaml
# plugins/MultiAuth/config.yml
proxy: false                     # Spigot standalone mode, performs Mojang verification itself (PacketEvents required)

database:
  type: sqlite                   # Default SQLite is fine for a single server

auth:
  enabled: true
  login-timeout: 600             # Login timeout (seconds)
  register-timeout: 180          # Register timeout (seconds)

session:
  timeout: 0                     # Session timeout (minutes), 0 = disabled
```

**Proxy Mode (Velocity + Spigot backend servers, proxy: true)**

```yaml
# Each Spigot backend server's plugins/MultiAuth/config.yml
proxy: true                      # Trust Velocity's verification result
session-sync-secret: "kF8#vM2!qR7@sL9"   # Must match the Velocity side; enables cross-server session sync

database:
  type: mysql                    # Cross-server mode must share the same MySQL
  mysql-host: localhost
  mysql-port: 3306
  mysql-database: multiauth
  mysql-username: root
  mysql-password: ""
  mysql-table-prefix: multiauth_

auth:
  enabled: true
  login-timeout: 600             # Login timeout (seconds)
  register-timeout: 180          # Register timeout (seconds)
```

```toml
# Velocity plugins/MultiAuth/config.toml
session-sync-secret = "kF8#vM2!qR7@sL9"   # Must match all Spigot backend servers

[database]
type = "mysql"                   # Point to the same database as the Spigot backend servers
mysql-host = "localhost"
mysql-port = 3306
mysql-database = "multiauth"
mysql-username = "root"
mysql-password = ""
mysql-table-prefix = "multiauth_"
```

> In proxy mode, also set `online-mode = true` and `player-info-forwarding-mode = "modern"` in `velocity.toml`, and `online-mode=false` in each backend's `server.properties` (see the "Configuration" section above).

## Requirements

- JDK 21+
- Spigot/Paper 1.18.2+ or Velocity 3.4.0+
- [packetevents](https://modrinth.com/plugin/packetevents) (Spigot)

## Credits

- [PacketEvents](https://github.com/retrooper/packetevents) — Packet interception
- [ip2region](https://github.com/lionsoul2014/ip2region) — Offline IP geolocation lookup
- [FastLogin](https://github.com/games647/FastLogin) — Reference implementation
- [MC_Protocol_Data](https://github.com/Nickid2018/MC_Protocol_Data) - Protocol reference
