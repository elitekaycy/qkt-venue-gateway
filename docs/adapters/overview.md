# Adapters

An adapter translates between one venue's API and the gateway's venue-neutral interface. The gateway
runs exactly one adapter, chosen by `GATEWAY_ADAPTER`.

<!-- adapters:start -->
| Adapter | `GATEWAY_ADAPTER` | Venue | Status |
|---|---|---|---|
| [Alpaca](alpaca.md) | `alpaca` | Alpaca paper and live: US shares (whole shares, regular, extended and overnight sessions) and USD coin pairs | New |
| [Bybit](bybit.md) | `bybit` | Bybit testnet and mainnet: USDT/USDC linear contracts, or spot | New |
| [Deribit](deribit.md) | `deribit` | Deribit testnet and mainnet: USDC-linear perpetuals, futures and options | Stable |
| [Paper](paper.md) | `paper` | Simulated account on Deribit's public prices; no account needed | Stable |
<!-- adapters:end -->

Each page has the venue's setup, how to connect qkt, its settings, and the venue behaviour the adapter
keeps. A venue-specific setting is passed as `GATEWAY_SETTING_<KEY>`; see
[Configuration](../reference/configuration.md).

## Adding a venue

| Page | Read it to |
|---|---|
| [Adapter rules](../adapters.md) | See what every adapter must do, and what CI checks |
| [Writing an adapter](../writing-an-adapter.md) | Add a venue, step by step |
