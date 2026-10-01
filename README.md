<h1 align="center">qkt-venue-gateway</h1>

<h3 align="center">One process per venue account between <a href="https://github.com/elitekaycy/qkt">qkt</a> and the venue.<br/>
Futures, perpetuals and options, over one small wire protocol.</h3>

<p align="center">
  <a href="https://github.com/elitekaycy/qkt-venue-gateway/actions/workflows/check.yml"><img src="https://github.com/elitekaycy/qkt-venue-gateway/actions/workflows/check.yml/badge.svg" alt="check"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="license"></a>
</p>

---

**qkt-venue-gateway** serves one trading account at one venue over **VGP v1**, the venue gateway protocol
qkt speaks ([wire spec](docs/vgp-v1-wire.md)). qkt sends orders and reads quotes, bars, fills and
positions through it; a guardian process can flip its kill switch. The gateway talks to the venue through
one **adapter**, a plugin that only translates between the venue's API and a small venue-neutral
interface. Everything a venue does not decide is written once, in the host:

- **Idempotent orders.** A client order id is placed at most once, ever; a retried submit returns the same order.
- **A journal qkt can replay.** Every order change, fill, position change and settlement is an event with a
  sequence number, written to SQLite before it is sent; after a restart of qkt, of the gateway or of the
  venue connection, a client asks for everything since its last sequence and loses or doubles nothing.
- **Orders resolved after any restart.** Orders are written ahead of the venue call. An order the venue
  forgot (some venues forget closed orders within the hour) is resolved from its fills, never sent again.
- **A kill switch outside qkt.** The guardian role stops new exposure for the account or per symbol; only
  reducing orders pass while it is engaged.
- **Quotes and closed bars.** A quote socket subscribed by code or by option root, and paged closed bars.

```
qkt ────────┐                ┌────────────── qkt-venue-gateway (one per account) ──────────────┐
            ├── VGP v1 ─────▶│ HTTP/WS ─ auth ─ kill switch ─ idempotency ─ event journal      │
guardian ───┘   (bearer)     │    │                       ▲            ▲                       │
                             │    ▼                       │            │                       │
                             │ order router ───▶ adapter ─┴─ reconciler┴─ quote hub            │
                             └──────────────────────│──────────────────────────────────────────┘
                                                    ▼
                                         venue API (Deribit JSON-RPC/WS, …)
```

## Adapters

| Adapter | Venue | Status |
|---|---|---|
| `paper` | Matching on Deribit's public prices, no account needed | Built in. Futures, perpetuals and options. |
| `deribit` | Deribit (testnet and mainnet) | Built in. Passes the adapter contract suite on testnet; qkt trades perpetuals, futures and options through it end to end. Settlement history is not served yet. |

Mainnet is never a default: a Deribit gateway must be told `testnet` or `mainnet`.

## Quickstart (paper, no account)

Requires Docker. The gateway runs as its own service beside qkt, one container per venue account.

```bash
git clone https://github.com/elitekaycy/qkt-venue-gateway && cd qkt-venue-gateway
cp .env.example .env              # set PAPER_TRADER_TOKEN and PAPER_GUARDIAN_TOKEN
docker compose up -d              # the paper account on 127.0.0.1:8443

source .env
curl -H "Authorization: Bearer $PAPER_TRADER_TOKEN" http://127.0.0.1:8443/v1/health
curl -H "Authorization: Bearer $PAPER_TRADER_TOKEN" http://127.0.0.1:8443/v1/instruments/BTC_USDC-PERPETUAL
```

Or one container by hand:

```bash
docker build -t qkt-venue-gateway .
docker run -d -p 127.0.0.1:8443:8443 -v gw-paper:/data \
  -e GATEWAY_TRADER_TOKEN=change-me -e GATEWAY_GUARDIAN_TOKEN=change-me-too qkt-venue-gateway
```

Without Docker (JDK 21): `GATEWAY_TRADER_TOKEN=… GATEWAY_GUARDIAN_TOKEN=… ./gradlew :app:run`.

## Configuration

Everything is a `GATEWAY_*` environment variable. Defaults live in one place,
[`GatewayConfig`](app/src/main/kotlin/com/qkt/venuegateway/config/GatewayConfig.kt); a variable with
no default is required, and the gateway refuses to start naming it.

| Variable | Default | |
|---|---|---|
| `GATEWAY_TRADER_TOKEN` | required | bearer token qkt trades with |
| `GATEWAY_GUARDIAN_TOKEN` | required | bearer token guardrails reads and flips the kill switch with |
| `GATEWAY_ADAPTER` | `paper` | the venue adapter: `paper`, `deribit`, or a plugin's type |
| `GATEWAY_LISTEN` | `127.0.0.1:8443` (`0.0.0.0:8443` in the image) | `<host>:<port>` to serve VGP on |
| `GATEWAY_STATE_DIR` | `./state` (`/data` in the image) | the journal and the adapter's state; keep it on a volume |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | none | venue credentials, both or neither |
| `GATEWAY_PLUGINS_DIR` | none | a directory of adapter jars beside the built-in ones |
| `GATEWAY_SETTING_<KEY>` | the adapter's | adapter setting `<key>`: `GATEWAY_SETTING_STOP_TRIGGER` is `stop_trigger` |

