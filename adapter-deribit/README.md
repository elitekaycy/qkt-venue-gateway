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
- **Settlement history** isn't served yet.

The full list of API calls is in [design.md](../docs/design.md) §10.

## Contract suite

Runs against testnet when a testnet key is set, and is skipped without one:

```bash
DERIBIT_CLIENT_ID=… DERIBIT_CLIENT_SECRET=… ./gradlew :adapter-deribit:test --tests '*ContractTest' --rerun
```

It places, fills and cancels real testnet orders at the smallest size.
