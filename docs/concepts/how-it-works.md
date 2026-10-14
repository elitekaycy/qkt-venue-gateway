# How it works

One `qkt-venue-gateway` process serves **one account at one venue** over VGP v1. Two clients talk to it:
the qkt daemon (trading) and [guardrails](../connect/guardrails.md) (watching the account and flipping the
kill switch). It talks to the venue through one **adapter**.

Everything a venue does not decide lives in the host and is written once: auth, idempotent submits, the
event journal (stream, seq, replay), the kill switch, reconciliation with the venue, quote refresh, health
and identity. An adapter only translates between the venue's API and a small venue-neutral interface.

```text
qkt daemon ──┐                 ┌──────────────────── qkt-venue-gateway (one per account) ───────────────────┐
             ├── VGP v1 ──────▶│ HTTP/WS server ─ auth ─ kill switch ─ idempotency ─ event journal    │
guardrails ──┘  (bearer)       │        │                        ▲             ▲                       │
                               │        ▼                        │             │                       │
                               │   order router ──────▶ adapter ─┴─ reconciler ┴─ quote hub (refresh)  │
                               └──────────────────────────────│───────────────────────────────────────┘
                                                              ▼
                                                    venue API (Deribit JSON-RPC/WS, …)
```

qkt-insights does not talk to the gateway. It keeps ingesting qkt's own event stream (signals, orders,
fills, equity) as before: `qkt daemon ──telemetry──▶ insights collector`.

## A separate repository

The gateway lives in its own repository, never inside qkt. qkt stays a consumer of VGP, and a gateway can
be replaced, written in another language, or certified on its own. The same protocol is what
[mt5-gateway](https://mt5.tryqkt.com) can speak for MetaTrader 5 accounts.

## Modules

The build enforces the boundaries: an adapter can only see `adapter-api`.

| Module | Owns | Never touches |
|---|---|---|
| `vgp-wire` | the qkt-facing DTOs (decimal strings, error envelope) | venues |
| `adapter-api` | `VenueAdapter` and the neutral types adapters hand the host | HTTP to qkt, the journal |
| `host` | the VGP server: auth, journal, orders, stream, reconciler, kill switch, quotes, bars | any venue API |
| `adapter-testkit` | `AdapterContractTest`: the behaviour every adapter must pass | the host |
| `adapter-deribit` | Deribit, whole: its client, the mapping onto `adapter-api`, the adapter | VGP, the host |
| `adapter-bybit` | Bybit, whole, in the same shape | VGP, the host |
| `adapter-paper` | venue-free matching on Deribit's public prices | VGP, the host |
| `app` | config, choosing the adapter, starting the host | — |

An adapter speaks only its venue's transport; everything qkt sees (HTTP, WebSocket, JSON) is the host's.

## One venue, one module

An adapter module holds everything for its venue, layered by package: a `client` package that speaks the
venue's protocol in the venue's own words and knows nothing of VGP, a mapping that is the only place venue
words become `adapter-api` types, and the adapter itself. When the venue changes a field or an endpoint, the
fix is in `client` and the recorded fixture that breaks names the call; when it changes what a value
means, the fix is in the mapping. A third-party adapter follows the same shape and depends only on
`adapter-api` (and `adapter-testkit` in tests). See [Writing an adapter](../writing-an-adapter.md).

## Goals and non-goals

- **Goals:** every guarantee the [wire spec](../vgp-v1-wire.md) states (decimal strings,
  idempotent POST, by-id lookup with dead ids, exclusive `since`, `reset`, settlements as events, kill
  switch with `reduce_only`, quotes refreshed while they hold); a restart of the gateway, of qkt, or of the
  venue connection loses or doubles nothing; adding a venue is writing one adapter.
- **Non-goals (v1):** more than one account per process; order types beyond market, limit, stop and
  stop-limit with `reduce_only` and GTC, IOC or GTD; venue-native combos; HFT latency (holding periods
  are minutes to weeks).
