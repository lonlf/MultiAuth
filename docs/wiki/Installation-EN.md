# Installation

This page covers the full installation steps for MultiAuth, including both standalone and proxy modes. See [Configuration](Configuration-EN.md) for config details.

## Requirements

| Dependency | Version | Notes |
|---|---|---|
| JDK | 21+ | Build and runtime requirement |
| Spigot / Paper | 1.18.2+ | Backend server |
| Velocity | 3.4.0+ | Proxy mode only |
| PacketEvents | 2.7.0+ | **Standalone mode only**; not needed in proxy mode |
| Multiverse-Core | 4.3.12+ | Optional, for custom spawn world in `login-spawn-point.world` |
| MySQL | 5.7+ / 8.0+ | Optional, required for shared databases across servers |

## Getting the Plugin

Grab the two JARs from the build output or a Release:

- `multiauth-spigot-<version>.jar` — Spigot/Paper side
- `multiauth-velocity-<version>.jar` — Velocity side (proxy mode only)

---

## Mode 1: Standalone (proxy=false, default)

A single Spigot server performs the full Mojang verification itself.

### Steps

1. Put `multiauth-spigot-<version>.jar` into the server's `plugins/` directory
2. Install the [PacketEvents](https://modrinth.com/plugin/packetevents) plugin (required for the encrypted handshake in standalone mode)
3. Edit `server.properties`:

```properties
online-mode=false
```

4. Start the server; `plugins/MultiAuth/config.yml`, `lang/` language files and the database are generated automatically
5. Edit `plugins/MultiAuth/config.yml` with at least the following:

```yaml
# plugins/MultiAuth/config.yml
proxy: false                     # Spigot standalone mode; performs Mojang verification itself

database:
  type: sqlite                   # Default SQLite is fine for a single server

auth:
  enabled: true
  login-timeout: 600             # Login timeout (seconds)
  register-timeout: 180          # Register timeout (seconds)

session:
  timeout: 0                     # Session timeout (minutes), 0=disabled
```

6. Run `/multiauth reload` or restart the server to apply

> **Note**: without PacketEvents, the plugin falls back to API-only verification (username check only — offline players are allowed, while premium players and auth-list players are rejected); the full encrypted handshake cannot run.

---

## Mode 2: Proxy mode (proxy=true)

The Velocity side explicitly sets the verification result per connection, and Spigot backend servers only check that the UUID forwarded by Velocity matches the database record. This mode does **not** depend on the global `online-mode` setting in Velocity (setting it to `true` is still recommended).

```
Client ──→ Velocity (MultiAuth verification) ──→ Spigot backend (MultiAuth, proxy=true)
                                                      │
                                                      └──→ Shared MySQL database
```

### 1. Velocity side

1. Put `multiauth-velocity-<version>.jar` into Velocity's `plugins/` directory
2. Edit `velocity.toml` in the Velocity root directory:

```toml
online-mode = true
player-info-forwarding-mode = "modern"
forwarding.secret = "set a long random secret"
```

| Option | Notes |
|---|---|
| `online-mode` | Keep `true` to avoid affecting other plugins that rely on the global setting (this plugin verifies per connection regardless) |
| `player-info-forwarding-mode` | Must be `"modern"` or `"bungeeguard"` to forward the verified UUID to backends |
| `forwarding.secret` | Forwarding secret; **all Spigot backend servers must use the same value** |

3. Start Velocity; `plugins/multiauth/config.toml` is generated automatically. Edit `session-sync-secret` and the database config (below)

### 2. Spigot backend side

1. Put `multiauth-spigot-<version>.jar` into each backend server's `plugins/` directory
2. Edit `server.properties`:

```properties
online-mode=false
```

3. On **Paper** servers, also enable Velocity forwarding in `paper-global.yml`:

```yaml
# paper-global.yml (Paper servers only)
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "same as velocity.toml's forwarding.secret"
```

4. Edit `plugins/MultiAuth/config.yml`:

```yaml
# Each Spigot backend's plugins/MultiAuth/config.yml
proxy: true                      # Trust Velocity's verification result
```

### 3. Cross-server session sync (optional)

When enabled, players do not need to re-login when transferring between backend servers; when disabled, they must re-login after switching servers.

Use the **exact same** secret string on both sides:

```yaml
# Spigot plugins/MultiAuth/config.yml
proxy: true
session-sync-secret: "kF8#vM2!qR7@sL9"   # must match the Velocity side
```

```toml
# Velocity plugins/MultiAuth/config.toml
session-sync-secret = "kF8#vM2!qR7@sL9"  # must match the Spigot side
```

> An empty secret disables cross-server session sync. If the two sides use different secrets, sync messages are rejected (fail-closed).

### 4. Shared database (required)

In proxy mode, Velocity and all Spigot backend servers must point to the **same MySQL** (same database and `mysql-table-prefix`):

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

> Without a shared database, offline accounts, login history, IP limits and other data are split per server, and cross-server session sync cannot work.

### 5. Premium UUID policy (must match on both sides)

```yaml
# Spigot config.yml
use-mojang-uuid: true
```

```toml
# Velocity config.toml
cross-server-use-mojang-uuid = true
```

> Mismatched values cause the UUID records in the database to be rewritten repeatedly.

### Minimal config summary

```yaml
# Each Spigot backend's plugins/MultiAuth/config.yml
proxy: true
session-sync-secret: "kF8#vM2!qR7@sL9"   # matches Velocity; enables cross-server session sync

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

## Verifying the Installation

After startup, run as admin:

```text
/multiauth status
```

The output should include:
- Plugin version
- Database connection status
- Current mode (standalone / proxy)
- Mojang API status (up / down / not enabled)
- Total historical player count
- Premium player count

## Related Docs

- [Configuration](Configuration-EN.md) — config file details
- [Troubleshooting](Troubleshooting-EN.md) — common deployment & usage issues
