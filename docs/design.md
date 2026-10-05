# qkt-venue-gateway — the VGP v1 gateway host and its adapters — design

**Status:** built: the host, the paper adapter and the Deribit adapter (contract suite passed on
testnet; qkt trades through it end to end). Deribit expiries are served from its settlement history. **Builds on:** the wire spec
`2026-10-01-vgp-v1-wire.md` (what any gateway must do), the qkt client in `connector/gateway` (what it
relies on), and `docs/research/2026-09-18-venue-plugin-architecture.md` §2 (why adapters run in a
separate host: the kill switch must be a choke point outside qkt).

## 1. What it is

One `qkt-venue-gateway` process serves **one account at one venue** over VGP v1. Two clients talk to it: the
qkt daemon (trading) and guardrails (watching the account and flipping the kill switch). It talks to
the venue through one **adapter**. qkt-insights does not talk to it: insights keeps ingesting qkt's own
event stream (signals, orders, fills, equity) as it does today. Everything a venue does not decide lives in
the host and is written once: auth, idempotent submits, the event journal (stream/seq/replay), the
kill switch, reconciliation with the venue, quote refresh, health and identity. An adapter only
translates between the venue's API and a small venue-neutral interface.

```
qkt daemon ──┐                 ┌──────────────────── qkt-venue-gateway (one per account) ───────────────────┐
             ├── VGP v1 ──────▶│ HTTP/WS server ─ auth ─ kill switch ─ idempotency ─ event journal    │
guardrails ──┘  (bearer)       │        │                        ▲             ▲                       │
                               │        ▼                        │             │                       │
                               │   order router ──────▶ adapter ─┴─ reconciler ┴─ quote hub (refresh)  │
                               └──────────────────────────────│───────────────────────────────────────┘
                                                              ▼
                                                    venue API (Deribit JSON-RPC/WS, …)
```

qkt-insights sits beside qkt, not beside the gateway: `qkt daemon ──telemetry──▶ insights collector`.

**Lives in its own repository** (`qkt-venue-gateway/` in the workspace), never inside qkt: qkt stays a
consumer of VGP, and a gateway can be replaced, written in another language, or certified on its own.

**Modules** (the build enforces the boundaries: an adapter can only see `adapter-api`):

| Module | Owns | Never touches |
|---|---|---|
| `vgp-wire` | the qkt-facing DTOs (decimal strings, error envelope) | venues |
| `adapter-api` | `VenueAdapter` and the neutral types adapters hand the host | HTTP to qkt, the journal |
| `host` | the VGP server: auth, journal, orders, stream, reconciler, kill switch, quotes, bars | any venue API |
| `adapter-testkit` | `AdapterContractTest`: the behaviour every adapter must pass | the host |
| `adapter-deribit` | Deribit, whole: its JSON-RPC in the `client` package (socket, auth, public and private calls, subscriptions), the mapping onto `adapter-api`, the adapter | VGP, the host |
| `adapter-paper` | venue-free matching on Deribit's public prices (from `adapter-deribit`'s `client`) | VGP, the host |
| `app` | config, choosing the adapter, starting the host | — |

An adapter speaks only its venue's transport; everything qkt sees (HTTP, WebSocket, JSON) is the host's.

**One venue, one module.** An adapter module holds everything for its venue, layered by package: a
`client` package that speaks the venue's protocol in the venue's own words and knows nothing of VGP,
a mapping that is the only place venue words become `adapter-api` types, and the adapter itself.
When the venue changes a field or an endpoint, the fix is in `client` and the recorded fixture that
breaks names the call; when it changes what a value means, the fix is in the mapping. A third-party
adapter follows the same shape and depends only on `adapter-api` (and `adapter-testkit` in tests).

## 2. Goals and non-goals

- **Goals:** every guarantee the wire spec states (decimal strings, idempotent POST, by-id lookup with
  dead ids, exclusive `since`, `reset`, settlements as events, kill switch with `reduce_only`, quotes
  refreshed while they hold); a restart of the gateway, of qkt, or of the venue connection loses or
  doubles nothing; adding a venue is writing one adapter.
