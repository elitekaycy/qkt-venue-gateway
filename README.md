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

Mainnet is never a default: a Deribit config must say `environment: testnet` or `environment: mainnet`.

## Quickstart (paper, no account)

Requires JDK 21.

```bash
git clone https://github.com/elitekaycy/qkt-venue-gateway && cd qkt-venue-gateway
./gradlew build
```

`paper.yaml`:

```yaml
listen: 127.0.0.1:8443
state_dir: ./state/paper
tokens: { trader: env:GATEWAY_TRADER_TOKEN, guardian: env:GATEWAY_GUARDIAN_TOKEN }
adapter:
  type: paper
  settings: { currency: USDC, starting_balance: "10000" }
```

```bash
export GATEWAY_TRADER_TOKEN=change-me GATEWAY_GUARDIAN_TOKEN=change-me-too
./gradlew :app:run --args="paper.yaml"

curl -H "Authorization: Bearer $GATEWAY_TRADER_TOKEN" http://127.0.0.1:8443/v1/health
curl -H "Authorization: Bearer $GATEWAY_TRADER_TOKEN" http://127.0.0.1:8443/v1/instruments/BTC_USDC-PERPETUAL
```

Tokens and credentials are always references (`env:VAR` or `file:/path`), never inline, so a config file
carries no secret.

## A Deribit account

Create an API key with read and trade scopes only (never withdrawal). The login is the key's client id,
which qkt checks; the secret never leaves the gateway.

```yaml
listen: 127.0.0.1:8443
state_dir: /var/lib/qkt-venue-gateway/deribit
tokens: { trader: env:GATEWAY_TRADER_TOKEN, guardian: env:GATEWAY_GUARDIAN_TOKEN }
adapter:
  type: deribit
  credentials: { login: env:DERIBIT_CLIENT_ID, secret: env:DERIBIT_CLIENT_SECRET }
  settings:
    environment: testnet          # mainnet is real money
    currency: USDC                # the account currency qkt books in
    stop_trigger: last_price      # or mark_price, index_price
```

## Trading through it from qkt

One `type: gateway` broker entry per account in qkt's config; the entry name is the symbol prefix:

```yaml
brokers:
  deribit:
    type: gateway
    gateway_url: http://127.0.0.1:8443
    api_key: env:GATEWAY_TRADER_TOKEN
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
it in `plugins_dir`, and set `adapter.type`. `adapter-deribit` is the worked example; the interface and
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
| `app` | config, adapter loading, startup |

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
