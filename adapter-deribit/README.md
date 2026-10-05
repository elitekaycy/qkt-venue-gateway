# Deribit adapter

Trades one Deribit account: perpetuals, futures and options on USDC-linear contracts (sizes in
coins). Coin-margined contracts are not supported. It passes the adapter contract suite on testnet.

## Setup

1. **Create an API key** on [test.deribit.com](https://test.deribit.com) (or deribit.com for real
   money) with **read** and **trade** permissions only. Never grant withdrawal.
2. **Fill in the `DERIBIT_*` lines** of `.env`:

   ```bash
   DERIBIT_TRADER_TOKEN=…          # any long random string; qkt sends it
   DERIBIT_GUARDIAN_TOKEN=…        # any long random string; guardrails sends it
   DERIBIT_CLIENT_ID=…             # the API key's client id
   DERIBIT_CLIENT_SECRET=…         # the API key's secret
   DERIBIT_ENVIRONMENT=testnet     # or mainnet: real money
   ```

3. **Start it:**

   ```bash
   docker compose --profile deribit up -d      # serves on 127.0.0.1:8444
   source .env
   curl -H "Authorization: Bearer $DERIBIT_TRADER_TOKEN" http://127.0.0.1:8444/v1/health
   ```

   Health should show `"adapter":"deribit"`, your client id as `account_login`, and
   `"venue_connected":true`.

Without Compose:

```bash
docker run -d -p 127.0.0.1:8444:8443 -v gw-deribit:/data \
  -e GATEWAY_ADAPTER=deribit -e GATEWAY_SETTING_ENVIRONMENT=testnet \
  -e GATEWAY_LOGIN=<client id> -e GATEWAY_SECRET=<client secret> \
  -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
  qkt-venue-gateway
```

## Connect qkt

```yaml
brokers:
  deribit:
    type: gateway
    gateway_url: http://127.0.0.1:8444
    api_key: env:DERIBIT_TRADER_TOKEN
    expected_adapter: deribit
    expected_account_login: "<client id>"
    expected_trade_mode: demo             # real on mainnet
```

```sql
SYMBOLS
  perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m
  call = DERIBIT:BTC_USDC_25DEC26_92000_C EVERY 1m
```