- **Non-goals (v1):** more than one account per process; order types beyond market/limit/stop/stop-limit
  with `reduce_only` and GTC/IOC/GTD; venue-native combos; liquidation events (the wire spec defines
  none); HFT latency (holding periods are minutes to weeks).

## 3. The adapter interface

The host compiles against this; each adapter is one module implementing it. Every value is the
venue-neutral VGP shape (decimal strings become `BigDecimal` at this boundary, never `Double`).

```kotlin
interface VenueAdapter : AutoCloseable {
    val id: String                                     // "deribit" — reported as health.adapter
    val version: String
    val capabilities: Set<Capability>                  // optional services served (default: bars, quotes, settlements)
    fun connect(listener: AdapterListener)             // opens the venue link; listener gets pushes
    fun identity(): VenueIdentity                      // account login, trade mode (demo|real), currency
    fun instruments(): List<Instrument>                // codes, kind, sizes, expiry/strike/right/underlying
    fun account(): AccountSnapshot
    fun positions(): Positions                         // netting|hedging + per-symbol/ticket rows
    fun openOrders(): List<VenueOrder>
    fun place(order: NewOrder): VenueOrder             // order carries its client_order_id as the venue label
    fun cancel(clientOrderId: String): VenueOrder?
    fun modify(clientOrderId: String, change: OrderChange): VenueOrder?
    fun orderByLabel(clientOrderId: String): VenueOrder?   // venue lookup by our id; null = venue never saw it
    fun fills(fromMs: Long, toMs: Long): List<VenueFill>   // executions with venue fill ids and costs
    fun settlements(fromMs: Long, toMs: Long): List<VenueSettlement>
    fun funding(fromMs: Long, toMs: Long): List<VenueFunding>              // only with FUNDING
    fun fundingRates(code: String, fromMs: Long, toMs: Long): List<VenueFundingRate>  // only with FUNDING_RATES
    fun marks(code: String, windowMs: Long, fromMs: Long, toMs: Long): List<VenueMark>  // only with MARK_PRICES
    fun openInterest(code: String, fromMs: Long, toMs: Long): List<VenueOpenInterest>  // only with OPEN_INTEREST
    fun trades(code: String, fromMs: Long, toMs: Long, limit: Int): List<VenuePrint>  // only with TRADES
    fun liquidations(code: String, fromMs: Long, toMs: Long): List<VenuePrint>  // only with LIQUIDATIONS
    fun depth(code: String, fromMs: Long, toMs: Long): List<VenueDepth>  // only with DEPTH
    fun bars(code: String, windowMs: Long, fromMs: Long, toMs: Long): List<VenueBar>  // closed bars (venue klines)
    fun subscribeQuotes(codes: Set<String>, roots: Set<String>)  // pushes arrive on the listener
}

interface AdapterListener {
    fun order(o: VenueOrder); fun fill(f: VenueFill); fun settlement(s: VenueSettlement)
    fun funding(f: VenueFunding); fun quote(q: VenueQuote); fun connection(up: Boolean, reason: String)
}
```

Rules every adapter keeps, checked by the conformance suite (§9):
- **Our id travels with the order.** `place` puts `client_order_id` in the venue's label/client-id field
  so `orderByLabel` can find it after any crash. A venue without such a field cannot be adapted.
