# Connect qkt

Add one `type: gateway` broker per gateway to qkt's config. The broker's name becomes the symbol prefix
(`PAPER:BTC_USDC_PERPETUAL`).

```yaml
brokers:
  paper:
    type: gateway
    gateway_url: http://127.0.0.1:8443
    api_key: env:PAPER_TRADER_TOKEN       # the gateway's GATEWAY_TRADER_TOKEN
    expected_adapter: paper
    expected_account_login: paper
    expected_trade_mode: demo             # real for a mainnet account
```

qkt refuses to start if the gateway's adapter, account or trade mode don't match the entry. That is the
check that stops a strategy meant for a test account from reaching a live one.

## Per venue

| Venue | `expected_adapter` | `expected_account_login` | Broker name |
|---|---|---|---|
| Paper | `paper` | `paper` | `paper` |
| Deribit | `deribit` | your API key's client id | see [Deribit](../adapters/deribit.md#connect-qkt) |
| Bybit | `bybit` | your API key | name it after the category, e.g. `bybit_linear`: see [Bybit](../adapters/bybit.md#connect-qkt) |

## Where the gateway runs

- **qkt on the same host:** `http://127.0.0.1:<published port>`.
- **qkt in Docker:** put both on one network and use the service name: `http://gateway-paper:8443`.
  If qkt runs in another Compose project, create the network once (`docker network create qkt`) and
  attach both projects to it as `external: true`.

## A first order

Deploy a one-rule strategy such as `WHEN POSITION.btc = 0 AND TRADES.today = 0 THEN BUY btc SIZING 0.001`
on `btc = PAPER:BTC_USDC_PERPETUAL EVERY 1s`, then read `GET /v1/positions` and
`GET /v1/deals?from=0&to=<now in ms>` (both bounds are required). The [Quickstart](../get-started/quickstart.md)
has the complete file.
