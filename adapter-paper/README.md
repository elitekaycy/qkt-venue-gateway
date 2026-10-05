# Paper adapter

A simulated account on Deribit's live public prices. Use it to forward-test strategies through the
real gateway, with no venue account or API key.

- Market orders fill at the best bid or ask. Limits fill when the market reaches them.
- Positions persist across restarts. Expired contracts settle at Deribit's delivery price.
- Held perpetuals pay funding at Deribit's published hourly rates: `quantity × index × interest_1h` per
  hour, a long paying a positive rate and a short paid it, rounded to 8 decimals. A position pays each
  hour whose rate is published while it is held, the hour it opened in full; Deribit accrues by the
  millisecond, so paper funding is close to Deribit's, not equal. Funding rates are served from Deribit.
- Mark and index history (`/v1/marks`) is Deribit's own, read from its trade history exactly as the Deribit
  adapter reads it (the last trade of each window; see its README): years of it from mainnet's history host.
- Open interest is Deribit's (the venue's market, not the paper account's), recorded as the Deribit adapter
  records it: Deribit publishes no history, so a read reaching the present records the ticker's
  `open_interest` and serves what was recorded (`open-interest/<code>.csv` in the state volume). A series
  starts when the gateway first read it; see the [Deribit adapter](../adapter-deribit/README.md).
- Order-book depth (`/v1/depth`) is Deribit's book, its ten best levels a side, recorded as the Deribit
  adapter records it: a read reaching the present takes `public/get_order_book` and serves what was
  recorded (`depth/<code>/<day>.csv` in the state volume), so a series starts when the gateway first read it.
- Option quotes are Deribit's tickers, each with its mark IV and forward (`option_marks`; see the Deribit
  README for why its Greeks are not carried).
- The public tape (`/v1/trades`, each print with its aggressor side) and its liquidations (`/v1/liquidations`)
  are Deribit's too, read from the same history as the Deribit adapter reads them (see its README).
- No margin: all equity is available. Trade mode is always `demo`.

## Start

It's the gateway's default adapter:

```bash
docker compose up -d     # gateway-paper on 127.0.0.1:8443
```

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_SETTING_STARTING_BALANCE` | `10000` | Opening balance |
| `GATEWAY_SETTING_CURRENCY` | `USDC` | Account currency |
| `GATEWAY_SETTING_FEE_RATE` | `0` | Fee per fill, as a fraction of notional |
| `GATEWAY_SETTING_LOGIN` | `paper` | Account login reported to qkt |
| `GATEWAY_SETTING_SETTLEMENT_CHECK_MS` | `60000` | How often expiries and funding are checked |
| `GATEWAY_SETTING_DERIBIT_URL`, `GATEWAY_SETTING_DERIBIT_WS_URL` | Deribit mainnet public API | Price source. Testnet: `https://test.deribit.com`, `wss://test.deribit.com/ws/api/v2` |
| `GATEWAY_SETTING_DERIBIT_HISTORY_URL` | `https://history.deribit.com` | Trade history marks, the tape and liquidations are read from (`/v1/marks`, `/v1/trades`, `/v1/liquidations`). Testnet: `https://test.deribit.com`, which keeps about a day of trades |

In Compose, `PAPER_STARTING_BALANCE` in `.env` sets the opening balance.

## Layout exception

Unlike other adapters, it has no `client/` package or recorded fixtures. It reads prices through the
Deribit adapter's public client, whose fixtures cover them. Its contract suite runs offline against a
scripted market.
