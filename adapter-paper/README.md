# Paper adapter

A simulated account on Deribit's live public prices. Use it to forward-test strategies through the
real gateway, with no venue account or API key.

- Market orders fill at the best bid or ask. Limits fill when the market reaches them.
- Positions persist across restarts. Expired contracts settle at Deribit's delivery price.
- Held perpetuals pay funding at Deribit's published hourly rates: `quantity × index × interest_1h` per
  hour, a long paying a positive rate and a short paid it, rounded to 8 decimals. A position pays each
  hour whose rate is published while it is held, the hour it opened in full; Deribit accrues by the
  millisecond, so paper funding is close to Deribit's, not equal. Funding rates are served from Deribit.
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

In Compose, `PAPER_STARTING_BALANCE` in `.env` sets the opening balance.

## Layout exception

Unlike other adapters, it has no `client/` package or recorded fixtures. It reads prices through the
Deribit adapter's public client, whose fixtures cover them. Its contract suite runs offline against a
scripted market.
