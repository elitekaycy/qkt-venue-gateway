<h1 align="center">qkt-venue-gateway</h1>

<p align="center">
  The gateway between <a href="https://github.com/elitekaycy/qkt">qkt</a> and a trading venue: one container per account.<br/>
  Futures, perpetuals and options over one small protocol, VGP v1.
</p>

<p align="center">
  <a href="https://github.com/elitekaycy/qkt-venue-gateway/actions/workflows/check.yml"><img src="https://github.com/elitekaycy/qkt-venue-gateway/actions/workflows/check.yml/badge.svg" alt="check"></a>
  <a href="CHANGELOG.md"><img src="https://img.shields.io/github/v/tag/elitekaycy/qkt-venue-gateway?label=version&sort=semver" alt="version"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="license"></a>
</p>

---

qkt places orders and reads market data through the gateway. The gateway talks to the venue through an
**adapter**, and guarantees the same behaviour for every venue:

- **Orders are never doubled.** A client order id is placed at most once; a retried submit returns the
  same order.
- **Nothing is lost on restart.** Every order, fill and position change is journaled before it is sent.
  After any restart, qkt replays from its last sequence number.
- **Lost orders are recovered.** An order the venue has forgotten is resolved from its fills, never
  sent again.
- **A kill switch outside qkt.** A guardian token can block new exposure for the account or per symbol.

```
qkt ──────┐                ┌─────────── qkt-venue-gateway (one per account) ───────────┐
          ├── VGP v1 ─────▶│ auth · kill switch · idempotency · journal · reconciler   │──▶ venue
guardian ─┘  HTTP + WS     │                      adapter                              │
                           └────────────────────────────────────────────────────────────┘
```

## Quickstart

A paper account on Deribit's live prices. No venue account needed, only Docker.

```bash
git clone https://github.com/elitekaycy/qkt-venue-gateway && cd qkt-venue-gateway
cp .env.example .env          # set PAPER_TRADER_TOKEN and PAPER_GUARDIAN_TOKEN
docker compose up -d          # serves on 127.0.0.1:8443

source .env
curl -H "Authorization: Bearer $PAPER_TRADER_TOKEN" http://127.0.0.1:8443/v1/health
```

## Connect qkt

Add one `type: gateway` broker per gateway to qkt's config:

```yaml
brokers:
  paper:
    type: gateway
    gateway_url: http://127.0.0.1:8443
    api_key: env:PAPER_TRADER_TOKEN
    expected_adapter: paper
    expected_account_login: paper
    expected_trade_mode: demo
```

qkt refuses to start if the gateway's adapter, account or trade mode don't match the entry.

## Adapters

| Adapter | Venue | Status |
|---|---|---|
| `paper` | Simulated account on Deribit's public prices | Stable. [Settings](adapter-paper/README.md) |
| `deribit` | Deribit testnet and mainnet | Stable. [Setup](adapter-deribit/README.md) |

Want another venue? See [writing an adapter](docs/writing-an-adapter.md).

## Documentation

| | |
|---|---|
| [Running](docs/running.md) | Configuration, Docker, connecting qkt and guardrails, backups, upgrades, troubleshooting |
| [Writing an adapter](docs/writing-an-adapter.md) | Adding a venue, step by step |
| [Adapter rules](docs/adapters.md) | What every adapter must do, and what CI checks |
| [Wire protocol](docs/vgp-v1-wire.md) | Every route, event and error qkt relies on |
| [Design](docs/design.md) | How the gateway works and why |

## Development

Requires JDK 21.

```bash
./gradlew build        # compile, lint and test every module
./gradlew :app:run     # run locally (set GATEWAY_TRADER_TOKEN and GATEWAY_GUARDIAN_TOKEN)
```

Pull requests target `dev`. Read [CONTRIBUTING.md](CONTRIBUTING.md) first. Report security issues
privately as described in [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE)
