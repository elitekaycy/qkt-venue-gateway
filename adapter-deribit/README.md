# adapter-deribit

One Deribit account, USDC-linear contracts (perpetuals, futures, options; amounts in coins).
Coin-margined contracts are refused. Passes `AdapterContractTest` on testnet.

## Settings and credentials

| Variable | Default | |
|---|---|---|
| `GATEWAY_ADAPTER` | | `deribit` |
| `GATEWAY_SETTING_ENVIRONMENT` | required | `testnet`, or `mainnet` for real money; never defaulted |
| `GATEWAY_SETTING_CURRENCY` | `USDC` | the only one supported |
| `GATEWAY_SETTING_STOP_TRIGGER` | `last_price` | or `mark_price`, `index_price`: the price a stop fires on |
| `GATEWAY_LOGIN`, `GATEWAY_SECRET` | required | an API key's client id and secret, read and trade scopes only |

## Venue facts (measured on testnet, fixtures in `src/test/resources/fixtures`)

- Our client order id travels as Deribit's `label`; labels are not unique at Deribit, so only the host
  decides to resend.
- A closed order is answered by label for under an hour (found 27 min after closing, gone after about
  an hour); trades stay, so the host resolves a lost order from its fills.
- A future's position `size` is dollars; its quantity is `size_currency`.
- A fired stop is a new Deribit order under the same label. A stop already crossed is refused; market
  and stop orders take only GTC or DAY.
- Kline answers are cut silently at 5001 rows, so bars are fetched in spans.
- Settlement history is not served yet (refused as unavailable).

The full call list is in [docs/design.md](../docs/design.md) §10.

## Contract suite

`DeribitContractTest` runs with `DERIBIT_CLIENT_ID` and `DERIBIT_CLIENT_SECRET` set to a **testnet**
key, and skips without them locally. In the `adapter` workflow it must run.
