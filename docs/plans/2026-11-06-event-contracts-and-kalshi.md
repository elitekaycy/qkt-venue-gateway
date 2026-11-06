# Event contracts, a catalog at scale, and the Kalshi adapter — design

**Status:** design, 2026-11-06. The gateway half of qkt's prediction-market design
(`qkt/docs/superpowers/specs/2026-11-06-event-contracts-design.md`, with the venue-neutral symbol-set work in
`2026-11-06-symbol-sets-and-universes-design.md`). Amends `docs/vgp-v1-wire.md` additively; the qkt copy of the
wire spec stays authoritative and changes in the same step.

## 1. Problem

Prediction markets (Kalshi first, Polymarket later) list **binary event contracts**: a YES/NO question that pays 1
per contract when it resolves YES and 0 otherwise, priced between 0 and 1. They are organised as
**series → event → market** (`KXINXU` "S&P 500 hourly above/below" → `KXINXU-26NOV06H1500` "the 15:00 ET close"
→ `KXINXU-26NOV06H1500-T7425` "≥ 7425"). Thousands of markets open and close every day.

The gateway cannot serve them today:

- `InstrumentKind` has no kind for them, and the contract kit allows an expiry only on futures and options
  (`adapter-testkit/.../ContractChecks.kt:61-67`).
- `/v1/instruments` has no paging, filter or change feed. The host re-calls `adapter.instruments()` on every
  request (`host/.../Gateway.kt:82,85`), single-code lookups scan the whole listing
  (`host/.../market/InstrumentShelf.kt:51-55`), and the shelf keeps every dated instrument for 30 days after
  expiry (`InstrumentShelf.kt:40-48`). Kalshi's daily churn would grow the served listing to around 10^5 rows.
- A single `tick_size` cannot describe venues whose price grid changes across the price range.
- Quote subscriptions go in the WebSocket URL, and every held quote is re-sent to every subscriber every 5 s
  (`host/.../market/QuoteHub.kt:83-90`).

## 2. The rule: venue-neutral in the host, venue words in the adapter

The split this design keeps, so the next venue (Polymarket, ForecastEx, Hyperliquid outcome markets) costs only
an adapter:

| Lives in host / `adapter-api` / `vgp-wire` / testkit (every venue benefits) | Lives in `adapter-kalshi` only |
|---|---|
| `InstrumentKind.EVENT` and its optional fields | RSA-PSS request signing |
| Catalog paging, filtering and change polling, host-cached | Kalshi REST v2 / WebSocket clients, shard selection |
| Indexed single-code lookup, per-kind shelf keep policy | Series/event/market → `Instrument` mapping |
| Optional `tick_ranges` | YES/NO book → one YES book (NO bid at p = YES ask at 1−p) |
| `roots=` expansion by event and series | "0 = no bid" → absent side |
| Change-only quote refresh for large subscriptions | Market orders as marketable IOC limits; stop refusals |
| Contract-kit rules for `event` | Fee from the series' `fee_type` / `fee_multiplier` onto each fill |
| | Settlement from `market_lifecycle_v2` and the market's result |

No host code names Kalshi. No adapter code re-implements journal, idempotency, reconciliation or the kill
switch.

## 3. Decisions

### 3.1 `event` instrument kind (additive, the equity precedent)

Follows gateway #85 / qkt 28760c585: an enum value, optional nullable wire fields absent when unset, a host wire
test, a spec edit, a qkt reader. No protocol bump.

- `InstrumentKind.EVENT` (`adapter-api/.../VenueEnums.kt:7`). KDoc: a contract paying 1 per unit when its
  question resolves YES, priced in [0, 1], settling at a value in [0, 1] (1 or 0, or a scalar when the venue voids
  or settles at a fair price).
- `Instrument` gains optional fields, null for every existing adapter (`VenueAccount.kt:18-32`); `WireInstrument`
  gains the snake_case equivalents (`vgp-wire/.../Wire.kt:45-63`), absent when null so every listing served
  today stays byte-identical:

| Field | Meaning | Kalshi source |
|---|---|---|
| `series` | the recurring question type, the family | `series_ticker` |
| `event` | one instance of the series (one close time) | `event_ticker` |
| `strike_type` | `greater`, `greater_or_equal`, `less`, `less_or_equal`, `between`, `custom` | `strike_type` (`functional`/`structured` → `custom`) |
| `floor`, `cap` | the strike bounds the predicate uses | `floor_strike`, `cap_strike` |
| `open_at`, `close_at` | trading opens / stops (epoch ms) | `open_time`, `close_time` |
| `title` | the human question for this market | `yes_sub_title` ("7,425 or above") |
| `exclusive` | the event's markets partition its outcomes (exactly one resolves YES) | event `mutually_exclusive` |
| `outcome` | the settled value once known | `result` / `settlement_value` |
| `fee_model` | `probability`: fee per order = round_up(rate × contracts × p × (1 − p), rounding) | series `fee_type` |
| `taker_fee_rate`, `maker_fee_rate` | the rates in that formula (maker absent = no maker fee) | 0.07 / 0.0175 × series `fee_multiplier` |
| `fee_rounding` | the rounding unit | 0.01 |

The fee fields let qkt show a contract's fee to a strategy before it trades, live as in backtests. They describe
a fee shape, not a venue: Polymarket's taker fee has the same form. Actual charges still arrive on each fill.

- Reused fields: `expiry` = the time the result is expected (`expected_expiration_time`), so the shelf and qkt's
  dated-contract logic keep working; `category` = the venue's category (`Financials`, `Climate and Weather`),
  which qkt already matches in calendar rules; `trading` = `close_only`/`disabled` for closed or halted markets
  (no new `trading` values: qkt's `tradeModeOf` throws on unknown ones, `qkt:.../GatewayInstrumentSpec.kt:41-47`).
  `contract_size` = 1, `currency` = the settlement currency.
- Contract kit (`ContractChecks.kt:54-68`): an `EVENT` branch — expiry, `series`, `event`, `close_at` required;
  strike and right absent; `floor`/`cap` consistent with `strike_type` (`between` needs both, `greater*` needs
  `floor`, `less*` needs `cap`, `custom` neither).
- Host test `EventInstrumentWireTest` beside `EquityInstrumentWireTest.kt`.

### 3.2 Catalog at scale (every adapter, no capability needed)

- **Host-cached listing.** The host reads `adapter.instruments()` on a timer (default 60 s, adapter-overridable
  through a new `VenueAdapter.listingRefreshMs` with a default) and on a lookup miss (rate-limited, as QuoteHub
  already does), not on every request. Requests are served from the cache. An adapter's own `Listing` cache keeps
  working underneath.
- **Indexed lookup.** `/v1/instruments/{code}` reads a map, not `listing.firstOrNull`.
- **Paging and filters on `/v1/instruments`**, all optional: `kind`, `category`, `series`, `event`, `status`
  (`open|closed|all`), `updated_since`, `cursor`, `limit` (≤ 5000). No parameters = today's full list,
  unchanged. A page carries `next`.
- **Change polling.** `updated_since=<ms>` returns instruments added or changed since then, plus removals as
  `{"code", "removed": true}`. The host derives changes by diffing successive cached reads, so adapters do nothing.
  This is a poll, not a stream event: stream events are journaled money records kept 7+ days
  (`docs/design.md:154-160`), and thousands of listing changes a day do not belong there.
- **Shelf keep policy per kind.** Events are remembered 7 days after expiry (setting
  `GATEWAY_EVENT_KEEP_DAYS`), not 30. That still covers restarts and late settlement reads; qkt's by-code fallback
  for unlisted symbols is extended to events (qkt side).
- **By-code lookup of unlisted instruments.** A new optional `VenueAdapter.instrument(code): Instrument?` (default
  null) lets `/v1/instruments/{code}` answer for a contract the venue no longer lists, with its `outcome`. Kalshi
  answers any market by ticker, settled or not. qkt's `fetch --catalog` uses it to fill settlement values older than
  the keep window.

### 3.3 Price grids (`tick_ranges`, optional)

`Instrument.tickRanges: List<TickRange(from, to, tick)>?`, wire `tick_ranges`. Absent = `tick_size` everywhere,
as today. `tick_size` stays and carries the finest tick, so a client that ignores ranges still sends prices the
venue can refuse loudly rather than mis-round silently. Kalshi publishes `price_ranges` per market; Polymarket
has per-market tick sizes, so this is venue-neutral.

### 3.4 Quotes for many contracts

- `roots=` already expands by any instrument's `underlying` (`QuoteHub.kt:122-126`). Extend the expansion to
  match `event` and `series` too, so a client subscribes "every market of `KXINXU`" in one short URL. Spec text
  (wire spec :89-91, :363-364) is relaxed accordingly.
- Opt-in `refresh=changes` on `/v1/quotes`: a held quote is re-sent only when it changes, plus a 30 s heartbeat
  frame, and changes inside one 250 ms tick go out as one batched frame. Default behaviour is unchanged.

### 3.5 Order-flow data uses the existing capabilities

No new data types. Kalshi fits the shapes the depth and trade-flow designs already defined:

- `trades`: Kalshi public trades carry `taker_side`; a taker buying YES is `buy`, a taker buying NO is `sell` (a
  NO purchase is a YES sale at 1−p). Recorded with `RecordedTape` where the venue's own history runs out.
- `depth`: the YES bids and the NO bids converted to YES asks at 1−p, best first, ≤10 levels, recorded on read
  (`RecordedDepth`). The testkit's ordering rules (`DepthChecks.kt:16,48-51`) then hold.
- `open_interest`: from the market's `open_interest`, recorded on read.
- `bars`: Kalshi candlesticks (1m/1h/1d); the bar price is the YES mid from the candle's bid and ask (Undefined
  when a side is empty), never the last trade, so bars agree with the quotes slots select on.

