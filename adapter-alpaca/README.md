# Alpaca adapter

> Venue: Alpaca paper and live: US shares (whole shares, regular, extended and overnight sessions) and USD coin pairs · Status: New

Trades one Alpaca account: US shares listed on Alpaca (NASDAQ, NYSE, ARCA, AMEX, BATS, OTC), whole or, when the
account chooses them, fractional; and, opt-in, the coin pairs Alpaca lists (`BTCUSD`, `ETHUSD`, ...). One
gateway is one account. Options, trailing stops and auction orders (`opg`, `cls`) are not supported.

## Setup

1. **Create an API key** at [alpaca.markets](https://alpaca.markets): a paper account needs no funding. Create the
   key on the paper dashboard (or the live one for real money). Keep the paper account at or above $25,000 of
   equity: below it the pattern day trader rule restricts it, and Alpaca's forum reports a restricted paper account
   stays restricted until it is recreated.
2. **Start it:**

   ```bash
   docker run -d -p 127.0.0.1:8446:8443 -v gw-alpaca:/data \
     -e GATEWAY_ADAPTER=alpaca -e GATEWAY_SETTING_ENVIRONMENT=paper -e GATEWAY_SETTING_EXTENDED_HOURS=true \
     -e GATEWAY_LOGIN=<api key id> -e GATEWAY_SECRET=<api secret> \
     -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
     qkt-venue-gateway
   curl -H "Authorization: Bearer <trader token>" http://127.0.0.1:8446/v1/health
   ```

   Health should show `"adapter":"alpaca"`, the account number (`PA…`, from the Alpaca dashboard) as
   `account_login`, and `"venue_connected":true`. The account is named by its number, never by the API key.

## Connect qkt

```yaml
brokers:
  alpaca:
    type: gateway
    gateway_url: http://127.0.0.1:8446
    api_key: env:ALPACA_TRADER_TOKEN
    expected_adapter: alpaca
    expected_account_login: "<account number>"
    expected_trade_mode: demo             # real on the live environment
```

```sql
SYMBOLS
  aapl = ALPACA:AAPL EVERY 1m
  btc = ALPACA:BTCUSD EVERY 1m      -- with GATEWAY_SETTING_ASSET_CLASSES=us_equity,crypto
```

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_SETTING_ENVIRONMENT` | required | `paper`, or `live` for real money. Never defaulted |
| `GATEWAY_SETTING_FEED` | `iex` | The stock data feed quotes and bars come from: `iex` (free) or `sip` (paid) |
| `GATEWAY_SETTING_EXTENDED_HOURS` | `false` | `true` sends every limit DAY or GTC order on a share with Alpaca's `extended_hours`, so it may fill pre-market (04:00-09:30 New York), after hours (16:00-20:00) and overnight (20:00-04:00). VGP orders carry no such flag, so it is the account's choice |
| `GATEWAY_SETTING_ASSET_CLASSES` | `us_equity` | What is listed and traded: `us_equity`, `crypto`, or both (`us_equity,crypto`). Coin pairs are opt-in. A coin buy's fee is reported in the coin, which qkt 0.64.0 and later books as the quantity received ([qkt#1515](https://github.com/elitekaycy/qkt/issues/1515)); an older qkt holds more than the venue after a buy. Alpaca's dollar fee on a coin sale is reported by no fill, order or activity, so qkt's realized differs from the account's cash by it |
| `GATEWAY_SETTING_FRACTIONAL` | `false` | `true` lists a share Alpaca can split (`fractionable`) in steps of 0.000000001; a fractional quantity is then sent on DAY orders only (Alpaca refuses it on any other) and refused before Alpaca otherwise. Off by default because qkt's orders are GTC unless they say otherwise |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | required | The API key id and its secret |

## Capabilities

Bars and quotes. The host builds every bar window from Alpaca's klines of 1, 5, 15 and 30 minutes and an hour, all
on the UTC grid (Alpaca stamps a share's daily bar at midnight New York, 04:00 or 05:00 UTC, so days are built from
hours). Each quote subscription replaces the last: what is no longer asked for is unsubscribed on its stream. Alpaca
lists no expiring contracts, so settlements, funding, marks, open interest, the tape, liquidations and depth are
refused as unsupported.

## Venue behaviour

Measured on the paper environment, 2026-10-27/28, every answer recorded in `src/test/resources/fixtures`.

- **Instruments.** A share's code is its symbol (`AAPL`, `BRK.B`); a coin pair's is its symbol without the slash
  (`BTC/USD` is `BTCUSD`, as Alpaca's positions name it). 14,406 shares were active: 13,510 tradable, 5,255
  shortable, 7,867 fractionable. A share Alpaca lists but does not trade is `disabled`, one it will not lend
  `long_only`; a coin pair is always `long_only` (Alpaca sells no coin short). The category is the share's exchange,
  or `crypto`. Shares are whole (lot 1, tick 0.01: Alpaca refuses a sub-penny price on a share above $1); a coin
  pair's step, minimum and tick are Alpaca's `min_trade_increment`, `min_order_size` and `price_increment`.
- **Listing cost.** The whole share list is 6.5 MB and took from 2 s to 166 s; it is read only when the host lists
  instruments (at most every 10 minutes). An order or a quote resolves its share by itself (`/v2/assets/{symbol}`).
- **Orders.** The client's id is sent as `client_order_id` (unique on the account: a second order with it is
  refused with `40010001`, which the adapter answers with the order already holding it, so a retried place is not
  a second order). Alpaca has no reduce-only flag: a reduce-only order is sent for at most the position it reduces
  and refused when there is none.
- **Replace.** A replace is a new Alpaca order with a new `client_order_id`, which must also be unique; the adapter
  sends `<label>~r1`, `~r2`, ... and reads the label back from it, and a lookup by label follows `replaced_by` to the
  newest order. A replace of an order not yet live (`accepted`, before the session opens) is refused with `42210000`.
- **Cancel.** `DELETE` answers 204 before the order is final; the adapter polls until it reads `canceled`. The
  open-order list still showed a cancelled order for a moment after, so an order seen final in the last minute is
  left out of it.
- **Fills.** The trade stream pushes `fill` and `partial_fill` with an `execution_id`; a FILL activity's id is
  `<time>::<execution_id>`, so both name one execution by one id. FILL activities trailed the stream (one listed
  0.6 s after its order read filled), so a fill read back by time is the activities merged with what the stream
  pushed in the last 10 minutes.
- **Short sales.** A FILL activity names an opening short `sell_short` (the order and the trade stream say `sell`);
  both read as a sell.
- **Coin fees** are taken in the coin bought: 0.0002 BTC bought left 0.000199499 BTC (tier 1, 0.25% taker, rounded
  up to 9 decimals). No answer carries the fee: not the fill, the order, the FILL activity, nor any CFEE activity (none
  posted). Only the trade stream's `position_qty`, the position after each execution, says what was received, so the
  adapter keeps each symbol's position (read whenever the stream connects, moved by each fill) and reports a coin buy
  as Alpaca filled it, with the difference as a `commission` in the coin: the VGP rule for a spot pair's base asset,
  by which qkt 0.64.0 and later completes the order on Alpaca's quantity and books the holding as the quantity
  received ([qkt#1515](https://github.com/elitekaycy/qkt/issues/1515); proven on paper 2026-10-28: a BTCUSD round
  trip flat at the venue, qkt's realized equal to the deals' cash). An older qkt holds more than the venue after a
  buy. A sale pays its fee in dollars and moves the position by what it sold; that dollar fee is reported nowhere per
  fill, so the account's cash moves by it beyond qkt's realized. A coin buy filled while the stream was down is read
  back from the activities at the quantity bought, with no fee. Coin pairs are listed only when
  `GATEWAY_SETTING_ASSET_CLASSES` names `crypto`.
- **Fractional shares.** On a `fractionable` share Alpaca took 0.5 share on market, limit, stop and extended-hours
  limit DAY orders, and refused a fraction on GTC (`fractional orders must be DAY orders`), sold short (`fractional
  orders cannot be sold short`), worth under $1 (`cost basis must be >= minimal amount of order 1`), on a share it
  cannot split (`asset "AV" is not fractionable`) and finer than 9 decimals (`qty must be > 1e-9`).
- **Coin minimums.** A coin order is refused below $10 of value (`cost basis must be >= minimal amount of order 10`),
  whatever `min_order_size` says; crypto takes only `gtc` and `ioc`.
- **Sessions.** An `extended_hours` order must be a limit (a market one is refused); DAY and GTC were accepted. Overnight
  (20:00-04:00 New York) a 1-share AAPL limit through the ask filled within 2 s at 347.49. The IEX stream sends
  nothing after 20:00; with `extended_hours` on, shares are also quoted from Alpaca's free overnight stream
  (`/v1beta1/overnight`, same protocol), which quotes sparsely: a share's first quote after it is first
  subscribed took more than 10 s, so a one-shot `qkt bot` order can time out waiting for it the first time. A DAY order sent after the close without
  `extended_hours` rests `accepted` until the next open.
- **Errors.** 403 `40310000` is a refusal (buying power, insufficient coin balance, coin minimum); 422 `42210000`
  (unknown symbol, sub-penny price, a time in force the asset cannot take) and 422 `40010001` (malformed or
  duplicate order) are refusals; 401, 429 and 5xx are unavailable.
- **Account.** USD; `multiplier` 4 on a $100,000 paper account (buying power $400,000). It is named by its
  `account_number`. The paper account sent no `pattern_day_trader` or `daytrade_count` field, so the adapter reports
  nothing about them.
- **Streams.** The trade stream (`/stream`) logs in with `auth` and listens to `trade_updates`; it sends binary
  frames. The data streams greet with `connected`, take `auth`, then `subscribe`; a wrong key is
  `unauthorized` on the trade stream and `402 auth failed` on a data stream. All of them answer WebSocket pings.
  A key holds one connection to each data stream: a second (another gateway, or the contract suite, on the same
  key) is refused with `406 connection limit exceeded`, so run one gateway per key.

## Contract suite

`AlpacaContractTest` places one AAPL share at a time with `extended_hours` on, so it runs whenever Alpaca trades
shares (Sunday 20:00 to Friday 20:00 New York, holidays aside); bars and quotes are checked on BTCUSD, so the suite enables `crypto` itself. It reads
`ALPACA_CLIENT_ID` and `ALPACA_CLIENT_SECRET` (a paper key) and skips without them. No gateway may be running on the
same key meanwhile, or the suite's quote check is refused a data connection (`406`):

```bash
ALPACA_CLIENT_ID=… ALPACA_CLIENT_SECRET=… ./gradlew :adapter-alpaca:test --tests '*AlpacaContractTest*'
```

Passed 8 of 8 on paper on 2026-10-28 at 00:20, 00:44, 02:09, 02:18 and 02:28 UTC (the overnight session).
