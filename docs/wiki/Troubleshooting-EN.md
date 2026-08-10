# Troubleshooting

Common issues when deploying or using MultiAuth, and how to fix them.

## Players cannot join in standalone mode

Make sure [PacketEvents](https://modrinth.com/plugin/packetevents) is installed and `online-mode=false` in `server.properties`. Without PacketEvents the plugin falls back to API-only verification and cannot run the full encrypted handshake.

## Proxy mode switch has no effect

After changing `proxy`, you must **restart the server** (reload cannot hot-swap the PacketEvents dependency or the external forwarding config); otherwise mixing old and new modes causes connection errors.

## Data split across servers

Confirm that Velocity and all backend servers point to the **same MySQL** with the same `mysql-table-prefix`. Otherwise offline accounts, login history, IP limits and other data are split per server, and cross-server session sync cannot work.

## Session sync failure

Confirm that `session-sync-secret` is **exactly identical** on both sides (case-sensitive). With mismatched secrets, sync messages are rejected (fail-closed), and players must re-login after transferring between servers.

## Offline player cannot log in

With the message `Login failed: Invalid session (Please restart your game and the launcher)`, possible causes:

- The username collides with a premium player (Mojang verification is taken over by the same-named premium account)
- The launcher's premium login state is stale, causing hasJoined verification to fail

## Quit location is not recorded

`auth.return-last-location` defaults to `false`; this option controls both "save location on quit" and "return location after login". Enable it to record the `last_world/last_x/...` fields.

## Language file changes have no effect

An already-generated `plugins/MultiAuth/lang/` directory is not updated automatically. Delete the directory to let the plugin regenerate the files, or edit the corresponding file manually and run `/multiauth reload`.

## Related Docs

- [Installation](Installation-EN.md) — installation & deployment
- [Configuration](Configuration-EN.md) — config file details