Any variable but a setting can be given as `<NAME>_FILE` instead, the path of a file holding the value
(a Docker or Kubernetes secret).

Adapter settings:

| Adapter | Setting | Default |
|---|---|---|
| `paper` | `currency` | `USDC` |
| | `starting_balance` | `10000` |
| | `fee_rate` | `0` |
| | `login` | `paper` |
| | `deribit_url`, `deribit_ws_url` | Deribit mainnet's public endpoints |
| `deribit` | `environment` | required: `testnet`, or `mainnet` for real money |
| | `currency` | `USDC` (the only one supported) |
| | `stop_trigger` | `last_price` (or `mark_price`, `index_price`) |

## A Deribit account

Create an API key with read and trade scopes only (never withdrawal). The login is the key's client id,
which qkt checks; the secret never leaves the gateway. Fill the `DERIBIT_*` lines of `.env`, then:

```bash
docker compose --profile deribit up -d    # the Deribit account on 127.0.0.1:8444
```

Mainnet is never a default: `DERIBIT_ENVIRONMENT` (the gateway's `GATEWAY_SETTING_ENVIRONMENT`) must
say `testnet` or `mainnet`.

## Trading through it from qkt

One `type: gateway` broker entry per account in qkt's config; the entry name is the symbol prefix:

```yaml
brokers:
  deribit:
    type: gateway
    gateway_url: http://127.0.0.1:8444      # http://gateway-deribit:8443 from a container on the compose network
    api_key: env:DERIBIT_TRADER_TOKEN       # the gateway's GATEWAY_TRADER_TOKEN
    expected_adapter: deribit
    expected_account_login: "<your client id>"
    expected_trade_mode: demo
```

```sql
SYMBOLS
  perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m
```

qkt writes venue codes with `-` as `_` (`DERIBIT:BTC_USDC_25DEC26_92000_C`). It refuses to start when the
gateway's adapter, account login or trade mode differ from what the entry expects.

## The protocol

| Route | Role | What |
|---|---|---|
| `GET /v1/health` | any | protocol, adapter, account, venue link, kill switch, stream and sequence |
| `GET /v1/account`, `/v1/positions` | any | balances and margin; open positions |
| `GET /v1/instruments`, `/v1/instruments/{code}` | any | the listing, including dated contracts expired within 30 days |
| `POST /v1/orders`, `PATCH`/`DELETE /v1/orders/{id}` | trader | place (idempotent), change, cancel |
| `GET /v1/orders`, `/v1/orders/{id}` | any | open orders; one order by client id, ended or not |
| `POST /v1/positions/close` | trader | close a position at the market |
| `GET /v1/deals`, `/v1/settlements` | any | fills and settlements, paged |
| `GET /v1/bars` | any | closed bars, paged |
| `WS /v1/stream?since=<seq>` | any | the event journal, replayed from `since`, then live |
| `WS /v1/quotes?symbols=…&roots=…` | any | quotes by code or by option root |
| `POST /v1/kill`, `/v1/kill/release` | guardian | engage or release the kill switch |

Quantities and prices travel as decimal strings; costs are positive when charged. The full contract,
including errors, dead ids and the replay rules, is [docs/vgp-v1-wire.md](docs/vgp-v1-wire.md).

## Writing an adapter

One venue is one module, `adapter-<venue>`, depending only on `adapter-api`:

- a `client` package that speaks the venue's protocol in the venue's own words and knows nothing of VGP;
- a mapping, the only place venue words become `adapter-api` types;
- the adapter (`VenueAdapter`) and its factory (`VenueAdapterFactory`, registered in
  `META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory`);
- tests: fixtures recorded from the venue, and one class extending `AdapterContractTest` from
  `adapter-testkit`, run against the venue's test environment.

The host gives every adapter the same guarantees (journal, idempotency, recovery from fills, kill switch,
quote refresh), so an adapter that passes the contract suite works with qkt as it is. Build the jar, put
it in `GATEWAY_PLUGINS_DIR`, and set `GATEWAY_ADAPTER`. `adapter-deribit` is the worked example; the interface and
its rules are in [docs/design.md](docs/design.md) §3.

## Repository

| Module | Owns |
|---|---|
| `vgp-wire` | the wire DTOs |
| `adapter-api` | `VenueAdapter` and the neutral types adapters hand the host |
| `host` | the server: auth, journal, orders, stream, reconciler, kill switch, quotes, bars |
| `adapter-testkit` | `AdapterContractTest`, the behaviour every adapter must pass |
| `adapter-deribit` | Deribit: its JSON-RPC `client`, the mapping, the adapter |
| `adapter-paper` | venue-free matching on Deribit's public prices |
| `app` | config from `GATEWAY_*` variables, adapter loading, startup; the `Dockerfile` builds it |

The build enforces the boundaries: the host never sees a venue API, and an adapter only sees `adapter-api`.

```bash
./gradlew build              # compile, ktlint, every module's tests
./gradlew :host:test         # one module
```

The Deribit contract suite runs only when `DERIBIT_CLIENT_ID` and `DERIBIT_CLIENT_SECRET` (a testnet
key) are set; everything else runs offline on recorded fixtures.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately as described in
[SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE).
