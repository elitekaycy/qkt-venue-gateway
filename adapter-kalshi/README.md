# Kalshi adapter

> Venue: Kalshi demo and production: binary event contracts of every category · Status: New

Trades one shard of one Kalshi account. Every market of every open event, in every category (sports, elections,
politics, financials, economics, crypto, commodities, weather, mentions, entertainment, ...), is listed as an
`event` instrument. A quantity is signed YES: a buy buys YES, a sell sells YES (buys NO). Combo markets, stops and
day orders are not supported.

## Setup

1. **Create an API key** on [demo.kalshi.co](https://demo.kalshi.co) (or kalshi.com for real money), under
   Profile, API keys. Kalshi issues an Ed25519 key (its default since 2026-10-01) or an RSA key; either works. Keep
   the private key file: Kalshi shows it once.
2. **Start it:**

   ```bash
   docker run -d -p 127.0.0.1:8447:8443 -v gw-kalshi:/data \
     -e GATEWAY_ADAPTER=kalshi -e GATEWAY_SETTING_ENVIRONMENT=demo \
     -e GATEWAY_LOGIN=<api key id> -e GATEWAY_SECRET="$(cat kalshi-key.pem)" \
     -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
     qkt-venue-gateway
   ```

   `GATEWAY_SECRET` is the PEM itself; a PEM written on one line with `\n` escapes is read too. Health should
   show `"adapter":"kalshi"`, the key id as `account_login` and `"venue_connected":true`.

## Connect qkt

```yaml
brokers:
  kalshi:
    type: gateway
    gateway_url: http://127.0.0.1:8447
    api_key: env:KALSHI_TRADER_TOKEN
    expected_adapter: kalshi
    expected_trade_mode: demo             # real on production
```

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_SETTING_ENVIRONMENT` | required | `demo`, or `production` for real money. Never defaulted |
| `GATEWAY_SETTING_SHARD` | `0` | The exchange index whose capital the gateway trades (0 default, 1 combos, 2 crypto and commodities, 3 tennis, baseball and basketball, as production names them). Kalshi keeps a balance per shard, so markets of any other shard are listed `disabled`; run one gateway per shard |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | required | The API key id and its PEM private key |

## Capabilities

Bars (from Kalshi's 1 m, 1 h and 1 d candlesticks), quotes, settlements, the tape, depth and open interest. A bar's
price is the YES mid from the candle's bid and ask, never the last trade, so bars agree with quotes; a candle with
an empty side (Kalshi's 0 bid or 1 ask) has no bar. The tape is Kalshi's own trade history; depth and open interest
are recorded as read (Kalshi publishes no book history and only the present open interest), at the gateway's
clock, since Kalshi stamps neither. Not `mark_prices`: Kalshi publishes an index for only some series.

## Venue behaviour

Measured on the demo environment and production's public market, 2026-11-05/06, every answer recorded in
`src/test/resources/fixtures` (`public/` production, `demo/` and `private/` the demo account, `socket/` its
WebSocket), the account's user id replaced by zeros.

- **Signing.** `KALSHI-ACCESS-KEY` (key id), `-TIMESTAMP` (ms) and `-SIGNATURE`: base64 of the key's signature of
  `timestamp + METHOD + path`, the path from `/trade-api/` without its query. Ed25519 signs the text; RSA signs
  RSA-PSS SHA-256 with a 32-byte salt. The WebSocket signs its handshake the same way (`GET /trade-api/ws/v2`).
- **Listing.** Series (15,835 on production, one 20 MB answer, read every 6 hours) → events → markets. Open events
  with their markets are 6,800 events and 17,890 markets on production, 34+ pages of 200; a page took from under a
  second to over a minute, so listing calls wait up to 60 s and the listing is read every 5 minutes (and on a miss,
  at most every minute). A series an event names that the series read did not hold is read by its ticker.
- **Instruments.** Code = market ticker; contract 1 USD; tick = the finest step of `price_ranges` (0.01, or
  0.001 at the tails on tapered markets); quantity step and minimum 0.01 (the demo took a 1.5-contract order on a
  whole-cent market; Kalshi no longer reports which markets trade fractions); expiry = `expected_expiration_time`.
  `strike_type` `functional`/`structured`/`custom` → `custom`, absent → `none`; `greater*` keep only the floor and
  `less*` only the cap (Kalshi sends a floor of 0 on `less_or_equal`). An exact-count bucket is `between` with
  floor equal to cap (`KXDSENATESEATS-27-45`). Series frequencies outside the documented words (`often`, `test`,
  `one_time` on the demo) read as `custom`. `active` trades in full, `closed` is `close_only`, every other status
  (`initialized`, `inactive` = paused, `determined`, `disputed`, `amended`, `finalized`) and combo markets (`KXMVE…`,
  request for quote only; `/events` lists none) are `disabled`.
- **Fees.** `quadratic`: taker 0.07 × multiplier × contracts × p × (1 − p); `quadratic_with_maker_fees` adds maker
  0.0175 × multiplier. An event's `fee_type_override` wins. Charges are rounded up to 0.0001, not a cent (recorded:
  2 contracts at 0.04 paid 0.0054; 1 at 0.10 paid 0.0063; 1 at 0.01 paid 0.0007). Each fill carries its fee as an
  `exchange_fee`.
- **Book.** Kalshi lists bids only: a NO bid at p is a YES ask at 1 − p. An empty YES bid is reported as 0 and an
  empty ask as 1, each with size 0; both are absent sides here.
- **Orders.** V2 order entry (`POST /portfolio/events/orders`, `bid`/`ask` on the YES book). The client's id is
  `client_order_id`; a second order with it is `409 order_already_exists`, answered with the order holding it.
  Kalshi finds an order only by its id, so label → id is kept in `labels.csv` in the state directory, and an
  unknown label is looked for in the account's order list. A market order is an IOC limit at the grid's worst price
  (0.99 or 0.999 to buy, 0.01 or 0.001 to sell). `reduce_only` is accepted on IOC only (`400 invalid_order:
  reduce_only can only be used with IoC orders`), so a resting reduce-only order is sent for at most the position.
  An amend keeps the order id and the label; the order's `initial_count` stays the original size. Recorded
  refusals: `invalid_price` (off the grid), `insufficient_balance`, `not_found`. Kalshi's order does not echo the
  time in force or type sent, so they are reported as sent while the gateway runs, and as GTC limit after a restart.
- **Account.** Balance is per shard (`balance_breakdown`); positions are signed YES (negative = NO) with their cost.
  Margin used is Kalshi's portfolio value.
- **Liquidity.** The demo is thin: most markets have one side or none, so the demo's candles carry no YES mid and
  bars are empty there. The demo's hourly series `KXDEMOFUTHOURLY` keeps a 0.01 bid.

## Contract suite

`KalshiContractTest` runs against the demo with `KALSHI_CLIENT_ID` (key id) and `KALSHI_CLIENT_SECRET` (the PEM),
on the open `KXDEMOFUTHOURLY` market, and skips without them.