- **Fills carry the venue's own execution id**, so a fill seen twice (push and backfill) is one fill.
- **Capabilities are declared, never faked.** `capabilities` names the optional services the adapter
  serves: `BARS`, `QUOTES`, `SETTLEMENTS`, `FUNDING` (what the venue charged or credited the account for
  holding a perpetual), `FUNDING_RATES` (a perpetual's public rate history), `MARK_PRICES` (a contract's
  mark and index history, the last report in each window), `OPEN_INTEREST` (a contract's open
  interest over time, each figure at the instant the venue made it known), `OPTION_MARKS` (option quotes
  carry the mark IV and the forward, `VenueQuote.markIv` and `underlying`), `TRADES` (a contract's public
  tape, each print with its aggressor side), `LIQUIDATIONS` (the prints that liquidated a position, with
  the side liquidated) and `DEPTH` (a contract's order book, its best ten levels a side, as the adapter
  recorded it). The host reports them in
  `/v1/health`, never asks for one that is not declared, and answers `501 unsupported` for it; an
  undeclared call throws `VenueUnsupportedException`. A venue that charges funding but cannot report it
  does not declare `FUNDING`, and qkt then refuses to trade its perpetuals rather than book them without
  funding; a strategy reading a contract's mark or index starts only on a gateway declaring `MARK_PRICES`,
  one reading an option's implied volatility or Greeks only on one declaring `OPTION_MARKS`, one reading
  a contract's traded or liquidated volume only on one declaring `TRADES` or `LIQUIDATIONS`, and one
  reading a contract's book depth only on one declaring `DEPTH`.
- **A market order never rests** (wire spec, `POST /v1/orders`). A `MARKET` order, or a `STOP` once
  triggered, ends with what filled at once; an adapter whose venue leaves a remainder working (Deribit
  turns it into a limit at its price-band edge) cancels it as soon as it sees it, and reports the order
  with the type the client sent.
- **Pushes may arrive late, twice or out of order;** the host orders and dedupes them. An adapter never
  drops a push it cannot classify; it reports it as an error.
- **No threads, clocks, env or HTTP clients of its own:** the host hands them in (`HostServices`, as the
  research doc §5.4), so adapters are testable and secrets stay in one place.

## 4. The event journal (stream, seq, replay)

The journal is the gateway's memory and the source of the event stream.

- **Storage:** SQLite in WAL mode under the state directory, one file per account, written with
  `BEGIN IMMEDIATE` (the shared-DB locking lesson from the research DB). Tables: `meta` (stream id,
  created at), `events` (seq INTEGER PRIMARY KEY, type, time, data JSON), `orders` (client_order_id
  PRIMARY KEY, body hash, status, venue order id, last order JSON), `fills` (venue fill id PRIMARY KEY),
  `settlements` (symbol, time PRIMARY KEY), `funding` (venue funding id PRIMARY KEY), `dead_ids`.
- **Stream identity:** a random `stream` id is created with the journal. Losing the journal file means a
  new stream id, which tells every client to resynchronize from REST (`reset`), exactly as the wire spec
  defines.
- **Appending:** each order change, new fill, settlement or funding record is written in one transaction with its
  dedupe row (a fill whose venue id exists is not appended again), then published to open streams.
  `seq` increases by exactly one per event.
- **Replay:** `GET /v1/stream?since=<seq>` first sends every retained event with `seq > since`, then
  live ones. Retention keeps at least 7 days; a `since` older than the oldest retained event, or from
  another stream (named by the optional `stream` parameter, or beyond the latest `seq`), gets a `reset`
  first.
- **Health anchor:** `GET /v1/health` reads `stream` and the latest `seq` in the same transaction the
  client's REST reconcile follows, so nothing falls between them.

## 5. Orders: idempotency, by-id lookup, dead ids

- **POST /v1/orders** with a known `client_order_id` and the same body hash returns the stored order;
  a different body is `409 conflict`; an id in `dead_ids` is `409` for ever. A new id is first written
  to `orders` as `pending` (the write-ahead record), then sent to the adapter, then updated.
- **A crash between the write-ahead record and the venue's answer** is resolved on restart by
  `orderByLabel`: found means the venue has it (journal its state and fills); not found after the venue
  link is up means it was never placed (mark it rejected, journal the event).
- **A lost journal file** loses the records of orders already sent. While the journal was created by the
  running process, a submit with no record is first looked up through `OrderRecovery` (by label, else its
  fills); found means a resend of an order the venue holds: it is journaled under the body's hash and
  answered, never placed again.
