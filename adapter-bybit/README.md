# Bybit adapter

Trades one category of one Bybit unified account: `linear` (USDT- or USDC-settled perpetuals and dated
futures, sizes in the base coin) or `spot` (pairs quoted in USDT or USDC). One gateway is one account and one
category; run two gateways for both. Inverse contracts and options are not supported.

## Setup

1. **Create an API key** on [testnet.bybit.com](https://testnet.bybit.com) (or bybit.com for real money) on a
   unified trading account, with contract and spot **trade** permissions only. Never grant withdrawal.
2. **Start it:**

   ```bash
   docker run -d -p 127.0.0.1:8445:8443 -v gw-bybit:/data \
     -e GATEWAY_ADAPTER=bybit -e GATEWAY_SETTING_ENVIRONMENT=testnet -e GATEWAY_SETTING_CATEGORY=linear \
     -e GATEWAY_LOGIN=<api key> -e GATEWAY_SECRET=<api secret> \
     -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
     qkt-venue-gateway
   curl -H "Authorization: Bearer <trader token>" http://127.0.0.1:8445/v1/health
   ```

   Health should show `"adapter":"bybit"`, your API key as `account_login`, and `"venue_connected":true`.

## Connect qkt

Name the broker after the category, so symbols keep the names qkt's Bybit connector used:

```yaml
brokers:
  bybit_linear:
    type: gateway
    gateway_url: http://127.0.0.1:8445
    api_key: env:BYBIT_TRADER_TOKEN
    expected_adapter: bybit
    expected_account_login: "<api key>"
    expected_trade_mode: demo             # real on mainnet
```

```sql
SYMBOLS
  perp = BYBIT_LINEAR:BTCUSDT EVERY 1m
  dated = BYBIT_LINEAR:BTCUSDT_30OCT26 EVERY 1m
```

qkt writes `-` in venue codes as `_`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_SETTING_ENVIRONMENT` | required | `testnet`, or `mainnet` for real money. Never defaulted |
| `GATEWAY_SETTING_CATEGORY` | required | `linear` or `spot`. Never defaulted |
| `GATEWAY_SETTING_CURRENCY` | `USDT` | The coin a linear contract settles in, or a spot pair is quoted in: `USDT` or `USDC` |
| `GATEWAY_SETTING_STOP_TRIGGER` | `last_price` | Price a linear stop fires on: `last_price`, `mark_price` or `index_price` (spot: `last_price` only) |
| `GATEWAY_SETTING_TRADES_RETENTION_DAYS` | `0` | UTC days of the recorded tape and liquidations kept besides today; `0` keeps everything |
| `GATEWAY_SETTING_DEPTH_RETENTION_DAYS` | `0` | The same for recorded depth |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | required | The API key and its secret |

## Capabilities

| Category | Declared |
|---|---|
| `linear` | bars, quotes, funding, funding_rates, mark_prices, open_interest, trades, liquidations, depth |
| `spot` | bars, quotes, trades, depth |

Not declared, answered `501`: settlements (no Bybit delivery record has been recorded to map from yet) and
option marks (no options).

## Venue behaviour

Measured on testnet 2026-10-05; recorded responses are in `src/test/resources/fixtures`.

- **Order ids.** The client order id travels as `orderLinkId`. Bybit refuses one longer than 45 characters
  (`10001 order link id is longer than 45`), so the adapter refuses it before sending. An order is read back by
  `orderLinkId` from `/v5/order/realtime`, then `/v5/order/history`; an unknown id answers an empty list.
  Bybit answers a placement with its ids only, so the adapter reads the order back before answering.
- **Time in force.** `GTC`, `IOC`, `FOK` (and `PostOnly`, read as GTC). Bybit has no day orders: `day` is
  refused. An `orderLinkId` is never taken twice, even after its order ended (`110072 OrderLinkedID is duplicate`).
- **Market orders never rest.** Bybit fills a market order immediate-or-cancel within its price protection
  (about 1% from the mark) and cancels the rest itself: a 0.4 BTC buy on testnet BTCUSDT on 2026-10-06 filled
  0.158 in 32 executions and ended `Cancelled` (`rejectReason` `EC_NoImmediateQtyToFill`, `timeInForce` `IOC`,
  `price` the protection price; `market-overrun` fixtures). So the adapter needs no remainder handling; the order
  is reported `cancelled` with what filled. A fired stop-market is the same order type and behaves alike.
- **Stops** are conditional orders: on linear `triggerPrice`, `triggerDirection` (1 a buy stop fires as the
  price rises, 2 a sell stop as it falls) and `triggerBy`; on spot `orderFilter=StopOrder`. Their status runs
  `Untriggered`, `Triggered`, then the order's own; `Deactivated` is a stop cancelled before firing. A fired stop
  keeps its `orderLinkId`, `orderType` `Market`, `stopOrderType` `Stop` and `triggerPrice` (`stop-fired` fixtures,
  ETHUSDT 2026-10-06), and its execution says `createType` `CreateByStopOrder`. A stop already past its trigger is
  refused (`110092 expect Rising, but trigger_price ... <= current`).
- **Statuses.** `New`, `PartiallyFilled`, `Untriggered`, `Triggered` work; `Filled` fills; `Cancelled`,
  `PartiallyFilledCanceled` (spot) and `Deactivated` cancel, with what filled (`cumExecQty`); `Rejected` rejects.
  An unknown status fails by name.
- **Executions.** `/v5/execution/list` answers at most seven days a call (longer ranges are read seven days at a
  time) and pages by `nextPageCursor`, sent back as Bybit wrote it. Only `Trade` executions (or one naming no
  `execType`) are fills; `execFee` is the commission, positive charged, negative a rebate. `BustTrade`,
  `AdlTrade`, `Delivery`, `Settle` are Bybit moving the position itself: not fills; the host's position watch
  sees them.
- **Funding** is a `Funding` execution: `execFee` is the charge, positive when the account paid (Bybit's
  transaction log documents its `funding` as the opposite sign), `execQty` the position on `side`, `orderLinkId`
  empty, stamped at the funding time. Recorded 2026-10-06 08:00 UTC: a 0.001 BTCUSDT long paid 0.0085907 USDT at
  rate 0.0001 (`executions-funding.json`); `execution.linear` pushed the same record 0.2 s later
  (`ws-execution-funding.json`), and the host's reconcile reads it back with `execType=Funding`. The all-in-one
  `execution` topic cannot be subscribed beside `execution.linear` (Bybit: subscription conflict).
- **Position mode.** Read, never assumed: a contract in one-way mode has one slot (`positionIdx` 0), one in hedge
  mode two (1 long, 2 short) (`position-slots-*.json`; switched on ETHUSDT and back, 2026-10-05). An order with
  the wrong index is refused `position idx not match position mode`. Orders go with the index their contract's
  mode needs; the account reports `hedging` when a held position is per side or the coin's BTC perpetual is in
  hedge mode. Closing one ticket is not supported by the host, so a hedge-mode account can trade but not be
  flattened by `/v1/positions/close`.
- **Account.** `currency` is the coin's own wallet balance and equity; margin used and available are the
  unified account's pooled figures, which Bybit values in USD.
- **Fees.** Testnet charged 0.04% taker on linear (`feeRate` 0.0004, `feeCurrency` USDT).
- **Spot positions.** A spot account holds coins: each coin with a listed pair against the quote coin is a
  netted position of that pair. Bybit keeps no entry price of a coin, so a holding's `avg_price` is the pair's
  last price when read. A buy's fee is taken in the coin bought, so a holding grows by less than the fill.
- **Errors.** Unavailable (the host resolves the order by its label): HTTP 403/429/5xx, `10000` timeout,
  `10002` outside the receive window, `10006`/`10018`/`10429` rate limits, `10016`/`10019` restarts, `110079`
  order still processing, `170007`/`170032`/`170234` spot backend errors. Every other code is Bybit's answer:
  `10001` covers malformed requests (`Qty invalid`, `params error: symbol invalid`), `110007` too little margin,
  `110001` an order that does not exist or already ended.
- **Listing.** Instruments of the category with status `Trading` settling in (spot: quoted in) the currency.
  An order on any other code is refused before it reaches Bybit.
- **Quotes** come from the public socket: `tickers.<s>` (a linear ticker's first push is whole, later pushes hold
  only what changed, so they are merged) and `orderbook.1.<s>` (whole every push; a spot ticker carries no bid
  or ask). The public socket is reported as the quote feed. Bybit drops a socket silent for a while; the
  adapter pings every 20 s and reconnects one silent for 60 s.
- **Bars** are Bybit's klines (1, 3, 5, 15, 30, 60, 120, 240, 360, 720 minutes and a day, UTC-aligned); other
  whole-minute windows are built from the longest of those dividing them. The kline holding now is still
  forming and is never served. Klines are read 1000 a call.
- **Marks** are the closes of Bybit's mark-price and index-price klines, one per window, stamped at the
  window's last millisecond (never earlier than Bybit could have reported them).
- **Funding rates** from `/v5/market/funding/history` (every 8 h on BTCUSDT): the rate, no price.
- **Open interest** from `/v5/market/open-interest` at 5 minutes, in the base coin. Bybit stamps a figure with
  the start of its five minutes and publishes it within about a minute (measured), so each is served at its
  five minutes' end. A window reaching now also holds the ticker's present figure.
- **Tape and liquidations are recorded.** Bybit's REST answers only a contract's latest 1000 trades (60 of a
  spot pair) and no liquidations, so every code asked for is taped from then on from `publicTrade.<s>` and
  `allLiquidation.<s>` (Bybit removed `liquidation.<s>`: subscribing to it is refused `handler not found`, and
  one refused topic fails the whole request), across restarts (`taped.txt`). A read of the tape first records
  Bybit's latest trades, so a code read for the first time is served its recent past. A liquidation's `S` is the
  liquidated position's side (`Buy` a long), served as the side of the order that closed it. Liquidations have
  no id; theirs is time, price and size. Recorded under `trades/` and `liquidations/` in the state volume.
- **Depth is recorded** like Deribit's: a read reaching now takes `/v5/market/orderbook` (ten levels a side, at
  Bybit's `ts`) and records it under `depth/`.

## Contract suite

```bash
BYBIT_CLIENT_ID=<testnet api key> BYBIT_CLIENT_SECRET=<secret> ./gradlew :adapter-bybit:test --tests '*ContractTest'
```

It trades BTCUSDT (category `linear`) at 0.001 and skips without the key. Spot is not in the suite: the kit
checks that a fill moves the position by its quantity, and Bybit takes a spot buy's fee in the coin bought (buying
0.0001 BTC added 0.00009991 to the holding, 2026-10-06), so that check cannot hold on spot. A one-off spot run
passed the suite's seven other tests (pull request #58).
