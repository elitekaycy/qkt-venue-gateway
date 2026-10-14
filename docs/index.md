---
title: qkt-venue-gateway
hide:
  - navigation
  - toc
---

<section class="vgw-hero" markdown="1">

<div class="vgw-hero__pills" markdown="0">
  <span class="pill pill--accent">Apache 2.0</span>
  <span class="pill">VGP v1</span>
  <span class="pill">Deribit</span>
</div>

# One small protocol between qkt and a trading venue — <em>one container per account.</em> { .vgw-hero__lede }

<p class="vgw-hero__sub" markdown="0">
qkt-venue-gateway sits between <a href="https://docs.tryqkt.com">qkt</a> and a venue. Futures,
perpetuals and options all use the same protocol, so qkt trades them the same way.
</p>

<div class="vgw-hero__ctas" markdown="0">
  <a class="vgw-btn vgw-btn--primary" href="#quickstart"><span>Run a paper gateway</span><span>&rarr;</span></a>
  <a class="vgw-btn" href="running/">Run it for real</a>
  <a class="vgw-btn" href="vgp-v1-wire/">Wire protocol</a>
</div>

</section>

<section class="vgw-section" markdown="1">
<div class="vgw-section__head" markdown="0">
  <span class="vgw-section__num">&sect; 01</span>
  <h2>What the gateway guarantees</h2>
</div>

The gateway talks to the venue through an **adapter** and gives qkt the same behaviour for every venue:

- **Orders are never doubled.** A client order id is placed at most once; a retried submit returns the same order.
- **Nothing is lost on restart.** Every order, fill and position change is journaled before it is sent. After any restart, qkt replays from its last sequence number.
- **Lost orders are recovered.** An order the venue has forgotten is resolved from its fills, never sent again.
- **A kill switch outside qkt.** A guardian token can block new exposure for the account or per symbol.

```
qkt ──────┐                ┌─────────── qkt-venue-gateway (one per account) ───────────┐
          ├── VGP v1 ─────▶│ auth · kill switch · idempotency · journal · reconciler   │──▶ venue
guardian ─┘  HTTP + WS     │                      adapter                              │
                           └────────────────────────────────────────────────────────────┘
```

</section>

<section class="vgw-section" id="quickstart" markdown="1">
<div class="vgw-section__head" markdown="0">
  <span class="vgw-section__num">&sect; 02</span>
  <h2>Quickstart</h2>
</div>

A paper account on Deribit's live prices. No venue account needed, only Docker.

```bash
git clone https://github.com/elitekaycy/qkt-venue-gateway && cd qkt-venue-gateway
cp .env.example .env          # set PAPER_TRADER_TOKEN and PAPER_GUARDIAN_TOKEN
docker compose up -d          # serves on 127.0.0.1:8443

source .env
curl -H "Authorization: Bearer $PAPER_TRADER_TOKEN" http://127.0.0.1:8443/v1/health
```

Then point qkt at it with one `type: gateway` broker:

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

</section>

<section class="vgw-section" markdown="1">
<div class="vgw-section__head" markdown="0">
  <span class="vgw-section__num">&sect; 03</span>
  <h2>Adapters</h2>
</div>

| Adapter | Venue | Status |
|---|---|---|
| [`paper`](adapters/paper.md) | Simulated account on Deribit's public prices | Stable |
| [`deribit`](adapters/deribit.md) | Deribit testnet and mainnet | Stable; settlement history not served yet |

Want another venue? See [writing an adapter](writing-an-adapter.md) and the [adapter rules](adapters.md).

</section>

<section class="vgw-section" markdown="1">
<div class="vgw-section__head" markdown="0">
  <span class="vgw-section__num">&sect; 04</span>
  <h2>Where to go next</h2>
</div>

| | |
|---|---|
| [Run it](running.md) | Configuration, Docker, connecting qkt and guardrails, backups, upgrades, troubleshooting |
| [Wire protocol](vgp-v1-wire.md) | Every route, event and error qkt relies on |
| [Design](design.md) | How the gateway works and why |
| [qkt docs](https://docs.tryqkt.com) | The engine the gateway serves |
| [mt5-gateway](https://mt5.tryqkt.com) | The same idea for MetaTrader 5 |

</section>
