# Going live

1. Run the same setup on the venue's test environment, with qkt trading through it end to end.
2. Create a live API key with read and trade permissions only. Never grant withdrawal.
3. Run a separate gateway for it, with its own service, port, volume and tokens.
4. Set `expected_trade_mode: real` in qkt's broker entry.
5. Keep the port on `127.0.0.1` or a private network, and put TLS on your reverse proxy.

## Venue-specific steps

| Venue | Test environment | Live |
|---|---|---|
| Deribit | `GATEWAY_SETTING_ENVIRONMENT=testnet` | `mainnet`. See [Deribit](../adapters/deribit.md) |
| Bybit | `GATEWAY_SETTING_ENVIRONMENT=testnet` | `mainnet`. See [Bybit](../adapters/bybit.md) |

The environment setting is required and never defaulted, so a gateway cannot reach real money by
omission.

## Before you trust it

- Check `GET /v1/health`: `adapter`, `account_login` and `venue_connected` should be what you expect.
- Put the [kill switch](../connect/guardrails.md) in reach of something that is not qkt.
- Read [Ports and security](../reference/ports-and-security.md).
- Alert if `venue_connected` stays `false` for more than two minutes ([Health and logs](../operations/health-and-logs.md)).
