# Configuration

MultiAuth has two config files, both **generated automatically on first startup** (comments are bilingual Chinese/English):

- Spigot side: `plugins/MultiAuth/config.yml`
- Velocity side: `plugins/multiauth/config.toml`

See [config.yml](../../spigot/src/main/resources/config.yml) and [config.toml](../../velocity/src/main/resources/config.toml) for the complete option reference.

## Language

The `language` option (default `en_gb`, supported: `zh_cn` / `en_gb`) controls the plugin's message language. On first startup the matching language file is auto-selected from the **system locale**:

| System Locale | Auto-selected |
|---|---|
| Simplified Chinese | `zh_cn` |
| English | `en_gb` |
| Other | `en_gb` (default) |

Deployed servers are not rewritten automatically; edit `language` manually or delete the config file and restart to trigger detection.

## Spigot config.yml

| Section | Default | Description |
|---|---|---|
| `language` | `en_gb` | Language code, auto-detected on first startup |
| `debug` | `false` | Debug mode (verbose logging) |
| `proxy` | `false` | Proxy mode (`true` = trust Velocity's verification) |
| `use-mojang-uuid` | `true` | Whether to use the premium UUID after successful verification |
| `session-sync-secret` | `""` | Cross-server session sync secret (must match Velocity; empty = disabled) |
| `auth-list` | `[]` | Player names forced to undergo Mojang verification |
| `mojang-api` | — | Fallback API URLs, per-username request rate limit |
| `database` | — | Database type, connection params, heartbeat interval |
| `backup` | — | Scheduled backups, backup dir, max count |
| `auth` | — | All offline registration/login options (below) |
| `session` | — | Session timeout (minutes, 0 = no auto-login resumption) |

### auth section (offline registration & login)

- **Register/Login**: enable flags, password length range, timeouts
- **Unauthenticated restrictions**: force adventure mode, freeze position, disable move/chat/interaction/damage/commands, command whitelist
- **Spawn & location**: `login-spawn-point` (teleport point for unauthenticated joins), `return-last-location` (return to the last quit location after login)
- **Post-login behavior**: `force-survival` (force survival mode)
- **Security** (`auth.security`): failed-attempt counters and cooldowns, per-IP account limits, IP-change warnings, geo-IP anomaly detection, login history

## Velocity config.toml

Structure is mostly the same as the Spigot side; main differences:

| Section | Description |
|---|---|
| `cross-server-use-mojang-uuid` | Cross-server premium UUID policy (must match Spigot's `use-mojang-uuid`) |
| `auth-list` | Auth list (Velocity side) |
| `session-sync-secret` | Session sync secret (must match the Spigot side) |
| `mojang-api` / `database` / `backup` | Same meaning as the Spigot side |

There is no `auth` section (offline registration/login is handled by Spigot backends) and no `proxy` option (Velocity is always the proxy side).

**Database requirement**: in proxy mode, Velocity and all Spigot backends must point to the **same MySQL** (same database, same `mysql-table-prefix`), otherwise offline accounts, login history, IP limits and other data are split per server.

## Hot Reload

Run `/multiauth reload` to apply changes immediately:

- All config options and language files
- Proxy mode switching (register/unregister PacketEvents interceptor)
- Security services (rebuild geo lookup, clear failed-attempt counters)
- Command whitelist for unauthenticated players

**Not cleared**: online players' login state and persistent sessions (reload does not kick or force re-login).

**Not applied immediately**: `use-mojang-uuid` (already-logged-in players need to re-login), database type switching (server restart required).

> Caution: changing `use-mojang-uuid` casually may break other UUID-related plugins; do not change it unless necessary.

## Language Files

Language files live in `plugins/MultiAuth/lang/`:

- `zh_cn.yml` — Simplified Chinese
- `en_gb.yml` — English

Changes take effect immediately after `/multiauth reload`; missing keys fall back to hardcoded defaults. Note: an already-generated `lang/` directory is not updated automatically — delete the directory to regenerate, or edit the corresponding file manually.