qkt writes `-` in venue codes as `_`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_SETTING_ENVIRONMENT` | required | `testnet`, or `mainnet` for real money. Never defaulted |
| `GATEWAY_SETTING_CURRENCY` | `USDC` | The only supported currency |
| `GATEWAY_SETTING_STOP_TRIGGER` | `last_price` | Price a stop fires on: `last_price`, `mark_price` or `index_price` |
| `GATEWAY_SETTING_DEPTH_RETENTION_DAYS` | `0` | UTC days of recorded depth kept besides today (see Depth); `0` keeps everything |
| `GATEWAY_SETTING_OPEN_INTEREST_RETENTION_DAYS` | `0` | The same for recorded open interest (see Open interest) |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | required | The API key's client id and secret |

## Venue behaviour

Measured on testnet; recorded responses are in `src/test/resources/fixtures`.

- **Order labels.** The client order id is sent as Deribit's `label`. Deribit doesn't enforce unique
  labels, so only the gateway decides when to resend.
- **Closed orders.** Deribit forgets a closed order's label after about an hour, but keeps its trades,
  so the gateway recovers lost orders from fills.
- **Future sizes.** A future position's `size` is in dollars; the quantity is `size_currency`.
- **Stops.** A triggered stop becomes a new order under the same label. A stop already past its trigger
  is refused. Market and stop orders accept only GTC or DAY.
- **Klines.** Responses are silently cut at 5001 rows, so bars are fetched in chunks.
- **Settlements.** Expiries are read from `private/get_settlement_history_by_currency`: a `delivery` row
  when a future expires, an `exercise` row when an option does (out of the money too), both at 08:00 UTC
  and carrying the delivery price as `index_price` (the same figure `public/get_delivery_prices` publishes
  for the day). A future settles at it; an option at its intrinsic value from it, because an `exercise`
  row's `mark_price` is 0 even deep in the money. `settlement` rows, the daily session settlement every
  position gets, are not expiries and are left out. The history takes no window, only the newest instant
  to start from (inclusive), and pages newest first by a continuation (`"none"` on the last page);
  `type=exercise` is refused (`bad_argument`), so every type is read. The delivery fee is the
  `commission` of the transaction log's `expiry` (option) or `delivery` (future) row at the same instant,
  reported as `delivery_fee` when non-zero (0 for the testnet TRX call that expired on 2026-10-02).
  Expired contracts are looked up one by one (`public/get_instrument` keeps them) for their strike.
- **Listing.** An order on a code the account's USDC listing does not hold (a coin-margined contract such
  as `BTC-PERPETUAL`, a spot pair) is refused before it reaches Deribit: every read is scoped to the
  currency, so such an order would be traded outside them. The public ticker link is reported as the quote
  feed, so a dropped ticker socket makes quotes stale instead of re-sending the last price.
- **Inactive contracts.** Deribit lists contracts whose `state` is `inactive` (`is_active: false`; 96 of 3148
  USDC options on testnet on 2026-10-05, e.g. `AVAX_USDC-6OCT26-12-C`): their ticker never pushes and they take
  no orders. They are left out of the listing and of a root's options, but still found by name, so one held settles.
- **Funding rates.** `public/get_funding_rate_history` gives one row per hour: `interest_1h`, the rate
  accrued over the hour ending at `timestamp`, and `index_price` at its end (rates are JSON numbers such
  as `4.18e-05`, read from their text). A call answers at most about 740 hours, the newest, without
  saying it cut the rest, so rates are fetched in 720-hour spans. Testnet's history reaches back to at
  least 2024 for BTC_USDC-PERPETUAL.
- **Funding.** Deribit accrues funding continuously and realizes it into the transaction log
  (`private/get_transaction_log`) as `interest_pl` on a perpetual's rows, positive when the account
  gained. The adapter reports each non-zero row as a funding record (`tx-<id>`, the charge being
  `-interest_pl`); a `settlement` row's `position` is the one charged on. Deribit pushes no funding, so
  the gateway reconciles it every minute.
- **Mark prices.** Deribit keeps no mark or index history of futures or perpetuals:
  `public/get_mark_price_history` answers `[]` for them (it serves second-by-second marks of only the options
  in its volatility index, and never the index). Every public trade, though, carries the `mark_price` and
  `index_price` of its instant, so `/v1/marks` serves, for each window, the last trade's (a window without a
  trade has no sample). `get_last_trades_by_instrument_and_time` includes both ends, answers in time
  order (trades of one millisecond in no particular sequence order) and at most 1000 trades a call (`count`
  above is refused, `value is too high`), with `has_more` when it cut the rest. `get_last_trades_by_instrument`
  (by `start_seq`/`end_seq`) is not used: on `history.deribit.com` it answers the trades between the times of
  its two ends, so a page holds numbers outside the range and, cut at its count, misses some inside it
  (recorded in `trades-history-mainnet-slice.json`). The adapter reads the range's first and last trade, then
  either each window's last trade or every trade a page at a time by time (each page starting at the
  millisecond the last ended in), whichever costs fewer calls: at most a page's windows plus two, about
  0.2 s a call measured from Europe. A millisecond holding 1000 trades cannot be paged and fails the request.
- **Option marks.** Every option ticker carries `mark_iv` and `underlying_price` (its expiry's forward), so
  each option quote carries both and the adapter declares `OPTION_MARKS`. The ticker's `greeks` are Black-76
  on that mark IV and forward at rate 0 and a 365-day year (vega per volatility point, theta per day) to
  within 0.05%, published to 5 decimals, which leaves a BTC option's gamma one digit
  (`ticker-option-greeks.json`: gamma `1e-5` where the inputs give 0.0000059); they are not carried, and qkt
  prices the Greeks from the mark IV.
  The book summary has `mark_iv` and `underlying_price` but no Greeks (`book-summary-option.json`). No host
  keeps a history of either: `public/get_mark_price_history` answers `[]` for a linear option
  (`mark-price-history-linear-option.json`), and a trade carries its own `iv`, not the mark IV.
- **Mark history depth.** `test.deribit.com` and `www.deribit.com` answer trades by time for only about
  the last 24 hours (none 26 hours back, measured 2026-10-04). Mainnet's whole history, current to the
  second and including expired futures and options, is on `history.deribit.com` in the same shape, so a
  mainnet adapter reads marks there. Testnet has no history host (`history.test.deribit.com` does not
  answer): a testnet gateway serves marks of the last day only; fetch longer histories through a mainnet
  or paper gateway.
- **Open interest.** Deribit publishes no open-interest history: `public/get_open_interest_history` and
  `public/get_open_interest` are `Method not found` (-32601) on testnet and mainnet, and
  `public/get_tradingview_chart_data` carries prices, volume and cost only (probed 2026-10-04). The present
  figure is the ticker's `open_interest` (also in `public/get_book_summary_by_*`), at the ticker's
  `timestamp`, in the contract's amount unit: the base coin on a USDC-linear contract (testnet
  BTC_USDC-PERPETUAL 23307.7328, SOL_USDC-PERPETUAL 376285.029), USD on an inverse one. So the adapter
  **records** it: a read of `/v1/open-interest` whose window reaches the present (within a minute) first
  takes the ticker's figure, then serves what was recorded, kept one file a UTC day in
  `open-interest/<code>/<yyyy-MM-dd>.csv` in the state volume across restarts. A series starts when the
  gateway first read it and has one figure per read (qkt's live poll reads every minute); time nothing read
  it is a gap, never filled in. A gateway before the day files kept one `open-interest/<code>.csv`; the
  first read of the code splits it into day files and deletes it, every figure kept.
- **Depth.** Deribit publishes no order-book history (`public/get_order_book_history` is `Method not found`
  on testnet and mainnet, probed 2026-10-05). `public/get_order_book` with `depth=10` answers the book as it
  stands: `bids` best first, `asks` best first, each `[price, amount]` in the amount unit (the base coin on
  a USDC-linear contract), stamped with `timestamp`; prices may come in exponent form (`8.62e4`, read
  exactly), and a side with no orders is `[]` (`order-book-option-no-bids.json`). So the adapter
  **records** it, as it does open interest: a read of `/v1/depth` whose window reaches the present (within
  a minute) first takes the book, then serves what was recorded, kept one file a UTC day in
  `depth/<code>/<yyyy-MM-dd>.csv` in the state volume across restarts (about 270 bytes a snapshot of
  BTC_USDC-PERPETUAL: at qkt's 10-second poll, some 2.3 MB a contract a day). A series has one
  snapshot per read and starts when the gateway first read it. The read is one REST call, about 0.17 s from
  Europe. The `book.<code>.none.10.100ms` channel is not used: it pushes up to ten full snapshots a second
  (2.8 a second, 534 bytes each, on testnet BTC_USDC-PERPETUAL, measured 2026-10-05) to serve one read
  every 10 seconds, and would need its own staleness guard for a dropped socket.
- **Retention of the recordings.** Depth and open interest are recorded only by this gateway, so what it
  deletes is lost for good unless `qkt fetch --depth` / `qkt fetch --open-interest` copied it first. By
  default (`0`) nothing is deleted. With `GATEWAY_SETTING_DEPTH_RETENTION_DAYS` or
  `GATEWAY_SETTING_OPEN_INTEREST_RETENTION_DAYS` set to N, the gateway keeps today's file and the N days
  before it, and deletes every older day file, of every contract, when it connects and then once a UTC day
  (on the 10-minute refresh timer; each deletion is logged). A read of a range that reaches before the
  window answers what remains. Fetch into qkt more often than every N days. A value that is not a whole
  number of days fails the start, naming the setting.

- **Tape and liquidations.** `/v1/trades` and `/v1/liquidations` read the same
  `get_last_trades_by_instrument_and_time` pages as marks, on the same hosts (mainnet's history host; testnet's
  own, about a day deep). A print's `direction` is its taker's, the aggressor side; `amount` is in coins, the
  order quantity. A print that liquidated a position carries `liquidation`: `T` the taker was liquidated (the
  liquidation order is on the print's own side), `M` the maker (its resting order on the other side), `MT` both
  (Deribit's documented value, served as one liquidation of each side; none was seen in about six days of
  mainnet USDC futures history scanned on 2026-10-05, 2,314 liquidation prints, all `T` or `M`). A page
  cut at its count may end inside a millisecond (`tape-liquidation-taker.json` holds one of the eleven prints of
  1791096884055 ms), so each page starts at the millisecond the last one ended in. Liquidations are not
  indexed: finding them reads the whole tape of the range, a call per 1000 prints. Measured 2026-10-05 over a
  day: mainnet BTC_USDC-PERPETUAL about 150,000 prints, testnet about 15,000; mainnet USDC futures about 270
  liquidation prints across all contracts, testnet none.

The full list of API calls is in [design.md](../docs/design.md) §10.

## Contract suite

Runs against testnet when a testnet key is set, and is skipped without one:

```bash
DERIBIT_CLIENT_ID=… DERIBIT_CLIENT_SECRET=… ./gradlew :adapter-deribit:test --tests '*ContractTest' --rerun
```

It places, fills and cancels real testnet orders at the smallest size.
