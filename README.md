# qkt-venue-gateway

A gateway between qkt and a trading venue, speaking VGP v1 (`docs/vgp-v1-wire.md`). One process serves
one account: idempotent orders, an event journal qkt can replay after any restart, a kill switch
guardrails can flip, live quotes and closed bars. Venues plug in as adapters; the first are a paper
adapter on Deribit's public data and a Deribit adapter.

```
./gradlew build
./gradlew :app:run --args="config.yaml"
```

A Deribit testnet account (secrets are references, never inline; the login is the API key's client id,
which qkt checks as `expected_account_login`; the secret never leaves the gateway):

```yaml
listen: 127.0.0.1:8443
state_dir: /var/lib/qkt-venue-gateway/deribit
tokens: { trader: env:GATEWAY_TRADER_TOKEN, guardian: env:GATEWAY_GUARDIAN_TOKEN }
adapter:
  type: deribit
  credentials: { login: env:DERIBIT_CLIENT_ID, secret: env:DERIBIT_CLIENT_SECRET }
  settings: { environment: testnet }        # mainnet is real money; never defaulted
```

## Writing an adapter

One venue is one module, `adapter-<venue>`, depending only on `adapter-api`:

- a `client` package that speaks the venue's protocol in the venue's own words and knows nothing of VGP;
- a mapping, the only place venue words become `adapter-api` types;
- the adapter (`VenueAdapter`) and its factory (`VenueAdapterFactory`, one line in
  `META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory`);
- tests: fixtures recorded from the venue, and one class extending `AdapterContractTest`
  (`adapter-testkit`) run against the venue's test environment.

Build the jar, drop it in `plugins_dir`, set `adapter.type`. `adapter-deribit` is the worked example.