### 3.6 Orders on limit-only, stop-less venues

- Market orders: emulated by the adapter as a marketable IOC limit at the worst valid price, reported back with
  type `market` (wire spec :159-164). `POST /v1/positions/close` sends market + GTC + reduce_only
  (`host/.../orders/PositionCloser.kt:52`): the adapter treats GTC on a market order as IOC.
- Stops: refused by name with `VenueRefusedException` (the Bybit/Alpaca precedent). New optional capability
  `stop_orders`, declared by adapters that support native stops, so qkt can refuse a strategy that needs stops
  at start instead of on its first stop order. Absent on an older gateway = unknown; qkt then behaves as today.
- `reduce_only`: emulated in the adapter where the venue restricts it (Kalshi allows it only with IOC), as
  `AlpacaOrderDesk.kt:180-189` does. The kill switch's own reduce-only check is unchanged.

### 3.7 Accounts

One gateway is one account (`docs/design.md:12,64`). Kalshi keeps capital per exchange shard; v1 runs one gateway
per shard, the shard chosen by adapter setting, each with its own qkt `type: gateway` entry. A multi-balance
`/v1/account` is not added until a venue forces it.

## 4. `adapter-kalshi`

Branch `adapter/kalshi`, touching only `adapter-kalshi/**`, after the §3 host/API PR merges
(`docs/adapters.md:9-19`). Layout after `adapter-bybit` (closest: signed REST + WebSocket):

