# adapter-paper

A venue-free account on Deribit's live public prices, for forward testing through the real gateway
path. No account or key. Fills market orders at the touch and limits when the other side reaches
them, keeps positions across restarts, and settles expiries at Deribit's delivery price. It holds no
margin: margin used is 0 and all equity is available. Trade mode is always `demo`.

## Settings

| Variable | Default | |
|---|---|---|
| `GATEWAY_ADAPTER` | `paper` | the gateway's default adapter |
| `GATEWAY_SETTING_CURRENCY` | `USDC` | |
| `GATEWAY_SETTING_STARTING_BALANCE` | `10000` | |
| `GATEWAY_SETTING_FEE_RATE` | `0` | charged on each fill's notional |
| `GATEWAY_SETTING_LOGIN` | `paper` | the account login qkt checks |
| `GATEWAY_SETTING_SETTLEMENT_CHECK_MS` | `60000` | how often expiries are settled |
| `GATEWAY_SETTING_DERIBIT_URL`, `…_DERIBIT_WS_URL` | Deribit mainnet public | testnet: `https://test.deribit.com`, `wss://test.deribit.com/ws/api/v2` |

## Exceptions to the adapter layout

It has no `client/` and no recorded fixtures of its own: it reads Deribit through
`adapter-deribit`'s public client, whose fixtures cover those answers. `PaperContractTest` runs offline
over a scripted market, so it needs no secret.
