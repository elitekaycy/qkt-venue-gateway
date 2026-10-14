# API at a glance

Every route is under `/v1` and takes `Authorization: Bearer <token>`. Prices and quantities are decimal
strings. The full contract is the [wire protocol](../vgp-v1-wire.md).

| Route | Token | Does |
|---|---|---|
| `GET /v1/health` | any | Adapter, account, venue link, kill switch, stream and sequence |
| `GET /v1/account`, `/v1/positions` | any | Balances and margin; open positions |
| `GET /v1/instruments[/{code}]` | any | Listings, including dated contracts expired within 30 days |
| `POST /v1/orders`; `PATCH`, `DELETE /v1/orders/{id}` | trader | Place (idempotent), change, cancel |
| `GET /v1/orders[/{id}]` | any | Open orders placed through this gateway; one order by its client id |
| `POST /v1/positions/close` | trader | Close a position at market; idempotent on an optional `client_order_id` |
| `GET /v1/deals`, `/v1/settlements`, `/v1/bars` | any | Fills, settlements and closed bars, paged |
| `WS /v1/stream?since=<seq>&stream=<id>` | any | The event journal: replayed from `since`, then live; `reset` when `stream` is not the current one |
| `WS /v1/quotes?symbols=…&roots=…` | any | Live quotes by code or option root |
| `POST /v1/kill`, `/v1/kill/release` | guardian | Kill switch |

The wire protocol also defines market-data routes that an adapter serves when it declares the capability:
`/v1/funding`, `/v1/funding-rates`, `/v1/marks`, `/v1/open-interest`, `/v1/trades`, `/v1/liquidations` and
`/v1/depth`. A route whose capability the adapter does not declare answers `501 unsupported`.
`GET /v1/health` lists the capabilities a gateway declares.

## Errors

Every non-2xx response has the body `{"error": {"code": "<code>", "message": "<text>"}}`.

| HTTP | code | Meaning |
|---|---|---|
| 400 | `invalid_request` | Malformed body or field; never retried |
| 401 | `unauthorized` | Bad or missing token |
| 404 | `not_found` | No such order, instrument or root |
| 409 | `conflict` | `client_order_id` reused with a different body |
| 422 | `venue_rejected` | The venue refused the request; `message` is its reason |
| 423 | `kill_switch` | The request adds or changes risk while a kill scope covers its symbol |
| 501 | `unsupported` | The adapter does not serve what was asked (a capability it does not declare) |
| 503 | `venue_unavailable` | The gateway cannot reach the venue; safe to retry reads, and submits (idempotent on `client_order_id`) |

The full contract for each route is in the [wire protocol](../vgp-v1-wire.md).
