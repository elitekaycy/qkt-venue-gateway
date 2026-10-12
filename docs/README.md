# Documentation

| Page | Read it to |
|---|---|
| [Running](running.md) | Configure, run and operate a gateway; connect qkt and guardrails; troubleshoot |
| [Paper adapter](../adapter-paper/README.md) | Forward-test on Deribit prices without an account |
| [Deribit adapter](../adapter-deribit/README.md) | Trade a Deribit account, testnet or mainnet |
| [Bybit adapter](../adapter-bybit/README.md) | Trade Bybit linear contracts or spot, testnet or mainnet |
| [Writing an adapter](writing-an-adapter.md) | Add a venue, step by step |
| [Adapter rules](adapters.md) | See what every adapter must do and what CI checks |
| [Wire protocol](vgp-v1-wire.md) | See every route, event and error qkt relies on |
| [Design](design.md) | Understand how the gateway works and why |
| [Contributing](../CONTRIBUTING.md) | Branches, commits, checks and releases |

## Adapters

| Adapter | Venue | Status |
|---|---|---|
| `paper` | Simulated account on Deribit's public prices | Stable |
| `deribit` | Deribit testnet and mainnet | Stable |
| `bybit` | Bybit testnet and mainnet: USDT/USDC linear contracts, or spot | New |
