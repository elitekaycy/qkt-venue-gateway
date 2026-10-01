# Running qkt-venue-gateway

How to run a gateway for each venue account, connect qkt and guardrails to it, and keep it running.
Everything here applies to any adapter; venue specifics are in each adapter's README
([paper](../adapter-paper/README.md), [deribit](../adapter-deribit/README.md)).

## The shape

```
qkt ───────────┐  http://<gateway>:8443   ┌─ gateway-paper   (paper account)    volume paper-state
guardrails ────┼──────────────────────────┤
               │                          └─ gateway-deribit (Deribit account)  volume deribit-state
```

One container per venue account, beside qkt, the same way qkt uses `mt5-gateway`. Each container has
its own tokens, its own volume (its journal) and its own port. Two accounts never share a container or
a volume.

## 1. Run with Docker Compose (recommended)

Requires Docker with Compose v2.

```bash
git clone https://github.com/elitekaycy/qkt-venue-gateway && cd qkt-venue-gateway
cp .env.example .env            # fill in the tokens (any long random strings: openssl rand -hex 32)
docker compose up -d            # gateway-paper on 127.0.0.1:8443
docker compose ps               # STATUS shows (healthy) once it serves
docker compose logs -f gateway-paper
```

Add the Deribit account by filling the `DERIBIT_*` lines of `.env` (a testnet key first), then:

```bash
docker compose --profile deribit up -d     # gateway-deribit on 127.0.0.1:8444
```

Another account is another service in `docker-compose.yml`: copy `gateway-deribit`, give it its own
name, port, volume and `.env` variables.

## 2. Run one container by hand

```bash
docker build -t qkt-venue-gateway .
docker run -d --name gw-paper --restart unless-stopped \
  -p 127.0.0.1:8443:8443 -v gw-paper:/data \
  -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
  qkt-venue-gateway
```

For Deribit add `-e GATEWAY_ADAPTER=deribit -e GATEWAY_SETTING_ENVIRONMENT=testnet
-e GATEWAY_LOGIN=<client id> -e GATEWAY_SECRET=<client secret>`, a different port and a different volume.

## 3. Run without Docker (development)

Requires JDK 21.

```bash
GATEWAY_TRADER_TOKEN=dev GATEWAY_GUARDIAN_TOKEN=dev-guard ./gradlew :app:run
# or a runnable distribution:
./gradlew :app:installDist
GATEWAY_TRADER_TOKEN=dev GATEWAY_GUARDIAN_TOKEN=dev-guard app/build/install/qkt-venue-gateway/bin/qkt-venue-gateway
```

Outside the image the defaults are `127.0.0.1:8443` and `./state`.

## Configuration

