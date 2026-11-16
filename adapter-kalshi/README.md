# Kalshi adapter

> Venue: Kalshi demo and production: binary event contracts of every category · Status: New

Trades one shard of one Kalshi account, in the series it is set to list. Every market of every open event of those
series, whatever their category (sports, elections, politics, financials, economics, crypto, commodities, weather,
mentions, entertainment, ...), is listed as an `event` instrument. A quantity is signed YES: a buy buys YES, a sell sells YES (buys NO). Combo markets, stops and
day orders are not supported.

## Setup

1. **Create an API key** on [demo.kalshi.co](https://demo.kalshi.co) (or kalshi.com for real money), under
   Profile, API keys. Kalshi issues an Ed25519 key (its default since 2026-10-01) or an RSA key; either works. Keep
   the private key file: Kalshi shows it once.
2. **Start it:**

   ```bash
   docker run -d -p 127.0.0.1:8447:8443 -v gw-kalshi:/data \
     -e GATEWAY_ADAPTER=kalshi -e GATEWAY_SETTING_ENVIRONMENT=demo \
     -e GATEWAY_SETTING_SERIES=KXINXU,KXBTCD,KXGOLD15M \
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
| `GATEWAY_SETTING_SERIES` | required | The Kalshi series the gateway lists and trades, by ticker, separated by commas (`KXINXU,KXBTCD,KXGOLD15M`). Only these are read, each in under a second, every minute. `*` lists the whole catalogue instead: see below before choosing it. Never defaulted |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | required | The API key id and its PEM private key |

**Choosing the series.** A series ticker is the first part of a market's ticker (`KXINXU-26NOV16H1600-T6999.99` is
of series `KXINXU`) and is on the market's page on kalshi.com. A market of a series not named is not listed, quoted
or traded (an order for it is refused: `kalshi lists no market …`), though `/v1/instruments/{code}` still answers
it by its ticker, as it answers a settled market, and `/v1/instruments?series=…` or `?event=…` reads any series'
open events or any event from Kalshi directly; each such market outside the named series is `trading: disabled`.
That is how a client browses the catalogue before choosing what to name here. A named series Kalshi does not have
fails the listing naming it.

**`GATEWAY_SETTING_SERIES=*`** reads everything Kalshi lists. Measured 2026-11-16: production 18,912 open events
and 167,165 markets in 95 pages and 229 s; the demo 19,830 events and 226,538 markets in 100 pages and 307 s; and
the series list is one 20 MB answer on top. The gateway serves no instrument until that first read ends, so with
`*` it starts minutes late, reads the catalogue again only once an hour, and a market Kalshi lists in between is
unknown until the next read. Name the series unless the whole catalogue is what the strategy trades.

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
- **Listing.** Series → events → markets. One series' open events with their markets are one call with
  `series_ticker` (0.8 s for `KXDEMOFUTHOURLY`; paged by 200 events), and its terms one call by its ticker, so a
  listing of named series is read every minute (and on a miss, at most every 10 s). The whole catalogue grew from
  6,800 open events and 17,890 markets (2026-11-05) to 18,912 events and 167,165 markets (2026-11-16) on production,
  95 pages of 200 events at 2 to 3 s each, beside a 20 MB list of every series (15,835, read every 6 hours); it is
  read only for `series=*`, once an hour. An unknown `series_ticker` answers no events, not an error; an unknown
  series by its ticker is `404 not_found`.
- **Timeouts.** No call waits without a limit: a REST call fails after 10 s (60 s for the public market, whose
  catalogue pages have taken up to a minute), the socket's handshake after 10 s, and a socket whose ping goes
  unanswered for 20 s is dropped and connected again. A venue that stalls is reported unavailable, never waited on.
- **Instruments.** Code = market ticker; contract 1 USD; tick = the finest step of `price_ranges` (0.01, or
  0.001 at the tails on tapered markets); quantity step and minimum 0.01 (the demo took a 1.5-contract order on a
  whole-cent market; Kalshi no longer reports which markets trade fractions); expiry = `expected_expiration_time`.
  `strike_type` `functional`/`structured`/`custom` → `custom`, absent → `none`; `greater*` keep only the floor and
  `less*` only the cap (Kalshi sends a floor of 0 on `less_or_equal`). An exact-count bucket is `between` with
  floor equal to cap (`KXDSENATESEATS-27-45`). A `between` whose floor is above its cap is left out and logged (the
  demo's `KXGDPYEAR-27-B0.8` lists 0.6 to 0.000001; production listed none). Series frequencies outside the documented words (`often`, `test`,
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
  unknown label is looked for in the account's order list. `labels.csv` is the only label → id record: if the state
  directory is lost, an order is still found through that list (which carries each order's `client_order_id`), at
  the cost of one listing read per lookup until the label is learnt again. A market order is an IOC limit at the grid's worst price
  (0.99 or 0.999 to buy, 0.01 or 0.001 to sell). `reduce_only` is accepted on IOC only (`400 invalid_order:
  reduce_only can only be used with IoC orders`), so a resting reduce-only order is sent for at most the position.
  An amend keeps the order id and the label; the order's `initial_count` stays the original size. The order read
  by its id shows an amend or a cancel late (measured 2026-11-16: the old price 0.6 s after the amend was taken,
  the new one at 1 s; still `resting` right after a cancel), so a change or a cancel returns once the order shows
  it, read every 200 ms for up to 5 s, and is reported unavailable if it never does. An amend that changes nothing
  is refused (`400 AMEND_ORDER_NO_OP`), so a change to what the order already is sends nothing and succeeds. Recorded
  refusals: `invalid_price` (off the grid), `insufficient_balance`, `not_found`. Kalshi's order does not echo the
  time in force or type sent, so they are reported as sent while the gateway runs, and as GTC limit after a restart.
- **Settlements.** Read from the account's settlement history (`/portfolio/settlements`): 1 for `yes`, 0 for `no`,
  a scalar's `value` in cents, at `settled_time`. Kalshi's `fee_cost` there is the trading fees the market's fills
  already paid (recorded: 0.0054 and 0.0007, the fills' fees), so a settlement carries no cost of its own. Recorded
  settlements came 6 and 93 minutes after close.
- **Account.** Balance is per shard (`balance_breakdown`); positions are signed YES (negative = NO) with their cost.
  Margin used is Kalshi's portfolio value.
- **Liquidity.** On 2026-11-05 the demo was thin (most markets one side or none). On 2026-11-16 bots make and take
  it: 1,000 public trades in 21 s over 520 markets, and 515 series with a market quoted on both sides. Kalshi
  answers a candle only for a minute in which the market traded (30 of the last 30 minutes on a market trading
  then, none on one last traded three hours before), so a quiet market has no bars. The demo's hourly series
  `KXDEMOFUTHOURLY` is bid only (0.01, no ask): its candles close with an empty ask and carry no bar, and a
  position sold into that bid cannot be bought back before the hour settles it.

## Contract suite

`KalshiContractTest` runs against the demo with `KALSHI_CLIENT_ID` (key id) and `KALSHI_CLIENT_SECRET` (the PEM),
on the open `KXDEMOFUTHOURLY` market, and skips without them.