- **GET /v1/orders/{id}:** the stored order, or the venue's by label when the journal lost it; `404`
  only when neither knows it, and that id is then written to `dead_ids` so a late POST of it can never
  place an order (the client's resolve-by-id relies on this).
- **503** while the venue link is down: reads are served from the journal where they can be, submits
  are refused before the write-ahead record so a retry is clean.

## 6. Reconciliation with the venue

Runs at start, after every venue reconnect, and every 60 seconds:
1. Venue open orders vs journal open orders: a journal order the venue no longer lists is looked up by
   label and its final state and fills journaled.
2. Venue fills since the last reconciled fill time (minus a 5-minute overlap) are journaled through the
   fill dedupe, so a push lost during a disconnect becomes an event late but exactly once.
3. Venue settlements since the last reconciled settlement, and funding since the last reconciled funding
   record, are journaled the same way, each only when the adapter declares it.
4. Positions are not journaled; they are served live from the venue (the client checks holdings).

## 7. Kill switch

- Scopes: `all`, or a set of symbols, held in the journal (`meta`), so a restart keeps the switch.
- `POST /v1/kill` and `POST /v1/kill/release` (operator token or guardrails token).
- While a scope covers a symbol, `POST /v1/orders` and `PATCH` are `423 kill_switch` unless the order is
  `reduce_only` and reduces the account's position at the venue (checked against live positions);
  cancels always pass. Health reports the switch.
- The switch is enforced in the gateway, never in qkt: the guardian can flip it while qkt is down.

## 8. Quotes

- One quote hub per process: it merges every client's `GET /v1/quotes` subscription into one adapter
  subscription (codes plus roots; a root expands to every listed option of it, including ones listed
  later, by refreshing the instrument list every 10 minutes).
- Each subscribed quote is pushed on change and **re-sent with `time` advanced at least every 5 seconds
  while it holds** (wire spec §4a), so a quiet book stays fresh and a stopped venue feed goes stale.
  It holds only while the venue link and the adapter's own quote feed are up: an adapter whose quotes
  come over a link apart from the venue link (Deribit's public ticker socket) reports that link through
  `AdapterListener.quoteFeed`, and while it is down nothing is re-sent, so qkt's stale-data gate trips.
- Quotes are never journaled and never replayed.

## 9. Testing

- **Conformance suite** (black-box, against a URL): every statement of the wire spec as a test, run in
  CI against `qkt-venue-gateway` with the paper adapter, and runnable against any VGP gateway. qkt's own
  client tests keep their `FakeGateway`; both are built from the one spec.
- **Adapter contract tests:** each adapter against recorded venue sessions (request/response fixtures),
  plus a testnet soak (Deribit testnet) before it may serve a `real` account.
- **Chaos tests:** kill the process between the write-ahead record and the venue answer; drop the venue
  socket mid-fill; restart with a deleted journal; each must end with every order and fill exactly once
  at the client.

## 10. Adapters, in order

1. **paper** — a venue-free adapter for forward testing through the real gateway path: it takes quotes
   from another adapter's public data (Deribit public feed, no account), fills market orders at the
   touch and limits when the opposite side reaches them, keeps positions and settles expiries at the
   venue's published delivery price. It runs the conformance suite in CI.
2. **deribit** — built and passing `AdapterContractTest` on testnet (2026-10-01), with qkt's own
   connector round-tripping a perpetual and an option through it. USDC-linear contracts only (amounts
   in coins); coin-margined contracts are refused. Every call was checked against testnet first and its
   answer recorded as a fixture (`adapter-deribit/src/test/resources/fixtures/private`): `public/auth`
   (client credentials, per connection), `public/set_heartbeat` (each `test_request` answered),
   `private/subscribe` to `user.orders|trades.{future,option}.USDC.raw` (every channel must be
   confirmed), `private/buy|sell` with `label`, `private/edit_by_label`, `private/cancel_by_label`, `private/cancel`
   (a market remainder, by order id),
   `private/get_order_state_by_label`, `private/get_open_orders_by_currency`, `private/get_positions`,
   `private/get_account_summary`, `private/get_user_trades_by_currency_and_time` (paged), and the public
   listing, ticker and kline calls. Venue rules the adapter keeps (declared to qkt as parity rows
   A50-A52): a stop fires on `stop_trigger` (`last_price` by default); a stop already crossed is refused;
   market and stop orders take only GTC or DAY. Other facts: a future's position `size` is dollars (its
   quantity is `size_currency`); a fired stop is a new Deribit order under the same label; labels are not
   unique at Deribit, so only the host decides to resend; kline answers are cut silently at 5001, so they
   are fetched in spans; a closed order is answered by label for under an hour (measured: found 27 min
   after closing, gone after about an hour, and absent from order history too), while trades stay, so
   the host resolves an order it lost track of from its fills (`OrderRecovery`). Expiries are settled from
   Deribit's settlement history (`delivery` rows for futures, `exercise` rows for options at intrinsic
   value from the delivery price), recorded from the option the testnet account held to 2026-10-02.