Every setting is a `GATEWAY_*` environment variable; the full table is in the
[README](../README.md#configuration), and the defaults live in
[`GatewayConfig`](../app/src/main/kotlin/com/qkt/venuegateway/config/GatewayConfig.kt). A variable
with no default is required: the gateway refuses to start and names it.

Secrets can be files instead of variables (`<NAME>_FILE`), which is how Compose secrets work:

```yaml
services:
  gateway-deribit:
    environment:
      GATEWAY_SECRET_FILE: /run/secrets/deribit_secret
    secrets: [deribit_secret]
secrets:
  deribit_secret:
    file: ./secrets/deribit_secret     # one line, the client secret; keep it out of git
```

## Connect qkt

One `type: gateway` broker entry per account in qkt's config; the entry name is the symbol prefix.

```yaml
brokers:
  deribit:
    type: gateway
    gateway_url: http://127.0.0.1:8444        # qkt on the host
    api_key: env:DERIBIT_TRADER_TOKEN         # the gateway's GATEWAY_TRADER_TOKEN
    expected_adapter: deribit
    expected_account_login: "<client id>"
    expected_trade_mode: demo                 # real on mainnet
```

- **qkt on the same host:** `http://127.0.0.1:<published port>`.
- **qkt in Docker:** put both on one network and use the service name with the container port,
  `http://gateway-deribit:8443`. With qkt in another compose project, create the network once
  (`docker network create qkt`) and attach both projects to it as `external: true`.

qkt refuses to start when the gateway's adapter, account login or trade mode differ from the entry, so
a wrong gateway is caught before any order.

## Connect guardrails (the kill switch)

Guardrails uses the guardian token. It reads like qkt and can stop new exposure:

```bash
G="Authorization: Bearer $GATEWAY_GUARDIAN_TOKEN"
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill -d '{"scope":"all"}'
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill -d '{"scope":"symbols","symbols":["BTC_USDC-PERPETUAL"]}'
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill/release -d '{"scope":"all"}'
```

While engaged, only reducing orders, cancels and position closes pass. The switch is kept in the
journal, so it survives a restart. Details: [vgp-v1-wire.md](vgp-v1-wire.md) §3.

## State, backups and upgrades

- `/data` (the volume) holds `journal.db` (orders, fills, events, kill switch), `instruments.db` and
  the adapter's own state. It is the account's memory: losing it starts a new stream, which makes
  qkt resynchronize from the venue, and forgets which client order ids were already placed.
- Back up with the container stopped (Compose prefixes volume names with the project name):

  ```bash
  docker compose stop gateway-paper
  docker run --rm -v qkt-venue-gateway_paper-state:/data -v "$PWD":/backup alpine \
    tar czf /backup/paper-state.tgz -C /data .
  docker compose start gateway-paper
  ```
- Upgrade: `git pull && docker compose build && docker compose up -d`. The journal is kept; qkt
  reconnects and replays from its last sequence, losing or doubling nothing.
- Never point two containers at one volume, and never reuse a volume for another account.

## Health, logs and alerts

- `GET /v1/health` (any token) is the readiness probe: `venue_connected`, the kill switch, `stream`
  and `seq`. The image's healthcheck calls it; `docker compose ps` shows the result.
- Logs go to stdout (`docker compose logs`) at info level; they never carry a token or secret.
- Alert when `venue_connected` stays `false` for more than 2 minutes, or the container is unhealthy.

## Going to mainnet

1. Run the same account type on testnet first, with qkt trading through it end to end.
2. Create a mainnet API key with read and trade scopes only, never withdrawal.
3. Run a separate gateway (its own service, port, volume and tokens) with
   `GATEWAY_SETTING_ENVIRONMENT=mainnet`; health then reports `trade_mode: real`.
4. In qkt, set `expected_trade_mode: real` on that entry; qkt refuses a mismatch.
5. Keep the port bound to `127.0.0.1` or a private network; TLS terminates at your reverse proxy.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `GATEWAY_TRADER_TOKEN (or GATEWAY_TRADER_TOKEN_FILE) is required` | set both tokens in `.env` |
| `adapter deribit: setting environment is required` | set `DERIBIT_ENVIRONMENT` (`GATEWAY_SETTING_ENVIRONMENT`) |
| `GATEWAY_SECRET is required with GATEWAY_LOGIN` | credentials are a pair: set both or neither |
| `no adapter of type 'x' is installed (installed: …)` | `GATEWAY_ADAPTER` is misspelled, or the plugin jar is not in `GATEWAY_PLUGINS_DIR` |
| `takes no arguments` | the YAML config is gone; pass `GATEWAY_*` variables instead |
| `401` on every call | wrong bearer token for this gateway |
| `423 kill_switch` on orders | guardrails engaged the kill switch; release it with the guardian token |
| `503 venue_unavailable` | the venue link is down; `/v1/health` shows `venue_connected: false`, the gateway reconnects by itself |
| qkt refuses to start: adapter/login/mode mismatch | the broker entry points at the wrong gateway or expects the wrong account |
| unhealthy right after start | wait for the start period; check `docker compose logs` for the venue link |
