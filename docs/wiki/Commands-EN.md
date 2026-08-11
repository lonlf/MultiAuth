# Commands & Permissions

## Player Commands

| Command | Usage | Description |
|---|---|---|
| `/register` (aliases `reg`, `r`) | `/register <password> <confirm>` | First-time registration (offline players only; auto-login after registering) |
| `/login` (alias `l`) | `/login <password>` | Login (registered offline players only) |
| `/changepassword` | `/changepassword <old> <new>` | Change password (must be logged in) |

## Admin Commands

| Command | Usage | Description |
|---|---|---|
| `/multiauth reload` | — | Reload config + language + proxy mode |
| `/multiauth status` | — | Show plugin status (version/database/mode/Mojang API/player stats) |
| `/multiauth backup` | — | Manual database backup |
| `/multiauth migrate <type>` | `sqlite \| mysql` | Database migration |
| `/multiauth info [player]` | — | Show account info (players query themselves; admins can query anyone) |
| `/multiauth unregister <player>` | — | Force-delete an offline player account |

Velocity admin commands are unified under `/vmultiauth` (console only), sharing the same subcommands and logic with the Spigot `/multiauth`.

## Permission Nodes

| Permission | Default | Description |
|---|---|---|
| `multiauth.admin` | OP | Admin commands (reload/status/backup/migrate/unregister) and `info` for any player |
| `multiauth.info` | everyone | `info` to query your own account info |

## /multiauth info Behavior

`info` works for both premium and offline players. Without arguments the target defaults to the executor:

| Executor | `info` (no args) | `info <player>` |
|---|---|---|
| Admin player | Query self | Query any player |
| Admin console | Shows usage | Query any player |
| Normal player | Query self | "You can only query your own info" |

Output (same structure for premium/offline: common part + type-specific part):

- **Common**: player name, UUID, online status, last IP, geo location
- **Offline account**: registration time, last login
- **Premium player**: last login, first join (shows "unknown" for history records)
- **Quit location** (admin only): world name + coordinates, "unknown" when not recorded
- **Related accounts** (multi-account detection): attributed by the player's most recent login IP — queries all accounts under that IP (offline account table + premium player table, merged and deduplicated); online accounts are shown green, offline white. Always visible to admins; visible to normal players only when `auth.notify-other-accounts` is enabled (same level as quit location). After a player changes IP, the new related set is the list queried by the new IP

## Multi-Account Detection

Based on AuthMe's attribution logic, accounts are attributed by the **most recent login IP** (the registration IP is stored but unused; last_ip is overwritten on every login):

- On successful login (premium join / offline login / registration / session resume / cross-server sync) the player is automatically notified of other accounts on the same IP, controlled by `auth.notify-other-accounts` (single source of truth, off by default; Velocity has no separate option — info display reads the same field from the shared AuthConfig)
- `info` shows the related accounts with the same visibility: visible to normal players when the option is on, otherwise admin only

## Mojang API Status Values

| Status | Meaning |
|---|---|
| `Normal` | API enabled and reachable |
| `Down` | All APIs unreachable (incl. fast-fail during cooldown); only players with an offline history record are allowed in |
| `Disabled` | proxy=true Spigot backend makes no API calls |
| `Unknown` | Service not initialized |
