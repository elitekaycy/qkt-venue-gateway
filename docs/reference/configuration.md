# Configuration

Everything is an environment variable. Defaults live in one place,
[`GatewayConfig`](https://github.com/elitekaycy/qkt-venue-gateway/blob/main/app/src/main/kotlin/com/qkt/venuegateway/config/GatewayConfig.kt).
A variable without a default is required, and the gateway won't start without it.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_TRADER_TOKEN` | required | Bearer token qkt trades with |
| `GATEWAY_GUARDIAN_TOKEN` | required | Bearer token for reads and the kill switch |
| `GATEWAY_ADAPTER` | `paper` | The adapter's type: any [installed adapter](../adapters/overview.md), or a plugin's |
| `GATEWAY_LISTEN` | `127.0.0.1:8443`; `0.0.0.0:8443` in the image | Address to serve on |
| `GATEWAY_STATE_DIR` | `./state`; `/data` in the image | The journal and adapter state. Keep it on a volume |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | none | Venue credentials, both or neither |
| `GATEWAY_PLUGINS_DIR` | none | Directory of extra adapter jars |
| `GATEWAY_RECORDING_RETENTION_DAYS` | `0` | UTC days every recording of the venue's market (depth, open interest, the tape) keeps besides today; `0` keeps everything |
| `GATEWAY_SETTING_<KEY>` | set by the adapter | An adapter setting, e.g. `GATEWAY_SETTING_STOP_TRIGGER` sets `stop_trigger` |

Each adapter's settings are on its page, listed in the [adapters overview](../adapters/overview.md).

## Secrets as files

Any variable except a setting can be given as `<NAME>_FILE`, a path to a file holding the value. This is
how Docker and Kubernetes secrets work:

```yaml
services:
  gateway-deribit:
    environment:
      GATEWAY_SECRET_FILE: /run/secrets/deribit_secret
    secrets: [deribit_secret]
secrets:
  deribit_secret:
    file: ./secrets/deribit_secret     # keep out of git
```

## Start-up checks

The gateway refuses to start, with a message that names the variable, when:

- `GATEWAY_RECORDING_RETENTION_DAYS` is not a whole number of days, or a retired per-recording retention
  (`GATEWAY_SETTING_DEPTH_RETENTION_DAYS`, `_OPEN_INTEREST_`, `_TRADES_`) is set: the message names the one
  retention that replaced them.
- `GATEWAY_TRADER_TOKEN` or `GATEWAY_GUARDIAN_TOKEN` is missing.
- Only one of `GATEWAY_LOGIN` and `GATEWAY_SECRET` is set.
- `GATEWAY_LISTEN` is not `<host>:<port>`.
- `GATEWAY_ADAPTER` names an adapter that is not installed.
- A required adapter setting is missing, for example a venue adapter's `environment`.

See [Troubleshooting](../operations/troubleshooting.md) for each message and its fix.