3. Later: a futures venue for CME products (Rithmic or a bridge, see the prop-automation findings), and
   `mt5-gateway` speaking VGP so MT5 accounts share the same client.

**Futures chains** are not part of v1 (wire spec §3): a dated future carries no root on the wire, so the
chain endpoint is designed with its first consumer, live continuous futures (qkt phase 46).

## 11. Deployment and operations

- One container per account (`Dockerfile`, `docker-compose.yml`), configured by `GATEWAY_*` environment
  variables (venue, adapter settings, credentials, state directory, listen address, tokens), each
  defaulted in one place or required. Secrets can be `_FILE` paths to mounted secrets.
- TLS terminates at the reverse proxy already in front of the fleet; the gateway listens on a private
  address. Tokens: one for qkt (trading), one for guardrails (reads and the kill switch).
- Logs are structured; health is the readiness probe; a venue link down for longer than 2 minutes alerts.

## 12. Decisions for the reviewer

1. **Language and server:** Kotlin on the JVM with Ktor (proposed), or Python (matching `mt5-gateway`).
   The deciding question is which connectors each can reach:

   | Connector | Interfaces it offers | From Kotlin/JVM |
   |---|---|---|
   | Rithmic | R\|Protocol API (WebSocket + protobuf, any language); R\|API+ (C++, .NET only) | R\|Protocol, natively |
   | CQG | WebAPI (WebSocket + protobuf, any language) | natively |
   | Tradovate | REST + WebSocket (JSON) | natively |
   | Interactive Brokers | TWS API, official clients in Java, Python, C++, C#; also REST | official Java client |
   | FIX venues (TT, many FCMs) | FIX 4.x/5.x | QuickFIX/J, the reference FIX engine |
   | Crypto (Deribit, Bybit, Binance…) | REST + WebSocket (JSON) | natively |
   | NinjaTrader | NinjaScript (C#) only | a bridge, as from Python |
   | MT5 | the `MetaTrader5` Python package (Windows) | the existing Python `mt5-gateway` |

   Every language-neutral connector is reached from the JVM directly, and the language-locked ones
   (Rithmic R|API+, NinjaTrader, MT5) need a separate native process whichever language the host is.
   That process should then speak VGP itself: the protocol is the contract, so the qkt client treats a
   C# NinjaTrader bridge or the Python `mt5-gateway` exactly like `qkt-venue-gateway`. Python reaches the same
   neutral connectors, but loses the one thing only the JVM gives: the same adapter JAR running inside
   qkt (backtest, paper) and inside the gateway (live), from the research doc §2. Hence Kotlin/Ktor.
   Rithmic access for automated trading also needs the FCM's and Rithmic's approval (see the 2026-09-30
   futures prop findings), whatever the language.
2. **Journal store:** SQLite (proposed) or an append-only file per day.
3. **First venue:** Deribit USDC-linear (proposed), matching the options data qkt already backtests.
4. **Paper adapter as the CI venue** (proposed) instead of mocking the venue in the host's tests.
