# Running the gateway

Run one gateway per venue account, next to qkt. Each gateway has its own container, port, tokens and
volume. Venue-specific setup is in each adapter's README:
[paper](../adapter-paper/README.md) and [deribit](../adapter-deribit/README.md).

## Start

### Docker Compose (recommended)

```bash
cp .env.example .env              # set the tokens: any long random strings (openssl rand -hex 32)
docker compose up -d              # gateway-paper on 127.0.0.1:8443
docker compose ps                 # shows (healthy) once it serves
docker compose logs -f gateway-paper
```

`docker-compose.yml` has one service per account. To add an account, copy a service and give it its own
name, port, volume and variables.

### Docker

```bash
docker build -t qkt-venue-gateway .
docker run -d --name gw-paper --restart unless-stopped \
  -p 127.0.0.1:8443:8443 -v gw-paper:/data \
  -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
  qkt-venue-gateway
```

### Without Docker

Requires JDK 21.

```bash
./gradlew :app:installDist
GATEWAY_TRADER_TOKEN=… GATEWAY_GUARDIAN_TOKEN=… app/build/install/qkt-venue-gateway/bin/qkt-venue-gateway
```

## Configuration

Everything is an environment variable. Defaults live in one place,
[`GatewayConfig`](../app/src/main/kotlin/com/qkt/venuegateway/config/GatewayConfig.kt). A variable
without a default is required, and the gateway won't start without it.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_TRADER_TOKEN` | required | Bearer token qkt trades with |
| `GATEWAY_GUARDIAN_TOKEN` | required | Bearer token for reads and the kill switch |
| `GATEWAY_ADAPTER` | `paper` | The adapter: `paper`, `deribit`, or a plugin's type |
| `GATEWAY_LISTEN` | `127.0.0.1:8443`; `0.0.0.0:8443` in the image | Address to serve on |
| `GATEWAY_STATE_DIR` | `./state`; `/data` in the image | The journal and adapter state. Keep it on a volume |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | none | Venue credentials, both or neither |
| `GATEWAY_PLUGINS_DIR` | none | Directory of extra adapter jars |
| `GATEWAY_SETTING_<KEY>` | set by the adapter | An adapter setting, e.g. `GATEWAY_SETTING_STOP_TRIGGER` sets `stop_trigger` |

Each adapter's settings are listed in its README.

**Secrets as files.** Any variable except a setting can be given as `<NAME>_FILE`, a path to a file
holding the value. This is how Docker and Kubernetes secrets work:

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

## Connect qkt

Add one `type: gateway` broker per gateway to qkt's config. The broker's name becomes the symbol
prefix (`PAPER:BTC_USDC_PERPETUAL`).

```yaml
brokers:
  paper:
    type: gateway
    gateway_url: http://127.0.0.1:8443
    api_key: env:PAPER_TRADER_TOKEN       # the gateway's GATEWAY_TRADER_TOKEN
    expected_adapter: paper
    expected_account_login: paper
    expected_trade_mode: demo             # real for a mainnet account
```

- **qkt on the same host:** `http://127.0.0.1:<published port>`.
- **qkt in Docker:** put both on one network and use the service name: `http://gateway-paper:8443`.
  If qkt runs in another Compose project, create the network once (`docker network create qkt`) and
  attach both projects to it as `external: true`.

qkt refuses to start if the gateway's adapter, account or trade mode don't match the entry.

## Kill switch

Guardrails holds the guardian token. It can read everything and stop new exposure:

```bash
G="Authorization: Bearer $GATEWAY_GUARDIAN_TOKEN"
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill -d '{"scope":"all"}'
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill -d '{"scope":"symbols","symbols":["BTC_USDC-PERPETUAL"]}'
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill/release -d '{"scope":"all"}'
```

While the switch is on, only cancels, position closes and orders that reduce a position go through.
The switch survives restarts.

## API at a glance

| Route | Token | Does |
|---|---|---|
| `GET /v1/health` | any | Adapter, account, venue link, kill switch, stream and sequence |
| `GET /v1/account`, `/v1/positions` | any | Balances and margin; open positions |
| `GET /v1/instruments[/{code}]` | any | Listings, including dated contracts expired within 30 days |
| `POST /v1/orders`; `PATCH`, `DELETE /v1/orders/{id}` | trader | Place (idempotent), change, cancel |
| `GET /v1/orders[/{id}]` | any | Open orders; one order by its client id |
| `POST /v1/positions/close` | trader | Close a position at market |
| `GET /v1/deals`, `/v1/settlements`, `/v1/bars` | any | Fills, settlements and closed bars, paged |
| `WS /v1/stream?since=<seq>` | any | The event journal: replayed from `since`, then live |
| `WS /v1/quotes?symbols=…&roots=…` | any | Live quotes by code or option root |
| `POST /v1/kill`, `/v1/kill/release` | guardian | Kill switch |

Prices and quantities are decimal strings. The full contract is in [vgp-v1-wire.md](vgp-v1-wire.md).

## State, backups and upgrades

- **State.** The volume (`/data`) holds the account's journal: orders, fills, events and the kill
  switch. If it's lost, the gateway starts a new stream, qkt resyncs from the venue, and the record of
  which order ids were already used is gone. Until it restarts, the gateway looks up each submit it has
  no record of at the venue (by label, else its fills of the last 7 days) before placing it, so a resend
  after the loss is answered, not placed twice; each new order costs those venue reads meanwhile. Never
  share a volume between accounts or containers.
- **Backup.** Stop the container, then archive the volume (Compose prefixes volume names with the
  project name):

  ```bash
  docker compose stop gateway-paper
  docker run --rm -v qkt-venue-gateway_paper-state:/data -v "$PWD":/backup alpine \
    tar czf /backup/paper-state.tgz -C /data .
  docker compose start gateway-paper
  ```

- **Upgrade.** Check out a release tag (changes are in [CHANGELOG.md](../CHANGELOG.md)), then run
  `docker compose build && docker compose up -d`. The journal is kept, and qkt replays from its last
  sequence.

## Health and logs

- `GET /v1/health` is the readiness check. The image's healthcheck calls it.
- Logs go to stdout and never contain tokens or secrets.
- Alert if `venue_connected` stays `false` for more than two minutes.

## Going live

1. Run the same setup on the venue's test environment, with qkt trading through it end to end.
2. Create a live API key with read and trade permissions only. Never grant withdrawal.
3. Run a separate gateway for it, with its own service, port, volume and tokens.
4. Set `expected_trade_mode: real` in qkt.
5. Keep the port on `127.0.0.1` or a private network, and put TLS on your reverse proxy.

## Troubleshooting

| Message or symptom | Fix |
|---|---|
| `GATEWAY_TRADER_TOKEN (or GATEWAY_TRADER_TOKEN_FILE) is required` | Set both tokens |
| `adapter deribit: setting environment is required` | Set `GATEWAY_SETTING_ENVIRONMENT` to `testnet` or `mainnet` |
| `GATEWAY_SECRET is required with GATEWAY_LOGIN` | Set both credentials, or neither |
| `no adapter of type 'x' is installed` | Check `GATEWAY_ADAPTER`, or put the plugin jar in `GATEWAY_PLUGINS_DIR` |
| `takes no arguments` | Config files are gone: use `GATEWAY_*` variables |
| `401` on every call | Wrong token for this gateway |
| `423 kill_switch` | The kill switch is on; release it with the guardian token |
| `503 venue_unavailable` | The venue link is down; the gateway reconnects on its own |
| qkt won't start: adapter, login or mode mismatch | The broker entry points at the wrong gateway |
