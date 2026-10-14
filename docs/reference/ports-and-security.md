# Ports and security

## Ports

| What | Port | Notes |
|---|---|---|
| Gateway inside the container | `8443` | `GATEWAY_LISTEN=0.0.0.0:8443` in the image |
| Paper account (Compose) | `127.0.0.1:8443` | published on localhost only |
| Deribit account (Compose profile) | `127.0.0.1:8444` | published on localhost only |
| Bybit account (its `docker run`) | `127.0.0.1:8445` | published on localhost only |

The gateway speaks plain HTTP and WebSocket. Keep every port on `127.0.0.1` or a private network, and put
TLS on a reverse proxy when the network is shared.

## Tokens

| Token | Role |
|---|---|
| `GATEWAY_TRADER_TOKEN` | Trading: place, change and cancel orders, close positions, read |
| `GATEWAY_GUARDIAN_TOKEN` | Reads and the kill switch. A trader token cannot flip the kill switch |

Both are required. Use long random strings. Keep them out of config files: pass them as `env:` or `file:`
references, or as `<NAME>_FILE` secrets ([Configuration](configuration.md#secrets-as-files)).

## Venue credentials

- Give a venue API key read and trade permissions only. **Never grant withdrawal.**
- Pass `GATEWAY_LOGIN` and `GATEWAY_SECRET` together, or neither.
- Logs go to stdout and never contain tokens or secrets.

## What the security policy covers

In scope: authentication bypass (a request served without a valid role token, or a trader token flipping
the kill switch), credential leakage, order handling that can place, change or resend an order nobody asked
for or let new exposure past an engaged kill switch, and code execution, deserialization or path traversal
in the host, the config loader or plugin loading. Report problems privately: see the
[security policy](../project/security.md).