| Area | Files |
|---|---|
| Factory, settings | `KalshiAdapterFactory` (`type = "kalshi"`), `KalshiSettings`: `environment=demo\|production` (no default), `shard`, key id from `GATEWAY_LOGIN`, PEM from `GATEWAY_SECRET_FILE` |
| Signing | `client/KalshiAuth`: RSA-PSS SHA-256 over `timestamp_ms + METHOD + path` (query stripped), JDK `Signature`; moves to `venue-support` only when a second venue needs it |
| Clients | `client/KalshiRest` (v2 orders `POST /portfolio/events/orders`, amend/decrease, batch), `client/KalshiSocket` over `ReconnectingSocket` (`orderbook_delta` with seq/gap → fresh snapshot, `ticker`, `trade`, `fill`, `user_orders`, `market_lifecycle_v2`, `cfbenchmarks_value`) |
| Listing | `KalshiListing` on `support.Listing`: series → events → markets, refreshed every 60 s; excludes multivariate combo markets (`KXMVE…`) |
| Mapping | `KalshiMapping` (market → `Instrument` per §3.1), `KalshiOrderMapping` (signed YES quantity: buy = YES, sell = NO; prices as dollar strings; `tick_ranges` from `price_ranges`), `KalshiBookMapping` (NO bids → YES asks; 0 → absent) |
| Orders | `KalshiOrderDesk`: market→IOC emulation, stop refusal, reduce-only emulation, `client_order_id` as label, `orderByLabel` |
| Settlement | `KalshiSettlement`: lifecycle `settled` + market `result` → `VenueSettlement(price ∈ [0,1])`, stable timestamp (the journal dedupes on `(symbol, time)`) |
| Errors | `KalshiErrors`: venue codes → refused / unavailable |

Capabilities: `bars`, `quotes`, `settlements`, `trades`, `depth`, `open_interest`. Not `mark_prices`: Kalshi streams
a settlement index only for some series, and a capability promises data for every instrument; that waits for
per-instrument availability. Fees arrive per fill as `exchange_fee` costs, so live P&L needs no fee model.

Contract test on the Kalshi demo environment: `KALSHI_CLIENT_ID` / `KALSHI_CLIENT_SECRET` secrets. `activeCode`
must be a demo market with recent trading; if the demo is too thin for the kit's bars/tape checks, record that in
the adapter README rather than weaken the kit.

## 5. Polymarket (later, same host)

A thin `adapter-polymarket` whose `client/` talks over HTTP/WebSocket (shared `AdapterContext.http`) to a small
sidecar built on Polymarket's official SDK (`clob-client-v2` / `py-clob-client-v2`). The sidecar owns wallet
signing, the order and position contracts and on-chain redemption, and tracks Polymarket's protocol changes;
the gateway keeps the journal and kill switch. YES and NO are separate tokens: listed as two `event` instruments
per market, `trading: long_only`. **Gate:** the label rule (`VenueAdapter.kt:7-8`) — if Polymarket orders cannot
be found by a client label, the adapter keeps a label → order-id map in its state dir and the contract kit's
after-restart test decides whether that is enough.

## 6. Delivery order

1. `feat/event-kind`: §3.1 + kit rule + wire test + spec text (gateway and qkt copy).
2. `feat/catalog-scale`: §3.2 (cache, index, paging, `updated_since`, shelf policy).
3. `feat/tick-ranges` and `feat/quote-roots-by-family`: §3.3, §3.4.
4. `feat/stop-orders-capability`: §3.6.
5. `adapter/kalshi`: §4, through the checkpoints in `docs/adapters.md:61-78`.

Each host PR keeps every existing listing byte-identical when the new fields and parameters are unused, proved
by the existing wire tests plus one new test per change.

## 7. Open questions

1. Can a Kalshi order be looked up by `client_order_id` (not only by listing and filtering)? Probe in checkpoint 1.
2. Kalshi shards: which series live in which shard, and whether capital moves between shards by API.
3. Kalshi account eligibility and funding from the operator's country.
4. Demo-environment liquidity for the contract kit's bars and tape checks.
5. Whether any Kalshi settlement carries a fee (it would ride on `settlement.costs` as `delivery_fee`).
